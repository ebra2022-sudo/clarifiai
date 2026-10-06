from __future__ import annotations

import asyncio
import re
from datetime import datetime, timezone

import httpx
from google.auth.transport.requests import Request as GoogleRequest
from google.oauth2 import service_account

from config import Settings
from models import Tier

# Play Console subscription product IDs -> tier
PRODUCT_TIERS: dict[str, Tier] = {"clarity_pro": Tier.PRO, "clarity_max": Tier.MAX}

ACTIVE_STATES = {
    "SUBSCRIPTION_STATE_ACTIVE",
    "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
    "SUBSCRIPTION_STATE_CANCELED",  # canceled but paid through expiry
}

API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"


class BillingError(Exception):
    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


def _parse_ts(value: str) -> datetime:
    value = re.sub(r"(\.\d{6})\d+", r"\1", value.replace("Z", "+00:00"))
    return datetime.fromisoformat(value).astimezone(timezone.utc)


class PlayBillingVerifier:
    def __init__(self, settings: Settings, http: httpx.AsyncClient) -> None:
        self._s, self._http = settings, http
        self._creds: service_account.Credentials | None = None

    async def _access_token(self) -> str:
        if not self._s.google_service_account_file or not self._s.play_package_name:
            raise BillingError(503, "BILLING_NOT_CONFIGURED", "Play billing verification is not configured.")
        if self._creds is None:
            self._creds = service_account.Credentials.from_service_account_file(
                self._s.google_service_account_file,
                scopes=["https://www.googleapis.com/auth/androidpublisher"],
            )
        if not self._creds.valid:
            await asyncio.to_thread(self._creds.refresh, GoogleRequest())
        return self._creds.token

    async def verify(self, purchase_token: str, product_id: str) -> tuple[Tier, datetime]:
        tier = PRODUCT_TIERS.get(product_id)
        if tier is None:
            raise BillingError(400, "UNKNOWN_PRODUCT", f"Unknown product '{product_id}'.")
        token = await self._access_token()
        url = f"{API}/{self._s.play_package_name}/purchases/subscriptionsv2/tokens/{purchase_token}"
        try:
            r = await self._http.get(url, headers={"Authorization": f"Bearer {token}"})
        except httpx.HTTPError as exc:
            raise BillingError(502, "PLAY_UNREACHABLE", "Could not reach Google Play to verify the purchase.") from exc
        if r.status_code in (400, 404, 410):
            raise BillingError(402, "INVALID_PURCHASE", "Google Play does not recognise this purchase.")
        if r.status_code != 200:
            raise BillingError(502, "PLAY_ERROR", f"Google Play returned HTTP {r.status_code}.")
        data = r.json()
        if data.get("subscriptionState") not in ACTIVE_STATES:
            raise BillingError(402, "SUBSCRIPTION_INACTIVE", "This subscription is not active.")
        for item in data.get("lineItems", []):
            if item.get("productId") == product_id:
                expiry = _parse_ts(item["expiryTime"])
                if expiry <= datetime.now(timezone.utc):
                    raise BillingError(402, "SUBSCRIPTION_EXPIRED", "This subscription has expired.")
                return tier, expiry
        raise BillingError(402, "PRODUCT_MISMATCH", "Purchase does not match the requested product.")
