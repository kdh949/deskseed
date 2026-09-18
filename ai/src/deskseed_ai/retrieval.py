from __future__ import annotations

import hashlib
import math
from dataclasses import dataclass
from datetime import UTC, datetime
from uuid import UUID, uuid4

from .call_receipts import ProviderCallReceipt, ReceiptRecorder, UsageStatus
from .db import Database
from .pricing import Usage
from .usage_normalization import bounded_text, normalize_litellm_usage, value


@dataclass(frozen=True)
class KnowledgeChunk:
    chunk_id: UUID
    article_id: UUID
    revision_id: UUID
    title: str
    slug: str
    content: str
    score: float


@dataclass(frozen=True)
class EmbeddingResult:
    vector: list[float]
    receipt: ProviderCallReceipt


class EmbeddingProvider:
    def embed(self, text: str, call_id: UUID, record_receipt: ReceiptRecorder) -> EmbeddingResult:
        raise NotImplementedError


class FakeEmbeddingProvider(EmbeddingProvider):
    def __init__(self, model: str = "openai/text-embedding-3-small"):
        self.model = model

    def embed(self, text: str, call_id: UUID, record_receipt: ReceiptRecorder) -> EmbeddingResult:
        digest = hashlib.sha256(text.encode()).digest()
        values = [((digest[index % len(digest)] / 255.0) - 0.5) for index in range(1536)]
        magnitude = math.sqrt(sum(value * value for value in values)) or 1.0
        receipt = ProviderCallReceipt(
            call_id=call_id,
            provider_request_id=f"fake-{call_id}",
            requested_alias=self.model,
            actual_model=self.model,
            usage_schema_version="synthetic-v1",
            usage_status=UsageStatus.KNOWN,
            usage=Usage(max(1, len(text) // 4), 0, 0, 0),
            usage_issue_code=None,
            service_tier="standard",
            context_price_band="short",
        )
        record_receipt(receipt)
        return EmbeddingResult([value / magnitude for value in values], receipt)


class LiteLlmEmbeddingProvider(EmbeddingProvider):
    def __init__(self, model: str, api_key: str, timeout_seconds: int):
        self.model = model
        self.api_key = api_key
        self.timeout_seconds = timeout_seconds

    def embed(self, text: str, call_id: UUID, record_receipt: ReceiptRecorder) -> EmbeddingResult:
        from litellm import embedding

        response = embedding(
            model=self.model,
            input=[text],
            api_key=self.api_key,
            timeout=min(45, self.timeout_seconds),
            num_retries=0,
        )
        status, usage, issue = normalize_litellm_usage(value(response, "usage"), output_optional=True)
        service_tier = bounded_text(value(response, "service_tier"), 24)
        if service_tier in {None, "default"}:
            service_tier = "standard"
        receipt = ProviderCallReceipt(
            call_id=call_id,
            provider_request_id=bounded_text(value(response, "id"), 200),
            requested_alias=self.model,
            actual_model=bounded_text(value(response, "model"), 160),
            usage_schema_version="litellm-1.101-v1",
            usage_status=status,
            usage=usage,
            usage_issue_code=issue,
            service_tier=service_tier,
            context_price_band="short",
        )
        record_receipt(receipt)
        vector = list(response.data[0]["embedding"])
        return EmbeddingResult(vector, receipt)


class KnowledgeRepository:
    def __init__(self, database: Database, embeddings: EmbeddingProvider):
        self.database = database
        self.embeddings = embeddings

    def embed_text(
        self, text: str, call_id: UUID, record_receipt: ReceiptRecorder
    ) -> EmbeddingResult:
        return self.embeddings.embed(text, call_id, record_receipt)

    def replace_public_revision(
        self,
        workspace_key: str,
        article_id: UUID,
        revision_id: UUID,
        source_version: int,
        event_id: UUID,
        slug: str,
        title: str,
        public_revision: str,
        chunks: list[str],
    ) -> tuple[int, bool]:
        embedded_results = [self.embeddings.embed(text, uuid4(), lambda _receipt: None) for text in chunks]
        embedded = [(text, result.vector, result.receipt) for text, result in zip(chunks, embedded_results)]
        return self.replace_public_revision_with_vectors(
            workspace_key,
            article_id,
            revision_id,
            source_version,
            event_id,
            slug,
            title,
            public_revision,
            embedded,
        )

    def replace_public_revision_with_vectors(
        self,
        workspace_key: str,
        article_id: UUID,
        revision_id: UUID,
        source_version: int,
        event_id: UUID,
        slug: str,
        title: str,
        public_revision: str,
        embedded: list[tuple[str, list[float], ProviderCallReceipt]],
    ) -> tuple[int, bool]:
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            state = connection.execute(
                """
                select source_version, action, event_id from ai_kb_article_state
                where workspace_key = %s and article_id = %s for update
                """,
                (workspace_key, article_id),
            ).fetchone()
            if (
                not state
                or state["source_version"] != source_version
                or state["action"] != "UPSERT"
                or state["event_id"] != event_id
            ):
                return _known_input_tokens(embedded), False
            connection.execute(
                "update ai_kb_revisions set status = 'DELETED', deleted_at = %s where workspace_key = %s and article_id = %s",
                (now, workspace_key, article_id),
            )
            connection.execute(
                """
                insert into ai_kb_revisions (
                    article_id, revision_id, workspace_key, slug, title, public_revision, status, indexed_at
                ) values (%s, %s, %s, %s, %s, %s, 'PUBLIC', %s)
                on conflict (article_id, revision_id) do update set
                    slug = excluded.slug, title = excluded.title, public_revision = excluded.public_revision,
                    status = 'PUBLIC', indexed_at = excluded.indexed_at, deleted_at = null
                """,
                (article_id, revision_id, workspace_key, slug, title, public_revision, now),
            )
            connection.execute(
                "delete from ai_kb_chunks where article_id = %s and revision_id = %s", (article_id, revision_id)
            )
            for ordinal, (content, vector, _) in enumerate(embedded):
                connection.execute(
                    """
                    insert into ai_kb_chunks (
                        chunk_id, article_id, revision_id, workspace_key, ordinal, content,
                        content_sha256, embedding, indexed_at
                    ) values (%s, %s, %s, %s, %s, %s, %s, %s::vector, %s)
                    """,
                    (
                        uuid4(), article_id, revision_id, workspace_key, ordinal, content,
                        hashlib.sha256(content.encode()).hexdigest(), _vector_literal(vector), now,
                    ),
                )
        return _known_input_tokens(embedded), True

    def retrieve(self, workspace_key: str, query: str, limit: int = 5) -> list[KnowledgeChunk]:
        chunks, _ = self.retrieve_with_usage(workspace_key, query, limit)
        return chunks

    def retrieve_with_usage(
        self, workspace_key: str, query: str, limit: int = 5
    ) -> tuple[list[KnowledgeChunk], int]:
        result = self.embeddings.embed(query, uuid4(), lambda _receipt: None)
        chunks = self._retrieve_with_vector(workspace_key, query, result.vector, limit)
        tokens = result.receipt.usage.input_total_tokens if result.receipt.usage is not None else 0
        return chunks, tokens

    def retrieve_with_receipt(
        self,
        workspace_key: str,
        query: str,
        call_id: UUID,
        record_receipt: ReceiptRecorder,
        limit: int = 5,
    ) -> tuple[list[KnowledgeChunk], ProviderCallReceipt]:
        result = self.embeddings.embed(query, call_id, record_receipt)
        return self._retrieve_with_vector(workspace_key, query, result.vector, limit), result.receipt

    def _retrieve_with_vector(
        self, workspace_key: str, query: str, vector: list[float], limit: int
    ) -> list[KnowledgeChunk]:
        with self.database.connection() as connection:
            rows = connection.execute(
                """
                select chunk.chunk_id, chunk.article_id, chunk.revision_id, revision.title, revision.slug,
                       chunk.content,
                       (
                           0.80 * (1 - (chunk.embedding <=> %s::vector)) +
                           0.20 * ts_rank_cd(
                               to_tsvector('simple', chunk.content),
                               plainto_tsquery('simple', %s)
                           )
                       )::double precision as score
                from ai_kb_chunks chunk
                join ai_kb_revisions revision
                  on revision.article_id = chunk.article_id and revision.revision_id = chunk.revision_id
                where chunk.workspace_key = %s and revision.status = 'PUBLIC'
                order by score desc, chunk.chunk_id
                limit %s
                """,
                (_vector_literal(vector), query, workspace_key, limit),
            ).fetchall()
        return [KnowledgeChunk(**row) for row in rows]


def chunk_public_article(body: str, max_chars: int = 1200, overlap: int = 120) -> list[str]:
    normalized = "\n".join(line.strip() for line in body.splitlines() if line.strip())
    if not normalized:
        return []
    chunks: list[str] = []
    cursor = 0
    while cursor < len(normalized):
        end = min(len(normalized), cursor + max_chars)
        if end < len(normalized):
            boundary = normalized.rfind("\n", cursor, end)
            if boundary > cursor + max_chars // 2:
                end = boundary
        chunks.append(normalized[cursor:end])
        if end == len(normalized):
            break
        cursor = max(cursor + 1, end - overlap)
    return chunks


def _vector_literal(vector: list[float]) -> str:
    return "[" + ",".join(f"{value:.8f}" for value in vector) + "]"


def _known_input_tokens(embedded: list[tuple[str, list[float], ProviderCallReceipt]]) -> int:
    return sum(
        receipt.usage.input_total_tokens
        for _, _, receipt in embedded
        if receipt.usage_status == UsageStatus.KNOWN and receipt.usage is not None
    )
