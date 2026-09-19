from __future__ import annotations

import hashlib
import hmac
from dataclasses import dataclass

from .backend_client import AiPolicy
from .config import Settings
from .prompting import prompt_for
from .repository import ClaimedJob, PublishedIndexGeneration
from .schemas import Feature, GenerationMode

SUMMARY_TRIAGE_CACHE_KEY_VERSION = "result-cache-summary-triage-v1"
REPLY_CACHE_KEY_VERSION = "result-cache-reply-v1"
MODEL_ROUTE_VERSION = "model-route-v1"
OUTPUT_SCHEMA_VERSION = "typed-result-v1"
CONTEXT_BUILDER_VERSION = "public-comments-bounded-v1"
RETRIEVAL_VERSION = "hybrid-pgvector-v1"
CHUNKING_VERSION = "public-kb-fixed-1800-v1"


@dataclass(frozen=True)
class ExactResultCacheKey:
    digest: str
    version: str


def exact_result_cache_key(
    claim: ClaimedJob,
    policy: AiPolicy,
    resolved_model: str,
    settings: Settings,
    published_index: PublishedIndexGeneration | None = None,
) -> ExactResultCacheKey | None:
    if (
        settings.exact_result_cache_mode == "off"
        or claim.ai_input_revision is None
        or claim.input_policy_version is None
    ):
        return None
    if settings.exact_result_cache_mode == "intent" and claim.generation_mode != GenerationMode.REUSE_OR_CREATE:
        return None
    if claim.feature in {Feature.SUMMARY, Feature.TRIAGE}:
        key_version = SUMMARY_TRIAGE_CACHE_KEY_VERSION
        knowledge_fields: tuple[str, ...] = ()
    elif claim.feature == Feature.REPLY_DRAFT:
        corpus_revision = getattr(policy, "canonicalPublicCorpusRevision", None)
        if (
            corpus_revision is None
            or published_index is None
            or published_index.canonical_corpus_revision != corpus_revision
        ):
            return None
        key_version = REPLY_CACHE_KEY_VERSION
        knowledge_fields = (
            RETRIEVAL_VERSION,
            CHUNKING_VERSION,
            str(corpus_revision),
            str(published_index.generation),
        )
    else:
        return None
    secret = settings.result_cache_key_secret.get_secret_value().encode("utf-8")
    fields = (
        key_version,
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
        *knowledge_fields,
        CONTEXT_BUILDER_VERSION,
    )
    canonical = bytearray()
    for field in fields:
        encoded = field.encode("utf-8")
        canonical.extend(len(encoded).to_bytes(4, byteorder="big", signed=False))
        canonical.extend(encoded)
    return ExactResultCacheKey(hmac.new(secret, canonical, hashlib.sha256).hexdigest(), key_version)
