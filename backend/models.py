from __future__ import annotations

from datetime import date, datetime, timezone
from enum import Enum

from pydantic import BaseModel, Field, model_validator


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


class AuditRequest(BaseModel):
    project_id: str = Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_\-]+$")
    timeframe: Timeframe
    start_date: date | None = None
    end_date: date | None = None

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
