from __future__ import annotations

import logging
import threading
from contextlib import contextmanager
from pathlib import Path
from typing import Any
from uuid import UUID

from redis import Redis
from redis.exceptions import ResponseError

from .backend_client import (
    BackendAuthorizationError,
    BackendClient,
    BackendPolicyDisabledError,
    BackendSupersededError,
)
from .config import Settings
from .observability import TraceAdapter, TraceAttributes
from .pricing import PricingCatalog, Usage
from .providers import GenerationProvider
from .repository import BudgetExceededError, ClaimedJob, Repository, StaleLeaseError
from .retrieval import KnowledgeRepository
from .schemas import Feature, JobPhase, JobStatus, ReplyDraftResult, TriageResult
from .workflows import ReplyWorkflow

LOGGER = logging.getLogger(__name__)


class InputTooLongError(RuntimeError):
    pass


class InvalidModelOutputError(RuntimeError):
    pass


class StreamRuntime:
    def __init__(
        self,
        settings: Settings,
        repository: Repository,
        backend: BackendClient,
        provider: GenerationProvider,
        knowledge: KnowledgeRepository,
        traces: TraceAdapter,
        pricing_path: Path,
    ):
        self.settings = settings
        self.repository = repository
        self.backend = backend
        self.provider = provider
        self.traces = traces
        self.pricing = PricingCatalog(pricing_path)
        self.redis = Redis.from_url(settings.redis_url.get_secret_value(), decode_responses=True)
        self.reply_workflow = ReplyWorkflow(provider, knowledge)

    def ensure_group(self) -> None:
        try:
            self.redis.xgroup_create(
                name=self.settings.stream_name,
                groupname=self.settings.stream_group,
                id="0",
                mkstream=True,
            )
        except ResponseError as exception:
            if "BUSYGROUP" not in str(exception):
                raise

    def ping(self) -> bool:
        try:
            return bool(self.redis.ping())
        except Exception:
            return False

    def dispatch_once(self, limit: int = 20) -> int:
        events = self.repository.claim_dispatch(self.settings.consumer_name, limit)
        delivered = 0
        for event in events:
            try:
                self.redis.xadd(
                    self.settings.stream_name,
                    {
                        "schema_version": "1",
                        "job_id": str(event.job_id),
                        "generation": str(event.generation),
                        "traceparent": event.traceparent or "",
                        "tracestate": event.tracestate or "",
                    },
                )
                self.repository.mark_dispatched(event.event_id, self.settings.consumer_name)
                delivered += 1
            except Exception as exception:
                LOGGER.warning(
                    "AI dispatch failed",
                    extra={"event_id": str(event.event_id), "error": type(exception).__name__},
                )
                self.repository.release_dispatch(event.event_id, self.settings.consumer_name, type(exception).__name__)
        return delivered

    def consume_once(self, block_ms: int = 100, count: int = 10) -> int:
        self.ensure_group()
        messages = self.redis.xreadgroup(
            groupname=self.settings.stream_group,
            consumername=self.settings.consumer_name,
            streams={self.settings.stream_name: ">"},
            count=count,
            block=block_ms,
        )
        return self._handle_messages(messages)

    def recover_once(self, min_idle_ms: int | None = None, count: int = 10) -> int:
        self.ensure_group()
        self.repository.requeue_stranded_dispatches()
        self.dispatch_once(limit=count)
        idle = min_idle_ms if min_idle_ms is not None else self.settings.lease_seconds * 1000
        claimed = self.redis.xautoclaim(
            name=self.settings.stream_name,
            groupname=self.settings.stream_group,
            consumername=self.settings.consumer_name,
            min_idle_time=idle,
            start_id="0-0",
            count=count,
        )
        messages = claimed[1] if len(claimed) >= 2 else []
        return self._handle_messages([(self.settings.stream_name, messages)])

    def _handle_messages(self, batches: list[Any]) -> int:
        handled = 0
        for _, messages in batches:
            for message_id, fields in messages:
                try:
                    allowed = {"schema_version", "job_id", "generation", "traceparent", "tracestate"}
                    if set(fields) - allowed:
                        raise ValueError("stream message contains non-allowlisted fields")
                    if fields.get("schema_version") != "1":
                        raise ValueError("unsupported stream schema")
                    claim = self.repository.claim_job(
                        UUID(fields["job_id"]), int(fields["generation"]), self.settings.consumer_name
                    )
                    if claim is not None:
                        self._execute(claim, fields.get("traceparent") or None)
                    self.redis.xack(self.settings.stream_name, self.settings.stream_group, message_id)
                    handled += 1
                except StaleLeaseError:
                    self.redis.xack(self.settings.stream_name, self.settings.stream_group, message_id)
                    handled += 1
                except Exception as exception:
                    LOGGER.exception(
                        "AI stream message failed",
                        extra={"message_id": message_id, "error": type(exception).__name__},
                    )
        return handled

    def _execute(self, claim: ClaimedJob, traceparent: str | None) -> None:
        reservations: list[UUID] = []
        attributes = TraceAttributes(
            job_id=claim.job_id,
            feature=claim.feature.value,
            prompt_version=self.settings.prompt_version,
            graph_version=self.settings.graph_version,
            config_version=self.settings.config_version,
            context_revision=claim.context_revision,
            generation=claim.generation,
            lease_epoch=claim.lease_epoch,
        )
        with self.traces.job(attributes), self._heartbeat(claim):
            try:
                context = self.backend.read_context(claim.job_id, traceparent)
                if context.contextRevision != claim.context_revision:
                    raise BackendSupersededError("context revision mismatch")
                policy = self.backend.read_policy(claim.feature.value)
                model = self.settings.model_standard if claim.feature == Feature.REPLY_DRAFT else self.settings.model_fast
                if model not in {policy.fastModelAlias, policy.standardModelAlias}:
                    raise BackendPolicyDisabledError("configured model alias differs from backend policy")
                context = _bounded_context(context, claim.feature)
                output_limit = 2048 if claim.feature == Feature.REPLY_DRAFT else 1024 if claim.feature == Feature.SUMMARY else 768
                generation_reservation = self.repository.reserve_budget(
                    claim,
                    model,
                    self.pricing.version,
                    self.pricing.upper_bound_microusd(model, 16_000, output_limit),
                    "GENERATION",
                )
                reservations.append(generation_reservation)
                query_reservation = None
                query_cost = 0
                if claim.feature == Feature.REPLY_DRAFT:
                    query_reservation = self.repository.reserve_budget(
                        claim,
                        self.settings.embedding_model,
                        self.pricing.version,
                        self.pricing.upper_bound_microusd(self.settings.embedding_model, 2_000),
                        "QUERY_EMBEDDING",
                    )
                    reservations.append(query_reservation)
                    self.repository.set_phase(claim, JobPhase.RETRIEVE)
                    reply_execution = self.reply_workflow.invoke(context, claim.workspace_key)
                    generated = reply_execution.generation
                    query_cost = self.pricing.cost_microusd(
                        self.settings.embedding_model,
                        Usage(reply_execution.query_embedding_tokens, 0, 0),
                    )
                    self.repository.settle_budget(query_reservation, query_cost)
                elif claim.feature == Feature.SUMMARY:
                    self.repository.set_phase(claim, JobPhase.GENERATE)
                    generated = self.provider.summary(context)
                else:
                    self.repository.set_phase(claim, JobPhase.GENERATE)
                    generated = self.provider.triage(context)
                self.repository.set_phase(claim, JobPhase.VALIDATE)
                generation_cost = self.pricing.cost_microusd(generated.model, generated.usage)
                self.repository.settle_budget(generation_reservation, generation_cost)
                cost = generation_cost + query_cost
                current = self.backend.read_context_revision(claim.job_id)
                if current.contextRevision != claim.context_revision:
                    raise BackendSupersededError("context changed before result commit")
                self.backend.read_policy(claim.feature.value)
                result = generated.result
                if isinstance(result, TriageResult) and result.suggestedTagIds:
                    raise InvalidModelOutputError("triage tags require a backend allowlist")
                if isinstance(result, ReplyDraftResult) and result.citations:
                    result = result.model_copy(
                        update={"citations": self.backend.authorize_citations(claim.job_id, result.citations)}
                    )
                needs_review = isinstance(result, ReplyDraftResult) and not result.citations
                if needs_review:
                    self.repository.complete_needs_review(claim, "NO_APPROVED_KNOWLEDGE", cost)
                else:
                    self.repository.complete_job(
                        claim,
                        result,
                        JobStatus.SUCCEEDED,
                        cost,
                        generated.model,
                        [item.id for item in context.comments],
                    )
            except BackendSupersededError:
                self.repository.terminate_job(claim, JobStatus.SUPERSEDED, "CONTEXT_SUPERSEDED")
            except BackendAuthorizationError:
                self.repository.fail_job(claim, "SOURCE_AUTHORIZATION_FAILED", retryable=False)
            except BackendPolicyDisabledError:
                self.repository.terminate_job(claim, JobStatus.FAILED, "AI_POLICY_DISABLED")
            except BudgetExceededError:
                self.repository.fail_job(claim, "BUDGET_EXCEEDED", retryable=False)
            except InputTooLongError:
                self.repository.fail_job(claim, "INPUT_TOO_LONG", retryable=False)
            except InvalidModelOutputError:
                self.repository.fail_job(claim, "MODEL_OUTPUT_INVALID", retryable=False)
            except Exception as exception:
                if reservations:
                    for reservation in reservations:
                        self.repository.mark_budget_unknown(reservation)
                    self.repository.fail_job(claim, "PROVIDER_OUTCOME_UNKNOWN", retryable=False)
                else:
                    self.repository.fail_job(claim, type(exception).__name__.upper()[:80], retryable=True)
                raise

    @contextmanager
    def _heartbeat(self, claim: ClaimedJob):
        stopped = threading.Event()

        def renew() -> None:
            interval = max(1.0, self.settings.lease_seconds / 3)
            while not stopped.wait(interval):
                try:
                    self.repository.renew_lease(claim)
                except Exception:
                    LOGGER.exception("AI lease heartbeat failed", extra={"job_id": str(claim.job_id)})
                    return

        thread = threading.Thread(target=renew, name=f"ai-lease-{claim.job_id}", daemon=True)
        thread.start()
        try:
            yield
        finally:
            stopped.set()
            thread.join(timeout=1.0)


def _bounded_context(context, feature: Feature):
    """Uses a conservative UTF-8 byte count as a token upper bound."""
    if feature in {Feature.SUMMARY, Feature.TRIAGE}:
        if sum(len(item.body.encode("utf-8")) for item in context.comments) > 16_000:
            raise InputTooLongError("PUBLIC context exceeds the configured input bound")
        return context
    first = context.comments[0]
    if len(first.body.encode("utf-8")) > 8_000:
        raise InputTooLongError("first PUBLIC inquiry exceeds the configured reply bound")
    selected = [first]
    used = len(first.body.encode("utf-8"))
    for item in reversed(context.comments[1:]):
        size = len(item.body.encode("utf-8"))
        if used + size > 8_000:
            continue
        selected.insert(1, item)
        used += size
    return context.model_copy(update={"comments": selected})
