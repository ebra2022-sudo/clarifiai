# Clarity AI — Executive Dashboard

Pulls Microsoft Clarity telemetry, sanitises it, streams a Claude-written engineering backlog as NDJSON, and
exports an executive PDF on-device. Free / Pro / Max subscription tiers are enforced server-side.

```
ClarifiAI/
  backend/   FastAPI service: auth by device ID, tiers & quota, Clarity fetch + cache, KPI engine, Claude streaming, Play billing
  app/       Android app (Kotlin, Jetpack Compose, Material 3), package com.clarifiai.app
```

The Android app never talks to Clarity or Anthropic directly; all keys live on the backend.

## Backend

```bash
cd backend
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
cp .env.example .env          # fill in ANTHROPIC_API_KEY and your Clarity token(s)
.venv/bin/uvicorn main:app --host 0.0.0.0 --port 8000
.venv/bin/python -m pytest    # unit + API tests (Clarity and Anthropic are mocked)
```

| Variable | Purpose |
| --- | --- |
| `ANTHROPIC_API_KEY`, `CLAUDE_MODEL` | Claude access and model name (default `claude-sonnet-5-5`). |
| `CLARITY_API_TOKEN` / `CLARITY_TOKENS_JSON` | Single token, or per-project map `{"projectId": "token"}` (map wins). |
| `DATABASE_PATH`, `CACHE_TTL_SECONDS`, `SNAPSHOT_HOUR_UTC` | SQLite file, Clarity cache TTL (default 3600s), hour after which the daily snapshot job runs. |
| `PLAY_PACKAGE_NAME`, `GOOGLE_SERVICE_ACCOUNT_FILE` | Play subscription verification. |
| `DEV_ALLOW_TIER_OVERRIDE` | Testing only: accepts the `X-Dev-Tier` header. **Must stay `false` in production.** |

### API

All requests need `X-Device-Id` (16–64 chars, alphanumeric or dash). Errors are
`{"detail": {"code", "message", "required_tier"}}`.

| Endpoint | Purpose |
| --- | --- |
| `GET /health` | Liveness. |
| `GET /api/v1/account/entitlements` | Tier, expiry, monthly usage and feature flags. |
| `POST /api/v1/subscription/verify` | `{purchase_token, product_id}` → verifies with Google Play, upgrades the tier. |
| `POST /api/v1/analytics/audit` | `{project_id, timeframe, start_date?, end_date?}` → streams `application/x-ndjson`. |

NDJSON events: `meta`, `delta…`, `locked` (Free only), then `done` or `error`. A failed or abandoned
stream refunds the reserved audit.

## Tiers

Defined in `backend/services/tiers.py` (authoritative) and mirrored for display in `ui/PaywallSheet.kt`.

| | Free | Pro | Max |
| --- | --- | --- | --- |
| Audits / month | 3 | 60 | 600 (fair use) |
| Timeframes | Today, Last 3 days | + Last 7 days | + Last 30 days, Custom (≤ 90 days) |
| Top friction targets | 3 | 10 | 10 |
| Report sections | Hotfixes + Trends | + Strategic Roadmap | + Strategic Roadmap |
| PDF export | No | Yes | Yes, white-label |
| Device breakdown | No | No | Yes |
| Projects | 1 | 5 | 25 |
| Play product ID | – | `clarity_pro` | `clarity_max` |

## Android

Open the project root in Android Studio, or:

```bash
./gradlew :app:assembleDebug
```

- Debug builds call `http://10.0.2.2:8000/` (the host machine from the emulator). On a physical device run
  `adb reverse tcp:8000 tcp:8000` and change the debug `BASE_URL` to `http://localhost:8000/`. Cleartext HTTP
  is allowed only in debug builds (`app/src/debug/res/xml/network_security_config.xml`).
- Release builds call `BASE_URL` in `app/build.gradle.kts` — set it to your HTTPS backend.
- **Testing paid tiers without Google Play:** start the backend with `DEV_ALLOW_TIER_OVERRIDE=true` and build
  with `./gradlew :app:installDebug -Pclarity.devTier=PRO` (or `MAX`), or put `clarity.devTier=PRO` in
  `~/.gradle/gradle.properties`. Debug builds then send `X-Dev-Tier`; release builds never do.

## Known constraints

- Clarity's export API (`project-live-insights`) returns at most the last 3 days and allows 10 requests per
  project per day. Longer ranges are assembled from daily snapshots this server records, and coverage is
  reported in `meta.notes`. Responses are cached for `CACHE_TTL_SECONDS`.
- Clarity exports by dimension (URL, Device…), not CSS selectors, so friction targets are page paths.
- Accounts are keyed by an install-generated device ID, so a reinstall resets the free quota. Add real auth
  (Firebase Auth / Play Integrity) before launch.
