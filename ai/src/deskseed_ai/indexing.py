from __future__ import annotations

import logging
from pathlib import Path
from uuid import NAMESPACE_URL, UUID, uuid4, uuid5

from .backend_client import BackendClient
from .call_receipts import ProviderCallReceipt, ReceiptRecorder, UsageStatus
from .config import Settings
from .observability import CallTraceAttributes, TraceAdapter
from .pricing import PricingCatalog
from .repository import ConflictError, IndexWorkItem, Repository
from .retrieval import (
    CHUNKER_VERSION,
    EMBEDDING_DIMENSION,
    INDEX_CONTRACT_VERSION,
    NORMALIZATION_VERSION,
    KnowledgeRepository,
    build_public_article_chunks,
)
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
                self.repository.mark_index_event_succeeded(event.event_id, self.owner)
                completed += 1
            except Exception as exception:
                LOGGER.warning(
                    "AI knowledge indexing failed",
                    extra={"event_id": str(event.event_id), "error": type(exception).__name__},
                )
                self.repository.release_index_event(event.event_id, self.owner, type(exception).__name__.upper())
        return completed

    def cycle_once(self) -> int:
        processed = self.process_once()
        self.reconcile_once()
        return processed

    def reconcile_once(self, workspace_key: str = "default") -> bool:
        publication = self.repository.try_publish_index_generation(workspace_key)
        if publication is not None:
            return publication
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
                        f"deskseed:kb-build:{run.run_id}:{run.target_artifact_generation}:"
                        f"{item.articleId}:{item.revisionId}:{item.publicRevision}:{INDEX_CONTRACT_VERSION}",
                    ),
                    workspaceKey=workspace_key,
                    articleId=item.articleId,
                    revisionId=item.revisionId,
                    action="UPSERT",
                    sourceVersion=item.sourceVersion,
                    publicRevision=item.publicRevision,
                    createdAt=item.publishedAt,
                )
                self.repository.accept_reconciliation_index_event(run, event)
            return self.repository.record_reconciliation_page(
                run,
                [
                    (item.articleId, item.revisionId, item.sourceVersion, item.publicRevision)
                    for item in page.items
                ],
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
            getattr(page, "canonicalPublicCorpusRevision", None),
            INDEX_CONTRACT_VERSION,
            CHUNKER_VERSION,
            NORMALIZATION_VERSION,
            self.settings.embedding_model,
            EMBEDDING_DIMENSION,
        ):
            return None
        return self.repository.current_reconciliation(workspace_key)

    def process(self, event: IndexWorkItem) -> int:
        if event.action == "DELETE":
            return 0
        article = self.backend.read_public_article(event.article_id, event.revision_id, event.event_id)
        if article.dataClass != "PUBLIC_KB_ONLY":
            raise ConflictError("knowledge source data class is not PUBLIC_KB_ONLY")
        if article.articleId != event.article_id or article.revisionId != event.revision_id:
            raise ConflictError("knowledge source binding mismatch")
        if article.sourceVersion != event.source_version:
            raise ConflictError("knowledge source version mismatch")
        if article.publicRevision != event.public_revision:
            raise ConflictError("knowledge source revision mismatch")
        chunks = build_public_article_chunks(
            article.title,
            article.categoryTitle,
            article.sectionTitle,
            article.body,
            lambda value: self.pricing.count_text_tokens(self.settings.embedding_model, value),
        )
        if not chunks:
            raise ConflictError("published knowledge article is empty")
        embedded = []
        for ordinal, chunk in enumerate(chunks):
            call_id = uuid4()
            reservation = self.repository.reserve_system_budget(
                event.workspace_key,
                f"index:{event.artifact_generation}:{event.event_id}:{ordinal}",
                self.settings.embedding_model,
                self.pricing.version,
                self.pricing.upper_bound_microusd(
                    self.settings.embedding_model,
                    self.pricing.count_text_tokens(self.settings.embedding_model, chunk.embedding_input),
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
                    chunk.embedding_input,
                    call_id,
                    self._receipt_recorder(call_id, reservation),
                )
                embedded.append((chunk, result.vector, result.receipt))
            except Exception:
                self.repository.mark_provider_call_unknown(call_id)
                raise
        tokens, _ = self.knowledge.replace_public_revision_with_vectors(
            event.workspace_key,
            article.articleId,
            article.revisionId,
            event.source_version,
            event.event_id,
            article.slug,
            article.title,
            article.publicRevision,
            embedded,
            artifact_generation=event.artifact_generation,
            category_title=article.categoryTitle,
            section_title=article.sectionTitle,
            reconciliation_run_id=event.reconciliation_run_id,
            lease_owner=self.owner,
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
