from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
from typing import Iterator
from uuid import UUID

from .config import Settings


@dataclass(frozen=True)
class TraceAttributes:
    job_id: UUID
    feature: str
    prompt_version: str
    graph_version: str
    config_version: str
    context_revision: str
    generation: int
    lease_epoch: int


class TraceAdapter:
    """Metadata-only adapter; prompts, results, ticket text, and customer fields are never exported."""

    def __init__(self, settings: Settings):
        self._client = None
        if settings.langfuse_enabled:
            from langfuse import Langfuse

            self._client = Langfuse(
                public_key=settings.langfuse_public_key,
                secret_key=settings.langfuse_secret_key.get_secret_value(),
                host=settings.langfuse_host,
            )

    @contextmanager
    def job(self, attributes: TraceAttributes) -> Iterator[None]:
        if self._client is None:
            yield
            return
        observation = self._client.start_observation(
            name="deskseed-ai-job",
            as_type="span",
            metadata={
                "jobId": str(attributes.job_id),
                "feature": attributes.feature,
                "promptVersion": attributes.prompt_version,
                "graphVersion": attributes.graph_version,
                "configVersion": attributes.config_version,
                "contextRevision": attributes.context_revision,
                "generation": attributes.generation,
                "leaseEpoch": attributes.lease_epoch,
            },
        )
        try:
            yield
            observation.update(metadata={"outcome": "completed"})
        except Exception:
            observation.update(metadata={"outcome": "failed"}, level="ERROR")
            raise
        finally:
            observation.end()

    def flush(self) -> None:
        if self._client is not None:
            self._client.flush()

    def export_feedback(
        self,
        *,
        job_id: UUID,
        score_id: UUID,
        score_name: str,
        feedback_type: str,
        reason_code: str | None,
        timestamp,
    ) -> bool:
        if self._client is None:
            return False
        value = 0 if feedback_type == "unhelpful" else 1
        self._client.create_score(
            trace_id=job_id.hex,
            score_id=str(score_id),
            name=score_name,
            value=value,
            data_type="BOOLEAN",
            metadata={"feedbackType": feedback_type, "reasonCode": reason_code},
            timestamp=timestamp,
        )
        return True
