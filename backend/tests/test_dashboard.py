"""Period-wide history from Clarity's dashboard for long and custom ranges."""
from __future__ import annotations

import json
from datetime import date, timedelta

import httpx

from services.clarity_ai import AuditService, ClarityClient, utc_today
from services.dashboard import DASHBOARD_URL, DashboardClient, history_kpis
from services.projects import ClaritySource, TokenVault
from tests.conftest import clarity_payload, headers, ndjson

# Shapes as returned by Clarity for a real 30-day mobile project.
ANSWERS = {
    "Total sessions": [{"TotalSessions": 318, "DistinctUsers": 36, "TotalDeadClicks": 224, "TotalRageClicks": 4,
                        "TotalQuickbacks": 0, "SessionsWithDeadClicks": 40, "SessionsWithRageClicks": 1,
                        "SessionsWithQuickbacks": 0}],
    "Average active": [{"AvgActiveDurationInSeconds": 1292.03, "AvgTotalDurationInSeconds": 1321.04, "AvgPagesPerSession": 1.4}],
    "Daily sessions": [{"Date": "2026-09-09", "DistinctSessionCount": 20, "DistinctUserCount": 1}],
    "Sessions by device": [{"Device": "Mobile", "OS": "Android", "DistinctSessionCount": 163}],
    "Top 15 pages": [{"Url": "https://shop.example.com/u/123456/orders?x=1", "SessionCount": 9}],
}


class DashboardMock:
    def __init__(self) -> None:
        self.questions: list[str] = []
        self.fail = False

    def __call__(self, request: httpx.Request) -> httpx.Response:
        if str(request.url) != DASHBOARD_URL:
            return httpx.Response(200, json=clarity_payload())
        q = json.loads(request.content)["query"]
        self.questions.append(q)
        if self.fail:
            return httpx.Response(503)
        rows = next((v for k, v in ANSWERS.items() if q.startswith(k)), [])
        return httpx.Response(200, json={"query": f"interpreted: {q}", "data": rows, "dataErrorType": 0})


def test_history_kpis_reads_recognisable_columns_only():
    hist = {"totals": {"rows": ANSWERS["Total sessions"]}, "engagement": {"rows": ANSWERS["Average active"]}}
    k = history_kpis(hist)
    assert k["total_sessions"] == 318 and k["total_users"] == 36
    assert (k["dead_click_count"], k["rage_click_count"]) == (224, 4)
    assert k["dead_click_session_pct"] == 12.6 and k["frustrated_session_pct"] == 12.6
    assert (k["engaged_seconds"], k["session_seconds"], k["views_per_session"]) == (1292, 1321, 1.4)
    assert history_kpis({"totals": {"rows": [{"Something": "else"}]}}) == {}


async def test_period_asks_every_question_once_sanitises_and_caches(storage):
    mock = DashboardMock()
    async with httpx.AsyncClient(transport=httpx.MockTransport(mock)) as http:
        dash = DashboardClient(storage, http)
        src = ClaritySource("c:d", "t")
        out = await dash.period(src, date(2026, 9, 7), date(2026, 10, 7))
        assert all("2026-09-07" in q and "2026-10-07" in q for q in mock.questions) and len(mock.questions) == 7
        assert out["pages"]["rows"][0]["Url"] == "/u/:id/orders"  # host, ids and query string removed
        assert out["totals"]["question"].startswith("interpreted:")
        assert await dash.period(src, date(2026, 9, 7), date(2026, 10, 7)) == out and len(mock.questions) == 7


async def test_period_failure_is_soft(storage):
    mock = DashboardMock()
    mock.fail = True
    async with httpx.AsyncClient(transport=httpx.MockTransport(mock)) as http:
        assert await DashboardClient(storage, http).period(ClaritySource("c:f", "t"), date(2026, 9, 1), date(2026, 9, 30)) is None


def install(app, settings, storage, claude, mock):
    http = httpx.AsyncClient(transport=httpx.MockTransport(mock))
    app.state.audit = AuditService(settings, storage, ClarityClient(settings, storage, http), claude,
                                   TokenVault(settings.token_encryption_key), None, DashboardClient(storage, http))
    return http


async def test_last_30_days_covers_the_whole_period(client, settings, storage, claude):
    from main import app
    settings.dev_allow_tier_override = True
    http = install(app, settings, storage, claude, DashboardMock())
    events = ndjson(await client.post("/api/v1/analytics/audit", json={"project_id": "demo", "timeframe": "LAST_MONTH"},
                                      headers=headers(**{"X-Dev-Tier": "MAX"})))
    meta = events[0]
    assert events[-1] == {"type": "done"}
    assert meta["days_covered"] == 30 and meta["kpis"]["total_sessions"] == 318
    assert any("cover all 30 days" in n for n in meta["notes"]) and not any(n.startswith("Covers ") for n in meta["notes"])
    payload = claude.calls[0]
    assert payload["period_history"]["daily"]["rows"] and "period_history" in payload["report_context"]["data_scope"]
    await http.aclose()


async def test_past_custom_range_without_saved_history_is_answered_from_the_dashboard(client, settings, storage, claude):
    from main import app
    settings.dev_allow_tier_override = True
    h = headers(**{"X-Dev-Tier": "MAX"})
    end = utc_today() - timedelta(days=20)
    body = {"project_id": "demo", "timeframe": "CUSTOM", "start_date": str(end - timedelta(days=13)), "end_date": str(end)}
    r = await client.post("/api/v1/analytics/audit", json=body, headers=h)  # no dashboard client: nothing to go on
    assert (r.status_code, r.json()["detail"]["code"]) == (404, "NO_DATA")

    http = install(app, settings, storage, claude, DashboardMock())
    events = ndjson(await client.post("/api/v1/analytics/audit", json=body, headers=h))
    assert events[-1] == {"type": "done"} and events[0]["days_covered"] == 14
    assert events[0]["kpis"]["total_sessions"] == 318
    await http.aclose()
