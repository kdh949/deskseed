from __future__ import annotations

import logging
from pathlib import Path
from uuid import NAMESPACE_URL, uuid4, uuid5

from .backend_client import BackendClient
from .config import Settings
from .pricing import PricingCatalog, Usage
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
    ):
        self.backend = backend
        self.knowledge = knowledge
        self.repository = repository
        self.settings = settings
        self.pricing = PricingCatalog(pricing_path)
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
        if article.publicRevision != event.publicRevision:
            raise ConflictError("knowledge source revision mismatch")
        chunks = chunk_public_article(article.body)
        if not chunks:
            raise ConflictError("published knowledge article is empty")
        token_upper_bound = sum(len(chunk.encode("utf-8")) for chunk in chunks)
        reservation = self.repository.reserve_system_budget(
            event.workspaceKey,
            f"index:{event.eventId}",
            self.settings.embedding_model,
            self.pricing.version,
            self.pricing.upper_bound_microusd(self.settings.embedding_model, token_upper_bound),
        )
        try:
            tokens = self.knowledge.replace_public_revision(
                event.workspaceKey,
                article.articleId,
                article.revisionId,
                article.slug,
                article.title,
                article.publicRevision,
                chunks,
            )
            self.repository.settle_budget(
                reservation,
                self.pricing.cost_microusd(self.settings.embedding_model, Usage(tokens, 0, 0)),
            )
            return tokens
        except Exception:
            self.repository.mark_budget_unknown(reservation)
            raise
