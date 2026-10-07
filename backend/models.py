from __future__ import annotations

import base64
import binascii
from datetime import date, datetime, timezone
from enum import Enum

from pydantic import BaseModel, Field, field_validator, model_validator


def image_media_type(head: bytes) -> str | None:
    if head.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if head.startswith(b"\x89PNG"):
        return "image/png"
    return None


class Timeframe(str, Enum):
    TODAY = "TODAY"
    LAST_3_DAYS = "LAST_3_DAYS"
    LAST_WEEK = "LAST_WEEK"
    LAST_MONTH = "LAST_MONTH"
    CUSTOM = "CUSTOM"


class Tier(str, Enum):
    FREE = "FREE"
    PRO = "PRO"
    MAX = "MAX"


MAX_RECORDING_FRAMES = 12
MAX_FRAME_BASE64_CHARS = 700_000  # ~500 KB JPEG; the app sends ~1024 px frames at ~100 KB


class AuditRequest(BaseModel):
    project_id: str = Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_\-]+$")
    timeframe: Timeframe
    start_date: date | None = None
    end_date: date | None = None
    # Frames from session recordings (or screenshots) the user attached, base64 JPEG/PNG. Clarity has no recordings
    # API, so this is how real sessions reach the analysis. Never stored.
    recording_frames: list[str] = Field(default_factory=list, max_length=MAX_RECORDING_FRAMES)
    recording_note: str | None = Field(default=None, max_length=500)

    @field_validator("recording_frames")
    @classmethod
    def _validate_frames(cls, frames: list[str]) -> list[str]:
        for f in frames:
            if len(f) > MAX_FRAME_BASE64_CHARS:
                raise ValueError("A recording frame is too large; send frames of about 1024 px.")
            try:
                head = base64.b64decode(f[:24], validate=True)
            except (binascii.Error, ValueError) as exc:
                raise ValueError("Recording frames must be base64-encoded images.") from exc
            if image_media_type(head) is None:
                raise ValueError("Recording frames must be JPEG or PNG images.")
        return frames

    @model_validator(mode="after")
    def _validate_dates(self) -> "AuditRequest":
        if self.timeframe is Timeframe.CUSTOM:
            if not self.start_date or not self.end_date:
                raise ValueError("start_date and end_date are required for CUSTOM timeframe")
            if self.start_date > self.end_date:
                raise ValueError("start_date must be on or before end_date")
            if self.end_date > datetime.now(timezone.utc).date():
                raise ValueError("end_date cannot be in the future")
            if (self.end_date - self.start_date).days + 1 > 90:
                raise ValueError("CUSTOM range cannot exceed 90 days")
        else:
            self.start_date = None
            self.end_date = None
        return self


class SubscriptionVerifyRequest(BaseModel):
    purchase_token: str = Field(min_length=10, max_length=4096)
    product_id: str = Field(min_length=1, max_length=128)


# Clarity's own project ID, as in clarity.microsoft.com/projects/view/<id>/dashboard.
CLARITY_PROJECT_ID = r"^[A-Za-z0-9]{4,32}$"


class ConnectProjectRequest(BaseModel):
    name: str = Field(min_length=1, max_length=40)
    clarity_token: str = Field(min_length=20, max_length=8192)
    clarity_project_id: str | None = Field(default=None, pattern=CLARITY_PROJECT_ID)


class UpdateProjectRequest(BaseModel):
    """Rename a project or set its Clarity project ID ("" clears it). Omitted fields are left as they are."""
    name: str | None = Field(default=None, min_length=1, max_length=40)
    clarity_project_id: str | None = Field(default=None, pattern=r"^(?:[A-Za-z0-9]{4,32})?$")


class ReconnectProjectRequest(BaseModel):
    clarity_token: str = Field(min_length=20, max_length=8192)
