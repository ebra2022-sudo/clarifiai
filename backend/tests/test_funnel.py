"""Funnel drop-off built from Clarity's event filter, with the top reasons from the sessions that dropped."""
from __future__ import annotations

import json

import httpx

from services.dashboard import DASHBOARD_URL, DashboardClient
from services.funnel import FunnelService, reach
from services.recordings import RECORDINGS_URL, RecordingsClient
from tests.conftest import headers


def session(link: str, stamp: str = "2026-10-06 10:00:00", taps: list[tuple[str, str]] | None = None) -> dict:
    events = [{"eventtype": t, "text": x, "hash": f"h{i}", "start": f"00:0{i}"} for i, (t, x) in enumerate(taps or [("Click", "Next")])]
    return {"link": link, "timestamp": stamp, "totalDuration": "1 minute", "activeDuration": "1 minute",
            "pagesCount": 1, "sessionClickCount": len(events),
            "timeline": [{"displayTitle": "Main", "start": "00:00", "duration": 60000, "timelineEvents": events}]}


def test_reach_intersects_steps_and_ignores_sessions_older_than_a_capped_sample():
    a = [session("s1", "2026-10-06 10:00:00"), session("s2", "2026-10-05 10:00:00"), session("s0", "2026-10-01 10:00:00")]
    b = [session("s1", "2026-10-06 10:00:00"), session("s2", "2026-10-05 10:00:00")]
    reached, by_link, horizon = reach([a, b], [False, True])  # b hit the sample cap: it only reaches back to 10-05
    assert horizon == "2026-10-05 10:00:00"
    assert reached == [{"s1", "s2"}, {"s1", "s2"}]  # s0 is older than b covers, so it isn't counted as a drop-off
    assert "s0" not in by_link


class ClarityMock:
    def __init__(self) -> None:
        self.bodies: list[dict] = []
        self.fired = {"Open": ["s1", "s2", "s3", "s4"], "Answer": ["s1", "s2", "s3"], "Pay": ["s1"]}

    def __call__(self, request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        self.bodies.append(body)
        if str(request.url) == DASHBOARD_URL:
            return httpx.Response(200, json={"query": "q", "dataErrorType": 0, "data": [
                {"SmartEvents": "Other", "SessionCount": 50}, {"SmartEvents": "Open", "SessionCount": 4},
                {"SmartEvents": "Pay", "SessionCount": 1}]})
        assert str(request.url) == RECORDINGS_URL
        events = body["filters"].get("smartEvents")
        if events:
            return httpx.Response(200, json=[session(f"L-{s}", taps=[("Click", "Next"), ("Dead click", "Pay")])
                                             for s in self.fired[events[0]]])
        # A lone fetch (one second around a session's start): the session with its true first-screen times.
        return httpx.Response(200, json=[session(f"L-s{i}", taps=[("Click", "Next"), ("Dead click", "Pay")]) for i in (2, 3)])


async def wire(storage, claude):
    from main import app
    mock = ClarityMock()
    http = httpx.AsyncClient(transport=httpx.MockTransport(mock))
    app.state.funnel = FunnelService(storage, RecordingsClient(storage, http), DashboardClient(storage, http), claude)
    return mock, http


async def test_funnel_finds_the_worst_drop_and_its_reasons(client, storage, claude):
    mock, http = await wire(storage, claude)
    body = {"project_id": "demo", "timeframe": "TODAY", "name": "Activation", "steps": ["Open", "Answer", "Pay"]}
    r = await client.post("/api/v1/analytics/funnel", json=body, headers=headers())
    assert r.status_code == 200, r.text
    out = r.json()
    assert [s["sessions"] for s in out["steps"]] == [4, 3, 1]
    assert out["drop_off"] == {"from": "Answer", "to": "Pay", "reached": 3, "dropped": 2, "rate": 0.6667}
    assert [s["id"] for s in out["sessions"]] == ["D1", "D2"]
    assert out["sessions"][0]["journey"][0]["events"][1].startswith("00:01 dead tap")  # times confirmed by lone fetch
    assert out["reasons"] == [{"title": "Pay button does nothing",
                               "explanation": "Users tap Pay and wait [D1 at 00:05](L-s2).", "sessions": 2}]
    assert '"Answer" but never fired "Pay"' in claude.prompts[0]
    sent = len(mock.bodies)
    assert (await client.post("/api/v1/analytics/funnel", json=body, headers=headers())).json() == out
    assert len(mock.bodies) == sent  # cached
    await http.aclose()


async def test_funnel_without_a_drop_has_no_reasons(client, storage, claude):
    mock, http = await wire(storage, claude)
    mock.fired = {"Open": ["s1"], "Pay": ["s1"]}
    out = (await client.post("/api/v1/analytics/funnel", headers=headers(),
                             json={"project_id": "demo", "timeframe": "TODAY", "steps": ["Open", "Pay"]})).json()
    assert out["drop_off"] is None and out["reasons"] == [] and not claude.prompts
    await http.aclose()


async def test_events_to_pick_steps_from_skip_clarity_catch_all(client, storage, claude):
    _, http = await wire(storage, claude)
    r = await client.post("/api/v1/analytics/funnel/events", json={"project_id": "demo", "timeframe": "TODAY"}, headers=headers())
    assert r.json() == {"events": [{"name": "Open", "sessions": 4}, {"name": "Pay", "sessions": 1}]}
    await http.aclose()


async def test_a_funnel_needs_two_to_eight_named_steps(client, storage, claude):
    _, http = await wire(storage, claude)
    for steps in (["Open"], ["Open", " "], [f"e{i}" for i in range(9)]):
        r = await client.post("/api/v1/analytics/funnel", headers=headers(),
                              json={"project_id": "demo", "timeframe": "TODAY", "steps": steps})
        assert r.status_code == 422, steps
    await http.aclose()
