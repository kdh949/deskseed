from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

import pytest

from deskseed_ai.call_receipts import ProviderCallReceipt, UsageStatus
from deskseed_ai.config import Settings
from deskseed_ai.observability import CallTraceAttributes, TraceAdapter, TraceAttributes
from deskseed_ai.pricing import Usage


class RecordingObservation:
    def __init__(self, *, fail_update: bool = False, fail_end: bool = False):
        self.fail_update = fail_update
        self.fail_end = fail_end
        self.updates: list[dict[str, object]] = []
        self.ended = False

    def update(self, **kwargs):
        if self.fail_update:
            raise RuntimeError("sentinel update failure with protected body")
        self.updates.append(kwargs)

    def end(self):
        if self.fail_end:
            raise RuntimeError("sentinel end failure with protected body")
        self.ended = True


class RecordingClient:
    def __init__(self, observation: RecordingObservation | None = None):
        self.observation = observation or RecordingObservation()
        self.observations: list[dict[str, object]] = []
        self.scores: list[dict[str, object]] = []
        self.fail_start = False
        self.fail_score = False
        self.fail_flush = False

    def start_observation(self, **kwargs):
        if self.fail_start:
            raise RuntimeError("sentinel start failure with protected body")
        self.observations.append(kwargs)
        return self.observation

    def create_score(self, **kwargs):
        if self.fail_score:
            raise RuntimeError("sentinel score failure with protected body")
        self.scores.append(kwargs)

    def flush(self):
        if self.fail_flush:
            raise RuntimeError("sentinel flush failure with protected body")


def attributes(job_id=None) -> TraceAttributes:
    return TraceAttributes(
        job_id=job_id or uuid4(),
        feature="ticket.reply_draft",
        prompt_version="reply-v2",
        graph_version="reply-v1",
        config_version="config-v1",
        context_policy_version="public-comments-v1",
        generation=2,
        lease_epoch=3,
        traceparent="00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
    )


def receipt(call_id) -> ProviderCallReceipt:
    return ProviderCallReceipt(
        call_id=call_id,
        provider_request_id="provider-sensitive-request-id",
        requested_alias="openai/gpt-5.6-luna",
        actual_model="openai/gpt-5.6-luna-2026-09-01",
        usage_schema_version="fixture-v1",
        usage_status=UsageStatus.KNOWN,
        usage=Usage(10, 20, 3, 4),
        usage_issue_code=None,
        service_tier="standard",
        context_price_band="short",
    )


def test_job_trace_uses_explicit_identity_and_metadata_allowlist() -> None:
    client = RecordingClient()
    adapter = TraceAdapter(Settings(environment="test"), client)
    item = attributes()

    with adapter.job(item):
        pass

    exported = client.observations[0]
    assert exported["trace_context"] == {"trace_id": item.job_id.hex}
    assert "input" not in exported
    assert "output" not in exported
    metadata = exported["metadata"]
    assert metadata["upstreamTraceId"] == "4bf92f3577b34da6a3ce929d0e0e4736"
    assert metadata["upstreamSpanId"] == "00f067aa0ba902b7"
    assert set(metadata) == {
        "environment",
        "providerMode",
        "feature",
        "promptVersion",
        "graphVersion",
        "configVersion",
        "contextPolicyVersion",
        "generation",
        "leaseEpoch",
        "upstreamTraceId",
        "upstreamSpanId",
    }
    assert "jobId" not in metadata
    assert "contextRevision" not in metadata
    assert "traceparent" not in metadata
    assert client.observation.ended is True


def test_provider_observation_exports_only_receipt_allowlist() -> None:
    client = RecordingClient()
    adapter = TraceAdapter(Settings(environment="test"), client)
    call_id = uuid4()
    trace_id = uuid4().hex

    assert adapter.export_provider_call(
        CallTraceAttributes(trace_id, call_id.hex, "GENERATION", "pricing-v2", 1234),
        receipt(call_id),
    ) is True

    exported = client.observations[0]
    assert exported["trace_context"] == {"trace_id": trace_id}
    assert exported["usage_details"] == {
        "input": 10,
        "cache_read_input_tokens": 20,
        "cache_creation_input_tokens": 3,
        "output": 4,
    }
    assert exported["cost_details"] == {"total": 0.001234}
    assert exported["metadata"]["observationId"] == call_id.hex
    assert "provider-sensitive-request-id" not in repr(exported)
    assert "input" not in exported or exported["input"] is None
    assert "output" not in exported or exported["output"] is None

    memory_call_id = uuid4()
    assert adapter.export_provider_call(
        CallTraceAttributes(
            trace_id,
            memory_call_id.hex,
            "CONTEXT_MEMORY",
            "pricing-v2",
            321,
            "context-memory-v1:fixture",
        ),
        receipt(memory_call_id),
    ) is True
    memory_export = client.observations[1]
    assert memory_export["as_type"] == "generation"
    assert memory_export["metadata"]["stage"] == "CONTEXT_MEMORY"
    assert memory_export["metadata"]["operationVersion"] == "context-memory-v1:fixture"
    assert "input" not in memory_export or memory_export["input"] is None
    assert "output" not in memory_export or memory_export["output"] is None


@pytest.mark.parametrize(
    ("failure", "counter"),
    [("start", "jobStart"), ("update", "jobUpdate"), ("end", "jobEnd")],
)
def test_job_telemetry_failure_is_isolated(failure: str, counter: str) -> None:
    observation = RecordingObservation(fail_update=failure == "update", fail_end=failure == "end")
    client = RecordingClient(observation)
    client.fail_start = failure == "start"
    adapter = TraceAdapter(Settings(environment="test"), client)
    product_work = []

    with adapter.job(attributes()):
        product_work.append("committed")

    assert product_work == ["committed"]
    assert adapter.status()["dropped"][counter] == 1


def test_product_exception_is_preserved_when_telemetry_cleanup_fails() -> None:
    client = RecordingClient(RecordingObservation(fail_update=True, fail_end=True))
    adapter = TraceAdapter(Settings(environment="test"), client)

    with pytest.raises(LookupError, match="canonical failure"):
        with adapter.job(attributes()):
            raise LookupError("canonical failure")

    status = adapter.status()
    assert status["dropped"]["jobUpdate"] == 1
    assert status["dropped"]["jobEnd"] == 1


def test_feedback_provider_and_flush_failures_return_retryable_status() -> None:
    client = RecordingClient()
    client.fail_start = True
    client.fail_score = True
    client.fail_flush = True
    adapter = TraceAdapter(Settings(environment="test"), client)
    call_id = uuid4()

    assert adapter.export_provider_call(
        CallTraceAttributes(uuid4().hex, call_id.hex, "GENERATION", "pricing-v2", 10),
        receipt(call_id),
    ) is False
    assert adapter.export_feedback(
        job_id=uuid4(),
        score_id=uuid4(),
        score_name="deskseed.helpful",
        feedback_type="helpful",
        reason_code=None,
        timestamp=datetime.now(UTC),
    ) is False
    adapter.flush()

    status = adapter.status()
    assert status["dropped"]["providerObservation"] == 1
    assert status["dropped"]["flush"] == 1
    assert status["feedbackRetries"] == 1


def test_failure_counter_sink_is_best_effort_and_bounded() -> None:
    persisted: list[str] = []
    client = RecordingClient()
    client.fail_start = True
    adapter = TraceAdapter(Settings(environment="test"), client, persisted.append)

    with adapter.job(attributes()):
        pass

    assert persisted == ["jobStart"]

    def fail_sink(_operation: str) -> None:
        raise RuntimeError("counter database unavailable")

    adapter = TraceAdapter(Settings(environment="test"), client, fail_sink)
    with adapter.job(attributes()):
        pass
    assert adapter.status()["dropped"]["jobStart"] == 1


def test_invalid_traceparent_is_not_exported() -> None:
    client = RecordingClient()
    adapter = TraceAdapter(Settings(environment="test"), client)
    item = attributes()
    item = TraceAttributes(**(item.__dict__ | {"traceparent": "sentinel protected body"}))

    with adapter.job(item):
        pass

    assert "upstreamTraceId" not in client.observations[0]["metadata"]
    assert "sentinel protected body" not in repr(client.observations[0])
