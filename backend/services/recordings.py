"""Real session recordings from Microsoft Clarity, condensed into journeys the AI can read.

Clarity's Data Export API only returns aggregates. Microsoft's Clarity MCP server
(github.com/microsoft/clarity-mcp-server) exposes a recordings endpoint that accepts the same Data Export token and
returns, per session, a replay link and a timeline of screens and taps (dead and rage taps marked). The endpoint is
official but undocumented, so every failure here is soft: the audit continues on metrics alone.
"""
from __future__ import annotations

import asyncio
import json
import logging
import re
from collections import Counter
from datetime import datetime, timezone
from typing import Any

import httpx

from services.projects import ClaritySource
from services.storage import Storage

log = logging.getLogger("recordings")

RECORDINGS_URL = "https://clarity.microsoft.com/mcp/recordings/sample"
SORT_NEWEST, SORT_MOST_TAPS = 0, 5

# Which sessions to sample: trouble first, then drop-offs and the most engaged users for contrast.
SAMPLES: list[tuple[str, dict[str, Any], int]] = [
    ("rage taps", {"rageClickPresent": True}, SORT_NEWEST),
    ("dead taps", {"deadClickPresent": True}, SORT_NEWEST),
    ("quick backs", {"quickbackClickPresent": True}, SORT_NEWEST),
    ("left within a minute", {"sessionDuration": {"min": None, "max": 1}}, SORT_NEWEST),
    ("most active", {}, SORT_MOST_TAPS),
]
# Sessions fetched per category. Long ranges can hold thousands of sessions; a few dozen per category is enough to
# see patterns, and every category is one request, so cost stays flat however large the range is.
PER_CATEGORY_SHORT, PER_CATEGORY_LONG = 8, 25
MAX_DETAILED = 12  # full journeys sent to the model; the rest only feed the patterns
MAX_SESSIONS = MAX_DETAILED
MAX_EVENTS_PER_SESSION = 45
EVENT_NAMES = {"click": "tap", "dead click": "dead tap", "rage clicks": "rage taps", "rage click": "rage taps"}
_PRIVATE_USE = re.compile(r"[-]+")
_EMAIL = re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")


def _iso(t: datetime) -> str:
    return t.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _label(text: Any) -> str:
    """Tapped element text, without icon-font glyphs or e-mail addresses (Clarity already masks digits)."""
    s = _PRIVATE_USE.sub("[icon]", str(text or "")).strip()
    s = _EMAIL.sub("[redacted]", re.sub(r"\s+", " ", s))
    return s[:40] or "[unlabelled]"


def condense(session: dict[str, Any], why: str) -> dict[str, Any]:
    """One recording as a compact journey: screens in order with what the user tapped, repeats collapsed."""
    journey: list[dict[str, Any]] = []
    kept = omitted = 0
    for page in session.get("timeline") or []:
        screen = str(page.get("displayTitle") or page.get("url") or "(screen)")[:60]
        events: list[str] = []
        last, repeat = None, 0
        for e in page.get("timelineEvents") or []:
            kind = EVENT_NAMES.get(str(e.get("eventtype", "")).lower(), str(e.get("eventtype", "event")).lower())
            item = f"{kind} '{_label(e.get('text'))}'"
            if item == last:  # same action repeated: "tap 'Back' ×3"
                repeat += 1
                events[-1] = f"{item} ×{repeat + 1}"
            elif kept < MAX_EVENTS_PER_SESSION or kind != "tap":  # never drop dead/rage taps
                events.append(item)
                kept += 1
                last, repeat = item, 0
            else:
                omitted += 1
                last, repeat = None, 0
        journey.append({"screen": screen, "at": page.get("start"), "events": events})
    # Screens where nothing happened add noise; keep them only if that's all there is.
    journey = [p for p in journey if p["events"]] or journey[:1]
    out = {
        "selected_for": why,
        "started": session.get("timestamp"),
        "active_time": session.get("activeDuration"),
        "screens_visited": session.get("pagesCount"),
        "taps": session.get("sessionClickCount"),
        "replay": session.get("link"),
        "journey": journey,
    }
    if omitted:
        out["events_omitted"] = omitted
    return out


def _seconds(text: Any) -> int | None:
    """'04 minutes and 18 seconds' -> 258."""
    parts = dict((unit, int(n)) for n, unit in re.findall(r"(\d+)\s*(hour|minute|second)", str(text or "")))
    return parts.get("hour", 0) * 3600 + parts.get("minute", 0) * 60 + parts.get("second", 0) if parts else None


def _events(s: dict[str, Any]) -> list[tuple[str, str]]:
    return [(EVENT_NAMES.get(str(e.get("eventtype", "")).lower(), str(e.get("eventtype", "")).lower()), _label(e.get("text")))
            for p in s.get("timeline") or [] for e in p.get("timelineEvents") or []]


def _trouble(s: dict[str, Any]) -> int:
    return sum(3 if k == "rage taps" else 1 for k, _ in _events(s) if k in ("dead tap", "rage taps"))


def patterns(sessions: list[dict[str, Any]]) -> dict[str, Any]:
    """What recurs across every sampled session, so many sessions cost a few hundred tokens instead of megabytes."""
    def top(kind: str, n: int = 8) -> list[dict[str, Any]]:
        # Counted once per session, so one user hammering a button doesn't outweigh many users hitting it.
        per_session = Counter(label for s in sessions for label in {lbl for k, lbl in _events(s) if k == kind})
        return [{"element": lbl, "sessions": c} for lbl, c in per_session.most_common(n)]

    durations = sorted(d for d in (_seconds(s.get("activeDuration")) for s in sessions) if d is not None)
    last_screens = Counter(str((s.get("timeline") or [{}])[-1].get("displayTitle") or "(screen)")[:60] for s in sessions)
    days = sorted({str(s.get("timestamp", ""))[:10] for s in sessions if s.get("timestamp")})
    return {
        "most_dead_tapped": top("dead tap"),
        "most_rage_tapped": top("rage taps"),
        "most_tapped": top("tap"),
        "sessions_with_dead_taps": sum(1 for s in sessions if any(k == "dead tap" for k, _ in _events(s))),
        "sessions_with_rage_taps": sum(1 for s in sessions if any(k == "rage taps" for k, _ in _events(s))),
        "median_active_seconds": durations[len(durations) // 2] if durations else None,
        "last_screen_seen": [{"screen": k, "sessions": v} for k, v in last_screens.most_common(5)],
        "days_spanned": {"first": days[0], "last": days[-1], "distinct_days": len(days)} if days else None,
    }


def select(tagged: list[tuple[str, dict[str, Any]]]) -> list[tuple[str, dict[str, Any]]]:
    """Pick MAX_DETAILED sessions: round-robin across categories, worst trouble first, avoiding repeats of a day so the
    detail is spread over the period rather than one bad afternoon."""
    queues: dict[str, list[dict[str, Any]]] = {}
    for why, s in tagged:
        queues.setdefault(why, []).append(s)
    for q in queues.values():
        q.sort(key=_trouble, reverse=True)
    picked: list[tuple[str, dict[str, Any]]] = []
    days_used: Counter[str] = Counter()
    while len(picked) < MAX_DETAILED and any(queues.values()):
        for why, q in queues.items():
            if not q or len(picked) >= MAX_DETAILED:
                continue
            # Prefer the most troubled session from a day not yet represented as often.
            i = min(range(len(q)), key=lambda j: (days_used[str(q[j].get("timestamp", ""))[:10]], -_trouble(q[j]), j))
            s = q.pop(i)
            days_used[str(s.get("timestamp", ""))[:10]] += 1
            picked.append((why, s))
    return picked


class RecordingsClient:
    def __init__(self, storage: Storage, http: httpx.AsyncClient, timeout: float = 45.0, cache_ttl: int = 3600) -> None:
        self._storage, self._http, self._timeout, self._ttl = storage, http, timeout, cache_ttl

    async def sample(self, source: ClaritySource, start: datetime, end: datetime) -> dict[str, Any] | None:
        """A bounded sample of real recordings for the period: patterns across all sampled sessions plus up to
        MAX_DETAILED full journeys. None if Clarity provides no recordings."""
        key = f"rec:{source.key}:{start.date()}:{end.date()}"
        cached = await self._storage.cache_get(key, self._ttl)
        if cached is not None:
            return cached
        per = PER_CATEGORY_LONG if (end - start).days > 3 else PER_CATEGORY_SHORT
        results = await asyncio.gather(*(self._fetch(source, start, end, f, per, sort) for _, f, sort in SAMPLES))
        tagged: list[tuple[str, dict[str, Any]]] = []
        seen: set[str] = set()
        for (why, *_), batch in zip(SAMPLES, results):
            for s in batch:
                link = str(s.get("link") or "")
                if link and link not in seen and s.get("timeline"):
                    seen.add(link)
                    tagged.append((why, s))
        if not tagged:
            return None
        out = {
            "sampled_sessions": len(tagged),
            "patterns": patterns([s for _, s in tagged]),
            "sessions": [condense(s, why) for why, s in select(tagged)],
        }
        await self._storage.cache_put(key, out)
        return out

    async def _fetch(self, source: ClaritySource, start: datetime, end: datetime,
                     filters: dict[str, Any], count: int, sort: int) -> list[dict[str, Any]]:
        body = {
            "sortBy": sort, "start": _iso(start), "end": _iso(end), "count": count,
            "filters": {"date": {"start": _iso(start), "end": _iso(end)}, **filters},
        }
        try:
            r = await self._http.post(RECORDINGS_URL, json=body, timeout=self._timeout,
                                      headers={"Authorization": f"Bearer {source.token}"})
        except (httpx.TimeoutException, httpx.TransportError) as exc:
            log.warning("Clarity recordings request failed: %s", type(exc).__name__)
            return []
        if r.status_code != 200:
            log.warning("Clarity recordings returned HTTP %s", r.status_code)
            return []
        try:
            data = r.json()
        except json.JSONDecodeError:
            return []
        return [s for s in data if isinstance(s, dict)] if isinstance(data, list) else []
