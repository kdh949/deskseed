from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

import pytest
from pydantic import ValidationError

from deskseed_ai.backend_client import BackendAuthorizationError, BackendClient
from deskseed_ai.call_receipts import UsageStatus
from deskseed_ai.config import Settings
from deskseed_ai.pricing import PricingCatalog, Usage
from deskseed_ai.prompting import prompt_for
from deskseed_ai.providers import LiteLlmGenerationProvider
from deskseed_ai.queue import InputTooLongError, _bounded_context
from deskseed_ai.retrieval import KnowledgeChunk, LiteLlmEmbeddingProvider
from deskseed_ai.schemas import (
    AuthorRole,
    Citation,
    Feature,
    JobEnvelope,
    PublicComment,
    ReplyProviderOutput,
    SourceContext,
)
from deskseed_ai.security import authenticate_machine
from deskseed_ai.usage_normalization import normalize_litellm_usage
from deskseed_ai.workflows import source_map_digest


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
            data=[{"embedding": [0.0] * 1536}],
            usage=SimpleNamespace(total_tokens=3),
            id="embedding-1",
            model="openai/text-embedding-3-small",
            service_tier="default",
        )

    monkeypatch.setattr(litellm, "completion", fake_completion)
    monkeypatch.setattr(litellm, "embedding", fake_embedding)
    settings = Settings(environment="test", openai_api_key="test-only-key")
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
    generated = LiteLlmGenerationProvider(settings).summary(
        context, {"language": "ko"}, uuid4(), receipts.append
    )
    LiteLlmEmbeddingProvider("openai/text-embedding-3-small", "test-only-key", 30).embed(
        "공개 도움말", uuid4(), receipts.append
    )

    assert len(calls) == 2
    assert all(call["num_retries"] == 0 for call in calls)
    assert calls[0]["store"] is False
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
    assert len(receipts) == 2


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

    generated = LiteLlmGenerationProvider(settings).reply(
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
