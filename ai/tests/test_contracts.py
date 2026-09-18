from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from pydantic import ValidationError

from deskseed_ai.config import Settings
from deskseed_ai.pricing import PricingCatalog, Usage
from deskseed_ai.prompting import prompt_for
from deskseed_ai.providers import LiteLlmGenerationProvider
from deskseed_ai.queue import InputTooLongError, _bounded_context
from deskseed_ai.retrieval import LiteLlmEmbeddingProvider
from deskseed_ai.schemas import AuthorRole, Feature, JobEnvelope, PublicComment, SourceContext
from deskseed_ai.security import authenticate_machine


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
        '{"version":"v1","models":{"m":{"input":200000,"cachedInput":20000,"output":1200000}}}',
        encoding="utf-8",
    )
    catalog = PricingCatalog(path)
    assert catalog.cost_microusd("m", Usage(input_tokens=1, cached_input_tokens=0, output_tokens=1)) == 2


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
        )

    def fake_embedding(**kwargs):
        calls.append(kwargs)
        return SimpleNamespace(
            data=[{"embedding": [0.0] * 1536}],
            usage=SimpleNamespace(total_tokens=3),
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

    generated = LiteLlmGenerationProvider(settings).summary(context, {"language": "ko"})
    LiteLlmEmbeddingProvider("openai/text-embedding-3-small", "test-only-key", 30).embed("공개 도움말")

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
