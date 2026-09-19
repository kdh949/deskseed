from __future__ import annotations

import json
import re
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import httpx
from openai import OpenAI

from deskseed_ai.config import Settings
from deskseed_ai.embedding_batch import (
    BATCH_ENDPOINT,
    EmbeddingBatchService,
    FakeEmbeddingBatchAdapter,
    OpenAIEmbeddingBatchAdapter,
)
from deskseed_ai.indexing import IndexingService
from deskseed_ai.observability import TraceAdapter
from deskseed_ai.repository import Repository
from deskseed_ai.retrieval import (
    CHUNKER_VERSION,
    EMBEDDING_DIMENSION,
    INDEX_CONTRACT_VERSION,
    NORMALIZATION_VERSION,
    FakeEmbeddingProvider,
    KnowledgeRepository,
)
from deskseed_ai.schemas import IndexEvent

ROOT = Path(__file__).resolve().parents[1]


def test_embedding_batch_settings_fail_closed() -> None:
    defaults = Settings(environment="test")
    assert defaults.embedding_batch_mode == "off"

    test_settings = Settings(
        environment="test",
        embedding_optimization_mode="test",
        embedding_batch_mode="test",
    )
    assert test_settings.embedding_batch_max_requests == 512
    assert test_settings.embedding_batch_max_inputs == 2048
    assert test_settings.embedding_batch_max_bytes == 20_971_520

    for values in (
        {"embedding_batch_mode": "test"},
        {
            "environment": "production",
            "embedding_optimization_mode": "test",
            "embedding_batch_mode": "test",
        },
        {
            "embedding_optimization_mode": "intent",
            "embedding_batch_mode": "intent",
            "embedding_model_snapshot": "openai/text-embedding-3-small-2026-09-01",
        },
    ):
        try:
            Settings(**({"environment": "test"} | values))
        except ValueError:
            pass
        else:
            raise AssertionError("unsafe embedding batch settings were accepted")

    intent = Settings(
        environment="test",
        embedding_optimization_mode="intent",
        embedding_batch_mode="intent",
        embedding_model_snapshot="openai/text-embedding-3-small-2026-09-01",
        embedding_batch_data_controls_reviewed=True,
        embedding_batch_file_cleanup_enabled=True,
    )
    assert intent.embedding_batch_mode == "intent"


def test_openai_batch_adapter_uses_files_and_embedding_batch_contract() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        path = request.url.path
        if request.method == "POST" and path == "/v1/files":
            return httpx.Response(
                200,
                json={
                    "id": "file-input",
                    "object": "file",
                    "bytes": 10,
                    "created_at": 1,
                    "filename": "batch.jsonl",
                    "purpose": "batch",
                    "status": "processed",
                },
            )
        if request.method == "POST" and path == "/v1/batches":
            return httpx.Response(200, json=_batch_response("in_progress"))
        if request.method == "GET" and path == "/v1/batches/batch-test":
            return httpx.Response(200, json=_batch_response("completed", "file-output"))
        if request.method == "POST" and path == "/v1/batches/batch-test/cancel":
            return httpx.Response(200, json=_batch_response("cancelling"))
        if request.method == "GET" and path == "/v1/files/file-output/content":
            return httpx.Response(200, content=b'{"custom_id":"b-0000-deadbeefdeadbeef"}\n')
        if request.method == "DELETE" and path == "/v1/files/file-input":
            return httpx.Response(200, json={"id": "file-input", "object": "file", "deleted": True})
        raise AssertionError(f"unexpected SDK request: {request.method} {path}")

    client = OpenAI(
        api_key="test-key",
        base_url="https://provider.invalid/v1",
        http_client=httpx.Client(transport=httpx.MockTransport(handler)),
    )
    adapter = OpenAIEmbeddingBatchAdapter("unused", 30, client=client)
    assert adapter.upload_input(b'{"custom_id":"synthetic"}\n', "upload-key") == "file-input"
    submitted = adapter.submit("file-input", "submit-key")
    assert submitted.batch_id == "batch-test"
    assert submitted.status == "in_progress"
    completed = adapter.retrieve("batch-test")
    assert completed.status == "completed"
    assert completed.output_file_id == "file-output"
    assert adapter.cancel("batch-test").status == "cancelling"
    assert adapter.download_file("file-output").startswith(b'{"custom_id"')
    adapter.delete_file("file-input")

    batch_request = next(request for request in requests if request.url.path == "/v1/batches")
    batch_payload = json.loads(batch_request.content)
    assert batch_payload == {
        "completion_window": "24h",
        "endpoint": BATCH_ENDPOINT,
        "input_file_id": "file-input",
        "metadata": {"contract": "embedding-batch-v1"},
    }
    assert batch_request.headers["idempotency-key"] == "submit-key"
    upload_request = requests[0]
    assert upload_request.headers["idempotency-key"] == "upload-key"
    assert b'name="purpose"' in upload_request.content
    assert b"batch" in upload_request.content


def test_full_index_build_uses_durable_batch_then_reuses_artifacts(
    repository: Repository, settings: Settings
) -> None:
    batch_settings = settings.model_copy(
        update={
            "embedding_optimization_mode": "test",
            "embedding_batch_mode": "test",
        }
    )
    event = _begin_build(repository, "batch-index")
    backend = _StaticBatchBackend(event)
    knowledge = KnowledgeRepository(repository.database, FakeEmbeddingProvider())
    adapter = FakeEmbeddingBatchAdapter()
    batches = EmbeddingBatchService(
        backend,
        knowledge,
        repository,
        batch_settings,
        ROOT / "config" / "pricing-batch-v1.json",
        adapter,
    )
    indexer = IndexingService(
        backend,
        knowledge,
        repository,
        batch_settings,
        ROOT / "config" / "pricing-v2.json",
        TraceAdapter(batch_settings),
        batches,
    )

    assert indexer.process_once() == 0
    with repository.database.connection() as connection:
        migration = connection.execute(
            "select description from ai_schema_history where version = 18"
        ).fetchone()
        job = connection.execute(
            "select batch_job_id, status, request_count from ai_embedding_batch_jobs where event_id = %s",
            (event.eventId,),
        ).fetchone()
        custom_ids = [
            row["custom_id"]
            for row in connection.execute(
                "select custom_id from ai_embedding_batch_items where batch_job_id = %s order by ordinal",
                (job["batch_job_id"],),
            ).fetchall()
        ]
    assert migration["description"] == "018_offline_embedding_batches"
    assert job["status"] == "PREPARING"
    assert job["request_count"] == len(custom_ids) > 0
    assert len(custom_ids) == len(set(custom_ids))
    assert all(re.fullmatch(r"b-[0-9]{4}-[0-9a-f]{16}", value) for value in custom_ids)
    assert all(str(event.articleId) not in value and str(event.revisionId) not in value for value in custom_ids)

    for _ in range(8):
        batches.progress_once(limit=1)
    with repository.database.connection() as connection:
        batch = connection.execute(
            "select status, terminal_status from ai_embedding_batch_jobs where event_id = %s",
            (event.eventId,),
        ).fetchone()
        artifact_count = connection.execute("select count(*) as count from ai_embedding_artifacts").fetchone()["count"]
        settled = connection.execute(
            "select status, pricing_version from ai_cost_ledger where operation_key like 'batch-index:%'"
        ).fetchone()
        deleted_files = connection.execute(
            "select count(*) as count from ai_embedding_batch_files where cleanup_status = 'DELETED'"
        ).fetchone()["count"]
    assert batch["status"] == "COMPLETED"
    assert batch["terminal_status"] == "COMPLETED"
    assert artifact_count == len(custom_ids)
    assert settled["status"] == "SETTLED"
    assert settled["pricing_version"] == "2026-09-19-openai-batch-v1"
    assert deleted_files == 2
    assert adapter.upload_calls == 1
    assert adapter.submit_calls == 1

    assert indexer.process_once() == 1
    with repository.database.connection() as connection:
        chunk_count = connection.execute(
            "select count(*) as count from ai_kb_chunks where embedding_artifact_key is not null"
        ).fetchone()["count"]
        sync_calls = connection.execute(
            "select count(*) as count from ai_provider_calls where pricing_version <> %s",
            ("2026-09-19-openai-batch-v1",),
        ).fetchone()["count"]
    assert chunk_count == len(custom_ids)
    assert sync_calls == 0

    with repository.database.transaction() as connection:
        connection.execute(
            """
            update ai_embedding_batch_jobs
            set completed_at = clock_timestamp() - interval '31 days'
            where event_id = %s
            """,
            (event.eventId,),
        )
    assert repository.purge_expired_embedding_batches() == 1
    with repository.database.connection() as connection:
        assert connection.execute(
            "select count(*) as count from ai_embedding_artifacts"
        ).fetchone()["count"] == len(custom_ids)


def test_withdrawn_source_settles_late_batch_without_binding(
    repository: Repository, settings: Settings
) -> None:
    batch_settings = settings.model_copy(
        update={"embedding_optimization_mode": "test", "embedding_batch_mode": "test"}
    )
    event = _begin_build(repository, "batch-stale")
    backend = _StaticBatchBackend(event)
    knowledge = KnowledgeRepository(repository.database, FakeEmbeddingProvider())
    batches = EmbeddingBatchService(
        backend,
        knowledge,
        repository,
        batch_settings,
        ROOT / "config" / "pricing-batch-v1.json",
        FakeEmbeddingBatchAdapter(),
    )
    indexer = IndexingService(
        backend,
        knowledge,
        repository,
        batch_settings,
        ROOT / "config" / "pricing-v2.json",
        TraceAdapter(batch_settings),
        batches,
    )
    assert indexer.process_once() == 0

    delete = IndexEvent(
        schemaVersion=1,
        eventId=uuid4(),
        workspaceKey=event.workspaceKey,
        articleId=event.articleId,
        revisionId=event.revisionId,
        action="DELETE",
        sourceVersion=event.sourceVersion + 1,
        publicRevision=event.publicRevision,
        createdAt=datetime.now(UTC),
    )
    repository.accept_index_event(delete)
    for _ in range(8):
        batches.progress_once(limit=1)

    with repository.database.connection() as connection:
        batch = connection.execute(
            "select status from ai_embedding_batch_jobs where event_id = %s", (event.eventId,)
        ).fetchone()
        artifact_count = connection.execute("select count(*) as count from ai_embedding_artifacts").fetchone()["count"]
        old_job = connection.execute(
            "select status, last_error_code from ai_kb_index_jobs where event_id = %s", (event.eventId,)
        ).fetchone()
        cost = connection.execute(
            "select status from ai_cost_ledger where operation_key like 'batch-index:%'"
        ).fetchone()["status"]
    assert batch["status"] == "COMPLETED"
    assert artifact_count == 0
    assert old_job["status"] == "DEAD"
    assert old_job["last_error_code"] == "BATCH_SOURCE_STALE"
    assert cost == "SETTLED"


def test_partial_output_preserves_known_artifact_and_marks_cost_unknown(
    repository: Repository, settings: Settings
) -> None:
    batch_settings = settings.model_copy(
        update={"embedding_optimization_mode": "test", "embedding_batch_mode": "test"}
    )
    event = _begin_build(repository, "batch-partial")
    backend = _StaticBatchBackend(event, body_repetitions=100)
    knowledge = KnowledgeRepository(repository.database, FakeEmbeddingProvider())
    adapter = _PartialBatchAdapter()
    batches = EmbeddingBatchService(
        backend,
        knowledge,
        repository,
        batch_settings,
        ROOT / "config" / "pricing-batch-v1.json",
        adapter,
    )
    indexer = IndexingService(
        backend,
        knowledge,
        repository,
        batch_settings,
        ROOT / "config" / "pricing-v2.json",
        TraceAdapter(batch_settings),
        batches,
    )
    assert indexer.process_once() == 0
    for _ in range(8):
        batches.progress_once(limit=1)

    with repository.database.connection() as connection:
        counts = connection.execute(
            """
            select count(*) filter (where result_status = 'SUCCEEDED') as succeeded,
                   count(*) filter (where result_status = 'UNKNOWN') as unknown
            from ai_embedding_batch_items
            """
        ).fetchone()
        batch_status = connection.execute(
            "select status, terminal_status from ai_embedding_batch_jobs where event_id = %s",
            (event.eventId,),
        ).fetchone()
        artifacts = connection.execute("select count(*) as count from ai_embedding_artifacts").fetchone()["count"]
        cost = connection.execute(
            "select status from ai_cost_ledger where operation_key like 'batch-index:%'"
        ).fetchone()["status"]
    assert counts["succeeded"] == 1
    assert counts["unknown"] >= 1
    assert artifacts == 1
    assert cost == "UNKNOWN"
    assert batch_status["status"] == "FAILED"
    assert batch_status["terminal_status"] == "FAILED"


def test_batch_restart_and_cleanup_retry_converge_without_duplicate_submit(
    repository: Repository, settings: Settings
) -> None:
    batch_settings = settings.model_copy(
        update={"embedding_optimization_mode": "test", "embedding_batch_mode": "test"}
    )
    event = _begin_build(repository, "batch-restart")
    backend = _StaticBatchBackend(event)
    knowledge = KnowledgeRepository(repository.database, FakeEmbeddingProvider())
    adapter = _DeleteOnceFailsBatchAdapter()
    first = EmbeddingBatchService(
        backend,
        knowledge,
        repository,
        batch_settings,
        ROOT / "config" / "pricing-batch-v1.json",
        adapter,
    )
    indexer = IndexingService(
        backend,
        knowledge,
        repository,
        batch_settings,
        ROOT / "config" / "pricing-v2.json",
        TraceAdapter(batch_settings),
        first,
    )
    assert indexer.process_once() == 0
    assert first.progress_once(limit=1) == 1
    assert first.progress_once(limit=1) == 1
    assert adapter.upload_calls == 1

    restarted = EmbeddingBatchService(
        backend,
        knowledge,
        repository,
        batch_settings,
        ROOT / "config" / "pricing-batch-v1.json",
        adapter,
    )
    for _ in range(5):
        restarted.progress_once(limit=1)
    with repository.database.connection() as connection:
        pending = connection.execute(
            "select status from ai_embedding_batch_jobs where event_id = %s", (event.eventId,)
        ).fetchone()["status"]
    assert pending == "CLEANUP_PENDING"
    assert adapter.submit_calls == 1
    batch_status = repository.operational_status()["embeddingBatches"]
    assert batch_status["cleanupPendingCount"] == 1
    assert batch_status["oldestCleanupPendingAt"] is not None

    with repository.database.transaction() as connection:
        connection.execute(
            "update ai_embedding_batch_jobs set available_at = clock_timestamp() where event_id = %s",
            (event.eventId,),
        )
    assert restarted.progress_once(limit=1) == 1
    with repository.database.connection() as connection:
        final = connection.execute(
            "select status from ai_embedding_batch_jobs where event_id = %s", (event.eventId,)
        ).fetchone()["status"]
    assert final == "COMPLETED"
    assert adapter.upload_calls == 1
    assert adapter.submit_calls == 1


def test_cancelled_batch_reconciles_late_out_of_order_successes(
    repository: Repository, settings: Settings
) -> None:
    batch_settings = settings.model_copy(
        update={"embedding_optimization_mode": "test", "embedding_batch_mode": "test"}
    )
    event = _begin_build(repository, "batch-cancel")
    backend = _StaticBatchBackend(event, body_repetitions=100)
    knowledge = KnowledgeRepository(repository.database, FakeEmbeddingProvider())
    adapter = _ReverseBatchAdapter()
    batches = EmbeddingBatchService(
        backend,
        knowledge,
        repository,
        batch_settings,
        ROOT / "config" / "pricing-batch-v1.json",
        adapter,
    )
    indexer = IndexingService(
        backend,
        knowledge,
        repository,
        batch_settings,
        ROOT / "config" / "pricing-v2.json",
        TraceAdapter(batch_settings),
        batches,
    )
    assert indexer.process_once() == 0
    for _ in range(3):
        batches.progress_once(limit=1)
    with repository.database.connection() as connection:
        batch_job_id = connection.execute(
            "select batch_job_id from ai_embedding_batch_jobs where event_id = %s", (event.eventId,)
        ).fetchone()["batch_job_id"]
    batches.request_cancel(batch_job_id)
    for _ in range(4):
        batches.progress_once(limit=1)

    with repository.database.connection() as connection:
        batch = connection.execute(
            "select status, terminal_status from ai_embedding_batch_jobs where batch_job_id = %s",
            (batch_job_id,),
        ).fetchone()
        item_counts = connection.execute(
            """
            select count(*) as total,
                   count(*) filter (where result_status = 'SUCCEEDED') as succeeded
            from ai_embedding_batch_items where batch_job_id = %s
            """,
            (batch_job_id,),
        ).fetchone()
        cost = connection.execute(
            "select status from ai_cost_ledger where operation_key like 'batch-index:%'"
        ).fetchone()["status"]
    assert batch["status"] == "CANCELLED"
    assert batch["terminal_status"] == "CANCELLED"
    assert item_counts["total"] == item_counts["succeeded"] > 1
    assert cost == "SETTLED"

def _batch_response(status: str, output_file_id: str | None = None) -> dict[str, object]:
    return {
        "id": "batch-test",
        "object": "batch",
        "endpoint": BATCH_ENDPOINT,
        "errors": None,
        "input_file_id": "file-input",
        "completion_window": "24h",
        "status": status,
        "output_file_id": output_file_id,
        "error_file_id": None,
        "created_at": 1,
        "in_progress_at": 1,
        "expires_at": 2,
        "finalizing_at": None,
        "completed_at": 2 if status == "completed" else None,
        "failed_at": None,
        "expired_at": None,
        "cancelling_at": 1 if status == "cancelling" else None,
        "cancelled_at": None,
        "request_counts": {"total": 1, "completed": 1 if status == "completed" else 0, "failed": 0},
        "metadata": {"contract": "embedding-batch-v1"},
    }


class _StaticBatchBackend:
    def __init__(self, event: IndexEvent, body_repetitions: int = 20):
        self.event = event
        self.body_repetitions = body_repetitions

    def read_public_article(self, article_id, revision_id, request_ref):
        assert article_id == self.event.articleId
        assert revision_id == self.event.revisionId
        assert request_ref == self.event.eventId
        return SimpleNamespace(
            dataClass="PUBLIC_KB_ONLY",
            articleId=article_id,
            revisionId=revision_id,
            sourceVersion=self.event.sourceVersion,
            publicRevision=self.event.publicRevision,
            slug="batch-contract",
            title="Batch contract",
            categoryTitle="AI",
            sectionTitle="Indexing",
            body="Public synthetic knowledge for offline embedding batch validation. " * self.body_repetitions,
        )


class _PartialBatchAdapter(FakeEmbeddingBatchAdapter):
    def submit(self, input_file_id: str, idempotency_key: str):
        remote = super().submit(input_file_id, idempotency_key)
        assert remote.output_file_id is not None
        lines = self.files[remote.output_file_id].splitlines()
        assert len(lines) > 1
        self.files[remote.output_file_id] = lines[0] + b"\n"
        return remote


class _DeleteOnceFailsBatchAdapter(FakeEmbeddingBatchAdapter):
    def __init__(self):
        super().__init__()
        self.failed_once = False

    def delete_file(self, file_id: str) -> None:
        if not self.failed_once:
            self.failed_once = True
            raise RuntimeError("synthetic cleanup failure")
        super().delete_file(file_id)


class _ReverseBatchAdapter(FakeEmbeddingBatchAdapter):
    def submit(self, input_file_id: str, idempotency_key: str):
        remote = super().submit(input_file_id, idempotency_key)
        assert remote.output_file_id is not None
        lines = self.files[remote.output_file_id].splitlines()
        assert len(lines) > 1
        self.files[remote.output_file_id] = b"\n".join(reversed(lines)) + b"\n"
        return remote


def _begin_build(repository: Repository, workspace_key: str) -> IndexEvent:
    now = datetime.now(UTC)
    event = IndexEvent(
        schemaVersion=1,
        eventId=uuid4(),
        workspaceKey=workspace_key,
        articleId=uuid4(),
        revisionId=uuid4(),
        action="UPSERT",
        sourceVersion=1,
        publicRevision="a" * 64,
        createdAt=now,
    )
    assert repository.begin_reconciliation(
        uuid4(),
        workspace_key,
        uuid4(),
        now + timedelta(hours=1),
        1,
        INDEX_CONTRACT_VERSION,
        CHUNKER_VERSION,
        NORMALIZATION_VERSION,
        "openai/text-embedding-3-small",
        EMBEDDING_DIMENSION,
    )
    run = repository.current_reconciliation(workspace_key)
    assert run is not None
    repository.accept_reconciliation_index_event(run, event)
    assert repository.record_reconciliation_page(
        run,
        [(event.articleId, event.revisionId, event.sourceVersion, event.publicRevision)],
        None,
    )
    return event
