"""User-connected Clarity projects: token inspection and encryption at rest."""
from __future__ import annotations

import base64
import binascii
import hashlib
import json
import time
from dataclasses import dataclass
from datetime import datetime, timezone

from cryptography.fernet import Fernet, InvalidToken


@dataclass(frozen=True)
class ClaritySource:
    """Where an audit's data comes from.

    `key` identifies the underlying Clarity project. Cache entries, snapshots and the daily request budget are
    keyed by it, so two users who connect the same Clarity project share them (Microsoft's limit is per project).
    """
    key: str
    token: str


class TokenError(ValueError):
    pass


def _b64json(part: str) -> dict:
    try:
        return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
    except (binascii.Error, ValueError, UnicodeDecodeError) as exc:
        raise TokenError("This doesn't look like a Clarity API token.") from exc


def inspect_clarity_token(token: str) -> str:
    """Check the token's shape and claims (not its signature; Clarity does that) and return its source key.

    Catches common paste mistakes (wrong token, truncated copy) without spending a Clarity request.
    """
    parts = token.strip().split(".")
    if len(parts) != 3:
        raise TokenError("This doesn't look like a Clarity API token. Copy the whole token from Settings → Data Export.")
    claims = _b64json(parts[1])
    if not isinstance(claims, dict) or claims.get("iss") != "clarity":
        raise TokenError("This token wasn't issued by Microsoft Clarity.")
    if "Data.Export" not in str(claims.get("scope", "")):
        raise TokenError("This Clarity token doesn't have the Data Export scope.")
    exp = claims.get("exp")
    if isinstance(exp, (int, float)) and exp < time.time():
        raise TokenError("This Clarity token has expired. Generate a new one in Settings → Data Export.")
    subject = str(claims.get("sub") or token)
    return "c:" + hashlib.sha256(subject.encode()).hexdigest()[:20]


def token_expiry(token: str) -> str | None:
    """The token's `exp` claim as an ISO timestamp, if it has one. Call after inspect_clarity_token."""
    exp = _b64json(token.strip().split(".")[1]).get("exp")
    if not isinstance(exp, (int, float)):
        return None
    return datetime.fromtimestamp(exp, timezone.utc).isoformat()


class TokenVault:
    """Encrypts Clarity tokens before they touch the database."""

    def __init__(self, key: str) -> None:
        self._fernet = Fernet(key.encode()) if key else None

    @property
    def configured(self) -> bool:
        return self._fernet is not None

    def encrypt(self, token: str) -> str:
        assert self._fernet is not None
        return self._fernet.encrypt(token.encode()).decode()

    def decrypt(self, blob: str) -> str:
        assert self._fernet is not None
        try:
            return self._fernet.decrypt(blob.encode()).decode()
        except InvalidToken as exc:
            raise TokenError("Stored Clarity token can't be decrypted (was TOKEN_ENCRYPTION_KEY changed?).") from exc
