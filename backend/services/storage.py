from __future__ import annotations

import asyncio
import json
import time
from dataclasses import dataclass
from datetime import date, datetime, timezone
from typing import Any

import aiosqlite

from models import Tier

SCHEMA = """
CREATE TABLE IF NOT EXISTS users(
  device_id TEXT PRIMARY KEY, tier TEXT NOT NULL DEFAULT 'FREE',
  expires_at TEXT, purchase_token TEXT, created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS usage(
  device_id TEXT NOT NULL, period TEXT NOT NULL, count INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(device_id, period));
CREATE TABLE IF NOT EXISTS device_projects(
  device_id TEXT NOT NULL, project_id TEXT NOT NULL, PRIMARY KEY(device_id, project_id));
CREATE TABLE IF NOT EXISTS snapshots(
  project_id TEXT NOT NULL, day TEXT NOT NULL, payload TEXT NOT NULL,
  PRIMARY KEY(project_id, day));
CREATE TABLE IF NOT EXISTS api_cache(
  key TEXT PRIMARY KEY, payload TEXT NOT NULL, fetched_at REAL NOT NULL);
"""


@dataclass
class UserRecord:
    device_id: str
    tier: Tier
    expires_at: datetime | None


class Storage:
    def __init__(self, path: str) -> None:
        self._path = path
        self._db: aiosqlite.Connection | None = None
        self._lock = asyncio.Lock()

    async def init(self) -> None:
        self._db = await aiosqlite.connect(self._path)
        self._db.row_factory = aiosqlite.Row
        await self._db.executescript(SCHEMA)
        await self._db.commit()

    async def close(self) -> None:
        if self._db:
            await self._db.close()

    @property
    def db(self) -> aiosqlite.Connection:
        assert self._db is not None, "Storage not initialised"
        return self._db

    # ---- users / tiers -------------------------------------------------
    async def get_user(self, device_id: str) -> UserRecord:
        async with self._lock:
            await self.db.execute(
                "INSERT OR IGNORE INTO users(device_id, tier, created_at) VALUES(?, 'FREE', ?)",
                (device_id, datetime.now(timezone.utc).isoformat()),
            )
            cur = await self.db.execute("SELECT tier, expires_at FROM users WHERE device_id=?", (device_id,))
            row = await cur.fetchone()
            tier = Tier(row["tier"])
            expires = datetime.fromisoformat(row["expires_at"]) if row["expires_at"] else None
            if tier is not Tier.FREE and expires and expires < datetime.now(timezone.utc):
                await self.db.execute("UPDATE users SET tier='FREE' WHERE device_id=?", (device_id,))
                tier = Tier.FREE
            await self.db.commit()
            return UserRecord(device_id, tier, expires)

    async def set_tier(self, device_id: str, tier: Tier, expires_at: datetime, token: str) -> None:
        async with self._lock:
            # A purchase token belongs to one device at a time (latest verification wins).
            await self.db.execute(
                "UPDATE users SET tier='FREE', purchase_token=NULL WHERE purchase_token=? AND device_id!=?",
                (token, device_id),
            )
            await self.db.execute(
                "INSERT INTO users(device_id, tier, expires_at, purchase_token, created_at) VALUES(?,?,?,?,?) "
                "ON CONFLICT(device_id) DO UPDATE SET tier=excluded.tier, expires_at=excluded.expires_at, "
                "purchase_token=excluded.purchase_token",
                (device_id, tier.value, expires_at.isoformat(), token, datetime.now(timezone.utc).isoformat()),
            )
            await self.db.commit()

    # ---- quota ---------------------------------------------------------
    async def usage(self, device_id: str, period: str) -> int:
        cur = await self.db.execute("SELECT count FROM usage WHERE device_id=? AND period=?", (device_id, period))
        row = await cur.fetchone()
        return int(row["count"]) if row else 0

    async def try_consume(self, device_id: str, period: str, limit: int) -> int | None:
        """Atomically reserve one audit. Returns the new count, or None if the quota is exhausted."""
        async with self._lock:
            used = await self.usage(device_id, period)
            if used >= limit:
                return None
            await self.db.execute(
                "INSERT INTO usage(device_id, period, count) VALUES(?,?,1) "
                "ON CONFLICT(device_id, period) DO UPDATE SET count=count+1",
                (device_id, period),
            )
            await self.db.commit()
            return used + 1

    async def refund(self, device_id: str, period: str) -> None:
        async with self._lock:
            await self.db.execute(
                "UPDATE usage SET count=MAX(count-1,0) WHERE device_id=? AND period=?", (device_id, period)
            )
            await self.db.commit()

    # ---- projects ------------------------------------------------------
    async def register_project(self, device_id: str, project_id: str, max_projects: int) -> bool:
        async with self._lock:
            cur = await self.db.execute(
                "SELECT 1 FROM device_projects WHERE device_id=? AND project_id=?", (device_id, project_id)
            )
            if await cur.fetchone():
                return True
            cur = await self.db.execute("SELECT COUNT(*) AS n FROM device_projects WHERE device_id=?", (device_id,))
            if (await cur.fetchone())["n"] >= max_projects:
                return False
            await self.db.execute(
                "INSERT INTO device_projects(device_id, project_id) VALUES(?,?)", (device_id, project_id)
            )
            await self.db.commit()
            return True

    async def tracked_projects(self) -> list[str]:
        cur = await self.db.execute("SELECT DISTINCT project_id FROM device_projects")
        return [r["project_id"] for r in await cur.fetchall()]

    # ---- snapshots -----------------------------------------------------
    async def save_snapshot(self, project_id: str, day: date, normalized: dict[str, Any]) -> None:
        async with self._lock:
            await self.db.execute(
                "INSERT INTO snapshots(project_id, day, payload) VALUES(?,?,?) "
                "ON CONFLICT(project_id, day) DO UPDATE SET payload=excluded.payload",
                (project_id, day.isoformat(), json.dumps(normalized)),
            )
            await self.db.commit()

    async def has_snapshot(self, project_id: str, day: date) -> bool:
        cur = await self.db.execute(
            "SELECT 1 FROM snapshots WHERE project_id=? AND day=?", (project_id, day.isoformat())
        )
        return await cur.fetchone() is not None

    async def get_snapshots(self, project_id: str, start: date, end: date) -> list[tuple[date, dict[str, Any]]]:
        cur = await self.db.execute(
            "SELECT day, payload FROM snapshots WHERE project_id=? AND day BETWEEN ? AND ? ORDER BY day",
            (project_id, start.isoformat(), end.isoformat()),
        )
        return [(date.fromisoformat(r["day"]), json.loads(r["payload"])) for r in await cur.fetchall()]

    # ---- response cache (protects Clarity's 10 requests/project/day cap) -
    async def cache_get(self, key: str, ttl: int) -> Any | None:
        cur = await self.db.execute("SELECT payload, fetched_at FROM api_cache WHERE key=?", (key,))
        row = await cur.fetchone()
        if row and time.time() - row["fetched_at"] < ttl:
            return json.loads(row["payload"])
        return None

    async def cache_put(self, key: str, payload: Any) -> None:
        async with self._lock:
            await self.db.execute(
                "INSERT INTO api_cache(key, payload, fetched_at) VALUES(?,?,?) "
                "ON CONFLICT(key) DO UPDATE SET payload=excluded.payload, fetched_at=excluded.fetched_at",
                (key, json.dumps(payload), time.time()),
            )
            await self.db.commit()
