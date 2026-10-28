"""Funnel drop-off: how many sessions get through each step of a funnel, and why people stop at the worst step.

Clarity's APIs don't expose the funnels configured in its dashboard (nor app versions), but the recordings endpoint
filters sessions by smart/custom event. A funnel is therefore a list of event names, in order, kept by the app per
project. A session reaches step k when it fired step k's event and reached step k-1; the sessions that reached a step
but never fired the next one dropped off there. Up to MAX_DROPPED of those are read in full and Claude names the top
reasons, each backed by replay links. Event order inside a session isn't available, so steps are matched by presence.

The endpoint silently ignores event names it doesn't know and returns every session instead, and it only knows Clarity's
smart events: custom events sent with the SDK's sendCustomEvent (and dashboard funnels) aren't exposed. Each step is
therefore compared with a request for a name that can't exist; a step returning the same sessions isn't visible to the
API, and the funnel is reported as unavailable rather than with counts that mean nothing.
"""
from __future__ import annotations

import asyncio
import json
import logging
import re
from datetime import date, datetime, timezone
from typing import Any

import anthropic

from services.dashboard import DashboardClient
from services.projects import ClaritySource
from services.recordings import SORT_NEWEST, RecordingsClient, _trouble, condense, retime
from services.storage import Storage

log = logging.getLogger("funnel")

PER_STEP = 250  # the endpoint's maximum sample per request
MAX_DROPPED = 8  # drop-off sessions read in full
MAX_STEPS = 8
MAX_REASON_TOKENS = 2000
PROBE_EVENT = "clarifiai-no-such-event-7f3a"  # a name no project uses: what the endpoint returns for unknown events
# Clarity's automatic catch-all and blank names aren't steps anyone defines.
NOT_EVENTS = {"", "other"}

REASONS_SYSTEM = (
    "You are a senior product manager reviewing real user sessions from Microsoft Clarity. Answer only with JSON.")
REASONS_PROMPT = """Users in this app's funnel "{funnel}" reached the step "{reached}" but never fired "{missed}".
{dropped} of {reached_count} sessions that reached "{reached}" dropped off here.
Below are {n} of those sessions read in full (id, replay URL, and each screen with what the user tapped; events carry
their time in the replay as mm:ss where Clarity provides it; dead and rage taps mark taps that did nothing).

Find the top 3 reasons these users didn't complete the step, most common first. Base every reason on what the sessions
show; when a reason is an inference, say so. Return JSON only, in this shape:
{{"reasons": [{{"title": "short reason, 3-9 words",
               "explanation": "2-3 sentences of what users did and why it stops them, with proof links",
               "sessions": number of the sessions above that show this reason}}]}}
Proof links are Markdown links whose text is the session id and the moment, e.g. [D3 at 04:11](replay URL of D3); use a
time only if that event shows one, otherwise link the session alone, e.g. [D3](replay URL). Every explanation needs at
least one proof link.

Sessions:
{sessions}"""


def _stamp(session: dict[str, Any]) -> str:
    return str(session.get("timestamp") or "")


def reach(samples: list[list[dict[str, Any]]], capped: list[bool]) -> tuple[list[set[str]], dict[str, dict[str, Any]], str]:
    """Sessions reaching each step, every session seen by link, and the earliest start all samples cover.

    A capped sample (PER_STEP newest sessions) only covers back to its oldest session; before that, a session missing
    from it may simply be older, not a drop-off. So every step is compared only from the latest such horizon on.
    """
    horizon = max((min(_stamp(s) for s in sample) for sample, cut in zip(samples, capped) if cut and sample), default="")
    by_link: dict[str, dict[str, Any]] = {}
    fired: list[set[str]] = []
    for sample in samples:
        links = set()
        for s in sample:
            link = str(s.get("link") or "")
            if link and _stamp(s) >= horizon:
                links.add(link)
                by_link.setdefault(link, s)
        fired.append(links)
    reached: list[set[str]] = []
    for i, links in enumerate(fired):
        reached.append(links if i == 0 else reached[-1] & links)
    return reached, by_link, horizon


def _json(text: str) -> dict[str, Any] | None:
    m = re.search(r"\{.*\}", text, re.S)
    try:
        return json.loads(m.group(0)) if m else None
    except json.JSONDecodeError:
        return None


class FunnelService:
    def __init__(self, storage: Storage, recordings: RecordingsClient, dashboard: DashboardClient | None,
                 claude: Any, cache_ttl: int = 3600) -> None:
        self._storage, self._recordings, self._dashboard, self._claude = storage, recordings, dashboard, claude
        self._ttl = cache_ttl
        self._slots = asyncio.Semaphore(2)  # Clarity rate-limits bursts (HTTP 429)

    async def events(self, source: ClaritySource, start: date, end: date) -> list[dict[str, Any]]:
        """Event names Clarity recorded for the period, most sessions first: the choices for funnel steps."""
        if self._dashboard is None:
            return []
        answer = await self._dashboard.ask(
            source, f"Custom events with unique session counts between {start:%Y/%m/%d} and {end:%Y/%m/%d}")
        out = []
        for row in (answer or {}).get("rows") or []:
            name = next((v for k, v in row.items() if "event" in k.lower() and isinstance(v, str)), None)
            count = next((v for k, v in row.items() if "count" in k.lower() and isinstance(v, (int, float))), None)
            if name is not None and name.strip().lower() not in NOT_EVENTS:
                out.append({"name": name.strip(), "sessions": int(count or 0)})
        return out

    async def analyze(self, source: ClaritySource, start: datetime, end: datetime, name: str,
                      steps: list[str]) -> dict[str, Any]:
        steps = [s.strip() for s in steps if s.strip()][:MAX_STEPS]
        key = f"funnel:{source.key}:{start.date()}:{end.date()}:" + "|".join(steps)
        cached = await self._storage.cache_get(key, self._ttl)
        if cached is not None:
            return cached

        async def sample(event: str) -> list[dict[str, Any]]:
            async with self._slots:
                return await self._recordings._fetch(source, start, end, {"smartEvents": [event]}, PER_STEP, SORT_NEWEST)

        *samples, ignored = await asyncio.gather(*(sample(e) for e in [*steps, PROBE_EVENT]))
        unknown_links = {str(s.get("link")) for s in ignored}
        unseen = [e for e, sm in zip(steps, samples) if {str(s.get("link")) for s in sm} == unknown_links]
        if unseen:
            out = {"name": name, "steps": [], "since": None, "drop_off": None, "reasons": [], "sessions": [],
                   "unavailable": unseen}
            await self._storage.cache_put(key, out)
            return out
        reached, by_link, horizon = reach(samples, [len(s) >= PER_STEP for s in samples])
        out: dict[str, Any] = {
            "name": name, "steps": [{"name": e, "sessions": len(r)} for e, r in zip(steps, reached)],
            "since": horizon or None, "drop_off": None, "reasons": [], "sessions": [], "unavailable": [],
        }
        drops = [(i, reached[i] - reached[i + 1]) for i in range(len(steps) - 1)]
        i, dropped = max(drops, key=lambda d: len(d[1]), default=(0, set()))
        if not dropped:
            await self._storage.cache_put(key, out)
            return out
        out["drop_off"] = {"from": steps[i], "to": steps[i + 1], "reached": len(reached[i]), "dropped": len(dropped),
                           "rate": round(len(dropped) / len(reached[i]), 4)}
        picked = sorted((by_link[link] for link in dropped), key=lambda s: (_trouble(s), _stamp(s)), reverse=True)
        picked = [s for s in picked if s.get("timeline")][:MAX_DROPPED]
        alone = await asyncio.gather(*(self._recordings._alone(source, s) for s in picked))
        sessions = [condense(retime(s, a), f"reached {steps[i]}, never {steps[i + 1]}", f"D{n}")
                    for n, (s, a) in enumerate(zip(picked, alone), 1)]
        out["sessions"] = sessions
        out["reasons"] = await self._reasons(name, out["drop_off"], sessions)
        await self._storage.cache_put(key, out)
        return out

    async def _reasons(self, name: str, drop: dict[str, Any], sessions: list[dict[str, Any]]) -> list[dict[str, Any]]:
        prompt = REASONS_PROMPT.format(
            funnel=name, reached=drop["from"], missed=drop["to"], dropped=drop["dropped"],
            reached_count=drop["reached"], n=len(sessions), sessions=json.dumps(sessions, indent=1, ensure_ascii=False))
        try:
            text = await self._claude.complete(REASONS_SYSTEM, prompt, MAX_REASON_TOKENS)
        except anthropic.APIError:
            log.warning("Funnel reasons unavailable", exc_info=True)
            return []
        parsed = _json(text) or {}
        reasons = []
        for r in (parsed.get("reasons") or [])[:3]:
            if isinstance(r, dict) and r.get("title") and r.get("explanation"):
                reasons.append({"title": str(r["title"])[:120], "explanation": str(r["explanation"])[:1500],
                                "sessions": int(r["sessions"]) if isinstance(r.get("sessions"), int) else None})
        return reasons


def utc_range(start: date, end: date, today: date) -> tuple[datetime, datetime]:
    start_dt = datetime.combine(start, datetime.min.time(), tzinfo=timezone.utc)
    end_dt = datetime.now(timezone.utc) if end >= today else datetime.combine(end, datetime.max.time(), tzinfo=timezone.utc)
    return start_dt, end_dt
