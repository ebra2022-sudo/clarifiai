from __future__ import annotations

import httpx

from main import _period
from services.clarity_ai import AuditError
from tests.conftest import DEVICE, headers, ndjson

AUDIT = "/api/v1/analytics/audit"


async def usage_count(storage) -> int:
    return await storage.usage(DEVICE, _period())


async def test_health(client):
    r = await client.get("/health")
    assert r.json() == {"status": "ok"}


async def test_missing_or_bad_device_id_rejected(client):
    r = await client.get("/api/v1/account/entitlements")
    assert r.status_code == 400
    assert r.json()["detail"]["code"] == "BAD_DEVICE_ID"
    r = await client.get("/api/v1/account/entitlements", headers=headers("short"))
    assert r.status_code == 400


async def test_new_device_gets_free_entitlements(client):
    r = await client.get("/api/v1/account/entitlements", headers=headers())
    body = r.json()
    assert r.status_code == 200
    assert body["tier"] == "FREE"
    assert body["usage"] == {"used": 0, "limit": 3, "period": _period()}
    assert body["features"]["allowed_timeframes"] == ["LAST_3_DAYS", "TODAY"]
    assert body["features"]["pdf_export"] is False


async def test_free_tier_cannot_use_week_timeframe(client, storage, clarity_mock):
    r = await client.post(AUDIT, json={"project_id": "p1", "timeframe": "LAST_WEEK"}, headers=headers())
    assert r.status_code == 403
    assert r.json()["detail"] == {
        "code": "UPGRADE_REQUIRED", "message": "The Last Week range requires the Pro plan.", "required_tier": "PRO"}
    assert clarity_mock.requests == []
    assert await usage_count(storage) == 0


async def test_free_tier_stream_is_cut_before_roadmap(client, storage, claude):
    r = await client.post(AUDIT, json={"project_id": "p1", "timeframe": "TODAY"}, headers=headers())
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("application/x-ndjson")
    events = ndjson(r)
    assert [e["type"] for e in events][0] == "meta"
    assert events[-2]["type"] == "locked" and events[-2]["section"] == "roadmap"
    assert events[-1] == {"type": "done"}
    text = "".join(e["text"] for e in events if e["type"] == "delta")
    assert "Immediate Hotfixes" in text and "Friction Trends" in text
    assert "🚀" not in text and "Roadmap" not in text and "one-click" not in text
    meta = events[0]
    assert meta["tier"] == "FREE"
    assert meta["usage"] == {"used": 1, "limit": 3, "period": _period()}
    assert meta["kpis"]["total_sessions"] == 100
    # Free tier sends at most 3 targets, already sanitised.
    targets = claude.calls[0]["top_friction_targets"]
    assert len(targets) <= 3 and targets[0]["element"] == "/checkout"
    assert await usage_count(storage) == 1


async def test_paid_tier_gets_full_roadmap(client, settings):
    settings.dev_allow_tier_override = True
    r = await client.post(AUDIT, json={"project_id": "p1", "timeframe": "TODAY"}, headers=headers(**{"X-Dev-Tier": "pro"}))
    events = ndjson(r)
    text = "".join(e["text"] for e in events if e["type"] == "delta")
    assert "### 🚀 Strategic Next-Sprint Product Roadmap" in text
    assert not any(e["type"] == "locked" for e in events)
    assert events[0]["tier"] == "PRO"


async def test_dev_tier_header_ignored_unless_enabled(client):
    r = await client.get("/api/v1/account/entitlements", headers=headers(**{"X-Dev-Tier": "MAX"}))
    assert r.json()["tier"] == "FREE"


async def test_dev_tier_override_when_enabled(client, settings):
    settings.dev_allow_tier_override = True
    r = await client.get("/api/v1/account/entitlements", headers=headers(**{"X-Dev-Tier": "max"}))
    assert r.json()["tier"] == "MAX"
    assert r.json()["usage"]["limit"] == 600


async def test_monthly_quota_enforced(client, storage):
    for _ in range(3):
        r = await client.post(AUDIT, json={"project_id": "p1", "timeframe": "TODAY"}, headers=headers())
        assert ndjson(r)[-1] == {"type": "done"}
    r = await client.post(AUDIT, json={"project_id": "p1", "timeframe": "TODAY"}, headers=headers())
    assert r.status_code == 429
    assert r.json()["detail"]["code"] == "QUOTA_EXCEEDED"
    assert r.json()["detail"]["required_tier"] == "PRO"
    assert await usage_count(storage) == 3


async def test_max_quota_exceeded_has_no_upgrade_target(client, storage, settings):
    settings.dev_allow_tier_override = True
    for _ in range(600):
        await storage.try_consume(DEVICE, _period(), 600)
    r = await client.post(AUDIT, json={"project_id": "p1", "timeframe": "TODAY"}, headers=headers(**{"X-Dev-Tier": "MAX"}))
    assert r.status_code == 429
    assert r.json()["detail"]["required_tier"] is None


async def test_project_limit_enforced(client, storage):
    r = await client.post(AUDIT, json={"project_id": "p1", "timeframe": "TODAY"}, headers=headers())
    assert r.status_code == 200
    r = await client.post(AUDIT, json={"project_id": "p2", "timeframe": "TODAY"}, headers=headers())
    assert r.status_code == 403
    assert r.json()["detail"]["code"] == "PROJECT_LIMIT"
    assert r.json()["detail"]["required_tier"] == "PRO"
    assert await usage_count(storage) == 1  # rejected before a quota slot was reserved


async def test_clarity_auth_failure_refunds_audit(client, storage, clarity_mock):
    clarity_mock.handler = lambda req: httpx.Response(401)
    r = await client.post(AUDIT, json={"project_id": "p1", "timeframe": "TODAY"}, headers=headers())
    assert r.status_code == 502
    assert r.json()["detail"]["code"] == "CLARITY_AUTH"
    assert await usage_count(storage) == 0


async def test_clarity_rate_limit_and_no_data(client, storage, clarity_mock):
    clarity_mock.handler = lambda req: httpx.Response(429)
    r = await client.post(AUDIT, json={"project_id": "p1", "timeframe": "TODAY"}, headers=headers())
    assert (r.status_code, r.json()["detail"]["code"]) == (429, "CLARITY_RATE_LIMIT")
    clarity_mock.handler = lambda req: httpx.Response(200, json=[])
    r = await client.post(AUDIT, json={"project_id": "p1", "timeframe": "LAST_3_DAYS"}, headers=headers())
    assert (r.status_code, r.json()["detail"]["code"]) == (404, "NO_DATA")
    assert await usage_count(storage) == 0


async def test_clarity_request_shape_and_cache(client, clarity_mock):
    for _ in range(2):
        await client.post(AUDIT, json={"project_id": "p1", "timeframe": "LAST_3_DAYS"}, headers=headers())
    assert len(clarity_mock.requests) == 1  # second call served from the cache
    req = clarity_mock.requests[0]
    assert req.url.path.endswith("/project-live-insights")
    assert req.url.params["numOfDays"] == "3" and req.url.params["dimension1"] == "URL"
    assert req.headers["Authorization"] == "Bearer clarity-token"


async def test_ai_error_mid_stream_refunds_audit(client, storage, claude):
    claude.chunks = []
    claude.error = AuditError(429, "AI_RATE_LIMIT", "busy")
    r = await client.post(AUDIT, json={"project_id": "p1", "timeframe": "TODAY"}, headers=headers())
    events = ndjson(r)
    assert events[-1] == {"type": "error", "code": "AI_RATE_LIMIT", "message": "busy"}
    assert await usage_count(storage) == 0


async def test_empty_ai_output_is_an_error(client, storage, claude):
    claude.chunks = []
    r = await client.post(AUDIT, json={"project_id": "p1", "timeframe": "TODAY"}, headers=headers())
    assert ndjson(r)[-1]["code"] == "AI_EMPTY"
    assert await usage_count(storage) == 0


async def test_long_range_uses_snapshots_with_coverage_note(client, settings):
    settings.dev_allow_tier_override = True
    r = await client.post(AUDIT, json={"project_id": "p1", "timeframe": "LAST_MONTH"}, headers=headers(**{"X-Dev-Tier": "MAX"}))
    meta = ndjson(r)[0]
    assert meta["days_covered"] == 1
    assert any("History tracking just started" in n for n in meta["notes"])
    assert any("Covers 1 of 30" in n for n in meta["notes"])


async def test_custom_range_validation(client, settings):
    settings.dev_allow_tier_override = True
    h = headers(**{"X-Dev-Tier": "MAX"})
    r = await client.post(AUDIT, json={"project_id": "p1", "timeframe": "CUSTOM"}, headers=h)
    assert r.status_code == 422
    r = await client.post(AUDIT, json={"project_id": "p1", "timeframe": "CUSTOM",
                                       "start_date": "2020-01-01", "end_date": "2020-06-01"}, headers=h)
    assert r.status_code == 422
    r = await client.post(AUDIT, json={"project_id": "bad id!", "timeframe": "TODAY"}, headers=h)
    assert r.status_code == 422
