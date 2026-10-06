# Going live: keys and configuration

Three stages, each usable on its own:

| Stage | You get | You need |
| --- | --- | --- |
| **1. Real audits on your machine** | Real Clarity data analysed by Claude, in the emulator | Anthropic API key, an encryption key, a Clarity project + API token |
| **2. Real phone / other people** | The app working anywhere | A hosted backend with HTTPS |
| **3. Paid plans** | Pro / Max subscriptions through Google Play | Play Console app, subscription products, service account |

All secrets live in `backend/.env` (git-ignored) or your host's secret settings. Never put them in the Android app.

---

## Stage 1: Real audits locally

### 1a. Anthropic API key (Claude)

1. Go to the Claude Console at <https://console.anthropic.com> and sign up or log in.
2. **Settings → Billing**: add a payment method and buy prepaid credits ($5 is plenty to start).
3. **Settings → Limits**: optionally set a monthly spend limit.
4. **API Keys → Create Key**: name it `clarity-backend` and copy it. It starts with `sk-ant-` and is shown only once.

Cost: the backend uses `claude-sonnet-5-5` ($2 per million input tokens, $10 per million output tokens). One audit is
roughly 4–6k input tokens and up to ~4k output tokens, so **about 2–5 cents per audit**. A Max user running their full
600 audits a month costs you about $12–30.

### 1b. Microsoft Clarity project and API token

1. Sign in at <https://clarity.microsoft.com> and create a project for your website.
2. Install the Clarity tracking script on the site (Clarity shows the snippet, or use a plugin for WordPress, Shopify
   and so on). Data starts appearing within a few hours; give it real traffic before judging the audits.
3. **Project ID**: under **Settings → Overview**. It's also the code after `/tag/` in the tracking snippet.
4. **API token**: **Settings → Data Export → Generate new API token**. Give it a name (4–32 letters, digits, `-`, `_`
   or `.`) and copy the token. Only project **admins** can create tokens.

**Users add their own projects in the app.** A Clarity token belongs to exactly one project, so the app has a
**Connect Clarity project** screen: the user names the project and pastes its token. The server checks it with one
real Clarity request (that data is kept, not wasted), stores the token **encrypted**, and never sends it back to the
app. Connected projects count toward the plan's project limit (Free 1, Pro 5, Max 25) and can be removed from the
project picker.

**Websites and mobile apps both work.** For mobile-app projects Clarity reports taps and app errors as app-wide totals
(its export doesn't break them down by screen), plus popular screens, devices, OS and countries. The report says so.

Clarity's limits, which the backend already works around:
- **10 export requests per project per day.** The backend counts its own calls per project: users get up to **9 data
  refreshes a day** (shown in the project picker) and 1 is kept for the nightly snapshot. Responses are cached for an
  hour, so repeat audits are free. A Today audit uses 1 request (plus 1 for Max's device breakdown on websites; mobile
  projects already include devices). When the budget is used up, the server stops calling Clarity, Today reports fall
  back to data saved earlier that day, and new data is available after 00:00 UTC.
- **Only the last 1–3 days.** Longer ranges are built from daily snapshots the backend records, so 7- and 30-day
  reports fill in over the following days. The backend must be running each day for that.

### 1c. Configure and check

```bash
cd backend
cp .env.example .env
```

Edit `backend/.env`:

```ini
ANTHROPIC_API_KEY=sk-ant-...your key...
CLAUDE_MODEL=claude-sonnet-5-5

# Encrypts users' Clarity tokens at rest. Generate once, keep secret, never change it afterwards:
#   .venv/bin/python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
TOKEN_ENCRYPTION_KEY=<generated key>

# local testing only, so the debug app can act as Pro/Max:
DEV_ALLOW_TIER_OVERRIDE=true
```

Verify (free, no Clarity request used):

```bash
.venv/bin/python check_setup.py
```


### 1d. Run it

```bash
cd backend && .venv/bin/uvicorn main:app --host 0.0.0.0 --port 8000
```

In another terminal, from the project root:

```bash
./gradlew :app:installDebug -Pclarity.devTier=PRO   # or MAX, or leave it out for Free
```

Open the app in the emulator, tap **Connect Clarity project**, paste your token, then tap **Run audit**.

---

## Stage 2: Real phone and other users

**Quick test on your own phone (backend still on your Mac):** connect the phone by USB, run
`adb reverse tcp:8000 tcp:8000`, and put `clarity.baseUrl=http://localhost:8000/` in `local.properties`.

### 2a. Free Postgres (Neon)

Render's free disk is wiped on every deploy and whenever the free instance sleeps, so saved projects, tokens and
usage need a real database. Neon's free plan doesn't expire.

1. Sign up at <https://neon.tech> → **Create project** (any name, region close to your Render region).
2. **Connection string** → copy it. It looks like
   `postgresql://user:password@ep-xxx.region.aws.neon.tech/neondb?sslmode=require`.

The backend creates its tables on first start. (Render's own free Postgres works too, but it expires after 30 days.)

### 2b. Deploy the backend on Render (free)

The repo has a Blueprint (`render.yaml`) that sets everything up.

1. <https://dashboard.render.com> → **New → Blueprint** → connect GitHub and pick `clarifiai`.
2. Render reads `render.yaml` and asks for the secret values:
   - `ANTHROPIC_API_KEY`: your Claude key.
   - `TOKEN_ENCRYPTION_KEY`: the same key as in your local `.env`, or a new one (generate it as in 1c). Keep it
     safe; if it changes, every connected project has to be connected again.
   - `DATABASE_URL`: the Neon connection string from 2a.
3. **Apply**. The first build takes a few minutes. When it's live, open
   `https://<service-name>.onrender.com/health`. It should show `{"status":"ok"}`.

Every push to `main` redeploys automatically. Free-plan limits to know:
- The service **sleeps after 15 minutes** without traffic. The first request after that takes up to a minute; the app
  waits for it.
- The daily snapshot job only runs while the service is awake, so 7- and 30-day history fills in more slowly. A free
  uptime pinger (e.g. cron-job.org hitting `/health` every 10 minutes) keeps it awake.
- `DEV_ALLOW_TIER_OVERRIDE` is `true` in the Blueprint so debug builds can test Pro/Max. Set it to `false` in the
  Render dashboard before real users arrive.

### 2c. Point the app at it

In `local.properties` (git-ignored) add:

```properties
clarity.baseUrl=https://<service-name>.onrender.com/
# optional, debug builds only: test paid features such as PDF export
clarity.devTier=MAX
```

Then plug in the phone (USB debugging on) and press **Run** in Android Studio, or run `./gradlew installDebug`. The
phone now works on any network. Release builds use the same `clarity.baseUrl`.

---

## Stage 3: Google Play subscriptions

1. **Play Console account**: <https://play.google.com/console>. There's a one-time $25 fee plus identity verification.
   Add a **payments profile** (Setup → Payments profile) so you can sell.
   New personal accounts must run a **closed test with at least 12 testers for 14 days** before publishing to production.
2. **Create the app** with package name `com.clarifiai.app`.
3. **Upload a signed build** to **Testing → Internal testing**. In Android Studio use **Build → Generate Signed App
   Bundle**, create an upload keystore and **back it up**. Play only lets you create subscriptions after a build that
   uses the Billing library has been uploaded.
4. **Monetize → Products → Subscriptions → Create subscription**, twice:
   - Product ID **`clarity_pro`**, then **`clarity_max`**. These must match exactly.
   - Add a base plan to each (auto-renewing, monthly), set prices and **Activate**.
5. **Test purchases without being charged**: **Settings → License testing**, then add your Gmail accounts. On a device
   signed in with one of those accounts, install the app from the internal-testing link.
6. **Service account for server-side verification**:
   1. In Google Cloud Console (<https://console.cloud.google.com>), create or pick a project and **enable the
      "Google Play Android Developer API"**.
   2. **IAM & Admin → Service accounts → Create**, then **Keys → Add key → JSON**. Download the file.
   3. In Play Console, **Users and permissions → Invite new users**, enter the service account's email and grant
      *View financial data* and *Manage orders and subscriptions* for this app. Permissions can take up to 24 hours
      to start working.
7. **Configure the backend**:

```ini
PLAY_PACKAGE_NAME=com.clarifiai.app
GOOGLE_SERVICE_ACCOUNT_FILE=/secure/path/play-service-account.json   # keep outside the repo
```

`check_setup.py` confirms the file loads. A completed test purchase upgrades the account to Pro or Max.

---

## Production checklist

- [ ] `DEV_ALLOW_TIER_OVERRIDE=false` on the server.
- [ ] `.env` and the service-account JSON are not in git (`git status` should never show them).
- [ ] `TOKEN_ENCRYPTION_KEY` set on the server and backed up.
- [ ] `clarity.baseUrl` points at your HTTPS backend.
- [ ] `DATABASE_URL` points at a persistent Postgres database.
- [ ] Spend limit set in the Claude Console.
- [ ] Upload keystore backed up somewhere safe.
- [ ] Plan for real accounts (Firebase Auth or Play Integrity). Today accounts are keyed by an install ID, so
      reinstalling the app resets the Free quota.
