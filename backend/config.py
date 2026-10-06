from __future__ import annotations

import json
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    anthropic_api_key: str
    claude_model: str = "claude-sonnet-5-5"
    clarity_api_token: str = ""
    clarity_tokens_json: str = "{}"
    clarity_base_url: str = "https://www.clarity.ms/export-data/api/v1"
    # Microsoft allows 10 export requests per Clarity project per day; keep some for the nightly snapshot.
    clarity_daily_limit: int = 10
    clarity_snapshot_reserve: int = 1
    # Fernet key for encrypting user-connected Clarity tokens at rest.
    token_encryption_key: str = ""
    database_path: str = "clarity_ai.db"
    cache_ttl_seconds: int = 3600
    snapshot_hour_utc: int = 23
    play_package_name: str = ""
    google_service_account_file: str = ""
    dev_allow_tier_override: bool = False

    def token_for(self, project_id: str) -> str | None:
        try:
            mapping = json.loads(self.clarity_tokens_json or "{}")
        except json.JSONDecodeError:
            mapping = {}
        return mapping.get(project_id) or self.clarity_api_token or None


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
