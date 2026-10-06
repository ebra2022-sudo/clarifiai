from __future__ import annotations

import json
from typing import Any, AsyncIterator, Callable

import httpx
import pytest

from config import Settings
from main import app
from services.clarity_ai import AuditError, AuditService, ClarityClient
from services.storage import Storage

DEVICE = "test-device-0001-abcd"


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

    async def stream_backlog(self, payload: dict[str, Any]) -> AsyncIterator[str]:
        self.calls.append(payload)
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
        database_path=str(tmp_path / "test.db"), dev_allow_tier_override=False,
    )


@pytest.fixture
async def storage(settings) -> AsyncIterator[Storage]:
    s = Storage(settings.database_path)
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
    app.state.audit = AuditService(settings, storage, ClarityClient(settings, storage, clarity_http), claude)  # type: ignore[arg-type]
    app.state.billing = None
    # ASGITransport does not run the lifespan, so the state above is all the app sees.
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as c:
        yield c
    await clarity_http.aclose()


def headers(device: str = DEVICE, **extra: str) -> dict[str, str]:
    return {"X-Device-Id": device, **extra}


def ndjson(resp: httpx.Response) -> list[dict[str, Any]]:
    return [json.loads(line) for line in resp.text.splitlines() if line.strip()]
