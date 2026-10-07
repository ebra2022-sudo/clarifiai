"""Product-manager metrics, session-recording frames, and history reporting."""
from __future__ import annotations

import base64

from services.clarity_ai import PROMPT_TEMPLATE, ROADMAP_MARKER, audience, compute_kpis, merge, normalize
from tests.conftest import clarity_jwt, headers, ndjson

AUDIT = "/api/v1/analytics/audit"
JPEG = base64.b64encode(b"\xff\xd8\xff\xe0" + b"\x00" * 64).decode()
PNG = base64.b64encode(b"\x89PNG\r\n\x1a\n" + b"\x00" * 64).decode()


def day(sessions: int, per_session: float, active: int) -> list[dict]:
    return [
        {"metricName": "Traffic", "information": [{"totalSessionCount": str(sessions), "distinctUserCount": "3",
                                                    "totalBotSessionCount": "1", "pagesPerSessionPercentage": per_session}]},
        {"metricName": "EngagementTime", "information": [{"totalTime": active * 2, "activeTime": active}]},
        {"metricName": "ReferrerUrl", "information": [{"name": "https://www.google.com/search?q=secret", "sessionsCount": "4"}]},
    ]


def test_views_per_session_and_engagement_average_across_days_instead_of_adding_up():
    k = compute_kpis(merge([normalize(day(10, 2.0, 60), "URL"), normalize(day(30, 4.0, 120), "URL")]))
    assert k["total_sessions"] == 40 and k["total_users"] == 6 and k["bot_sessions"] == 2
    assert k["views_per_session"] == 3.5          # (10*2 + 30*4) / 40
    assert k["engaged_seconds"] == 90             # mean of the two days' averages
    assert k["session_seconds"] == 180


def test_referrers_keep_only_the_host():
    a = audience(normalize(day(5, 1.0, 10), "URL"))
    assert a["referrers"] == [{"name": "www.google.com", "sessions": 4}]


def test_prompt_is_user_focused_and_keeps_the_roadmap_marker():
    assert ROADMAP_MARKER in PROMPT_TEMPLATE
    assert "Focus on users, not code" in PROMPT_TEMPLATE
    assert "[INSERT SANITIZED CLARITY JSON HERE]" in PROMPT_TEMPLATE


async def test_recording_frames_reach_claude_with_context(client, claude):
    r = await client.post(AUDIT, json={"project_id": "demo", "timeframe": "TODAY", "recording_frames": [JPEG, PNG],
                                       "recording_note": "Trying to pay, mail me at jane@doe.com"}, headers=headers())
    assert ndjson(r)[-1] == {"type": "done"}
    assert claude.frames[0] == [JPEG, PNG]
    payload = claude.calls[0]
    assert payload["session_recordings"]["frames_attached"] == 2
    assert "jane@doe.com" not in payload["session_recordings"]["user_note"]
    assert "2 frame(s)" in payload["report_context"]["data_scope"]


async def test_no_frames_means_metrics_only_scope(client, claude):
    await client.post(AUDIT, json={"project_id": "demo", "timeframe": "TODAY"}, headers=headers())
    assert claude.frames[0] == [] and "session_recordings" not in claude.calls[0]


async def test_bad_frames_are_rejected(client):
    for frames in (["not base64!"], [base64.b64encode(b"GIF89a....").decode()], [JPEG] * 13):
        r = await client.post(AUDIT, json={"project_id": "demo", "timeframe": "TODAY", "recording_frames": frames},
                              headers=headers())
        assert r.status_code == 422, frames[:1]


async def test_projects_report_their_saved_history(client):
    r = await client.post("/api/v1/projects", json={"name": "Shop", "clarity_token": clarity_jwt()}, headers=headers())
    p = r.json()
    assert p["history_days"] == 1 and p["history_since"]  # connecting saves today's snapshot
