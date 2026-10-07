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
from datetime import datetime, timezone
from typing import Any

import httpx

from services.projects import ClaritySource
from services.storage import Storage

log = logging.getLogger("recordings")

RECORDINGS_URL = "https://clarity.microsoft.com/mcp/recordings/sample"
SORT_NEWEST, SORT_MOST_TAPS = 0, 5

# Which sessions to review: the ones that show trouble first, then the most active ones for context.
SAMPLES: list[tuple[str, dict[str, Any], int, int]] = [
    ("rage taps", {"rageClickPresent": True}, 4, SORT_NEWEST),
    ("dead taps", {"deadClickPresent": True}, 4, SORT_NEWEST),
    ("quick backs", {"quickbackClickPresent": True}, 3, SORT_NEWEST),
    ("most active", {}, 3, SORT_MOST_TAPS),
]
MAX_SESSIONS = 10
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


class RecordingsClient:
    def __init__(self, storage: Storage, http: httpx.AsyncClient, timeout: float = 45.0, cache_ttl: int = 3600) -> None:
        self._storage, self._http, self._timeout, self._ttl = storage, http, timeout, cache_ttl

    async def sample(self, source: ClaritySource, start: datetime, end: datetime) -> list[dict[str, Any]]:
        """Up to MAX_SESSIONS condensed recordings for the period. Empty list if Clarity can't provide them."""
        key = f"rec:{source.key}:{start.date()}:{end.date()}"
        cached = await self._storage.cache_get(key, self._ttl)
        if cached is not None:
            return cached
        results = await asyncio.gather(*(self._fetch(source, start, end, f, n, sort) for _, f, n, sort in SAMPLES))
        sessions: list[dict[str, Any]] = []
        seen: set[str] = set()
        for (why, *_), batch in zip(SAMPLES, results):
            for s in batch:
                link = str(s.get("link") or "")
                if not link or link in seen or not s.get("timeline"):
                    continue
                seen.add(link)
                sessions.append(condense(s, why))
        sessions = sessions[:MAX_SESSIONS]
        if sessions:
            await self._storage.cache_put(key, sessions)
        return sessions

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
