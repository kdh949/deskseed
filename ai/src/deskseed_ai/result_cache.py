from __future__ import annotations

import hashlib
import hmac
from dataclasses import dataclass

from .backend_client import AiPolicy
from .config import Settings
from .prompting import prompt_for
from .repository import ClaimedJob
from .schemas import Feature

CACHE_KEY_VERSION = "result-cache-summary-triage-v1"
MODEL_ROUTE_VERSION = "model-route-v1"
OUTPUT_SCHEMA_VERSION = "typed-result-v1"
CONTEXT_BUILDER_VERSION = "public-comments-bounded-v1"


@dataclass(frozen=True)
class ExactResultCacheKey:
    digest: str
    version: str = CACHE_KEY_VERSION


def exact_result_cache_key(
    claim: ClaimedJob,
    policy: AiPolicy,
    resolved_model: str,
    settings: Settings,
) -> ExactResultCacheKey | None:
    if (
        settings.exact_result_cache_mode != "test"
        or claim.feature not in {Feature.SUMMARY, Feature.TRIAGE}
        or claim.ai_input_revision is None
        or claim.input_policy_version is None
    ):
        return None
    secret = settings.result_cache_key_secret.get_secret_value().encode("utf-8")
    fields = (
        CACHE_KEY_VERSION,
        claim.workspace_key,
        str(claim.requester_id),
        str(claim.ticket_id),
        claim.feature.value,
        claim.ai_input_revision,
        claim.input_policy_version,
        claim.options.get("language", ""),
        claim.options.get("tone", ""),
        prompt_for(claim.feature).digest,
        OUTPUT_SCHEMA_VERSION,
        MODEL_ROUTE_VERSION,
        resolved_model,
        str(policy.version),
        settings.config_version,
        CONTEXT_BUILDER_VERSION,
    )
    canonical = bytearray()
    for field in fields:
        encoded = field.encode("utf-8")
        canonical.extend(len(encoded).to_bytes(4, byteorder="big", signed=False))
        canonical.extend(encoded)
    return ExactResultCacheKey(hmac.new(secret, canonical, hashlib.sha256).hexdigest())
