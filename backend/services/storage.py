from __future__ import annotations

import asyncio
import json
import time
from dataclasses import dataclass
from datetime import date, datetime, timezone
from typing import Any

from models import Tier
from services.db import Database, open_database

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
CREATE TABLE IF NOT EXISTS connections(
  id TEXT PRIMARY KEY, device_id TEXT NOT NULL, name TEXT NOT NULL, source_key TEXT NOT NULL,
  token_enc TEXT NOT NULL, created_at TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'active',
  clarity_project_id TEXT, token_expires_at TEXT);
CREATE INDEX IF NOT EXISTS connections_device ON connections(device_id);
CREATE TABLE IF NOT EXISTS clarity_usage(
  source_key TEXT NOT NULL, day TEXT NOT NULL, count INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(source_key, day));
"""


@dataclass
class Connection:
    id: str
    device_id: str
    name: str
    source_key: str
    token_enc: str
    created_at: str
    # "active", or "needs_reauth" once Clarity rejects the stored token (it was revoked or expired).
    status: str = "active"
    # The project ID in Clarity's own URLs; only used to deep-link to recordings, which the export API doesn't expose.
    clarity_project_id: str | None = None
    token_expires_at: str | None = None


@dataclass
class UserRecord:
    device_id: str
    tier: Tier
    expires_at: datetime | None


class Storage:
    def __init__(self, target: str) -> None:
        """`target` is a SQLite file path or a postgres:// URL (DATABASE_URL)."""
        self._target = target
        self._db: Database | None = None
        self._lock = asyncio.Lock()

    async def init(self) -> None:
        self._db = await open_database(self._target)
        # Postgres' REAL is single precision, too coarse for epoch timestamps.
        schema = SCHEMA if self.db.dialect == "sqlite" else SCHEMA.replace(" REAL ", " DOUBLE PRECISION ")
        await self.db.executescript(schema)
        await self._migrate()
        await self.db.commit()

    async def _migrate(self) -> None:
        """Add columns introduced after a database was first created (CREATE TABLE IF NOT EXISTS skips them)."""
        added = (("status", "TEXT NOT NULL DEFAULT 'active'"), ("clarity_project_id", "TEXT"), ("token_expires_at", "TEXT"))
        if self.db.dialect == "postgres":
            for col, ddl in added:
                await self.db.execute(f"ALTER TABLE connections ADD COLUMN IF NOT EXISTS {col} {ddl}")
            return
        cur = await self.db.execute("PRAGMA table_info(connections)")
        have = {r["name"] for r in await cur.fetchall()}
        for col, ddl in added:
            if col not in have:
                await self.db.execute(f"ALTER TABLE connections ADD COLUMN {col} {ddl}")

    async def close(self) -> None:
        if self._db:
            await self._db.close()

    @property
    def db(self) -> Database:
        assert self._db is not None, "Storage not initialised"
        return self._db

    # ---- users / tiers -------------------------------------------------
    async def get_user(self, device_id: str) -> UserRecord:
        async with self._lock:
            await self.db.execute(
                "INSERT INTO users(device_id, tier, created_at) VALUES(?, 'FREE', ?) ON CONFLICT(device_id) DO NOTHING",
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
                "ON CONFLICT(device_id, period) DO UPDATE SET count=usage.count+1",
                (device_id, period),
            )
            await self.db.commit()
            return used + 1

    async def refund(self, device_id: str, period: str) -> None:
        async with self._lock:
            await self.db.execute(
                "UPDATE usage SET count=CASE WHEN count>0 THEN count-1 ELSE 0 END WHERE device_id=? AND period=?", (device_id, period)
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

    async def unregister_project(self, device_id: str, project_id: str) -> None:
        async with self._lock:
            await self.db.execute(
                "DELETE FROM device_projects WHERE device_id=? AND project_id=?", (device_id, project_id)
            )
            await self.db.commit()

    async def tracked_projects(self) -> list[str]:
        cur = await self.db.execute("SELECT DISTINCT project_id FROM device_projects")
        return [r["project_id"] for r in await cur.fetchall()]

    # ---- user-connected Clarity projects -------------------------------
    async def add_connection(self, conn: Connection) -> None:
        async with self._lock:
            await self.db.execute(
                "INSERT INTO connections(id, device_id, name, source_key, token_enc, created_at, status, "
                "clarity_project_id, token_expires_at) VALUES(?,?,?,?,?,?,?,?,?) "
                "ON CONFLICT(id) DO UPDATE SET name=excluded.name, source_key=excluded.source_key, "
                "token_enc=excluded.token_enc, status=excluded.status, "
                "clarity_project_id=COALESCE(excluded.clarity_project_id, connections.clarity_project_id), "
                "token_expires_at=excluded.token_expires_at",
                (conn.id, conn.device_id, conn.name, conn.source_key, conn.token_enc, conn.created_at, conn.status,
                 conn.clarity_project_id, conn.token_expires_at),
            )
            await self.db.commit()

    async def list_connections(self, device_id: str) -> list[Connection]:
        cur = await self.db.execute(
            "SELECT * FROM connections WHERE device_id=? ORDER BY created_at", (device_id,)
        )
        return [Connection(**dict(r)) for r in await cur.fetchall()]

    async def get_connection(self, device_id: str, conn_id: str) -> Connection | None:
        cur = await self.db.execute("SELECT * FROM connections WHERE device_id=? AND id=?", (device_id, conn_id))
        row = await cur.fetchone()
        return Connection(**dict(row)) if row else None

    async def find_connection_by_source(self, device_id: str, source_key: str) -> Connection | None:
        cur = await self.db.execute(
            "SELECT * FROM connections WHERE device_id=? AND source_key=?", (device_id, source_key)
        )
        row = await cur.fetchone()
        return Connection(**dict(row)) if row else None

    async def update_connection(self, device_id: str, conn_id: str, name: str | None,
                                clarity_project_id: str | None) -> Connection | None:
        """Rename a project and/or set its Clarity project ID ("" clears it). None leaves a field unchanged."""
        async with self._lock:
            if name is not None:
                await self.db.execute("UPDATE connections SET name=? WHERE device_id=? AND id=?", (name, device_id, conn_id))
            if clarity_project_id is not None:
                await self.db.execute("UPDATE connections SET clarity_project_id=? WHERE device_id=? AND id=?",
                                      (clarity_project_id or None, device_id, conn_id))
            await self.db.commit()
        return await self.get_connection(device_id, conn_id)

    async def mark_needs_reauth(self, source_key: str) -> None:
        """Clarity rejected this project's token: every connection using it must be reconnected."""
        async with self._lock:
            await self.db.execute("UPDATE connections SET status='needs_reauth' WHERE source_key=?", (source_key,))
            await self.db.commit()

    async def delete_connection(self, device_id: str, conn_id: str) -> bool:
        async with self._lock:
            cur = await self.db.execute("DELETE FROM connections WHERE device_id=? AND id=?", (device_id, conn_id))
            await self.db.execute(
                "DELETE FROM device_projects WHERE device_id=? AND project_id=?", (device_id, conn_id)
            )
            await self.db.commit()
            return cur.rowcount > 0

    async def connection_sources(self) -> list[tuple[str, str]]:
        """One (source_key, token_enc) per distinct Clarity project, for the snapshot job."""
        cur = await self.db.execute(
            "SELECT source_key, MAX(token_enc) AS token_enc FROM connections WHERE status='active' GROUP BY source_key"
        )
        return [(r["source_key"], r["token_enc"]) for r in await cur.fetchall()]

    # ---- Clarity daily request budget (Microsoft: 10 per project per day) -
    async def clarity_used(self, source_key: str, day: date) -> int:
        cur = await self.db.execute(
            "SELECT count FROM clarity_usage WHERE source_key=? AND day=?", (source_key, day.isoformat())
        )
        row = await cur.fetchone()
        return int(row["count"]) if row else 0

    async def clarity_try_spend(self, source_key: str, day: date, limit: int) -> bool:
        """Reserve one Clarity request for today, unless `limit` is already reached."""
        async with self._lock:
            if await self.clarity_used(source_key, day) >= limit:
                return False
            await self.db.execute(
                "INSERT INTO clarity_usage(source_key, day, count) VALUES(?,?,1) "
                "ON CONFLICT(source_key, day) DO UPDATE SET count=clarity_usage.count+1",
                (source_key, day.isoformat()),
            )
            await self.db.commit()
            return True

    async def clarity_mark_exhausted(self, source_key: str, day: date, limit: int) -> None:
        """Clarity said 429: stop calling it for this project until tomorrow."""
        async with self._lock:
            await self.db.execute(
                "INSERT INTO clarity_usage(source_key, day, count) VALUES(?,?,?) "
                "ON CONFLICT(source_key, day) DO UPDATE SET "
                "count=CASE WHEN excluded.count>clarity_usage.count THEN excluded.count ELSE clarity_usage.count END",
                (source_key, day.isoformat(), limit),
            )
            await self.db.commit()

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
