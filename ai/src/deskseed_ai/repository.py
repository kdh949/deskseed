from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

from cryptography.exceptions import InvalidTag
from psycopg.types.json import Jsonb
from pydantic import ValidationError

from .call_receipts import ProviderCallReceipt, UsageStatus
from .config import Settings
from .db import Database
from .schemas import (
    Accepted,
    CancellationEnvelope,
    ContextMemoryPayload,
    Feature,
    FeedbackRequest,
    GenerationMode,
    GenerationProvenance,
    IndexEvent,
    JobEnvelope,
    JobPhase,
    JobReceipt,
    JobStatus,
    OperationRequest,
    ReplyDraftResult,
    SummaryResult,
    TriageResult,
    TypedResult,
)
from .security import EnvelopeCipher, sha256_text


class ConflictError(RuntimeError):
    pass


class NotFoundError(RuntimeError):
    pass


class BudgetExceededError(RuntimeError):
    pass


class StaleLeaseError(RuntimeError):
    pass


class ActiveLeaseError(RuntimeError):
    pass


class ProviderCallStateUnknownError(RuntimeError):
    pass


class ProviderReceiptConflictError(RuntimeError):
    pass


@dataclass(frozen=True)
class ReconciliationRun:
    run_id: UUID
    workspace_key: str
    snapshot_token: UUID
    snapshot_expires_at: datetime
    next_cursor: UUID | None
    canonical_corpus_revision: int | None
    target_artifact_generation: int
    index_contract_version: str
    chunker_version: str
    normalization_version: str
    embedding_model: str
    embedding_dimension: int


@dataclass(frozen=True)
class PublishedIndexGeneration:
    generation: int
    canonical_corpus_revision: int
    artifact_generation: int


@dataclass(frozen=True)
class IndexWorkItem:
    event_id: UUID
    workspace_key: str
    article_id: UUID
    revision_id: UUID
    action: str
    source_version: int
    public_revision: str
    created_at: datetime
    artifact_generation: int
    reconciliation_run_id: UUID


@dataclass(frozen=True)
class ReplyCacheCandidate:
    origin_job_id: UUID
    result: ReplyDraftResult
    fingerprint: str


@dataclass(frozen=True)
class ContextMemoryRecord:
    memory_id: UUID
    workspace_key: str
    requester_id: UUID
    ticket_id: UUID
    memory_version: int
    covered_through_sequence: int
    source_prefix_digest: str
    schema_version: str
    policy_version: str
    prompt_version: str
    model_alias: str
    update_count: int
    payload: ContextMemoryPayload
    created_at: datetime
    expires_at: datetime


@dataclass(frozen=True)
class SharedExecutionClaim:
    execution_id: UUID
    execution_key: str
    key_version: str
    execution_generation: int
    disposition: str
    status: str
    terminal_reason: str | None


@dataclass(frozen=True)
class ClaimedJob:
    job_id: UUID
    generation: int
    lease_epoch: int
    feature: Feature
    workspace_key: str
    requester_id: UUID
    ticket_id: UUID
    context_revision: str
    context_policy_version: str
    ai_input_revision: str | None
    input_policy_version: str | None
    traceparent: str | None
    deadline_at: datetime
    options: dict[str, str]
    generation_mode: GenerationMode | None = None
    candidate_id: UUID | None = None
    candidate_sequence: int | None = None


@dataclass(frozen=True)
class DispatchEvent:
    event_id: UUID
    job_id: UUID
    generation: int
    traceparent: str | None
    tracestate: str | None


@dataclass(frozen=True)
class FeedbackExport:
    job_id: UUID
    feedback_type: str
    source_revision: int
    score_id: UUID
    score_name: str
    reason_code: str | None
    first_recorded_at: datetime
    lease_owner: str


def _context_memory_aad(row) -> bytes:
    return (
        f"{row['memory_id']}:{row['workspace_key']}:{row['requester_id']}:"
        f"{row['ticket_id']}:{row['memory_version']}:{row['schema_version']}:"
        f"{row['policy_version']}"
    ).encode()


class Repository:
    def __init__(self, database: Database, settings: Settings, cipher: EnvelopeCipher):
        self.database = database
        self.settings = settings
        self.cipher = cipher

    def read_context_memory(
        self,
        workspace_key: str,
        requester_id: UUID,
        ticket_id: UUID,
    ) -> ContextMemoryRecord | None:
        with self.database.transaction() as connection:
            connection.execute(
                """
                update ai_context_memories set status = 'INVALIDATED',
                    invalidated_at = clock_timestamp(), invalidation_reason = 'EXPIRED',
                    updated_at = clock_timestamp()
                where workspace_key = %s and requester_id = %s and ticket_id = %s
                  and status = 'ACTIVE' and expires_at <= clock_timestamp()
                """,
                (workspace_key, requester_id, ticket_id),
            )
            row = connection.execute(
                """
                select * from ai_context_memories
                where workspace_key = %s and requester_id = %s and ticket_id = %s
                  and status = 'ACTIVE' and expires_at > clock_timestamp()
                """,
                (workspace_key, requester_id, ticket_id),
            ).fetchone()
            if row is None:
                return None
            try:
                plaintext = self.cipher.decrypt(
                    bytes(row["payload_ciphertext"]),
                    bytes(row["payload_nonce"]),
                    _context_memory_aad(row),
                )
                payload = ContextMemoryPayload.model_validate_json(plaintext)
            except (InvalidTag, ValidationError, ValueError, TypeError):
                connection.execute(
                    """
                    update ai_context_memories set status = 'INVALIDATED',
                        invalidated_at = clock_timestamp(), invalidation_reason = 'PAYLOAD_INVALID',
                        updated_at = clock_timestamp()
                    where memory_id = %s and status = 'ACTIVE'
                    """,
                    (row["memory_id"],),
                )
                return None
            return ContextMemoryRecord(
                memory_id=row["memory_id"],
                workspace_key=row["workspace_key"],
                requester_id=row["requester_id"],
                ticket_id=row["ticket_id"],
                memory_version=row["memory_version"],
                covered_through_sequence=row["covered_through_sequence"],
                source_prefix_digest=row["source_prefix_digest"],
                schema_version=row["schema_version"],
                policy_version=row["policy_version"],
                prompt_version=row["prompt_version"],
                model_alias=row["model_alias"],
                update_count=row["update_count"],
                payload=payload,
                created_at=row["created_at"],
                expires_at=row["expires_at"],
            )

    def save_context_memory(
        self,
        workspace_key: str,
        requester_id: UUID,
        ticket_id: UUID,
        covered_through_sequence: int,
        source_prefix_digest: str,
        payload: ContextMemoryPayload,
        prompt_version: str,
        model_alias: str,
        existing: ContextMemoryRecord | None,
        full_rebuild: bool,
    ) -> ContextMemoryRecord:
        now = datetime.now(UTC)
        memory_id = existing.memory_id if existing is not None else uuid4()
        memory_version = existing.memory_version + 1 if existing is not None else 1
        created_at = existing.created_at if existing is not None else now
        expires_at = (
            existing.expires_at
            if existing is not None
            else now + timedelta(hours=self.settings.context_memory_ttl_hours)
        )
        update_count = 0 if existing is None or full_rebuild else existing.update_count + 1
        aad_row = {
            "memory_id": memory_id,
            "workspace_key": workspace_key,
            "requester_id": requester_id,
            "ticket_id": ticket_id,
            "memory_version": memory_version,
            "schema_version": "context-memory-v1",
            "policy_version": "context-memory-policy-v1",
        }
        ciphertext, nonce = self.cipher.encrypt(
            payload.model_dump_json().encode(), _context_memory_aad(aad_row)
        )
        with self.database.transaction() as connection:
            if existing is None:
                inserted = connection.execute(
                    """
                    insert into ai_context_memories (
                        memory_id, workspace_key, requester_id, ticket_id, memory_version, status,
                        covered_through_sequence, source_prefix_digest, schema_version,
                        policy_version, prompt_version, model_alias, update_count,
                        payload_ciphertext, payload_nonce, created_at, updated_at, expires_at
                    ) values (
                        %s, %s, %s, %s, %s, 'ACTIVE', %s, %s, 'context-memory-v1',
                        'context-memory-policy-v1', %s, %s, %s, %s, %s, %s, %s, %s
                    ) on conflict do nothing
                    """,
                    (
                        memory_id, workspace_key, requester_id, ticket_id, memory_version,
                        covered_through_sequence, source_prefix_digest, prompt_version, model_alias,
                        update_count, ciphertext, nonce, created_at, now, expires_at,
                    ),
                ).rowcount
                if not inserted:
                    raise ConflictError("active context memory already exists")
            else:
                updated = connection.execute(
                    """
                    update ai_context_memories set memory_version = %s,
                        covered_through_sequence = %s, source_prefix_digest = %s,
                        prompt_version = %s, model_alias = %s, update_count = %s,
                        payload_ciphertext = %s, payload_nonce = %s, updated_at = %s
                    where memory_id = %s and memory_version = %s and status = 'ACTIVE'
                      and expires_at > clock_timestamp()
                    """,
                    (
                        memory_version, covered_through_sequence, source_prefix_digest,
                        prompt_version, model_alias, update_count, ciphertext, nonce, now,
                        memory_id, existing.memory_version,
                    ),
                ).rowcount
                if not updated:
                    raise ConflictError("context memory version changed")
        record = self.read_context_memory(workspace_key, requester_id, ticket_id)
        if record is None:
            raise ConflictError("context memory was not readable after save")
        return record

    def invalidate_context_memory(
        self,
        memory_id: UUID,
        memory_version: int,
        reason: str,
    ) -> bool:
        with self.database.transaction() as connection:
            return bool(
                connection.execute(
                    """
                    update ai_context_memories set status = 'INVALIDATED',
                        invalidated_at = clock_timestamp(), invalidation_reason = %s,
                        updated_at = clock_timestamp()
                    where memory_id = %s and memory_version = %s and status = 'ACTIVE'
                    """,
                    (reason[:40], memory_id, memory_version),
                ).rowcount
            )

    def accept_job(self, envelope: JobEnvelope) -> Accepted:
        canonical = envelope.model_dump_json(by_alias=True, exclude_none=False)
        fingerprint = sha256_text(canonical)
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            same_event = connection.execute(
                "select job_id, request_fingerprint from ai_job_inbox where event_id = %s for update",
                (envelope.eventId,),
            ).fetchone()
            if same_event:
                if same_event["job_id"] != envelope.jobId or same_event["request_fingerprint"] != fingerprint:
                    raise ConflictError("event ID reused with different content")
                return Accepted(replayed=True, jobId=envelope.jobId)
            same_job = connection.execute(
                "select request_fingerprint from ai_jobs where job_id = %s for update", (envelope.jobId,)
            ).fetchone()
            if same_job:
                if same_job["request_fingerprint"] != fingerprint:
                    raise ConflictError("job ID reused with different content")
                connection.execute(
                    "insert into ai_job_inbox (event_id, job_id, event_type, request_fingerprint, received_at) values (%s, %s, 'JOB_REQUESTED', %s, %s)",
                    (envelope.eventId, envelope.jobId, fingerprint, now),
                )
                return Accepted(replayed=True, jobId=envelope.jobId)
            historical = connection.execute(
                "select request_fingerprint from ai_job_inbox where job_id = %s and event_type = 'JOB_REQUESTED' limit 1",
                (envelope.jobId,),
            ).fetchone()
            if historical:
                if historical["request_fingerprint"] != fingerprint:
                    raise ConflictError("purged job ID reused with different content")
                return Accepted(replayed=True, jobId=envelope.jobId)
            outstanding = connection.execute(
                """
                select count(*) as count from ai_jobs
                where workspace_key = %s and status in ('QUEUED', 'RUNNING', 'RETRY_WAIT')
                """,
                (envelope.workspaceKey,),
            ).fetchone()["count"]
            if outstanding >= 300:
                raise ConflictError("interactive backlog limit reached")
            if envelope.deadlineAt <= now:
                raise ConflictError("job deadline already expired")
            tombstone = connection.execute(
                "select workspace_key, request_revision from ai_cancellation_tombstones where job_id = %s for update",
                (envelope.jobId,),
            ).fetchone()
            if tombstone and tombstone["workspace_key"] != envelope.workspaceKey:
                raise ConflictError("cancellation tombstone workspace mismatch")
            cancelled = bool(tombstone and tombstone["request_revision"] >= envelope.requestRevision)
            connection.execute(
                "insert into ai_job_inbox (event_id, job_id, event_type, request_fingerprint, received_at) values (%s, %s, 'JOB_REQUESTED', %s, %s)",
                (envelope.eventId, envelope.jobId, fingerprint, now),
            )
            connection.execute(
                """
                insert into ai_jobs (
                    job_id, workspace_key, requester_id, ticket_id, ticket_number, feature,
                    status, phase, generation, lease_epoch, request_revision, cancel_requested,
                    context_revision, context_policy_version, input_scope, options_json,
                    ai_input_revision, input_policy_version,
                    generation_mode, candidate_id, candidate_sequence,
                    request_fingerprint, traceparent, tracestate, created_at, deadline_at, updated_at
                ) values (
                    %s, %s, %s, %s, %s, %s, %s, %s, 1, 0, %s, %s,
                    %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
                )
                """,
                (
                    envelope.jobId,
                    envelope.workspaceKey,
                    envelope.requesterId,
                    envelope.ticketId,
                    envelope.ticketNumber,
                    envelope.feature.value,
                    "CANCELLED" if cancelled else "QUEUED",
                    "COMPLETE" if cancelled else "QUEUED",
                    max(envelope.requestRevision, tombstone["request_revision"] if tombstone else 0),
                    cancelled,
                    envelope.contextRevision,
                    envelope.contextPolicyVersion,
                    envelope.dataClass,
                    Jsonb(envelope.options),
                    envelope.aiInputRevision,
                    envelope.inputPolicyVersion,
                    envelope.generationMode.value if envelope.generationMode else None,
                    envelope.candidateId,
                    envelope.candidateSequence,
                    fingerprint,
                    envelope.traceparent,
                    envelope.tracestate,
                    envelope.createdAt,
                    envelope.deadlineAt,
                    now,
                ),
            )
            if not cancelled:
                connection.execute(
                    """
                    insert into ai_dispatch_outbox (
                        event_id, job_id, generation, status, attempts, available_at, created_at
                    ) values (%s, %s, 1, 'PENDING', 0, %s, %s)
                    """,
                    (uuid4(), envelope.jobId, now, now),
                )
            else:
                connection.execute(
                    "update ai_jobs set completed_at = %s where job_id = %s",
                    (now, envelope.jobId),
                )
        return Accepted(replayed=False, jobId=envelope.jobId)

    def cancel(self, envelope: CancellationEnvelope) -> Accepted:
        fingerprint = sha256_text(envelope.model_dump_json(exclude_none=False))
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            seen = connection.execute(
                "select job_id, request_fingerprint from ai_job_inbox where event_id = %s for update",
                (envelope.eventId,),
            ).fetchone()
            if seen:
                if seen["job_id"] != envelope.jobId or seen["request_fingerprint"] != fingerprint:
                    raise ConflictError("cancellation event conflict")
                return Accepted(replayed=True, jobId=envelope.jobId)
            job = connection.execute(
                """
                select workspace_key, request_revision, status, shared_execution_id
                from ai_jobs where job_id = %s for update
                """,
                (envelope.jobId,),
            ).fetchone()
            if job and job["workspace_key"] != envelope.workspaceKey:
                raise NotFoundError("job not found")
            if job and envelope.requestRevision < job["request_revision"]:
                raise ConflictError("stale cancellation revision")
            connection.execute(
                "insert into ai_job_inbox (event_id, job_id, event_type, request_fingerprint, received_at) values (%s, %s, 'JOB_CANCELLED', %s, %s)",
                (envelope.eventId, envelope.jobId, fingerprint, now),
            )
            connection.execute(
                """
                insert into ai_cancellation_tombstones (
                    job_id, workspace_key, request_revision, event_id, request_fingerprint, cancelled_at
                ) values (%s, %s, %s, %s, %s, %s)
                on conflict (job_id) do update set
                    request_revision = greatest(ai_cancellation_tombstones.request_revision, excluded.request_revision),
                    event_id = case when excluded.request_revision >= ai_cancellation_tombstones.request_revision then excluded.event_id else ai_cancellation_tombstones.event_id end,
                    request_fingerprint = case when excluded.request_revision >= ai_cancellation_tombstones.request_revision then excluded.request_fingerprint else ai_cancellation_tombstones.request_fingerprint end,
                    cancelled_at = case when excluded.request_revision >= ai_cancellation_tombstones.request_revision then excluded.cancelled_at else ai_cancellation_tombstones.cancelled_at end
                """,
                (envelope.jobId, envelope.workspaceKey, envelope.requestRevision, envelope.eventId, fingerprint, now),
            )
            if job and job["status"] not in {"SUCCEEDED", "NEEDS_REVIEW", "FAILED", "CANCELLED", "SUPERSEDED", "EXPIRED"}:
                connection.execute(
                    """
                    update ai_jobs set status = 'CANCELLED', phase = 'COMPLETE', cancel_requested = true,
                        request_revision = greatest(request_revision, %s), completed_at = %s, updated_at = %s,
                        lease_owner = null, lease_expires_at = null
                    where job_id = %s
                    """,
                    (envelope.requestRevision, now, now, envelope.jobId),
                )
            elif job:
                connection.execute(
                    """
                    update ai_jobs set cancel_requested = true,
                        request_revision = greatest(request_revision, %s), updated_at = %s
                    where job_id = %s
                    """,
                    (envelope.requestRevision, now, envelope.jobId),
                )
            if job:
                connection.execute(
                    """
                    update ai_result_cache set invalidated_at = coalesce(invalidated_at, %s),
                        invalidation_reason = coalesce(invalidation_reason, 'ORIGIN_CANCELLED')
                    where origin_job_id = %s
                    """,
                    (now, envelope.jobId),
                )
                if job["shared_execution_id"] is not None:
                    self._end_shared_consumer(
                        connection,
                        envelope.jobId,
                        job["shared_execution_id"],
                        now,
                        "CANCELLED",
                        "ALL_CONSUMERS_CANCELLED",
                    )
        return Accepted(replayed=False, jobId=envelope.jobId)

    def get_job(self, job_id: UUID, include_result: bool = True) -> JobReceipt:
        now = datetime.now(UTC)
        with self.database.connection() as connection:
            row = connection.execute(
                """
                select job.*,
                       exists (
                           select 1 from ai_provider_calls call
                           where call.job_id = job.job_id
                             and call.lifecycle_status in ('DISPATCHING', 'RESPONDED', 'UNKNOWN')
                       ) as provider_dispatched
                from ai_jobs job where job.job_id = %s
                """,
                (job_id,),
            ).fetchone()
        if not row:
            raise NotFoundError("job not found")
        result = None
        result_available = (
            include_result
            and row["status"] == "SUCCEEDED"
            and row["result_ciphertext"] is not None
            and not row["cancel_requested"]
            and (row["result_expires_at"] is None or row["result_expires_at"] > now)
        )
        if result_available:
            plain = self.cipher.decrypt(
                bytes(row["result_ciphertext"]), bytes(row["result_nonce"]), str(job_id).encode()
            )
            result = json.loads(plain)
        return JobReceipt(
            jobId=row["job_id"],
            feature=Feature(row["feature"]),
            status=JobStatus(row["status"]),
            phase=JobPhase(row["phase"]),
            generation=row["generation"],
            leaseEpoch=row["lease_epoch"],
            requestRevision=row["request_revision"],
            createdAt=row["created_at"],
            deadlineAt=row["deadline_at"],
            completedAt=row["completed_at"],
            resultExpiresAt=row["result_expires_at"],
            cancelRequested=row["cancel_requested"],
            contextRevision=row["context_revision"],
            contextPolicyVersion=row["context_policy_version"],
            inputScope=row["input_scope"],
            stale=row["status"] == "SUPERSEDED",
            canInsert=(
                result_available
                and row["status"] == "SUCCEEDED"
                and row["feature"] == Feature.REPLY_DRAFT.value
            ),
            errorCode=row["error_code"],
            result=result,
            provenance=(
                GenerationProvenance(
                    modelAlias=row["model_alias"],
                    actualModel=row["actual_model"],
                    promptVersion=row["prompt_version"],
                    configVersion=row["config_version"],
                    generatedAt=row["generated_at"],
                    publicCommentIds=row["source_comment_ids"],
                    contextRevision=row["context_revision"],
                )
                if row["actual_model"] is not None
                else None
            ),
            costMicrousd=row["cost_microusd"],
            generationMode=(GenerationMode(row["generation_mode"]) if row["generation_mode"] else None),
            candidateSequence=row["candidate_sequence"],
            reuseKind=(
                "CACHE_HIT" if row["reuse_kind"] == "EXACT_CACHE_HIT" else row["reuse_kind"]
            ),
            providerDispatched=row["provider_dispatched"],
        )

    def claim_dispatch(self, owner: str, limit: int = 20) -> list[DispatchEvent]:
        now = datetime.now(UTC)
        lease_until = now + timedelta(seconds=self.settings.lease_seconds)
        with self.database.transaction() as connection:
            rows = connection.execute(
                """
                select outbox.event_id, outbox.job_id, outbox.generation, job.traceparent, job.tracestate
                from ai_dispatch_outbox outbox
                join ai_jobs job on job.job_id = outbox.job_id
                where (outbox.status = 'PENDING' and outbox.available_at <= %s)
                   or (outbox.status = 'LEASED' and outbox.lease_expires_at <= %s)
                order by outbox.created_at, outbox.event_id
                for update skip locked
                limit %s
                """,
                (now, now, limit),
            ).fetchall()
            for row in rows:
                connection.execute(
                    """
                    update ai_dispatch_outbox
                    set status = 'LEASED', lease_owner = %s, lease_expires_at = %s, attempts = attempts + 1
                    where event_id = %s
                    """,
                    (owner, lease_until, row["event_id"]),
                )
        return [DispatchEvent(**row) for row in rows]

    def mark_dispatched(self, event_id: UUID, owner: str) -> None:
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            updated = connection.execute(
                """
                update ai_dispatch_outbox set status = 'DELIVERED', delivered_at = %s,
                    lease_owner = null, lease_expires_at = null
                where event_id = %s and status = 'LEASED' and lease_owner = %s
                """,
                (now, event_id, owner),
            ).rowcount
            if updated != 1:
                raise StaleLeaseError("dispatch lease lost")

    def release_dispatch(self, event_id: UUID, owner: str, error_code: str) -> None:
        with self.database.transaction() as connection:
            connection.execute(
                """
                update ai_dispatch_outbox set status = case when attempts >= %s then 'DEAD' else 'PENDING' end,
                    available_at = clock_timestamp() + make_interval(secs => least(60, power(2, attempts)::int)),
                    lease_owner = null, lease_expires_at = null, last_error_code = %s
                where event_id = %s and status = 'LEASED' and lease_owner = %s
                """,
                (self.settings.max_attempts, error_code[:80], event_id, owner),
            )

    def requeue_stranded_dispatches(self) -> int:
        """Re-publishes durable intents after Redis data loss or a publish/mark gap."""
        with self.database.transaction() as connection:
            return connection.execute(
                """
                update ai_dispatch_outbox outbox
                set status = 'PENDING', available_at = clock_timestamp(), delivered_at = null,
                    lease_owner = null, lease_expires_at = null, last_error_code = 'STRANDED_REQUEUE'
                from ai_jobs job
                where job.job_id = outbox.job_id
                  and job.generation = outbox.generation
                  and job.status in ('QUEUED', 'RETRY_WAIT')
                  and outbox.status = 'DELIVERED'
                  and outbox.delivered_at <= clock_timestamp() - make_interval(secs => %s)
                """,
                (self.settings.lease_seconds,),
            ).rowcount

    def renew_lease(self, claim: ClaimedJob) -> None:
        with self.database.transaction() as connection:
            updated = connection.execute(
                """
                update ai_jobs set lease_expires_at = clock_timestamp() + make_interval(secs => %s),
                    updated_at = clock_timestamp()
                where job_id = %s and generation = %s and lease_epoch = %s
                  and status = 'RUNNING' and lease_owner = %s
                """,
                (
                    self.settings.lease_seconds,
                    claim.job_id,
                    claim.generation,
                    claim.lease_epoch,
                    self.settings.consumer_name,
                ),
            ).rowcount
            if updated != 1:
                raise StaleLeaseError("job lease lost during heartbeat")

    def claim_job(self, job_id: UUID, generation: int, owner: str) -> ClaimedJob | None:
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            row = connection.execute("select * from ai_jobs where job_id = %s for update", (job_id,)).fetchone()
            if not row or row["generation"] != generation:
                return None
            if row["status"] in {"SUCCEEDED", "NEEDS_REVIEW", "FAILED", "CANCELLED", "SUPERSEDED", "EXPIRED"}:
                return None
            if row["deadline_at"] <= now:
                connection.execute(
                    "update ai_jobs set status = 'EXPIRED', phase = 'COMPLETE', completed_at = %s, updated_at = %s where job_id = %s",
                    (now, now, job_id),
                )
                if row["shared_execution_id"] is not None:
                    self._end_shared_consumer(
                        connection,
                        job_id,
                        row["shared_execution_id"],
                        now,
                        "FAILED",
                        "ALL_CONSUMERS_EXPIRED",
                    )
                return None
            if row["status"] == "RUNNING" and row["lease_expires_at"] and row["lease_expires_at"] > now:
                raise ActiveLeaseError("job is still owned by an active worker")
            lease_epoch = row["lease_epoch"] + 1
            connection.execute(
                """
                update ai_jobs set status = 'RUNNING', phase = 'AUTHORIZE', lease_epoch = %s,
                    lease_owner = %s, lease_expires_at = %s, attempt_count = attempt_count + 1,
                    started_at = coalesce(started_at, %s), updated_at = %s
                where job_id = %s
                """,
                (lease_epoch, owner, now + timedelta(seconds=self.settings.lease_seconds), now, now, job_id),
            )
        return ClaimedJob(
            job_id=job_id,
            generation=generation,
            lease_epoch=lease_epoch,
            feature=Feature(row["feature"]),
            workspace_key=row["workspace_key"],
            requester_id=row["requester_id"],
            ticket_id=row["ticket_id"],
            context_revision=row["context_revision"],
            context_policy_version=row["context_policy_version"],
            ai_input_revision=row["ai_input_revision"],
            input_policy_version=row["input_policy_version"],
            generation_mode=(GenerationMode(row["generation_mode"]) if row["generation_mode"] else None),
            candidate_id=row["candidate_id"],
            candidate_sequence=row["candidate_sequence"],
            traceparent=row["traceparent"],
            deadline_at=row["deadline_at"],
            options=row["options_json"],
        )

    def set_phase(self, claim: ClaimedJob, phase: JobPhase) -> None:
        with self.database.transaction() as connection:
            updated = connection.execute(
                """
                update ai_jobs set phase = %s, updated_at = clock_timestamp()
                where job_id = %s and generation = %s and lease_epoch = %s and status = 'RUNNING'
                """,
                (phase.value, claim.job_id, claim.generation, claim.lease_epoch),
            ).rowcount
            if updated != 1:
                raise StaleLeaseError("job lease lost")

    def claim_shared_execution(
        self,
        claim: ClaimedJob,
        execution_key: str,
        key_version: str,
    ) -> SharedExecutionClaim:
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            connection.execute(
                "select pg_advisory_xact_lock(hashtext('ai-shared-execution'), hashtext(%s))",
                (execution_key,),
            )
            job = connection.execute(
                """
                select shared_execution_id, status from ai_jobs
                where job_id = %s and generation = %s and lease_epoch = %s for update
                """,
                (claim.job_id, claim.generation, claim.lease_epoch),
            ).fetchone()
            if not job or job["status"] != "RUNNING":
                raise StaleLeaseError("job lease lost before shared execution claim")
            if job["shared_execution_id"] is not None:
                existing = connection.execute(
                    "select * from ai_shared_executions where execution_id = %s for update",
                    (job["shared_execution_id"],),
                ).fetchone()
                if existing is None:
                    raise ConflictError("shared execution binding is missing")
                if existing["execution_key"] != execution_key or existing["key_version"] != key_version:
                    return self._shared_claim(existing, "KEY_MISMATCH")
                if existing["status"] != "RUNNING":
                    return self._shared_claim(existing, "TERMINAL")
                if existing["representative_job_id"] == claim.job_id:
                    connection.execute(
                        """
                        update ai_shared_executions set lease_epoch = %s, updated_at = %s
                        where execution_id = %s and status = 'RUNNING'
                        """,
                        (claim.lease_epoch, now, existing["execution_id"]),
                    )
                    existing["lease_epoch"] = claim.lease_epoch
                    return self._shared_claim(existing, "LEADER")
                self._defer_shared_waiter(connection, claim, existing["execution_id"], now)
                return self._shared_claim(existing, "WAITING")
            existing = connection.execute(
                """
                select * from ai_shared_executions
                where execution_key = %s and status = 'RUNNING' for update
                """,
                (execution_key,),
            ).fetchone()
            if existing is None:
                execution_id = uuid4()
                connection.execute(
                    """
                    insert into ai_shared_executions (
                        execution_id, execution_key, key_version, workspace_key, requester_id,
                        ticket_id, feature, representative_job_id, status, phase,
                        execution_generation, lease_epoch, provider_dispatched,
                        created_at, updated_at
                    ) values (
                        %s, %s, %s, %s, %s, %s, %s, %s,
                        'RUNNING', 'READY', 1, %s, false, %s, %s
                    )
                    """,
                    (
                        execution_id,
                        execution_key,
                        key_version,
                        claim.workspace_key,
                        claim.requester_id,
                        claim.ticket_id,
                        claim.feature.value,
                        claim.job_id,
                        claim.lease_epoch,
                        now,
                        now,
                    ),
                )
                connection.execute(
                    """
                    insert into ai_execution_consumers (
                        execution_id, job_id, state, joined_at
                    ) values (%s, %s, 'REPRESENTATIVE', %s)
                    """,
                    (execution_id, claim.job_id, now),
                )
                connection.execute(
                    "update ai_jobs set shared_execution_id = %s, updated_at = %s where job_id = %s",
                    (execution_id, now, claim.job_id),
                )
                return SharedExecutionClaim(
                    execution_id,
                    execution_key,
                    key_version,
                    1,
                    "LEADER",
                    "RUNNING",
                    None,
                )
            scope_matches = (
                existing["key_version"] == key_version
                and existing["workspace_key"] == claim.workspace_key
                and existing["requester_id"] == claim.requester_id
                and existing["ticket_id"] == claim.ticket_id
                and existing["feature"] == claim.feature.value
            )
            if not scope_matches:
                raise ConflictError("shared execution scope mismatch")
            connection.execute(
                """
                insert into ai_execution_consumers (execution_id, job_id, state, joined_at)
                values (%s, %s, 'WAITING', %s)
                on conflict (job_id) do nothing
                """,
                (existing["execution_id"], claim.job_id, now),
            )
            connection.execute(
                "update ai_jobs set shared_execution_id = %s where job_id = %s",
                (existing["execution_id"], claim.job_id),
            )
            self._defer_shared_waiter(connection, claim, existing["execution_id"], now)
            return self._shared_claim(existing, "WAITING")

    def mark_shared_provider_stage(
        self,
        claim: ClaimedJob,
        shared: SharedExecutionClaim,
        phase: str,
    ) -> None:
        shared_phase = "GENERATION" if phase.startswith("GENERATION") else phase
        if shared_phase not in {"QUERY_EMBEDDING", "CONTEXT_MEMORY", "GENERATION"}:
            raise ValueError("unsupported shared provider phase")
        with self.database.transaction() as connection:
            updated = connection.execute(
                """
                update ai_shared_executions execution
                set phase = %s, provider_dispatched = true, updated_at = clock_timestamp()
                where execution.execution_id = %s and execution.status = 'RUNNING'
                  and execution.representative_job_id = %s
                  and execution.execution_generation = %s and execution.lease_epoch = %s
                  and exists (
                      select 1 from ai_jobs job
                      where job.job_id = %s and job.generation = %s and job.lease_epoch = %s
                        and job.status = 'RUNNING'
                  )
                """,
                (
                    shared_phase,
                    shared.execution_id,
                    claim.job_id,
                    shared.execution_generation,
                    claim.lease_epoch,
                    claim.job_id,
                    claim.generation,
                    claim.lease_epoch,
                ),
            ).rowcount
            if updated != 1:
                raise StaleLeaseError("shared execution lease lost before provider dispatch")

    def record_reply_route(
        self,
        claim: ClaimedJob,
        *,
        decision: str,
        cohort: str | None,
        policy_version: str,
        marker_version: str,
        rollout_percent: int,
        requested_alias: str,
    ) -> None:
        if decision not in {"STANDARD", "LOW_COST"}:
            raise ValueError("invalid reply route decision")
        with self.database.transaction() as connection:
            updated = connection.execute(
                """
                update ai_jobs set
                    reply_route_decision = %s,
                    reply_route_cohort = %s,
                    reply_route_policy_version = %s,
                    reply_route_marker_version = %s,
                    reply_route_rollout_percent = %s,
                    reply_route_requested_alias = %s,
                    reply_route_escalation_reason = null,
                    updated_at = clock_timestamp()
                where job_id = %s and generation = %s and lease_epoch = %s
                  and status = 'RUNNING'
                """,
                (
                    decision,
                    cohort,
                    policy_version,
                    marker_version,
                    rollout_percent,
                    requested_alias,
                    claim.job_id,
                    claim.generation,
                    claim.lease_epoch,
                ),
            ).rowcount
            if updated != 1:
                raise StaleLeaseError("job lease lost before reply route recording")

    def record_reply_escalation(
        self,
        claim: ClaimedJob,
        *,
        reason: str,
        requested_alias: str,
    ) -> None:
        if reason not in {"PROVIDER_OUTPUT_INVALID", "REPLY_VALIDATION_FAILED"}:
            raise ValueError("invalid reply escalation reason")
        with self.database.transaction() as connection:
            updated = connection.execute(
                """
                update ai_jobs set
                    reply_route_decision = 'ESCALATED',
                    reply_route_requested_alias = %s,
                    reply_route_escalation_reason = %s,
                    updated_at = clock_timestamp()
                where job_id = %s and generation = %s and lease_epoch = %s
                  and status = 'RUNNING'
                  and reply_route_decision = 'LOW_COST'
                  and reply_route_escalation_reason is null
                """,
                (
                    requested_alias,
                    reason,
                    claim.job_id,
                    claim.generation,
                    claim.lease_epoch,
                ),
            ).rowcount
            if updated != 1:
                raise StaleLeaseError("reply escalation is stale or already recorded")

    def finish_shared_terminal_consumer(
        self,
        claim: ClaimedJob,
        shared: SharedExecutionClaim,
    ) -> None:
        if shared.disposition == "KEY_MISMATCH":
            self.terminate_job(claim, JobStatus.SUPERSEDED, "SHARED_EXECUTION_SUPERSEDED")
            return
        if shared.status == "NEEDS_REVIEW":
            self.complete_needs_review(claim, shared.terminal_reason or "SHARED_NEEDS_REVIEW", 0)
            return
        reason = shared.terminal_reason or (
            "PROVIDER_OUTCOME_UNKNOWN" if shared.status == "UNKNOWN" else "SHARED_EXECUTION_UNAVAILABLE"
        )
        self.terminate_job(claim, JobStatus.FAILED, reason)

    @staticmethod
    def _shared_claim(row, disposition: str) -> SharedExecutionClaim:
        return SharedExecutionClaim(
            execution_id=row["execution_id"],
            execution_key=row["execution_key"],
            key_version=row["key_version"],
            execution_generation=row["execution_generation"],
            disposition=disposition,
            status=row["status"],
            terminal_reason=row["terminal_reason"],
        )

    @staticmethod
    def _defer_shared_waiter(connection, claim: ClaimedJob, execution_id: UUID, now: datetime) -> None:
        updated = connection.execute(
            """
            update ai_jobs set status = 'RETRY_WAIT', phase = 'QUEUED', generation = generation + 1,
                lease_owner = null, lease_expires_at = null, updated_at = %s
            where job_id = %s and generation = %s and lease_epoch = %s and status = 'RUNNING'
              and shared_execution_id = %s
            """,
            (now, claim.job_id, claim.generation, claim.lease_epoch, execution_id),
        ).rowcount
        if updated != 1:
            raise StaleLeaseError("shared execution waiter lease lost")

    def _end_shared_consumer(
        self,
        connection,
        job_id: UUID,
        execution_id: UUID,
        now: datetime,
        consumer_state: str,
        empty_reason: str,
    ) -> None:
        if consumer_state not in {"CANCELLED", "FAILED"}:
            raise ValueError("unsupported shared consumer terminal state")
        connection.execute(
            """
            update ai_execution_consumers set state = %s, completed_at = %s
            where execution_id = %s and job_id = %s
              and state in ('REPRESENTATIVE', 'WAITING')
            """,
            (consumer_state, now, execution_id, job_id),
        )
        execution = connection.execute(
            "select * from ai_shared_executions where execution_id = %s for update",
            (execution_id,),
        ).fetchone()
        if (
            execution is None
            or execution["status"] != "RUNNING"
            or execution["representative_job_id"] != job_id
        ):
            return
        if execution["provider_dispatched"]:
            connection.execute(
                """
                update ai_provider_calls set lifecycle_status = 'UNKNOWN',
                    settlement_status = case
                        when settlement_status = 'CONFLICT' then 'CONFLICT' else 'UNKNOWN'
                    end, updated_at = %s
                where execution_id = %s and receipt_fingerprint is null
                  and lifecycle_status in ('RESERVED', 'DISPATCHING')
                """,
                (now, execution_id),
            )
            connection.execute(
                """
                update ai_cost_ledger set status = 'UNKNOWN', unknown_since = coalesce(unknown_since, %s)
                where execution_id = %s and status = 'RESERVED'
                """,
                (now, execution_id),
            )
            connection.execute(
                """
                update ai_shared_executions set status = 'UNKNOWN', phase = 'COMPLETE',
                    terminal_reason = 'PROVIDER_OUTCOME_UNKNOWN', completed_at = %s, updated_at = %s
                where execution_id = %s and status = 'RUNNING'
                """,
                (now, now, execution_id),
            )
            self._wake_shared_waiters(connection, execution_id, now)
            return
        successor = connection.execute(
            """
            select consumer.job_id, job.generation
            from ai_execution_consumers consumer
            join ai_jobs job on job.job_id = consumer.job_id
            where consumer.execution_id = %s and consumer.state = 'WAITING'
              and job.status = 'RETRY_WAIT' and not job.cancel_requested and job.deadline_at > %s
            order by consumer.joined_at, consumer.job_id
            for update of consumer, job skip locked
            limit 1
            """,
            (execution_id, now),
        ).fetchone()
        if successor is None:
            connection.execute(
                """
                update ai_shared_executions set status = 'CANCELLED', phase = 'COMPLETE',
                    terminal_reason = %s, completed_at = %s, updated_at = %s
                where execution_id = %s and status = 'RUNNING'
                """,
                (empty_reason, now, now, execution_id),
            )
            return
        connection.execute(
            """
            update ai_execution_consumers set state = 'REPRESENTATIVE', woken_at = %s
            where execution_id = %s and job_id = %s and state = 'WAITING'
            """,
            (now, execution_id, successor["job_id"]),
        )
        connection.execute(
            """
            update ai_shared_executions set representative_job_id = %s,
                execution_generation = execution_generation + 1, lease_epoch = 0,
                phase = 'READY', provider_dispatched = false, updated_at = %s
            where execution_id = %s and status = 'RUNNING' and representative_job_id = %s
            """,
            (successor["job_id"], now, execution_id, job_id),
        )
        self._enqueue_job_dispatch(connection, successor["job_id"], successor["generation"], now)

    @staticmethod
    def _enqueue_job_dispatch(connection, job_id: UUID, generation: int, now: datetime) -> None:
        connection.execute(
            """
            insert into ai_dispatch_outbox (
                event_id, job_id, generation, status, attempts, available_at, created_at
            ) values (%s, %s, %s, 'PENDING', 0, %s, %s)
            on conflict (job_id, generation) do nothing
            """,
            (uuid4(), job_id, generation, now, now),
        )

    def _wake_shared_waiters(self, connection, execution_id: UUID, now: datetime) -> None:
        expired = connection.execute(
            """
            update ai_jobs job set status = 'EXPIRED', phase = 'COMPLETE',
                error_code = 'DEADLINE_EXCEEDED', completed_at = %s, updated_at = %s
            from ai_execution_consumers consumer
            where consumer.execution_id = %s and consumer.job_id = job.job_id
              and consumer.state = 'WAITING' and job.status = 'RETRY_WAIT'
              and job.deadline_at <= %s
            returning job.job_id
            """,
            (now, now, execution_id, now),
        ).fetchall()
        if expired:
            connection.execute(
                """
                update ai_execution_consumers set state = 'FAILED', completed_at = %s
                where execution_id = %s and job_id = any(%s)
                """,
                (now, execution_id, [row["job_id"] for row in expired]),
            )
        waiters = connection.execute(
            """
            select consumer.job_id, job.generation
            from ai_execution_consumers consumer
            join ai_jobs job on job.job_id = consumer.job_id
            where consumer.execution_id = %s and consumer.state = 'WAITING'
              and job.status = 'RETRY_WAIT' and not job.cancel_requested and job.deadline_at > %s
            order by consumer.joined_at, consumer.job_id
            for update of consumer, job
            """,
            (execution_id, now),
        ).fetchall()
        for waiter in waiters:
            self._enqueue_job_dispatch(connection, waiter["job_id"], waiter["generation"], now)
        if waiters:
            connection.execute(
                """
                update ai_execution_consumers set woken_at = coalesce(woken_at, %s)
                where execution_id = %s and state = 'WAITING'
                """,
                (now, execution_id),
            )

    def _finish_shared_execution(
        self,
        connection,
        claim: ClaimedJob,
        execution_status: str,
        terminal_reason: str | None,
        consumer_state: str,
        now: datetime,
    ) -> None:
        job = connection.execute(
            "select shared_execution_id from ai_jobs where job_id = %s",
            (claim.job_id,),
        ).fetchone()
        if job is None or job["shared_execution_id"] is None:
            return
        execution_id = job["shared_execution_id"]
        execution = connection.execute(
            "select * from ai_shared_executions where execution_id = %s for update",
            (execution_id,),
        ).fetchone()
        if execution is None:
            raise ConflictError("shared execution binding is missing")
        if execution["status"] == "RUNNING":
            if execution["representative_job_id"] != claim.job_id:
                raise ConflictError("only the representative may finish a shared execution")
            updated = connection.execute(
                """
                update ai_shared_executions set status = %s, phase = 'COMPLETE',
                    terminal_reason = %s, completed_at = %s, updated_at = %s
                where execution_id = %s and status = 'RUNNING'
                  and representative_job_id = %s and execution_generation = %s
                  and lease_epoch = %s
                """,
                (
                    execution_status,
                    terminal_reason,
                    now,
                    now,
                    execution_id,
                    claim.job_id,
                    execution["execution_generation"],
                    claim.lease_epoch,
                ),
            ).rowcount
            if updated != 1:
                raise StaleLeaseError("shared execution completion lease lost")
            self._wake_shared_waiters(connection, execution_id, now)
        connection.execute(
            """
            update ai_execution_consumers set state = %s, completed_at = coalesce(completed_at, %s)
            where execution_id = %s and job_id = %s
              and state in ('REPRESENTATIVE', 'WAITING')
            """,
            (consumer_state, now, execution_id, claim.job_id),
        )

    def reserve_budget(
        self,
        claim: ClaimedJob,
        model_alias: str,
        pricing_version: str,
        reserve_microusd: int,
        call_type: str,
        shared: SharedExecutionClaim | None = None,
    ) -> UUID:
        operation_key = (
            f"{shared.execution_id}:{shared.execution_generation}:{call_type}"
            if shared is not None
            else f"{claim.job_id}:{claim.generation}:{call_type}"
        )
        reservation = self._reserve_budget(
            workspace_key=claim.workspace_key,
            requester_id=claim.requester_id,
            job_id=claim.job_id,
            execution_id=shared.execution_id if shared is not None else None,
            operation_key=operation_key,
            budget_bucket="ACTOR",
            model_alias=model_alias,
            pricing_version=pricing_version,
            reserve_microusd=reserve_microusd,
            call_type=call_type,
        )
        if reservation is None:
            raise ProviderCallStateUnknownError(
                "an earlier provider call with this operation key may already have started"
            )
        return reservation

    def reserve_system_budget(
        self,
        workspace_key: str,
        operation_key: str,
        model_alias: str,
        pricing_version: str,
        reserve_microusd: int,
    ) -> UUID:
        reservation = self._reserve_budget(
            workspace_key=workspace_key,
            requester_id=None,
            job_id=None,
            execution_id=None,
            operation_key=operation_key,
            budget_bucket="SYSTEM",
            model_alias=model_alias,
            pricing_version=pricing_version,
            reserve_microusd=reserve_microusd,
            call_type="INDEX_EMBEDDING",
        )
        if reservation is None:
            raise ProviderCallStateUnknownError(
                "an earlier provider call with this operation key may already have started"
            )
        return reservation

    def _reserve_budget(
        self,
        *,
        workspace_key: str,
        requester_id: UUID | None,
        job_id: UUID | None,
        execution_id: UUID | None,
        operation_key: str,
        budget_bucket: str,
        model_alias: str,
        pricing_version: str,
        reserve_microusd: int,
        call_type: str,
    ) -> UUID | None:
        now = datetime.now(UTC)
        budget_day = now.date()
        if reserve_microusd <= 0 or reserve_microusd > self.settings.job_budget_microusd:
            raise ValueError("budget reservation is outside the per-call bound")
        with self.database.transaction() as connection:
            connection.execute("select pg_advisory_xact_lock(hashtext(%s), hashtext(%s))", (workspace_key, str(budget_day)))
            existing = connection.execute(
                "select reservation_id, status from ai_cost_ledger where operation_key = %s for update",
                (operation_key,),
            ).fetchone()
            if existing:
                if existing["status"] == "RESERVED":
                    connection.execute(
                        """
                        update ai_cost_ledger
                        set status = 'UNKNOWN', unknown_since = coalesce(unknown_since, clock_timestamp())
                        where reservation_id = %s and status = 'RESERVED'
                        """,
                        (existing["reservation_id"],),
                    )
                    connection.execute(
                        """
                        update ai_provider_calls
                        set lifecycle_status = 'UNKNOWN',
                            settlement_status = case
                                when settlement_status = 'CONFLICT' then 'CONFLICT' else 'UNKNOWN'
                            end,
                            updated_at = clock_timestamp()
                        where reservation_id = %s and lifecycle_status in ('RESERVED', 'DISPATCHING')
                        """,
                        (existing["reservation_id"],),
                    )
                return None
            workspace_spend = connection.execute(
                """
                select coalesce(sum(case when status = 'SETTLED' then settled_microusd else reserved_microusd end), 0) as total
                from ai_cost_ledger
                where workspace_key = %s
                  and ((budget_date = %s and status in ('RESERVED', 'SETTLED', 'UNKNOWN'))
                    or status = 'UNKNOWN')
                """,
                (workspace_key, budget_day),
            ).fetchone()["total"]
            actor_spend = 0
            job_spend = 0
            if requester_id is not None:
                actor_spend = connection.execute(
                    """
                    select coalesce(sum(case when status = 'SETTLED' then settled_microusd else reserved_microusd end), 0) as total
                    from ai_cost_ledger
                    where workspace_key = %s and requester_id = %s
                      and ((budget_date = %s and status in ('RESERVED', 'SETTLED', 'UNKNOWN'))
                        or status = 'UNKNOWN')
                    """,
                    (workspace_key, requester_id, budget_day),
                ).fetchone()["total"]
            if job_id is not None:
                job_spend = connection.execute(
                    """
                    select coalesce(sum(case when status = 'SETTLED' then settled_microusd else reserved_microusd end), 0) as total
                    from ai_cost_ledger where job_id = %s and status in ('RESERVED', 'SETTLED', 'UNKNOWN')
                    """,
                    (job_id,),
                ).fetchone()["total"]
            if workspace_spend + reserve_microusd > self.settings.workspace_daily_budget_microusd:
                raise BudgetExceededError("workspace daily budget exceeded")
            if requester_id is not None and actor_spend + reserve_microusd > self.settings.actor_daily_budget_microusd:
                raise BudgetExceededError("actor daily budget exceeded")
            if job_id is not None and job_spend + reserve_microusd > self.settings.job_budget_microusd:
                raise BudgetExceededError("job budget exceeded")
            reservation_id = uuid4()
            connection.execute(
                """
                insert into ai_cost_ledger (
                    reservation_id, operation_key, job_id, execution_id, workspace_key, requester_id,
                    budget_bucket, call_type, budget_date, status, reserved_microusd,
                    pricing_version, model_alias, created_at
                ) values (%s, %s, %s, %s, %s, %s, %s, %s, %s, 'RESERVED', %s, %s, %s, %s)
                """,
                (
                    reservation_id,
                    operation_key,
                    job_id,
                    execution_id,
                    workspace_key,
                    requester_id,
                    budget_bucket,
                    call_type,
                    budget_day,
                    reserve_microusd,
                    pricing_version,
                    model_alias,
                    now,
                ),
            )
        return reservation_id

    def settle_budget(self, reservation_id: UUID, actual_microusd: int) -> None:
        if actual_microusd < 0:
            raise ValueError("actual cost cannot be negative")
        with self.database.transaction() as connection:
            updated = connection.execute(
                """
                update ai_cost_ledger set status = 'SETTLED', settled_microusd = %s,
                    overrun_microusd = greatest(0, %s - reserved_microusd),
                    settled_at = clock_timestamp(), unknown_since = null
                where reservation_id = %s and status in ('RESERVED', 'UNKNOWN')
                """,
                (actual_microusd, actual_microusd, reservation_id),
            ).rowcount
            if updated == 0:
                existing = connection.execute(
                    "select status, settled_microusd from ai_cost_ledger where reservation_id = %s",
                    (reservation_id,),
                ).fetchone()
                if not existing or existing["status"] != "SETTLED" or existing["settled_microusd"] != actual_microusd:
                    raise ValueError("actual cost conflicts with the existing settlement")

    def create_provider_call(
        self,
        reservation_id: UUID,
        call_id: UUID,
        requested_alias: str,
        pricing_version: str,
        service_tier: str,
        context_price_band: str,
    ) -> None:
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            reservation = connection.execute(
                """
                select operation_key, job_id, execution_id, call_type, model_alias, pricing_version, status
                from ai_cost_ledger where reservation_id = %s for update
                """,
                (reservation_id,),
            ).fetchone()
            if not reservation or reservation["status"] != "RESERVED":
                raise ProviderCallStateUnknownError("provider call reservation is not dispatchable")
            if reservation["model_alias"] != requested_alias or reservation["pricing_version"] != pricing_version:
                raise ValueError("provider call does not match its reservation")
            existing = connection.execute(
                "select * from ai_provider_calls where reservation_id = %s or call_id = %s for update",
                (reservation_id, call_id),
            ).fetchone()
            if existing:
                expected = (
                    existing["call_id"] == call_id
                    and existing["requested_alias"] == requested_alias
                    and existing["pricing_version"] == pricing_version
                    and existing["service_tier"] == service_tier
                    and existing["context_price_band"] == context_price_band
                )
                if not expected:
                    raise ProviderReceiptConflictError("provider call identity conflicts with existing call")
                return
            connection.execute(
                """
                insert into ai_provider_calls (
                    call_id, reservation_id, operation_key, job_id, execution_id, stage,
                    lifecycle_status, settlement_status, requested_alias, pricing_version,
                    service_tier, context_price_band, created_at, updated_at
                ) values (%s, %s, %s, %s, %s, %s, 'RESERVED', 'PENDING', %s, %s, %s, %s, %s, %s)
                """,
                (
                    call_id,
                    reservation_id,
                    reservation["operation_key"],
                    reservation["job_id"],
                    reservation["execution_id"],
                    reservation["call_type"],
                    requested_alias,
                    pricing_version,
                    service_tier,
                    context_price_band,
                    now,
                    now,
                ),
            )

    def mark_provider_call_dispatching(self, call_id: UUID) -> None:
        with self.database.transaction() as connection:
            updated = connection.execute(
                """
                update ai_provider_calls
                set lifecycle_status = 'DISPATCHING', dispatching_at = clock_timestamp(),
                    updated_at = clock_timestamp()
                where call_id = %s and lifecycle_status = 'RESERVED' and settlement_status = 'PENDING'
                """,
                (call_id,),
            ).rowcount
            if updated != 1:
                raise ProviderCallStateUnknownError("provider call is not dispatchable")

    def record_provider_response(
        self,
        receipt: ProviderCallReceipt,
        known_cost_microusd: int | None,
    ) -> int | None:
        if known_cost_microusd is not None and known_cost_microusd < 0:
            raise ValueError("known cost cannot be negative")
        canonical = {
            "callId": str(receipt.call_id),
            "providerRequestId": receipt.provider_request_id,
            "requestedAlias": receipt.requested_alias,
            "actualModel": receipt.actual_model,
            "usageSchemaVersion": receipt.usage_schema_version,
            "usageStatus": receipt.usage_status.value,
            "usage": None
            if receipt.usage is None
            else {
                "inputUncached": receipt.usage.input_uncached_tokens,
                "inputCacheRead": receipt.usage.input_cache_read_tokens,
                "inputCacheWrite": receipt.usage.input_cache_write_tokens,
                "outputBilled": receipt.usage.output_billed_tokens,
            },
            "usageIssueCode": receipt.usage_issue_code,
            "serviceTier": receipt.service_tier,
            "contextPriceBand": receipt.context_price_band,
        }
        fingerprint = sha256_text(json.dumps(canonical, sort_keys=True, separators=(",", ":")))
        now = datetime.now(UTC)
        with self.database.connection() as connection:
            call = connection.execute(
                """
                select provider_call.*, cost.reserved_microusd
                from ai_provider_calls provider_call
                join ai_cost_ledger cost on cost.reservation_id = provider_call.reservation_id
                where provider_call.call_id = %s for update of provider_call, cost
                """,
                (receipt.call_id,),
            ).fetchone()
            if not call:
                raise NotFoundError("provider call not found")
            if call["receipt_fingerprint"] is not None:
                if call["receipt_fingerprint"] == fingerprint:
                    return call["known_cost_microusd"]
                connection.execute(
                    """
                    update ai_provider_calls set settlement_status = 'CONFLICT', updated_at = %s
                    where call_id = %s
                    """,
                    (now, receipt.call_id),
                )
                connection.commit()
                raise ProviderReceiptConflictError("provider receipt conflicts with immutable facts")
            if call["lifecycle_status"] not in {"DISPATCHING", "UNKNOWN"}:
                raise ProviderCallStateUnknownError("provider response has no dispatch intent")
            if (
                call["requested_alias"] != receipt.requested_alias
                or call["service_tier"] != receipt.service_tier
                or call["context_price_band"] != receipt.context_price_band
            ):
                connection.execute(
                    """
                    update ai_provider_calls set settlement_status = 'CONFLICT', updated_at = %s
                    where call_id = %s
                    """,
                    (now, receipt.call_id),
                )
                connection.commit()
                raise ProviderReceiptConflictError("provider receipt does not match dispatch contract")
            usage = receipt.usage if receipt.usage_status == UsageStatus.KNOWN else None
            if receipt.usage_status == UsageStatus.KNOWN and usage is None:
                raise ValueError("known usage receipt has no usage buckets")
            if receipt.usage_status != UsageStatus.KNOWN:
                known_cost_microusd = None
            overrun = (
                max(0, known_cost_microusd - call["reserved_microusd"])
                if known_cost_microusd is not None
                else 0
            )
            settlement_status = "SETTLED" if known_cost_microusd is not None else "UNKNOWN"
            connection.execute(
                """
                update ai_provider_calls set
                    lifecycle_status = 'RESPONDED', settlement_status = %s,
                    actual_model = %s, provider_request_id = %s,
                    usage_schema_version = %s, usage_status = %s, usage_issue_code = %s,
                    input_uncached_tokens = %s, input_cache_read_tokens = %s,
                    input_cache_write_tokens = %s, output_billed_tokens = %s,
                    known_cost_microusd = %s, overrun_microusd = %s,
                    receipt_fingerprint = %s, responded_at = %s, updated_at = %s
                where call_id = %s
                """,
                (
                    settlement_status,
                    receipt.actual_model,
                    receipt.provider_request_id,
                    receipt.usage_schema_version,
                    receipt.usage_status.value,
                    receipt.usage_issue_code,
                    usage.input_uncached_tokens if usage else None,
                    usage.input_cache_read_tokens if usage else None,
                    usage.input_cache_write_tokens if usage else None,
                    usage.output_billed_tokens if usage else None,
                    known_cost_microusd,
                    overrun,
                    fingerprint,
                    now,
                    now,
                    receipt.call_id,
                ),
            )
            if known_cost_microusd is None:
                connection.execute(
                    """
                    update ai_cost_ledger set status = 'UNKNOWN',
                        unknown_since = coalesce(unknown_since, %s)
                    where reservation_id = %s and status in ('RESERVED', 'UNKNOWN')
                    """,
                    (now, call["reservation_id"]),
                )
            else:
                connection.execute(
                    """
                    update ai_cost_ledger set status = 'SETTLED', settled_microusd = %s,
                        overrun_microusd = %s, settled_at = %s, unknown_since = null
                    where reservation_id = %s and status in ('RESERVED', 'UNKNOWN')
                    """,
                    (known_cost_microusd, overrun, now, call["reservation_id"]),
                )
        return known_cost_microusd

    def mark_provider_call_unknown(self, call_id: UUID) -> None:
        with self.database.transaction() as connection:
            call = connection.execute(
                "select reservation_id, receipt_fingerprint from ai_provider_calls where call_id = %s for update",
                (call_id,),
            ).fetchone()
            if not call:
                return
            if call["receipt_fingerprint"] is not None:
                return
            connection.execute(
                """
                update ai_provider_calls
                set lifecycle_status = 'UNKNOWN',
                    settlement_status = case
                        when settlement_status = 'CONFLICT' then 'CONFLICT' else 'UNKNOWN'
                    end,
                    updated_at = clock_timestamp()
                where call_id = %s and receipt_fingerprint is null
                """,
                (call_id,),
            )
            connection.execute(
                """
                update ai_cost_ledger
                set status = 'UNKNOWN', unknown_since = coalesce(unknown_since, clock_timestamp())
                where reservation_id = %s and status = 'RESERVED'
                """,
                (call["reservation_id"],),
            )

    def job_cost_microusd(self, job_id: UUID) -> int | None:
        with self.database.connection() as connection:
            row = connection.execute(
                """
                select count(*) filter (where settlement_status != 'SETTLED') as unsettled,
                       coalesce(sum(known_cost_microusd), 0) as known_cost
                from ai_provider_calls where job_id = %s
                """,
                (job_id,),
            ).fetchone()
        return None if row["unsettled"] else row["known_cost"]

    def mark_budget_unknown(self, reservation_id: UUID) -> None:
        with self.database.transaction() as connection:
            connection.execute(
                """
                update ai_cost_ledger set status = 'UNKNOWN', unknown_since = coalesce(unknown_since, clock_timestamp())
                where reservation_id = %s and status = 'RESERVED'
                """,
                (reservation_id,),
            )

    def complete_job(
        self,
        claim: ClaimedJob,
        result: TypedResult,
        status: JobStatus,
        cost_microusd: int | None,
        actual_model: str,
        source_comment_ids: list[UUID],
        prompt_version: str,
        source_map_digest: str | None = None,
        source_chunk_ids: list[UUID] | None = None,
        cache_key: str | None = None,
    ) -> None:
        if (source_map_digest is None) != (source_chunk_ids is None):
            raise ValueError("source map digest and chunk IDs must be stored together")
        now = datetime.now(UTC)
        ciphertext, nonce = self.cipher.encrypt(
            result.model_dump_json().encode(), str(claim.job_id).encode()
        )
        result_expires_at = now + timedelta(days=7)
        with self.database.transaction() as connection:
            shared_binding = connection.execute(
                "select shared_execution_id from ai_jobs where job_id = %s",
                (claim.job_id,),
            ).fetchone()
            if (
                shared_binding
                and shared_binding["shared_execution_id"] is not None
                and (cache_key is None or cost_microusd is None)
            ):
                raise ConflictError("shared success requires an exact settled cache result")
            updated = connection.execute(
                """
                update ai_jobs set status = %s, phase = 'COMPLETE', result_schema_version = 1,
                    result_ciphertext = %s, result_nonce = %s, result_expires_at = %s,
                    model_alias = %s, actual_model = %s, prompt_version = %s,
                    config_version = %s, source_comment_ids = %s, generated_at = %s,
                    source_map_digest = %s, source_chunk_ids = %s,
                    cost_microusd = %s, completed_at = %s, updated_at = %s,
                    lease_owner = null, lease_expires_at = null, error_code = null,
                    reuse_kind = case when generation_mode is not null then 'GENERATED' else reuse_kind end
                where job_id = %s and generation = %s and lease_epoch = %s and status = 'RUNNING'
                """,
                (
                    status.value,
                    ciphertext,
                    nonce,
                    result_expires_at,
                    actual_model,
                    actual_model,
                    prompt_version,
                    self.settings.config_version,
                    source_comment_ids,
                    now,
                    source_map_digest,
                    source_chunk_ids,
                    cost_microusd,
                    now,
                    now,
                    claim.job_id,
                    claim.generation,
                    claim.lease_epoch,
                ),
            ).rowcount
            if updated != 1:
                raise StaleLeaseError("job completion lease lost")
            if cache_key is not None and status == JobStatus.SUCCEEDED and cost_microusd is not None:
                cache_expires_at = min(now + timedelta(hours=24), result_expires_at)
                key_version = (
                    "result-cache-reply-v1"
                    if claim.feature == Feature.REPLY_DRAFT
                    else "result-cache-summary-triage-v1"
                )
                connection.execute(
                    """
                    insert into ai_result_cache (
                        cache_key, key_version, workspace_key, requester_id, ticket_id, feature,
                        origin_job_id, created_at, expires_at
                    ) values (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                    on conflict (cache_key) do update set
                        origin_job_id = excluded.origin_job_id,
                        created_at = excluded.created_at,
                        expires_at = excluded.expires_at,
                        invalidated_at = null,
                        invalidation_reason = null
                    where ai_result_cache.invalidated_at is not null
                       or ai_result_cache.expires_at <= %s
                    """,
                    (
                        cache_key,
                        key_version,
                        claim.workspace_key,
                        claim.requester_id,
                        claim.ticket_id,
                        claim.feature.value,
                        claim.job_id,
                        now,
                        cache_expires_at,
                        now,
                    ),
                )
            self._finish_shared_execution(
                connection, claim, "SUCCEEDED", None, "COMPLETED", now
            )

    def complete_from_cache(self, claim: ClaimedJob, cache_key: str) -> bool:
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            entry_ref = connection.execute(
                "select origin_job_id from ai_result_cache where cache_key = %s",
                (cache_key,),
            ).fetchone()
            if not entry_ref:
                return False
            origin = connection.execute(
                """
                select job_id, status, cancel_requested, result_schema_version, result_ciphertext,
                       result_nonce, result_expires_at, model_alias, actual_model, prompt_version,
                       config_version, source_comment_ids, generated_at, source_map_digest,
                       source_chunk_ids, cost_microusd,
                       exists (
                           select 1 from ai_provider_calls call
                           where call.job_id = ai_jobs.job_id and call.settlement_status <> 'SETTLED'
                       ) as has_unsettled_call
                from ai_jobs where job_id = %s
                for update
                """,
                (entry_ref["origin_job_id"],),
            ).fetchone()
            if not origin:
                return False
            cached = connection.execute(
                """
                select workspace_key, requester_id, ticket_id, feature, origin_job_id,
                       expires_at as cache_expires_at, invalidated_at
                from ai_result_cache where cache_key = %s
                for update
                """,
                (cache_key,),
            ).fetchone()
            if not cached or cached["origin_job_id"] != origin["job_id"]:
                return False
            scope_matches = (
                cached["workspace_key"] == claim.workspace_key
                and cached["requester_id"] == claim.requester_id
                and cached["ticket_id"] == claim.ticket_id
                and cached["feature"] == claim.feature.value
            )
            origin_valid = (
                scope_matches
                and cached["invalidated_at"] is None
                and cached["cache_expires_at"] > now
                and origin["status"] == "SUCCEEDED"
                and not origin["cancel_requested"]
                and origin["result_ciphertext"] is not None
                and origin["result_nonce"] is not None
                and origin["result_expires_at"] is not None
                and origin["result_expires_at"] > now
                and origin["cost_microusd"] is not None
                and not origin["has_unsettled_call"]
            )
            if not origin_valid:
                connection.execute(
                    """
                    update ai_result_cache set invalidated_at = coalesce(invalidated_at, %s),
                        invalidation_reason = coalesce(invalidation_reason, 'ORIGIN_INELIGIBLE')
                    where cache_key = %s
                    """,
                    (now, cache_key),
                )
                return False
            try:
                plaintext = self.cipher.decrypt(
                    bytes(origin["result_ciphertext"]),
                    bytes(origin["result_nonce"]),
                    str(origin["job_id"]).encode(),
                )
                payload = json.loads(plaintext)
                result: TypedResult
                if claim.feature == Feature.SUMMARY:
                    result = SummaryResult.model_validate(payload)
                elif claim.feature == Feature.TRIAGE:
                    result = TriageResult.model_validate(payload)
                else:
                    return False
                ciphertext, nonce = self.cipher.encrypt(
                    result.model_dump_json().encode(), str(claim.job_id).encode()
                )
            except (InvalidTag, UnicodeDecodeError, json.JSONDecodeError, ValidationError, ValueError):
                connection.execute(
                    """
                    update ai_result_cache set invalidated_at = coalesce(invalidated_at, %s),
                        invalidation_reason = coalesce(invalidation_reason, 'ORIGIN_INVALID')
                    where cache_key = %s
                    """,
                    (now, cache_key),
                )
                return False
            updated = connection.execute(
                """
                update ai_jobs set status = 'SUCCEEDED', phase = 'COMPLETE',
                    result_schema_version = %s, result_ciphertext = %s, result_nonce = %s,
                    result_expires_at = %s, model_alias = %s, actual_model = %s,
                    prompt_version = %s, config_version = %s, source_comment_ids = %s,
                    generated_at = %s, source_map_digest = %s, source_chunk_ids = %s,
                    cost_microusd = 0, completed_at = %s, updated_at = %s,
                    lease_owner = null, lease_expires_at = null, error_code = null,
                    result_origin_job_id = %s,
                    reuse_kind = case when shared_execution_id is null then 'EXACT_CACHE_HIT' else 'COALESCED' end
                where job_id = %s and generation = %s and lease_epoch = %s and status = 'RUNNING'
                """,
                (
                    origin["result_schema_version"],
                    ciphertext,
                    nonce,
                    min(cached["cache_expires_at"], origin["result_expires_at"]),
                    origin["model_alias"],
                    origin["actual_model"],
                    origin["prompt_version"],
                    origin["config_version"],
                    origin["source_comment_ids"],
                    origin["generated_at"],
                    origin["source_map_digest"],
                    origin["source_chunk_ids"],
                    now,
                    now,
                    origin["job_id"],
                    claim.job_id,
                    claim.generation,
                    claim.lease_epoch,
                ),
            ).rowcount
            if updated != 1:
                raise StaleLeaseError("cache completion lease lost")
            self._finish_shared_execution(
                connection, claim, "SUCCEEDED", None, "COMPLETED", now
            )
            return True

    def read_reply_cache_candidate(self, claim: ClaimedJob, cache_key: str) -> ReplyCacheCandidate | None:
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            entry_ref = connection.execute(
                "select origin_job_id from ai_result_cache where cache_key = %s",
                (cache_key,),
            ).fetchone()
            if not entry_ref:
                return None
            origin = connection.execute(
                """
                select job_id, status, cancel_requested, result_ciphertext, result_nonce,
                       result_expires_at, source_map_digest, source_chunk_ids, cost_microusd,
                       exists (
                           select 1 from ai_provider_calls call
                           where call.job_id = ai_jobs.job_id and call.settlement_status <> 'SETTLED'
                       ) as has_unsettled_call
                from ai_jobs where job_id = %s for update
                """,
                (entry_ref["origin_job_id"],),
            ).fetchone()
            cached = connection.execute(
                """
                select key_version, workspace_key, requester_id, ticket_id, feature, origin_job_id,
                       expires_at as cache_expires_at, invalidated_at
                from ai_result_cache where cache_key = %s for update
                """,
                (cache_key,),
            ).fetchone()
            if not origin or not cached or cached["origin_job_id"] != origin["job_id"]:
                return None
            scope_matches = (
                cached["key_version"] == "result-cache-reply-v1"
                and cached["workspace_key"] == claim.workspace_key
                and cached["requester_id"] == claim.requester_id
                and cached["ticket_id"] == claim.ticket_id
                and cached["feature"] == Feature.REPLY_DRAFT.value
                and claim.feature == Feature.REPLY_DRAFT
            )
            origin_valid = (
                scope_matches
                and cached["invalidated_at"] is None
                and cached["cache_expires_at"] > now
                and origin["status"] == "SUCCEEDED"
                and not origin["cancel_requested"]
                and origin["result_ciphertext"] is not None
                and origin["result_nonce"] is not None
                and origin["result_expires_at"] is not None
                and origin["result_expires_at"] > now
                and origin["source_map_digest"] is not None
                and origin["source_chunk_ids"]
                and origin["cost_microusd"] is not None
                and not origin["has_unsettled_call"]
            )
            if not origin_valid:
                self._invalidate_cache_entry(connection, cache_key, now, "ORIGIN_INELIGIBLE")
                return None
            try:
                plaintext = self.cipher.decrypt(
                    bytes(origin["result_ciphertext"]),
                    bytes(origin["result_nonce"]),
                    str(origin["job_id"]).encode(),
                )
                result = ReplyDraftResult.model_validate(json.loads(plaintext))
                if not result.citations or any(
                    citation.chunkId not in origin["source_chunk_ids"] for citation in result.citations
                ):
                    raise ValueError("reply cache provenance mismatch")
                fingerprint = self._reply_cache_fingerprint(origin, result)
            except (InvalidTag, UnicodeDecodeError, json.JSONDecodeError, ValidationError, ValueError):
                self._invalidate_cache_entry(connection, cache_key, now, "ORIGIN_INVALID")
                return None
            return ReplyCacheCandidate(origin["job_id"], result, fingerprint)

    def complete_reply_from_cache(
        self,
        claim: ClaimedJob,
        cache_key: str,
        candidate: ReplyCacheCandidate,
    ) -> bool:
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            origin = connection.execute(
                """
                select job_id, status, cancel_requested, result_schema_version, result_ciphertext,
                       result_nonce, result_expires_at, model_alias, actual_model, prompt_version,
                       config_version, source_comment_ids, generated_at, source_map_digest,
                       source_chunk_ids, cost_microusd,
                       exists (
                           select 1 from ai_provider_calls call
                           where call.job_id = ai_jobs.job_id and call.settlement_status <> 'SETTLED'
                       ) as has_unsettled_call
                from ai_jobs where job_id = %s for update
                """,
                (candidate.origin_job_id,),
            ).fetchone()
            cached = connection.execute(
                """
                select key_version, workspace_key, requester_id, ticket_id, feature, origin_job_id,
                       expires_at as cache_expires_at, invalidated_at
                from ai_result_cache where cache_key = %s for update
                """,
                (cache_key,),
            ).fetchone()
            if not origin or not cached or cached["origin_job_id"] != candidate.origin_job_id:
                return False
            origin_valid = (
                cached["key_version"] == "result-cache-reply-v1"
                and cached["workspace_key"] == claim.workspace_key
                and cached["requester_id"] == claim.requester_id
                and cached["ticket_id"] == claim.ticket_id
                and cached["feature"] == Feature.REPLY_DRAFT.value
                and claim.feature == Feature.REPLY_DRAFT
                and cached["invalidated_at"] is None
                and cached["cache_expires_at"] > now
                and origin["status"] == "SUCCEEDED"
                and not origin["cancel_requested"]
                and origin["result_ciphertext"] is not None
                and origin["result_nonce"] is not None
                and origin["result_expires_at"] is not None
                and origin["result_expires_at"] > now
                and origin["source_map_digest"] is not None
                and origin["source_chunk_ids"]
                and origin["cost_microusd"] is not None
                and not origin["has_unsettled_call"]
            )
            if not origin_valid:
                self._invalidate_cache_entry(connection, cache_key, now, "ORIGIN_INELIGIBLE")
                return False
            try:
                plaintext = self.cipher.decrypt(
                    bytes(origin["result_ciphertext"]),
                    bytes(origin["result_nonce"]),
                    str(origin["job_id"]).encode(),
                )
                result = ReplyDraftResult.model_validate(json.loads(plaintext))
                if (
                    result != candidate.result
                    or self._reply_cache_fingerprint(origin, result) != candidate.fingerprint
                ):
                    raise ValueError("reply cache candidate changed")
                ciphertext, nonce = self.cipher.encrypt(
                    result.model_dump_json().encode(), str(claim.job_id).encode()
                )
            except (InvalidTag, UnicodeDecodeError, json.JSONDecodeError, ValidationError, ValueError):
                self._invalidate_cache_entry(connection, cache_key, now, "ORIGIN_INVALID")
                return False
            updated = connection.execute(
                """
                update ai_jobs set status = 'SUCCEEDED', phase = 'COMPLETE',
                    result_schema_version = %s, result_ciphertext = %s, result_nonce = %s,
                    result_expires_at = %s, model_alias = %s, actual_model = %s,
                    prompt_version = %s, config_version = %s, source_comment_ids = %s,
                    generated_at = %s, source_map_digest = %s, source_chunk_ids = %s,
                    cost_microusd = 0, completed_at = %s, updated_at = %s,
                    lease_owner = null, lease_expires_at = null, error_code = null,
                    result_origin_job_id = %s,
                    reuse_kind = case when shared_execution_id is null then 'EXACT_CACHE_HIT' else 'COALESCED' end
                where job_id = %s and generation = %s and lease_epoch = %s and status = 'RUNNING'
                """,
                (
                    origin["result_schema_version"],
                    ciphertext,
                    nonce,
                    min(cached["cache_expires_at"], origin["result_expires_at"]),
                    origin["model_alias"],
                    origin["actual_model"],
                    origin["prompt_version"],
                    origin["config_version"],
                    origin["source_comment_ids"],
                    origin["generated_at"],
                    origin["source_map_digest"],
                    origin["source_chunk_ids"],
                    now,
                    now,
                    origin["job_id"],
                    claim.job_id,
                    claim.generation,
                    claim.lease_epoch,
                ),
            ).rowcount
            if updated != 1:
                raise StaleLeaseError("reply cache completion lease lost")
            self._finish_shared_execution(
                connection, claim, "SUCCEEDED", None, "COMPLETED", now
            )
            return True

    def invalidate_result_cache(self, cache_key: str, reason: str) -> None:
        with self.database.transaction() as connection:
            self._invalidate_cache_entry(connection, cache_key, datetime.now(UTC), reason[:40])

    @staticmethod
    def _invalidate_cache_entry(connection, cache_key: str, now: datetime, reason: str) -> None:
        connection.execute(
            """
            update ai_result_cache set invalidated_at = coalesce(invalidated_at, %s),
                invalidation_reason = coalesce(invalidation_reason, %s)
            where cache_key = %s
            """,
            (now, reason, cache_key),
        )

    @staticmethod
    def _reply_cache_fingerprint(origin, result: ReplyDraftResult) -> str:
        chunk_ids = ",".join(str(item) for item in origin["source_chunk_ids"])
        return sha256_text(
            "\u001f".join(
                (
                    str(origin["job_id"]),
                    result.model_dump_json(),
                    origin["source_map_digest"],
                    chunk_ids,
                )
            )
        )

    def complete_needs_review(self, claim: ClaimedJob, error_code: str, cost_microusd: int | None) -> None:
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            updated = connection.execute(
                """
                update ai_jobs set status = 'NEEDS_REVIEW', phase = 'COMPLETE',
                    result_schema_version = null, result_ciphertext = null, result_nonce = null,
                    result_expires_at = null, model_alias = null, actual_model = null,
                    prompt_version = null, config_version = null, source_comment_ids = '{}',
                    source_map_digest = null, source_chunk_ids = null,
                    generated_at = null, cost_microusd = %s, completed_at = %s, updated_at = %s,
                    lease_owner = null, lease_expires_at = null, error_code = %s
                where job_id = %s and generation = %s and lease_epoch = %s and status = 'RUNNING'
                """,
                (
                    cost_microusd,
                    now,
                    now,
                    error_code[:80],
                    claim.job_id,
                    claim.generation,
                    claim.lease_epoch,
                ),
            ).rowcount
            if updated != 1:
                raise StaleLeaseError("job completion lease lost")
            self._finish_shared_execution(
                connection,
                claim,
                "NEEDS_REVIEW",
                error_code[:80],
                "COMPLETED",
                now,
            )

    def fail_job(self, claim: ClaimedJob, error_code: str, retryable: bool) -> None:
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            current = connection.execute(
                """
                select attempt_count from ai_jobs
                where job_id = %s and generation = %s and lease_epoch = %s and status = 'RUNNING'
                for update
                """,
                (claim.job_id, claim.generation, claim.lease_epoch),
            ).fetchone()
            if not current:
                raise StaleLeaseError("job failure lease lost")
            retry = retryable and current["attempt_count"] < self.settings.max_attempts and claim.deadline_at > now
            if retry:
                next_generation = claim.generation + 1
                updated = connection.execute(
                    """
                    update ai_jobs set status = 'RETRY_WAIT', phase = 'QUEUED', generation = %s,
                        error_code = %s, lease_owner = null, lease_expires_at = null, updated_at = %s
                    where job_id = %s and generation = %s and lease_epoch = %s and status = 'RUNNING'
                    """,
                    (
                        next_generation,
                        error_code[:80],
                        now,
                        claim.job_id,
                        claim.generation,
                        claim.lease_epoch,
                    ),
                ).rowcount
                if updated != 1:
                    raise StaleLeaseError("job failure lease lost")
                connection.execute(
                    """
                    insert into ai_dispatch_outbox (
                        event_id, job_id, generation, status, attempts, available_at, created_at
                    ) values (%s, %s, %s, 'PENDING', 0, %s, %s)
                    on conflict (job_id, generation) do nothing
                    """,
                    (uuid4(), claim.job_id, next_generation, now + timedelta(seconds=2 ** current["attempt_count"]), now),
                )
            else:
                updated = connection.execute(
                    """
                    update ai_jobs set status = 'FAILED', phase = 'COMPLETE', error_code = %s,
                        completed_at = %s, lease_owner = null, lease_expires_at = null, updated_at = %s
                    where job_id = %s and generation = %s and lease_epoch = %s and status = 'RUNNING'
                    """,
                    (
                        error_code[:80],
                        now,
                        now,
                        claim.job_id,
                        claim.generation,
                        claim.lease_epoch,
                    ),
                ).rowcount
                if updated != 1:
                    raise StaleLeaseError("job failure lease lost")
                execution_status = "UNKNOWN" if error_code == "PROVIDER_OUTCOME_UNKNOWN" else "FAILED"
                self._finish_shared_execution(
                    connection,
                    claim,
                    execution_status,
                    error_code[:80],
                    "FAILED",
                    now,
                )

    def terminate_job(self, claim: ClaimedJob, status: JobStatus, error_code: str) -> None:
        if status not in {JobStatus.SUPERSEDED, JobStatus.CANCELLED, JobStatus.EXPIRED, JobStatus.FAILED}:
            raise ValueError("unsupported terminal status")
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            updated = connection.execute(
                """
                update ai_jobs set status = %s, phase = 'COMPLETE', error_code = %s,
                    completed_at = %s, updated_at = %s, lease_owner = null, lease_expires_at = null
                where job_id = %s and generation = %s and lease_epoch = %s and status = 'RUNNING'
                """,
                (status.value, error_code[:80], now, now, claim.job_id, claim.generation, claim.lease_epoch),
            ).rowcount
            if updated != 1:
                raise StaleLeaseError("job termination lease lost")
            execution_status = "UNKNOWN" if error_code == "PROVIDER_OUTCOME_UNKNOWN" else "FAILED"
            self._finish_shared_execution(
                connection,
                claim,
                execution_status,
                error_code[:80],
                "FAILED",
                now,
            )

    def accept_feedback(self, feedback: FeedbackRequest) -> Accepted:
        fingerprint = sha256_text(feedback.model_dump_json(exclude_none=False))
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            seen = connection.execute(
                "select job_id, event_fingerprint from ai_feedback_inbox where event_id = %s for update",
                (feedback.eventId,),
            ).fetchone()
            if seen:
                if seen["job_id"] != feedback.jobId or seen["event_fingerprint"] != fingerprint:
                    raise ConflictError("feedback event ID reused with different content")
                return Accepted(replayed=True, jobId=feedback.jobId)
            job = connection.execute(
                "select requester_id, workspace_key, status, request_revision from ai_jobs where job_id = %s for update",
                (feedback.jobId,),
            ).fetchone()
            if (
                not job
                or job["requester_id"] != feedback.requesterId
                or job["workspace_key"] != feedback.workspaceKey
            ):
                raise NotFoundError("job not found")
            if job["status"] not in {"SUCCEEDED", "NEEDS_REVIEW"}:
                raise ConflictError("feedback requires a completed result")
            if feedback.requestRevision < job["request_revision"]:
                raise ConflictError("stale feedback request revision")
            current = connection.execute(
                "select source_revision, reason_code from ai_feedback where job_id = %s and feedback_type = %s for update",
                (feedback.jobId, feedback.feedbackType),
            ).fetchone()
            if current and feedback.sourceRevision <= current["source_revision"]:
                if feedback.sourceRevision == current["source_revision"] and feedback.reasonCode == current["reason_code"]:
                    connection.execute(
                        "insert into ai_feedback_inbox (event_id, job_id, event_fingerprint, received_at) values (%s, %s, %s, %s)",
                        (feedback.eventId, feedback.jobId, fingerprint, now),
                    )
                    return Accepted(replayed=True, jobId=feedback.jobId)
                raise ConflictError("feedback source revision is stale or conflicting")
            connection.execute(
                "insert into ai_feedback_inbox (event_id, job_id, event_fingerprint, received_at) values (%s, %s, %s, %s)",
                (feedback.eventId, feedback.jobId, fingerprint, now),
            )
            connection.execute(
                """
                insert into ai_feedback (
                    job_id, requester_id, feedback_type, source_revision, last_event_id,
                    reason_code, score_id, score_name, first_recorded_at, updated_at, next_export_at
                ) values (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                on conflict (job_id, feedback_type) do update set
                    source_revision = excluded.source_revision,
                    last_event_id = excluded.last_event_id,
                    reason_code = excluded.reason_code,
                    updated_at = excluded.updated_at,
                    next_export_at = excluded.next_export_at,
                    last_export_error = null
                """,
                (
                    feedback.jobId,
                    feedback.requesterId,
                    feedback.feedbackType,
                    feedback.sourceRevision,
                    feedback.eventId,
                    feedback.reasonCode,
                    uuid4(),
                    f"deskseed.{feedback.feedbackType}",
                    now,
                    now,
                    now,
                ),
            )
            connection.execute(
                "update ai_jobs set request_revision = greatest(request_revision, %s), updated_at = %s where job_id = %s",
                (feedback.requestRevision, now, feedback.jobId),
            )
        return Accepted(replayed=False, jobId=feedback.jobId)

    def accept_index_event(self, event: IndexEvent) -> Accepted:
        fingerprint = sha256_text(event.model_dump_json())
        with self.database.transaction() as connection:
            seen = connection.execute(
                "select event_fingerprint from ai_kb_index_inbox where event_id = %s for update", (event.eventId,)
            ).fetchone()
            if seen:
                if seen["event_fingerprint"] != fingerprint:
                    raise ConflictError("index event conflict")
                return Accepted(replayed=True)
            connection.execute(
                """
                insert into ai_kb_index_inbox (event_id, article_id, revision_id, event_fingerprint, received_at)
                values (%s, %s, %s, %s, clock_timestamp())
                """,
                (event.eventId, event.articleId, event.revisionId, fingerprint),
            )
            current = connection.execute(
                """
                select state.source_version, state.action, job.revision_id, job.public_revision
                from ai_kb_article_state state
                join ai_kb_index_jobs job on job.event_id = state.event_id
                where state.workspace_key = %s and state.article_id = %s for update of state
                """,
                (event.workspaceKey, event.articleId),
            ).fetchone()
            if current and event.sourceVersion < current["source_version"]:
                return Accepted(replayed=False)
            if current and event.sourceVersion == current["source_version"]:
                if (
                    event.action != current["action"]
                    or event.revisionId != current["revision_id"]
                    or event.publicRevision != current["public_revision"]
                ):
                    raise ConflictError("index event source version has conflicting payload")
                return Accepted(replayed=False)
            connection.execute(
                """
                insert into ai_kb_article_state (
                    workspace_key, article_id, source_version, action, event_id, updated_at
                ) values (%s, %s, %s, %s, %s, clock_timestamp())
                on conflict (workspace_key, article_id) do update set
                    source_version = excluded.source_version,
                    action = excluded.action,
                    event_id = excluded.event_id,
                    updated_at = excluded.updated_at
                """,
                (
                    event.workspaceKey,
                    event.articleId,
                    event.sourceVersion,
                    event.action,
                    event.eventId,
                ),
            )
            connection.execute(
                """
                insert into ai_kb_index_jobs (
                    event_id, workspace_key, article_id, revision_id, action, source_version, public_revision,
                    status, attempts, available_at, created_at, completed_at
                ) values (%s, %s, %s, %s, %s, %s, %s, 'SUCCEEDED', 0,
                          clock_timestamp(), %s, clock_timestamp())
                """,
                (
                    event.eventId,
                    event.workspaceKey,
                    event.articleId,
                    event.revisionId,
                    event.action,
                    event.sourceVersion,
                    event.publicRevision,
                    event.createdAt,
                ),
            )
        return Accepted(replayed=False)

    def accept_reconciliation_index_event(self, run: ReconciliationRun, event: IndexEvent) -> Accepted:
        fingerprint = sha256_text(
            f"{event.model_dump_json()}:{run.run_id}:{run.target_artifact_generation}:"
            f"{run.index_contract_version}"
        )
        with self.database.transaction() as connection:
            current = connection.execute(
                "select status, target_artifact_generation from ai_kb_reconciliation_runs where run_id = %s for update",
                (run.run_id,),
            ).fetchone()
            if (
                current is None
                or current["status"] not in {"RUNNING", "INDEXING"}
                or current["target_artifact_generation"] != run.target_artifact_generation
            ):
                raise ConflictError("reconciliation build is not active")
            seen = connection.execute(
                "select event_fingerprint from ai_kb_index_inbox where event_id = %s for update",
                (event.eventId,),
            ).fetchone()
            if seen:
                if seen["event_fingerprint"] != fingerprint:
                    raise ConflictError("index build event conflict")
                return Accepted(replayed=True)
            connection.execute(
                """
                insert into ai_kb_index_inbox (event_id, article_id, revision_id, event_fingerprint, received_at)
                values (%s, %s, %s, %s, clock_timestamp())
                """,
                (event.eventId, event.articleId, event.revisionId, fingerprint),
            )
            connection.execute(
                """
                insert into ai_kb_index_jobs (
                    event_id, workspace_key, article_id, revision_id, action, source_version,
                    public_revision, status, attempts, available_at, created_at,
                    artifact_generation, reconciliation_run_id
                ) values (%s, %s, %s, %s, 'UPSERT', %s, %s, 'PENDING', 0,
                          clock_timestamp(), %s, %s, %s)
                """,
                (
                    event.eventId,
                    event.workspaceKey,
                    event.articleId,
                    event.revisionId,
                    event.sourceVersion,
                    event.publicRevision,
                    event.createdAt,
                    run.target_artifact_generation,
                    run.run_id,
                ),
            )
        return Accepted(replayed=False)

    def claim_index_events(self, owner: str, limit: int = 10) -> list[IndexWorkItem]:
        now = datetime.now(UTC)
        lease_until = now + timedelta(seconds=self.settings.lease_seconds)
        with self.database.transaction() as connection:
            rows = connection.execute(
                """
                select event_id, workspace_key, article_id, revision_id, action, source_version,
                       public_revision, created_at, artifact_generation, reconciliation_run_id
                from ai_kb_index_jobs
                where artifact_generation is not null and (
                    (status = 'PENDING' and available_at <= %s)
                    or (status = 'LEASED' and lease_expires_at <= %s)
                )
                order by available_at, created_at, event_id
                for update skip locked limit %s
                """,
                (now, now, limit),
            ).fetchall()
            for row in rows:
                connection.execute(
                    """
                    update ai_kb_index_jobs set status = 'LEASED', attempts = attempts + 1,
                        lease_owner = %s, lease_expires_at = %s
                    where event_id = %s
                    """,
                    (owner, lease_until, row["event_id"]),
                )
        return [
            IndexWorkItem(
                event_id=row["event_id"],
                workspace_key=row["workspace_key"],
                article_id=row["article_id"],
                revision_id=row["revision_id"],
                action=row["action"],
                source_version=row["source_version"],
                public_revision=row["public_revision"],
                created_at=row["created_at"],
                artifact_generation=row["artifact_generation"],
                reconciliation_run_id=row["reconciliation_run_id"],
            )
            for row in rows
        ]

    def mark_index_event_succeeded(self, event_id: UUID, owner: str) -> None:
        with self.database.transaction() as connection:
            updated = connection.execute(
                """
                update ai_kb_index_jobs set status = 'SUCCEEDED', completed_at = clock_timestamp(),
                    lease_owner = null, lease_expires_at = null, last_error_code = null
                where event_id = %s and status = 'LEASED' and lease_owner = %s
                """,
                (event_id, owner),
            ).rowcount
            if updated != 1:
                raise StaleLeaseError("index event lease lost")

    def release_index_event(self, event_id: UUID, owner: str, error_code: str) -> None:
        with self.database.transaction() as connection:
            connection.execute(
                """
                update ai_kb_index_jobs
                set status = case when attempts >= %s then 'DEAD' else 'PENDING' end,
                    available_at = clock_timestamp() + interval '30 seconds',
                    lease_owner = null, lease_expires_at = null, last_error_code = %s
                where event_id = %s and status = 'LEASED' and lease_owner = %s
                """,
                (self.settings.max_attempts, error_code[:80], event_id, owner),
            )

    def current_reconciliation(self, workspace_key: str) -> ReconciliationRun | None:
        with self.database.connection() as connection:
            row = connection.execute(
                """
                select run_id, workspace_key, snapshot_token, snapshot_expires_at, next_cursor,
                       canonical_corpus_revision, target_artifact_generation,
                       index_contract_version, chunker_version, normalization_version,
                       embedding_model, embedding_dimension
                from ai_kb_reconciliation_runs
                where workspace_key = %s and status = 'RUNNING'
                """,
                (workspace_key,),
            ).fetchone()
        return ReconciliationRun(**row) if row else None

    def reconciliation_due(self, workspace_key: str) -> bool:
        with self.database.connection() as connection:
            row = connection.execute(
                """
                select
                    max(started_at) as last_started_at,
                    max(completed_at) filter (where status = 'SUCCEEDED') as last_succeeded_at,
                    (select max(state.updated_at) from ai_kb_article_state state
                     where state.workspace_key = %s) as last_source_event_at,
                    (select published_at from ai_kb_published_generations
                     where workspace_key = %s) as published_at
                from ai_kb_reconciliation_runs where workspace_key = %s
                """,
                (workspace_key, workspace_key, workspace_key),
            ).fetchone()
        now = datetime.now(UTC)
        last_started = row["last_started_at"]
        last_succeeded = row["last_succeeded_at"]
        if row["last_source_event_at"] is not None and (
            row["published_at"] is None or row["last_source_event_at"] > row["published_at"]
        ):
            return True
        if last_started is not None and last_started > now - timedelta(seconds=self.settings.reconciliation_retry_seconds):
            return False
        return last_succeeded is None or last_succeeded <= now - timedelta(
            seconds=self.settings.reconciliation_interval_seconds
        )

    def begin_reconciliation(
        self,
        run_id: UUID,
        workspace_key: str,
        snapshot_token: UUID,
        snapshot_expires_at: datetime,
        canonical_corpus_revision: int | None,
        index_contract_version: str,
        chunker_version: str,
        normalization_version: str,
        embedding_model: str,
        embedding_dimension: int,
    ) -> bool:
        with self.database.transaction() as connection:
            connection.execute("select pg_advisory_xact_lock(hashtext('ai-kb-reconciliation'), hashtext(%s))", (workspace_key,))
            running = connection.execute(
                """
                select 1 from ai_kb_reconciliation_runs
                where workspace_key = %s and status in ('RUNNING', 'INDEXING')
                """,
                (workspace_key,),
            ).fetchone()
            if running:
                return False
            if canonical_corpus_revision is None:
                return False
            published = connection.execute(
                """
                select published.canonical_corpus_revision, artifact.index_contract_version,
                       artifact.chunker_version, artifact.normalization_version,
                       artifact.embedding_model, artifact.embedding_dimension
                from ai_kb_published_generations published
                join ai_kb_index_artifacts artifact
                  on artifact.workspace_key = published.workspace_key
                 and artifact.artifact_generation = published.artifact_generation
                where published.workspace_key = %s
                """,
                (workspace_key,),
            ).fetchone()
            expected_spec = (
                index_contract_version,
                chunker_version,
                normalization_version,
                embedding_model,
                embedding_dimension,
            )
            if published is not None and (
                published["canonical_corpus_revision"] == canonical_corpus_revision
                and (
                    published["index_contract_version"],
                    published["chunker_version"],
                    published["normalization_version"],
                    published["embedding_model"],
                    published["embedding_dimension"],
                ) == expected_spec
            ):
                return False
            target_artifact_generation = connection.execute(
                """
                select coalesce(max(artifact_generation), 0) + 1 as generation
                from ai_kb_index_artifacts where workspace_key = %s
                """,
                (workspace_key,),
            ).fetchone()["generation"]
            connection.execute(
                """
                insert into ai_kb_reconciliation_runs (
                    run_id, workspace_key, snapshot_token, snapshot_expires_at,
                    canonical_corpus_revision, status, started_at, target_artifact_generation,
                    index_contract_version, chunker_version, normalization_version,
                    embedding_model, embedding_dimension
                ) values (%s, %s, %s, %s, %s, 'RUNNING', clock_timestamp(),
                          %s, %s, %s, %s, %s, %s)
                """,
                (
                    run_id,
                    workspace_key,
                    snapshot_token,
                    snapshot_expires_at,
                    canonical_corpus_revision,
                    target_artifact_generation,
                    index_contract_version,
                    chunker_version,
                    normalization_version,
                    embedding_model,
                    embedding_dimension,
                ),
            )
            connection.execute(
                """
                insert into ai_kb_index_artifacts (
                    workspace_key, artifact_generation, canonical_corpus_revision,
                    reconciliation_run_id, state, index_contract_version, chunker_version,
                    normalization_version, embedding_model, embedding_dimension, created_at
                ) values (%s, %s, %s, %s, 'BUILDING', %s, %s, %s, %s, %s, clock_timestamp())
                """,
                (
                    workspace_key,
                    target_artifact_generation,
                    canonical_corpus_revision,
                    run_id,
                    index_contract_version,
                    chunker_version,
                    normalization_version,
                    embedding_model,
                    embedding_dimension,
                ),
            )
        return True

    def record_reconciliation_page(
        self,
        run: ReconciliationRun,
        items: list[tuple[UUID, UUID, int, str]],
        next_cursor: UUID | None,
    ) -> bool:
        with self.database.transaction() as connection:
            row = connection.execute(
                """
                select next_cursor from ai_kb_reconciliation_runs
                where run_id = %s and status = 'RUNNING' for update
                """,
                (run.run_id,),
            ).fetchone()
            if row is None or row["next_cursor"] != run.next_cursor:
                return False
            for article_id, revision_id, source_version, public_revision in items:
                connection.execute(
                    """
                    insert into ai_kb_reconciliation_seen (
                        run_id, article_id, revision_id, source_version, public_revision
                    ) values (%s, %s, %s, %s, %s)
                    on conflict (run_id, article_id) do update set
                        revision_id = excluded.revision_id,
                        source_version = excluded.source_version,
                        public_revision = excluded.public_revision
                    """,
                    (run.run_id, article_id, revision_id, source_version, public_revision),
                )
            connection.execute(
                """
                update ai_kb_reconciliation_runs
                set next_cursor = %s, page_count = page_count + 1, item_count = item_count + %s
                where run_id = %s
                """,
                (next_cursor, len(items), run.run_id),
            )
            if next_cursor is None:
                connection.execute(
                    """
                    update ai_kb_reconciliation_runs
                    set status = 'INDEXING', completed_at = clock_timestamp(), last_error_code = null
                    where run_id = %s
                    """,
                    (run.run_id,),
                )
        return True

    def try_publish_index_generation(self, workspace_key: str) -> bool | None:
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            connection.execute(
                "select pg_advisory_xact_lock(hashtext('ai-kb-generation'), hashtext(%s))",
                (workspace_key,),
            )
            run = connection.execute(
                """
                select run_id, snapshot_token, snapshot_expires_at, canonical_corpus_revision,
                       item_count, target_artifact_generation, index_contract_version,
                       chunker_version, normalization_version, embedding_model, embedding_dimension
                from ai_kb_reconciliation_runs
                where workspace_key = %s and status = 'INDEXING'
                order by started_at desc, run_id desc limit 1
                for update
                """,
                (workspace_key,),
            ).fetchone()
            if run is None:
                return None
            if run["snapshot_expires_at"] <= now or run["canonical_corpus_revision"] is None:
                error_code = (
                    "CORPUS_REVISION_UNKNOWN"
                    if run["canonical_corpus_revision"] is None
                    else "SNAPSHOT_EXPIRED"
                )
                connection.execute(
                    """
                    update ai_kb_reconciliation_runs
                    set status = 'FAILED', last_error_code = %s
                    where run_id = %s
                    """,
                    (error_code, run["run_id"]),
                )
                connection.execute(
                    """
                    update ai_kb_index_artifacts
                    set state = 'FAILED', completed_at = %s, failure_code = %s
                    where workspace_key = %s and artifact_generation = %s and state = 'BUILDING'
                    """,
                    (now, error_code, workspace_key, run["target_artifact_generation"]),
                )
                return False
            job_counts = connection.execute(
                """
                select
                    count(*) filter (where job.status in ('PENDING', 'LEASED')) as pending,
                    count(*) filter (where job.status = 'DEAD') as dead
                from ai_kb_index_jobs job
                where job.reconciliation_run_id = %s
                  and job.artifact_generation = %s
                """,
                (run["run_id"], run["target_artifact_generation"]),
            ).fetchone()
            if job_counts["dead"]:
                connection.execute(
                    """
                    update ai_kb_reconciliation_runs
                    set status = 'FAILED', last_error_code = 'INDEX_JOB_DEAD'
                    where run_id = %s
                    """,
                    (run["run_id"],),
                )
                connection.execute(
                    """
                    update ai_kb_index_artifacts
                    set state = 'FAILED', completed_at = %s, failure_code = 'INDEX_JOB_DEAD'
                    where workspace_key = %s and artifact_generation = %s and state = 'BUILDING'
                    """,
                    (now, workspace_key, run["target_artifact_generation"]),
                )
                return False
            if job_counts["pending"]:
                return False
            seen_count = connection.execute(
                "select count(*) as count from ai_kb_reconciliation_seen where run_id = %s",
                (run["run_id"],),
            ).fetchone()["count"]
            mismatch = seen_count != run["item_count"] or connection.execute(
                """
                select exists (
                    select 1
                    from ai_kb_reconciliation_seen seen
                    left join ai_kb_article_state state
                      on state.workspace_key = %s and state.article_id = seen.article_id
                    left join ai_kb_index_jobs job
                      on job.reconciliation_run_id = seen.run_id
                     and job.article_id = seen.article_id
                     and job.revision_id = seen.revision_id
                    left join ai_kb_revisions revision
                      on revision.workspace_key = %s
                     and revision.artifact_generation = %s
                     and revision.article_id = seen.article_id
                     and revision.revision_id = seen.revision_id
                    where seen.run_id = %s and (
                        seen.source_version is null or seen.public_revision is null
                        or (state.source_version is not null and (
                            state.source_version > seen.source_version
                            or (
                                state.source_version = seen.source_version
                                and state.action is distinct from 'UPSERT'
                            )
                        ))
                        or job.status is distinct from 'SUCCEEDED'
                        or job.source_version is distinct from seen.source_version
                        or job.revision_id is distinct from seen.revision_id
                        or job.public_revision is distinct from seen.public_revision
                        or revision.status is distinct from 'PUBLIC'
                        or revision.public_revision is distinct from seen.public_revision
                        or not exists (
                            select 1 from ai_kb_chunks chunk
                            where chunk.workspace_key = %s
                              and chunk.artifact_generation = %s
                              and chunk.article_id = seen.article_id
                              and chunk.revision_id = seen.revision_id
                        )
                    )
                ) or exists (
                    select 1 from ai_kb_revisions revision
                    where revision.workspace_key = %s
                      and revision.artifact_generation = %s
                      and revision.status = 'PUBLIC'
                      and not exists (
                          select 1 from ai_kb_reconciliation_seen seen
                          where seen.run_id = %s and seen.article_id = revision.article_id
                            and seen.revision_id = revision.revision_id
                      )
                ) as mismatch
                """,
                (
                    workspace_key,
                    workspace_key,
                    run["target_artifact_generation"],
                    run["run_id"],
                    workspace_key,
                    run["target_artifact_generation"],
                    workspace_key,
                    run["target_artifact_generation"],
                    run["run_id"],
                ),
            ).fetchone()["mismatch"]
            if mismatch:
                connection.execute(
                    """
                    update ai_kb_reconciliation_runs
                    set status = 'FAILED', last_error_code = 'INDEX_STATE_MISMATCH'
                    where run_id = %s
                    """,
                    (run["run_id"],),
                )
                connection.execute(
                    """
                    update ai_kb_index_artifacts
                    set state = 'FAILED', completed_at = %s, failure_code = 'INDEX_STATE_MISMATCH'
                    where workspace_key = %s and artifact_generation = %s and state = 'BUILDING'
                    """,
                    (now, workspace_key, run["target_artifact_generation"]),
                )
                return False
            current = connection.execute(
                "select generation from ai_kb_published_generations where workspace_key = %s for update",
                (workspace_key,),
            ).fetchone()
            generation = (current["generation"] if current else 0) + 1
            artifact_updated = connection.execute(
                """
                update ai_kb_index_artifacts
                set state = 'COMPLETE', completed_at = %s, failure_code = null
                where workspace_key = %s and artifact_generation = %s and state = 'BUILDING'
                """,
                (now, workspace_key, run["target_artifact_generation"]),
            ).rowcount
            if artifact_updated != 1:
                connection.execute(
                    """
                    update ai_kb_reconciliation_runs
                    set status = 'FAILED', last_error_code = 'INDEX_ARTIFACT_UNAVAILABLE'
                    where run_id = %s
                    """,
                    (run["run_id"],),
                )
                return False
            connection.execute(
                """
                insert into ai_kb_published_generations (
                    workspace_key, generation, canonical_corpus_revision,
                    reconciliation_run_id, snapshot_token, published_at, artifact_generation
                ) values (%s, %s, %s, %s, %s, %s, %s)
                on conflict (workspace_key) do update set
                    generation = excluded.generation,
                    canonical_corpus_revision = excluded.canonical_corpus_revision,
                    reconciliation_run_id = excluded.reconciliation_run_id,
                    snapshot_token = excluded.snapshot_token,
                    published_at = excluded.published_at,
                    artifact_generation = excluded.artifact_generation
                """,
                (
                    workspace_key,
                    generation,
                    run["canonical_corpus_revision"],
                    run["run_id"],
                    run["snapshot_token"],
                    now,
                    run["target_artifact_generation"],
                ),
            )
            connection.execute(
                """
                insert into ai_kb_index_publication_history (
                    workspace_key, publication_epoch, artifact_generation,
                    canonical_corpus_revision, action, reason, published_at
                ) values (%s, %s, %s, %s, 'PUBLISH', null, %s)
                """,
                (
                    workspace_key,
                    generation,
                    run["target_artifact_generation"],
                    run["canonical_corpus_revision"],
                    now,
                ),
            )
            connection.execute(
                """
                update ai_kb_reconciliation_runs
                set status = 'SUCCEEDED', published_generation = %s, last_error_code = null
                where run_id = %s
                """,
                (generation, run["run_id"]),
            )
            return True

    def current_published_index_generation(self, workspace_key: str) -> PublishedIndexGeneration | None:
        with self.database.connection() as connection:
            row = connection.execute(
                """
                select generation, canonical_corpus_revision, artifact_generation
                from ai_kb_published_generations where workspace_key = %s
                """,
                (workspace_key,),
            ).fetchone()
        return PublishedIndexGeneration(**row) if row else None

    def restore_index_artifact(
        self,
        workspace_key: str,
        artifact_generation: int,
        expected_publication_epoch: int,
        expected_canonical_corpus_revision: int,
        reason: str,
    ) -> PublishedIndexGeneration:
        normalized_reason = reason.strip()
        if not normalized_reason or len(normalized_reason) > 500 or any(
            ord(character) < 32 or ord(character) == 127 for character in normalized_reason
        ):
            raise ValueError("restore reason must be bounded printable text")
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            connection.execute(
                "select pg_advisory_xact_lock(hashtext('ai-kb-generation'), hashtext(%s))",
                (workspace_key,),
            )
            current = connection.execute(
                """
                select generation, artifact_generation, canonical_corpus_revision
                from ai_kb_published_generations where workspace_key = %s for update
                """,
                (workspace_key,),
            ).fetchone()
            if current is None:
                raise ConflictError("published index is unavailable")
            if (
                current["generation"] != expected_publication_epoch
                or current["canonical_corpus_revision"] != expected_canonical_corpus_revision
            ):
                raise ConflictError("published index restore precondition failed")
            if current["artifact_generation"] == artifact_generation:
                raise ConflictError("target artifact is already active")
            active_spec = connection.execute(
                """
                select index_contract_version, chunker_version, normalization_version,
                       embedding_model, embedding_dimension
                from ai_kb_index_artifacts
                where workspace_key = %s and artifact_generation = %s and state = 'COMPLETE'
                """,
                (workspace_key, current["artifact_generation"]),
            ).fetchone()
            target = connection.execute(
                """
                select artifact.canonical_corpus_revision, artifact.index_contract_version,
                       artifact.chunker_version, artifact.normalization_version,
                       artifact.embedding_model, artifact.embedding_dimension,
                       artifact.reconciliation_run_id, run.snapshot_token
                from ai_kb_index_artifacts artifact
                join ai_kb_reconciliation_runs run
                  on run.run_id = artifact.reconciliation_run_id
                where artifact.workspace_key = %s and artifact.artifact_generation = %s
                  and artifact.state = 'COMPLETE' and run.status = 'SUCCEEDED'
                """,
                (workspace_key, artifact_generation),
            ).fetchone()
            if target is None or active_spec is None:
                raise ConflictError("restore artifact is incomplete")
            if target["canonical_corpus_revision"] != expected_canonical_corpus_revision:
                raise ConflictError("restore artifact corpus revision differs")
            spec_fields = (
                "index_contract_version",
                "embedding_model",
                "embedding_dimension",
            )
            if any(target[field] != active_spec[field] for field in spec_fields):
                raise ConflictError("restore artifact index contract differs")
            generation = current["generation"] + 1
            connection.execute(
                """
                update ai_kb_published_generations
                set generation = %s, artifact_generation = %s,
                    reconciliation_run_id = %s, snapshot_token = %s, published_at = %s
                where workspace_key = %s
                """,
                (
                    generation,
                    artifact_generation,
                    target["reconciliation_run_id"],
                    target["snapshot_token"],
                    now,
                    workspace_key,
                ),
            )
            connection.execute(
                """
                insert into ai_kb_index_publication_history (
                    workspace_key, publication_epoch, artifact_generation,
                    canonical_corpus_revision, action, reason, published_at
                ) values (%s, %s, %s, %s, 'RESTORE', %s, %s)
                """,
                (
                    workspace_key,
                    generation,
                    artifact_generation,
                    expected_canonical_corpus_revision,
                    normalized_reason,
                    now,
                ),
            )
        return PublishedIndexGeneration(
            generation=generation,
            canonical_corpus_revision=expected_canonical_corpus_revision,
            artifact_generation=artifact_generation,
        )

    def fail_reconciliation(self, run_id: UUID, error_code: str) -> None:
        with self.database.transaction() as connection:
            row = connection.execute(
                "select workspace_key, target_artifact_generation from ai_kb_reconciliation_runs where run_id = %s",
                (run_id,),
            ).fetchone()
            connection.execute(
                """
                update ai_kb_reconciliation_runs
                set status = 'FAILED', completed_at = clock_timestamp(), last_error_code = %s
                where run_id = %s and status = 'RUNNING'
                """,
                (error_code[:80], run_id),
            )
            if row is not None and row["target_artifact_generation"] is not None:
                connection.execute(
                    """
                    update ai_kb_index_artifacts
                    set state = 'FAILED', completed_at = clock_timestamp(), failure_code = %s
                    where workspace_key = %s and artifact_generation = %s and state = 'BUILDING'
                    """,
                    (error_code[:80], row["workspace_key"], row["target_artifact_generation"]),
                )

    def claim_feedback_exports(self, owner: str, limit: int = 20) -> list[FeedbackExport]:
        now = datetime.now(UTC)
        lease_until = now + timedelta(seconds=self.settings.lease_seconds)
        with self.database.transaction() as connection:
            rows = connection.execute(
                """
                select job_id, feedback_type, source_revision, score_id, score_name,
                       reason_code, first_recorded_at
                from ai_feedback
                where exported_revision < source_revision
                  and (next_export_at is null or next_export_at <= %s)
                  and (export_lease_expires_at is null or export_lease_expires_at <= %s)
                order by updated_at, job_id, feedback_type
                for update skip locked limit %s
                """,
                (now, now, limit),
            ).fetchall()
            for row in rows:
                connection.execute(
                    """
                    update ai_feedback set export_lease_owner = %s, export_lease_expires_at = %s
                    where job_id = %s and feedback_type = %s
                    """,
                    (owner, lease_until, row["job_id"], row["feedback_type"]),
                )
        return [FeedbackExport(**row, lease_owner=owner) for row in rows]

    def mark_feedback_exported(self, item: FeedbackExport) -> None:
        with self.database.transaction() as connection:
            connection.execute(
                """
                update ai_feedback set exported_revision = greatest(exported_revision, %s),
                    next_export_at = null, last_export_error = null,
                    export_lease_owner = null, export_lease_expires_at = null
                where job_id = %s and feedback_type = %s and export_lease_owner = %s
                """,
                (item.source_revision, item.job_id, item.feedback_type, item.lease_owner),
            )

    def release_feedback_export(self, item: FeedbackExport, error_code: str) -> None:
        with self.database.transaction() as connection:
            connection.execute(
                """
                update ai_feedback set next_export_at = clock_timestamp() + interval '30 seconds',
                    last_export_error = %s, export_lease_owner = null, export_lease_expires_at = null
                where job_id = %s and feedback_type = %s and export_lease_owner = %s
                """,
                (error_code[:80], item.job_id, item.feedback_type, item.lease_owner),
            )

    def operate(self, job_id: UUID | None, request: OperationRequest) -> Accepted:
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            inserted = connection.execute(
                """
                insert into ai_operations (
                    operation_id, job_id, action, reason, status, expected_generation, created_at
                ) values (%s, %s, %s, %s, 'ACCEPTED', %s, %s)
                on conflict (operation_id) do nothing
                """,
                (request.operationId, job_id, request.action, request.reason, request.expectedGeneration, now),
            ).rowcount
            if not inserted:
                return Accepted(replayed=True, jobId=job_id)
            if request.action == "RETRY":
                if job_id is None:
                    raise ConflictError("retry requires job")
                row = connection.execute("select generation, status from ai_jobs where job_id = %s for update", (job_id,)).fetchone()
                if not row or row["status"] not in {"FAILED", "NEEDS_REVIEW"}:
                    raise ConflictError("job is not retryable")
                if request.expectedGeneration is not None and row["generation"] != request.expectedGeneration:
                    raise ConflictError("generation conflict")
                generation = row["generation"] + 1
                connection.execute(
                    """
                    update ai_jobs set generation = %s, status = 'RETRY_WAIT', phase = 'QUEUED',
                        error_code = null, completed_at = null, updated_at = %s where job_id = %s
                    """,
                    (generation, now, job_id),
                )
                connection.execute(
                    """
                    insert into ai_dispatch_outbox (event_id, job_id, generation, status, attempts, available_at, created_at)
                    values (%s, %s, %s, 'PENDING', 0, %s, %s)
                    """,
                    (uuid4(), job_id, generation, now, now),
                )
            connection.execute(
                "update ai_operations set status = 'SUCCEEDED', result_json = %s, completed_at = %s where operation_id = %s",
                (Jsonb({"accepted": True}), now, request.operationId),
            )
        return Accepted(replayed=False, jobId=job_id)

    def purge_expired_results(self, limit: int = 1000) -> int:
        with self.database.transaction() as connection:
            rows = connection.execute(
                """
                select job_id from ai_jobs where result_expires_at <= clock_timestamp() and result_ciphertext is not null
                order by result_expires_at, job_id for update skip locked limit %s
                """,
                (limit,),
            ).fetchall()
            for row in rows:
                connection.execute(
                    """
                    update ai_result_cache set invalidated_at = coalesce(invalidated_at, clock_timestamp()),
                        invalidation_reason = coalesce(invalidation_reason, 'ORIGIN_RESULT_EXPIRED')
                    where origin_job_id = %s
                    """,
                    (row["job_id"],),
                )
                connection.execute(
                    """
                    update ai_jobs set result_ciphertext = null, result_nonce = null,
                        result_schema_version = null, result_expires_at = null,
                        model_alias = null, actual_model = null, prompt_version = null,
                        config_version = null, source_comment_ids = null, generated_at = null,
                        source_map_digest = null, source_chunk_ids = null,
                        updated_at = clock_timestamp() where job_id = %s
                    """,
                    (row["job_id"],),
                )
        return len(rows)

    def purge_expired_cache_entries(self, limit: int = 1000) -> int:
        with self.database.transaction() as connection:
            rows = connection.execute(
                """
                select cache_key from ai_result_cache
                where expires_at <= clock_timestamp() or invalidated_at is not null
                order by coalesce(invalidated_at, expires_at), cache_key
                for update skip locked limit %s
                """,
                (limit,),
            ).fetchall()
            for row in rows:
                connection.execute("delete from ai_result_cache where cache_key = %s", (row["cache_key"],))
        return len(rows)

    def purge_expired_context_memories(self, limit: int = 1000) -> int:
        with self.database.transaction() as connection:
            rows = connection.execute(
                """
                select memory_id from ai_context_memories
                where expires_at <= clock_timestamp() or status = 'INVALIDATED'
                order by coalesce(invalidated_at, expires_at), memory_id
                for update skip locked limit %s
                """,
                (limit,),
            ).fetchall()
            for row in rows:
                connection.execute(
                    "delete from ai_context_memories where memory_id = %s",
                    (row["memory_id"],),
                )
        return len(rows)

    def purge_expired_shared_executions(self, limit: int = 1000) -> int:
        with self.database.transaction() as connection:
            rows = connection.execute(
                """
                select execution.execution_id
                from ai_shared_executions execution
                where execution.status <> 'RUNNING'
                  and execution.completed_at <= clock_timestamp() - interval '30 days'
                  and not exists (
                      select 1 from ai_jobs job
                      where job.shared_execution_id = execution.execution_id
                        and (
                            job.completed_at is null
                            or job.completed_at > clock_timestamp() - interval '30 days'
                            or job.result_ciphertext is not null
                        )
                  )
                  and not exists (
                      select 1 from ai_cost_ledger cost
                      where cost.execution_id = execution.execution_id
                        and cost.status in ('RESERVED', 'UNKNOWN')
                  )
                order by execution.completed_at, execution.execution_id
                for update skip locked
                limit %s
                """,
                (limit,),
            ).fetchall()
            for row in rows:
                execution_id = row["execution_id"]
                connection.execute(
                    "update ai_provider_calls set execution_id = null where execution_id = %s",
                    (execution_id,),
                )
                connection.execute(
                    "update ai_cost_ledger set execution_id = null where execution_id = %s",
                    (execution_id,),
                )
                connection.execute(
                    "update ai_jobs set shared_execution_id = null where shared_execution_id = %s",
                    (execution_id,),
                )
                connection.execute(
                    "delete from ai_shared_executions where execution_id = %s",
                    (execution_id,),
                )
        return len(rows)

    def purge_expired_metadata(self, limit: int = 1000) -> int:
        with self.database.transaction() as connection:
            rows = connection.execute(
                """
                select job.job_id
                from ai_jobs job
                where job.completed_at <= clock_timestamp() - interval '30 days'
                  and job.result_ciphertext is null
                  and not exists (
                      select 1 from ai_cost_ledger cost
                      where cost.job_id = job.job_id and cost.status in ('RESERVED', 'UNKNOWN')
                  )
                  and not exists (
                      select 1 from ai_feedback feedback
                      where feedback.job_id = job.job_id and feedback.exported_revision < feedback.source_revision
                  )
                order by job.completed_at, job.job_id
                for update skip locked
                limit %s
                """,
                (limit,),
            ).fetchall()
            for row in rows:
                job_id = row["job_id"]
                connection.execute("delete from ai_feedback where job_id = %s", (job_id,))
                connection.execute("delete from ai_feedback_inbox where job_id = %s", (job_id,))
                connection.execute("delete from ai_dispatch_outbox where job_id = %s", (job_id,))
                connection.execute("delete from ai_operations where job_id = %s", (job_id,))
                connection.execute("update ai_dead_letters set job_id = null where job_id = %s", (job_id,))
                connection.execute("delete from ai_jobs where job_id = %s", (job_id,))
        return len(rows)

    def status_counts(self) -> dict[str, int]:
        with self.database.connection() as connection:
            rows = connection.execute("select status, count(*) as count from ai_jobs group by status").fetchall()
            return {row["status"]: row["count"] for row in rows}

    def shared_execution_status_counts(self) -> dict[str, int]:
        with self.database.connection() as connection:
            rows = connection.execute(
                "select status, count(*) as count from ai_shared_executions group by status"
            ).fetchall()
            return {row["status"]: row["count"] for row in rows}

    def increment_telemetry_counter(self, counter_key: str) -> None:
        with self.database.transaction() as connection:
            updated = connection.execute(
                """
                update ai_telemetry_counters
                set counter_value = counter_value + 1, updated_at = clock_timestamp()
                where counter_key = %s
                """,
                (counter_key,),
            ).rowcount
            if updated != 1:
                raise ValueError("unsupported telemetry counter")

    def telemetry_status(self, enabled: bool) -> dict[str, object]:
        with self.database.connection() as connection:
            rows = connection.execute(
                "select counter_key, counter_value from ai_telemetry_counters"
            ).fetchall()
        counters = {row["counter_key"]: int(row["counter_value"]) for row in rows}
        return {
            "enabled": enabled,
            "dropped": {
                key: counters.get(key, 0)
                for key in ("jobStart", "jobUpdate", "jobEnd", "providerObservation", "flush")
            },
            "feedbackRetries": counters.get("feedbackRetry", 0),
        }

    def operational_status(self) -> dict[str, object]:
        with self.database.connection() as connection:
            budget_rows = connection.execute(
                """
                select status, coalesce(sum(case when status = 'SETTLED' then settled_microusd else reserved_microusd end), 0) as amount
                from ai_cost_ledger group by status
                """
            ).fetchall()
            budgets = {row["status"]: int(row["amount"]) for row in budget_rows}
            public_revisions = connection.execute(
                "select count(*) as count from ai_kb_revisions where status = 'PUBLIC'"
            ).fetchone()["count"]
            dead_letters = connection.execute("select count(*) as count from ai_dead_letters").fetchone()["count"]
            last_reconciled_at = connection.execute(
                "select max(completed_at) as value from ai_kb_reconciliation_runs where status = 'SUCCEEDED'"
            ).fetchone()["value"]
        return {
            "budget": {
                "reservedMicrousd": budgets.get("RESERVED", 0),
                "settledMicrousd": budgets.get("SETTLED", 0),
                "unknownMicrousd": budgets.get("UNKNOWN", 0),
            },
            "index": {
                "publicRevisions": int(public_revisions),
                "lastReconciledAt": last_reconciled_at.isoformat() if last_reconciled_at else None,
            },
            "deadLetterCount": int(dead_letters),
        }
