from __future__ import annotations

import logging
import re
import threading
from collections import Counter
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Callable, Iterator
from uuid import UUID

from .call_receipts import ProviderCallReceipt, UsageStatus
from .config import Settings

LOGGER = logging.getLogger(__name__)

_TRACEPARENT = re.compile(
    r"^(?P<version>[0-9a-f]{2})-(?P<trace_id>[0-9a-f]{32})-(?P<span_id>[0-9a-f]{16})-(?P<flags>[0-9a-f]{2})$"
)
_DROP_KEYS = (
    "jobStart",
    "jobUpdate",
    "jobEnd",
    "providerObservation",
    "flush",
)


@dataclass(frozen=True)
class TraceAttributes:
    job_id: UUID
    feature: str
    prompt_version: str
    graph_version: str
    config_version: str
    context_policy_version: str
    generation: int
    lease_epoch: int
    traceparent: str | None


@dataclass(frozen=True)
class CallTraceAttributes:
    trace_id: str
    observation_id: str
    stage: str
    pricing_version: str
    known_cost_microusd: int | None
    operation_version: str | None = None


class TraceAdapter:
    """Best-effort, metadata-only telemetry isolated from product state and accounting."""

    def __init__(
        self,
        settings: Settings,
        client: Any | None = None,
        counter_sink: Callable[[str], None] | None = None,
    ):
        self._environment = settings.environment
        self._provider_mode = settings.provider_mode
        self._counters: Counter[str] = Counter()
        self._counter_lock = threading.Lock()
        self._counter_sink = counter_sink
        self._client = client
        if client is None and settings.langfuse_enabled:
            from langfuse import Langfuse

            try:
                self._client = Langfuse(
                    public_key=settings.langfuse_public_key,
                    secret_key=settings.langfuse_secret_key.get_secret_value(),
                    host=settings.langfuse_host,
                )
            except Exception as exception:
                self._record_failure("jobStart", exception)

    @property
    def enabled(self) -> bool:
        return self._client is not None

    @contextmanager
    def job(self, attributes: TraceAttributes) -> Iterator[None]:
        observation = None
        if self._client is not None:
            try:
                observation = self._client.start_observation(
                    trace_context={"trace_id": attributes.job_id.hex},
                    name="deskseed-ai-job",
                    as_type="span",
                    version=attributes.graph_version,
                    metadata=self._job_metadata(attributes),
                )
            except Exception as exception:
                self._record_failure("jobStart", exception)
        try:
            yield
        except BaseException:
            if observation is not None:
                try:
                    observation.update(metadata={"outcome": "failed"}, level="ERROR")
                except Exception as exception:
                    self._record_failure("jobUpdate", exception)
            raise
        else:
            if observation is not None:
                try:
                    observation.update(metadata={"outcome": "completed"})
                except Exception as exception:
                    self._record_failure("jobUpdate", exception)
        finally:
            if observation is not None:
                try:
                    observation.end()
                except Exception as exception:
                    self._record_failure("jobEnd", exception)

    def export_provider_call(
        self,
        attributes: CallTraceAttributes,
        receipt: ProviderCallReceipt,
    ) -> bool:
        if self._client is None:
            return False
        usage_details = None
        if receipt.usage_status == UsageStatus.KNOWN and receipt.usage is not None:
            usage_details = {
                "input": receipt.usage.input_uncached_tokens,
                "cache_read_input_tokens": receipt.usage.input_cache_read_tokens,
                "cache_creation_input_tokens": receipt.usage.input_cache_write_tokens,
                "output": receipt.usage.output_billed_tokens,
            }
        cost_details = (
            {"total": attributes.known_cost_microusd / 1_000_000}
            if attributes.known_cost_microusd is not None
            else None
        )
        try:
            observation = self._client.start_observation(
                trace_context={"trace_id": attributes.trace_id},
                name=f"deskseed-ai-{attributes.stage.lower().replace('_', '-')}",
                as_type=(
                    "generation"
                    if attributes.stage in {"GENERATION", "CONTEXT_MEMORY"}
                    else "embedding"
                ),
                model=receipt.actual_model,
                version=attributes.pricing_version,
                usage_details=usage_details,
                cost_details=cost_details,
                metadata={
                    "environment": self._environment,
                    "providerMode": self._provider_mode,
                    "observationId": attributes.observation_id,
                    "stage": attributes.stage,
                    "requestedAlias": receipt.requested_alias,
                    "actualModel": receipt.actual_model,
                    "pricingVersion": attributes.pricing_version,
                    "operationVersion": attributes.operation_version,
                    "serviceTier": receipt.service_tier,
                    "contextPriceBand": receipt.context_price_band,
                    "usageSchemaVersion": receipt.usage_schema_version,
                    "usageStatus": receipt.usage_status.value,
                    "usageIssueCode": receipt.usage_issue_code,
                    "costMicrousd": attributes.known_cost_microusd,
                },
            )
            observation.end()
            return True
        except Exception as exception:
            self._record_failure("providerObservation", exception)
            return False

    def flush(self) -> None:
        if self._client is None:
            return
        try:
            self._client.flush()
        except Exception as exception:
            self._record_failure("flush", exception)

    def export_feedback(
        self,
        *,
        job_id: UUID,
        score_id: UUID,
        score_name: str,
        feedback_type: str,
        reason_code: str | None,
        timestamp: datetime,
    ) -> bool:
        if self._client is None:
            return False
        value = 0 if feedback_type == "unhelpful" else 1
        try:
            self._client.create_score(
                trace_id=job_id.hex,
                score_id=str(score_id),
                name=score_name,
                value=value,
                data_type="BOOLEAN",
                metadata={"feedbackType": feedback_type, "reasonCode": reason_code},
                timestamp=timestamp,
                environment=self._environment,
            )
            return True
        except Exception as exception:
            self._increment("feedbackRetry")
            LOGGER.warning(
                "AI telemetry export failed",
                extra={"telemetry_operation": "feedback", "error_type": type(exception).__name__},
            )
            return False

    def status(self) -> dict[str, object]:
        with self._counter_lock:
            dropped = {key: self._counters[key] for key in _DROP_KEYS}
            feedback_retries = self._counters["feedbackRetry"]
        return {
            "enabled": self.enabled,
            "dropped": dropped,
            "feedbackRetries": feedback_retries,
        }

    def _job_metadata(self, attributes: TraceAttributes) -> dict[str, object]:
        metadata: dict[str, object] = {
            "environment": self._environment,
            "providerMode": self._provider_mode,
            "feature": attributes.feature,
            "promptVersion": attributes.prompt_version,
            "graphVersion": attributes.graph_version,
            "configVersion": attributes.config_version,
            "contextPolicyVersion": attributes.context_policy_version,
            "generation": attributes.generation,
            "leaseEpoch": attributes.lease_epoch,
        }
        metadata.update(_upstream_link(attributes.traceparent))
        return metadata

    def _record_failure(self, operation: str, exception: Exception) -> None:
        self._increment(operation)
        LOGGER.warning(
            "AI telemetry export failed",
            extra={"telemetry_operation": operation, "error_type": type(exception).__name__},
        )

    def _increment(self, operation: str) -> None:
        with self._counter_lock:
            self._counters[operation] += 1
        if self._counter_sink is None:
            return
        try:
            self._counter_sink(operation)
        except Exception as exception:
            LOGGER.warning(
                "AI telemetry counter persistence failed",
                extra={"telemetry_operation": operation, "error_type": type(exception).__name__},
            )


def _upstream_link(traceparent: str | None) -> dict[str, str]:
    if traceparent is None:
        return {}
    match = _TRACEPARENT.fullmatch(traceparent.lower())
    if match is None:
        return {}
    trace_id = match.group("trace_id")
    span_id = match.group("span_id")
    if trace_id == "0" * 32 or span_id == "0" * 16:
        return {}
    return {"upstreamTraceId": trace_id, "upstreamSpanId": span_id}
