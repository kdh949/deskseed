from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

from psycopg.types.json import Jsonb

from .config import Settings
from .db import Database
from .schemas import (
    Accepted,
    CancellationEnvelope,
    Feature,
    FeedbackRequest,
    GenerationProvenance,
    IndexEvent,
    JobEnvelope,
    JobPhase,
    JobReceipt,
    JobStatus,
    OperationRequest,
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


@dataclass(frozen=True)
class ReconciliationRun:
    run_id: UUID
    workspace_key: str
    snapshot_token: UUID
    snapshot_expires_at: datetime
    next_cursor: UUID | None


@dataclass(frozen=True)
class ClaimedJob:
    job_id: UUID
    generation: int
    lease_epoch: int
    feature: Feature
    workspace_key: str
    requester_id: UUID
    context_revision: str
    deadline_at: datetime
    options: dict[str, str]


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


class Repository:
    def __init__(self, database: Database, settings: Settings, cipher: EnvelopeCipher):
        self.database = database
        self.settings = settings
        self.cipher = cipher

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
                    request_fingerprint, traceparent, tracestate, created_at, deadline_at, updated_at
                ) values (
                    %s, %s, %s, %s, %s, %s, %s, %s, 1, 0, %s, %s,
                    %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
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
                "select workspace_key, request_revision, status from ai_jobs where job_id = %s for update",
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
        return Accepted(replayed=False, jobId=envelope.jobId)

    def get_job(self, job_id: UUID, include_result: bool = True) -> JobReceipt:
        now = datetime.now(UTC)
        with self.database.connection() as connection:
            row = connection.execute("select * from ai_jobs where job_id = %s", (job_id,)).fetchone()
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
            context_revision=row["context_revision"],
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

    def reserve_budget(
        self,
        claim: ClaimedJob,
        model_alias: str,
        pricing_version: str,
        reserve_microusd: int,
        call_type: str,
    ) -> UUID:
        reservation = self._reserve_budget(
            workspace_key=claim.workspace_key,
            requester_id=claim.requester_id,
            job_id=claim.job_id,
            operation_key=f"{claim.job_id}:{claim.generation}:{call_type}",
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
                return None
            workspace_spend = connection.execute(
                """
                select coalesce(sum(case when status = 'SETTLED' then settled_microusd else reserved_microusd end), 0) as total
                from ai_cost_ledger where workspace_key = %s and budget_date = %s and status in ('RESERVED', 'SETTLED', 'UNKNOWN')
                """,
                (workspace_key, budget_day),
            ).fetchone()["total"]
            actor_spend = 0
            job_spend = 0
            if requester_id is not None:
                actor_spend = connection.execute(
                    """
                    select coalesce(sum(case when status = 'SETTLED' then settled_microusd else reserved_microusd end), 0) as total
                    from ai_cost_ledger where workspace_key = %s and requester_id = %s and budget_date = %s
                      and status in ('RESERVED', 'SETTLED', 'UNKNOWN')
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
                    reservation_id, operation_key, job_id, workspace_key, requester_id,
                    budget_bucket, call_type, budget_date, status, reserved_microusd,
                    pricing_version, model_alias, created_at
                ) values (%s, %s, %s, %s, %s, %s, %s, %s, 'RESERVED', %s, %s, %s, %s)
                """,
                (
                    reservation_id,
                    operation_key,
                    job_id,
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
                    settled_at = clock_timestamp(), unknown_since = null
                where reservation_id = %s and status in ('RESERVED', 'UNKNOWN')
                  and reserved_microusd >= %s
                """,
                (actual_microusd, reservation_id, actual_microusd),
            ).rowcount
            if updated == 0:
                existing = connection.execute(
                    "select status, settled_microusd from ai_cost_ledger where reservation_id = %s",
                    (reservation_id,),
                ).fetchone()
                if not existing or existing["status"] != "SETTLED" or existing["settled_microusd"] != actual_microusd:
                    raise ValueError("actual cost exceeds or conflicts with the reservation")

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
        cost_microusd: int,
        actual_model: str,
        source_comment_ids: list[UUID],
        prompt_version: str,
    ) -> None:
        now = datetime.now(UTC)
        ciphertext, nonce = self.cipher.encrypt(
            result.model_dump_json().encode(), str(claim.job_id).encode()
        )
        with self.database.transaction() as connection:
            updated = connection.execute(
                """
                update ai_jobs set status = %s, phase = 'COMPLETE', result_schema_version = 1,
                    result_ciphertext = %s, result_nonce = %s, result_expires_at = %s,
                    model_alias = %s, actual_model = %s, prompt_version = %s,
                    config_version = %s, source_comment_ids = %s, generated_at = %s,
                    cost_microusd = %s, completed_at = %s, updated_at = %s,
                    lease_owner = null, lease_expires_at = null, error_code = null
                where job_id = %s and generation = %s and lease_epoch = %s and status = 'RUNNING'
                """,
                (
                    status.value,
                    ciphertext,
                    nonce,
                    now + timedelta(days=7),
                    actual_model,
                    actual_model,
                    prompt_version,
                    self.settings.config_version,
                    source_comment_ids,
                    now,
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

    def complete_needs_review(self, claim: ClaimedJob, error_code: str, cost_microusd: int) -> None:
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            updated = connection.execute(
                """
                update ai_jobs set status = 'NEEDS_REVIEW', phase = 'COMPLETE',
                    result_schema_version = null, result_ciphertext = null, result_nonce = null,
                    result_expires_at = null, model_alias = null, actual_model = null,
                    prompt_version = null, config_version = null, source_comment_ids = '{}',
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

    def terminate_job(self, claim: ClaimedJob, status: JobStatus, error_code: str) -> None:
        if status not in {JobStatus.SUPERSEDED, JobStatus.CANCELLED, JobStatus.EXPIRED, JobStatus.FAILED}:
            raise ValueError("unsupported terminal status")
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            connection.execute(
                """
                update ai_jobs set status = %s, phase = 'COMPLETE', error_code = %s,
                    completed_at = %s, updated_at = %s, lease_owner = null, lease_expires_at = null
                where job_id = %s and generation = %s and lease_epoch = %s and status = 'RUNNING'
                """,
                (status.value, error_code[:80], now, now, claim.job_id, claim.generation, claim.lease_epoch),
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
                select source_version, action from ai_kb_article_state
                where workspace_key = %s and article_id = %s for update
                """,
                (event.workspaceKey, event.articleId),
            ).fetchone()
            if current and event.sourceVersion < current["source_version"]:
                return Accepted(replayed=False)
            if current and event.sourceVersion == current["source_version"]:
                if event.action != current["action"]:
                    raise ConflictError("index event source version has conflicting actions")
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
                    status, attempts, available_at, created_at
                ) values (%s, %s, %s, %s, %s, %s, %s, 'PENDING', 0, clock_timestamp(), %s)
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
            if event.action == "DELETE":
                connection.execute(
                    """
                    update ai_kb_revisions set status = 'DELETED', deleted_at = clock_timestamp()
                    where workspace_key = %s and article_id = %s
                    """,
                    (event.workspaceKey, event.articleId),
                )
        return Accepted(replayed=False)

    def claim_index_events(self, owner: str, limit: int = 10) -> list[IndexEvent]:
        now = datetime.now(UTC)
        lease_until = now + timedelta(seconds=self.settings.lease_seconds)
        with self.database.transaction() as connection:
            rows = connection.execute(
                """
                select event_id, workspace_key, article_id, revision_id, action, source_version,
                       public_revision, created_at
                from ai_kb_index_jobs
                where (status = 'PENDING' and available_at <= %s)
                   or (status = 'LEASED' and lease_expires_at <= %s)
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
            IndexEvent(
                schemaVersion=1,
                eventId=row["event_id"],
                workspaceKey=row["workspace_key"],
                articleId=row["article_id"],
                revisionId=row["revision_id"],
                action=row["action"],
                sourceVersion=row["source_version"],
                publicRevision=row["public_revision"],
                createdAt=row["created_at"],
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
                select run_id, workspace_key, snapshot_token, snapshot_expires_at, next_cursor
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
                    max(completed_at) filter (where status = 'SUCCEEDED') as last_succeeded_at
                from ai_kb_reconciliation_runs where workspace_key = %s
                """,
                (workspace_key,),
            ).fetchone()
        now = datetime.now(UTC)
        last_started = row["last_started_at"]
        last_succeeded = row["last_succeeded_at"]
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
    ) -> bool:
        with self.database.transaction() as connection:
            connection.execute("select pg_advisory_xact_lock(hashtext('ai-kb-reconciliation'), hashtext(%s))", (workspace_key,))
            running = connection.execute(
                "select 1 from ai_kb_reconciliation_runs where workspace_key = %s and status = 'RUNNING'",
                (workspace_key,),
            ).fetchone()
            if running:
                return False
            connection.execute(
                """
                insert into ai_kb_reconciliation_runs (
                    run_id, workspace_key, snapshot_token, snapshot_expires_at, status, started_at
                ) values (%s, %s, %s, %s, 'RUNNING', clock_timestamp())
                """,
                (run_id, workspace_key, snapshot_token, snapshot_expires_at),
            )
        return True

    def record_reconciliation_page(
        self,
        run: ReconciliationRun,
        items: list[tuple[UUID, UUID]],
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
            for article_id, revision_id in items:
                connection.execute(
                    """
                    insert into ai_kb_reconciliation_seen (run_id, article_id, revision_id)
                    values (%s, %s, %s)
                    on conflict (run_id, article_id) do update set revision_id = excluded.revision_id
                    """,
                    (run.run_id, article_id, revision_id),
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
                    update ai_kb_revisions revision
                    set status = 'DELETED', deleted_at = clock_timestamp()
                    where revision.workspace_key = %s and revision.status = 'PUBLIC'
                      and not exists (
                          select 1 from ai_kb_reconciliation_seen seen
                          where seen.run_id = %s and seen.article_id = revision.article_id
                            and seen.revision_id = revision.revision_id
                      )
                    """,
                    (run.workspace_key, run.run_id),
                )
                connection.execute(
                    """
                    update ai_kb_reconciliation_runs
                    set status = 'SUCCEEDED', completed_at = clock_timestamp(), last_error_code = null
                    where run_id = %s
                    """,
                    (run.run_id,),
                )
        return True

    def fail_reconciliation(self, run_id: UUID, error_code: str) -> None:
        with self.database.transaction() as connection:
            connection.execute(
                """
                update ai_kb_reconciliation_runs
                set status = 'FAILED', completed_at = clock_timestamp(), last_error_code = %s
                where run_id = %s and status = 'RUNNING'
                """,
                (error_code[:80], run_id),
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
                    update ai_jobs set result_ciphertext = null, result_nonce = null,
                        result_schema_version = null, result_expires_at = null,
                        model_alias = null, actual_model = null, prompt_version = null,
                        config_version = null, source_comment_ids = null, generated_at = null,
                        updated_at = clock_timestamp() where job_id = %s
                    """,
                    (row["job_id"],),
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
