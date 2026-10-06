"""A thin async database layer so Storage runs on SQLite (local dev, tests) and Postgres (hosted).

Storage writes portable SQL with `?` placeholders; the Postgres backend rewrites them to `$1, $2, ...`.
"""
from __future__ import annotations

import re
from typing import Any, Sequence
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import aiosqlite


class Cursor:
    def __init__(self, rows: Sequence[Any], rowcount: int) -> None:
        self._rows, self.rowcount = list(rows), rowcount

    async def fetchone(self) -> Any | None:
        return self._rows[0] if self._rows else None

    async def fetchall(self) -> list[Any]:
        return self._rows


class Database:
    dialect: str

    async def execute(self, sql: str, params: Sequence[Any] = ()) -> Cursor:
        raise NotImplementedError

    async def executescript(self, script: str) -> None:
        raise NotImplementedError

    async def commit(self) -> None:
        pass

    async def close(self) -> None:
        pass


class SqliteDatabase(Database):
    dialect = "sqlite"

    def __init__(self, conn: aiosqlite.Connection) -> None:
        self._conn = conn

    @classmethod
    async def open(cls, path: str) -> "SqliteDatabase":
        conn = await aiosqlite.connect(path)
        conn.row_factory = aiosqlite.Row
        return cls(conn)

    async def execute(self, sql: str, params: Sequence[Any] = ()) -> Cursor:
        cur = await self._conn.execute(sql, tuple(params))
        rows = await cur.fetchall()
        return Cursor(rows, cur.rowcount)

    async def executescript(self, script: str) -> None:
        await self._conn.executescript(script)

    async def commit(self) -> None:
        await self._conn.commit()

    async def close(self) -> None:
        await self._conn.close()


_PLACEHOLDER = re.compile(r"\?")
_ROWCOUNT = re.compile(r"(\d+)$")
# libpq options asyncpg doesn't understand (it would send them to the server as settings and fail).
_UNSUPPORTED_PARAMS = {"channel_binding"}


def _asyncpg_dsn(url: str) -> str:
    parts = urlsplit(url)
    query = [(k, v) for k, v in parse_qsl(parts.query) if k not in _UNSUPPORTED_PARAMS]
    return urlunsplit(parts._replace(query=urlencode(query)))


class PostgresDatabase(Database):
    """Each statement autocommits; Storage's asyncio lock serialises the read-then-write sequences."""

    dialect = "postgres"

    def __init__(self, pool: Any) -> None:
        self._pool = pool

    @classmethod
    async def open(cls, url: str) -> "PostgresDatabase":
        import asyncpg  # only needed when DATABASE_URL is set

        # statement_cache_size=0 keeps this working behind PgBouncer-style poolers (Neon, Supabase, Render).
        pool = await asyncpg.create_pool(_asyncpg_dsn(url), min_size=1, max_size=5, statement_cache_size=0)
        return cls(pool)

    @staticmethod
    def _convert(sql: str) -> str:
        n = 0

        def repl(_: re.Match[str]) -> str:
            nonlocal n
            n += 1
            return f"${n}"

        return _PLACEHOLDER.sub(repl, sql)

    async def execute(self, sql: str, params: Sequence[Any] = ()) -> Cursor:
        sql = self._convert(sql)
        async with self._pool.acquire() as conn:
            if sql.lstrip().upper().startswith(("SELECT", "WITH")):
                rows = await conn.fetch(sql, *params)
                return Cursor(rows, len(rows))
            status = await conn.execute(sql, *params)
            m = _ROWCOUNT.search(status or "")
            return Cursor([], int(m.group(1)) if m else 0)

    async def executescript(self, script: str) -> None:
        async with self._pool.acquire() as conn:
            await conn.execute(script)

    async def close(self) -> None:
        await self._pool.close()


async def open_database(target: str) -> Database:
    """`target` is a postgres:// or postgresql:// URL, or a SQLite file path."""
    if target.startswith(("postgres://", "postgresql://")):
        return await PostgresDatabase.open(target)
    return await SqliteDatabase.open(target)
