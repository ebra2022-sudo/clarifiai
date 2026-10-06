from __future__ import annotations

import httpx

from services.clarity_ai import utc_today
from services.projects import TokenError, inspect_clarity_token
from tests.conftest import DEVICE, clarity_jwt, headers, ndjson

import pytest

PROJECTS = "/api/v1/projects"
AUDIT = "/api/v1/analytics/audit"


# ---------------------------------------------------------------- token inspection
def test_inspect_accepts_clarity_token_and_is_stable_per_subject():
    assert inspect_clarity_token(clarity_jwt("A")) == inspect_clarity_token(clarity_jwt("A", jti="other"))
    assert inspect_clarity_token(clarity_jwt("A")) != inspect_clarity_token(clarity_jwt("B"))


@pytest.mark.parametrize("token", [
    "not-a-token", "a.b.c", clarity_jwt(iss="someone-else"), clarity_jwt(scope="Other"), clarity_jwt(exp=1),
])
def test_inspect_rejects_bad_tokens(token):
    with pytest.raises(TokenError):
        inspect_clarity_token(token)


# ---------------------------------------------------------------- connect / list / delete
async def connect(client, token=None, name="My shop", device=DEVICE, **h):
    return await client.post(PROJECTS, json={"name": name, "clarity_token": token or clarity_jwt()},
                             headers=headers(device, **h))


async def test_connect_validates_with_one_request_then_audits_from_cache(client, storage, clarity_mock):
    token = clarity_jwt()
    r = await connect(client, token)
    assert r.status_code == 200, r.text
    project = r.json()
    assert project["id"].startswith("cp_") and project["name"] == "My shop"
    assert project["clarity_requests"] == {"used": 1, "limit": 9}
    assert "clarity_token" not in project
    assert clarity_mock.requests[0].headers["Authorization"] == f"Bearer {token}"

    # Stored encrypted, never in plain text.
    cur = await storage.db.execute("SELECT token_enc FROM connections")
    assert token not in (await cur.fetchone())["token_enc"]

    r = await client.post(AUDIT, json={"project_id": project["id"], "timeframe": "TODAY"}, headers=headers())
    assert ndjson(r)[-1] == {"type": "done"}
    assert len(clarity_mock.requests) == 1  # the audit reused the connect-time fetch

    listing = (await client.get(PROJECTS, headers=headers())).json()
    assert [p["id"] for p in listing["projects"]] == [project["id"]] and listing["max_projects"] == 1


async def test_connect_rejects_token_clarity_refuses_without_using_a_slot(client, storage, clarity_mock):
    clarity_mock.handler = lambda req: httpx.Response(401)
    r = await connect(client)
    assert (r.status_code, r.json()["detail"]["code"]) == (400, "INVALID_CLARITY_TOKEN")
    assert (await client.get(PROJECTS, headers=headers())).json()["projects"] == []
    clarity_mock.handler = lambda req: httpx.Response(200, json=[])
    assert (await connect(client, clarity_jwt("other"))).status_code == 200  # slot still free


async def test_connect_rejects_malformed_token_without_calling_clarity(client, clarity_mock):
    r = await connect(client, "x" * 40)
    assert (r.status_code, r.json()["detail"]["code"]) == (400, "INVALID_CLARITY_TOKEN")
    assert clarity_mock.requests == []


async def test_project_limit_applies_to_connections_and_delete_frees_a_slot(client):
    first = (await connect(client, clarity_jwt("A"))).json()
    r = await connect(client, clarity_jwt("B"))
    assert (r.status_code, r.json()["detail"]["code"], r.json()["detail"]["required_tier"]) == (403, "PROJECT_LIMIT", "PRO")
    assert (await client.delete(f"{PROJECTS}/{first['id']}", headers=headers())).status_code == 200
    assert (await connect(client, clarity_jwt("B"))).status_code == 200
    assert (await client.delete(f"{PROJECTS}/{first['id']}", headers=headers())).status_code == 404


async def test_reconnecting_same_project_updates_instead_of_duplicating(client):
    a = (await connect(client, clarity_jwt("A"), name="Old")).json()
    b = (await connect(client, clarity_jwt("A"), name="New")).json()
    assert a["id"] == b["id"] and b["name"] == "New"


async def test_other_device_cannot_use_or_delete_my_project(client):
    pid = (await connect(client)).json()["id"]
    other = "other-device-0002-abcd"
    r = await client.post(AUDIT, json={"project_id": pid, "timeframe": "TODAY"}, headers=headers(other))
    assert r.json()["detail"]["code"] in ("PROJECT_NOT_CONNECTED",)
    assert (await client.delete(f"{PROJECTS}/{pid}", headers=headers(other))).status_code == 404


async def test_unknown_project_needs_connecting(client, settings):
    settings.clarity_api_token = ""
    r = await client.post(AUDIT, json={"project_id": "cp_nope", "timeframe": "TODAY"}, headers=headers())
    assert (r.status_code, r.json()["detail"]["code"]) == (404, "PROJECT_NOT_CONNECTED")


async def test_connect_needs_encryption_key(client, settings):
    from services.projects import TokenVault
    from main import app
    app.state.vault = TokenVault("")
    r = await connect(client)
    assert (r.status_code, r.json()["detail"]["code"]) == (503, "CONNECT_NOT_CONFIGURED")


# ---------------------------------------------------------------- daily Clarity budget
async def test_budget_stops_before_microsofts_limit_and_keeps_one_for_snapshot(client, storage, clarity_mock, settings):
    settings.dev_allow_tier_override = True
    pid = (await connect(client, **{"X-Dev-Tier": "MAX"})).json()["id"]  # request 1
    key = (await storage.get_connection(DEVICE, pid)).source_key
    settings.cache_ttl_seconds = 0  # force live fetches
    for _ in range(8):  # requests 2..9
        r = await client.post(AUDIT, json={"project_id": pid, "timeframe": "LAST_3_DAYS"},
                              headers=headers(**{"X-Dev-Tier": "PRO"}))
        assert r.status_code == 200
    assert await storage.clarity_used(key, utc_today()) == 9
    r = await client.post(AUDIT, json={"project_id": pid, "timeframe": "LAST_3_DAYS"}, headers=headers(**{"X-Dev-Tier": "PRO"}))
    assert (r.status_code, r.json()["detail"]["code"]) == (429, "CLARITY_DAILY_BUDGET")
    assert len(clarity_mock.requests) == 9

    # TODAY falls back to the snapshot saved earlier today instead of failing.
    r = await client.post(AUDIT, json={"project_id": pid, "timeframe": "TODAY"}, headers=headers(**{"X-Dev-Tier": "PRO"}))
    events = ndjson(r)
    assert events[-1] == {"type": "done"}
    assert any("saved earlier today" in n for n in events[0]["notes"])

    # The nightly job may still use the reserved 10th request.
    assert await app_clarity(client).fetch_live(
        (await sources(client))[0], 2, "URL", for_snapshot=True) is not None
    assert await storage.clarity_used(key, utc_today()) == 10


async def test_two_users_connecting_same_project_share_cache_and_budget(client, clarity_mock):
    token = clarity_jwt("shared")
    await connect(client, token, device=DEVICE)
    await connect(client, token, device="second-device-0002-abc")
    assert len(clarity_mock.requests) == 1


def app_clarity(_client):
    from main import app
    return app.state.clarity


async def sources(_client):
    from main import app
    return await app.state.audit.snapshot_sources()


# ---------------------------------------------------------------- history: rename, re-authenticate, migrate
async def test_rename_and_set_clarity_project_id(client):
    pid = (await connect(client)).json()["id"]
    r = await client.patch(f"{PROJECTS}/{pid}", json={"name": "  Checkout app "}, headers=headers())
    assert r.status_code == 200 and r.json()["name"] == "Checkout app" and r.json()["clarity_project_id"] is None
    r = await client.patch(f"{PROJECTS}/{pid}", json={"clarity_project_id": "abc123xyz"}, headers=headers())
    assert r.json()["clarity_project_id"] == "abc123xyz" and r.json()["name"] == "Checkout app"
    r = await client.patch(f"{PROJECTS}/{pid}", json={"clarity_project_id": ""}, headers=headers())
    assert r.json()["clarity_project_id"] is None
    listing = (await client.get(PROJECTS, headers=headers())).json()["projects"]
    assert listing[0]["name"] == "Checkout app"


@pytest.mark.parametrize("body", [{"name": "   "}, {"name": "x" * 41}, {"clarity_project_id": "bad id!"}])
async def test_rename_rejects_invalid_values(client, body):
    pid = (await connect(client)).json()["id"]
    assert (await client.patch(f"{PROJECTS}/{pid}", json=body, headers=headers())).status_code in (400, 422)


async def test_other_device_cannot_rename_or_reconnect_my_project(client):
    pid = (await connect(client)).json()["id"]
    other = headers("other-device-0002-abcd")
    assert (await client.patch(f"{PROJECTS}/{pid}", json={"name": "Mine"}, headers=other)).status_code == 404
    r = await client.put(f"{PROJECTS}/{pid}/token", json={"clarity_token": clarity_jwt()}, headers=other)
    assert r.status_code == 404


async def test_rejected_token_flags_project_for_reconnect_and_reconnect_restores_it(client, clarity_mock, settings):
    pid = (await connect(client, clarity_jwt("A"))).json()["id"]
    settings.cache_ttl_seconds = 0
    clarity_mock.handler = lambda req: httpx.Response(401)
    r = await client.post(AUDIT, json={"project_id": pid, "timeframe": "LAST_3_DAYS"}, headers=headers())
    assert (r.status_code, r.json()["detail"]["code"]) == (409, "RECONNECT_REQUIRED")
    assert (await client.get(PROJECTS, headers=headers())).json()["projects"][0]["status"] == "needs_reauth"
    entitlements = (await client.get("/api/v1/account/entitlements", headers=headers())).json()
    assert entitlements["usage"]["used"] == 0  # refunded

    # Still flagged without calling Clarity again.
    calls = len(clarity_mock.requests)
    r = await client.post(AUDIT, json={"project_id": pid, "timeframe": "TODAY"}, headers=headers())
    assert r.json()["detail"]["code"] == "RECONNECT_REQUIRED" and len(clarity_mock.requests) == calls

    clarity_mock.handler = lambda req: httpx.Response(200, json=[])
    r = await client.put(f"{PROJECTS}/{pid}/token", json={"clarity_token": clarity_jwt("A", jti="new")}, headers=headers())
    assert r.status_code == 200 and r.json()["status"] == "active" and r.json()["id"] == pid


async def test_reconnect_rejects_bad_token_and_keeps_flag(client, clarity_mock, storage):
    pid = (await connect(client, clarity_jwt("A"))).json()["id"]
    await storage.mark_needs_reauth((await storage.get_connection(DEVICE, pid)).source_key)
    clarity_mock.handler = lambda req: httpx.Response(401)
    r = await client.put(f"{PROJECTS}/{pid}/token", json={"clarity_token": clarity_jwt("A", jti="x")}, headers=headers())
    assert (r.status_code, r.json()["detail"]["code"]) == (400, "INVALID_CLARITY_TOKEN")
    assert (await storage.get_connection(DEVICE, pid)).status == "needs_reauth"


async def test_reconnect_refuses_token_of_another_saved_project(client, settings):
    settings.dev_allow_tier_override = True
    h = headers(**{"X-Dev-Tier": "MAX"})
    a = (await connect(client, clarity_jwt("A"), **{"X-Dev-Tier": "MAX"})).json()["id"]
    await connect(client, clarity_jwt("B"), name="Other", **{"X-Dev-Tier": "MAX"})
    r = await client.put(f"{PROJECTS}/{a}/token", json={"clarity_token": clarity_jwt("B")}, headers=h)
    assert (r.status_code, r.json()["detail"]["code"]) == (409, "ALREADY_CONNECTED")


async def test_expired_token_shows_as_needs_reauth(client, storage):
    pid = (await connect(client)).json()["id"]
    await storage.db.execute("UPDATE connections SET token_expires_at='2000-01-01T00:00:00+00:00'")
    await storage.db.commit()
    assert (await client.get(PROJECTS, headers=headers())).json()["projects"][0]["status"] == "needs_reauth"
    assert pid


async def test_old_database_gains_new_connection_columns(tmp_path):
    import aiosqlite
    from services.storage import Storage
    path = str(tmp_path / "old.db")
    async with aiosqlite.connect(path) as db:
        await db.execute("CREATE TABLE connections(id TEXT PRIMARY KEY, device_id TEXT NOT NULL, name TEXT NOT NULL, "
                         "source_key TEXT NOT NULL, token_enc TEXT NOT NULL, created_at TEXT NOT NULL)")
        await db.execute("INSERT INTO connections VALUES('cp_1', ?, 'Shop', 'c:x', 'enc', '2026-01-01')", (DEVICE,))
        await db.commit()
    s = Storage(path)
    await s.init()
    cur = await s.db.execute("PRAGMA table_info(connections)")
    assert {"status", "clarity_project_id", "token_expires_at"} <= {r["name"] for r in await cur.fetchall()}
    await s.update_connection(DEVICE, "cp_1", None, "abc123xyz")
    await s.mark_needs_reauth("c:x")
    conn = await s.get_connection(DEVICE, "cp_1")
    assert (conn.status, conn.clarity_project_id) == ("needs_reauth", "abc123xyz")
    await s.close()
