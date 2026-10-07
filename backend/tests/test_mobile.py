"""Clarity projects created for mobile apps report taps/app errors and ignore the URL dimension."""
from __future__ import annotations

import httpx

from services.clarity_ai import audience, compute_kpis, is_mobile, normalize, top_targets
from tests.conftest import clarity_jwt, headers, ndjson

# Same shape as a real mobile-app project-live-insights response (values are made up).
MOBILE = [
    {"metricName": "DeadTapCount", "information": [{"sessionsCount": 40, "sessionsWithMetricPercentage": 10,
                                                     "screensViews": 6, "subTotal": 9}]},
    {"metricName": "RageTapCount", "information": [{"sessionsCount": 40, "sessionsWithMetricPercentage": 5,
                                                     "screensViews": 2, "subTotal": 3}]},
    {"metricName": "ApplicationErrorCount", "information": [{"sessionsCount": 40, "sessionsWithMetricPercentage": 0,
                                                              "screensViews": 0, "subTotal": 0}]},
    {"metricName": "Traffic", "information": [{"totalSessionCount": 40, "totalBotSessionCount": 0,
                                                "distinctUserCount": 7, "screensPerSessionPercentage": 1.2}]},
    {"metricName": "EngagementTime", "information": [{"totalTime": 900, "activeTime": 700}]},
    {"metricName": "Device", "information": [{"name": "Mobile", "sessionsCount": 38}, {"name": "Tablet", "sessionsCount": 2}]},
    {"metricName": "OS", "information": [{"name": "Android", "sessionsCount": 30}, {"name": "IOS", "sessionsCount": 10}]},
    {"metricName": "Country", "information": [{"name": "Kenya", "sessionsCount": 40}]},
    {"metricName": "PopularScreens", "information": [{"name": "CheckoutScreen", "sessionsCount": 25},
                                                      {"name": "user jane@doe.com", "sessionsCount": 1}]},
]


def test_mobile_metrics_map_onto_kpis():
    norm = normalize(MOBILE, "URL")
    assert is_mobile(norm)
    # sessionsCount is the row total; the percentage gives the sessions actually affected (40 * 10% = 4).
    assert norm["DeadClickCount"]["(all)"] == {"sessions": 4, "pageviews": 6, "total": 9}
    k = compute_kpis(norm)
    assert k["total_sessions"] == 40 and k["dead_click_count"] == 9 and k["rage_click_count"] == 3
    assert (k["dead_click_session_pct"], k["rage_session_pct"], k["script_error_session_pct"]) == (10.0, 5.0, 0.0)
    assert k["health_score"] == 60  # 100 - 25 (dead, saturated at 10%) - 15 (rage, half of 30)
    assert top_targets(norm, 10) == []  # no per-screen friction in the export
    assert not is_mobile(normalize([{"metricName": "Traffic", "information": []}], "URL"))


def test_audience_context_is_ranked_and_scrubbed():
    a = audience(normalize(MOBILE, "URL"))
    assert a["popular_screens"][0] == {"name": "CheckoutScreen", "sessions": 25}
    assert "jane@doe.com" not in str(a)
    assert a["operating_systems"][0]["name"] == "Android"
    assert "engagement_time" not in a  # engagement is reported as product KPIs instead
    k = compute_kpis(normalize(MOBILE, "URL"))
    assert (k["session_seconds"], k["engaged_seconds"]) == (900, 700)


async def test_mobile_audit_payload_and_max_device_breakdown_without_extra_request(client, clarity_mock, claude, settings):
    settings.dev_allow_tier_override = True
    clarity_mock.handler = lambda req: httpx.Response(200, json=MOBILE)
    h = headers(**{"X-Dev-Tier": "MAX"})
    pid = (await client.post("/api/v1/projects", json={"name": "App", "clarity_token": clarity_jwt()}, headers=h)).json()["id"]
    r = await client.post("/api/v1/analytics/audit", json={"project_id": pid, "timeframe": "TODAY"}, headers=h)
    assert ndjson(r)[-1] == {"type": "done"}
    payload = claude.calls[0]
    assert payload["report_context"]["platform"] == "mobile_app"
    assert "app-wide" in payload["report_context"]["friction_target_granularity"]
    assert payload["device_breakdown"] == [{"device": "Mobile", "sessions": 38}, {"device": "Tablet", "sessions": 2}]
    assert "devices" not in payload["audience"] and payload["audience"]["popular_screens"]
    assert len(clarity_mock.requests) == 1  # connect fetched once; no Device-dimension request needed


async def test_snapshots_keep_mobile_context_across_days(client, clarity_mock, claude, settings):
    settings.dev_allow_tier_override = True
    clarity_mock.handler = lambda req: httpx.Response(200, json=MOBILE)
    h = headers(**{"X-Dev-Tier": "MAX"})
    pid = (await client.post("/api/v1/projects", json={"name": "App", "clarity_token": clarity_jwt()}, headers=h)).json()["id"]
    await client.post("/api/v1/analytics/audit", json={"project_id": pid, "timeframe": "LAST_MONTH"}, headers=h)
    assert claude.calls[0]["report_context"]["platform"] == "mobile_app"
