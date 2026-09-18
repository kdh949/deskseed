from __future__ import annotations

import logging
from uuid import uuid4

from .observability import TraceAdapter
from .repository import Repository

LOGGER = logging.getLogger(__name__)


class FeedbackExporter:
    def __init__(self, repository: Repository, traces: TraceAdapter):
        self.repository = repository
        self.traces = traces
        self.owner = f"feedback-{uuid4()}"

    def export_once(self, limit: int = 20) -> int:
        exported = 0
        for item in self.repository.claim_feedback_exports(self.owner, limit):
            try:
                if not self.traces.export_feedback(
                    job_id=item.job_id,
                    score_id=item.score_id,
                    score_name=item.score_name,
                    feedback_type=item.feedback_type,
                    reason_code=item.reason_code,
                    timestamp=item.first_recorded_at,
                ):
                    self.repository.release_feedback_export(
                        item,
                        "LANGFUSE_DISABLED" if not self.traces.enabled else "LANGFUSE_EXPORT_FAILED",
                    )
                    continue
                self.repository.mark_feedback_exported(item)
                exported += 1
            except Exception as exception:
                LOGGER.warning(
                    "AI feedback export failed",
                    extra={"job_id": str(item.job_id), "error": type(exception).__name__},
                )
                self.repository.release_feedback_export(item, type(exception).__name__.upper())
        return exported
