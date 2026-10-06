from __future__ import annotations

import asyncio
import json
import logging
import re
from contextlib import asynccontextmanager
from datetime import datetime, timezone

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse, StreamingResponse

from config import Settings, get_settings
from models import AuditRequest, SubscriptionVerifyRequest, Tier
from services.billing import BillingError, PlayBillingVerifier
from services.clarity_ai import AuditError, AuditService, ClarityClient, ClaudeService
from services.storage import Storage, UserRecord
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
    storage = Storage(settings.database_path)
    await storage.init()
    http = httpx.AsyncClient(timeout=httpx.Timeout(30.0, connect=10.0))
    audit = AuditService(settings, storage, ClarityClient(settings, storage, http), ClaudeService(settings))
    app.state.settings, app.state.storage, app.state.audit = settings, storage, audit
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


@app.post("/api/v1/analytics/audit")
async def audit(body: AuditRequest, request: Request, user: UserRecord = Depends(current_user)) -> StreamingResponse:
    storage: Storage = request.app.state.storage
    svc: AuditService = request.app.state.audit
    policy = POLICIES[user.tier]
    period = _period()

    svc.enforce_policy(body, policy)
    if not await storage.register_project(user.device_id, body.project_id, policy.max_projects):
        need = next_tier(user.tier)
        raise AuditError(403, "PROJECT_LIMIT",
                         f"Your {user.tier.value.title()} plan supports {policy.max_projects} project(s).",
                         need.value if need is not user.tier else None)
    used = await storage.try_consume(user.device_id, period, policy.monthly_audits)
    if used is None:
        need = next_tier(user.tier)
        raise AuditError(429, "QUOTA_EXCEEDED",
                         f"You've used all {policy.monthly_audits} audits included in your {user.tier.value.title()} plan this month.",
                         need.value if need is not user.tier else None)
    try:
        prepared = await svc.prepare(body, policy)
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
