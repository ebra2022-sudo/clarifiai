"""Verify backend configuration before running real audits.

    .venv/bin/python check_setup.py                    # Anthropic key, model, Play billing config
    .venv/bin/python check_setup.py --clarity <id>     # also test an operator-configured project's token

The Clarity check is opt-in because each call uses 1 of the project's 10 export requests per day.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import sys

import anthropic
import httpx
from pydantic import ValidationError

from config import Settings

OK, FAIL, WARN = "\033[32m✓\033[0m", "\033[31m✗\033[0m", "\033[33m!\033[0m"


async def check_anthropic(s: Settings) -> bool:
    client = anthropic.AsyncAnthropic(api_key=s.anthropic_api_key)
    try:
        model = await client.models.retrieve(s.claude_model)  # free call: validates key and model ID
        print(f"{OK} Anthropic key works; model '{model.id}' is available")
        return True
    except anthropic.AuthenticationError:
        print(f"{FAIL} ANTHROPIC_API_KEY was rejected. Create a new key in the Claude Console.")
    except anthropic.NotFoundError:
        print(f"{FAIL} CLAUDE_MODEL '{s.claude_model}' was not found for this key.")
    except anthropic.APIConnectionError:
        print(f"{FAIL} Could not reach api.anthropic.com.")
    except anthropic.APIStatusError as exc:
        print(f"{FAIL} Anthropic API error HTTP {exc.status_code}.")
    return False


async def check_clarity(s: Settings, project_id: str) -> bool:
    token = s.token_for(project_id)
    if not token:
        print(f"{FAIL} No Clarity token for '{project_id}'. Set CLARITY_API_TOKEN or add it to CLARITY_TOKENS_JSON.")
        return False
    async with httpx.AsyncClient(timeout=30) as http:
        r = await http.get(f"{s.clarity_base_url}/project-live-insights",
                           params={"numOfDays": "1", "dimension1": "URL"},
                           headers={"Authorization": f"Bearer {token}"})
    if r.status_code == 200:
        metrics = sorted({b.get("metricName") for b in r.json() if isinstance(b, dict)})
        print(f"{OK} Clarity token works for '{project_id}'. Metrics returned: {', '.join(map(str, metrics)) or 'none yet'}")
        return True
    hint = {401: "token missing, invalid or expired", 403: "token not authorised for this project",
            429: "daily limit of 10 requests reached; try again tomorrow"}.get(r.status_code, r.text[:200])
    print(f"{FAIL} Clarity returned HTTP {r.status_code}: {hint}")
    return False


def check_billing(s: Settings) -> None:
    if not (s.play_package_name and s.google_service_account_file):
        print(f"{WARN} Play billing not configured (fine for testing with DEV_ALLOW_TIER_OVERRIDE).")
        return
    try:
        with open(s.google_service_account_file) as f:
            email = json.load(f).get("client_email", "?")
        print(f"{OK} Service account file loads ({email}); package '{s.play_package_name}'")
    except (OSError, json.JSONDecodeError) as exc:
        print(f"{FAIL} GOOGLE_SERVICE_ACCOUNT_FILE could not be read: {exc}")


async def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--clarity", metavar="PROJECT_ID", help="also test this project's Clarity token")
    args = parser.parse_args()
    try:
        s = Settings()  # type: ignore[call-arg]
    except ValidationError:
        print(f"{FAIL} ANTHROPIC_API_KEY is not set. Copy .env.example to .env and fill it in.")
        return 1
    ok = await check_anthropic(s)
    if args.clarity:
        ok = await check_clarity(s, args.clarity) and ok
    check_billing(s)
    if not s.token_encryption_key:
        print(f"{FAIL} TOKEN_ENCRYPTION_KEY is empty: users can't connect Clarity projects. See .env.example.")
        ok = False
    else:
        try:
            from services.projects import TokenVault
            TokenVault(s.token_encryption_key)
            print(f"{OK} TOKEN_ENCRYPTION_KEY is valid")
        except ValueError:
            print(f"{FAIL} TOKEN_ENCRYPTION_KEY is not a valid Fernet key.")
            ok = False
    if s.dev_allow_tier_override:
        print(f"{WARN} DEV_ALLOW_TIER_OVERRIDE is on: clients can pick their own tier. Turn it off in production.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
