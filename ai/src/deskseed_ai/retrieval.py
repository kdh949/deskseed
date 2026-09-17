from __future__ import annotations

import hashlib
import math
from dataclasses import dataclass
from datetime import UTC, datetime
from uuid import UUID, uuid4

from .db import Database


@dataclass(frozen=True)
class KnowledgeChunk:
    chunk_id: UUID
    article_id: UUID
    revision_id: UUID
    title: str
    slug: str
    content: str
    score: float


class EmbeddingProvider:
    def embed(self, text: str) -> tuple[list[float], int]:
        raise NotImplementedError


class FakeEmbeddingProvider(EmbeddingProvider):
    def embed(self, text: str) -> tuple[list[float], int]:
        digest = hashlib.sha256(text.encode()).digest()
        values = [((digest[index % len(digest)] / 255.0) - 0.5) for index in range(1536)]
        magnitude = math.sqrt(sum(value * value for value in values)) or 1.0
        return [value / magnitude for value in values], max(1, len(text) // 4)


class LiteLlmEmbeddingProvider(EmbeddingProvider):
    def __init__(self, model: str, api_key: str, timeout_seconds: int):
        self.model = model
        self.api_key = api_key
        self.timeout_seconds = timeout_seconds

    def embed(self, text: str) -> tuple[list[float], int]:
        from litellm import embedding

        response = embedding(
            model=self.model,
            input=[text],
            api_key=self.api_key,
            timeout=min(45, self.timeout_seconds),
            num_retries=0,
        )
        vector = list(response.data[0]["embedding"])
        return vector, int(response.usage.total_tokens)


class KnowledgeRepository:
    def __init__(self, database: Database, embeddings: EmbeddingProvider):
        self.database = database
        self.embeddings = embeddings

    def replace_public_revision(
        self,
        workspace_key: str,
        article_id: UUID,
        revision_id: UUID,
        slug: str,
        title: str,
        public_revision: str,
        chunks: list[str],
    ) -> int:
        embedded = [(text, *self.embeddings.embed(text)) for text in chunks]
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
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
        return sum(tokens for _, _, tokens in embedded)

    def retrieve(self, workspace_key: str, query: str, limit: int = 5) -> list[KnowledgeChunk]:
        chunks, _ = self.retrieve_with_usage(workspace_key, query, limit)
        return chunks

    def retrieve_with_usage(
        self, workspace_key: str, query: str, limit: int = 5
    ) -> tuple[list[KnowledgeChunk], int]:
        vector, tokens = self.embeddings.embed(query)
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
        return [KnowledgeChunk(**row) for row in rows], tokens


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
