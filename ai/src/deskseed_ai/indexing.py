from __future__ import annotations

import logging
from pathlib import Path
from uuid import NAMESPACE_URL, UUID, uuid4, uuid5

from .backend_client import BackendClient
from .call_receipts import ProviderCallReceipt, ReceiptRecorder, UsageStatus
from .config import Settings
from .observability import CallTraceAttributes, TraceAdapter
from .pricing import PricingCatalog
from .repository import ConflictError, Repository
from .retrieval import KnowledgeRepository, chunk_public_article
from .schemas import IndexEvent

LOGGER = logging.getLogger(__name__)


class IndexingService:
    def __init__(
        self,
        backend: BackendClient,
        knowledge: KnowledgeRepository,
        repository: Repository,
        settings: Settings,
        pricing_path: Path,
        traces: TraceAdapter,
    ):
        self.backend = backend
        self.knowledge = knowledge
        self.repository = repository
        self.settings = settings
        self.pricing = PricingCatalog(pricing_path)
        self.traces = traces
        self.owner = f"indexer-{uuid4()}"

    def process_once(self, limit: int = 10) -> int:
        completed = 0
        for event in self.repository.claim_index_events(self.owner, limit):
            try:
                self.process(event)
                self.repository.mark_index_event_succeeded(event.eventId, self.owner)
                completed += 1
            except Exception as exception:
                LOGGER.warning(
                    "AI knowledge indexing failed",
                    extra={"event_id": str(event.eventId), "error": type(exception).__name__},
                )
                self.repository.release_index_event(event.eventId, self.owner, type(exception).__name__.upper())
        return completed

    def cycle_once(self) -> int:
        processed = self.process_once()
        self.reconcile_once()
        return processed

    def reconcile_once(self, workspace_key: str = "default") -> bool:
        run = self.repository.current_reconciliation(workspace_key)
        if run is None:
            if not self.repository.reconciliation_due(workspace_key):
                return False
            page = self.backend.read_public_manifest(limit=self.settings.reconciliation_page_size)
            run = self._run_from_first_page(workspace_key, page)
            if run is None:
                return False
        else:
            try:
                page = self.backend.read_public_manifest(
                    snapshot_token=run.snapshot_token,
                    cursor=run.next_cursor,
                    limit=self.settings.reconciliation_page_size,
                )
            except Exception as exception:
                self.repository.fail_reconciliation(run.run_id, type(exception).__name__.upper())
                raise
        try:
            for item in page.items:
                event = IndexEvent(
                    schemaVersion=1,
                    eventId=uuid5(
                        NAMESPACE_URL,
                        f"deskseed:kb:{workspace_key}:{item.articleId}:{item.revisionId}:{item.publicRevision}",
                    ),
                    workspaceKey=workspace_key,
                    articleId=item.articleId,
                    revisionId=item.revisionId,
                    action="UPSERT",
                    sourceVersion=item.sourceVersion,
                    publicRevision=item.publicRevision,
                    createdAt=item.publishedAt,
                )
                self.repository.accept_index_event(event)
            return self.repository.record_reconciliation_page(
                run,
                [(item.articleId, item.revisionId) for item in page.items],
                page.nextCursor,
            )
        except Exception as exception:
            self.repository.fail_reconciliation(run.run_id, type(exception).__name__.upper())
            raise

    def _run_from_first_page(self, workspace_key, page):
        run = self.repository.current_reconciliation(workspace_key)
        if run is not None:
            return None
        run_id = uuid4()
        if not self.repository.begin_reconciliation(
            run_id,
            workspace_key,
            page.snapshotToken,
            page.expiresAt,
        ):
            return None
        return self.repository.current_reconciliation(workspace_key)

    def process(self, event: IndexEvent) -> int:
        if event.action == "DELETE":
            return 0
        article = self.backend.read_public_article(event.articleId, event.revisionId, event.eventId)
        if article.dataClass != "PUBLIC_KB_ONLY":
            raise ConflictError("knowledge source data class is not PUBLIC_KB_ONLY")
        if article.articleId != event.articleId or article.revisionId != event.revisionId:
            raise ConflictError("knowledge source binding mismatch")
        if article.sourceVersion != event.sourceVersion:
            raise ConflictError("knowledge source version mismatch")
        if article.publicRevision != event.publicRevision:
            raise ConflictError("knowledge source revision mismatch")
        chunks = chunk_public_article(article.body)
        if not chunks:
            raise ConflictError("published knowledge article is empty")
        embedded = []
        for ordinal, chunk in enumerate(chunks):
            call_id = uuid4()
            reservation = self.repository.reserve_system_budget(
                event.workspaceKey,
                f"index:{event.eventId}:{ordinal}",
                self.settings.embedding_model,
                self.pricing.version,
                self.pricing.upper_bound_microusd(
                    self.settings.embedding_model,
                    self.pricing.count_text_tokens(self.settings.embedding_model, chunk),
                ),
            )
            self.repository.create_provider_call(
                reservation,
                call_id,
                self.settings.embedding_model,
                self.pricing.version,
                self.pricing.service_tier,
                self.pricing.context_price_band,
            )
            self.repository.mark_provider_call_dispatching(call_id)
            try:
                result = self.knowledge.embed_text(
                    chunk,
                    call_id,
                    self._receipt_recorder(call_id, reservation),
                )
                embedded.append((chunk, result.vector, result.receipt))
            except Exception:
                self.repository.mark_provider_call_unknown(call_id)
                raise
        tokens, _ = self.knowledge.replace_public_revision_with_vectors(
            event.workspaceKey,
            article.articleId,
            article.revisionId,
            event.sourceVersion,
            event.eventId,
            article.slug,
            article.title,
            article.publicRevision,
            embedded,
        )
        return tokens

    def _receipt_recorder(self, expected_call_id: UUID, reservation_id: UUID) -> ReceiptRecorder:
        def record(receipt: ProviderCallReceipt) -> None:
            if receipt.call_id != expected_call_id:
                raise ValueError("provider receipt call identity mismatch")
            known_cost = None
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
                    trace_id=reservation_id.hex,
                    observation_id=expected_call_id.hex,
                    stage="INDEX_EMBEDDING",
                    pricing_version=self.pricing.version,
                    known_cost_microusd=persisted_cost,
                ),
                receipt,
            )

        return record
