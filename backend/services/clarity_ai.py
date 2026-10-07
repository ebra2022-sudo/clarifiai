"""Clarity data fetcher, sanitizer, KPI engine and Claude invocation."""
from __future__ import annotations

import asyncio
import base64
import json
import logging
import re
from collections import Counter
from contextlib import aclosing
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from typing import Any, AsyncIterator
from urllib.parse import urlsplit

import anthropic
import httpx

from config import Settings
from models import AuditRequest, Timeframe, image_media_type
from services.projects import ClaritySource, TokenError, TokenVault
from services.dashboard import DashboardClient, history_kpis
from services.recordings import RecordingsClient
from services.storage import Storage
from services.tiers import TierPolicy, required_tier

log = logging.getLogger("clarity_ai")

PROMPT_TEMPLATE = """You are a Principal Product Manager and UX Researcher. You are given sanitized Microsoft Clarity behaviour data for one product and, when attached, frames from real user session recordings. Write a concise, non-conversational report for a product manager.

Focus on users, not code: what people were trying to do, where they hesitated, got stuck or gave up, and which journeys work. Treat rage/dead taps and errors as evidence of user frustration, not as engineering tasks; mention implementation detail only when it is the whole point. Ground every claim in the data or the recording frames, name the screen or page and the numbers, and say plainly when the data is too thin to conclude something.

[INSERT SANITIZED CLARITY JSON HERE]

Format the output strictly in Markdown with these specific headers:

### 🚨 Immediate Hotfixes (High Frustration)
- [The few user-facing problems to fix first: where it happens, what the user experiences, the evidence, and the product fix]

### 📉 Friction Trends & User Drop-off
- [How users move through the product: engagement, depth, where journeys stall or users leave, which audiences struggle, and what the session recordings show]

### 🚀 Strategic Next-Sprint Product Roadmap
- [Prioritized product bets for activation, engagement and retention, each tied to the evidence above and how to measure it]
"""

SYSTEM_PROMPT = "Respond only with the requested Markdown report. No preamble, no closing remarks."
ROADMAP_MARKER = "### 🚀"
SNAPSHOT_GRACE_HOURS = 3
RECORDINGS_WAIT_SECONDS = 50
DASHBOARD_WAIT_SECONDS = 75


# Tells the model what it did NOT see, so it doesn't claim to have watched sessions. Clarity's export API returns
# aggregated metrics only; recordings and heatmaps stay in the Clarity dashboard (the app links to them).
DATA_SCOPE = ("Aggregated Microsoft Clarity Data Export metrics only. Session recordings, heatmaps and "
              "element-level click targets are not available to this analysis. Where a finding needs visual "
              "confirmation, name the page or screen whose recordings the team should review in Clarity.")
PERIOD_HISTORY_SCOPE = (
    "period_history holds Clarity dashboard answers covering the whole requested period (totals, daily trend, new vs "
    "returning users, devices, countries, top pages); each has the question as Clarity interpreted it. Prefer these for "
    "period-wide statements and trends; totals and top_friction_targets may cover fewer days (see data_notes).")
DATA_SCOPE_WITH_SESSIONS = (
    "Aggregated Microsoft Clarity metrics plus {n} real session recordings sampled across the period from Clarity "
    "(sessions with rage taps, dead taps, quick backs, early exits, and the most active users). "
    "session_recordings.patterns summarises all {n}: the elements most often dead- or rage-tapped (counted per "
    "session), the most tapped elements, where sessions end, and typical active time. session_recordings.sessions has "
    "{d} of them in full: the screens a user saw and what they tapped, in order, with dead and rage taps marked. Use "
    "the patterns for how common something is and the journeys for concrete user stories; cite a session by linking "
    "its replay URL as a Markdown link. Heatmaps are not available.")
DATA_SCOPE_WITH_RECORDINGS = (
    "Aggregated Microsoft Clarity Data Export metrics, plus {n} frame(s) from real user session recordings or "
    "screenshots, attached as images in playback order. The frames show what users actually saw and did; use them "
    "to explain the metrics, and say which observations come from the frames. Heatmaps and element-level click "
    "targets are not available.")


def data_scope(frames: int, sampled: int, detailed: int, history: bool) -> str:
    """What the model can and can't see, so it neither under-uses nor invents evidence."""
    parts = []
    if history:
        parts.append(PERIOD_HISTORY_SCOPE)
    if sampled:
        parts.append(DATA_SCOPE_WITH_SESSIONS.format(n=sampled, d=detailed))
    if frames:
        parts.append(DATA_SCOPE_WITH_RECORDINGS.format(n=frames) if not sampled else
                     f"{frames} frame(s) the user attached from recordings or screenshots are also included as images.")
    return " ".join(parts) if parts else DATA_SCOPE


class AuditError(Exception):
    def __init__(self, status: int, code: str, message: str, required_tier: str | None = None) -> None:
        super().__init__(message)
        self.status, self.code, self.message, self.required_tier = status, code, message, required_tier


def utc_today() -> date:
    return datetime.now(timezone.utc).date()


# --------------------------------------------------------------------------
# Normalisation helpers
# --------------------------------------------------------------------------
TRACKED_METRICS = {
    "Traffic", "DeadClickCount", "RageClickCount", "QuickbackClick",
    "ExcessiveScroll", "ScriptErrorCount", "ErrorClickCount",
}
FRICTION_METRICS = TRACKED_METRICS - {"Traffic"}

# Clarity's mobile-app SDK reports taps and app errors under different names. Map them onto the web metrics so the
# KPI engine works for both; the payload's `platform` tells Claude which one it is looking at.
MOBILE_METRICS = {
    "DeadTapCount": "DeadClickCount",
    "RageTapCount": "RageClickCount",
    "ApplicationErrorCount": "ScriptErrorCount",
}

# Aggregate {name, sessionsCount} blocks that come with every response. Kept as audience context for Claude.
# (Web "PopularPages" is left out: it carries full URLs, and per-page friction is already covered by the URL rows.)
CONTEXT_BLOCKS = {
    "Device": "devices", "OS": "operating_systems", "Browser": "browsers",
    "Country": "countries", "CountryRegion": "countries", "Country/Region": "countries",
    "PopularScreens": "popular_screens",
    "ReferrerUrl": "referrers", "Referrer": "referrers", "PageTitle": "page_titles",
}
CTX = "ctx:"  # key prefix for context blocks inside a normalised payload
MOBILE_MARKER = CTX + "Platform"


def _float(v: Any) -> float:
    try:
        f = float(v)
    except (TypeError, ValueError):
        return 0.0
    return f if f == f and f not in (float("inf"), float("-inf")) else 0.0


def _int(v: Any) -> int:
    try:
        return int(float(v))
    except (TypeError, ValueError):
        return 0


def _dim_value(info: dict[str, Any], dimension: str) -> str:
    target = dimension.lower()
    for k, v in info.items():
        if k.lower() == target and v not in (None, ""):
            return str(v)
    return "(all)"


def _add(rows: dict[str, dict[str, int]], dim: str, vals: dict[str, int]) -> None:
    dest = rows.setdefault(dim, {})
    for k, v in vals.items():
        dest[k] = dest.get(k, 0) + v


def normalize(raw: list[dict[str, Any]], dimension: str) -> dict[str, dict[str, dict[str, int]]]:
    """Clarity response -> {metric: {dimension_value: {numeric fields}}}. Whitelist only."""
    out: dict[str, dict[str, dict[str, int]]] = {}
    for block in raw:
        # Responses use "DeadClickCount"; Microsoft's docs spell it "Dead Click Count". Accept both.
        name = str(block.get("metricName") or "").replace(" ", "")
        infos = [i for i in block.get("information") or [] if isinstance(i, dict)]
        if name in MOBILE_METRICS:
            out.setdefault(MOBILE_MARKER, {"mobile_app": {"seen": 1}})
            name = MOBILE_METRICS[name]
        if name in CONTEXT_BLOCKS or name in ("EngagementTime", "ScrollDepth"):
            _add_context(out, name, infos)
            continue
        if name not in TRACKED_METRICS:
            continue
        rows = out.setdefault(name, {})
        for info in infos:
            if not isinstance(info, dict):
                continue
            dim = _dim_value(info, dimension)
            if name == "Traffic":
                sessions = _int(info.get("totalSessionCount"))
                # Views per session arrives as an average ("pagesPerSessionPercentage" on the web,
                # "screensPerSessionPercentage" in apps). Store it weighted by sessions so days and rows merge.
                per_session = _float(info.get("pagesPerSessionPercentage") or info.get("screensPerSessionPercentage"))
                _add(rows, dim, {
                    "sessions": sessions,
                    "users": _int(info.get("distinctUserCount") or info.get("distantUserCount")),
                    "bot_sessions": _int(info.get("totalBotSessionCount")),
                    "views_x100": round(per_session * sessions * 100),
                })
            else:
                _add(rows, dim, {
                    "sessions": _affected_sessions(info),
                    "pageviews": _int(info.get("pagesViews") or info.get("pageViews") or info.get("screensViews")),
                    "total": _int(info.get("subTotal")),
                })
    return out


def _affected_sessions(info: dict[str, Any]) -> int:
    """Sessions that hit the metric.

    In friction blocks `sessionsCount` is the total for that row and `sessionsWithMetricPercentage` the share
    affected (e.g. 25 sessions at 8% = 2 sessions with dead taps). Older payloads without the percentage are
    taken at face value.
    """
    total = _int(info.get("sessionsCount"))
    pct = info.get("sessionsWithMetricPercentage")
    if pct is None:
        return total
    try:
        return round(total * float(pct) / 100)
    except (TypeError, ValueError):
        return total


def _add_context(out: dict[str, Any], name: str, infos: list[dict[str, Any]]) -> None:
    if name == "EngagementTime":
        # Per-session averages (seconds). "samples" counts the readings so merged days average, not add up.
        for info in infos:
            _add(out.setdefault(CTX + name, {}), "(all)",
                 {"total_time": _int(info.get("totalTime")), "active_time": _int(info.get("activeTime")), "samples": 1})
        return
    if name == "ScrollDepth":
        for info in infos:
            depth = next((_float(v) for k, v in info.items() if "scroll" in k.lower()), 0.0)
            if depth:
                _add(out.setdefault(CTX + name, {}), "(all)", {"depth_x100": round(depth * 100), "samples": 1})
        return
    rows = out.setdefault(CTX + CONTEXT_BLOCKS[name], {})
    for info in infos:
        raw = str(info.get("name") or "").strip()
        # Referrers: keep only the host, never paths or query strings.
        label = (urlsplit(raw if "://" in raw else "//" + raw).hostname or "") if name in ("ReferrerUrl", "Referrer") else raw
        label = label[:60]
        if label:
            _add(rows, label, {"sessions": _int(info.get("sessionsCount"))})


def is_mobile(norm: dict[str, Any]) -> bool:
    return MOBILE_MARKER in norm


def audience(norm: dict[str, Any], limit: int = 8) -> dict[str, Any]:
    """Aggregate context (top screens, devices, OS, countries, engagement) with names scrubbed of PII."""
    out: dict[str, Any] = {}
    for key, rows in norm.items():
        if not key.startswith(CTX) or key in (MOBILE_MARKER, CTX + "EngagementTime", CTX + "ScrollDepth"):
            continue
        ranked = sorted(rows.items(), key=lambda kv: kv[1].get("sessions", 0), reverse=True)[:limit]
        out[key[len(CTX):]] = [{"name": scrub_text(n), "sessions": v.get("sessions", 0)} for n, v in ranked]
    return out


def merge(norms: list[dict[str, Any]]) -> dict[str, dict[str, dict[str, int]]]:
    out: dict[str, dict[str, dict[str, int]]] = {}
    for n in norms:
        for metric, rows in n.items():
            dest = out.setdefault(metric, {})
            for dim, vals in rows.items():
                _add(dest, dim, vals)
    return out


# --------------------------------------------------------------------------
# Sanitisation (strip every identifying value before the LLM sees anything)
# --------------------------------------------------------------------------
_ID_SEGMENT = re.compile(
    r"^(?:\d{3,}|[0-9a-fA-F]{8}-[0-9a-fA-F-]{27}|[0-9a-fA-F]{16,}|[^/]*@[^/]*|[A-Za-z0-9_\-]{24,})$"
)
_EMAIL = re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")
_IPV4 = re.compile(r"\b\d{1,3}(?:\.\d{1,3}){3}\b")


def sanitize_path(url: str) -> str:
    """Keep only a generic path: host, query string, fragment dropped; ID-like segments -> ':id'."""
    if "://" not in url and not url.startswith("/"):
        url = "//" + url
    path = urlsplit(url).path or "/"
    segs = [":id" if _ID_SEGMENT.match(s) else s[:40] for s in path.split("/")]
    return "/".join(segs) or "/"


def scrub_text(s: str) -> str:
    return _IPV4.sub("[redacted]", _EMAIL.sub("[redacted]", s))


# --------------------------------------------------------------------------
# KPI + friction ranking
# --------------------------------------------------------------------------
HEALTH_WEIGHTS = {  # each rate saturates at 10% of sessions
    "RageClickCount": 30, "DeadClickCount": 25, "QuickbackClick": 15,
    "ExcessiveScroll": 10, "ErrorClickCount": 10, "ScriptErrorCount": 10,
}


def compute_kpis(norm: dict[str, Any]) -> dict[str, Any]:
    def s(metric: str, fld: str) -> int:
        return sum(r.get(fld, 0) for r in norm.get(metric, {}).values())

    sessions = s("Traffic", "sessions")
    if sessions <= 0:
        sessions = max([s(m, "sessions") for m in norm if m in FRICTION_METRICS] or [0])

    def rate(metric: str) -> float:
        return min(1.0, s(metric, "sessions") / sessions) if sessions else 0.0

    penalty = sum(w * min(1.0, rate(m) / 0.10) for m, w in HEALTH_WEIGHTS.items())
    traffic_sessions = s("Traffic", "sessions")
    eng = norm.get(CTX + "EngagementTime", {}).get("(all)", {})
    eng_samples = max(1, eng.get("samples", 1))
    scroll = norm.get(CTX + "ScrollDepth", {}).get("(all)", {})
    return {
        "health_score": max(0, min(100, round(100 - penalty))),
        "total_sessions": sessions,
        "rage_click_count": s("RageClickCount", "total"),
        "rage_session_pct": round(rate("RageClickCount") * 100, 1),
        "dead_click_count": s("DeadClickCount", "total"),
        "dead_click_session_pct": round(rate("DeadClickCount") * 100, 1),
        "quickback_session_pct": round(rate("QuickbackClick") * 100, 1),
        "rapid_scroll_session_pct": round(rate("ExcessiveScroll") * 100, 1),
        "script_error_session_pct": round(rate("ScriptErrorCount") * 100, 1),
        "error_click_count": s("ErrorClickCount", "total"),
        "error_click_session_pct": round(rate("ErrorClickCount") * 100, 1),
        # Product-level view of the same sessions.
        "total_users": s("Traffic", "users"),
        "bot_sessions": s("Traffic", "bot_sessions"),
        "views_per_session": round(s("Traffic", "views_x100") / 100 / traffic_sessions, 1) if traffic_sessions else 0.0,
        "engaged_seconds": round(eng.get("active_time", 0) / eng_samples) if eng else 0,
        "session_seconds": round(eng.get("total_time", 0) / eng_samples) if eng else 0,
        "scroll_depth_pct": round(scroll["depth_x100"] / 100 / max(1, scroll.get("samples", 1)), 1) if scroll else None,
        # Share of sessions with visible frustration (rage or dead clicks/taps); a lower bound since they overlap.
        "frustrated_session_pct": round(max(rate("RageClickCount"), rate("DeadClickCount")) * 100, 1),
    }


HEALTH_PCT_KEYS = {
    "RageClickCount": "rage_session_pct", "DeadClickCount": "dead_click_session_pct",
    "QuickbackClick": "quickback_session_pct", "ExcessiveScroll": "rapid_scroll_session_pct",
    "ErrorClickCount": "error_click_session_pct", "ScriptErrorCount": "script_error_session_pct",
}


def health_from_pcts(kpis: dict[str, Any]) -> int:
    """The same health formula as compute_kpis, from session percentages (used when period-wide numbers replace them)."""
    penalty = sum(w * min(1.0, kpis.get(HEALTH_PCT_KEYS[m], 0.0) / 10.0) for m, w in HEALTH_WEIGHTS.items())
    return max(0, min(100, round(100 - penalty)))


def top_targets(norm: dict[str, Any], limit: int) -> list[dict[str, Any]]:
    agg: dict[str, dict[str, int]] = {}
    dims: set[str] = set()
    for metric, rows in norm.items():
        if metric in TRACKED_METRICS:
            dims.update(rows)
    dims.discard("(all)")
    for dim in dims:
        t = agg.setdefault(sanitize_path(dim), {})
        for metric, rows in norm.items():
            if metric not in TRACKED_METRICS:
                continue
            r = rows.get(dim)
            if not r:
                continue
            if metric == "Traffic":
                t["sessions"] = t.get("sessions", 0) + r.get("sessions", 0)
            else:
                t[metric] = t.get(metric, 0) + r.get("total", 0)
    ranked = []
    for path, t in agg.items():
        score = (3 * t.get("RageClickCount", 0) + 2 * t.get("DeadClickCount", 0)
                 + 1.5 * t.get("ErrorClickCount", 0) + t.get("QuickbackClick", 0)
                 + 0.5 * t.get("ExcessiveScroll", 0))
        if score <= 0:
            continue
        ranked.append({
            "element": path, "target_type": "page_path", "sessions": t.get("sessions", 0),
            "rage_clicks": t.get("RageClickCount", 0), "dead_clicks": t.get("DeadClickCount", 0),
            "quickbacks": t.get("QuickbackClick", 0), "rapid_scrolls": t.get("ExcessiveScroll", 0),
            "error_clicks": t.get("ErrorClickCount", 0), "script_errors": t.get("ScriptErrorCount", 0),
            "frustration_score": round(score, 1),
        })
    ranked.sort(key=lambda x: x["frustration_score"], reverse=True)
    return ranked[:limit]


def device_breakdown(norm: dict[str, Any]) -> list[dict[str, Any]]:
    out = []
    for dev, tr in norm.get("Traffic", {}).items():
        out.append({
            "device": scrub_text(dev), "sessions": tr.get("sessions", 0),
            "rage_clicks": norm.get("RageClickCount", {}).get(dev, {}).get("total", 0),
            "dead_clicks": norm.get("DeadClickCount", {}).get(dev, {}).get("total", 0),
            "quickbacks": norm.get("QuickbackClick", {}).get(dev, {}).get("total", 0),
        })
    return sorted(out, key=lambda d: d["sessions"], reverse=True)[:6]


# --------------------------------------------------------------------------
# Clarity client
# --------------------------------------------------------------------------
class ClarityClient:
    def __init__(self, settings: Settings, storage: Storage, http: httpx.AsyncClient) -> None:
        self._s, self._storage, self._http = settings, storage, http

    def requests_allowed(self, for_snapshot: bool = False) -> int:
        """User-triggered fetches leave `clarity_snapshot_reserve` requests for the nightly snapshot."""
        reserve = 0 if for_snapshot else self._s.clarity_snapshot_reserve
        return max(0, self._s.clarity_daily_limit - reserve)

    async def fetch_live(self, source: ClaritySource, num_days: int, dimension: str,
                         for_snapshot: bool = False, fresh: bool = False) -> list[dict[str, Any]]:
        """`fresh` skips the cache read, e.g. to prove a new token works rather than reuse another token's data."""
        key = f"{source.key}:{num_days}:{dimension}"
        cached = None if fresh else await self._storage.cache_get(key, self._s.cache_ttl_seconds)
        if cached is not None:
            return cached
        today = utc_today()
        # Count our own calls so we stop before Microsoft's per-project daily limit instead of burning a request on a 429.
        if not await self._storage.clarity_try_spend(source.key, today, self.requests_allowed(for_snapshot)):
            raise AuditError(429, "CLARITY_DAILY_BUDGET",
                             f"This project has used its {self._s.clarity_daily_limit} Clarity data requests for today "
                             "(a Microsoft limit). Cached reports still work; new data is available after 00:00 UTC.")
        url = f"{self._s.clarity_base_url}/project-live-insights"
        params = {"numOfDays": str(num_days), "dimension1": dimension}
        headers = {"Authorization": f"Bearer {source.token}", "Content-Type": "application/json"}
        for attempt in range(3):
            try:
                r = await self._http.get(url, params=params, headers=headers)
            except (httpx.TimeoutException, httpx.TransportError) as exc:
                log.warning("Clarity transport error (attempt %d): %s", attempt + 1, exc)
                await asyncio.sleep(0.5 * 2 ** attempt)
                continue
            if r.status_code == 200:
                try:
                    data = r.json()
                except ValueError as exc:
                    raise AuditError(502, "CLARITY_BAD_RESPONSE", "Clarity returned invalid JSON.") from exc
                if not isinstance(data, list):
                    raise AuditError(502, "CLARITY_BAD_RESPONSE", "Unexpected Clarity response shape.")
                await self._storage.cache_put(key, data)
                return data
            if r.status_code in (401, 403):
                raise AuditError(502, "CLARITY_AUTH", "Clarity rejected the API token for this project.")
            if r.status_code == 429:
                await self._storage.clarity_mark_exhausted(source.key, today, self._s.clarity_daily_limit)
                raise AuditError(429, "CLARITY_RATE_LIMIT",
                                 "Clarity's export quota (10 requests/project/day) is exhausted. Try again tomorrow.")
            if r.status_code >= 500:
                await asyncio.sleep(0.5 * 2 ** attempt)
                continue
            raise AuditError(502, "CLARITY_ERROR", f"Clarity returned HTTP {r.status_code}.")
        raise AuditError(504, "CLARITY_UNREACHABLE", "Could not reach Microsoft Clarity. Please retry shortly.")


# --------------------------------------------------------------------------
# Claude
# --------------------------------------------------------------------------
class ClaudeService:
    def __init__(self, settings: Settings) -> None:
        self._model = settings.claude_model
        self._client = anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key, max_retries=2, timeout=120.0)

    async def stream_backlog(self, payload: dict[str, Any], frames: list[str] | None = None) -> AsyncIterator[str]:
        body = scrub_text(json.dumps(payload, indent=2, ensure_ascii=False))
        prompt = PROMPT_TEMPLATE.replace("[INSERT SANITIZED CLARITY JSON HERE]", body)
        content: list[dict[str, Any]] = [
            {"type": "image", "source": {"type": "base64", "media_type": frame_media_type(f), "data": f}}
            for f in frames or []
        ]
        content.append({"type": "text", "text": prompt})
        try:
            async with self._client.messages.stream(
                model=self._model, max_tokens=4096, system=SYSTEM_PROMPT,
                messages=[{"role": "user", "content": content}],
            ) as stream:
                async for text in stream.text_stream:
                    yield text
        except anthropic.RateLimitError as exc:
            raise AuditError(429, "AI_RATE_LIMIT", "The AI engine is busy. Please retry in a minute.") from exc
        except anthropic.AuthenticationError as exc:
            log.error("Anthropic authentication failed")
            raise AuditError(502, "AI_AUTH", "AI engine is misconfigured.") from exc
        except anthropic.APIConnectionError as exc:
            raise AuditError(504, "AI_UNREACHABLE", "Could not reach the AI engine.") from exc
        except anthropic.APIStatusError as exc:
            log.error("Anthropic API error %s", exc.status_code)
            raise AuditError(502, "AI_ERROR", f"AI engine error (HTTP {exc.status_code}).") from exc


def frame_media_type(frame_b64: str) -> str:
    return image_media_type(base64.b64decode(frame_b64[:24])) or "image/jpeg"


class SectionGate:
    """Stops the roadmap section from reaching Free-tier clients (and halts generation early)."""

    def __init__(self, enabled: bool) -> None:
        self.enabled, self.blocked, self._buf = enabled, False, ""

    def feed(self, chunk: str) -> str:
        if not self.enabled:
            return chunk
        if self.blocked:
            return ""
        self._buf += chunk
        idx = self._buf.find(ROADMAP_MARKER)
        if idx != -1:
            out, self._buf, self.blocked = self._buf[:idx], "", True
            return out
        keep = len(ROADMAP_MARKER) - 1
        if len(self._buf) > keep:
            out, self._buf = self._buf[:-keep], self._buf[-keep:]
            return out
        return ""

    def flush(self) -> str:
        if not self.enabled or self.blocked:
            return ""
        out, self._buf = self._buf, ""
        return out


# --------------------------------------------------------------------------
# Orchestration
# --------------------------------------------------------------------------
@dataclass
class PreparedAudit:
    payload: dict[str, Any]
    kpis: dict[str, Any]
    notes: list[str] = field(default_factory=list)
    days_covered: int = 0
    frames: list[str] = field(default_factory=list)
    empty: bool = False  # no detailed metrics (a long range answered from period history only)


class AuditService:
    def __init__(self, settings: Settings, storage: Storage, clarity: ClarityClient, claude: ClaudeService,
                 vault: TokenVault, recordings: RecordingsClient | None = None,
                 dashboard: DashboardClient | None = None) -> None:
        self._s, self._storage, self._clarity, self._claude = settings, storage, clarity, claude
        self._vault = vault
        self._recordings = recordings
        self._dashboard = dashboard

    @staticmethod
    def resolve_range(req: AuditRequest) -> tuple[date, date, int]:
        today = utc_today()
        span = {Timeframe.TODAY: 1, Timeframe.LAST_3_DAYS: 3, Timeframe.LAST_WEEK: 7, Timeframe.LAST_MONTH: 30}.get(req.timeframe)
        if span:
            return today - timedelta(days=span - 1), today, span
        assert req.start_date and req.end_date
        return req.start_date, req.end_date, (req.end_date - req.start_date).days + 1

    def enforce_policy(self, req: AuditRequest, policy: TierPolicy) -> None:
        if req.timeframe not in policy.allowed_timeframes:
            need = required_tier(req.timeframe)
            raise AuditError(403, "UPGRADE_REQUIRED",
                             f"The {req.timeframe.value.replace('_', ' ').title()} range requires the {need.value.title()} plan.",
                             need.value)

    async def _live_url(self, source: ClaritySource, span: int, notes: list[str]) -> dict[str, Any]:
        """Live URL metrics; when today's Clarity budget is spent, fall back to today's stored snapshot."""
        today = utc_today()
        try:
            norm = normalize(await self._clarity.fetch_live(source, span, "URL"), "URL")
        except AuditError as exc:
            snaps = await self._storage.get_snapshots(source.key, today, today) if span == 1 else []
            if exc.code not in ("CLARITY_DAILY_BUDGET", "CLARITY_RATE_LIMIT") or not snaps:
                raise
            notes.append("Clarity's daily request limit is reached for this project, so this report uses data "
                         "saved earlier today.")
            return snaps[0][1]
        if span == 1:
            await self._storage.save_snapshot(source.key, today, norm)
        return norm

    async def prepare(self, req: AuditRequest, policy: TierPolicy, source: ClaritySource) -> PreparedAudit:
        """Detailed metrics, period-wide history (long/custom ranges) and real session recordings, fetched concurrently.

        However large the range, the cost is bounded: a fixed set of dashboard questions, one recordings request per
        sample category, and at most one Data Export request.
        """
        start, end, span = self.resolve_range(req)
        long_range = span > 3 or end != utc_today()
        rec_task = asyncio.create_task(self._sample_recordings(source, start, end)) if self._recordings else None
        dash_task = asyncio.create_task(self._period_history(source, start, end)) if long_range and self._dashboard else None
        try:
            prepared = await self._prepare_metrics(req, policy, source, allow_empty=dash_task is not None)
            history = await dash_task if dash_task else None
            if prepared.empty and not history:
                raise AuditError(404, "NO_DATA", "Clarity has no data for this project and period yet.")
            recordings = await rec_task if rec_task else None
        except BaseException:
            for t in (rec_task, dash_task):
                if t:
                    t.cancel()
            raise
        payload = prepared.payload
        if history:
            self._apply_history(prepared, history, span)
        elif dash_task:
            prepared.notes.append("Clarity's dashboard didn't answer for the full period, so long-range figures come "
                                  "from the last 3 days and saved nightly history.")
        detailed = 0
        if recordings:
            detailed = len(recordings["sessions"])
            payload.setdefault("session_recordings", {}).update(recordings)
            prepared.notes.append(
                f"Reviewed {recordings['sampled_sessions']} real session recordings from Clarity, sampled across the "
                f"period (rage and dead taps, quick backs, early exits and the most active users); {detailed} are "
                "read in full.")
        elif self._recordings:
            prepared.notes.append("Clarity didn't return session recordings for this period, so this report is "
                                  "based on metrics only.")
        payload["report_context"]["data_scope"] = data_scope(
            len(prepared.frames), recordings["sampled_sessions"] if recordings else 0, detailed, bool(history))
        return prepared

    def _apply_history(self, prepared: PreparedAudit, history: dict[str, Any], span: int) -> None:
        """Period-wide numbers replace the 3-day sample where Clarity's answer was recognisable."""
        overrides = history_kpis(history)
        prepared.kpis.update(overrides)
        if any(k.endswith("_pct") for k in overrides):
            prepared.kpis["health_score"] = health_from_pcts(prepared.kpis)
        prepared.payload["period_history"] = history
        ctx = prepared.payload["report_context"]
        detail_days = ctx["days_covered"]
        ctx["days_covered"] = prepared.days_covered = span
        # The dashboard covers the whole period and includes devices, so the 3-day caveats no longer apply.
        prepared.notes[:] = [n for n in prepared.notes
                             if not n.startswith(("Covers ", "Device breakdown is only", "History tracking just started"))]
        if detail_days == 0:
            prepared.notes.append(
                f"Totals, daily trends and audiences cover all {span} days (Clarity dashboard). Page-level friction "
                "detail isn't available for past ranges, because Clarity's export API only shares the last 3 days.")
        elif detail_days < span:
            prepared.notes.append(
                f"Totals, daily trends and audiences cover all {span} days (Clarity dashboard). Page- and screen-level "
                f"friction detail covers the latest {detail_days}, because Clarity's export API only shares the last 3 days.")

    async def _period_history(self, source: ClaritySource, start: date, end: date) -> dict[str, Any] | None:
        assert self._dashboard is not None
        try:
            return await asyncio.wait_for(self._dashboard.period(source, start, end), timeout=DASHBOARD_WAIT_SECONDS)
        except Exception:  # noqa: BLE001 - history is a bonus over the 3-day export
            log.warning("Clarity dashboard history unavailable for %s", source.key, exc_info=True)
            return None

    async def _sample_recordings(self, source: ClaritySource, start: date, end: date) -> dict[str, Any] | None:
        assert self._recordings is not None
        start_dt = datetime.combine(start, datetime.min.time(), tzinfo=timezone.utc)
        end_dt = datetime.now(timezone.utc) if end >= utc_today() else datetime.combine(end, datetime.max.time(), tzinfo=timezone.utc)
        try:
            return await asyncio.wait_for(self._recordings.sample(source, start_dt, end_dt), timeout=RECORDINGS_WAIT_SECONDS)
        except Exception:  # noqa: BLE001 - recordings are a bonus; never fail the audit over them
            log.warning("Session recordings unavailable for %s", source.key, exc_info=True)
            return None

    async def _prepare_metrics(self, req: AuditRequest, policy: TierPolicy, source: ClaritySource,
                               allow_empty: bool = False) -> PreparedAudit:
        """allow_empty: a long range whose detail is missing can still be answered from period-wide history."""
        start, end, span = self.resolve_range(req)
        today, notes = utc_today(), []
        device_norm = None

        if end == today and span <= 3:
            norm = await self._live_url(source, span, notes)
            covered = span
            if policy.device_breakdown and not is_mobile(norm):
                try:
                    device_norm = normalize(await self._clarity.fetch_live(source, span, "Device"), "Device")
                except AuditError as exc:
                    notes.append(f"Device breakdown unavailable: {exc.message}")
        else:
            # Clarity only serves the last 3 days. For ranges ending today, take those 3 days live and the older days
            # from the snapshots this server saves each night; past ranges come from snapshots alone.
            parts: list[dict[str, Any]] = []
            live_days = 0
            if end == today:
                try:
                    parts.append(normalize(await self._clarity.fetch_live(source, 3, "URL"), "URL"))
                    live_days = 3
                except AuditError as exc:
                    if exc.code not in ("CLARITY_DAILY_BUDGET", "CLARITY_RATE_LIMIT"):
                        raise
                    notes.append("Clarity's daily request limit is reached for this project, so this report uses "
                                 "saved history only.")
            older_end = today - timedelta(days=3) if live_days else end
            snaps = await self._storage.get_snapshots(source.key, start, older_end) if older_end >= start else []
            parts += [n for _, n in snaps]
            if not parts and not allow_empty:
                raise AuditError(404, "NO_DATA", "There's no saved history for this period yet, and Clarity only "
                                                 "shares the last 3 days.")
            norm = merge(parts)
            covered = min(span, live_days + len(snaps))
            if covered < span:
                notes.append(f"Covers {covered} of {span} days. Microsoft Clarity only shares the last 3 days, so "
                             "longer ranges are built from history ClarifiAI saves for this project every night. "
                             "Earlier days fill in automatically as history builds up.")
            if policy.device_breakdown and not is_mobile(norm):
                notes.append("Device breakdown is only available for ranges of 3 days or less.")

        kpis = compute_kpis(norm)
        empty = kpis["total_sessions"] == 0 and not any(norm.values())
        if empty and not allow_empty:
            raise AuditError(404, "NO_DATA", "Clarity returned no data for this project and period.")

        frames = list(req.recording_frames)
        mobile = is_mobile(norm)
        if mobile:
            granularity = ("app-wide totals: Clarity's export API does not break mobile-app friction down by screen; "
                           "rage/dead 'clicks' are taps and script errors are application errors")
        else:
            granularity = "page_path (CSS selectors are not exposed by Clarity's export API)"
        payload: dict[str, Any] = {
            "report_context": {
                "platform": "mobile_app" if mobile else "website",
                "timeframe": req.timeframe.value, "period_start": start.isoformat(), "period_end": end.isoformat(),
                "days_covered": covered, "friction_target_granularity": granularity,
                "data_scope": data_scope(len(frames), 0, 0, False),
                "data_notes": notes,
            },
            "totals": kpis,
            "top_friction_targets": top_targets(norm, policy.top_elements),
        }
        if frames:
            payload["session_recordings"] = {
                "frames_attached": len(frames),
                "user_note": scrub_text(req.recording_note.strip())[:500] if req.recording_note else None,
            }
        context = audience(norm)
        devices = context.pop("devices", None)
        if context:
            payload["audience"] = context
        if device_norm:
            payload["device_breakdown"] = device_breakdown(device_norm)
        elif policy.device_breakdown and devices:
            payload["device_breakdown"] = [{"device": d["name"], "sessions": d["sessions"]} for d in devices]
        return PreparedAudit(payload=payload, kpis=kpis, notes=notes, days_covered=covered, frames=frames, empty=empty)

    async def events(self, prepared: PreparedAudit, policy: TierPolicy, usage: dict[str, Any]) -> AsyncIterator[dict[str, Any]]:
        yield {"type": "meta", "tier": policy.tier.value, "kpis": prepared.kpis, "notes": prepared.notes,
               "days_covered": prepared.days_covered, "usage": usage}
        gate, emitted = SectionGate(enabled=not policy.full_roadmap), 0
        try:
            async with aclosing(self._claude.stream_backlog(prepared.payload, prepared.frames)) as gen:
                async for chunk in gen:
                    out = gate.feed(chunk)
                    if out:
                        emitted += len(out)
                        yield {"type": "delta", "text": out}
                    if gate.blocked:
                        break
            tail = gate.flush()
            if tail:
                emitted += len(tail)
                yield {"type": "delta", "text": tail}
            if emitted == 0:
                raise AuditError(502, "AI_EMPTY", "The AI engine returned an empty report.")
            if gate.blocked:
                yield {"type": "locked", "section": "roadmap",
                       "message": "Upgrade to Pro to unlock the Strategic Next-Sprint Product Roadmap."}
            yield {"type": "done"}
        except AuditError as exc:
            yield {"type": "error", "code": exc.code, "message": exc.message}
        except Exception:  # noqa: BLE001 - last-resort guard so the stream always terminates cleanly
            log.exception("Unexpected error while streaming audit")
            yield {"type": "error", "code": "INTERNAL", "message": "Unexpected server error."}

    def snapshot_day(self, now: datetime) -> date | None:
        """The day a "last 24 hours" fetch made now should be filed under, or None outside the snapshot window.

        From snapshot_hour_utc to midnight it is today. A late trigger (free hosts sleep; schedulers drift) still
        files the previous day during the first hours after midnight, instead of losing it.
        """
        if now.hour >= self._s.snapshot_hour_utc:
            return now.date()
        if now.hour < SNAPSHOT_GRACE_HOURS:
            return now.date() - timedelta(days=1)
        return None

    async def run_daily_snapshots(self) -> int:
        """Save one snapshot per tracked project for the current snapshot day. Returns how many were saved."""
        day = self.snapshot_day(datetime.now(timezone.utc))
        if day is None:
            return 0
        saved = 0
        for source in await self.snapshot_sources():
            if await self._storage.has_snapshot(source.key, day):
                continue
            try:
                raw = await self._clarity.fetch_live(source, 1, "URL", for_snapshot=True)
                await self._storage.save_snapshot(source.key, day, normalize(raw, "URL"))
                saved += 1
                log.info("Saved daily snapshot for %s (%s)", source.key, day)
            except AuditError as exc:
                log.warning("Snapshot failed for %s: %s", source.key, exc.message)
                if exc.code == "CLARITY_AUTH":
                    await self._storage.mark_needs_reauth(source.key)
        return saved

    async def snapshot_sources(self) -> list[ClaritySource]:
        sources: list[ClaritySource] = []
        if self._vault.configured:
            for key, token_enc in await self._storage.connection_sources():
                try:
                    sources.append(ClaritySource(key, self._vault.decrypt(token_enc)))
                except TokenError as exc:
                    log.warning("Skipping snapshot for %s: %s", key, exc)
        connection_ids = {c for c in await self._storage.tracked_projects() if c.startswith("cp_")}
        for pid in await self._storage.tracked_projects():
            token = self._s.token_for(pid)
            if pid not in connection_ids and token:
                sources.append(env_source(pid, token))
        return sources


def env_source(project_id: str, token: str) -> ClaritySource:
    """A project configured by the operator in CLARITY_TOKENS_JSON / CLARITY_API_TOKEN."""
    return ClaritySource(f"env:{project_id}", token)
