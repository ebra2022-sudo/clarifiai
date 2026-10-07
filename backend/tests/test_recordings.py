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
        assert [s["replay"] for s in out] == ["rage-1", "shared", "dead-1"]
        assert [s["selected_for"] for s in out] == ["rage taps", "rage taps", "dead taps"]
        assert all(b["filters"]["date"]["start"].endswith("Z") for b in mock.bodies) and len(mock.bodies) == 4
        assert await rec.sample(src, end - timedelta(days=3), end) == out and len(mock.bodies) == 4  # cached


async def test_sample_failure_is_soft(storage):
    mock = RecordingsMock()
    mock.status = 403
    async with httpx.AsyncClient(transport=httpx.MockTransport(mock)) as http:
        out = await RecordingsClient(storage, http).sample(ClaritySource("c:x", "t"), datetime.now(timezone.utc) - timedelta(days=1), datetime.now(timezone.utc))
    assert out == []


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
    assert [s["replay"] for s in payload["session_recordings"]["sessions"]] == ["rage-1", "shared", "dead-1"]
    assert "3 real session recordings" in payload["report_context"]["data_scope"]
    assert any(n.startswith("Reviewed 3 real session recordings") for n in events[0]["notes"])

    mock.status = 500
    await storage.db.execute("DELETE FROM api_cache WHERE key LIKE 'rec:%'")
    await storage.db.commit()
    events = ndjson(await client.post("/api/v1/analytics/audit", json={"project_id": "demo", "timeframe": "TODAY"}, headers=headers()))
    assert events[-1] == {"type": "done"} and "session_recordings" not in claude.calls[1]
    assert any("metrics only" in n for n in events[0]["notes"])
    await http.aclose()
