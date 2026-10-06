from __future__ import annotations

from services.db import PostgresDatabase, _asyncpg_dsn


def test_placeholders_become_numbered_for_postgres():
    assert PostgresDatabase._convert("SELECT * FROM t WHERE a=? AND b=?") == "SELECT * FROM t WHERE a=$1 AND b=$2"


def test_dsn_drops_libpq_options_asyncpg_rejects():
    dsn = _asyncpg_dsn("postgresql://u:p@ep-x.neon.tech/db?sslmode=require&channel_binding=require")
    assert dsn == "postgresql://u:p@ep-x.neon.tech/db?sslmode=require"
