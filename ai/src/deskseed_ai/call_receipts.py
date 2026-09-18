from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import Callable
from uuid import UUID

from .pricing import Usage


class UsageStatus(StrEnum):
    KNOWN = "KNOWN"
    UNAVAILABLE = "UNAVAILABLE"
    INCONSISTENT = "INCONSISTENT"


@dataclass(frozen=True)
class ProviderCallReceipt:
    call_id: UUID
    provider_request_id: str | None
    requested_alias: str
    actual_model: str | None
    usage_schema_version: str
    usage_status: UsageStatus
    usage: Usage | None
    usage_issue_code: str | None
    service_tier: str
    context_price_band: str


ReceiptRecorder = Callable[[ProviderCallReceipt], None]
