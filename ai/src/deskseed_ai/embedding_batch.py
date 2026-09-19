from __future__ import annotations

import hashlib
import io
import json
import math
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, Literal, Protocol, cast
from uuid import NAMESPACE_URL, UUID, uuid5

from .backend_client import BackendClient
from .call_receipts import ProviderCallReceipt, UsageStatus
from .config import Settings
from .pricing import PricingCatalog, Usage
from .repository import IndexWorkItem, ProviderCallStateUnknownError, Repository
from .retrieval import (
    EMBEDDING_DIMENSION,
    NORMALIZATION_VERSION,
    EmbeddingArtifactSpec,
    KnowledgeRepository,
    PreparedKnowledgeChunk,
    build_public_article_chunks,
    embedding_artifact_spec,
)

BATCH_CONTRACT_VERSION = "embedding-batch-v1"
BATCH_COMPLETION_WINDOW: Literal["24h"] = "24h"
BATCH_ENDPOINT: Literal["/v1/embeddings"] = "/v1/embeddings"
MAX_PROGRESS_ATTEMPTS = 20


class BatchPendingError(RuntimeError):
    pass


class BatchTerminalError(RuntimeError):
    pass


@dataclass(frozen=True)
class RemoteBatch:
    batch_id: str
    status: Literal["validating", "in_progress", "finalizing", "completed", "failed", "expired", "cancelling", "cancelled"]
    output_file_id: str | None = None
    error_file_id: str | None = None
    request_total: int | None = None
    request_completed: int | None = None
    request_failed: int | None = None


class EmbeddingBatchAdapter(Protocol):
    def upload_input(self, content: bytes, idempotency_key: str) -> str: ...

    def submit(self, input_file_id: str, idempotency_key: str) -> RemoteBatch: ...

    def retrieve(self, batch_id: str) -> RemoteBatch: ...

    def cancel(self, batch_id: str) -> RemoteBatch: ...

    def download_file(self, file_id: str) -> bytes: ...

    def delete_file(self, file_id: str) -> None: ...


class OpenAIEmbeddingBatchAdapter:
    def __init__(self, api_key: str, timeout_seconds: int, client: Any | None = None):
        if client is None:
            from openai import OpenAI

            client = OpenAI(api_key=api_key, timeout=min(45, timeout_seconds), max_retries=0)
        self.client = client

    def upload_input(self, content: bytes, idempotency_key: str) -> str:
        response = self.client.files.create(
            file=("deskseed-embedding-batch.jsonl", io.BytesIO(content), "application/jsonl"),
            purpose="batch",
            extra_headers={"Idempotency-Key": idempotency_key},
        )
        return _required_identifier(_value(response, "id"), "provider input file")

    def submit(self, input_file_id: str, idempotency_key: str) -> RemoteBatch:
        response = self.client.batches.create(
            input_file_id=input_file_id,
            endpoint=BATCH_ENDPOINT,
            completion_window=BATCH_COMPLETION_WINDOW,
            metadata={"contract": BATCH_CONTRACT_VERSION},
            extra_headers={"Idempotency-Key": idempotency_key},
        )
        return _remote_batch(response)

    def retrieve(self, batch_id: str) -> RemoteBatch:
        return _remote_batch(self.client.batches.retrieve(batch_id))

    def cancel(self, batch_id: str) -> RemoteBatch:
        return _remote_batch(self.client.batches.cancel(batch_id))

    def download_file(self, file_id: str) -> bytes:
        response = self.client.files.content(file_id)
        content = getattr(response, "content", None)
        if isinstance(content, bytes):
            return content
        read = getattr(response, "read", None)
        if callable(read):
            value = read()
            if isinstance(value, bytes):
                return value
        raise ValueError("provider file content is unavailable")

    def delete_file(self, file_id: str) -> None:
        response = self.client.files.delete(file_id)
        deleted = _value(response, "deleted")
        if deleted is not True:
            raise ValueError("provider file deletion was not acknowledged")


class FakeEmbeddingBatchAdapter:
    def __init__(self, dimension: int = EMBEDDING_DIMENSION):
        self.dimension = dimension
        self.files: dict[str, bytes] = {}
        self.batches: dict[str, RemoteBatch] = {}
        self.upload_calls = 0
        self.submit_calls = 0
        self.delete_calls = 0

    def upload_input(self, content: bytes, idempotency_key: str) -> str:
        self.upload_calls += 1
        file_id = f"file-{hashlib.sha256(idempotency_key.encode()).hexdigest()[:24]}"
        self.files.setdefault(file_id, content)
        return file_id

    def submit(self, input_file_id: str, idempotency_key: str) -> RemoteBatch:
        self.submit_calls += 1
        batch_id = f"batch-{hashlib.sha256(idempotency_key.encode()).hexdigest()[:24]}"
        existing = self.batches.get(batch_id)
        if existing is not None:
            return existing
        output_lines: list[bytes] = []
        for raw_line in self.files[input_file_id].splitlines():
            request = json.loads(raw_line)
            body = request["body"]
            text = body["input"]
            output_lines.append(
                _json_bytes(
                    {
                        "custom_id": request["custom_id"],
                        "response": {
                            "status_code": 200,
                            "request_id": f"fake-{request['custom_id']}",
                            "body": {
                                "data": [{"index": 0, "embedding": _fake_vector(text, self.dimension)}],
                                "model": body["model"],
                                "usage": {"prompt_tokens": max(1, len(text) // 4), "total_tokens": max(1, len(text) // 4)},
                            },
                        },
                        "error": None,
                    }
                )
            )
        output_id = f"file-out-{batch_id[6:]}"
        self.files[output_id] = b"\n".join(output_lines) + b"\n"
        result = RemoteBatch(
            batch_id,
            "completed",
            output_file_id=output_id,
            request_total=len(output_lines),
            request_completed=len(output_lines),
            request_failed=0,
        )
        self.batches[batch_id] = result
        return result

    def retrieve(self, batch_id: str) -> RemoteBatch:
        return self.batches[batch_id]

    def cancel(self, batch_id: str) -> RemoteBatch:
        current = self.batches[batch_id]
        cancelled = RemoteBatch(
            batch_id,
            "cancelled",
            current.output_file_id,
            current.error_file_id,
            current.request_total,
            current.request_completed,
            current.request_failed,
        )
        self.batches[batch_id] = cancelled
        return cancelled

    def download_file(self, file_id: str) -> bytes:
        return self.files[file_id]

    def delete_file(self, file_id: str) -> None:
        self.delete_calls += 1
        self.files.pop(file_id, None)


@dataclass(frozen=True)
class BatchItem:
    ordinal: int
    custom_id: str
    spec: EmbeddingArtifactSpec


@dataclass(frozen=True)
class ClaimedBatch:
    batch_job_id: UUID
    event_id: UUID
    workspace_key: str
    article_id: UUID
    revision_id: UUID
    source_version: int
    public_revision: str
    artifact_generation: int
    reconciliation_run_id: UUID
    status: str
    terminal_status: str | None
    model_alias: str
    model_snapshot: str
    pricing_version: str
    reservation_id: UUID | None
    provider_call_id: UUID | None
    provider_input_file_id: str | None
    provider_batch_id: str | None
    provider_output_file_id: str | None
    provider_error_file_id: str | None
    request_count: int
    provider_request_total: int | None
    provider_request_completed: int | None
    provider_request_failed: int | None
    cancel_requested: bool
    cancel_sent_at: datetime | None
    attempts: int


@dataclass(frozen=True)
class ParsedBatchResult:
    custom_id: str
    status: Literal["SUCCEEDED", "FAILED", "UNKNOWN"]
    actual_model: str | None
    input_tokens: int | None
    vector: list[float] | None
    error_code: str | None


class EmbeddingBatchStore:
    def __init__(self, repository: Repository, settings: Settings):
        self.repository = repository
        self.database = repository.database
        self.settings = settings

    def create_manifest(
        self,
        event: IndexWorkItem,
        items: list[BatchItem],
        input_tokens: int,
        input_bytes: int,
        manifest_digest: str,
        pricing_version: str,
    ) -> UUID:
        batch_job_id = uuid5(NAMESPACE_URL, f"deskseed:embedding-batch:{event.event_id}:{BATCH_CONTRACT_VERSION}")
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            connection.execute(
                """
                insert into ai_embedding_batch_jobs (
                    batch_job_id, event_id, workspace_key, article_id, revision_id,
                    source_version, public_revision, artifact_generation, reconciliation_run_id,
                    purpose, mode, contract_version, status, model_alias, model_snapshot,
                    embedding_dimension, normalization_version, pricing_version, completion_window,
                    manifest_digest, request_count, input_count, input_token_count, input_bytes,
                    available_at, created_at, updated_at
                ) values (%s, %s, %s, %s, %s, %s, %s, %s, %s,
                          'INDEX', %s, %s, 'PREPARING', %s, %s, %s, %s, %s, %s,
                          %s, %s, %s, %s, %s, %s, %s, %s)
                on conflict (event_id) do nothing
                """,
                (
                    batch_job_id,
                    event.event_id,
                    event.workspace_key,
                    event.article_id,
                    event.revision_id,
                    event.source_version,
                    event.public_revision,
                    event.artifact_generation,
                    event.reconciliation_run_id,
                    self.settings.embedding_batch_mode,
                    BATCH_CONTRACT_VERSION,
                    self.settings.embedding_model,
                    self.settings.resolved_embedding_model_snapshot,
                    EMBEDDING_DIMENSION,
                    NORMALIZATION_VERSION,
                    pricing_version,
                    BATCH_COMPLETION_WINDOW,
                    manifest_digest,
                    len(items),
                    len(items),
                    input_tokens,
                    input_bytes,
                    now,
                    now,
                    now,
                ),
            )
            row = cast(Any, connection.execute(
                "select batch_job_id, manifest_digest from ai_embedding_batch_jobs where event_id = %s for update",
                (event.event_id,),
            ).fetchone())
            if row is None or row["manifest_digest"] != manifest_digest:
                raise ValueError("embedding batch manifest conflicts with existing source")
            batch_job_id = row["batch_job_id"]
            for item in items:
                connection.execute(
                    """
                    insert into ai_embedding_batch_items (
                        batch_job_id, ordinal, custom_id, artifact_key, model_snapshot,
                        embedding_dimension, normalization_version, embedding_input_sha256
                    ) values (%s, %s, %s, %s, %s, %s, %s, %s)
                    on conflict do nothing
                    """,
                    (
                        batch_job_id,
                        item.ordinal,
                        item.custom_id,
                        item.spec.artifact_key,
                        item.spec.model_snapshot,
                        item.spec.dimension,
                        item.spec.normalization_version,
                        item.spec.input_sha256,
                    ),
                )
            count_row = cast(Any, connection.execute(
                "select count(*) as count from ai_embedding_batch_items where batch_job_id = %s",
                (batch_job_id,),
            ).fetchone())
            count = count_row["count"]
            if count != len(items):
                raise ValueError("embedding batch item manifest is incomplete")
        return cast(UUID, batch_job_id)

    def status_for_event(self, event_id: UUID) -> str | None:
        with self.database.connection() as connection:
            row = cast(Any, connection.execute(
                "select status from ai_embedding_batch_jobs where event_id = %s", (event_id,)
            ).fetchone())
        return None if row is None else str(row["status"])

    def claim_due(self, owner: str) -> ClaimedBatch | None:
        now = datetime.now(UTC)
        lease_until = now + timedelta(seconds=self.settings.lease_seconds)
        with self.database.transaction() as connection:
            row = cast(Any, connection.execute(
                """
                select * from ai_embedding_batch_jobs
                where status not in ('COMPLETED', 'CANCELLED', 'FAILED', 'EXPIRED')
                  and attempts < %s and available_at <= %s
                  and (lease_owner is null or lease_expires_at <= %s)
                order by available_at, created_at, batch_job_id
                for update skip locked limit 1
                """,
                (MAX_PROGRESS_ATTEMPTS, now, now),
            ).fetchone())
            if row is None:
                return None
            connection.execute(
                """
                update ai_embedding_batch_jobs set lease_owner = %s, lease_expires_at = %s,
                    attempts = attempts + 1, updated_at = %s where batch_job_id = %s
                """,
                (owner, lease_until, now, row["batch_job_id"]),
            )
        return _claimed_batch(row, int(row["attempts"]) + 1)

    def release(self, job_id: UUID, owner: str, error_code: str | None = None, delay_seconds: int = 1) -> None:
        with self.database.transaction() as connection:
            connection.execute(
                """
                update ai_embedding_batch_jobs set lease_owner = null, lease_expires_at = null,
                    available_at = clock_timestamp() + make_interval(secs => %s),
                    last_error_code = %s, updated_at = clock_timestamp()
                where batch_job_id = %s and lease_owner = %s
                """,
                (delay_seconds, error_code[:80] if error_code else None, job_id, owner),
            )

    def transition(self, job_id: UUID, owner: str, status: str, **values: object) -> None:
        allowed = {
            "reservation_id", "provider_call_id", "provider_input_file_id", "provider_batch_id",
            "provider_output_file_id", "provider_error_file_id", "submitted_at",
            "provider_terminal_at", "finalized_at", "terminal_status", "cancel_sent_at",
            "provider_request_total", "provider_request_completed", "provider_request_failed",
        }
        if set(values) - allowed:
            raise ValueError("unsupported embedding batch transition field")
        assignments = ["status = %s"]
        parameters: list[object] = [status]
        for name, value in values.items():
            assignments.append(f"{name} = %s")
            parameters.append(value)
        assignments.extend(
            [
                "lease_owner = null",
                "lease_expires_at = null",
                "available_at = clock_timestamp()",
                "last_error_code = null",
                "updated_at = clock_timestamp()",
            ]
        )
        parameters.extend([job_id, owner])
        with self.database.transaction() as connection:
            updated = connection.execute(
                f"update ai_embedding_batch_jobs set {', '.join(assignments)} "
                "where batch_job_id = %s and lease_owner = %s",
                tuple(parameters),
            ).rowcount
            if updated != 1:
                raise ValueError("embedding batch lease was lost")

    def items(self, batch_job_id: UUID) -> list[BatchItem]:
        with self.database.connection() as connection:
            rows = cast(list[Any], connection.execute(
                """
                select ordinal, custom_id, artifact_key, model_snapshot, embedding_dimension,
                       normalization_version, embedding_input_sha256
                from ai_embedding_batch_items where batch_job_id = %s order by ordinal
                """,
                (batch_job_id,),
            ).fetchall())
        return [
            BatchItem(
                int(row["ordinal"]),
                str(row["custom_id"]),
                EmbeddingArtifactSpec(
                    str(row["artifact_key"]),
                    str(row["model_snapshot"]),
                    int(row["embedding_dimension"]),
                    str(row["normalization_version"]),
                    str(row["embedding_input_sha256"]),
                ),
            )
            for row in rows
        ]

    def register_file(self, batch_job_id: UUID, role: str, file_id: str) -> None:
        with self.database.transaction() as connection:
            connection.execute(
                """
                insert into ai_embedding_batch_files (
                    batch_job_id, file_role, provider_file_id, cleanup_status, updated_at
                ) values (%s, %s, %s, 'PENDING_DELETE', clock_timestamp())
                on conflict (batch_job_id, file_role) do update set
                    provider_file_id = excluded.provider_file_id,
                    updated_at = excluded.updated_at
                where ai_embedding_batch_files.provider_file_id = excluded.provider_file_id
                """,
                (batch_job_id, role, file_id),
            )

    def record_results(self, batch_job_id: UUID, results: list[ParsedBatchResult]) -> None:
        with self.database.transaction() as connection:
            for result in results:
                connection.execute(
                    """
                    update ai_embedding_batch_items set result_status = %s, actual_model = %s,
                        input_tokens = %s, error_code = %s, finalized_at = clock_timestamp()
                    where batch_job_id = %s and custom_id = %s
                      and result_status in ('PENDING', %s)
                    """,
                    (
                        result.status,
                        result.actual_model,
                        result.input_tokens,
                        result.error_code,
                        batch_job_id,
                        result.custom_id,
                        result.status,
                    ),
                )

    def pending_files(self, batch_job_id: UUID) -> list[tuple[str, str]]:
        with self.database.connection() as connection:
            rows = cast(list[Any], connection.execute(
                """
                select file_role, provider_file_id from ai_embedding_batch_files
                where batch_job_id = %s and cleanup_status <> 'DELETED'
                order by file_role
                """,
                (batch_job_id,),
            ).fetchall())
        return [(str(row["file_role"]), str(row["provider_file_id"])) for row in rows]

    def mark_file_deleted(self, batch_job_id: UUID, role: str) -> None:
        with self.database.transaction() as connection:
            connection.execute(
                """
                update ai_embedding_batch_files set cleanup_status = 'DELETED',
                    delete_attempts = delete_attempts + 1,
                    delete_acknowledged_at = clock_timestamp(), last_error_code = null,
                    updated_at = clock_timestamp()
                where batch_job_id = %s and file_role = %s
                """,
                (batch_job_id, role),
            )

    def mark_file_delete_failed(self, batch_job_id: UUID, role: str, error_code: str) -> None:
        with self.database.transaction() as connection:
            connection.execute(
                """
                update ai_embedding_batch_files set delete_attempts = delete_attempts + 1,
                    last_error_code = %s, updated_at = clock_timestamp()
                where batch_job_id = %s and file_role = %s
                """,
                (error_code[:80], batch_job_id, role),
            )

    def finish_cleanup(self, job: ClaimedBatch, owner: str) -> None:
        terminal = job.terminal_status or "FAILED"
        with self.database.transaction() as connection:
            remaining_row = cast(Any, connection.execute(
                """
                select count(*) as count from ai_embedding_batch_files
                where batch_job_id = %s and cleanup_status <> 'DELETED'
                """,
                (job.batch_job_id,),
            ).fetchone())
            remaining = remaining_row["count"]
            if remaining:
                connection.execute(
                    """
                    update ai_embedding_batch_jobs set lease_owner = null, lease_expires_at = null,
                        available_at = clock_timestamp() + interval '30 seconds', updated_at = clock_timestamp()
                    where batch_job_id = %s and lease_owner = %s
                    """,
                    (job.batch_job_id, owner),
                )
                return
            connection.execute(
                """
                update ai_embedding_batch_jobs set status = %s, completed_at = clock_timestamp(),
                    lease_owner = null, lease_expires_at = null, last_error_code = null,
                    updated_at = clock_timestamp()
                where batch_job_id = %s and lease_owner = %s
                """,
                (terminal, job.batch_job_id, owner),
            )


class EmbeddingBatchService:
    def __init__(
        self,
        backend: BackendClient,
        knowledge: KnowledgeRepository,
        repository: Repository,
        settings: Settings,
        pricing_path: Path,
        adapter: EmbeddingBatchAdapter,
    ):
        self.backend = backend
        self.knowledge = knowledge
        self.repository = repository
        self.settings = settings
        self.pricing = PricingCatalog(pricing_path)
        self.adapter = adapter
        self.store = EmbeddingBatchStore(repository, settings)
        self.owner = f"embedding-batch-{uuid5(NAMESPACE_URL, str(id(self)))}"

    def defer_missing(
        self, event: IndexWorkItem, chunks: list[PreparedKnowledgeChunk], index_owner: str
    ) -> bool:
        if self.settings.embedding_batch_mode == "off":
            return False
        if event.reconciliation_run_id is None:
            raise ValueError("offline embedding batch is limited to full reconciliation builds")
        snapshot = self.settings.resolved_embedding_model_snapshot
        specs_by_key: dict[str, tuple[EmbeddingArtifactSpec, str]] = {}
        for chunk in chunks:
            spec = embedding_artifact_spec(snapshot, EMBEDDING_DIMENSION, NORMALIZATION_VERSION, chunk.embedding_input)
            current = specs_by_key.setdefault(spec.artifact_key, (spec, chunk.embedding_input))
            if current[1] != chunk.embedding_input:
                raise ValueError("embedding batch artifact key collision")
        existing = self.knowledge.find_embedding_artifacts([value[0] for value in specs_by_key.values()])
        missing = [value for key, value in specs_by_key.items() if key not in existing]
        if not missing:
            return False
        status = self.store.status_for_event(event.event_id)
        if status in {"FAILED", "EXPIRED", "CANCELLED"}:
            raise BatchTerminalError(f"embedding batch is terminal: {status}")
        if status == "COMPLETED":
            return False
        batch_job_id = uuid5(NAMESPACE_URL, f"deskseed:embedding-batch:{event.event_id}:{BATCH_CONTRACT_VERSION}")
        items = [
            BatchItem(
                ordinal,
                f"b-{ordinal:04d}-{hashlib.sha256(f'{batch_job_id}:{ordinal}:custom-v1'.encode()).hexdigest()[:16]}",
                spec,
            )
            for ordinal, (spec, _) in enumerate(missing)
        ]
        payload = _build_input_file(items, {spec.artifact_key: text for spec, text in missing}, self.settings.embedding_model)
        input_tokens = sum(self.pricing.count_text_tokens(self.settings.embedding_model, text) for _, text in missing)
        self._validate_limits(items, payload, input_tokens)
        manifest_digest = _manifest_digest(event, items, self.settings, self.pricing.version)
        self.store.create_manifest(event, items, input_tokens, len(payload), manifest_digest, self.pricing.version)
        self.repository.defer_index_event_for_batch(event.event_id, index_owner, hours=25)
        return True

    def progress_once(self, limit: int = 2) -> int:
        progressed = 0
        for _ in range(limit):
            job = self.store.claim_due(self.owner)
            if job is None:
                break
            try:
                self._progress(job)
                progressed += 1
            except Exception as exception:
                self.store.release(job.batch_job_id, self.owner, type(exception).__name__.upper(), 5)
        return progressed

    def request_cancel(self, batch_job_id: UUID) -> None:
        with self.repository.database.transaction() as connection:
            connection.execute(
                """
                update ai_embedding_batch_jobs set cancel_requested = true,
                    status = case when status in ('IN_PROGRESS', 'SUBMITTING') then 'CANCELLING' else status end,
                    available_at = clock_timestamp(), updated_at = clock_timestamp()
                where batch_job_id = %s and status not in ('COMPLETED', 'CANCELLED', 'FAILED', 'EXPIRED')
                """,
                (batch_job_id,),
            )

    def _progress(self, job: ClaimedBatch) -> None:
        if job.status == "PREPARING":
            self._prepare(job)
        elif job.status == "UPLOADING":
            self._upload(job)
        elif job.status == "SUBMITTING":
            self._submit(job)
        elif job.status in {"IN_PROGRESS", "CANCELLING"}:
            self._poll(job)
        elif job.status == "FINALIZING":
            self._finalize(job)
        elif job.status == "CLEANUP_PENDING":
            self._cleanup(job)
        else:
            raise ValueError("embedding batch state is not progressable")

    def _prepare(self, job: ClaimedBatch) -> None:
        operation_key = f"batch-index:{job.artifact_generation}:{job.event_id}"
        reservation_id = self._reservation_for(operation_key)
        if reservation_id is None:
            with self.repository.database.connection() as connection:
                token_row = cast(Any, connection.execute(
                    "select input_token_count from ai_embedding_batch_jobs where batch_job_id = %s",
                    (job.batch_job_id,),
                ).fetchone())
                tokens = token_row["input_token_count"]
            reservation_id = self.repository.reserve_system_budget(
                job.workspace_key,
                operation_key,
                job.model_alias,
                self.pricing.version,
                self.pricing.upper_bound_microusd(job.model_alias, int(tokens), service_tier="batch"),
            )
        call_id = uuid5(NAMESPACE_URL, f"deskseed:embedding-batch-call:{job.batch_job_id}")
        self.repository.create_provider_call(
            reservation_id,
            call_id,
            job.model_alias,
            self.pricing.version,
            self.pricing.service_tier,
            self.pricing.context_price_band,
        )
        with self.repository.database.connection() as connection:
            lifecycle_row = cast(Any, connection.execute(
                "select lifecycle_status from ai_provider_calls where call_id = %s", (call_id,)
            ).fetchone())
            lifecycle = lifecycle_row["lifecycle_status"]
        if lifecycle == "RESERVED":
            self.repository.mark_provider_call_dispatching(call_id)
        self.store.transition(
            job.batch_job_id,
            self.owner,
            "UPLOADING",
            reservation_id=reservation_id,
            provider_call_id=call_id,
        )

    def _upload(self, job: ClaimedBatch) -> None:
        if job.provider_input_file_id is not None:
            self.store.transition(job.batch_job_id, self.owner, "SUBMITTING")
            return
        inputs = self._rebuild_inputs(job)
        items = self.store.items(job.batch_job_id)
        payload = _build_input_file(items, inputs, job.model_alias)
        file_id = self.adapter.upload_input(payload, f"{job.batch_job_id}:upload:v1")
        self.store.register_file(job.batch_job_id, "INPUT", file_id)
        self.store.transition(
            job.batch_job_id,
            self.owner,
            "SUBMITTING",
            provider_input_file_id=file_id,
        )

    def _submit(self, job: ClaimedBatch) -> None:
        if job.provider_input_file_id is None:
            raise ValueError("embedding batch input file identity is missing")
        remote = self.adapter.submit(job.provider_input_file_id, f"{job.batch_job_id}:submit:v1")
        self.store.transition(
            job.batch_job_id,
            self.owner,
            "CANCELLING" if job.cancel_requested else "IN_PROGRESS",
            provider_batch_id=remote.batch_id,
            submitted_at=datetime.now(UTC),
        )

    def _poll(self, job: ClaimedBatch) -> None:
        if job.provider_batch_id is None:
            raise ValueError("embedding batch provider identity is missing")
        cancel_sent_at: datetime | None
        if job.cancel_requested and job.cancel_sent_at is None:
            remote = self.adapter.cancel(job.provider_batch_id)
            cancel_sent_at = datetime.now(UTC)
        else:
            remote = self.adapter.retrieve(job.provider_batch_id)
            cancel_sent_at = job.cancel_sent_at
        if remote.status in {"validating", "in_progress", "finalizing", "cancelling"}:
            self.store.transition(
                job.batch_job_id,
                self.owner,
                "CANCELLING" if job.cancel_requested else "IN_PROGRESS",
                cancel_sent_at=cancel_sent_at,
            )
            return
        terminal = {
            "completed": "COMPLETED",
            "cancelled": "CANCELLED",
            "failed": "FAILED",
            "expired": "EXPIRED",
        }[remote.status]
        if remote.output_file_id:
            self.store.register_file(job.batch_job_id, "OUTPUT", remote.output_file_id)
        if remote.error_file_id:
            self.store.register_file(job.batch_job_id, "ERROR", remote.error_file_id)
        self.store.transition(
            job.batch_job_id,
            self.owner,
            "FINALIZING",
            terminal_status=terminal,
            provider_output_file_id=remote.output_file_id,
            provider_error_file_id=remote.error_file_id,
            provider_request_total=remote.request_total,
            provider_request_completed=remote.request_completed,
            provider_request_failed=remote.request_failed,
            provider_terminal_at=datetime.now(UTC),
            cancel_sent_at=cancel_sent_at,
        )

    def _finalize(self, job: ClaimedBatch) -> None:
        items = self.store.items(job.batch_job_id)
        expected = {item.custom_id: item for item in items}
        raw = b""
        if job.provider_output_file_id:
            raw += self.adapter.download_file(job.provider_output_file_id)
        if job.provider_error_file_id:
            raw += self.adapter.download_file(job.provider_error_file_id)
        results = _parse_results(raw, expected)
        counts_match = (
            job.provider_request_total == job.request_count
            and job.provider_request_completed is not None
            and job.provider_request_failed is not None
            and job.provider_request_completed + job.provider_request_failed <= job.request_count
        )
        if not counts_match:
            results = [
                ParsedBatchResult(
                    result.custom_id,
                    "UNKNOWN",
                    result.actual_model,
                    result.input_tokens,
                    None,
                    "REQUEST_COUNT_MISMATCH",
                )
                for result in results
            ]
        reviewed_results: list[ParsedBatchResult] = []
        for result in results:
            if result.status != "SUCCEEDED" or result.actual_model is None:
                reviewed_results.append(result)
                continue
            try:
                self.pricing.require_reviewed_actual_model(
                    job.model_alias,
                    result.actual_model,
                    self.pricing.service_tier,
                    self.pricing.context_price_band,
                )
                reviewed_results.append(result)
            except ValueError:
                reviewed_results.append(
                    ParsedBatchResult(
                        result.custom_id,
                        "UNKNOWN",
                        result.actual_model,
                        result.input_tokens,
                        None,
                        "MODEL_UNREVIEWED",
                    )
                )
        results = reviewed_results
        self.store.record_results(job.batch_job_id, results)
        inputs = self._rebuild_inputs(job, allow_stale=True)
        successes = [result for result in results if result.status == "SUCCEEDED"]
        by_custom = {item.custom_id: item for item in items}
        artifacts: list[tuple[EmbeddingArtifactSpec, str, list[float], str]] = []
        for result in successes:
            assert result.vector is not None and result.actual_model is not None
            item = by_custom[result.custom_id]
            final_input = inputs.get(item.spec.artifact_key)
            if final_input is not None:
                artifacts.append((item.spec, final_input, result.vector, result.actual_model))
        stored = bool(artifacts) and self.knowledge.store_embedding_artifacts_for_batch_event(
            job.workspace_key,
            job.article_id,
            job.source_version,
            job.event_id,
            job.artifact_generation,
            job.reconciliation_run_id,
            artifacts,
        )
        self._settle(job, results)
        if stored and all(result.status == "SUCCEEDED" for result in results):
            self.repository.wake_index_event_after_batch(job.event_id)
        else:
            self.repository.fail_index_event_after_batch(job.event_id, "BATCH_SOURCE_STALE")
        terminal = job.terminal_status or "FAILED"
        if any(result.status != "SUCCEEDED" for result in results) and terminal == "COMPLETED":
            terminal = "FAILED"
        self.store.transition(
            job.batch_job_id,
            self.owner,
            "CLEANUP_PENDING",
            terminal_status=terminal,
            finalized_at=datetime.now(UTC),
        )

    def _cleanup(self, job: ClaimedBatch) -> None:
        failed = False
        for role, file_id in self.store.pending_files(job.batch_job_id):
            try:
                self.adapter.delete_file(file_id)
                self.store.mark_file_deleted(job.batch_job_id, role)
            except Exception as exception:
                failed = True
                self.store.mark_file_delete_failed(job.batch_job_id, role, type(exception).__name__.upper())
        if failed:
            self.store.release(job.batch_job_id, self.owner, "FILE_DELETE_PENDING", 30)
        else:
            self.store.finish_cleanup(job, self.owner)

    def _settle(self, job: ClaimedBatch, results: list[ParsedBatchResult]) -> None:
        if job.provider_call_id is None or job.provider_batch_id is None:
            raise ProviderCallStateUnknownError("embedding batch provider call identity is missing")
        all_known = bool(results) and all(
            result.status == "SUCCEEDED" and result.input_tokens is not None and result.actual_model is not None
            for result in results
        )
        actual_models = {result.actual_model for result in results if result.actual_model is not None}
        usage = Usage(sum(result.input_tokens or 0 for result in results), 0, 0, 0) if all_known else None
        actual_model = next(iter(actual_models)) if len(actual_models) == 1 else None
        receipt = ProviderCallReceipt(
            call_id=job.provider_call_id,
            provider_request_id=job.provider_batch_id,
            requested_alias=job.model_alias,
            actual_model=actual_model,
            usage_schema_version="openai-batch-embedding-v1",
            usage_status=UsageStatus.KNOWN if usage is not None else UsageStatus.UNAVAILABLE,
            usage=usage,
            usage_issue_code=None if usage is not None else "BATCH_PARTIAL_OR_UNKNOWN",
            service_tier=self.pricing.service_tier,
            context_price_band=self.pricing.context_price_band,
        )
        known_cost = None
        if usage is not None and actual_model is not None:
            known_cost = self.pricing.cost_microusd(
                job.model_alias, actual_model, usage, self.pricing.service_tier, self.pricing.context_price_band
            )
        self.repository.record_provider_response(receipt, known_cost)

    def _rebuild_inputs(self, job: ClaimedBatch, allow_stale: bool = False) -> dict[str, str]:
        try:
            article = self.backend.read_public_article(job.article_id, job.revision_id, job.event_id)
        except Exception:
            if allow_stale:
                return {}
            raise
        exact = (
            article.dataClass == "PUBLIC_KB_ONLY"
            and article.articleId == job.article_id
            and article.revisionId == job.revision_id
            and article.sourceVersion == job.source_version
            and article.publicRevision == job.public_revision
        )
        if not exact:
            if allow_stale:
                return {}
            raise ValueError("embedding batch source binding changed")
        chunks = build_public_article_chunks(
            article.title,
            article.categoryTitle,
            article.sectionTitle,
            article.body,
            lambda value: self.pricing.count_text_tokens(job.model_alias, value),
        )
        inputs: dict[str, str] = {}
        for chunk in chunks:
            spec = embedding_artifact_spec(
                job.model_snapshot, EMBEDDING_DIMENSION, NORMALIZATION_VERSION, chunk.embedding_input
            )
            inputs[spec.artifact_key] = chunk.embedding_input
        expected = {item.spec.artifact_key for item in self.store.items(job.batch_job_id)}
        if not allow_stale and not expected.issubset(inputs):
            raise ValueError("embedding batch manifest no longer matches source")
        return inputs

    def _reservation_for(self, operation_key: str) -> UUID | None:
        with self.repository.database.connection() as connection:
            row = cast(Any, connection.execute(
                """
                select reservation_id, status, model_alias, pricing_version
                from ai_cost_ledger where operation_key = %s
                """,
                (operation_key,),
            ).fetchone())
        if row is None:
            return None
        if (
            row["status"] != "RESERVED"
            or row["model_alias"] != self.settings.embedding_model
            or row["pricing_version"] != self.pricing.version
        ):
            raise ProviderCallStateUnknownError("embedding batch reservation is not safely resumable")
        return cast(UUID, row["reservation_id"])

    def _validate_limits(self, items: list[BatchItem], payload: bytes, input_tokens: int) -> None:
        if len(items) > self.settings.embedding_batch_max_requests:
            raise ValueError("embedding batch request limit exceeded")
        if len(items) > self.settings.embedding_batch_max_inputs:
            raise ValueError("embedding batch input limit exceeded")
        if len(payload) > self.settings.embedding_batch_max_bytes:
            raise ValueError("embedding batch byte limit exceeded")
        self.pricing.upper_bound_microusd(
            self.settings.embedding_model, input_tokens, service_tier=self.pricing.service_tier
        )


def _manifest_digest(
    event: IndexWorkItem, items: list[BatchItem], settings: Settings, pricing_version: str
) -> str:
    value = {
        "contract": BATCH_CONTRACT_VERSION,
        "eventId": str(event.event_id),
        "workspaceKey": event.workspace_key,
        "articleId": str(event.article_id),
        "revisionId": str(event.revision_id),
        "sourceVersion": event.source_version,
        "publicRevision": event.public_revision,
        "artifactGeneration": event.artifact_generation,
        "reconciliationRunId": str(event.reconciliation_run_id),
        "modelAlias": settings.embedding_model,
        "modelSnapshot": settings.resolved_embedding_model_snapshot,
        "dimension": EMBEDDING_DIMENSION,
        "normalization": NORMALIZATION_VERSION,
        "pricingVersion": pricing_version,
        "window": BATCH_COMPLETION_WINDOW,
        "items": [
            {"ordinal": item.ordinal, "customId": item.custom_id, "artifactKey": item.spec.artifact_key}
            for item in items
        ],
    }
    return hashlib.sha256(_json_bytes(value)).hexdigest()


def _build_input_file(items: list[BatchItem], inputs: dict[str, str], model_alias: str) -> bytes:
    model = model_alias.split("/", 1)[-1]
    lines = []
    for item in items:
        text = inputs.get(item.spec.artifact_key)
        if text is None or hashlib.sha256(text.encode()).hexdigest() != item.spec.input_sha256:
            raise ValueError("embedding batch input does not match manifest")
        lines.append(
            _json_bytes(
                {
                    "custom_id": item.custom_id,
                    "method": "POST",
                    "url": BATCH_ENDPOINT,
                    "body": {
                        "model": model,
                        "input": text,
                        "dimensions": item.spec.dimension,
                        "encoding_format": "float",
                    },
                }
            )
        )
    return b"\n".join(lines) + b"\n"


def _parse_results(raw: bytes, expected: dict[str, BatchItem]) -> list[ParsedBatchResult]:
    parsed: dict[str, ParsedBatchResult] = {}
    malformed = False
    for line in raw.splitlines():
        if not line.strip():
            continue
        try:
            payload = json.loads(line)
            custom_id = payload["custom_id"]
            if not isinstance(custom_id, str) or custom_id not in expected or custom_id in parsed:
                malformed = True
                continue
            response = payload.get("response")
            if not isinstance(response, dict) or response.get("status_code") != 200:
                parsed[custom_id] = ParsedBatchResult(custom_id, "FAILED", None, None, None, "PROVIDER_ERROR")
                continue
            body = response.get("body")
            if not isinstance(body, dict):
                raise ValueError
            data = body.get("data")
            usage = body.get("usage")
            model = body.get("model")
            if not isinstance(data, list) or len(data) != 1 or not isinstance(data[0], dict):
                raise ValueError
            vector = data[0].get("embedding")
            index = data[0].get("index")
            tokens = usage.get("prompt_tokens") if isinstance(usage, dict) else None
            if (
                index != 0
                or not isinstance(vector, list)
                or len(vector) != expected[custom_id].spec.dimension
                or any(not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value) for value in vector)
                or not isinstance(model, str)
                or not model
                or not isinstance(tokens, int)
                or isinstance(tokens, bool)
                or tokens < 0
            ):
                raise ValueError
            parsed[custom_id] = ParsedBatchResult(
                custom_id, "SUCCEEDED", model, tokens, [float(value) for value in vector], None
            )
        except (KeyError, TypeError, ValueError, json.JSONDecodeError):
            malformed = True
    if malformed:
        return [ParsedBatchResult(item.custom_id, "UNKNOWN", None, None, None, "MALFORMED_OUTPUT") for item in expected.values()]
    return [
        parsed.get(custom_id, ParsedBatchResult(custom_id, "UNKNOWN", None, None, None, "MISSING_OUTPUT"))
        for custom_id in expected
    ]


def _claimed_batch(row: Any, attempts: int) -> ClaimedBatch:
    return ClaimedBatch(
        row["batch_job_id"], row["event_id"], str(row["workspace_key"]), row["article_id"],
        row["revision_id"], int(row["source_version"]), str(row["public_revision"]),
        int(row["artifact_generation"]), row["reconciliation_run_id"], str(row["status"]),
        None if row["terminal_status"] is None else str(row["terminal_status"]), str(row["model_alias"]),
        str(row["model_snapshot"]), str(row["pricing_version"]), row["reservation_id"],
        row["provider_call_id"], row["provider_input_file_id"], row["provider_batch_id"],
        row["provider_output_file_id"], row["provider_error_file_id"], int(row["request_count"]),
        row["provider_request_total"], row["provider_request_completed"], row["provider_request_failed"],
        bool(row["cancel_requested"]),
        row["cancel_sent_at"], attempts,
    )


def _remote_batch(value: Any) -> RemoteBatch:
    status = _value(value, "status")
    allowed = {"validating", "in_progress", "finalizing", "completed", "failed", "expired", "cancelling", "cancelled"}
    if status not in allowed:
        raise ValueError("provider batch status is unsupported")
    counts = _value(value, "request_counts")
    total = _nonnegative_optional_int(_value(counts, "total"))
    completed = _nonnegative_optional_int(_value(counts, "completed"))
    failed = _nonnegative_optional_int(_value(counts, "failed"))
    if any(item is not None for item in (total, completed, failed)) and (
        total is None or completed is None or failed is None or completed + failed > total
    ):
        raise ValueError("provider batch request counts are invalid")
    return RemoteBatch(
        _required_identifier(_value(value, "id"), "provider batch"),
        status,
        _optional_identifier(_value(value, "output_file_id")),
        _optional_identifier(_value(value, "error_file_id")),
        total,
        completed,
        failed,
    )


def _nonnegative_optional_int(value: object) -> int | None:
    if value is None:
        return None
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise ValueError("provider batch request count is invalid")
    return value


def _required_identifier(value: object, label: str) -> str:
    identifier = _optional_identifier(value)
    if identifier is None:
        raise ValueError(f"{label} identity is invalid")
    return identifier


def _optional_identifier(value: object) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str) or not value or len(value) > 200 or any(not char.isprintable() for char in value):
        raise ValueError("provider identity is invalid")
    return value


def _value(value: Any, name: str) -> Any:
    return value.get(name) if isinstance(value, dict) else getattr(value, name, None)


def _json_bytes(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def _fake_vector(text: str, dimension: int) -> list[float]:
    digest = hashlib.sha256(text.encode()).digest()
    return [((digest[index % len(digest)] / 255.0) * 2.0) - 1.0 for index in range(dimension)]
