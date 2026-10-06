"""Clarity data fetcher, sanitizer, KPI engine and Claude invocation."""
from __future__ import annotations

import asyncio
import json
import logging
import re
from contextlib import aclosing
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from typing import Any, AsyncIterator
from urllib.parse import urlsplit

import anthropic
import httpx

from config import Settings
from models import AuditRequest, Timeframe
from services.storage import Storage
from services.tiers import TierPolicy, required_tier

log = logging.getLogger("clarity_ai")

PROMPT_TEMPLATE = """You are a Staff Product Manager and Lead UX Researcher. Analyze this sanitized Microsoft Clarity JSON telemetry payload and generate a strict, non-conversational engineering backlog:

[INSERT SANITIZED CLARITY JSON HERE]

Format the output strictly in Markdown with these specific headers:

### 🚨 Immediate Hotfixes (High Frustration)
- [Bullet points containing: Specific element, inferred technical cause, concrete fix action]

### 📉 Friction Trends & User Drop-off
- [Bullet points detailing behavioral friction patterns detected across screens]

### 🚀 Strategic Next-Sprint Product Roadmap
- [Prioritized features derived directly from conversion drop-offs to improve retention]
"""

SYSTEM_PROMPT = "Respond only with the requested Markdown report. No preamble, no closing remarks."
ROADMAP_MARKER = "### 🚀"


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
        name = block.get("metricName")
        if name not in TRACKED_METRICS:
            continue
        rows = out.setdefault(name, {})
        for info in block.get("information") or []:
            if not isinstance(info, dict):
                continue
            dim = _dim_value(info, dimension)
            if name == "Traffic":
                _add(rows, dim, {
                    "sessions": _int(info.get("totalSessionCount")),
                    "users": _int(info.get("distinctUserCount") or info.get("distantUserCount")),
                })
            else:
                _add(rows, dim, {
                    "sessions": _int(info.get("sessionsCount")),
                    "pageviews": _int(info.get("pagesViews") or info.get("pageViews")),
                    "total": _int(info.get("subTotal")),
                })
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
        sessions = max([s(m, "sessions") for m in norm if m != "Traffic"] or [0])

    def rate(metric: str) -> float:
        return min(1.0, s(metric, "sessions") / sessions) if sessions else 0.0

    penalty = sum(w * min(1.0, rate(m) / 0.10) for m, w in HEALTH_WEIGHTS.items())
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
    }


def top_targets(norm: dict[str, Any], limit: int) -> list[dict[str, Any]]:
    agg: dict[str, dict[str, int]] = {}
    dims: set[str] = set()
    for rows in norm.values():
        dims.update(rows)
    dims.discard("(all)")
    for dim in dims:
        t = agg.setdefault(sanitize_path(dim), {})
        for metric, rows in norm.items():
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

    async def fetch_live(self, project_id: str, num_days: int, dimension: str) -> list[dict[str, Any]]:
        key = f"{project_id}:{num_days}:{dimension}"
        cached = await self._storage.cache_get(key, self._s.cache_ttl_seconds)
        if cached is not None:
            return cached
        token = self._s.token_for(project_id)
        if not token:
            raise AuditError(404, "PROJECT_NOT_CONFIGURED", "No Clarity API token is configured for this project.")
        url = f"{self._s.clarity_base_url}/project-live-insights"
        params = {"numOfDays": str(num_days), "dimension1": dimension}
        headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
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

    async def stream_backlog(self, payload: dict[str, Any]) -> AsyncIterator[str]:
        body = scrub_text(json.dumps(payload, indent=2, ensure_ascii=False))
        prompt = PROMPT_TEMPLATE.replace("[INSERT SANITIZED CLARITY JSON HERE]", body)
        try:
            async with self._client.messages.stream(
                model=self._model, max_tokens=4096, system=SYSTEM_PROMPT,
                messages=[{"role": "user", "content": prompt}],
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


class AuditService:
    def __init__(self, settings: Settings, storage: Storage, clarity: ClarityClient, claude: ClaudeService) -> None:
        self._s, self._storage, self._clarity, self._claude = settings, storage, clarity, claude

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

    async def prepare(self, req: AuditRequest, policy: TierPolicy) -> PreparedAudit:
        start, end, span = self.resolve_range(req)
        today, notes = utc_today(), []
        pid = req.project_id
        device_norm = None

        if end == today and span <= 3:
            raw = await self._clarity.fetch_live(pid, span, "URL")
            norm = normalize(raw, "URL")
            if span == 1:
                await self._storage.save_snapshot(pid, today, norm)
            covered = span
            if policy.device_breakdown:
                try:
                    device_norm = normalize(await self._clarity.fetch_live(pid, span, "Device"), "Device")
                except AuditError as exc:
                    notes.append(f"Device breakdown unavailable: {exc.message}")
        else:
            snaps = await self._storage.get_snapshots(pid, start, end)
            if not snaps:
                raw = await self._clarity.fetch_live(pid, 1, "URL")
                norm1 = normalize(raw, "URL")
                await self._storage.save_snapshot(pid, today, norm1)
                snaps = [(today, norm1)]
                notes.append("History tracking just started for this project; only the last 24h is available so far.")
            norm = merge([n for _, n in snaps])
            covered = len(snaps)
            if covered < span:
                notes.append(f"Covers {covered} of {span} requested days. Clarity only exposes the last 3 days, "
                             "so longer ranges are assembled from daily snapshots recorded by this server.")
            if policy.device_breakdown:
                notes.append("Device breakdown is only available for ranges of 3 days or less.")

        kpis = compute_kpis(norm)
        if kpis["total_sessions"] == 0 and not any(norm.values()):
            raise AuditError(404, "NO_DATA", "Clarity returned no data for this project and period.")

        payload: dict[str, Any] = {
            "report_context": {
                "timeframe": req.timeframe.value, "period_start": start.isoformat(), "period_end": end.isoformat(),
                "days_covered": covered, "friction_target_granularity": "page_path (CSS selectors are not exposed by Clarity's export API)",
                "data_notes": notes,
            },
            "totals": kpis,
            "top_friction_targets": top_targets(norm, policy.top_elements),
        }
        if device_norm:
            payload["device_breakdown"] = device_breakdown(device_norm)
        return PreparedAudit(payload=payload, kpis=kpis, notes=notes, days_covered=covered)

    async def events(self, prepared: PreparedAudit, policy: TierPolicy, usage: dict[str, Any]) -> AsyncIterator[dict[str, Any]]:
        yield {"type": "meta", "tier": policy.tier.value, "kpis": prepared.kpis, "notes": prepared.notes,
               "days_covered": prepared.days_covered, "usage": usage}
        gate, emitted = SectionGate(enabled=not policy.full_roadmap), 0
        try:
            async with aclosing(self._claude.stream_backlog(prepared.payload)) as gen:
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

    async def run_daily_snapshots(self) -> None:
        now = datetime.now(timezone.utc)
        if now.hour < self._s.snapshot_hour_utc:
            return
        for pid in await self._storage.tracked_projects():
            if await self._storage.has_snapshot(pid, now.date()):
                continue
            try:
                raw = await self._clarity.fetch_live(pid, 1, "URL")
                await self._storage.save_snapshot(pid, now.date(), normalize(raw, "URL"))
                log.info("Saved daily snapshot for %s", pid)
            except AuditError as exc:
                log.warning("Snapshot failed for %s: %s", pid, exc.message)
