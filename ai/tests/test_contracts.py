from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from pydantic import ValidationError

from deskseed_ai.config import Settings
from deskseed_ai.pricing import PricingCatalog, Usage
from deskseed_ai.providers import LiteLlmGenerationProvider
from deskseed_ai.retrieval import LiteLlmEmbeddingProvider
from deskseed_ai.schemas import Feature, JobEnvelope, PublicComment, SourceContext
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
    context = SourceContext(
        jobId=uuid4(),
        ticketId=uuid4(),
        ticketNumber=1,
        ticketVersion=0,
        feature=Feature.SUMMARY,
        requestRevision=1,
        contextRevision="a" * 64,
        contextPolicyVersion="public-comments-v1",
        inputScope="PUBLIC_ONLY",
        comments=[PublicComment(id=uuid4(), body="공개 문의", createdAt=datetime.now(UTC))],
    )

    LiteLlmGenerationProvider(settings).summary(context)
    LiteLlmEmbeddingProvider("openai/text-embedding-3-small", "test-only-key", 30).embed("공개 도움말")

    assert len(calls) == 2
    assert all(call["num_retries"] == 0 for call in calls)
    assert calls[0]["store"] is False
