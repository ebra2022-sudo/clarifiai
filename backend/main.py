from __future__ import annotations

import asyncio
import json
import logging
import re
import secrets
from contextlib import asynccontextmanager
from datetime import datetime, timezone

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse, StreamingResponse

from config import Settings, get_settings
from models import (AuditRequest, ConnectProjectRequest, ReconnectProjectRequest, SubscriptionVerifyRequest, Tier,
                    UpdateProjectRequest)
from services.billing import BillingError, PlayBillingVerifier
from services.clarity_ai import AuditError, AuditService, ClarityClient, ClaudeService, env_source, normalize, utc_today
from services.projects import ClaritySource, TokenError, TokenVault, inspect_clarity_token, token_expiry
from services.storage import Connection, Storage, UserRecord
from services.tiers import POLICIES, next_tier

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("main")

DEVICE_RE = re.compile(r"^[A-Za-z0-9\-]{16,64}$")


async def _snapshot_loop(svc: AuditService) -> None:
    while True:
        try:
            await svc.run_daily_snapshots()
        except Exception:  # noqa: BLE001
            log.exception("Snapshot loop iteration failed")
        await asyncio.sleep(3600)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    storage = Storage(settings.database_url or settings.database_path)
    await storage.init()
    http = httpx.AsyncClient(timeout=httpx.Timeout(30.0, connect=10.0))
    vault = TokenVault(settings.token_encryption_key)
    if not vault.configured:
        log.warning("TOKEN_ENCRYPTION_KEY is not set: users can't connect Clarity projects from the app.")
    clarity = ClarityClient(settings, storage, http)
    audit = AuditService(settings, storage, clarity, ClaudeService(settings), vault)
    app.state.settings, app.state.storage, app.state.audit = settings, storage, audit
    app.state.clarity, app.state.vault = clarity, vault
    app.state.billing = PlayBillingVerifier(settings, http)
    task = asyncio.create_task(_snapshot_loop(audit))
    yield
    task.cancel()
    await http.aclose()
    await storage.close()


app = FastAPI(title="Clarity AI Executive Dashboard API", version="1.0.0", lifespan=lifespan)


@app.exception_handler(AuditError)
async def audit_error_handler(_: Request, exc: AuditError) -> JSONResponse:
    return JSONResponse(status_code=exc.status, content={
        "detail": {"code": exc.code, "message": exc.message, "required_tier": exc.required_tier}})


@app.exception_handler(BillingError)
async def billing_error_handler(_: Request, exc: BillingError) -> JSONResponse:
    return JSONResponse(status_code=exc.status, content={
        "detail": {"code": exc.code, "message": exc.message, "required_tier": None}})


async def current_user(
    request: Request,
    x_device_id: str | None = Header(default=None),
    x_dev_tier: str | None = Header(default=None),
) -> UserRecord:
    if not x_device_id or not DEVICE_RE.match(x_device_id):
        raise HTTPException(status_code=400, detail={"code": "BAD_DEVICE_ID", "message": "Missing or invalid X-Device-Id.", "required_tier": None})
    user = await request.app.state.storage.get_user(x_device_id)
    settings: Settings = request.app.state.settings
    if settings.dev_allow_tier_override and x_dev_tier and x_dev_tier.upper() in Tier.__members__:
        user.tier = Tier(x_dev_tier.upper())
    return user


def _period() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m")


async def _entitlements(storage: Storage, user: UserRecord) -> dict:
    policy = POLICIES[user.tier]
    used = await storage.usage(user.device_id, _period())
    return {
        "tier": user.tier.value,
        "expires_at": user.expires_at.isoformat() if user.expires_at else None,
        "usage": {"used": used, "limit": policy.monthly_audits, "period": _period()},
        "features": policy.to_features(),
    }


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@app.get("/api/v1/account/entitlements")
async def entitlements(request: Request, user: UserRecord = Depends(current_user)) -> dict:
    return await _entitlements(request.app.state.storage, user)


@app.post("/api/v1/subscription/verify")
async def verify_subscription(body: SubscriptionVerifyRequest, request: Request,
                              user: UserRecord = Depends(current_user)) -> dict:
    tier, expiry = await request.app.state.billing.verify(body.purchase_token, body.product_id)
    await request.app.state.storage.set_tier(user.device_id, tier, expiry, body.purchase_token)
    user.tier, user.expires_at = tier, expiry
    return await _entitlements(request.app.state.storage, user)


# ---- connected Clarity projects ---------------------------------------------
# Connections are the user's project history: they persist until deleted, so reports, snapshots and the token are
# reused across app restarts. A connection whose token Clarity rejects is kept and flagged for reconnecting.
async def _project_view(request: Request, conn: Connection) -> dict:
    clarity: ClarityClient = request.app.state.clarity
    used = await request.app.state.storage.clarity_used(conn.source_key, utc_today())
    status = conn.status
    if status == "active" and conn.token_expires_at and conn.token_expires_at < datetime.now(timezone.utc).isoformat():
        status = "needs_reauth"
    return {"id": conn.id, "name": conn.name, "created_at": conn.created_at, "status": status,
            "clarity_project_id": conn.clarity_project_id, "token_expires_at": conn.token_expires_at,
            "clarity_requests": {"used": used, "limit": clarity.requests_allowed()}}


def _limit_error(user: UserRecord, max_projects: int) -> AuditError:
    need = next_tier(user.tier)
    return AuditError(403, "PROJECT_LIMIT", f"Your {user.tier.value.title()} plan supports {max_projects} project(s).",
                      need.value if need is not user.tier else None)


def _check_token(request: Request, raw: str) -> tuple[str, str]:
    """Shape-check a pasted token. Returns (token, source_key)."""
    if not request.app.state.vault.configured:
        raise AuditError(503, "CONNECT_NOT_CONFIGURED", "This server can't store Clarity tokens yet (TOKEN_ENCRYPTION_KEY).")
    token = raw.strip()
    try:
        return token, inspect_clarity_token(token)
    except TokenError as exc:
        raise AuditError(400, "INVALID_CLARITY_TOKEN", str(exc)) from exc


async def _validate_with_clarity(request: Request, source_key: str, token: str, fresh: bool = False) -> None:
    """One real request proves the token works. Its data isn't wasted: it's cached and saved as today's snapshot."""
    try:
        raw = await request.app.state.clarity.fetch_live(ClaritySource(source_key, token), 1, "URL", fresh=fresh)
        await request.app.state.storage.save_snapshot(source_key, utc_today(), normalize(raw, "URL"))
    except AuditError as exc:
        # Out of requests for today: the token's claims looked valid, so accept it; data arrives tomorrow.
        if exc.code in ("CLARITY_DAILY_BUDGET", "CLARITY_RATE_LIMIT"):
            return
        if exc.code in ("CLARITY_AUTH", "CLARITY_ERROR"):
            raise AuditError(400, "INVALID_CLARITY_TOKEN",
                             "Clarity rejected this token. Generate a new one in Settings → Data Export.") from exc
        raise


async def _own_connection(request: Request, user: UserRecord, project_id: str) -> Connection:
    conn = await request.app.state.storage.get_connection(user.device_id, project_id)
    if not conn:
        raise AuditError(404, "PROJECT_NOT_FOUND", "That project isn't connected.")
    return conn


@app.get("/api/v1/projects")
async def list_projects(request: Request, user: UserRecord = Depends(current_user)) -> dict:
    conns = await request.app.state.storage.list_connections(user.device_id)
    return {"projects": [await _project_view(request, c) for c in conns],
            "max_projects": POLICIES[user.tier].max_projects}


@app.post("/api/v1/projects")
async def connect_project(body: ConnectProjectRequest, request: Request,
                          user: UserRecord = Depends(current_user)) -> dict:
    storage: Storage = request.app.state.storage
    token, source_key = _check_token(request, body.clarity_token)
    name = body.name.strip() or "My project"

    existing = await storage.find_connection_by_source(user.device_id, source_key)
    conn_id = existing.id if existing else "cp_" + secrets.token_urlsafe(8)
    policy = POLICIES[user.tier]
    if not existing and not await storage.register_project(user.device_id, conn_id, policy.max_projects):
        raise _limit_error(user, policy.max_projects)
    try:
        await _validate_with_clarity(request, source_key, token)
    except AuditError:
        if not existing:
            await storage.unregister_project(user.device_id, conn_id)
        raise

    conn = Connection(conn_id, user.device_id, name, source_key, request.app.state.vault.encrypt(token),
                      existing.created_at if existing else datetime.now(timezone.utc).isoformat(),
                      clarity_project_id=body.clarity_project_id, token_expires_at=token_expiry(token))
    await storage.add_connection(conn)
    return await _project_view(request, conn)


@app.patch("/api/v1/projects/{project_id}")
async def update_project(project_id: str, body: UpdateProjectRequest, request: Request,
                         user: UserRecord = Depends(current_user)) -> dict:
    await _own_connection(request, user, project_id)
    name = body.name.strip() if body.name is not None else None
    if name == "":
        raise AuditError(400, "INVALID_NAME", "Project name can't be empty.")
    conn = await request.app.state.storage.update_connection(user.device_id, project_id, name, body.clarity_project_id)
    assert conn is not None
    return await _project_view(request, conn)


@app.put("/api/v1/projects/{project_id}/token")
async def reconnect_project(project_id: str, body: ReconnectProjectRequest, request: Request,
                            user: UserRecord = Depends(current_user)) -> dict:
    """Re-authenticate a saved project with a fresh token, keeping its name and slot."""
    storage: Storage = request.app.state.storage
    conn = await _own_connection(request, user, project_id)
    token, source_key = _check_token(request, body.clarity_token)
    other = await storage.find_connection_by_source(user.device_id, source_key)
    if other and other.id != conn.id:
        raise AuditError(409, "ALREADY_CONNECTED", f"This token belongs to your project \"{other.name}\".")
    # Always ask Clarity: the cache may hold data fetched with the token being replaced.
    await _validate_with_clarity(request, source_key, token, fresh=True)
    conn.source_key, conn.token_enc = source_key, request.app.state.vault.encrypt(token)
    conn.status, conn.token_expires_at = "active", token_expiry(token)
    await storage.add_connection(conn)
    return await _project_view(request, conn)


@app.delete("/api/v1/projects/{project_id}")
async def delete_project(project_id: str, request: Request, user: UserRecord = Depends(current_user)) -> dict:
    if not await request.app.state.storage.delete_connection(user.device_id, project_id):
        raise AuditError(404, "PROJECT_NOT_FOUND", "That project isn't connected.")
    return {"deleted": project_id}


async def _resolve_source(request: Request, user: UserRecord, project_id: str, max_projects: int) -> ClaritySource:
    storage: Storage = request.app.state.storage
    conn = await storage.get_connection(user.device_id, project_id)
    if conn:
        if conn.status == "needs_reauth":
            raise AuditError(409, "RECONNECT_REQUIRED",
                             f"Clarity no longer accepts the token for \"{conn.name}\". Reconnect it with a new token.")
        try:
            return ClaritySource(conn.source_key, request.app.state.vault.decrypt(conn.token_enc))
        except TokenError as exc:
            await storage.mark_needs_reauth(conn.source_key)
            raise AuditError(409, "RECONNECT_REQUIRED", "Reconnect this project: its stored token can't be read.") from exc
    # Operator-configured projects. Connection IDs never fall through to the operator's token.
    token = None if project_id.startswith("cp_") else request.app.state.settings.token_for(project_id)
    if not token:
        raise AuditError(404, "PROJECT_NOT_CONNECTED", "Connect this Clarity project first.")
    if not await storage.register_project(user.device_id, project_id, max_projects):
        raise _limit_error(user, max_projects)
    return env_source(project_id, token)


def project_id_is_connection(project_id: str) -> bool:
    return project_id.startswith("cp_")


@app.post("/api/v1/analytics/audit")
async def audit(body: AuditRequest, request: Request, user: UserRecord = Depends(current_user)) -> StreamingResponse:
    storage: Storage = request.app.state.storage
    svc: AuditService = request.app.state.audit
    policy = POLICIES[user.tier]
    period = _period()

    svc.enforce_policy(body, policy)
    source = await _resolve_source(request, user, body.project_id, policy.max_projects)
    used = await storage.try_consume(user.device_id, period, policy.monthly_audits)
    if used is None:
        need = next_tier(user.tier)
        raise AuditError(429, "QUOTA_EXCEEDED",
                         f"You've used all {policy.monthly_audits} audits included in your {user.tier.value.title()} plan this month.",
                         need.value if need is not user.tier else None)
    try:
        prepared = await svc.prepare(body, policy, source)
    except AuditError as exc:
        await asyncio.shield(storage.refund(user.device_id, period))
        if exc.code == "CLARITY_AUTH" and project_id_is_connection(body.project_id):
            await storage.mark_needs_reauth(source.key)
            raise AuditError(409, "RECONNECT_REQUIRED",
                             "Clarity rejected this project's token (it may have been revoked). "
                             "Reconnect it with a new token.") from exc
        raise
    except BaseException:
        await asyncio.shield(storage.refund(user.device_id, period))
        raise
    usage = {"used": used, "limit": policy.monthly_audits, "period": period}

    async def event_stream():
        ok = False
        try:
            async for ev in svc.events(prepared, policy, usage):
                if ev["type"] == "done":
                    ok = True
                yield json.dumps(ev, ensure_ascii=False) + "\n"
        finally:
            if not ok:  # failed or client disconnected -> don't charge the audit
                await asyncio.shield(storage.refund(user.device_id, period))

    return StreamingResponse(event_stream(), media_type="application/x-ndjson",
                             headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"})
