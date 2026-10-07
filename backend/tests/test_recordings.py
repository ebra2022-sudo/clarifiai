"""Real session recordings pulled from Clarity's recordings endpoint."""
from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone

import httpx

from services.clarity_ai import AuditService, ClarityClient
from services.projects import ClaritySource, TokenVault
from services.recordings import MAX_EVENTS_PER_SESSION, RECORDINGS_URL, RecordingsClient, condense
from tests.conftest import clarity_payload, headers, ndjson


def session(link: str, events: list[tuple[str, str]], screen: str = "CheckoutScreen") -> dict:
    return {
        "link": link, "timestamp": "2026-10-06 10:00:00", "totalDuration": "3 minutes", "activeDuration": "2 minutes",
        "pagesCount": 1, "sessionClickCount": len(events),
        "timeline": [{"displayTitle": screen, "start": "00:00",
                      "timelineEvents": [{"eventtype": t, "text": x, "start": "00:01"} for t, x in events]}],
    }


def test_condense_collapses_repeats_cleans_labels_and_marks_frustration():
    s = condense(session("L1", [("Click", "Back"), ("Click", "Back"), ("Click", "Back"),
                                ("Dead click", "Approve with biometrics"), ("Rage clicks", ""),
                                ("Click", "mail jane@doe.com")]), "dead taps")
    events = s["journey"][0]["events"]
    assert events == ["tap 'Back' ×3", "dead tap 'Approve with biometrics'", "rage taps '[icon]'", "tap 'mail [redacted]'"]
    assert s["selected_for"] == "dead taps" and s["replay"] == "L1" and "events_omitted" not in s


def test_condense_caps_plain_taps_but_never_drops_dead_or_rage_taps():
    taps = [("Click", f"Item {i}") for i in range(MAX_EVENTS_PER_SESSION + 10)] + [("Dead click", "Pay")]
    s = condense(session("L", taps), "most active")
    events = s["journey"][0]["events"]
    assert events[-1] == "dead tap 'Pay'" and s["events_omitted"] == 10


class RecordingsMock:
    def __init__(self) -> None:
        self.bodies: list[dict] = []
        self.status = 200

    def __call__(self, request: httpx.Request) -> httpx.Response:
        if str(request.url) != RECORDINGS_URL:
            return httpx.Response(200, json=clarity_payload())
        body = json.loads(request.content)
        self.bodies.append(body)
        if self.status != 200:
            return httpx.Response(self.status)
        f = body["filters"]
        if f.get("rageClickPresent"):
            return httpx.Response(200, json=[session("rage-1", [("Rage clicks", "Block")]), session("shared", [("Click", "Home")])])
        if f.get("deadClickPresent"):
            return httpx.Response(200, json=[session("shared", [("Click", "Home")]), session("dead-1", [("Dead click", "Pay")])])
        return httpx.Response(200, json=[{"link": "no-timeline", "timeline": []}])


async def test_sample_dedupes_caches_and_queries_trouble_first(storage):
    mock = RecordingsMock()
    async with httpx.AsyncClient(transport=httpx.MockTransport(mock)) as http:
        rec = RecordingsClient(storage, http)
        src = ClaritySource("c:test", "tok")
        end = datetime(2026, 10, 7, tzinfo=timezone.utc)
        out = await rec.sample(src, end - timedelta(days=3), end)
        # Round-robin across categories, most trouble first: rage-1, then dead-1, then the shared session.
        assert [s["replay"] for s in out["sessions"]] == ["rage-1", "dead-1", "shared"]
        assert [s["selected_for"] for s in out["sessions"]] == ["rage taps", "dead taps", "rage taps"]
        assert out["sampled_sessions"] == 3
        assert out["patterns"]["most_rage_tapped"] == [{"element": "Block", "sessions": 1}]
        assert out["patterns"]["most_dead_tapped"] == [{"element": "Pay", "sessions": 1}]
        assert all(b["filters"]["date"]["start"].endswith("Z") for b in mock.bodies) and len(mock.bodies) == 5
        assert all(b["count"] == 8 for b in mock.bodies)  # short range: small samples
        assert await rec.sample(src, end - timedelta(days=3), end) == out and len(mock.bodies) == 5  # cached


async def test_sample_failure_is_soft(storage):
    mock = RecordingsMock()
    mock.status = 403
    async with httpx.AsyncClient(transport=httpx.MockTransport(mock)) as http:
        out = await RecordingsClient(storage, http).sample(ClaritySource("c:x", "t"), datetime.now(timezone.utc) - timedelta(days=1), datetime.now(timezone.utc))
    assert out is None


async def test_audit_includes_recordings_and_says_so(client, settings, storage, claude):
    from main import app
    mock = RecordingsMock()
    http = httpx.AsyncClient(transport=httpx.MockTransport(mock))
    vault = TokenVault(settings.token_encryption_key)
    app.state.audit = AuditService(settings, storage, ClarityClient(settings, storage, http), claude, vault,
                                   RecordingsClient(storage, http))
    events = ndjson(await client.post("/api/v1/analytics/audit", json={"project_id": "demo", "timeframe": "TODAY"}, headers=headers()))
    assert events[-1] == {"type": "done"}
    payload = claude.calls[0]
    assert [s["replay"] for s in payload["session_recordings"]["sessions"]] == ["rage-1", "dead-1", "shared"]
    assert "3 real session recordings sampled" in payload["report_context"]["data_scope"]
    assert any(n.startswith("Reviewed 3 real session recordings") for n in events[0]["notes"])

    mock.status = 500
    await storage.db.execute("DELETE FROM api_cache WHERE key LIKE 'rec:%'")
    await storage.db.commit()
    events = ndjson(await client.post("/api/v1/analytics/audit", json={"project_id": "demo", "timeframe": "TODAY"}, headers=headers()))
    assert events[-1] == {"type": "done"} and "session_recordings" not in claude.calls[1]
    assert any("metrics only" in n for n in events[0]["notes"])
    await http.aclose()


def test_large_ranges_sample_more_but_send_a_bounded_set():
    from services.recordings import MAX_DETAILED, patterns, select
    sessions = [session(f"s{i}", [("Dead click", "Pay")] * (i % 4) + [("Click", "Home")]) for i in range(120)]
    for i, s in enumerate(sessions):
        s["timestamp"] = f"2026-09-{1 + i % 30:02d} 10:00:00"
        s["activeDuration"] = f"0{i % 9} minutes and 10 seconds"
    tagged = [("dead taps" if i % 2 else "most active", s) for i, s in enumerate(sessions)]
    picked = select(tagged)
    assert len(picked) == MAX_DETAILED
    assert len({s["timestamp"][:10] for _, s in picked}) == MAX_DETAILED  # spread over different days
    p = patterns(sessions)
    assert p["most_dead_tapped"][0] == {"element": "Pay", "sessions": 90}
    assert p["days_spanned"]["distinct_days"] == 30 and p["median_active_seconds"] is not None


async def test_long_range_requests_larger_samples(storage):
    mock = RecordingsMock()
    async with httpx.AsyncClient(transport=httpx.MockTransport(mock)) as http:
        end = datetime(2026, 10, 7, tzinfo=timezone.utc)
        await RecordingsClient(storage, http).sample(ClaritySource("c:l", "t"), end - timedelta(days=30), end)
    assert {b["count"] for b in mock.bodies} == {25}


async def test_recordings_endpoint_returns_the_audit_sample_without_using_an_audit(client, settings, storage, claude):
    from main import app
    mock = RecordingsMock()
    http = httpx.AsyncClient(transport=httpx.MockTransport(mock))
    app.state.audit = AuditService(settings, storage, ClarityClient(settings, storage, http), claude,
                                   TokenVault(settings.token_encryption_key), RecordingsClient(storage, http))
    body = {"project_id": "demo", "timeframe": "TODAY"}
    await client.post("/api/v1/analytics/audit", json=body, headers=headers())
    calls = len(mock.bodies)
    r = await client.post("/api/v1/analytics/recordings", json=body, headers=headers())
    assert r.status_code == 200
    data = r.json()
    assert [s["replay"] for s in data["sessions"]] == ["rage-1", "dead-1", "shared"] and data["sampled_sessions"] == 3
    assert len(mock.bodies) == calls  # served from the audit's cache
    usage = (await client.get("/api/v1/account/entitlements", headers=headers())).json()["usage"]["used"]
    assert usage == 1  # only the audit counted
    r = await client.post("/api/v1/analytics/recordings", json={"project_id": "demo", "timeframe": "LAST_MONTH"}, headers=headers())
    assert r.status_code == 403  # same tier rules as audits
    await http.aclose()
