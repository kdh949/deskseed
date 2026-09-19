from __future__ import annotations

import hashlib
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import pytest
from cryptography.exceptions import InvalidTag
from fastapi.testclient import TestClient
from psycopg.errors import CheckViolation

from deskseed_ai.backend_client import (
    BackendAuthorizationError,
    PublicKnowledgeArticle,
)
from deskseed_ai.call_receipts import ProviderCallReceipt, UsageStatus
from deskseed_ai.config import Settings
from deskseed_ai.indexing import IndexingService
from deskseed_ai.main import app
from deskseed_ai.observability import TraceAdapter
from deskseed_ai.pricing import Usage
from deskseed_ai.providers import FakeGenerationProvider, InvalidProviderOutputError, ProviderResult
from deskseed_ai.queue import StreamRuntime
from deskseed_ai.repository import (
    ActiveLeaseError,
    BudgetExceededError,
    ConflictError,
    NotFoundError,
    ProviderCallStateUnknownError,
    ProviderReceiptConflictError,
    Repository,
    StaleLeaseError,
)
from deskseed_ai.retrieval import FakeEmbeddingProvider, KnowledgeRepository, chunk_public_article
from deskseed_ai.schemas import (
    CancellationEnvelope,
    Feature,
    FeedbackRequest,
    IndexEvent,
    JobEnvelope,
    JobStatus,
    PublicComment,
    ReplyProviderOutput,
    SourceContext,
)


def envelope(feature: Feature = Feature.SUMMARY) -> JobEnvelope:
    now = datetime.now(UTC)
    return JobEnvelope(
        schemaVersion=1,
        eventId=uuid4(),
        jobId=uuid4(),
        workspaceKey="default",
        requesterId=uuid4(),
        ticketId=uuid4(),
        ticketNumber=1042,
        feature=feature,
        contextRevision="a" * 64,
        contextPolicyVersion="public-comments-v1",
        dataClass="PUBLIC_ONLY",
        requestRevision=1,
        options={"language": "ko"},
        createdAt=now,
        deadlineAt=now + timedelta(minutes=2),
    )


def envelope_v2(feature: Feature = Feature.SUMMARY) -> JobEnvelope:
    now = datetime.now(UTC)
    policy = {
        Feature.SUMMARY: "summary-input-v1",
        Feature.TRIAGE: "triage-input-v1",
        Feature.REPLY_DRAFT: "reply-input-v1",
    }[feature]
    options = {"language": "ko"}
    if feature == Feature.REPLY_DRAFT:
        options["tone"] = "calm"
    return JobEnvelope(
        schemaVersion=2,
        eventId=uuid4(),
        jobId=uuid4(),
        workspaceKey="default",
        requesterId=uuid4(),
        ticketId=uuid4(),
        ticketNumber=1042,
        feature=feature,
        contextRevision="a" * 64,
        contextPolicyVersion="public-comments-v2",
        aiInputRevision="b" * 64,
        inputPolicyVersion=policy,
        dataClass="PUBLIC_ONLY",
        requestRevision=1,
        options=options,
        createdAt=now,
        deadlineAt=now + timedelta(minutes=2),
    )


def matching_v2_job(origin: JobEnvelope) -> JobEnvelope:
    now = datetime.now(UTC)
    return origin.model_copy(
        update={
            "eventId": uuid4(),
            "jobId": uuid4(),
            "createdAt": now,
            "deadlineAt": now + timedelta(minutes=2),
        }
    )


def cache_enabled(settings: Settings) -> Settings:
    return Settings.model_validate(
        settings.model_dump()
        | {
            "exact_result_cache_mode": "test",
            "result_cache_key_secret": "synthetic-cache-key-secret-at-least-32-bytes",
        }
    )


def provider_receipt(call_id, alias="openai/gpt-5.6-luna", request_id="provider-1"):
    return ProviderCallReceipt(
        call_id=call_id,
        provider_request_id=request_id,
        requested_alias=alias,
        actual_model=alias,
        usage_schema_version="fixture-v1",
        usage_status=UsageStatus.KNOWN,
        usage=Usage(10, 2, 1, 5),
        usage_issue_code=None,
        service_tier="standard",
        context_price_band="short",
    )


@pytest.mark.integration
def test_accept_is_exact_idempotent_and_dispatch_payload_is_content_free(
    repository: Repository, settings: Settings
) -> None:
    item = envelope()
    first = repository.accept_job(item)
    replay = repository.accept_job(item)
    assert first.replayed is False
    assert replay.replayed is True

    conflict = item.model_copy(update={"contextRevision": "b" * 64})
    with pytest.raises(ConflictError):
        repository.accept_job(conflict)

    runtime = runtime_for(repository, settings, StaticBackend(item))
    assert runtime.dispatch_once() == 1
    messages = runtime.redis.xrange(settings.stream_name)
    assert len(messages) == 1
    fields = messages[0][1]
    assert set(fields) == {"schema_version", "job_id", "generation", "traceparent", "tracestate"}
    assert "공개" not in repr(fields)


@pytest.mark.integration
def test_authenticated_api_runs_fake_provider_flow_with_real_postgres_and_redis(
    repository: Repository, settings: Settings
) -> None:
    secret = "synthetic-backend-to-ai-secret"
    authenticated = Settings.model_validate(
        settings.model_dump()
        | {
            "inbound_auth_enabled": True,
            "inbound_key_id": "synthetic-backend-v1",
            "inbound_secret_sha256": hashlib.sha256(secret.encode()).hexdigest(),
        }
    )
    app.state.runtime = SimpleNamespace(settings=authenticated, repository=repository)
    client = TestClient(app)
    item = envelope(Feature.SUMMARY)
    headers = {
        "X-Deskseed-AI-Key-Id": "synthetic-backend-v1",
        "Authorization": f"Bearer {secret}",
    }

    assert client.post("/internal/v1/jobs", json=item.model_dump(mode="json")).status_code == 401
    accepted = client.post("/internal/v1/jobs", json=item.model_dump(mode="json"), headers=headers)
    assert accepted.status_code == 202
    runtime = runtime_for(repository, authenticated, StaticBackend(item))
    assert runtime.dispatch_once() == 1
    assert runtime.consume_once(block_ms=1) == 1

    metadata = client.get(f"/internal/v1/jobs/{item.jobId}", headers=headers)
    assert metadata.status_code == 200
    assert metadata.json()["status"] == "SUCCEEDED"
    assert metadata.json()["result"] is None
    result = client.get(f"/internal/v1/jobs/{item.jobId}?includeResult=true", headers=headers)
    assert result.status_code == 200
    assert result.json()["result"]["type"] == "ticket.summary"


@pytest.mark.integration
@pytest.mark.parametrize("feature", [Feature.SUMMARY, Feature.TRIAGE, Feature.REPLY_DRAFT])
def test_fake_provider_completes_typed_job_through_postgres_and_redis(
    repository: Repository, settings: Settings, feature: Feature
) -> None:
    item = envelope(feature)
    repository.accept_job(item)
    backend = StaticBackend(item)
    runtime = runtime_for(repository, settings, backend)
    assert runtime.dispatch_once() == 1
    assert runtime.consume_once(block_ms=1) == 1

    receipt = repository.get_job(item.jobId)
    expected = JobStatus.NEEDS_REVIEW if feature == Feature.REPLY_DRAFT else JobStatus.SUCCEEDED
    assert receipt.status == expected
    if feature == Feature.REPLY_DRAFT:
        assert receipt.result is None
        assert receipt.errorCode == "NO_APPROVED_KNOWLEDGE"
    else:
        assert receipt.result is not None
        assert receipt.result.type == feature.value
    assert receipt.costMicrousd is not None and receipt.costMicrousd >= 0
    with repository.database.connection() as connection:
        row = connection.execute(
            "select result_ciphertext, result_nonce, status, trace_id from ai_jobs where job_id = %s", (item.jobId,)
        ).fetchone()
        ledger = connection.execute(
            "select status, settled_microusd from ai_cost_ledger where job_id = %s", (item.jobId,)
        ).fetchone()
        calls = connection.execute(
            "select call_id, trace_id, observation_id, stage from ai_provider_calls where job_id = %s",
            (item.jobId,),
        ).fetchall()
    assert row["trace_id"] == item.jobId.hex
    assert calls
    assert all(call["trace_id"] == item.jobId.hex for call in calls)
    assert all(call["observation_id"] == call["call_id"].hex for call in calls)
    if feature == Feature.REPLY_DRAFT:
        assert row["result_ciphertext"] is None
        assert row["result_nonce"] is None
        assert [call["stage"] for call in calls] == ["QUERY_EMBEDDING"]
    else:
        assert bytes(row["result_ciphertext"]).find("ticket".encode()) == -1
        assert row["result_nonce"] is not None
    assert ledger["status"] == "SETTLED"


@pytest.mark.integration
def test_schema_v2_job_persists_and_executes_with_bound_input_revision(
    repository: Repository, settings: Settings
) -> None:
    item = envelope_v2(Feature.SUMMARY)

    repository.accept_job(item)

    with repository.database.connection() as connection:
        stored = connection.execute(
            "select ai_input_revision, input_policy_version from ai_jobs where job_id = %s",
            (item.jobId,),
        ).fetchone()
    assert stored == {
        "ai_input_revision": item.aiInputRevision,
        "input_policy_version": item.inputPolicyVersion,
    }
    runtime = runtime_for(repository, settings, StaticBackend(item))
    assert runtime.dispatch_once() == 1
    assert runtime.consume_once(block_ms=1) == 1
    assert repository.get_job(item.jobId).status == JobStatus.SUCCEEDED


@pytest.mark.integration
@pytest.mark.parametrize("feature", [Feature.SUMMARY, Feature.TRIAGE])
def test_exact_result_cache_reencrypts_completed_result_without_second_provider_call(
    repository: Repository, settings: Settings, feature: Feature
) -> None:
    enabled = cache_enabled(settings)
    origin = envelope_v2(feature)
    consumer = matching_v2_job(origin)

    repository.accept_job(origin)
    origin_runtime = runtime_for(repository, enabled, StaticBackend(origin))
    assert origin_runtime.dispatch_once() == 1
    assert origin_runtime.consume_once(block_ms=1) == 1

    repository.accept_job(consumer)
    consumer_runtime = runtime_for(repository, enabled, StaticBackend(consumer))
    assert consumer_runtime.dispatch_once() == 1
    assert consumer_runtime.consume_once(block_ms=1) == 1

    origin_receipt = repository.get_job(origin.jobId)
    consumer_receipt = repository.get_job(consumer.jobId)
    assert consumer_receipt.status == JobStatus.SUCCEEDED
    assert consumer_receipt.result == origin_receipt.result
    assert consumer_receipt.costMicrousd == 0
    assert consumer_receipt.provenance is not None
    assert origin_receipt.provenance is not None
    assert consumer_receipt.provenance.generatedAt == origin_receipt.provenance.generatedAt
    assert consumer_receipt.resultExpiresAt < origin_receipt.resultExpiresAt

    with repository.database.connection() as connection:
        rows = connection.execute(
            """
            select job_id, result_ciphertext, result_nonce, result_origin_job_id, reuse_kind
            from ai_jobs where job_id in (%s, %s) order by job_id
            """,
            (origin.jobId, consumer.jobId),
        ).fetchall()
        calls = connection.execute(
            "select job_id from ai_provider_calls where job_id in (%s, %s)",
            (origin.jobId, consumer.jobId),
        ).fetchall()
        cached = connection.execute(
            "select origin_job_id, expires_at, invalidated_at from ai_result_cache"
        ).fetchone()
    by_job = {row["job_id"]: row for row in rows}
    assert len(calls) == 1
    assert calls[0]["job_id"] == origin.jobId
    assert by_job[consumer.jobId]["result_origin_job_id"] == origin.jobId
    assert by_job[consumer.jobId]["reuse_kind"] == "EXACT_CACHE_HIT"
    assert by_job[consumer.jobId]["result_ciphertext"] != by_job[origin.jobId]["result_ciphertext"]
    assert by_job[consumer.jobId]["result_nonce"] != by_job[origin.jobId]["result_nonce"]
    assert cached["origin_job_id"] == origin.jobId
    assert cached["invalidated_at"] is None
    assert cached["expires_at"] == consumer_receipt.resultExpiresAt
    with pytest.raises(InvalidTag):
        repository.cipher.decrypt(
            bytes(by_job[consumer.jobId]["result_ciphertext"]),
            bytes(by_job[consumer.jobId]["result_nonce"]),
            str(origin.jobId).encode(),
        )


@pytest.mark.integration
def test_reply_result_cache_requires_matching_published_index_and_reauthorizes_citations(
    repository: Repository, settings: Settings
) -> None:
    enabled = cache_enabled(settings)
    index_public_chunks(repository, 1)
    publish_current_index_generation(repository, canonical_corpus_revision=7)
    origin = envelope_v2(Feature.REPLY_DRAFT)
    consumer = matching_v2_job(origin)

    class CountingBackend(StaticBackend):
        def __init__(self, item):
            super().__init__(item)
            self.authorization_count = 0

        def authorize_citations(self, job_id, citations):
            self.authorization_count += 1
            return super().authorize_citations(job_id, citations)

    repository.accept_job(origin)
    origin_backend = CountingBackend(origin)
    origin_runtime = runtime_for(repository, enabled, origin_backend)
    assert origin_runtime.dispatch_once() == 1
    assert origin_runtime.consume_once(block_ms=1) == 1

    repository.accept_job(consumer)
    consumer_backend = CountingBackend(consumer)
    consumer_runtime = runtime_for(repository, enabled, consumer_backend)
    assert consumer_runtime.dispatch_once() == 1
    assert consumer_runtime.consume_once(block_ms=1) == 1

    origin_receipt = repository.get_job(origin.jobId)
    consumer_receipt = repository.get_job(consumer.jobId)
    assert origin_receipt.status == JobStatus.SUCCEEDED
    assert consumer_receipt.status == JobStatus.SUCCEEDED
    assert consumer_receipt.result == origin_receipt.result
    assert consumer_receipt.costMicrousd == 0
    assert origin_backend.authorization_count == 2
    assert consumer_backend.authorization_count == 1
    with repository.database.connection() as connection:
        calls = connection.execute(
            "select job_id, stage from ai_provider_calls order by created_at"
        ).fetchall()
        cache = connection.execute(
            "select key_version, feature, origin_job_id from ai_result_cache"
        ).fetchone()
        jobs = connection.execute(
            """
            select job_id, result_ciphertext, result_nonce, result_origin_job_id, reuse_kind
            from ai_jobs where job_id in (%s, %s)
            """,
            (origin.jobId, consumer.jobId),
        ).fetchall()
    assert calls == [
        {"job_id": origin.jobId, "stage": "QUERY_EMBEDDING"},
        {"job_id": origin.jobId, "stage": "GENERATION"},
    ]
    assert cache == {
        "key_version": "result-cache-reply-v1",
        "feature": Feature.REPLY_DRAFT.value,
        "origin_job_id": origin.jobId,
    }
    by_job = {row["job_id"]: row for row in jobs}
    assert by_job[consumer.jobId]["result_origin_job_id"] == origin.jobId
    assert by_job[consumer.jobId]["reuse_kind"] == "EXACT_CACHE_HIT"
    assert by_job[consumer.jobId]["result_ciphertext"] != by_job[origin.jobId]["result_ciphertext"]
    assert by_job[consumer.jobId]["result_nonce"] != by_job[origin.jobId]["result_nonce"]


@pytest.mark.integration
def test_reply_cache_misses_when_corpus_changes_or_cached_citation_is_withdrawn(
    repository: Repository, settings: Settings
) -> None:
    enabled = cache_enabled(settings)
    index_public_chunks(repository, 1)
    publish_current_index_generation(repository, canonical_corpus_revision=7)
    origin = envelope_v2(Feature.REPLY_DRAFT)
    repository.accept_job(origin)
    origin_runtime = runtime_for(repository, enabled, StaticBackend(origin))
    assert origin_runtime.dispatch_once() == 1
    assert origin_runtime.consume_once(block_ms=1) == 1

    corpus_changed = matching_v2_job(origin)

    class CorpusChangedBackend(StaticBackend):
        def read_policy(self, feature):
            return SimpleNamespace(
                enabled=True,
                features={feature: True},
                fastModelAlias="openai/gpt-5.6-luna",
                standardModelAlias="openai/gpt-5.6-terra",
                version=1,
                canonicalPublicCorpusRevision=8,
            )

    repository.accept_job(corpus_changed)
    changed_backend = CorpusChangedBackend(corpus_changed)
    changed_runtime = runtime_for(repository, enabled, changed_backend)
    assert changed_runtime.dispatch_once() == 1
    assert changed_runtime.consume_once(block_ms=1) == 1

    withdrawn = matching_v2_job(origin)

    class WithdrawOnceBackend(StaticBackend):
        def __init__(self, item):
            super().__init__(item)
            self.authorization_count = 0

        def authorize_citations(self, job_id, citations):
            self.authorization_count += 1
            if self.authorization_count == 1:
                return []
            return super().authorize_citations(job_id, citations)

    repository.accept_job(withdrawn)
    withdrawn_backend = WithdrawOnceBackend(withdrawn)
    withdrawn_runtime = runtime_for(repository, enabled, withdrawn_backend)
    assert withdrawn_runtime.dispatch_once() == 1
    assert withdrawn_runtime.consume_once(block_ms=1) == 1

    with repository.database.connection() as connection:
        calls_by_job = connection.execute(
            """
            select job_id, count(*) as count from ai_provider_calls
            where job_id in (%s, %s, %s) group by job_id
            """,
            (origin.jobId, corpus_changed.jobId, withdrawn.jobId),
        ).fetchall()
        jobs = connection.execute(
            """
            select job_id, result_origin_job_id, reuse_kind
            from ai_jobs where job_id in (%s, %s)
            """,
            (corpus_changed.jobId, withdrawn.jobId),
        ).fetchall()
    assert {row["job_id"]: row["count"] for row in calls_by_job} == {
        origin.jobId: 2,
        corpus_changed.jobId: 2,
        withdrawn.jobId: 2,
    }
    assert all(row["result_origin_job_id"] is None and row["reuse_kind"] is None for row in jobs)
    assert withdrawn_backend.authorization_count == 3


@pytest.mark.integration
def test_default_off_and_legacy_jobs_never_use_exact_result_cache(
    repository: Repository, settings: Settings
) -> None:
    first = envelope_v2(Feature.SUMMARY)
    second = matching_v2_job(first)
    for item in (first, second):
        repository.accept_job(item)
        runtime = runtime_for(repository, settings, StaticBackend(item))
        assert runtime.dispatch_once() == 1
        assert runtime.consume_once(block_ms=1) == 1

    legacy = envelope(Feature.SUMMARY)
    repository.accept_job(legacy)
    legacy_runtime = runtime_for(repository, cache_enabled(settings), StaticBackend(legacy))
    assert legacy_runtime.dispatch_once() == 1
    assert legacy_runtime.consume_once(block_ms=1) == 1

    with repository.database.connection() as connection:
        assert connection.execute("select count(*) as count from ai_result_cache").fetchone()["count"] == 0
        assert connection.execute("select count(*) as count from ai_provider_calls").fetchone()["count"] == 3


@pytest.mark.integration
def test_origin_cancel_and_invalid_ciphertext_force_cache_miss(
    repository: Repository, settings: Settings
) -> None:
    enabled = cache_enabled(settings)
    origin = envelope_v2(Feature.SUMMARY)
    repository.accept_job(origin)
    runtime = runtime_for(repository, enabled, StaticBackend(origin))
    assert runtime.dispatch_once() == 1
    assert runtime.consume_once(block_ms=1) == 1

    repository.cancel(
        CancellationEnvelope(
            schemaVersion=1,
            eventId=uuid4(),
            jobId=origin.jobId,
            workspaceKey=origin.workspaceKey,
            requestRevision=2,
            createdAt=datetime.now(UTC),
        )
    )
    replacement = matching_v2_job(origin)
    repository.accept_job(replacement)
    replacement_runtime = runtime_for(repository, enabled, StaticBackend(replacement))
    assert replacement_runtime.dispatch_once() == 1
    assert replacement_runtime.consume_once(block_ms=1) == 1

    with repository.database.transaction() as connection:
        connection.execute(
            "update ai_jobs set result_ciphertext = decode(repeat('00', 32), 'hex') where job_id = %s",
            (replacement.jobId,),
        )
    after_tamper = matching_v2_job(origin)
    repository.accept_job(after_tamper)
    tamper_runtime = runtime_for(repository, enabled, StaticBackend(after_tamper))
    assert tamper_runtime.dispatch_once() == 1
    assert tamper_runtime.consume_once(block_ms=1) == 1

    with repository.database.connection() as connection:
        calls = connection.execute("select count(*) as count from ai_provider_calls").fetchone()["count"]
        row = connection.execute(
            "select result_origin_job_id, reuse_kind from ai_jobs where job_id = %s", (after_tamper.jobId,)
        ).fetchone()
    assert calls == 3
    assert row == {"result_origin_job_id": None, "reuse_kind": None}


@pytest.mark.integration
def test_concurrent_completed_cache_consumers_keep_independent_ciphertexts(
    repository: Repository, settings: Settings
) -> None:
    enabled = cache_enabled(settings)
    origin = envelope_v2(Feature.SUMMARY)
    repository.accept_job(origin)
    runtime = runtime_for(repository, enabled, StaticBackend(origin))
    assert runtime.dispatch_once() == 1
    assert runtime.consume_once(block_ms=1) == 1
    with repository.database.connection() as connection:
        cache_key = connection.execute("select cache_key from ai_result_cache").fetchone()["cache_key"]

    consumers = [matching_v2_job(origin) for _ in range(8)]
    claims = []
    for consumer in consumers:
        repository.accept_job(consumer)
        claim = repository.claim_job(consumer.jobId, 1, settings.consumer_name)
        assert claim is not None
        claims.append(claim)
    with ThreadPoolExecutor(max_workers=8) as pool:
        hits = list(pool.map(lambda claim: repository.complete_from_cache(claim, cache_key), claims))
    assert hits == [True] * 8

    with repository.database.connection() as connection:
        rows = connection.execute(
            """
            select job_id, result_nonce, result_origin_job_id, cost_microusd
            from ai_jobs where result_origin_job_id = %s
            """,
            (origin.jobId,),
        ).fetchall()
        call_count = connection.execute(
            "select count(*) as count from ai_provider_calls where job_id = %s", (origin.jobId,)
        ).fetchone()["count"]
    assert len(rows) == 8
    assert len({bytes(row["result_nonce"]) for row in rows}) == 8
    assert all(row["cost_microusd"] == 0 for row in rows)
    assert call_count == 1


@pytest.mark.integration
def test_cache_retention_is_bounded_and_idempotent(repository: Repository, settings: Settings) -> None:
    enabled = cache_enabled(settings)
    origin = envelope_v2(Feature.TRIAGE)
    repository.accept_job(origin)
    runtime = runtime_for(repository, enabled, StaticBackend(origin))
    assert runtime.dispatch_once() == 1
    assert runtime.consume_once(block_ms=1) == 1
    with repository.database.transaction() as connection:
        connection.execute(
            """
            update ai_result_cache set
                created_at = clock_timestamp() - interval '25 hours',
                expires_at = clock_timestamp() - interval '1 hour'
            """
        )

    assert repository.purge_expired_cache_entries(limit=1) == 1
    assert repository.purge_expired_cache_entries(limit=1) == 0


@pytest.mark.integration
def test_result_cache_database_rejects_unpaired_reuse_and_reply_entries(
    repository: Repository,
) -> None:
    item = envelope_v2(Feature.SUMMARY)
    repository.accept_job(item)
    with pytest.raises(CheckViolation):
        with repository.database.transaction() as connection:
            connection.execute(
                "update ai_jobs set reuse_kind = 'EXACT_CACHE_HIT' where job_id = %s",
                (item.jobId,),
            )
    with pytest.raises(CheckViolation):
        with repository.database.transaction() as connection:
            connection.execute(
                """
                insert into ai_result_cache (
                    cache_key, key_version, workspace_key, requester_id, ticket_id, feature,
                    origin_job_id, created_at, expires_at
                ) values (
                    %s, 'result-cache-summary-triage-v1', %s, %s, %s, 'ticket.reply_draft',
                    %s, clock_timestamp(), clock_timestamp() + interval '1 hour'
                )
                """,
                ("a" * 64, item.workspaceKey, item.requesterId, item.ticketId, item.jobId),
            )


@pytest.mark.integration
def test_ai_database_rejects_unpaired_or_feature_mismatched_input_revision(
    repository: Repository,
) -> None:
    item = envelope_v2(Feature.SUMMARY)
    repository.accept_job(item)

    with pytest.raises(CheckViolation):
        with repository.database.transaction() as connection:
            connection.execute(
                "update ai_jobs set ai_input_revision = null where job_id = %s",
                (item.jobId,),
            )
    with pytest.raises(CheckViolation):
        with repository.database.transaction() as connection:
            connection.execute(
                "update ai_jobs set input_policy_version = 'reply-input-v1' where job_id = %s",
                (item.jobId,),
            )


@pytest.mark.integration
def test_schema_v2_job_is_superseded_when_input_revision_changes_before_commit(
    repository: Repository, settings: Settings
) -> None:
    item = envelope_v2(Feature.SUMMARY)
    repository.accept_job(item)

    class ChangedInputBackend(StaticBackend):
        def read_context_revision(self, job_id):
            current = super().read_context_revision(job_id)
            current.aiInputRevision = "c" * 64
            return current

    runtime = runtime_for(repository, settings, ChangedInputBackend(item))
    assert runtime.dispatch_once() == 1
    assert runtime.consume_once(block_ms=1) == 1

    job = repository.get_job(item.jobId)
    assert job.status == JobStatus.SUPERSEDED
    assert job.result is None


@pytest.mark.integration
def test_invalid_output_after_response_keeps_known_cost_and_no_result(
    repository: Repository, settings: Settings
) -> None:
    item = envelope(Feature.SUMMARY)
    repository.accept_job(item)
    runtime = runtime_for(repository, settings, StaticBackend(item))

    class InvalidAfterReceipt(FakeGenerationProvider):
        def summary(self, context, options, call_id, record_receipt):
            record_receipt(provider_receipt(call_id, self.settings.model_fast))
            raise InvalidProviderOutputError("synthetic invalid JSON")

    runtime.provider = InvalidAfterReceipt(settings)
    assert runtime.dispatch_once() == 1
    assert runtime.consume_once(block_ms=1) == 1

    job = repository.get_job(item.jobId)
    assert job.status == JobStatus.NEEDS_REVIEW
    assert job.errorCode == "MODEL_OUTPUT_INVALID"
    assert job.result is None
    assert job.costMicrousd is not None
    with repository.database.connection() as connection:
        assert connection.execute(
            "select status from ai_cost_ledger where job_id = %s", (item.jobId,)
        ).fetchone() == {"status": "SETTLED"}


@pytest.mark.integration
def test_provider_observation_runs_after_receipt_and_settlement_commit(
    repository: Repository, settings: Settings
) -> None:
    item = envelope(Feature.SUMMARY)
    repository.accept_job(item)

    class PersistedReceiptClient:
        provider_observations = 0

        def start_observation(self, **kwargs):
            if kwargs["name"] == "deskseed-ai-generation":
                self.provider_observations += 1
                call_id = kwargs["metadata"]["observationId"]
                with repository.database.connection() as connection:
                    row = connection.execute(
                        """
                        select lifecycle_status, settlement_status, usage_status
                        from ai_provider_calls where observation_id = %s
                        """,
                        (call_id,),
                    ).fetchone()
                assert row == {
                    "lifecycle_status": "RESPONDED",
                    "settlement_status": "SETTLED",
                    "usage_status": "KNOWN",
                }
            return SimpleNamespace(update=lambda **_kwargs: None, end=lambda: None)

    telemetry_client = PersistedReceiptClient()
    traces = TraceAdapter(settings, telemetry_client)
    runtime = runtime_for(repository, settings, StaticBackend(item), traces)

    assert runtime.dispatch_once() == 1
    assert runtime.consume_once(block_ms=1) == 1
    assert telemetry_client.provider_observations == 1
    assert repository.get_job(item.jobId).status == JobStatus.SUCCEEDED


@pytest.mark.integration
def test_provider_observation_failure_is_durable_and_does_not_fail_job(
    repository: Repository, settings: Settings
) -> None:
    item = envelope(Feature.SUMMARY)
    repository.accept_job(item)

    class ProviderObservationFailureClient:
        def start_observation(self, **kwargs):
            if kwargs["name"] == "deskseed-ai-generation":
                raise RuntimeError("synthetic telemetry outage")
            return SimpleNamespace(update=lambda **_kwargs: None, end=lambda: None)

    traces = TraceAdapter(
        settings,
        ProviderObservationFailureClient(),
        repository.increment_telemetry_counter,
    )
    runtime = runtime_for(repository, settings, StaticBackend(item), traces)

    assert runtime.dispatch_once() == 1
    assert runtime.consume_once(block_ms=1) == 1
    assert repository.get_job(item.jobId).status == JobStatus.SUCCEEDED
    telemetry = repository.telemetry_status(enabled=True)
    assert telemetry["dropped"]["providerObservation"] == 1


@pytest.mark.integration
def test_receipt_persistence_failure_marks_call_unknown_and_blocks_result(
    repository: Repository, settings: Settings, monkeypatch
) -> None:
    item = envelope(Feature.SUMMARY)
    repository.accept_job(item)
    claim = repository.claim_job(item.jobId, 1, settings.consumer_name)
    assert claim is not None
    runtime = runtime_for(repository, settings, StaticBackend(item))

    def fail_receipt(*_args, **_kwargs):
        raise RuntimeError("synthetic receipt persistence failure")

    monkeypatch.setattr(repository, "record_provider_response", fail_receipt)
    with pytest.raises(RuntimeError, match="receipt persistence"):
        runtime._execute(claim, None)

    job = repository.get_job(item.jobId)
    assert job.status == JobStatus.FAILED
    assert job.errorCode == "PROVIDER_OUTCOME_UNKNOWN"
    with repository.database.connection() as connection:
        assert connection.execute(
            """
            select call.lifecycle_status, call.settlement_status, cost.status
            from ai_provider_calls call
            join ai_cost_ledger cost on cost.reservation_id = call.reservation_id
            where call.job_id = %s
            """,
            (item.jobId,),
        ).fetchone() == {
            "lifecycle_status": "UNKNOWN",
            "settlement_status": "UNKNOWN",
            "status": "UNKNOWN",
        }


@pytest.mark.integration
def test_generation_and_lease_fence_stale_work(repository: Repository) -> None:
    item = envelope()
    repository.accept_job(item)
    claim = repository.claim_job(item.jobId, 1, "worker-a")
    assert claim is not None and claim.lease_epoch == 1
    with pytest.raises(ActiveLeaseError):
        repository.claim_job(item.jobId, 1, "worker-b")
    repository.fail_job(claim, "TRANSIENT", retryable=True)
    stale = repository.claim_job(item.jobId, 1, "worker-a")
    assert stale is None
    retry = repository.claim_job(item.jobId, 2, "worker-b")
    assert retry is not None
    assert retry.generation == 2
    assert retry.lease_epoch == 2


@pytest.mark.integration
def test_active_lease_message_remains_pending_until_owner_can_finish(
    repository: Repository, settings: Settings
) -> None:
    item = envelope()
    repository.accept_job(item)
    runtime = runtime_for(repository, settings, StaticBackend(item))
    assert runtime.dispatch_once() == 1
    runtime.ensure_group()
    assert runtime.redis.xreadgroup(
        settings.stream_group,
        "crashed-worker",
        {settings.stream_name: ">"},
        count=1,
        block=1,
    )
    claim = repository.claim_job(item.jobId, 1, "crashed-worker")
    assert claim is not None

    assert runtime.consume_once(block_ms=1, min_idle_ms=0) == 0
    assert runtime.redis.xpending(settings.stream_name, settings.stream_group)["pending"] == 1
    assert repository.get_job(item.jobId).status == JobStatus.RUNNING


@pytest.mark.integration
@pytest.mark.parametrize("retryable", [False, True])
def test_late_failure_cannot_overwrite_cancellation_or_create_retry(
    repository: Repository, retryable: bool
) -> None:
    item = envelope()
    repository.accept_job(item)
    claim = repository.claim_job(item.jobId, 1, "worker")
    assert claim is not None
    repository.cancel(
        CancellationEnvelope(
            schemaVersion=1,
            eventId=uuid4(),
            jobId=item.jobId,
            workspaceKey=item.workspaceKey,
            requestRevision=2,
            createdAt=datetime.now(UTC),
        )
    )

    with pytest.raises(StaleLeaseError):
        repository.fail_job(claim, "LATE_FAILURE", retryable=retryable)

    assert repository.get_job(item.jobId).status == JobStatus.CANCELLED
    with repository.database.connection() as connection:
        generations = connection.execute(
            "select generation from ai_dispatch_outbox where job_id = %s order by generation",
            (item.jobId,),
        ).fetchall()
    assert generations == [{"generation": 1}]


@pytest.mark.integration
def test_stale_worker_cannot_create_a_future_generation_outbox(repository: Repository) -> None:
    item = envelope()
    repository.accept_job(item)
    stale = repository.claim_job(item.jobId, 1, "worker-a")
    assert stale is not None
    with repository.database.transaction() as connection:
        connection.execute(
            "update ai_jobs set lease_expires_at = clock_timestamp() - interval '1 second' where job_id = %s",
            (item.jobId,),
        )
    current = repository.claim_job(item.jobId, 1, "worker-b")
    assert current is not None

    with pytest.raises(StaleLeaseError):
        repository.fail_job(stale, "TRANSIENT", retryable=True)

    with repository.database.connection() as connection:
        job = connection.execute(
            "select generation, status from ai_jobs where job_id = %s", (item.jobId,)
        ).fetchone()
        generations = connection.execute(
            "select generation from ai_dispatch_outbox where job_id = %s order by generation",
            (item.jobId,),
        ).fetchall()
    assert job == {"generation": 1, "status": "RUNNING"}
    assert generations == [{"generation": 1}]


@pytest.mark.integration
def test_existing_provider_reservation_blocks_automatic_recall(repository: Repository) -> None:
    item = envelope()
    repository.accept_job(item)
    claim = repository.claim_job(item.jobId, 1, "worker")
    assert claim is not None
    reservation = repository.reserve_budget(
        claim,
        "openai/gpt-5.6-luna",
        "pricing-v1",
        100,
        "GENERATION",
    )

    with pytest.raises(ProviderCallStateUnknownError):
        repository.reserve_budget(
            claim,
            "openai/gpt-5.6-luna",
            "pricing-v1",
            100,
            "GENERATION",
        )

    with repository.database.connection() as connection:
        ledger = connection.execute(
            "select reservation_id, status from ai_cost_ledger where job_id = %s",
            (item.jobId,),
        ).fetchone()
    assert ledger == {"reservation_id": reservation, "status": "UNKNOWN"}


@pytest.mark.integration
def test_provider_receipt_settles_overrun_idempotently_and_flags_conflict(
    repository: Repository,
) -> None:
    item = envelope()
    repository.accept_job(item)
    claim = repository.claim_job(item.jobId, 1, "worker")
    assert claim is not None
    reservation = repository.reserve_budget(
        claim, "openai/gpt-5.6-luna", "pricing-v2", 10, "GENERATION"
    )
    call_id = uuid4()
    repository.create_provider_call(
        reservation,
        call_id,
        "openai/gpt-5.6-luna",
        "pricing-v2",
        "standard",
        "short",
    )
    repository.mark_provider_call_dispatching(call_id)
    receipt = provider_receipt(call_id)

    assert repository.record_provider_response(receipt, 25) == 25
    assert repository.record_provider_response(receipt, 25) == 25
    with pytest.raises(ProviderReceiptConflictError):
        repository.record_provider_response(
            provider_receipt(call_id, request_id="different-provider-response"), 25
        )

    with repository.database.connection() as connection:
        ledger = connection.execute(
            """
            select status, settled_microusd, overrun_microusd
            from ai_cost_ledger where reservation_id = %s
            """,
            (reservation,),
        ).fetchone()
        provider_call = connection.execute(
            """
            select lifecycle_status, settlement_status, known_cost_microusd, overrun_microusd
            from ai_provider_calls where call_id = %s
            """,
            (call_id,),
        ).fetchone()
    assert ledger == {"status": "SETTLED", "settled_microusd": 25, "overrun_microusd": 15}
    assert provider_call == {
        "lifecycle_status": "RESPONDED",
        "settlement_status": "CONFLICT",
        "known_cost_microusd": 25,
        "overrun_microusd": 15,
    }


@pytest.mark.integration
def test_unavailable_usage_is_persisted_as_unknown_not_zero(repository: Repository) -> None:
    item = envelope()
    repository.accept_job(item)
    claim = repository.claim_job(item.jobId, 1, "worker")
    assert claim is not None
    reservation = repository.reserve_budget(
        claim, "openai/gpt-5.6-luna", "pricing-v2", 100, "GENERATION"
    )
    call_id = uuid4()
    repository.create_provider_call(
        reservation,
        call_id,
        "openai/gpt-5.6-luna",
        "pricing-v2",
        "standard",
        "short",
    )
    repository.mark_provider_call_dispatching(call_id)
    receipt = provider_receipt(call_id)
    unavailable = receipt.__class__(
        **{
            **receipt.__dict__,
            "usage_status": UsageStatus.UNAVAILABLE,
            "usage": None,
            "usage_issue_code": "USAGE_MISSING",
        }
    )

    assert repository.record_provider_response(unavailable, 0) is None
    assert repository.job_cost_microusd(item.jobId) is None
    with repository.database.connection() as connection:
        assert connection.execute(
            """
            select call.lifecycle_status, call.settlement_status, call.known_cost_microusd,
                   cost.status, cost.settled_microusd
            from ai_provider_calls call
            join ai_cost_ledger cost on cost.reservation_id = call.reservation_id
            where call.call_id = %s
            """,
            (call_id,),
        ).fetchone() == {
            "lifecycle_status": "RESPONDED",
            "settlement_status": "UNKNOWN",
            "known_cost_microusd": None,
            "status": "UNKNOWN",
            "settled_microusd": None,
        }


@pytest.mark.integration
def test_known_provider_response_settles_after_job_cancellation(repository: Repository) -> None:
    item = envelope()
    repository.accept_job(item)
    claim = repository.claim_job(item.jobId, 1, "worker")
    assert claim is not None
    reservation = repository.reserve_budget(
        claim, "openai/gpt-5.6-luna", "pricing-v2", 100, "GENERATION"
    )
    call_id = uuid4()
    repository.create_provider_call(
        reservation,
        call_id,
        "openai/gpt-5.6-luna",
        "pricing-v2",
        "standard",
        "short",
    )
    repository.mark_provider_call_dispatching(call_id)
    repository.cancel(
        CancellationEnvelope(
            schemaVersion=1,
            eventId=uuid4(),
            jobId=item.jobId,
            workspaceKey=item.workspaceKey,
            requestRevision=2,
            createdAt=datetime.now(UTC),
        )
    )

    repository.record_provider_response(provider_receipt(call_id), 12)

    assert repository.get_job(item.jobId).status == JobStatus.CANCELLED
    with repository.database.connection() as connection:
        assert connection.execute(
            "select status, settled_microusd from ai_cost_ledger where reservation_id = %s",
            (reservation,),
        ).fetchone() == {"status": "SETTLED", "settled_microusd": 12}


@pytest.mark.integration
def test_prior_day_unknown_reservation_remains_admission_debt(repository: Repository) -> None:
    first = envelope()
    repository.accept_job(first)
    first_claim = repository.claim_job(first.jobId, 1, "worker-a")
    assert first_claim is not None
    reservation = repository.reserve_budget(
        first_claim, "openai/gpt-5.6-luna", "pricing-v2", 100, "GENERATION"
    )
    repository.mark_budget_unknown(reservation)
    with repository.database.transaction() as connection:
        connection.execute(
            "update ai_cost_ledger set budget_date = current_date - 1 where reservation_id = %s",
            (reservation,),
        )
    repository.settings = repository.settings.model_copy(
        update={"workspace_daily_budget_microusd": 150}
    )
    second = envelope()
    repository.accept_job(second)
    second_claim = repository.claim_job(second.jobId, 1, "worker-b")
    assert second_claim is not None

    with pytest.raises(BudgetExceededError):
        repository.reserve_budget(
            second_claim, "openai/gpt-5.6-luna", "pricing-v2", 51, "GENERATION"
        )


@pytest.mark.integration
def test_twenty_concurrent_reservations_cannot_overspend_workspace(repository: Repository) -> None:
    repository.settings = repository.settings.model_copy(
        update={
            "workspace_daily_budget_microusd": 1000,
            "actor_daily_budget_microusd": 1000,
            "job_budget_microusd": 1000,
        }
    )
    claims = []
    for index in range(20):
        item = envelope()
        repository.accept_job(item)
        claim = repository.claim_job(item.jobId, 1, f"worker-{index}")
        assert claim is not None
        claims.append(claim)

    def reserve(index):
        try:
            repository.reserve_budget(
                claims[index], "openai/gpt-5.6-luna", "pricing-v2", 100, "GENERATION"
            )
            return True
        except BudgetExceededError:
            return False

    with ThreadPoolExecutor(max_workers=20) as executor:
        accepted = list(executor.map(reserve, range(20)))

    assert accepted.count(True) == 10
    assert accepted.count(False) == 10
    with repository.database.connection() as connection:
        total = connection.execute(
            "select sum(reserved_microusd) as total from ai_cost_ledger"
        ).fetchone()["total"]
    assert total == 1000


@pytest.mark.integration
def test_cancel_before_create_keeps_a_tombstone_and_never_dispatches(repository: Repository) -> None:
    item = envelope()
    cancellation = CancellationEnvelope(
        schemaVersion=1,
        eventId=uuid4(),
        jobId=item.jobId,
        workspaceKey=item.workspaceKey,
        requestRevision=2,
        createdAt=datetime.now(UTC),
    )
    assert repository.cancel(cancellation).replayed is False
    repository.accept_job(item)
    receipt = repository.get_job(item.jobId)
    assert receipt.status == JobStatus.CANCELLED
    assert receipt.cancelRequested is True
    assert repository.claim_dispatch("worker", 10) == []


@pytest.mark.integration
def test_feedback_is_monotonic_and_exact_idempotent(repository: Repository, settings: Settings) -> None:
    item = envelope()
    repository.accept_job(item)
    runtime = runtime_for(repository, settings, StaticBackend(item))
    assert runtime.dispatch_once() == 1
    assert runtime.consume_once(block_ms=1) == 1
    feedback = FeedbackRequest(
        schemaVersion=1,
        eventId=uuid4(),
        jobId=item.jobId,
        workspaceKey=item.workspaceKey,
        requesterId=item.requesterId,
        feedbackType="helpful",
        reasonCode="accurate",
        sourceRevision=1,
        requestRevision=2,
        createdAt=datetime.now(UTC),
    )
    assert repository.accept_feedback(feedback).replayed is False
    assert repository.accept_feedback(feedback).replayed is True
    with pytest.raises(ConflictError):
        repository.accept_feedback(feedback.model_copy(update={"reasonCode": "other"}))


@pytest.mark.integration
def test_worker_xautoclaim_recovers_a_pending_message(repository: Repository, settings: Settings) -> None:
    item = envelope()
    repository.accept_job(item)
    runtime = runtime_for(repository, settings, StaticBackend(item))
    assert runtime.dispatch_once() == 1
    runtime.ensure_group()
    claimed = runtime.redis.xreadgroup(
        settings.stream_group,
        "crashed-worker",
        {settings.stream_name: ">"},
        count=1,
        block=1,
    )
    assert claimed
    assert runtime.recover_once(min_idle_ms=0) == 0
    assert repository.get_job(item.jobId).status == JobStatus.QUEUED
    assert runtime.consume_once(block_ms=1, min_idle_ms=0) == 1
    assert repository.get_job(item.jobId).status == JobStatus.SUCCEEDED


@pytest.mark.integration
def test_durable_outbox_republishes_after_redis_stream_loss(repository: Repository, settings: Settings) -> None:
    item = envelope()
    repository.accept_job(item)
    runtime = runtime_for(repository, settings, StaticBackend(item))
    assert runtime.dispatch_once() == 1
    runtime.redis.delete(settings.stream_name)
    with repository.database.transaction() as connection:
        connection.execute(
            "update ai_dispatch_outbox set delivered_at = clock_timestamp() - interval '1 hour' where job_id = %s",
            (item.jobId,),
        )
    runtime.recover_once(min_idle_ms=0)
    assert runtime.consume_once(block_ms=1) == 1
    assert repository.get_job(item.jobId).status == JobStatus.SUCCEEDED


@pytest.mark.integration
def test_result_and_metadata_retention_preserve_dedupe_and_settled_cost(
    repository: Repository, settings: Settings
) -> None:
    item = envelope()
    repository.accept_job(item)
    runtime = runtime_for(repository, settings, StaticBackend(item))
    assert runtime.dispatch_once() == 1
    assert runtime.consume_once(block_ms=1) == 1
    with repository.database.transaction() as connection:
        connection.execute(
            "update ai_jobs set result_expires_at = clock_timestamp() - interval '1 second' where job_id = %s",
            (item.jobId,),
        )
    assert repository.purge_expired_results() == 1
    assert repository.get_job(item.jobId).result is None
    with repository.database.transaction() as connection:
        connection.execute(
            "update ai_jobs set completed_at = clock_timestamp() - interval '31 days' where job_id = %s",
            (item.jobId,),
        )
    assert repository.purge_expired_metadata() == 1
    with pytest.raises(NotFoundError):
        repository.get_job(item.jobId)
    assert repository.accept_job(item).replayed is True
    with repository.database.connection() as connection:
        assert connection.execute(
            "select count(*) as count from ai_cost_ledger where job_id = %s and status = 'SETTLED'",
            (item.jobId,),
        ).fetchone()["count"] == 1


@pytest.mark.integration
def test_public_kb_revision_replacement_and_vector_retrieval(repository: Repository) -> None:
    knowledge = KnowledgeRepository(repository.database, FakeEmbeddingProvider())
    article_id = uuid4()
    first_revision = uuid4()
    chunks = chunk_public_article("환불은 결제 후 7일 이내 요청할 수 있습니다.\n개인정보를 포함하지 마세요.")
    first_event = IndexEvent(
        schemaVersion=1,
        eventId=uuid4(),
        workspaceKey="default",
        articleId=article_id,
        revisionId=first_revision,
        action="UPSERT",
        sourceVersion=1,
        publicRevision="c" * 64,
        createdAt=datetime.now(UTC),
    )
    repository.accept_index_event(first_event)
    token_count, applied = knowledge.replace_public_revision(
        "default", article_id, first_revision, 1, first_event.eventId,
        "refund-policy", "환불 정책", "c" * 64, chunks
    )
    assert token_count > 0 and applied
    matches = knowledge.retrieve("default", "환불 요청 기간", limit=3)
    assert matches and matches[0].revision_id == first_revision

    second_revision = uuid4()
    second_event = first_event.model_copy(
        update={
            "eventId": uuid4(),
            "revisionId": second_revision,
            "sourceVersion": 2,
            "publicRevision": "d" * 64,
        }
    )
    repository.accept_index_event(second_event)
    knowledge.replace_public_revision(
        "default", article_id, second_revision, 2, second_event.eventId,
        "refund-policy", "환불 정책", "d" * 64,
        chunk_public_article("환불은 결제 후 14일 이내 요청할 수 있습니다."),
    )
    matches = knowledge.retrieve("default", "환불 요청 기간", limit=3)
    assert matches and all(item.revision_id == second_revision for item in matches)


@pytest.mark.integration
def test_index_event_fetches_exact_public_revision_and_settles_system_budget(
    repository: Repository, settings: Settings
) -> None:
    article_id = uuid4()
    revision_id = uuid4()
    event = IndexEvent(
        schemaVersion=1,
        eventId=uuid4(),
        workspaceKey="default",
        articleId=article_id,
        revisionId=revision_id,
        action="UPSERT",
        sourceVersion=1,
        publicRevision="e" * 64,
        createdAt=datetime.now(UTC),
    )

    class PublicArticleBackend:
        def read_public_article(self, requested_article, requested_revision, request_ref):
            assert (requested_article, requested_revision, request_ref) == (article_id, revision_id, event.eventId)
            return PublicKnowledgeArticle(
                articleId=article_id,
                revisionId=revision_id,
                slug="public-refund",
                title="공개 환불 도움말",
                body="공개 도움말 본문입니다.",
                sourceVersion=1,
                publicRevision="e" * 64,
                publishedAt=datetime.now(UTC),
                dataClass="PUBLIC_KB_ONLY",
            )

    service = IndexingService(
        PublicArticleBackend(),
        KnowledgeRepository(repository.database, FakeEmbeddingProvider()),
        repository,
        settings,
        Path(__file__).resolve().parents[1] / "config" / "pricing-v2.json",
        TraceAdapter(settings),
    )
    assert repository.accept_index_event(event).replayed is False
    assert service.process_once() == 1
    with repository.database.connection() as connection:
        row = connection.execute(
            """
            select cost.reservation_id, cost.budget_bucket, cost.call_type, cost.status,
                   provider.call_id, provider.trace_id, provider.observation_id
            from ai_cost_ledger cost
            join ai_provider_calls provider on provider.reservation_id = cost.reservation_id
            where cost.operation_key = %s
            """,
            (f"index:{event.eventId}:0",),
        ).fetchone()
    assert row["budget_bucket"] == "SYSTEM"
    assert row["call_type"] == "INDEX_EMBEDDING"
    assert row["status"] == "SETTLED"
    assert row["trace_id"] == row["reservation_id"].hex
    assert row["observation_id"] == row["call_id"].hex


@pytest.mark.integration
def test_delete_accepted_during_embedding_cannot_be_reversed(
    repository: Repository, settings: Settings
) -> None:
    article_id = uuid4()
    revision_id = uuid4()
    upsert = IndexEvent(
        schemaVersion=1,
        eventId=uuid4(),
        workspaceKey="default",
        articleId=article_id,
        revisionId=revision_id,
        action="UPSERT",
        sourceVersion=1,
        publicRevision="a" * 64,
        createdAt=datetime.now(UTC),
    )
    deleted = upsert.model_copy(
        update={"eventId": uuid4(), "action": "DELETE", "sourceVersion": 2}
    )
    repository.accept_index_event(upsert)

    class DeleteDuringEmbedding(FakeEmbeddingProvider):
        accepted = False

        def embed(self, text, call_id, record_receipt):
            if not self.accepted:
                self.accepted = True
                repository.accept_index_event(deleted)
            return super().embed(text, call_id, record_receipt)

    class PublicArticleBackend:
        def read_public_article(self, requested_article, requested_revision, request_ref):
            return PublicKnowledgeArticle(
                articleId=requested_article,
                revisionId=requested_revision,
                slug="withdrawn-article",
                title="철회 문서",
                body="철회되기 전 공개 본문",
                sourceVersion=1,
                publicRevision="a" * 64,
                publishedAt=datetime.now(UTC),
                dataClass="PUBLIC_KB_ONLY",
            )

    service = IndexingService(
        PublicArticleBackend(),
        KnowledgeRepository(repository.database, DeleteDuringEmbedding()),
        repository,
        settings,
        Path(__file__).resolve().parents[1] / "config" / "pricing-v2.json",
        TraceAdapter(settings),
    )
    assert service.process_once(limit=1) == 1
    with repository.database.connection() as connection:
        assert connection.execute(
            "select count(*) as count from ai_kb_revisions where article_id = %s and status = 'PUBLIC'",
            (article_id,),
        ).fetchone()["count"] == 0


@pytest.mark.integration
def test_reply_skips_generation_when_all_retrieved_candidates_are_withdrawn(
    repository: Repository, settings: Settings
) -> None:
    article_id = uuid4()
    revision_id = uuid4()
    index_event = IndexEvent(
        schemaVersion=1,
        eventId=uuid4(),
        workspaceKey="default",
        articleId=article_id,
        revisionId=revision_id,
        action="UPSERT",
        sourceVersion=1,
        publicRevision="a" * 64,
        createdAt=datetime.now(UTC),
    )
    repository.accept_index_event(index_event)
    knowledge = KnowledgeRepository(repository.database, FakeEmbeddingProvider())
    _, applied = knowledge.replace_public_revision(
        "default",
        article_id,
        revision_id,
        1,
        index_event.eventId,
        "withdrawn",
        "철회 문서",
        "a" * 64,
        ["WITHDRAWN_KB_SENTINEL"],
    )
    assert applied
    item = envelope(Feature.REPLY_DRAFT)
    repository.accept_job(item)

    class WithdrawnBackend(StaticBackend):
        def authorize_citations(self, job_id, citations):
            return []

    runtime = runtime_for(repository, settings, WithdrawnBackend(item))
    provider_called = False
    original_reply = runtime.provider.reply

    def reply(context, chunks, options, call_id, record_receipt):
        nonlocal provider_called
        provider_called = True
        return original_reply(context, chunks, options, call_id, record_receipt)

    runtime.provider.reply = reply
    claim = repository.claim_job(item.jobId, 1, settings.consumer_name)
    assert claim is not None
    runtime._execute(claim, None)

    assert provider_called is False
    job = repository.get_job(item.jobId)
    assert job.status == JobStatus.NEEDS_REVIEW
    assert job.errorCode == "NO_APPROVED_KNOWLEDGE"
    assert job.result is None
    assert job.canInsert is False
    with repository.database.connection() as connection:
        calls = connection.execute(
            """
            select stage, settlement_status from ai_provider_calls
            where job_id = %s order by created_at
            """,
            (item.jobId,),
        ).fetchall()
    assert calls == [{"stage": "QUERY_EMBEDDING", "settlement_status": "SETTLED"}]


@pytest.mark.integration
def test_reply_uses_only_partial_current_public_authorization_and_persists_source_map(
    repository: Repository, settings: Settings
) -> None:
    chunks = index_public_chunks(repository, 2)
    item = envelope(Feature.REPLY_DRAFT)
    repository.accept_job(item)

    class PartialBackend(StaticBackend):
        def authorize_citations(self, job_id, citations):
            assert job_id == self.item.jobId
            if len(citations) > 1:
                return [
                    citations[-1].model_copy(
                        update={"title": "Canonical 공개 도움말", "url": "/help/articles/canonical-help"}
                    )
                ]
            return citations

    runtime = runtime_for(repository, settings, PartialBackend(item))
    received_chunk_ids: list = []
    received_titles: list[str] = []
    original_reply = runtime.provider.reply

    def reply(context, approved, options, call_id, record_receipt):
        received_chunk_ids.extend(chunk.chunk_id for chunk in approved)
        received_titles.extend(chunk.title for chunk in approved)
        return original_reply(context, approved, options, call_id, record_receipt)

    runtime.provider.reply = reply
    assert runtime.dispatch_once() == 1
    assert runtime.consume_once(block_ms=1) == 1

    job = repository.get_job(item.jobId)
    assert job.status == JobStatus.SUCCEEDED
    assert job.result is not None
    assert len(job.result.citations) == 1
    assert received_chunk_ids == [job.result.citations[0].chunkId]
    assert received_titles == ["Canonical 공개 도움말"]
    assert job.result.citations[0].url == "/help/articles/canonical-help"
    assert received_chunk_ids[0] in {chunk.chunk_id for chunk in chunks}
    with repository.database.connection() as connection:
        row = connection.execute(
            "select source_map_digest, source_chunk_ids from ai_jobs where job_id = %s",
            (item.jobId,),
        ).fetchone()
        calls = connection.execute(
            "select stage, settlement_status from ai_provider_calls where job_id = %s order by created_at",
            (item.jobId,),
        ).fetchall()
    assert len(row["source_map_digest"]) == 64
    assert row["source_chunk_ids"] == received_chunk_ids
    assert calls == [
        {"stage": "QUERY_EMBEDDING", "settlement_status": "SETTLED"},
        {"stage": "GENERATION", "settlement_status": "SETTLED"},
    ]


@pytest.mark.integration
def test_reply_source_authorization_failure_is_not_reported_as_no_evidence(
    repository: Repository, settings: Settings
) -> None:
    item = envelope(Feature.REPLY_DRAFT)
    repository.accept_job(item)

    class UnavailableBackend(StaticBackend):
        def authorize_citations(self, job_id, citations):
            raise BackendAuthorizationError("synthetic 503")

    runtime = runtime_for(repository, settings, UnavailableBackend(item))
    assert runtime.dispatch_once() == 1
    assert runtime.consume_once(block_ms=1) == 1

    job = repository.get_job(item.jobId)
    assert job.status == JobStatus.FAILED
    assert job.errorCode == "SOURCE_AUTHORIZATION_FAILED"
    with repository.database.connection() as connection:
        calls = connection.execute(
            "select stage, settlement_status from ai_provider_calls where job_id = %s order by created_at",
            (item.jobId,),
        ).fetchall()
    assert calls == [{"stage": "QUERY_EMBEDDING", "settlement_status": "SETTLED"}]


@pytest.mark.integration
def test_reply_result_is_superseded_when_selected_source_is_withdrawn_after_generation(
    repository: Repository, settings: Settings
) -> None:
    index_public_chunks(repository, 1)
    item = envelope(Feature.REPLY_DRAFT)
    repository.accept_job(item)

    class WithdrawAfterGenerationBackend(StaticBackend):
        authorization_count = 0

        def authorize_citations(self, job_id, citations):
            self.authorization_count += 1
            return citations if self.authorization_count == 1 else []

    runtime = runtime_for(repository, settings, WithdrawAfterGenerationBackend(item))
    assert runtime.dispatch_once() == 1
    assert runtime.consume_once(block_ms=1) == 1

    job = repository.get_job(item.jobId)
    assert job.status == JobStatus.SUPERSEDED
    assert job.result is None
    with repository.database.connection() as connection:
        calls = connection.execute(
            "select stage, settlement_status from ai_provider_calls where job_id = %s order by created_at",
            (item.jobId,),
        ).fetchall()
        metadata = connection.execute(
            "select source_map_digest, source_chunk_ids from ai_jobs where job_id = %s",
            (item.jobId,),
        ).fetchone()
    assert calls == [
        {"stage": "QUERY_EMBEDDING", "settlement_status": "SETTLED"},
        {"stage": "GENERATION", "settlement_status": "SETTLED"},
    ]
    assert metadata == {"source_map_digest": None, "source_chunk_ids": None}


@pytest.mark.integration
@pytest.mark.parametrize("source_refs", [["S99"], ["S1", "S1"], []])
def test_reply_rejects_unknown_duplicate_or_empty_source_refs_after_known_generation_cost(
    repository: Repository, settings: Settings, source_refs: list[str]
) -> None:
    index_public_chunks(repository, 1)
    item = envelope(Feature.REPLY_DRAFT)
    repository.accept_job(item)
    runtime = runtime_for(repository, settings, StaticBackend(item))

    class InvalidRefsProvider(FakeGenerationProvider):
        def reply(self, context, knowledge, options, call_id, record_receipt):
            generated = super().reply(context, knowledge, options, call_id, record_receipt)
            return ProviderResult(
                ReplyProviderOutput.model_construct(answer="합성 답변", sourceRefs=source_refs),
                generated.receipt,
                generated.prompt_version,
            )

    invalid_provider = InvalidRefsProvider(settings)
    runtime.provider = invalid_provider
    runtime.reply_workflow.provider = invalid_provider
    assert runtime.dispatch_once() == 1
    assert runtime.consume_once(block_ms=1) == 1

    job = repository.get_job(item.jobId)
    assert job.status == JobStatus.NEEDS_REVIEW
    assert job.errorCode == "MODEL_OUTPUT_INVALID"
    assert job.result is None
    with repository.database.connection() as connection:
        calls = connection.execute(
            "select stage, settlement_status from ai_provider_calls where job_id = %s order by created_at",
            (item.jobId,),
        ).fetchall()
    assert calls == [
        {"stage": "QUERY_EMBEDDING", "settlement_status": "SETTLED"},
        {"stage": "GENERATION", "settlement_status": "SETTLED"},
    ]


@pytest.mark.integration
def test_manifest_reconciliation_deletes_absent_revisions_only_after_complete_scan(
    repository: Repository, settings: Settings
) -> None:
    knowledge = KnowledgeRepository(repository.database, FakeEmbeddingProvider())
    stale_article_id = uuid4()
    stale_revision_id = uuid4()
    stale_event = IndexEvent(
        schemaVersion=1,
        eventId=uuid4(),
        workspaceKey="default",
        articleId=stale_article_id,
        revisionId=stale_revision_id,
        action="UPSERT",
        sourceVersion=1,
        publicRevision="a" * 64,
        createdAt=datetime.now(UTC),
    )
    repository.accept_index_event(stale_event)
    knowledge.replace_public_revision(
        "default",
        stale_article_id,
        stale_revision_id,
        1,
        stale_event.eventId,
        "stale-article",
        "철회된 문서",
        "a" * 64,
        ["이 문서는 manifest에서 사라졌습니다."],
    )
    first_article_id, second_article_id = sorted([uuid4(), uuid4()], key=str)
    first_revision_id = uuid4()
    second_revision_id = uuid4()
    snapshot_token = uuid4()
    published_at = datetime.now(UTC)

    class PagedManifestBackend:
        def read_public_manifest(self, snapshot_token=None, cursor=None, limit=200):
            if snapshot_token is None:
                assert cursor is None
                return SimpleNamespace(
                    snapshotToken=snapshot_token_value,
                    expiresAt=published_at + timedelta(hours=1),
                    canonicalPublicCorpusRevision=7,
                    nextCursor=first_article_id,
                    items=[SimpleNamespace(
                        articleId=first_article_id,
                        revisionId=first_revision_id,
                        sourceVersion=1,
                        publicRevision="b" * 64,
                        publishedAt=published_at,
                    )],
                )
            assert snapshot_token == snapshot_token_value
            assert cursor == first_article_id
            return SimpleNamespace(
                snapshotToken=snapshot_token_value,
                expiresAt=published_at + timedelta(hours=1),
                canonicalPublicCorpusRevision=7,
                nextCursor=None,
                items=[SimpleNamespace(
                    articleId=second_article_id,
                    revisionId=second_revision_id,
                    sourceVersion=1,
                    publicRevision="c" * 64,
                    publishedAt=published_at,
                )],
            )

    snapshot_token_value = snapshot_token
    service = IndexingService(
        PagedManifestBackend(),
        knowledge,
        repository,
        settings,
        Path(__file__).resolve().parents[1] / "config" / "pricing-v2.json",
        TraceAdapter(settings),
    )

    assert service.reconcile_once() is True
    with repository.database.connection() as connection:
        assert connection.execute(
            "select status from ai_kb_revisions where article_id = %s and revision_id = %s",
            (stale_article_id, stale_revision_id),
        ).fetchone()["status"] == "PUBLIC"

    assert service.reconcile_once() is True
    with repository.database.connection() as connection:
        assert connection.execute(
            "select status from ai_kb_revisions where article_id = %s and revision_id = %s",
            (stale_article_id, stale_revision_id),
        ).fetchone()["status"] == "DELETED"
        run = connection.execute(
            "select status, page_count, item_count from ai_kb_reconciliation_runs where snapshot_token = %s",
            (snapshot_token,),
        ).fetchone()
        assert run == {"status": "INDEXING", "page_count": 2, "item_count": 2}
        assert connection.execute("select count(*) as count from ai_kb_index_jobs").fetchone()["count"] == 3
    assert repository.try_publish_index_generation("default") is False


class StaticBackend:
    def __init__(self, item: JobEnvelope):
        self.item = item

    def read_context(self, job_id, traceparent=None) -> SourceContext:
        assert job_id == self.item.jobId
        is_v2 = self.item.contextPolicyVersion == "public-comments-v2"
        return SourceContext(
            jobId=self.item.jobId,
            ticketId=self.item.ticketId,
            ticketNumber=self.item.ticketNumber,
            ticketVersion=0,
            feature=self.item.feature,
            requestRevision=1,
            contextRevision=self.item.contextRevision,
            contextPolicyVersion=self.item.contextPolicyVersion,
            aiInputRevision=self.item.aiInputRevision,
            inputPolicyVersion=self.item.inputPolicyVersion,
            inputScope="PUBLIC_ONLY",
            comments=[
                PublicComment(
                    id=self.item.ticketId,
                    sequence=1 if is_v2 else None,
                    authorRole="CUSTOMER" if is_v2 else None,
                    body="공개 결제 문의입니다.",
                    createdAt=datetime(2026, 1, 1, tzinfo=UTC),
                )
            ],
        )

    def read_policy(self, feature):
        return SimpleNamespace(
            enabled=True,
            features={feature: True},
            fastModelAlias="openai/gpt-5.6-luna",
            standardModelAlias="openai/gpt-5.6-terra",
            version=1,
            canonicalPublicCorpusRevision=7,
        )

    def read_context_revision(self, job_id):
        assert job_id == self.item.jobId
        return SimpleNamespace(
            contextRevision=self.item.contextRevision,
            aiInputRevision=self.item.aiInputRevision,
            inputPolicyVersion=self.item.inputPolicyVersion,
            authorized=True,
            cancelRequested=False,
            featureEnabled=True,
        )

    def authorize_citations(self, job_id, citations):
        assert job_id == self.item.jobId
        return citations


def index_public_chunks(repository: Repository, count: int) -> list:
    knowledge = KnowledgeRepository(repository.database, FakeEmbeddingProvider())
    indexed = []
    for ordinal in range(count):
        article_id = uuid4()
        revision_id = uuid4()
        event = IndexEvent(
            schemaVersion=1,
            eventId=uuid4(),
            workspaceKey="default",
            articleId=article_id,
            revisionId=revision_id,
            action="UPSERT",
            sourceVersion=1,
            publicRevision=f"{ordinal + 1:x}" * 64,
            createdAt=datetime.now(UTC),
        )
        repository.accept_index_event(event)
        _, applied = knowledge.replace_public_revision(
            "default",
            article_id,
            revision_id,
            1,
            event.eventId,
            f"public-help-{ordinal + 1}",
            f"공개 도움말 {ordinal + 1}",
            event.publicRevision,
            [f"공개 해결 절차 {ordinal + 1}"],
        )
        assert applied
    with repository.database.connection() as connection:
        indexed = connection.execute(
            "select chunk_id, article_id, revision_id, title, slug, content, 1.0 as score "
            "from ai_kb_chunks join ai_kb_revisions using (article_id, revision_id) "
            "where ai_kb_chunks.workspace_key = 'default' order by chunk_id"
        ).fetchall()
    return [SimpleNamespace(**row) for row in indexed]


def publish_current_index_generation(
    repository: Repository,
    canonical_corpus_revision: int,
) -> None:
    owner = "test-index-generation-publisher"
    events = repository.claim_index_events(owner, 100)
    for event in events:
        repository.mark_index_event_succeeded(event.eventId, owner)
    with repository.database.connection() as connection:
        items = connection.execute(
            """
            select revision.article_id, revision.revision_id, state.source_version,
                   revision.public_revision
            from ai_kb_revisions revision
            join ai_kb_article_state state
              on state.workspace_key = revision.workspace_key and state.article_id = revision.article_id
            where revision.workspace_key = 'default' and revision.status = 'PUBLIC'
            order by revision.article_id
            """
        ).fetchall()
    now = datetime.now(UTC)
    assert repository.begin_reconciliation(
        uuid4(),
        "default",
        uuid4(),
        now + timedelta(hours=1),
        canonical_corpus_revision,
    )
    run = repository.current_reconciliation("default")
    assert run is not None
    assert repository.record_reconciliation_page(
        run,
        [
            (
                row["article_id"],
                row["revision_id"],
                row["source_version"],
                row["public_revision"],
            )
            for row in items
        ],
        None,
    )
    assert not repository.begin_reconciliation(
        uuid4(),
        "default",
        uuid4(),
        now + timedelta(hours=1),
        canonical_corpus_revision,
    )
    assert repository.try_publish_index_generation("default") is True
    published = repository.current_published_index_generation("default")
    assert published is not None
    assert published.canonical_corpus_revision == canonical_corpus_revision


def runtime_for(
    repository: Repository,
    settings: Settings,
    backend: StaticBackend,
    traces: TraceAdapter | None = None,
) -> StreamRuntime:
    knowledge = KnowledgeRepository(repository.database, FakeEmbeddingProvider())
    return StreamRuntime(
        settings,
        repository,
        backend,
        FakeGenerationProvider(settings),
        knowledge,
        traces or TraceAdapter(settings),
        Path(__file__).resolve().parents[1] / "config" / "pricing-v2.json",
    )
