from __future__ import annotations

from dataclasses import dataclass

from models import Tier, Timeframe


@dataclass(frozen=True)
class TierPolicy:
    tier: Tier
    monthly_audits: int
    allowed_timeframes: frozenset[Timeframe]
    max_custom_days: int
    top_elements: int
    full_roadmap: bool
    pdf_export: bool
    white_label_pdf: bool
    device_breakdown: bool
    max_projects: int

    def to_features(self) -> dict:
        return {
            "allowed_timeframes": sorted(t.value for t in self.allowed_timeframes),
            "pdf_export": self.pdf_export,
            "white_label_pdf": self.white_label_pdf,
            "top_elements": self.top_elements,
            "device_breakdown": self.device_breakdown,
            "full_roadmap": self.full_roadmap,
            "max_projects": self.max_projects,
        }


T = Timeframe

POLICIES: dict[Tier, TierPolicy] = {
    Tier.FREE: TierPolicy(
        tier=Tier.FREE, monthly_audits=3,
        allowed_timeframes=frozenset({T.TODAY, T.LAST_3_DAYS}),
        max_custom_days=0, top_elements=3, full_roadmap=False, pdf_export=False,
        white_label_pdf=False, device_breakdown=False, max_projects=1,
    ),
    Tier.PRO: TierPolicy(
        tier=Tier.PRO, monthly_audits=60,
        allowed_timeframes=frozenset({T.TODAY, T.LAST_3_DAYS, T.LAST_WEEK}),
        max_custom_days=0, top_elements=10, full_roadmap=True, pdf_export=True,
        white_label_pdf=False, device_breakdown=False, max_projects=5,
    ),
    Tier.MAX: TierPolicy(
        tier=Tier.MAX, monthly_audits=600,  # fair-use ceiling
        allowed_timeframes=frozenset(Timeframe),
        max_custom_days=90, top_elements=10, full_roadmap=True, pdf_export=True,
        white_label_pdf=True, device_breakdown=True, max_projects=25,
    ),
}

TIMEFRAME_MIN_TIER: dict[Timeframe, Tier] = {
    T.TODAY: Tier.FREE, T.LAST_3_DAYS: Tier.FREE, T.LAST_WEEK: Tier.PRO,
    T.LAST_MONTH: Tier.MAX, T.CUSTOM: Tier.MAX,
}

_ORDER = [Tier.FREE, Tier.PRO, Tier.MAX]


def required_tier(tf: Timeframe) -> Tier:
    return TIMEFRAME_MIN_TIER[tf]


def next_tier(t: Tier) -> Tier:
    i = _ORDER.index(t)
    return _ORDER[min(i + 1, len(_ORDER) - 1)]
