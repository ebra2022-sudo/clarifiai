from __future__ import annotations

import base64
import json
import os
import time
from typing import Any, AsyncIterator, Callable

import httpx
import pytest
from cryptography.fernet import Fernet

from config import Settings
from main import app
from services.clarity_ai import AuditError, AuditService, ClarityClient
from services.projects import TokenVault
from services.storage import Storage

DEVICE = "test-device-0001-abcd"
# Set to a throwaway postgres:// URL to run the suite against Postgres instead of SQLite. Its data is wiped.
TEST_DATABASE_URL = os.environ.get("TEST_DATABASE_URL", "")


def clarity_jwt(sub: str = "3497143025250713", **overrides) -> str:
    """A token shaped like Clarity's data-export JWTs (signature is not checked locally)."""
    enc = lambda d: base64.urlsafe_b64encode(json.dumps(d).encode()).decode().rstrip("=")
    claims = {"iss": "clarity", "aud": "clarity.data-exporter", "scope": "Data.Export", "sub": sub,
              "exp": int(time.time()) + 3600, **overrides}
    return f"{enc({'alg': 'RS256', 'typ': 'JWT'})}.{enc(claims)}.signature"


def clarity_payload(url: str = "https://shop.example.com/checkout?x=1", sessions: int = 100) -> list[dict[str, Any]]:
    """A minimal project-live-insights response with one URL row per metric."""
    return [
        {"metricName": "Traffic", "information": [{"totalSessionCount": str(sessions), "distinctUserCount": "80", "Url": url}]},
        {"metricName": "RageClickCount", "information": [{"sessionsCount": "5", "pagesViews": "9", "subTotal": "12", "Url": url}]},
        {"metricName": "DeadClickCount", "information": [{"sessionsCount": "10", "pagesViews": "15", "subTotal": "20", "Url": url}]},
    ]


class FakeClaude:
    """Stands in for ClaudeService; yields pre-baked chunks or raises."""

    def __init__(self) -> None:
        self.chunks: list[str] = [
            "### 🚨 Immediate Hotfixes (High Frustration)\n- fix checkout\n\n",
            "### 📉 Friction Trends & User Drop-off\n- drop at cart\n\n### 🚀 Strat",
            "egic Next-Sprint Product Roadmap\n- one-click pay\n",
        ]
        self.error: AuditError | None = None
        self.calls: list[dict[str, Any]] = []
        self.frames: list[list[str]] = []

    async def stream_backlog(self, payload: dict[str, Any], frames: list[str] | None = None) -> AsyncIterator[str]:
        self.calls.append(payload)
        self.frames.append(list(frames or []))
        for c in self.chunks:
            yield c
        if self.error:
            raise self.error


class ClarityMock:
    """httpx transport that impersonates the Clarity export endpoint."""

    def __init__(self) -> None:
        self.handler: Callable[[httpx.Request], httpx.Response] = lambda r: httpx.Response(200, json=clarity_payload())
        self.requests: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        return self.handler(request)


@pytest.fixture
def settings(tmp_path) -> Settings:
    return Settings(
        anthropic_api_key="test", clarity_api_token="clarity-token",
        database_path=str(tmp_path / "test.db"), database_url=TEST_DATABASE_URL, dev_allow_tier_override=False,
        token_encryption_key=Fernet.generate_key().decode(),
    )


@pytest.fixture
async def storage(settings) -> AsyncIterator[Storage]:
    if TEST_DATABASE_URL:  # start every test from an empty Postgres schema
        import asyncpg
        conn = await asyncpg.connect(TEST_DATABASE_URL)
        await conn.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public")
        await conn.close()
    s = Storage(settings.database_url or settings.database_path)
    await s.init()
    yield s
    await s.close()


@pytest.fixture
def clarity_mock() -> ClarityMock:
    return ClarityMock()


@pytest.fixture
def claude() -> FakeClaude:
    return FakeClaude()


@pytest.fixture
async def client(settings, storage, clarity_mock, claude) -> AsyncIterator[httpx.AsyncClient]:
    clarity_http = httpx.AsyncClient(transport=httpx.MockTransport(clarity_mock))
    app.state.settings = settings
    app.state.storage = storage
    vault = TokenVault(settings.token_encryption_key)
    clarity = ClarityClient(settings, storage, clarity_http)
    app.state.audit = AuditService(settings, storage, clarity, claude, vault)  # type: ignore[arg-type]
    app.state.clarity, app.state.vault = clarity, vault
    app.state.billing = None
    # ASGITransport does not run the lifespan, so the state above is all the app sees.
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as c:
        yield c
    await clarity_http.aclose()


def headers(device: str = DEVICE, **extra: str) -> dict[str, str]:
    return {"X-Device-Id": device, **extra}


def ndjson(resp: httpx.Response) -> list[dict[str, Any]]:
    return [json.loads(line) for line in resp.text.splitlines() if line.strip()]
