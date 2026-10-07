"""Period-wide Clarity numbers for long and custom ranges.

The Data Export API stops at the last 3 days. Microsoft's Clarity MCP server (github.com/microsoft/clarity-mcp-server)
answers dashboard questions over any range with the same Data Export token. It takes plain-language questions and
returns small tables whose column names vary, so results are used defensively: the raw tables go to the model, and
headline numbers are read only when a column is clearly recognisable. Every failure is soft.
"""
from __future__ import annotations

import asyncio
import logging
import re
from datetime import date
from typing import Any

import httpx

from services.projects import ClaritySource
from services.storage import Storage

log = logging.getLogger("dashboard")

DASHBOARD_URL = "https://clarity.microsoft.com/mcp/dashboard/query"

# One focused question each (the endpoint answers single-purpose questions best). {s}/{e} are ISO dates.
QUESTIONS: dict[str, str] = {
    "totals": "Total sessions, distinct users, dead clicks, rage clicks and quick backs, and the number of sessions "
              "with dead clicks, sessions with rage clicks and sessions with quick backs, from {s} to {e}",
    "engagement": "Average active time, average total time and average pages per session from {s} to {e}",
    "daily": "Daily sessions, distinct users, dead clicks and rage clicks from {s} to {e}, one row per day, up to 100 rows",
    "new_vs_returning": "Sessions from new users versus returning users from {s} to {e}",
    "devices": "Sessions by device type and operating system from {s} to {e}",
    "countries": "Top 10 countries by sessions from {s} to {e}",
    "pages": "Top 15 pages or screens by sessions, with their dead clicks, rage clicks and quick backs, from {s} to {e}",
}
MAX_ROWS = 100
CONCURRENCY = 4
_EMAIL = re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")


def _clean(v: Any) -> Any:
    if isinstance(v, str):
        v = _EMAIL.sub("[redacted]", v)
        if "://" in v or v.startswith("/"):
            from services.clarity_ai import sanitize_path  # local import: clarity_ai imports this module
            v = sanitize_path(v)
        return v[:80]
    return v


class DashboardClient:
    def __init__(self, storage: Storage, http: httpx.AsyncClient, timeout: float = 60.0, cache_ttl: int = 6 * 3600) -> None:
        self._storage, self._http, self._timeout, self._ttl = storage, http, timeout, cache_ttl

    async def period(self, source: ClaritySource, start: date, end: date) -> dict[str, Any] | None:
        """{name: {"question": how Clarity read it, "rows": [...]}} for the questions answered, or None."""
        key = f"dash:{source.key}:{start}:{end}"
        cached = await self._storage.cache_get(key, self._ttl)
        if cached is not None:
            return cached
        gate = asyncio.Semaphore(CONCURRENCY)

        async def ask(name: str, question: str) -> tuple[str, dict[str, Any] | None]:
            async with gate:
                return name, await self._ask(source, question.format(s=start.isoformat(), e=end.isoformat()))

        answers = await asyncio.gather(*(ask(n, q) for n, q in QUESTIONS.items()))
        out = {name: a for name, a in answers if a}
        if not out:
            return None
        await self._storage.cache_put(key, out)
        return out

    async def _ask(self, source: ClaritySource, question: str) -> dict[str, Any] | None:
        try:
            r = await self._http.post(DASHBOARD_URL, json={"query": question, "timezone": "UTC"}, timeout=self._timeout,
                                      headers={"Authorization": f"Bearer {source.token}"})
            body = r.json() if r.status_code == 200 else None
        except (httpx.TimeoutException, httpx.TransportError, ValueError) as exc:
            log.warning("Clarity dashboard question failed: %s", type(exc).__name__)
            return None
        if not isinstance(body, dict) or body.get("dataErrorType") not in (0, None) or not isinstance(body.get("data"), list):
            if body is None:
                log.warning("Clarity dashboard returned HTTP %s", r.status_code)
            return None
        rows = [{k: _clean(v) for k, v in row.items()} for row in body["data"][:MAX_ROWS] if isinstance(row, dict)]
        return {"question": str(body.get("query") or question)[:300], "rows": rows}


# ---- headline numbers -------------------------------------------------------------------------------------------

def _num(v: Any) -> float | None:
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return f if f == f else None


def _pick(row: dict[str, Any], *, has: tuple[str, ...], lacks: tuple[str, ...] = ()) -> float | None:
    """The first numeric column whose lower-cased name contains all of `has` and none of `lacks`."""
    for k, v in row.items():
        name = k.lower()
        if all(h in name for h in has) and not any(x in name for x in lacks):
            n = _num(v)
            if n is not None:
                return n
    return None


def history_kpis(history: dict[str, Any]) -> dict[str, Any]:
    """KPI overrides read from period-wide answers. Only fields that were confidently recognised are returned."""
    out: dict[str, Any] = {}
    totals = (history.get("totals") or {}).get("rows") or []
    if totals:
        t = totals[0]
        sessions = _pick(t, has=("session",), lacks=("dead", "rage", "quick", "with", "bot"))
        if sessions:
            out["total_sessions"] = int(sessions)
            if (users := _pick(t, has=("user",))) is not None:
                out["total_users"] = int(users)
            if (dead := _pick(t, has=("dead",), lacks=("session",))) is not None:
                out["dead_click_count"] = int(dead)
            if (rage := _pick(t, has=("rage",), lacks=("session",))) is not None:
                out["rage_click_count"] = int(rage)
            pct = lambda n: round(min(100.0, 100.0 * n / sessions), 1)  # noqa: E731
            dead_s = _pick(t, has=("session", "dead"))
            rage_s = _pick(t, has=("session", "rage"))
            quick_s = _pick(t, has=("session", "quick"))
            if dead_s is not None:
                out["dead_click_session_pct"] = pct(dead_s)
            if rage_s is not None:
                out["rage_session_pct"] = pct(rage_s)
            if quick_s is not None:
                out["quickback_session_pct"] = pct(quick_s)
            if dead_s is not None or rage_s is not None:
                out["frustrated_session_pct"] = pct(max(dead_s or 0, rage_s or 0))
    eng = (history.get("engagement") or {}).get("rows") or []
    if eng:
        e = eng[0]
        scale = lambda k: 60 if "minute" in k else 1  # noqa: E731
        for k, v in e.items():
            name, n = k.lower(), _num(v)
            if n is None:
                continue
            if "active" in name:
                out["engaged_seconds"] = round(n * scale(name))
            elif "total" in name and ("time" in name or "duration" in name):
                out["session_seconds"] = round(n * scale(name))
            elif ("page" in name or "screen" in name) and "per" in name:
                out["views_per_session"] = round(n, 1)
    return out
