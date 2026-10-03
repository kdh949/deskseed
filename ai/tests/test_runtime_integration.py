from __future__ import annotations

import hashlib
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from deskseed_ai.backend_client import BackendSupersededError, PublicKnowledgeArticle
from deskseed_ai.config import Settings
from deskseed_ai.indexing import IndexingService
from deskseed_ai.main import app
from deskseed_ai.observability import TraceAdapter
from deskseed_ai.providers import FakeGenerationProvider
from deskseed_ai.queue import StreamRuntime
from deskseed_ai.repository import (
    ActiveLeaseError,
    ConflictError,
    NotFoundError,
    ProviderCallStateUnknownError,
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
            "select result_ciphertext, result_nonce, status from ai_jobs where job_id = %s", (item.jobId,)
        ).fetchone()
        ledger = connection.execute(
            "select status, settled_microusd from ai_cost_ledger where job_id = %s", (item.jobId,)
        ).fetchone()
    if feature == Feature.REPLY_DRAFT:
        assert row["result_ciphertext"] is None
        assert row["result_nonce"] is None
    else:
        assert bytes(row["result_ciphertext"]).find("ticket".encode()) == -1
        assert row["result_nonce"] is not None
    assert ledger["status"] == "SETTLED"


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
        Path(__file__).resolve().parents[1] / "config" / "pricing-v1.json",
    )
    assert repository.accept_index_event(event).replayed is False
    assert service.process_once() == 1
    with repository.database.connection() as connection:
        row = connection.execute(
            "select budget_bucket, call_type, status from ai_cost_ledger where operation_key = %s",
            (f"index:{event.eventId}",),
        ).fetchone()
    assert row == {"budget_bucket": "SYSTEM", "call_type": "INDEX_EMBEDDING", "status": "SETTLED"}


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

        def embed(self, text):
            if not self.accepted:
                self.accepted = True
                repository.accept_index_event(deleted)
            return super().embed(text)

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
        Path(__file__).resolve().parents[1] / "config" / "pricing-v1.json",
    )
    assert service.process_once(limit=1) == 1
    with repository.database.connection() as connection:
        assert connection.execute(
            "select count(*) as count from ai_kb_revisions where article_id = %s and status = 'PUBLIC'",
            (article_id,),
        ).fetchone()["count"] == 0


@pytest.mark.integration
def test_reply_candidates_are_reauthorized_before_provider_receives_content(
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
            raise BackendSupersededError("withdrawn")

    runtime = runtime_for(repository, settings, WithdrawnBackend(item))
    provider_called = False
    original_reply = runtime.provider.reply

    def reply(context, chunks):
        nonlocal provider_called
        provider_called = True
        return original_reply(context, chunks)

    runtime.provider.reply = reply
    claim = repository.claim_job(item.jobId, 1, settings.consumer_name)
    assert claim is not None
    runtime._execute(claim, None)

    assert provider_called is False
    assert repository.get_job(item.jobId).status == JobStatus.SUPERSEDED


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
        Path(__file__).resolve().parents[1] / "config" / "pricing-v1.json",
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
        assert run == {"status": "SUCCEEDED", "page_count": 2, "item_count": 2}
        assert connection.execute("select count(*) as count from ai_kb_index_jobs").fetchone()["count"] == 3


class StaticBackend:
    def __init__(self, item: JobEnvelope):
        self.item = item

    def read_context(self, job_id, traceparent=None) -> SourceContext:
        assert job_id == self.item.jobId
        return SourceContext(
            jobId=self.item.jobId,
            ticketId=self.item.ticketId,
            ticketNumber=self.item.ticketNumber,
            ticketVersion=0,
            feature=self.item.feature,
            requestRevision=1,
            contextRevision=self.item.contextRevision,
            contextPolicyVersion="public-comments-v1",
            inputScope="PUBLIC_ONLY",
            comments=[PublicComment(id=uuid4(), body="공개 결제 문의입니다.", createdAt=datetime.now(UTC))],
        )

    def read_policy(self, feature):
        return SimpleNamespace(
            enabled=True,
            features={feature: True},
            fastModelAlias="openai/gpt-5.6-luna",
            standardModelAlias="openai/gpt-5.6-terra",
        )

    def read_context_revision(self, job_id):
        assert job_id == self.item.jobId
        return SimpleNamespace(
            contextRevision=self.item.contextRevision,
            authorized=True,
            cancelRequested=False,
            featureEnabled=True,
        )

    def authorize_citations(self, job_id, citations):
        assert job_id == self.item.jobId
        return citations


def runtime_for(repository: Repository, settings: Settings, backend: StaticBackend) -> StreamRuntime:
    knowledge = KnowledgeRepository(repository.database, FakeEmbeddingProvider())
    return StreamRuntime(
        settings,
        repository,
        backend,
        FakeGenerationProvider(settings),
        knowledge,
        TraceAdapter(settings),
        Path(__file__).resolve().parents[1] / "config" / "pricing-v1.json",
    )
