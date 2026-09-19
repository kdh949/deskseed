from __future__ import annotations

import hashlib
import math
import re
import unicodedata
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from uuid import UUID, uuid4

from .call_receipts import ProviderCallReceipt, ReceiptRecorder, UsageStatus
from .db import Database
from .pricing import Usage
from .usage_normalization import bounded_text, normalize_litellm_usage, value

VECTOR_CANDIDATE_LIMIT = 20
KEYWORD_CANDIDATE_LIMIT = 20
RRF_CONSTANT = 60
FUSED_EVALUATION_LIMIT = 10
EVIDENCE_LIMIT = 5
EMBEDDING_QUERY_TOKEN_LIMIT = 2_048
KEYWORD_TOKEN_LIMIT = 16
ERROR_CODE_LIMIT = 8

_KEYWORD_TOKEN = re.compile(r"[^\W_][\w./-]{1,63}", re.UNICODE)


class MissingCurrentProblemError(ValueError):
    pass


class RetrievalQueryTooLongError(ValueError):
    pass


@dataclass(frozen=True)
class RetrievalQuery:
    embedding_text: str
    keyword_tokens: tuple[str, ...]
    error_codes: tuple[str, ...]


@dataclass(frozen=True)
class KnowledgeChunk:
    chunk_id: UUID
    article_id: UUID
    revision_id: UUID
    title: str
    slug: str
    content: str
    score: float
    ordinal: int = 0


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
                "delete from ai_kb_chunks where workspace_key = %s and article_id = %s",
                (workspace_key, article_id),
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

    def retrieve(
        self, workspace_key: str, query: str | RetrievalQuery, limit: int = 5
    ) -> list[KnowledgeChunk]:
        chunks, _ = self.retrieve_with_usage(workspace_key, query, limit)
        return chunks

    def retrieve_with_usage(
        self, workspace_key: str, query: str | RetrievalQuery, limit: int = 5
    ) -> tuple[list[KnowledgeChunk], int]:
        retrieval_query = _coerce_query(query)
        result = self.embeddings.embed(retrieval_query.embedding_text, uuid4(), lambda _receipt: None)
        chunks = self._retrieve_with_vector(workspace_key, retrieval_query, result.vector, limit)
        tokens = result.receipt.usage.input_total_tokens if result.receipt.usage is not None else 0
        return chunks, tokens

    def retrieve_with_receipt(
        self,
        workspace_key: str,
        query: str | RetrievalQuery,
        call_id: UUID,
        record_receipt: ReceiptRecorder,
        limit: int = 5,
    ) -> tuple[list[KnowledgeChunk], ProviderCallReceipt]:
        retrieval_query = _coerce_query(query)
        result = self.embeddings.embed(retrieval_query.embedding_text, call_id, record_receipt)
        return (
            self._retrieve_with_vector(workspace_key, retrieval_query, result.vector, limit),
            result.receipt,
        )

    def _retrieve_with_vector(
        self,
        workspace_key: str,
        query: RetrievalQuery,
        vector: list[float],
        limit: int,
    ) -> list[KnowledgeChunk]:
        evidence_limit = min(max(limit, 0), EVIDENCE_LIMIT)
        if evidence_limit == 0:
            return []
        vector_literal = _vector_literal(vector)
        with self.database.connection() as connection, connection.transaction():
            connection.execute("set transaction isolation level repeatable read, read only")
            connection.execute("set local hnsw.iterative_scan = 'strict_order'")
            vector_rows = connection.execute(
                """
                select chunk.chunk_id, chunk.article_id, chunk.revision_id, revision.title, revision.slug,
                       chunk.content, chunk.ordinal,
                       (chunk.embedding <=> %s::vector)::double precision as distance
                from ai_kb_chunks chunk
                join ai_kb_revisions revision
                  on revision.article_id = chunk.article_id and revision.revision_id = chunk.revision_id
                where chunk.workspace_key = %s and revision.status = 'PUBLIC'
                order by chunk.embedding <=> %s::vector, chunk.chunk_id
                limit %s
                """,
                (
                    vector_literal,
                    workspace_key,
                    vector_literal,
                    VECTOR_CANDIDATE_LIMIT,
                ),
            ).fetchall()
            fts_rows = []
            if query.keyword_tokens:
                websearch_query = " OR ".join(f'"{token}"' for token in query.keyword_tokens)
                fts_rows = connection.execute(
                    """
                    select chunk.chunk_id, chunk.article_id, chunk.revision_id,
                           revision.title, revision.slug, chunk.content, chunk.ordinal,
                           ts_rank_cd(
                               to_tsvector('simple', chunk.content),
                               websearch_to_tsquery('simple', %s)
                           )::double precision + (
                               select count(*)::double precision
                               from unnest(%s::text[]) as error_code
                               where exists (
                                   select 1
                                   from regexp_split_to_table(
                                       chunk.content,
                                       '[^[:alnum:]_./-]+'
                                   ) as content_token
                                   where upper(content_token) = upper(error_code)
                               )
                           ) as keyword_score
                    from ai_kb_chunks chunk
                    join ai_kb_revisions revision
                      on revision.article_id = chunk.article_id
                     and revision.revision_id = chunk.revision_id
                    where chunk.workspace_key = %s
                      and revision.status = 'PUBLIC'
                      and to_tsvector('simple', chunk.content)
                          @@ websearch_to_tsquery('simple', %s)
                    order by keyword_score desc, chunk.chunk_id
                    limit %s
                    """,
                    (
                        websearch_query,
                        list(query.error_codes),
                        workspace_key,
                        websearch_query,
                        KEYWORD_CANDIDATE_LIMIT,
                    ),
                ).fetchall()
        return _fuse_candidates(vector_rows, fts_rows, evidence_limit)


def build_retrieval_query(
    current_problem: str,
    count_tokens: Callable[[str], int] | None = None,
) -> RetrievalQuery:
    normalized = _normalize_current_problem(current_problem)
    if not normalized:
        raise MissingCurrentProblemError("current PUBLIC problem is empty")
    if count_tokens is not None and count_tokens(normalized) > EMBEDDING_QUERY_TOKEN_LIMIT:
        raise RetrievalQueryTooLongError("current PUBLIC problem exceeds the retrieval token bound")
    literal_tokens: list[str] = []
    seen: set[str] = set()
    for match in _KEYWORD_TOKEN.finditer(normalized):
        token = match.group(0).strip("._/-")
        folded = token.casefold()
        if len(token) < 2 or folded in seen:
            continue
        seen.add(folded)
        literal_tokens.append(token)
    error_codes = tuple(
        token for token in literal_tokens if _is_explicit_error_code(token)
    )[:ERROR_CODE_LIMIT]
    error_keys = {token.casefold() for token in error_codes}
    keyword_tokens = error_codes + tuple(
        token for token in literal_tokens if token.casefold() not in error_keys
    )[: KEYWORD_TOKEN_LIMIT - len(error_codes)]
    return RetrievalQuery(normalized, keyword_tokens, error_codes)


def _normalize_current_problem(value: str) -> str:
    compatible = unicodedata.normalize("NFKC", value)
    without_controls = "".join(
        " " if unicodedata.category(character).startswith("C") else character for character in compatible
    )
    return " ".join(without_controls.split())


def _is_explicit_error_code(token: str) -> bool:
    if not any(character.isalpha() for character in token) or not any(
        character.isdigit() for character in token
    ):
        return False
    if any(separator in token for separator in ("-", "_", ".")):
        return True
    letters = "".join(character for character in token if character.isalpha())
    return bool(letters) and letters == letters.upper()


def _coerce_query(query: str | RetrievalQuery) -> RetrievalQuery:
    return query if isinstance(query, RetrievalQuery) else build_retrieval_query(query)


def _fuse_candidates(
    vector_rows: list[dict[str, object]],
    fts_rows: list[dict[str, object]],
    evidence_limit: int,
) -> list[KnowledgeChunk]:
    rows_by_chunk: dict[UUID, dict[str, object]] = {}
    vector_rank: dict[UUID, int] = {}
    keyword_scores: dict[UUID, float] = {}
    for rank, row in enumerate(vector_rows, start=1):
        chunk_id = _chunk_id(row)
        rows_by_chunk[chunk_id] = row
        vector_rank[chunk_id] = rank
    for row in fts_rows:
        chunk_id = _chunk_id(row)
        rows_by_chunk[chunk_id] = row
        keyword_scores[chunk_id] = float(row["keyword_score"])
    keyword_order = sorted(
        keyword_scores,
        key=lambda chunk_id: (-keyword_scores[chunk_id], str(chunk_id)),
    )[:KEYWORD_CANDIDATE_LIMIT]
    keyword_rank = {chunk_id: rank for rank, chunk_id in enumerate(keyword_order, start=1)}
    fused: list[KnowledgeChunk] = []
    for chunk_id in set(vector_rank) | set(keyword_rank):
        score = 0.0
        if chunk_id in vector_rank:
            rank = vector_rank[chunk_id]
            score += 1 / (RRF_CONSTANT + rank)
        if chunk_id in keyword_rank:
            rank = keyword_rank[chunk_id]
            score += 1 / (RRF_CONSTANT + rank)
        row = rows_by_chunk[chunk_id]
        fused.append(
            KnowledgeChunk(
                chunk_id=chunk_id,
                article_id=_uuid_value(row, "article_id"),
                revision_id=_uuid_value(row, "revision_id"),
                title=str(row["title"]),
                slug=str(row["slug"]),
                content=str(row["content"]),
                score=score,
                ordinal=int(row["ordinal"]),
            )
        )
    fused.sort(
        key=lambda chunk: (
            -chunk.score,
            min(
                vector_rank.get(chunk.chunk_id, VECTOR_CANDIDATE_LIMIT + 1),
                keyword_rank.get(chunk.chunk_id, KEYWORD_CANDIDATE_LIMIT + 1),
            ),
            str(chunk.chunk_id),
        )
    )
    selected: list[KnowledgeChunk] = []
    for candidate in fused[:FUSED_EVALUATION_LIMIT]:
        if any(
            chosen.article_id == candidate.article_id and abs(chosen.ordinal - candidate.ordinal) <= 1
            for chosen in selected
        ):
            continue
        selected.append(candidate)
        if len(selected) == evidence_limit:
            break
    return selected


def _chunk_id(row: dict[str, object]) -> UUID:
    return _uuid_value(row, "chunk_id")


def _uuid_value(row: dict[str, object], key: str) -> UUID:
    candidate = row[key]
    if not isinstance(candidate, UUID):
        raise TypeError(f"retrieval row {key} is not a UUID")
    return candidate


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
