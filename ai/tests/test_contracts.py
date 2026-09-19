from __future__ import annotations

import hashlib
import json
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

import pytest
from pydantic import ValidationError

from deskseed_ai.backend_client import AiPolicy, BackendAuthorizationError, BackendClient
from deskseed_ai.call_receipts import PromptCacheStatus, UsageStatus
from deskseed_ai.config import Settings
from deskseed_ai.pricing import PricingCatalog, Usage
from deskseed_ai.prompt_cache import (
    PROMPT_CACHE_KEY_VERSION,
    PROMPT_CACHE_MIN_PREFIX_TOKENS,
    prepare_prompt_cache_request,
)
from deskseed_ai.prompting import (
    FeaturePrompt,
    context_memory_prompt,
    prompt_for,
    rewrite_validation_prompt,
)
from deskseed_ai.providers import LiteLlmGenerationProvider
from deskseed_ai.queue import InputTooLongError, _bounded_context
from deskseed_ai.repository import ClaimedJob, PublishedIndexGeneration
from deskseed_ai.result_cache import exact_result_cache_key
from deskseed_ai.retrieval import (
    EMBEDDING_DIMENSION,
    EMBEDDING_QUERY_TOKEN_LIMIT,
    KnowledgeChunk,
    LiteLlmEmbeddingProvider,
    MissingCurrentProblemError,
    RetrievalQueryTooLongError,
    build_public_article_chunks,
    build_retrieval_query,
    embedding_artifact_spec,
)
from deskseed_ai.rewrite import preservation_markers, preserves_deterministic_markers
from deskseed_ai.schemas import (
    AuthorRole,
    Citation,
    ContextMemoryItem,
    ContextMemoryPayload,
    ContextMemoryProviderOutput,
    Feature,
    GenerationMode,
    JobEnvelope,
    PublicComment,
    ReplyProviderOutput,
    ReplyRewritePreservationVerdict,
    ReplyRewriteProviderOutput,
    SourceContext,
)
from deskseed_ai.security import authenticate_machine
from deskseed_ai.usage_normalization import normalize_litellm_usage
from deskseed_ai.workflows import reply_query, source_map_digest


def test_production_requires_explicit_machine_auth() -> None:
    with pytest.raises(ValidationError):
        Settings(environment="production", inbound_auth_enabled=False)


def test_production_credentials_are_scoped_to_the_process_role() -> None:
    Settings(environment="production", process_role="migration")
    Settings(environment="production", process_role="dispatcher")
    Settings(environment="production", process_role="recovery")
    Settings(environment="production", process_role="feedback")
    Settings(environment="production", process_role="retention")
    Settings(
        environment="production",
        process_role="api",
        inbound_auth_enabled=True,
        inbound_key_id="backend-to-ai-v1",
        inbound_secret_sha256="a" * 64,
    )
    Settings(
        environment="production",
        process_role="worker",
        backend_source_key_id="worker-v1",
        backend_source_secret="worker-secret",
    )
    Settings(
        environment="production",
        process_role="indexer",
        backend_index_key_id="indexer-v1",
        backend_index_secret="indexer-secret",
    )

    with pytest.raises(ValidationError):
        Settings(environment="production", process_role="worker")
    with pytest.raises(ValidationError):
        Settings(environment="production", process_role="indexer")


def test_exact_result_cache_is_test_only_until_reuse_intent_is_contractual() -> None:
    with pytest.raises(ValidationError, match="S08 reuse-intent"):
        Settings(
            environment="production",
            process_role="migration",
            exact_result_cache_mode="test",
            result_cache_key_secret="synthetic-cache-key-secret-at-least-32-bytes",
        )
    with pytest.raises(ValidationError, match="at least 32"):
        Settings(environment="test", exact_result_cache_mode="test", result_cache_key_secret="short")

    settings = Settings(
        environment="test",
        exact_result_cache_mode="test",
        result_cache_key_secret="synthetic-cache-key-secret-at-least-32-bytes",
    )
    assert settings.exact_result_cache_mode == "test"


def test_context_memory_is_off_by_default_and_test_mode_is_not_production() -> None:
    assert Settings(environment="test").context_memory_mode == "off"
    with pytest.raises(ValueError, match="measured intent activation"):
        Settings(
            environment="production", process_role="migration", context_memory_mode="test"
        )


def test_prompt_cache_is_off_by_default_and_test_mode_is_not_production() -> None:
    assert Settings(environment="test").prompt_cache_mode == "off"
    with pytest.raises(ValueError, match="measured intent activation"):
        Settings(
            environment="production", process_role="migration", prompt_cache_mode="test"
        )
    assert (
        Settings(
            environment="production", process_role="migration", prompt_cache_mode="intent"
        ).prompt_cache_mode
        == "intent"
    )


def test_embedding_optimization_requires_separate_reviewed_snapshot() -> None:
    assert Settings(environment="test").embedding_optimization_mode == "off"
    test_settings = Settings(environment="test", embedding_optimization_mode="test")
    assert test_settings.resolved_embedding_model_snapshot.startswith("test:")
    assert Settings(
        environment="test",
        embedding_optimization_mode="test",
        embedding_model_snapshot="reviewed-looking-v1",
    ).resolved_embedding_model_snapshot == "test:reviewed-looking-v1"
    with pytest.raises(ValueError, match="measured intent activation"):
        Settings(
            environment="production",
            process_role="migration",
            embedding_optimization_mode="test",
        )
    with pytest.raises(ValueError, match="immutable model snapshot"):
        Settings(
            environment="production",
            process_role="migration",
            embedding_optimization_mode="intent",
        )
    with pytest.raises(ValueError, match="immutable model snapshot"):
        Settings(
            environment="production",
            process_role="migration",
            embedding_optimization_mode="intent",
            embedding_model_snapshot="openai/text-embedding-3-small",
        )
    settings = Settings(
        environment="production",
        process_role="migration",
        embedding_optimization_mode="intent",
        embedding_model_snapshot="openai/text-embedding-3-small@reviewed-immutable-v1",
    )
    assert settings.embedding_array_size == 8


def test_embedding_artifact_key_covers_exact_contract_and_final_input() -> None:
    baseline = embedding_artifact_spec(
        "test:embedding-v1",
        EMBEDDING_DIMENSION,
        "public-text-nfkc-v2",
        "Document title: 공개 도움말\nCategory: 지원\nSection: 시작\nBody:\n본문",
    )
    variants = {
        embedding_artifact_spec(
            "test:embedding-v1",
            3072,
            "public-text-nfkc-v2",
            "Document title: 공개 도움말\nCategory: 지원\nSection: 시작\nBody:\n본문",
        ).artifact_key,
        embedding_artifact_spec(
            "test:embedding-v2",
            EMBEDDING_DIMENSION,
            "public-text-nfkc-v2",
            "Document title: 공개 도움말\nCategory: 지원\nSection: 시작\nBody:\n본문",
        ).artifact_key,
        embedding_artifact_spec(
            "test:embedding-v1",
            EMBEDDING_DIMENSION,
            "public-text-nfkc-v3",
            "Document title: 공개 도움말\nCategory: 지원\nSection: 시작\nBody:\n본문",
        ).artifact_key,
        embedding_artifact_spec(
            "test:embedding-v1",
            EMBEDDING_DIMENSION,
            "public-text-nfkc-v2",
            "Document title: 다른 도움말\nCategory: 지원\nSection: 시작\nBody:\n본문",
        ).artifact_key,
    }
    assert len(baseline.artifact_key) == 64
    assert baseline.artifact_key not in variants
    assert "본문" not in baseline.artifact_key


def test_shared_execution_is_test_only_and_requires_exact_cache() -> None:
    with pytest.raises(ValidationError, match="S08 reuse-intent"):
        Settings(
            environment="production",
            process_role="migration",
            exact_result_cache_mode="test",
            shared_execution_mode="test",
            result_cache_key_secret="synthetic-cache-key-secret-at-least-32-bytes",
        )
    with pytest.raises(ValidationError, match="requires the exact result cache"):
        Settings(environment="test", shared_execution_mode="test")

    settings = Settings(
        environment="test",
        exact_result_cache_mode="test",
        shared_execution_mode="test",
        result_cache_key_secret="synthetic-cache-key-secret-at-least-32-bytes",
    )
    assert settings.shared_execution_mode == "test"

    production = Settings(
        environment="production",
        process_role="migration",
        exact_result_cache_mode="intent",
        shared_execution_mode="intent",
        result_cache_key_secret="synthetic-cache-key-secret-at-least-32-bytes",
    )
    assert production.shared_execution_mode == "intent"


def test_schema_v3_generation_intent_shape_is_strict() -> None:
    base = {
        "schemaVersion": 3,
        "eventId": uuid4(),
        "jobId": uuid4(),
        "workspaceKey": "default",
        "requesterId": uuid4(),
        "ticketId": uuid4(),
        "ticketNumber": 1,
        "feature": Feature.SUMMARY,
        "contextRevision": "a" * 64,
        "contextPolicyVersion": "public-comments-v2",
        "aiInputRevision": "b" * 64,
        "inputPolicyVersion": "summary-input-v1",
        "dataClass": "PUBLIC_ONLY",
        "requestRevision": 1,
        "options": {"language": "ko"},
        "createdAt": datetime.now(UTC),
        "deadlineAt": datetime.now(UTC) + timedelta(minutes=1),
    }
    reuse = JobEnvelope.model_validate(base | {"generationMode": GenerationMode.REUSE_OR_CREATE})
    assert reuse.candidateId is None
    with pytest.raises(ValidationError, match="cannot contain candidate identity"):
        JobEnvelope.model_validate(
            base | {"generationMode": GenerationMode.REUSE_OR_CREATE, "candidateId": uuid4()}
        )
    with pytest.raises(ValidationError, match="server candidate identity"):
        JobEnvelope.model_validate(base | {"generationMode": GenerationMode.NEW_CANDIDATE})
    candidate = JobEnvelope.model_validate(
        base
        | {
            "generationMode": GenerationMode.NEW_CANDIDATE,
            "candidateId": uuid4(),
            "candidateSequence": 1,
        }
    )
    assert candidate.candidateSequence == 1


def test_schema_v4_reply_rewrite_is_source_bound_and_uses_closed_options() -> None:
    now = datetime.now(UTC)
    source_job_id = uuid4()
    base = {
        "schemaVersion": 4,
        "eventId": uuid4(),
        "jobId": uuid4(),
        "workspaceKey": "default",
        "requesterId": uuid4(),
        "ticketId": uuid4(),
        "ticketNumber": 1,
        "feature": Feature.REPLY_REWRITE,
        "contextRevision": "a" * 64,
        "contextPolicyVersion": "public-comments-v2",
        "aiInputRevision": "b" * 64,
        "inputPolicyVersion": "rewrite-input-v1",
        "generationMode": GenerationMode.REUSE_OR_CREATE,
        "sourceJobId": source_job_id,
        "dataClass": "PUBLIC_DRAFT_ONLY",
        "requestRevision": 1,
        "options": {"language": "ko", "length": "standard", "tone": "calm"},
        "createdAt": now,
        "deadlineAt": now + timedelta(minutes=1),
    }

    parsed = JobEnvelope.model_validate(base)
    assert parsed.sourceJobId == source_job_id
    for invalid in (
        {"sourceJobId": None},
        {"feature": Feature.REPLY_DRAFT},
        {"generationMode": GenerationMode.NEW_CANDIDATE},
        {"dataClass": "PUBLIC_ONLY"},
        {"options": {"language": "ko", "length": "long", "tone": "calm"}},
        {"options": {"language": "ko", "length": "standard", "tone": "friendly"}},
    ):
        with pytest.raises(ValidationError):
            JobEnvelope.model_validate(base | invalid)
    with pytest.raises(ValidationError):
        JobEnvelope.model_validate(base | {"answer": "client text must not enter the envelope"})


def test_reply_rewrite_contract_requires_exact_refs_and_consistent_verdict() -> None:
    output = ReplyRewriteProviderOutput(answer="안내입니다.", sourceRefs=["S1", "S2"])
    assert output.sourceRefs == ["S1", "S2"]
    with pytest.raises(ValidationError, match="unique"):
        ReplyRewriteProviderOutput(answer="안내입니다.", sourceRefs=["S1", "S1"])
    assert ReplyRewritePreservationVerdict(preserved=True, changedCategories=[]).preserved
    with pytest.raises(ValidationError, match="inconsistent"):
        ReplyRewritePreservationVerdict(preserved=True, changedCategories=["POLICY"])


def test_reply_rewrite_deterministic_markers_preserve_facts_conditions_and_negation() -> None:
    original = (
        "Deskseed 환불 정책상 2026-09-30까지 12,000원 결제는 취소할 수 없으며 "
        "support@example.com 또는 https://example.com/help 를 확인해야 합니다."
    )
    reordered = (
        "support@example.com 또는 https://example.com/help 를 확인해야 합니다. "
        "Deskseed 환불 정책상 12,000원 결제는 2026-09-30까지 취소할 수 없습니다."
    )
    assert preservation_markers(original)
    assert preserves_deterministic_markers(original, reordered)
    assert not preserves_deterministic_markers(original, reordered.replace("12,000원", "13,000원"))
    assert not preserves_deterministic_markers(original, reordered.replace("없습니다", "있습니다"))
    assert not preserves_deterministic_markers(original, reordered.replace("2026-09-30", "2026-10-01"))


def test_exact_result_cache_key_is_server_scoped_and_versioned() -> None:
    settings = Settings(
        environment="test",
        exact_result_cache_mode="test",
        result_cache_key_secret="synthetic-cache-key-secret-at-least-32-bytes",
    )
    now = datetime.now(UTC)
    claim = ClaimedJob(
        job_id=uuid4(),
        generation=1,
        lease_epoch=1,
        feature=Feature.SUMMARY,
        workspace_key="default",
        requester_id=uuid4(),
        ticket_id=uuid4(),
        context_revision="a" * 64,
        context_policy_version="public-comments-v2",
        ai_input_revision="b" * 64,
        input_policy_version="summary-input-v1",
        traceparent=None,
        deadline_at=now + timedelta(minutes=2),
        options={"language": "ko"},
    )
    policy = AiPolicy(
        enabled=True,
        features={Feature.SUMMARY.value: True},
        fastModelAlias=settings.model_fast,
        standardModelAlias=settings.model_standard,
        replyRoutingMode="STANDARD_ONLY",
        replyRoutingCohorts=[],
        replyRoutingRolloutPercent=0,
        version=3,
        updatedAt=now,
        dataAsOf=now,
    )
    original = exact_result_cache_key(claim, policy, settings.model_fast, settings)
    assert original is not None
    assert len(original.digest) == 64
    assert exact_result_cache_key(replace(claim, job_id=uuid4()), policy, settings.model_fast, settings) == original
    assert exact_result_cache_key(replace(claim, requester_id=uuid4()), policy, settings.model_fast, settings) != original
    assert exact_result_cache_key(replace(claim, ticket_id=uuid4()), policy, settings.model_fast, settings) != original
    assert exact_result_cache_key(claim, policy.model_copy(update={"version": 4}), settings.model_fast, settings) != original
    assert exact_result_cache_key(replace(claim, ai_input_revision=None), policy, settings.model_fast, settings) is None
    reply_claim = replace(claim, feature=Feature.REPLY_DRAFT, input_policy_version="reply-input-v1")
    assert exact_result_cache_key(reply_claim, policy, settings.model_standard, settings) is None
    reply_policy = policy.model_copy(update={"canonicalPublicCorpusRevision": 9})
    published_index = PublishedIndexGeneration(
        generation=4, canonical_corpus_revision=9, artifact_generation=3
    )
    reply_key = exact_result_cache_key(
        reply_claim,
        reply_policy,
        settings.model_standard,
        settings,
        published_index,
    )
    assert reply_key is not None
    assert reply_key.version == "result-cache-reply-v1"
    assert exact_result_cache_key(
        reply_claim,
        reply_policy,
        settings.model_standard,
        settings,
        PublishedIndexGeneration(generation=4, canonical_corpus_revision=8, artifact_generation=3),
    ) is None
    assert exact_result_cache_key(
        reply_claim,
        reply_policy,
        settings.model_standard,
        settings,
        PublishedIndexGeneration(generation=5, canonical_corpus_revision=9, artifact_generation=3),
    ) != reply_key
    memory_settings = settings.model_copy(
        update={"context_memory_mode": "test", "context_memory_expected_reuses": 3}
    )
    assert exact_result_cache_key(
        reply_claim,
        reply_policy,
        memory_settings.model_standard,
        memory_settings,
        published_index,
    ) != reply_key
    routed_policy = reply_policy.model_copy(
        update={
            "replyRoutingMode": "EVALUATED_COHORT",
            "replyRoutingCohorts": ["reply-single-public-article-short-v1"],
            "replyRoutingRolloutPercent": 10,
            "replyRoutingEvaluationApprovalVersion": "holdout-v1",
        }
    )
    assert exact_result_cache_key(
        reply_claim,
        routed_policy,
        settings.model_standard,
        settings,
        published_index,
    ) != reply_key
    routed_settings = Settings.model_validate(
        settings.model_dump()
        | {
            "reply_routing_bucket_secret":
                "rotated-routing-secret-at-least-32-bytes",
        }
    )
    assert exact_result_cache_key(
        reply_claim,
        routed_policy,
        routed_settings.model_standard,
        routed_settings,
        published_index,
    ) != exact_result_cache_key(
        reply_claim,
        routed_policy,
        settings.model_standard,
        settings,
        published_index,
    )


def test_ai_policy_rejects_incomplete_or_inconsistent_reply_routing() -> None:
    now = datetime.now(UTC)
    base = {
        "enabled": True,
        "features": {Feature.REPLY_DRAFT.value: True},
        "fastModelAlias": "openai/gpt-5.6-luna",
        "standardModelAlias": "openai/gpt-5.6-terra",
        "replyRoutingMode": "STANDARD_ONLY",
        "replyRoutingCohorts": [],
        "replyRoutingRolloutPercent": 0,
        "replyRoutingEvaluationApprovalVersion": None,
        "version": 1,
        "updatedAt": now,
        "dataAsOf": now,
        "canonicalPublicCorpusRevision": 7,
    }
    assert AiPolicy.model_validate(base).replyRoutingMode == "STANDARD_ONLY"
    with pytest.raises(ValidationError):
        AiPolicy.model_validate(
            base
            | {
                "replyRoutingMode": "EVALUATED_COHORT",
                "replyRoutingCohorts": ["reply-single-public-article-short-v1"],
                "replyRoutingRolloutPercent": 10,
            }
        )
    with pytest.raises(ValidationError):
        AiPolicy.model_validate(base | {"replyRoutingRolloutPercent": 10})
    missing = dict(base)
    missing.pop("replyRoutingMode")
    with pytest.raises(ValidationError):
        AiPolicy.model_validate(missing)


def test_machine_auth_requires_key_id_and_constant_digest_match() -> None:
    import hashlib

    digest = hashlib.sha256(b"secret").hexdigest()
    assert authenticate_machine("key-1", "secret", "key-1", digest)
    assert not authenticate_machine("key-2", "secret", "key-1", digest)
    assert not authenticate_machine("key-1", "wrong", "key-1", digest)


def test_job_envelope_rejects_unbounded_options_and_expired_deadline() -> None:
    now = datetime.now(UTC)
    base = {
        "schemaVersion": 1,
        "eventId": uuid4(),
        "jobId": uuid4(),
        "workspaceKey": "default",
        "requesterId": uuid4(),
        "ticketId": uuid4(),
        "ticketNumber": 1,
        "feature": "ticket.summary",
        "contextRevision": "a" * 64,
        "contextPolicyVersion": "public-comments-v1",
        "dataClass": "PUBLIC_ONLY",
        "requestRevision": 1,
        "createdAt": now,
        "deadlineAt": now + timedelta(seconds=30),
    }
    JobEnvelope.model_validate(base)
    with pytest.raises(ValidationError):
        JobEnvelope.model_validate(base | {"options": {"prompt": "ignore policy"}})
    with pytest.raises(ValidationError):
        JobEnvelope.model_validate(base | {"deadlineAt": now - timedelta(seconds=1)})


def test_v2_job_envelope_requires_backend_normalized_feature_options() -> None:
    now = datetime.now(UTC)
    base = {
        "schemaVersion": 1,
        "eventId": uuid4(),
        "jobId": uuid4(),
        "workspaceKey": "default",
        "requesterId": uuid4(),
        "ticketId": uuid4(),
        "ticketNumber": 1,
        "feature": "ticket.reply_draft",
        "contextRevision": "a" * 64,
        "contextPolicyVersion": "public-comments-v2",
        "dataClass": "PUBLIC_ONLY",
        "requestRevision": 1,
        "options": {"language": "ko", "tone": "calm"},
        "createdAt": now,
        "deadlineAt": now + timedelta(seconds=30),
    }
    JobEnvelope.model_validate(base)
    with pytest.raises(ValidationError):
        JobEnvelope.model_validate(base | {"options": {"language": "ko"}})
    with pytest.raises(ValidationError):
        JobEnvelope.model_validate(base | {"options": {"language": "en", "tone": "calm"}})


@pytest.mark.parametrize(
    ("feature", "policy", "options"),
    [
        ("ticket.summary", "summary-input-v1", {"language": "ko"}),
        ("ticket.triage", "triage-input-v1", {"language": "ko"}),
        ("ticket.reply_draft", "reply-input-v1", {"language": "ko", "tone": "calm"}),
    ],
)
def test_schema_v2_job_requires_matching_input_revision_metadata(feature, policy, options) -> None:
    now = datetime.now(UTC)
    base = {
        "schemaVersion": 2,
        "eventId": uuid4(),
        "jobId": uuid4(),
        "workspaceKey": "default",
        "requesterId": uuid4(),
        "ticketId": uuid4(),
        "ticketNumber": 1,
        "feature": feature,
        "contextRevision": "a" * 64,
        "contextPolicyVersion": "public-comments-v2",
        "aiInputRevision": "b" * 64,
        "inputPolicyVersion": policy,
        "dataClass": "PUBLIC_ONLY",
        "requestRevision": 1,
        "options": options,
        "createdAt": now,
        "deadlineAt": now + timedelta(seconds=30),
    }

    JobEnvelope.model_validate(base)
    with pytest.raises(ValidationError):
        JobEnvelope.model_validate({key: value for key, value in base.items() if key != "aiInputRevision"})
    with pytest.raises(ValidationError):
        JobEnvelope.model_validate(base | {"inputPolicyVersion": "summary-input-v1" if policy != "summary-input-v1" else "triage-input-v1"})
    with pytest.raises(ValidationError):
        JobEnvelope.model_validate(base | {"schemaVersion": 1})


def test_source_context_v1_and_v2_shapes_are_compatible_but_not_mixed() -> None:
    now = datetime.now(UTC)
    common = {
        "jobId": uuid4(),
        "ticketId": uuid4(),
        "ticketNumber": 1,
        "ticketVersion": 0,
        "feature": Feature.SUMMARY,
        "requestRevision": 1,
        "contextRevision": "a" * 64,
        "inputScope": "PUBLIC_ONLY",
    }
    SourceContext(
        **common,
        contextPolicyVersion="public-comments-v1",
        comments=[PublicComment(id=uuid4(), body="legacy", createdAt=now)],
    )
    SourceContext(
        **common,
        contextPolicyVersion="public-comments-v2",
        aiInputRevision="b" * 64,
        inputPolicyVersion="summary-input-v1",
        comments=[
            PublicComment(
                id=uuid4(),
                sequence=1,
                authorRole=AuthorRole.CUSTOMER,
                body="current-v2",
                createdAt=now,
            )
        ],
    )
    with pytest.raises(ValidationError):
        SourceContext(
            **common,
            contextPolicyVersion="public-comments-v2",
            aiInputRevision="b" * 64,
            comments=[
                PublicComment(
                    id=uuid4(),
                    sequence=1,
                    authorRole=AuthorRole.CUSTOMER,
                    body="unpaired",
                    createdAt=now,
                )
            ],
        )
    SourceContext(
        **common,
        contextPolicyVersion="public-comments-v2",
        comments=[
            PublicComment(
                id=uuid4(),
                sequence=1,
                authorRole=AuthorRole.CUSTOMER,
                body="current",
                createdAt=now,
            )
        ],
    )
    with pytest.raises(ValidationError):
        SourceContext(
            **common,
            contextPolicyVersion="public-comments-v1",
            comments=[
                PublicComment(
                    id=uuid4(),
                    sequence=1,
                    authorRole=AuthorRole.CUSTOMER,
                    body="mixed",
                    createdAt=now,
                )
            ],
        )
    with pytest.raises(ValidationError):
        SourceContext(
            **common,
            contextPolicyVersion="public-comments-v2",
            comments=[
                PublicComment(
                    id=uuid4(),
                    sequence=2,
                    authorRole=AuthorRole.CUSTOMER,
                    body="gap",
                    createdAt=now,
                )
            ],
        )


def test_pricing_is_integer_and_rounds_up(tmp_path) -> None:
    path = tmp_path / "pricing.json"
    path.write_text(
        '{"version":"v1","serviceTier":"standard","contextPriceBand":"short",'
        '"models":{"m":{"actualModels":["m"],"maxInputTokensForBand":1000,'
        '"input":200000,"cachedInput":20000,"cacheWrite":250000,"output":1200000}}}',
        encoding="utf-8",
    )
    catalog = PricingCatalog(path)
    assert catalog.cost_microusd("m", "m", Usage(1, 0, 0, 1)) == 2


def test_actual_model_selects_price_instead_of_requested_alias(tmp_path) -> None:
    path = tmp_path / "pricing.json"
    path.write_text(
        '{"version":"v2","serviceTier":"standard","contextPriceBand":"short",'
        '"models":{"cheap":{"actualModels":["cheap"],"maxInputTokensForBand":1000,'
        '"input":100000,"output":100000},"expensive":{"actualModels":["expensive"],'
        '"maxInputTokensForBand":1000,"input":900000,"output":900000}}}',
        encoding="utf-8",
    )
    catalog = PricingCatalog(path)
    assert catalog.cost_microusd("cheap", "expensive", Usage(1_000, 0, 0, 0)) == 900


def test_usage_normalization_keeps_exclusive_cache_buckets() -> None:
    status, usage, issue = normalize_litellm_usage(
        {
            "prompt_tokens": 1000,
            "completion_tokens": 50,
            "cache_read_input_tokens": 800,
            "cache_creation_input_tokens": 200,
            "prompt_tokens_details": {
                "cached_tokens": 800,
                "cache_creation_tokens": 200,
            },
            "completion_tokens_details": {"reasoning_tokens": 20},
        }
    )
    assert (status, issue) == (UsageStatus.KNOWN, None)
    assert usage == Usage(0, 800, 200, 50)


def test_reviewed_luna_cache_fixture_has_exact_integer_cost() -> None:
    catalog = PricingCatalog(Path(__file__).resolve().parents[1] / "config" / "pricing-v2.json")
    assert catalog.cost_microusd(
        "openai/gpt-5.6-luna",
        "gpt-5.6-luna",
        Usage(0, 800, 200, 50),
    ) == 126


@pytest.mark.parametrize(
    ("payload", "expected_status", "expected_issue"),
    [
        (
            {
                "prompt_tokens": 1000,
                "completion_tokens": 50,
                "cache_read_input_tokens": 800,
                "prompt_tokens_details": {"cached_tokens": 700},
            },
            UsageStatus.INCONSISTENT,
            "USAGE_DUPLICATE_MISMATCH",
        ),
        ({"completion_tokens": 5}, UsageStatus.UNAVAILABLE, "USAGE_TOTAL_MISSING"),
        (
            {"prompt_tokens": -1, "completion_tokens": 5},
            UsageStatus.INCONSISTENT,
            "USAGE_VALUE_INVALID",
        ),
        (
            {
                "prompt_tokens": 10,
                "completion_tokens": 5,
                "completion_tokens_details": {"reasoning_tokens": 6},
            },
            UsageStatus.INCONSISTENT,
            "REASONING_EXCEEDS_OUTPUT",
        ),
    ],
)
def test_usage_normalization_rejects_unreliable_provider_facts(
    payload, expected_status, expected_issue
) -> None:
    status, usage, issue = normalize_litellm_usage(payload)
    assert (status, usage, issue) == (expected_status, None, expected_issue)


def _pricing_catalog() -> PricingCatalog:
    return PricingCatalog(Path(__file__).resolve().parents[1] / "config" / "pricing-v2.json")


def _synthetic_prompt(name: str, content: str) -> FeaturePrompt:
    return FeaturePrompt(
        name=name,
        content=content,
        digest=hashlib.sha256(content.encode("utf-8")).hexdigest(),
    )


def _prompt_cache_request(prompt: FeaturePrompt, variable: str = "customer-variable") -> dict:
    return {
        "model": "openai/gpt-5.6-luna",
        "messages": [
            {"role": "system", "content": prompt.content},
            {"role": "user", "content": variable},
        ],
        "response_format": {
            "type": "json_schema",
            "json_schema": {
                "name": "SyntheticOutput",
                "strict": True,
                "schema": {"type": "object", "properties": {"answer": {"type": "string"}}},
            },
        },
        "reasoning_effort": "none",
        "service_tier": "default",
    }


def test_prompt_cache_off_and_ineligible_preserve_original_transport_shape() -> None:
    prompt = _synthetic_prompt("short-v1", "Repository-owned instruction only.")
    request = _prompt_cache_request(prompt)

    off = prepare_prompt_cache_request(
        mode="off",
        pricing=_pricing_catalog(),
        model=request["model"],
        feature="ticket.summary",
        prompt=prompt,
        request=request,
    )
    assert off.status == PromptCacheStatus.OFF
    assert off.prefix_tokens is None
    assert off.messages is request["messages"]
    assert off.transport_options == {}

    ineligible = prepare_prompt_cache_request(
        mode="test",
        pricing=_pricing_catalog(),
        model=request["model"],
        feature="ticket.summary",
        prompt=prompt,
        request=request,
    )
    assert ineligible.status == PromptCacheStatus.INELIGIBLE
    assert ineligible.prefix_tokens is not None
    assert ineligible.prefix_tokens < PROMPT_CACHE_MIN_PREFIX_TOKENS
    assert ineligible.messages is request["messages"]
    assert ineligible.transport_options == {}


def test_prompt_cache_plan_is_static_deterministic_and_content_free() -> None:
    content = "Repository-owned static instruction.\n" * 1200
    prompt = _synthetic_prompt("synthetic-long-v1", content)
    first_request = _prompt_cache_request(prompt, "private-ticket-value-one")
    second_request = _prompt_cache_request(prompt, "private-ticket-value-two")

    def plan(request: dict):
        return prepare_prompt_cache_request(
            mode="test",
            pricing=_pricing_catalog(),
            model=request["model"],
            feature="ticket.summary",
            prompt=prompt,
            request=request,
        )

    first = plan(first_request)
    second = plan(second_request)
    assert first.status == PromptCacheStatus.REQUESTED
    assert first.prefix_tokens is not None
    assert first.prefix_tokens >= PROMPT_CACHE_MIN_PREFIX_TOKENS
    assert first.key_version == PROMPT_CACHE_KEY_VERSION
    assert first.cache_key == second.cache_key
    assert first.cache_key is not None and len(first.cache_key) <= 64
    assert "private-ticket" not in first.cache_key
    assert first.messages[0] == {
        "role": "system",
        "content": [
            {
                "type": "text",
                "text": content,
                "prompt_cache_breakpoint": {"mode": "explicit"},
            }
        ],
    }
    assert first.messages[1] == first_request["messages"][1]
    assert first.transport_options == {
        "prompt_cache_key": first.cache_key,
        "prompt_cache_options": {"mode": "explicit", "ttl": "30m"},
        "allowed_openai_params": ["prompt_cache_options"],
    }

    changed_schema = _prompt_cache_request(prompt)
    changed_schema["response_format"]["json_schema"]["name"] = "ChangedOutput"
    assert plan(changed_schema).cache_key != first.cache_key
    changed_service_tier = _prompt_cache_request(prompt)
    changed_service_tier["service_tier"] = "priority"
    assert plan(changed_service_tier).cache_key != first.cache_key
    changed_reasoning = _prompt_cache_request(prompt)
    changed_reasoning["reasoning_effort"] = "low"
    assert plan(changed_reasoning).cache_key != first.cache_key
    changed_model = _prompt_cache_request(prompt)
    changed_model["model"] = "openai/gpt-5.6-terra"
    assert (
        prepare_prompt_cache_request(
            mode="test",
            pricing=_pricing_catalog(),
            model=changed_model["model"],
            feature="ticket.summary",
            prompt=prompt,
            request=changed_model,
        ).cache_key
        != first.cache_key
    )
    changed_prompt = _synthetic_prompt("synthetic-long-v2", content + "New rule.")
    changed_prompt_request = _prompt_cache_request(changed_prompt)
    assert (
        prepare_prompt_cache_request(
            mode="test",
            pricing=_pricing_catalog(),
            model=changed_prompt_request["model"],
            feature="ticket.summary",
            prompt=changed_prompt,
            request=changed_prompt_request,
        ).cache_key
        != first.cache_key
    )

    mismatched_request = _prompt_cache_request(prompt)
    mismatched_request["messages"][0]["content"] = "unreviewed system content"
    with pytest.raises(ValueError, match="repository-owned"):
        plan(mismatched_request)
    with pytest.raises(ValueError, match="model must match"):
        prepare_prompt_cache_request(
            mode="test",
            pricing=_pricing_catalog(),
            model="openai/gpt-5.6-terra",
            feature="ticket.summary",
            prompt=prompt,
            request=first_request,
        )


def test_current_repository_prompts_remain_below_prompt_cache_minimum() -> None:
    prompts = [
        *(prompt_for(feature) for feature in Feature),
        context_memory_prompt(),
        rewrite_validation_prompt(),
    ]
    for prompt in prompts:
        request = _prompt_cache_request(prompt)
        plan = prepare_prompt_cache_request(
            mode="test",
            pricing=_pricing_catalog(),
            model=request["model"],
            feature=prompt.name,
            prompt=prompt,
            request=request,
        )
        assert plan.status == PromptCacheStatus.INELIGIBLE
        assert plan.prefix_tokens is not None
        assert plan.prefix_tokens < PROMPT_CACHE_MIN_PREFIX_TOKENS


def test_pinned_litellm_forwards_explicit_prompt_cache_to_openai_body(monkeypatch) -> None:
    import litellm
    import litellm.main
    from openai.types.chat import ChatCompletion, ChatCompletionMessage
    from openai.types.chat.chat_completion import Choice
    from openai.types.completion_usage import CompletionUsage

    prompt = _synthetic_prompt(
        "synthetic-transport-v1", "Repository-owned static instruction.\n" * 1200
    )
    request = _prompt_cache_request(prompt, "sensitive-variable-suffix")
    plan = prepare_prompt_cache_request(
        mode="test",
        pricing=_pricing_catalog(),
        model=request["model"],
        feature="ticket.summary",
        prompt=prompt,
        request=request,
    )
    captured: dict[str, object] = {}
    attempts = 0

    def fake_openai_request(*, openai_client, data, timeout, logging_obj):
        nonlocal attempts
        attempts += 1
        captured.update(data)
        return {}, ChatCompletion(
            id="chatcmpl-prompt-cache-fixture",
            created=0,
            model="gpt-5.6-luna",
            object="chat.completion",
            choices=[
                Choice(
                    index=0,
                    finish_reason="stop",
                    message=ChatCompletionMessage(role="assistant", content="{}"),
                )
            ],
            usage=CompletionUsage(prompt_tokens=1200, completion_tokens=1, total_tokens=1201),
        )

    monkeypatch.setattr(
        litellm.main.openai_chat_completions,
        "make_sync_openai_chat_completion_request",
        fake_openai_request,
    )
    litellm.completion(
        model=request["model"],
        api_key="test-only-key",
        messages=plan.messages,
        response_format=request["response_format"],
        store=False,
        num_retries=0,
        **plan.transport_options,
    )

    assert captured["messages"] == plan.messages
    assert captured["prompt_cache_key"] == plan.cache_key
    assert captured["extra_body"] == {
        "prompt_cache_options": {"mode": "explicit", "ttl": "30m"}
    }
    assert "prompt_cache_options" not in captured
    assert "sensitive-variable-suffix" not in str(captured["prompt_cache_key"])
    assert attempts == 1


def test_litellm_adapters_disable_hidden_retries(monkeypatch) -> None:
    from types import SimpleNamespace

    import litellm

    calls: list[dict[str, object]] = []

    def fake_completion(**kwargs):
        calls.append(kwargs)
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content=(
                '{"type":"ticket.summary","problem":"공개 문의",'
                '"attemptedActions":[],"unresolvedItems":[],"nextChecks":[]}'
            )))],
            usage=SimpleNamespace(prompt_tokens=10, completion_tokens=5),
            id="response-1",
            model=settings.model_fast,
            service_tier="default",
        )

    def fake_embedding(**kwargs):
        calls.append(kwargs)
        return SimpleNamespace(
            data=[{"index": 0, "embedding": [0.0] * 1536}],
            usage=SimpleNamespace(total_tokens=3),
            id="embedding-1",
            model="openai/text-embedding-3-small",
            service_tier="default",
        )

    monkeypatch.setattr(litellm, "completion", fake_completion)
    monkeypatch.setattr(litellm, "embedding", fake_embedding)
    settings = Settings(
        environment="test", openai_api_key="test-only-key", prompt_cache_mode="test"
    )
    first_id = uuid4()
    second_id = uuid4()
    context = SourceContext(
        jobId=uuid4(),
        ticketId=uuid4(),
        ticketNumber=1,
        ticketVersion=0,
        feature=Feature.SUMMARY,
        requestRevision=1,
        contextRevision="a" * 64,
        contextPolicyVersion="public-comments-v2",
        inputScope="PUBLIC_ONLY",
        comments=[
            PublicComment(
                id=first_id,
                sequence=1,
                authorRole=AuthorRole.STAFF,
                body="먼저 확인했습니다.",
                createdAt=datetime.now(UTC),
            ),
            PublicComment(
                id=second_id,
                sequence=2,
                authorRole=AuthorRole.CUSTOMER,
                body="하지만 아직 로그인되지 않습니다.",
                createdAt=datetime.now(UTC),
            ),
        ],
    )

    receipts = []
    generated = LiteLlmGenerationProvider(settings, _pricing_catalog()).summary(
        context, {"language": "ko"}, uuid4(), receipts.append
    )
    LiteLlmEmbeddingProvider("openai/text-embedding-3-small", "test-only-key", 30).embed(
        "공개 도움말", uuid4(), receipts.append
    )

    assert len(calls) == 2
    assert all(call["num_retries"] == 0 for call in calls)
    assert calls[0]["store"] is False
    assert "prompt_cache_key" not in calls[0]
    assert "prompt_cache_options" not in calls[0]
    prompt_payload = json.loads(calls[0]["messages"][1]["content"])
    assert prompt_payload["options"] == {"language": "ko"}
    assert prompt_payload["publicConversation"] == [
        {
            "commentRef": "C1",
            "authorRole": "STAFF",
            "sequence": 1,
            "createdAt": context.comments[0].createdAt.isoformat(),
            "body": "먼저 확인했습니다.",
        },
        {
            "commentRef": "C2",
            "authorRole": "CUSTOMER",
            "sequence": 2,
            "createdAt": context.comments[1].createdAt.isoformat(),
            "body": "하지만 아직 로그인되지 않습니다.",
        },
    ]
    assert str(first_id) not in calls[0]["messages"][1]["content"]
    assert str(second_id) not in calls[0]["messages"][1]["content"]
    assert generated.prompt_version == prompt_for(Feature.SUMMARY).version
    assert generated.receipt.prompt_cache_status == PromptCacheStatus.INELIGIBLE
    assert generated.receipt.prompt_cache_prefix_tokens is not None
    assert len(receipts) == 2


def test_litellm_embedding_array_maps_indices_and_records_usage_before_validation(
    monkeypatch,
) -> None:
    from types import SimpleNamespace

    import litellm

    calls: list[dict[str, object]] = []

    def valid_embedding(**kwargs):
        calls.append(kwargs)
        return SimpleNamespace(
            data=[
                {"index": 1, "embedding": [1.0] * EMBEDDING_DIMENSION},
                {"index": 0, "embedding": [0.0] * EMBEDDING_DIMENSION},
            ],
            usage=SimpleNamespace(total_tokens=7),
            id="embedding-array-1",
            model="text-embedding-3-small",
            service_tier="default",
        )

    monkeypatch.setattr(litellm, "embedding", valid_embedding)
    provider = LiteLlmEmbeddingProvider(
        "openai/text-embedding-3-small", "test-only-key", 30
    )
    receipts = []
    result = provider.embed_many(["첫 입력", "둘째 입력"], uuid4(), receipts.append)

    assert calls[0]["input"] == ["첫 입력", "둘째 입력"]
    assert calls[0]["num_retries"] == 0
    assert result.vectors[0][0] == 0.0
    assert result.vectors[1][0] == 1.0
    assert result.receipt.usage == Usage(7, 0, 0, 0)
    assert len(receipts) == 1

    def duplicate_embedding(**_kwargs):
        return SimpleNamespace(
            data=[
                {"index": 0, "embedding": [0.0] * EMBEDDING_DIMENSION},
                {"index": 0, "embedding": [1.0] * EMBEDDING_DIMENSION},
            ],
            usage=SimpleNamespace(total_tokens=9),
            id="embedding-array-invalid",
            model="text-embedding-3-small",
            service_tier="default",
        )

    monkeypatch.setattr(litellm, "embedding", duplicate_embedding)
    with pytest.raises(ValueError, match="index mapping"):
        provider.embed_many(["첫 입력", "둘째 입력"], uuid4(), receipts.append)
    assert len(receipts) == 2
    assert receipts[-1].usage == Usage(9, 0, 0, 0)


def test_pinned_litellm_forwards_embedding_array_to_final_openai_body(monkeypatch) -> None:
    import litellm

    captured: dict[str, object] = {}
    attempts = 0

    class RawEmbeddingResponse:
        headers: dict[str, str] = {}

        @staticmethod
        def parse():
            class ParsedEmbeddingResponse:
                @staticmethod
                def model_dump():
                    return {
                        "object": "list",
                        "data": [
                            {
                                "object": "embedding",
                                "index": 0,
                                "embedding": [0.0] * EMBEDDING_DIMENSION,
                            },
                            {
                                "object": "embedding",
                                "index": 1,
                                "embedding": [1.0] * EMBEDDING_DIMENSION,
                            },
                        ],
                        "model": "text-embedding-3-small",
                        "usage": {"prompt_tokens": 8, "total_tokens": 8},
                    }

            return ParsedEmbeddingResponse()

    def fake_openai_request(*, openai_client, data, timeout, logging_obj):
        nonlocal attempts
        attempts += 1
        captured.update(data)
        return RawEmbeddingResponse()

    monkeypatch.setattr(
        litellm.main.openai_chat_completions,
        "make_sync_openai_embedding_request",
        fake_openai_request,
    )
    provider = LiteLlmEmbeddingProvider(
        "openai/text-embedding-3-small", "test-only-key", 30
    )
    result = provider.embed_many(["첫 입력", "둘째 입력"], uuid4(), lambda _receipt: None)

    assert captured == {
        "model": "text-embedding-3-small",
        "input": ["첫 입력", "둘째 입력"],
    }
    assert [vector[0] for vector in result.vectors] == [0.0, 1.0]
    assert attempts == 1


def test_reply_provider_receives_only_request_local_source_refs(monkeypatch) -> None:
    from types import SimpleNamespace

    import litellm

    settings = Settings(environment="test", openai_api_key="test-only-key")
    article_id = uuid4()
    revision_id = uuid4()
    chunk_id = uuid4()
    calls: list[dict[str, object]] = []

    def fake_completion(**kwargs):
        calls.append(kwargs)
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content='{"answer":"공개 안내","sourceRefs":["S1"]}'))],
            usage=SimpleNamespace(prompt_tokens=10, completion_tokens=5),
            id="response-reply-1",
            model=settings.model_standard,
            service_tier="default",
        )

    monkeypatch.setattr(litellm, "completion", fake_completion)
    context = _v2_context(Feature.REPLY_DRAFT, [(AuthorRole.CUSTOMER, "로그인 오류가 계속됩니다.")])
    knowledge = [
        KnowledgeChunk(
            chunk_id=chunk_id,
            article_id=article_id,
            revision_id=revision_id,
            title="로그인 도움말",
            slug="login-help",
            content="비밀번호 재설정 후 다시 로그인하세요.",
            score=1.0,
        )
    ]

    generated = LiteLlmGenerationProvider(settings, _pricing_catalog()).reply(
        context,
        knowledge,
        {"language": "ko", "tone": "calm"},
        uuid4(),
        lambda _receipt: None,
    )

    assert isinstance(generated.result, ReplyProviderOutput)
    payload = json.loads(calls[0]["messages"][1]["content"])
    assert payload["approvedPublicKnowledge"] == [
        {
            "sourceRef": "S1",
            "title": "로그인 도움말",
            "content": "비밀번호 재설정 후 다시 로그인하세요.",
        }
    ]
    serialized = calls[0]["messages"][1]["content"]
    assert str(article_id) not in serialized
    assert str(revision_id) not in serialized
    assert str(chunk_id) not in serialized
    assert "login-help" not in serialized
    schema = calls[0]["response_format"]["json_schema"]["schema"]
    assert set(schema["properties"]) == {"answer", "sourceRefs"}


def test_context_memory_transport_is_bounded_and_uses_only_local_comment_refs(monkeypatch) -> None:
    from types import SimpleNamespace

    import litellm

    settings = Settings(environment="test", openai_api_key="test-only-key")
    calls: list[dict[str, object]] = []

    def fake_completion(**kwargs):
        calls.append(kwargs)
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content=(
                '{"confirmedFacts":[{"text":"로그인 실패","sourceRefs":["C1","C2"]}],'
                '"attemptsAndOutcomes":[],"openQuestions":[],"conflictSourceRefs":[]}'
            )))],
            usage=SimpleNamespace(prompt_tokens=20, completion_tokens=10),
            id="response-memory-1",
            model=settings.model_fast,
            service_tier="default",
        )

    monkeypatch.setattr(litellm, "completion", fake_completion)
    full = _v2_context(
        Feature.REPLY_DRAFT,
        [
            (AuthorRole.CUSTOMER, "로그인되지 않습니다."),
            (AuthorRole.STAFF, "재설정도 실패했습니다."),
        ],
    )
    delta = full.model_copy(update={"comments": [full.comments[1]]})
    previous = ContextMemoryPayload(
        confirmedFacts=[ContextMemoryItem(text="로그인 실패", sourceRefs=["C1"])],
        attemptsAndOutcomes=[],
        openQuestions=[],
    )
    receipts = []

    generated = LiteLlmGenerationProvider(settings, _pricing_catalog()).context_memory(
        delta, previous, uuid4(), receipts.append
    )

    assert isinstance(generated.result, ContextMemoryProviderOutput)
    assert generated.prompt_version == context_memory_prompt().version
    assert len(receipts) == 1
    assert calls[0]["num_retries"] == 0
    assert calls[0]["store"] is False
    assert calls[0]["max_completion_tokens"] == 1024
    payload = json.loads(calls[0]["messages"][1]["content"])
    assert payload["previousMemory"]["confirmedFacts"][0]["sourceRefs"] == ["C1"]
    assert payload["publicConversationDelta"][0]["commentRef"] == "C2"
    assert str(full.comments[1].id) not in calls[0]["messages"][1]["content"]


def test_reply_provider_output_rejects_duplicate_and_nonlocal_source_refs() -> None:
    with pytest.raises(ValidationError):
        ReplyProviderOutput(answer="답변", sourceRefs=["S1", "S1"])
    with pytest.raises(ValidationError):
        ReplyProviderOutput(answer="답변", sourceRefs=["S99"])


def test_backend_citation_authorization_accepts_only_an_ordered_exact_subset(monkeypatch) -> None:
    import httpx

    first = Citation(
        articleId=uuid4(),
        revisionId=uuid4(),
        chunkId=uuid4(),
        title="첫 도움말",
        url="/help/articles/first-help",
    )
    second = Citation(
        articleId=uuid4(),
        revisionId=uuid4(),
        chunkId=uuid4(),
        title="두 번째 도움말",
        url="/help/articles/second-help",
    )
    response_items = [second.model_dump(mode="json")]

    class Response:
        status_code = 200

        def json(self):
            return {"items": response_items}

    class Client:
        def __init__(self, **_kwargs):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def post(self, *_args, **_kwargs):
            return Response()

    monkeypatch.setattr(httpx, "Client", Client)
    backend = BackendClient(Settings(environment="test"))

    assert backend.authorize_citations(uuid4(), [first, second]) == [second]

    response_items[:] = [second.model_dump(mode="json"), first.model_dump(mode="json")]
    with pytest.raises(BackendAuthorizationError):
        backend.authorize_citations(uuid4(), [first, second])

    response_items[:] = [second.model_copy(update={"chunkId": uuid4()}).model_dump(mode="json")]
    with pytest.raises(BackendAuthorizationError):
        backend.authorize_citations(uuid4(), [first, second])

    response_items[:] = [{"chunkId": str(second.chunkId)}]
    with pytest.raises(BackendAuthorizationError):
        backend.authorize_citations(uuid4(), [first, second])


def test_source_map_digest_is_deterministic_and_excludes_knowledge_body() -> None:
    first = Citation(
        articleId=uuid4(),
        revisionId=uuid4(),
        chunkId=uuid4(),
        title="첫 도움말",
        url="/help/articles/first-help",
    )
    second = Citation(
        articleId=uuid4(),
        revisionId=uuid4(),
        chunkId=uuid4(),
        title="두 번째 도움말",
        url="/help/articles/second-help",
    )
    ordered = {"S1": first, "S2": second}

    digest = source_map_digest(ordered)

    assert digest == source_map_digest(dict(ordered))
    assert digest != source_map_digest({"S1": second, "S2": first})
    assert len(digest) == 64


def test_reply_context_keeps_latest_customer_suffix_and_canonical_order() -> None:
    context = _v2_context(
        Feature.REPLY_DRAFT,
        [
            (AuthorRole.CUSTOMER, "첫 문의"),
            (AuthorRole.STAFF, "A" * 4_000),
            (AuthorRole.CUSTOMER, "변경된 최신 요구"),
            (AuthorRole.SYSTEM, "후속 상태"),
        ],
    )

    bounded = _bounded_context(context, Feature.REPLY_DRAFT)

    assert [(item.sequence, item.body) for item in bounded.comments] == [
        (1, "첫 문의"),
        (2, "A" * 4_000),
        (3, "변경된 최신 요구"),
        (4, "후속 상태"),
    ]


def test_reply_context_rejects_oversized_latest_customer_suffix() -> None:
    context = _v2_context(
        Feature.REPLY_DRAFT,
        [
            (AuthorRole.STAFF, "앞선 안내"),
            (AuthorRole.CUSTOMER, "가" * 7_000),
        ],
    )

    with pytest.raises(InputTooLongError):
        _bounded_context(context, Feature.REPLY_DRAFT)


def test_reply_context_without_customer_preserves_latest_non_customer_role() -> None:
    context = _v2_context(
        Feature.REPLY_DRAFT,
        [
            (AuthorRole.STAFF, "최초 상담사 문의"),
            (AuthorRole.SYSTEM, "최신 시스템 상태"),
        ],
    )

    bounded = _bounded_context(context, Feature.REPLY_DRAFT)

    assert bounded.comments[-1].authorRole == AuthorRole.SYSTEM
    assert bounded.comments[-1].body == "최신 시스템 상태"


def test_reply_query_uses_only_the_latest_customer_problem() -> None:
    context = _v2_context(
        Feature.REPLY_DRAFT,
        [
            (AuthorRole.CUSTOMER, "Deskseed 로그인 ERR-OLD 오류"),
            (AuthorRole.STAFF, "비밀번호 재설정을 안내했습니다."),
            (AuthorRole.CUSTOMER, "  Deskseed 로그인에서 ERR-42 가 계속됩니다.  "),
            (AuthorRole.SYSTEM, "상태가 갱신되었습니다."),
        ],
    )

    query = reply_query(context, lambda value: len(value.split()))

    assert query.embedding_text == "Deskseed 로그인에서 ERR-42 가 계속됩니다."
    assert "비밀번호" not in query.embedding_text
    assert "상태" not in query.embedding_text
    assert "ERR-42" in query.keyword_tokens
    assert query.error_codes == ("ERR-42",)


def test_reply_query_uses_last_public_comment_for_legacy_source() -> None:
    now = datetime.now(UTC)
    context = SourceContext(
        jobId=uuid4(),
        ticketId=uuid4(),
        ticketNumber=1,
        ticketVersion=0,
        feature=Feature.REPLY_DRAFT,
        requestRevision=1,
        contextRevision="a" * 64,
        contextPolicyVersion="public-comments-v1",
        inputScope="PUBLIC_ONLY",
        comments=[
            PublicComment(id=uuid4(), body="이전 문의", createdAt=now),
            PublicComment(id=uuid4(), body="마지막 공개 문의", createdAt=now),
        ],
    )

    assert reply_query(context).embedding_text == "마지막 공개 문의"


def test_retrieval_query_is_bounded_and_allows_only_literal_tokens() -> None:
    query = build_retrieval_query(
        "로그인\u0000 ERR-42'; DROP TABLE ai_kb_chunks; -- 결제/취소 A1.B2 "
        + " ".join(f"word{index}" for index in range(30))
        + " CRITICAL-999",
        lambda value: len(value.split()),
    )

    assert len(query.keyword_tokens) == 16
    assert all('"' not in token and "'" not in token and ";" not in token for token in query.keyword_tokens)
    assert "ERR-42" in query.error_codes
    assert "CRITICAL-999" in query.keyword_tokens
    assert len(query.error_codes) <= 8


def test_retrieval_query_rejects_empty_or_over_cap_current_problem() -> None:
    with pytest.raises(MissingCurrentProblemError):
        build_retrieval_query(" \u0000 \n")
    with pytest.raises(RetrievalQueryTooLongError):
        build_retrieval_query("핵심 질문", lambda _value: EMBEDDING_QUERY_TOKEN_LIMIT + 1)


def test_section_chunker_preserves_public_structure_and_bounds_embedding_input() -> None:
    def count_tokens(value: str) -> int:
        return len(value.split())

    body = (
        "환불 조건은 결제 후 7일 이내입니다. 단, 사용한 상품은 제외합니다.\n\n"
        "항목 | 기준\n기간 | 7일\n예외 | 사용 상품\n\n"
        + " ".join(f"긴문장{index}" for index in range(80))
        + "\u0000"
    )

    chunks = build_public_article_chunks(
        " 환불\u00a0정책 ",
        " 고객 지원 ",
        " 결제와 환불 ",
        body,
        count_tokens,
        max_tokens=32,
    )

    assert len(chunks) > 2
    assert "환불 조건은 결제 후 7일 이내입니다. 단, 사용한 상품은 제외합니다." in chunks[0].body
    assert "항목 | 기준\n기간 | 7일\n예외 | 사용 상품" in chunks[0].body
    assert all(count_tokens(chunk.embedding_input) <= 32 for chunk in chunks)
    assert all("Document title: 환불 정책" in chunk.embedding_input for chunk in chunks)
    assert all("Category: 고객 지원" in chunk.embedding_input for chunk in chunks)
    assert all("Section: 결제와 환불" in chunk.embedding_input for chunk in chunks)
    assert all("Document title:" not in chunk.body for chunk in chunks)
    assert all("\u0000" not in chunk.body for chunk in chunks)
    assert chunks == build_public_article_chunks(
        " 환불\u00a0정책 ",
        " 고객 지원 ",
        " 결제와 환불 ",
        body,
        count_tokens,
        max_tokens=32,
    )


def _v2_context(feature: Feature, comments: list[tuple[AuthorRole, str]]) -> SourceContext:
    now = datetime.now(UTC)
    return SourceContext(
        jobId=uuid4(),
        ticketId=uuid4(),
        ticketNumber=1,
        ticketVersion=0,
        feature=feature,
        requestRevision=1,
        contextRevision="a" * 64,
        contextPolicyVersion="public-comments-v2",
        inputScope="PUBLIC_ONLY",
        comments=[
            PublicComment(
                id=uuid4(),
                sequence=index,
                authorRole=role,
                body=body,
                createdAt=now + timedelta(seconds=index),
            )
            for index, (role, body) in enumerate(comments, start=1)
        ],
    )
