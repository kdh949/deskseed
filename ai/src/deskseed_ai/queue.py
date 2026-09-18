from __future__ import annotations

import logging
import threading
from contextlib import contextmanager
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

from redis import Redis
from redis.exceptions import ResponseError

from .backend_client import (
    BackendAuthorizationError,
    BackendClient,
    BackendPolicyDisabledError,
    BackendSupersededError,
)
from .call_receipts import ProviderCallReceipt, ReceiptRecorder, UsageStatus
from .config import Settings
from .observability import CallTraceAttributes, TraceAdapter, TraceAttributes
from .pricing import PricingCatalog
from .prompting import prompt_for
from .providers import GenerationProvider, InvalidProviderOutputError
from .repository import (
    ActiveLeaseError,
    BudgetExceededError,
    ClaimedJob,
    ProviderCallStateUnknownError,
    Repository,
    StaleLeaseError,
)
from .retrieval import KnowledgeRepository
from .schemas import AuthorRole, Feature, JobPhase, JobStatus, ReplyDraftResult, TriageResult
from .workflows import (
    InvalidReplyOutputError,
    InvalidSourceAuthorizationError,
    ReplyWorkflow,
    reply_query,
)

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

    def consume_once(
        self,
        block_ms: int = 100,
        count: int = 10,
        min_idle_ms: int | None = None,
    ) -> int:
        self.ensure_group()
        idle = min_idle_ms if min_idle_ms is not None else self.settings.lease_seconds * 1000
        claimed = self.redis.xautoclaim(
            name=self.settings.stream_name,
            groupname=self.settings.stream_group,
            consumername=self.settings.consumer_name,
            min_idle_time=idle,
            start_id="0-0",
            count=count,
        )
        pending = claimed[1] if len(claimed) >= 2 else []
        handled = self._handle_messages([(self.settings.stream_name, pending)])
        remaining = max(0, count - len(pending))
        if remaining == 0:
            return handled
        messages = self.redis.xreadgroup(
            groupname=self.settings.stream_group,
            consumername=self.settings.consumer_name,
            streams={self.settings.stream_name: ">"},
            count=remaining,
            block=block_ms,
        )
        return handled + self._handle_messages(messages)

    def recover_once(self, min_idle_ms: int | None = None, count: int = 10) -> int:
        self.ensure_group()
        self.repository.requeue_stranded_dispatches()
        return self.dispatch_once(limit=count)

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
                except ActiveLeaseError:
                    LOGGER.info(
                        "AI stream message remains pending for the active lease owner",
                        extra={"message_id": message_id},
                    )
                except Exception as exception:
                    LOGGER.exception(
                        "AI stream message failed",
                        extra={"message_id": message_id, "error": type(exception).__name__},
                    )
        return handled

    def _execute(self, claim: ClaimedJob, traceparent: str | None) -> None:
        calls: list[UUID] = []
        attributes = TraceAttributes(
            job_id=claim.job_id,
            feature=claim.feature.value,
            prompt_version=prompt_for(claim.feature).version,
            graph_version=self.settings.graph_version,
            config_version=self.settings.config_version,
            context_policy_version=claim.context_policy_version,
            generation=claim.generation,
            lease_epoch=claim.lease_epoch,
            traceparent=claim.traceparent,
        )
        with self.traces.job(attributes), self._heartbeat(claim):
            try:
                source_map_digest: str | None = None
                source_chunk_ids: list[UUID] | None = None
                context = self.backend.read_context(claim.job_id, traceparent)
                if (
                    context.contextRevision != claim.context_revision
                    or context.aiInputRevision != claim.ai_input_revision
                    or context.inputPolicyVersion != claim.input_policy_version
                ):
                    raise BackendSupersededError("context revision mismatch")
                policy = self.backend.read_policy(claim.feature.value)
                model = self.settings.model_standard if claim.feature == Feature.REPLY_DRAFT else self.settings.model_fast
                if model not in {policy.fastModelAlias, policy.standardModelAlias}:
                    raise BackendPolicyDisabledError("configured model alias differs from backend policy")
                context = _bounded_context(context, claim.feature)
                if claim.feature == Feature.REPLY_DRAFT:
                    query = reply_query(context)
                    query_call_id, query_recorder = self._prepare_call(
                        claim,
                        self.settings.embedding_model,
                        self.pricing.count_text_tokens(self.settings.embedding_model, query),
                        0,
                        "QUERY_EMBEDDING",
                    )
                    calls.append(query_call_id)
                    self.repository.set_phase(claim, JobPhase.RETRIEVE)

                    def prepare_generation(source_context, knowledge):
                        call_id, recorder = self._prepare_call(
                            claim,
                            model,
                            self.provider.estimate_input_tokens(
                                self.pricing,
                                claim.feature,
                                source_context,
                                knowledge,
                                claim.options,
                            ),
                            2048,
                            "GENERATION",
                        )
                        calls.append(call_id)
                        self.repository.set_phase(claim, JobPhase.GENERATE)
                        return call_id, recorder

                    reply_execution = self.reply_workflow.invoke(
                        context,
                        claim.workspace_key,
                        lambda candidates: self.backend.authorize_citations(claim.job_id, candidates),
                        claim.options,
                        query_call_id,
                        query_recorder,
                        prepare_generation,
                    )
                    generated = reply_execution.generation
                    source_map_digest = reply_execution.source_map_digest
                    source_chunk_ids = list(reply_execution.source_chunk_ids) or None
                elif claim.feature == Feature.SUMMARY:
                    call_id, recorder = self._prepare_call(
                        claim,
                        model,
                        self.provider.estimate_input_tokens(
                            self.pricing, claim.feature, context, [], claim.options
                        ),
                        1024,
                        "GENERATION",
                    )
                    calls.append(call_id)
                    self.repository.set_phase(claim, JobPhase.GENERATE)
                    generated = self.provider.summary(context, claim.options, call_id, recorder)
                else:
                    call_id, recorder = self._prepare_call(
                        claim,
                        model,
                        self.provider.estimate_input_tokens(
                            self.pricing, claim.feature, context, [], claim.options
                        ),
                        768,
                        "GENERATION",
                    )
                    calls.append(call_id)
                    self.repository.set_phase(claim, JobPhase.GENERATE)
                    generated = self.provider.triage(context, claim.options, call_id, recorder)
                self.repository.set_phase(claim, JobPhase.VALIDATE)
                cost = self.repository.job_cost_microusd(claim.job_id)
                current = self.backend.read_context_revision(claim.job_id)
                if (
                    current.contextRevision != claim.context_revision
                    or current.aiInputRevision != claim.ai_input_revision
                    or current.inputPolicyVersion != claim.input_policy_version
                ):
                    raise BackendSupersededError("context changed before result commit")
                self.backend.read_policy(claim.feature.value)
                if generated is None:
                    self.repository.complete_needs_review(claim, "NO_APPROVED_KNOWLEDGE", cost)
                    return
                result = generated.result
                if isinstance(result, TriageResult) and result.suggestedTagIds:
                    raise InvalidModelOutputError("triage tags require a backend allowlist")
                if isinstance(result, ReplyDraftResult) and result.citations:
                    authorized = self.backend.authorize_citations(claim.job_id, result.citations)
                    if authorized != result.citations:
                        raise BackendSupersededError("reply citation changed before result commit")
                    result = result.model_copy(update={"citations": authorized})
                needs_review = isinstance(result, ReplyDraftResult) and not result.citations
                if needs_review:
                    self.repository.complete_needs_review(claim, "NO_APPROVED_KNOWLEDGE", cost)
                else:
                    self.repository.complete_job(
                        claim,
                        result,
                        JobStatus.SUCCEEDED,
                        cost,
                        generated.receipt.actual_model or generated.receipt.requested_alias,
                        [item.id for item in context.comments],
                        generated.prompt_version,
                        source_map_digest,
                        source_chunk_ids,
                    )
            except BackendSupersededError:
                self.repository.terminate_job(claim, JobStatus.SUPERSEDED, "CONTEXT_SUPERSEDED")
            except BackendAuthorizationError:
                self.repository.fail_job(claim, "SOURCE_AUTHORIZATION_FAILED", retryable=False)
            except InvalidSourceAuthorizationError:
                self.repository.fail_job(claim, "SOURCE_AUTHORIZATION_FAILED", retryable=False)
            except BackendPolicyDisabledError:
                self.repository.terminate_job(claim, JobStatus.FAILED, "AI_POLICY_DISABLED")
            except BudgetExceededError:
                self.repository.fail_job(claim, "BUDGET_EXCEEDED", retryable=False)
            except ProviderCallStateUnknownError:
                self.repository.fail_job(claim, "PROVIDER_OUTCOME_UNKNOWN", retryable=False)
            except InputTooLongError:
                self.repository.fail_job(claim, "INPUT_TOO_LONG", retryable=False)
            except InvalidProviderOutputError:
                self.repository.complete_needs_review(
                    claim,
                    "MODEL_OUTPUT_INVALID",
                    self.repository.job_cost_microusd(claim.job_id),
                )
            except InvalidModelOutputError:
                self.repository.complete_needs_review(
                    claim,
                    "MODEL_OUTPUT_INVALID",
                    self.repository.job_cost_microusd(claim.job_id),
                )
            except InvalidReplyOutputError:
                self.repository.complete_needs_review(
                    claim,
                    "MODEL_OUTPUT_INVALID",
                    self.repository.job_cost_microusd(claim.job_id),
                )
            except StaleLeaseError:
                self._mark_calls_unknown(calls)
                raise
            except Exception as exception:
                if calls:
                    self._mark_calls_unknown(calls)
                    self.repository.fail_job(claim, "PROVIDER_OUTCOME_UNKNOWN", retryable=False)
                else:
                    self.repository.fail_job(claim, type(exception).__name__.upper()[:80], retryable=True)
                raise

    def _prepare_call(
        self,
        claim: ClaimedJob,
        requested_alias: str,
        input_tokens: int,
        output_tokens: int,
        call_type: str,
    ) -> tuple[UUID, ReceiptRecorder]:
        reserve = self.pricing.upper_bound_microusd(
            requested_alias,
            input_tokens,
            output_tokens,
            self.pricing.service_tier,
            self.pricing.context_price_band,
        )
        reservation_id = self.repository.reserve_budget(
            claim,
            requested_alias,
            self.pricing.version,
            reserve,
            call_type,
        )
        call_id = uuid4()
        self.repository.create_provider_call(
            reservation_id,
            call_id,
            requested_alias,
            self.pricing.version,
            self.pricing.service_tier,
            self.pricing.context_price_band,
        )
        self.repository.mark_provider_call_dispatching(call_id)
        return call_id, self._receipt_recorder(call_id, claim.job_id.hex, call_type)

    def _receipt_recorder(self, expected_call_id: UUID, trace_id: str, stage: str) -> ReceiptRecorder:
        def record(receipt: ProviderCallReceipt) -> None:
            if receipt.call_id != expected_call_id:
                raise ValueError("provider receipt call identity mismatch")
            known_cost: int | None = None
            if (
                receipt.usage_status == UsageStatus.KNOWN
                and receipt.usage is not None
                and receipt.actual_model is not None
            ):
                try:
                    known_cost = self.pricing.cost_microusd(
                        receipt.requested_alias,
                        receipt.actual_model,
                        receipt.usage,
                        receipt.service_tier,
                        receipt.context_price_band,
                    )
                except ValueError:
                    known_cost = None
            persisted_cost = self.repository.record_provider_response(receipt, known_cost)
            self.traces.export_provider_call(
                CallTraceAttributes(
                    trace_id=trace_id,
                    observation_id=expected_call_id.hex,
                    stage=stage,
                    pricing_version=self.pricing.version,
                    known_cost_microusd=persisted_cost,
                ),
                receipt,
            )

        return record

    def _mark_calls_unknown(self, call_ids: list[UUID]) -> None:
        for call_id in call_ids:
            try:
                self.repository.mark_provider_call_unknown(call_id)
            except Exception:
                LOGGER.exception(
                    "AI provider call could not be marked unknown",
                    extra={"call_id": str(call_id)},
                )

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
    comments = context.comments
    latest_customer_index = next(
        (
            index
            for index in range(len(comments) - 1, -1, -1)
            if comments[index].authorRole == AuthorRole.CUSTOMER
        ),
        len(comments) - 1,
    )
    selected_indexes = set(range(latest_customer_index, len(comments)))
    used = sum(len(comments[index].body.encode("utf-8")) for index in selected_indexes)
    if used > 8_000:
        raise InputTooLongError("latest PUBLIC request suffix exceeds the configured reply bound")

    latest_staff_index = next(
        (
            index
            for index in range(len(comments) - 1, -1, -1)
            if comments[index].authorRole == AuthorRole.STAFF
        ),
        None,
    )
    anchors = [0]
    if latest_staff_index is not None:
        anchors.append(latest_staff_index)
    candidates = anchors + list(range(latest_customer_index - 1, -1, -1))
    for index in candidates:
        if index in selected_indexes:
            continue
        size = len(comments[index].body.encode("utf-8"))
        if used + size <= 8_000:
            selected_indexes.add(index)
            used += size
    selected = [comment for index, comment in enumerate(comments) if index in selected_indexes]
    return context.model_copy(update={"comments": selected})
