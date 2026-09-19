from __future__ import annotations

import hashlib
import math
import re
import unicodedata
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from uuid import UUID, uuid4

from psycopg import Connection

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
INDEX_CONTRACT_VERSION = "public-kb-artifact-v2"
CHUNKER_VERSION = "section-block-v2"
NORMALIZATION_VERSION = "public-text-nfkc-v2"
EMBEDDING_DIMENSION = 1536
EMBEDDING_INPUT_TOKEN_LIMIT = 512
EMBEDDING_ARTIFACT_KEY_VERSION = "embedding-artifact-v1"

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
class PreparedKnowledgeChunk:
    body: str
    embedding_input: str
    search_text: str


@dataclass(frozen=True)
class EmbeddingResult:
    vector: list[float]
    receipt: ProviderCallReceipt


@dataclass(frozen=True)
class EmbeddingBatchResult:
    vectors: list[list[float]]
    receipt: ProviderCallReceipt


@dataclass(frozen=True)
class EmbeddingArtifactSpec:
    artifact_key: str
    model_snapshot: str
    dimension: int
    normalization_version: str
    input_sha256: str


class EmbeddingProvider:
    def embed(self, text: str, call_id: UUID, record_receipt: ReceiptRecorder) -> EmbeddingResult:
        result = self.embed_many([text], call_id, record_receipt)
        return EmbeddingResult(result.vectors[0], result.receipt)

    def embed_many(
        self, texts: list[str], call_id: UUID, record_receipt: ReceiptRecorder
    ) -> EmbeddingBatchResult:
        raise NotImplementedError


class FakeEmbeddingProvider(EmbeddingProvider):
    def __init__(self, model: str = "openai/text-embedding-3-small"):
        self.model = model

    def embed_many(
        self, texts: list[str], call_id: UUID, record_receipt: ReceiptRecorder
    ) -> EmbeddingBatchResult:
        if not texts:
            raise ValueError("embedding input array must not be empty")
        vectors = [_fake_embedding(text) for text in texts]
        receipt = ProviderCallReceipt(
            call_id=call_id,
            provider_request_id=f"fake-{call_id}",
            requested_alias=self.model,
            actual_model=self.model,
            usage_schema_version="synthetic-v1",
            usage_status=UsageStatus.KNOWN,
            usage=Usage(sum(max(1, len(text) // 4) for text in texts), 0, 0, 0),
            usage_issue_code=None,
            service_tier="standard",
            context_price_band="short",
        )
        record_receipt(receipt)
        return EmbeddingBatchResult(vectors, receipt)


class LiteLlmEmbeddingProvider(EmbeddingProvider):
    def __init__(self, model: str, api_key: str, timeout_seconds: int):
        self.model = model
        self.api_key = api_key
        self.timeout_seconds = timeout_seconds

    def embed_many(
        self, texts: list[str], call_id: UUID, record_receipt: ReceiptRecorder
    ) -> EmbeddingBatchResult:
        from litellm import embedding

        if not texts:
            raise ValueError("embedding input array must not be empty")
        response = embedding(
            model=self.model,
            input=texts,
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
        return EmbeddingBatchResult(
            _ordered_embedding_vectors(value(response, "data"), len(texts)), receipt
        )


class KnowledgeRepository:
    def __init__(self, database: Database, embeddings: EmbeddingProvider):
        self.database = database
        self.embeddings = embeddings

    def embed_text(
        self, text: str, call_id: UUID, record_receipt: ReceiptRecorder
    ) -> EmbeddingResult:
        return self.embeddings.embed(text, call_id, record_receipt)

    def embed_texts(
        self, texts: list[str], call_id: UUID, record_receipt: ReceiptRecorder
    ) -> EmbeddingBatchResult:
        return self.embeddings.embed_many(texts, call_id, record_receipt)

    def find_embedding_artifacts(
        self, specs: list[EmbeddingArtifactSpec]
    ) -> dict[str, list[float]]:
        if not specs:
            return {}
        expected = {spec.artifact_key: spec for spec in specs}
        with self.database.connection() as connection:
            rows = connection.execute(
                """
                select artifact_key, key_version, model_snapshot, embedding_dimension,
                       normalization_version, embedding_input_sha256, embedding::text as vector
                from ai_embedding_artifacts where artifact_key = any(%s)
                """,
                (list(expected),),
            ).fetchall()
        artifacts: dict[str, list[float]] = {}
        for row in rows:
            key = str(row["artifact_key"])
            spec = expected.get(key)
            if spec is None or not _artifact_row_matches(row, spec):
                raise ValueError("embedding artifact contract mismatch")
            artifacts[key] = _parse_vector_literal(str(row["vector"]), spec.dimension)
        return artifacts

    def store_embedding_artifacts_for_index_event(
        self,
        workspace_key: str,
        article_id: UUID,
        source_version: int,
        event_id: UUID,
        artifact_generation: int,
        reconciliation_run_id: UUID | None,
        lease_owner: str | None,
        artifacts: list[
            tuple[EmbeddingArtifactSpec, str, list[float], ProviderCallReceipt]
        ],
    ) -> bool:
        now = datetime.now(UTC)
        for spec, final_input, vector, receipt in artifacts:
            _validate_new_embedding_artifact(spec, final_input, vector, receipt)
        with self.database.transaction() as connection:
            if not _index_event_is_current(
                connection,
                workspace_key,
                article_id,
                source_version,
                event_id,
                artifact_generation,
                reconciliation_run_id,
                lease_owner,
            ):
                return False
            for spec, _, vector, receipt in artifacts:
                assert receipt.actual_model is not None
                connection.execute(
                    """
                    insert into ai_embedding_artifacts (
                        artifact_key, key_version, model_snapshot, embedding_dimension,
                        normalization_version, embedding_input_sha256, actual_model,
                        embedding, created_at, last_used_at
                    ) values (%s, %s, %s, %s, %s, %s, %s, %s::vector, %s, %s)
                    on conflict do nothing
                    """,
                    (
                        spec.artifact_key,
                        EMBEDDING_ARTIFACT_KEY_VERSION,
                        spec.model_snapshot,
                        spec.dimension,
                        spec.normalization_version,
                        spec.input_sha256,
                        receipt.actual_model,
                        _vector_literal(vector),
                        now,
                        now,
                    ),
                )
                row = connection.execute(
                    """
                    select artifact_key, key_version, model_snapshot, embedding_dimension,
                           normalization_version, embedding_input_sha256, embedding::text as vector
                    from ai_embedding_artifacts where artifact_key = %s
                    """,
                    (spec.artifact_key,),
                ).fetchone()
                if row is None or not _artifact_row_matches(row, spec):
                    raise ValueError("embedding artifact conflict")
                _parse_vector_literal(str(row["vector"]), spec.dimension)
        return True

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
        *,
        artifact_generation: int = 1,
        category_title: str = "PUBLIC",
        section_title: str = "PUBLIC",
        reconciliation_run_id: UUID | None = None,
        lease_owner: str | None = None,
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
            artifact_generation=artifact_generation,
            category_title=category_title,
            section_title=section_title,
            reconciliation_run_id=reconciliation_run_id,
            lease_owner=lease_owner,
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
        embedded: list[
            tuple[str | PreparedKnowledgeChunk, list[float], ProviderCallReceipt | None]
        ],
        *,
        artifact_generation: int = 1,
        category_title: str = "PUBLIC",
        section_title: str = "PUBLIC",
        reconciliation_run_id: UUID | None = None,
        lease_owner: str | None = None,
        embedding_artifacts: list[EmbeddingArtifactSpec] | None = None,
    ) -> tuple[int, bool]:
        if embedding_artifacts is not None and len(embedding_artifacts) != len(embedded):
            raise ValueError("embedding artifact bindings do not match chunks")
        now = datetime.now(UTC)
        with self.database.transaction() as connection:
            if not _index_event_is_current(
                connection,
                workspace_key,
                article_id,
                source_version,
                event_id,
                artifact_generation,
                reconciliation_run_id,
                lease_owner,
            ):
                return _known_input_tokens(embedded), False
            connection.execute(
                "delete from ai_kb_revisions where workspace_key = %s and artifact_generation = %s and article_id = %s",
                (workspace_key, artifact_generation, article_id),
            )
            connection.execute(
                """
                insert into ai_kb_revisions (
                    article_id, revision_id, workspace_key, artifact_generation, slug, title,
                    category_title, section_title, public_revision, status, indexed_at
                ) values (%s, %s, %s, %s, %s, %s, %s, %s, %s, 'PUBLIC', %s)
                on conflict (workspace_key, artifact_generation, article_id, revision_id) do update set
                    slug = excluded.slug, title = excluded.title,
                    category_title = excluded.category_title, section_title = excluded.section_title,
                    public_revision = excluded.public_revision,
                    status = 'PUBLIC', indexed_at = excluded.indexed_at, deleted_at = null
                """,
                (
                    article_id,
                    revision_id,
                    workspace_key,
                    artifact_generation,
                    slug,
                    title,
                    category_title,
                    section_title,
                    public_revision,
                    now,
                ),
            )
            for ordinal, (prepared_or_content, vector, _) in enumerate(embedded):
                if isinstance(prepared_or_content, PreparedKnowledgeChunk):
                    content = prepared_or_content.body
                    search_text = prepared_or_content.search_text
                    embedding_input = prepared_or_content.embedding_input
                else:
                    content = prepared_or_content
                    search_text = "\n".join((title, category_title, section_title, content))
                    embedding_input = content
                artifact_key = None
                if embedding_artifacts is not None:
                    spec = embedding_artifacts[ordinal]
                    if hashlib.sha256(embedding_input.encode()).hexdigest() != spec.input_sha256:
                        raise ValueError("embedding artifact input digest mismatch")
                    row = connection.execute(
                        """
                        select artifact_key, key_version, model_snapshot, embedding_dimension,
                               normalization_version, embedding_input_sha256,
                               embedding::text as vector
                        from ai_embedding_artifacts where artifact_key = %s for share
                        """,
                        (spec.artifact_key,),
                    ).fetchone()
                    if row is None or not _artifact_row_matches(row, spec):
                        raise ValueError("embedding artifact is unavailable")
                    vector = _parse_vector_literal(str(row["vector"]), spec.dimension)
                    artifact_key = spec.artifact_key
                    connection.execute(
                        """
                        update ai_embedding_artifacts set last_used_at = %s
                        where artifact_key = %s
                        """,
                        (now, artifact_key),
                    )
                connection.execute(
                    """
                    insert into ai_kb_chunks (
                        chunk_id, article_id, revision_id, workspace_key, artifact_generation,
                        ordinal, content, search_text, content_sha256, embedding_input_sha256,
                        embedding_artifact_key, embedding, indexed_at
                    ) values (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s::vector, %s)
                    """,
                    (
                        uuid4(),
                        article_id,
                        revision_id,
                        workspace_key,
                        artifact_generation,
                        ordinal,
                        content,
                        search_text,
                        hashlib.sha256(content.encode()).hexdigest(),
                        hashlib.sha256(embedding_input.encode()).hexdigest(),
                        artifact_key,
                        _vector_literal(vector),
                        now,
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
            published = connection.execute(
                "select artifact_generation from ai_kb_published_generations where workspace_key = %s",
                (workspace_key,),
            ).fetchone()
            if published is None:
                return []
            artifact_generation = published["artifact_generation"]
            vector_rows = connection.execute(
                """
                select chunk.chunk_id, chunk.article_id, chunk.revision_id, revision.title, revision.slug,
                       chunk.content, chunk.ordinal,
                       (chunk.embedding <=> %s::vector)::double precision as distance
                from ai_kb_chunks chunk
                join ai_kb_revisions revision
                  on revision.workspace_key = chunk.workspace_key
                 and revision.artifact_generation = chunk.artifact_generation
                 and revision.article_id = chunk.article_id and revision.revision_id = chunk.revision_id
                where chunk.workspace_key = %s and chunk.artifact_generation = %s
                  and revision.status = 'PUBLIC'
                order by chunk.embedding <=> %s::vector, chunk.chunk_id
                limit %s
                """,
                (
                    vector_literal,
                    workspace_key,
                    artifact_generation,
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
                               to_tsvector('simple', chunk.search_text),
                               websearch_to_tsquery('simple', %s)
                           )::double precision + (
                               select count(*)::double precision
                               from unnest(%s::text[]) as error_code
                               where exists (
                                   select 1
                                   from regexp_split_to_table(
                                       chunk.search_text,
                                       '[^[:alnum:]_./-]+'
                                   ) as content_token
                                   where upper(content_token) = upper(error_code)
                               )
                           ) as keyword_score
                    from ai_kb_chunks chunk
                    join ai_kb_revisions revision
                      on revision.workspace_key = chunk.workspace_key
                     and revision.artifact_generation = chunk.artifact_generation
                     and revision.article_id = chunk.article_id
                     and revision.revision_id = chunk.revision_id
                    where chunk.workspace_key = %s
                      and chunk.artifact_generation = %s
                      and revision.status = 'PUBLIC'
                      and to_tsvector('simple', chunk.search_text)
                          @@ websearch_to_tsquery('simple', %s)
                    order by keyword_score desc, chunk.chunk_id
                    limit %s
                    """,
                    (
                        websearch_query,
                        list(query.error_codes),
                        workspace_key,
                        artifact_generation,
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


def build_public_article_chunks(
    title: str,
    category_title: str,
    section_title: str,
    body: str,
    count_tokens: Callable[[str], int],
    max_tokens: int = EMBEDDING_INPUT_TOKEN_LIMIT,
) -> list[PreparedKnowledgeChunk]:
    normalized_title = _normalize_public_text(title, preserve_blank_lines=False)
    normalized_category = _normalize_public_text(category_title, preserve_blank_lines=False)
    normalized_section = _normalize_public_text(section_title, preserve_blank_lines=False)
    normalized_body = _normalize_public_text(body, preserve_blank_lines=True)
    if not all((normalized_title, normalized_category, normalized_section, normalized_body)):
        return []
    prefix = (
        f"Document title: {normalized_title}\n"
        f"Category: {normalized_category}\n"
        f"Section: {normalized_section}\n"
        "Body:\n"
    )
    if count_tokens(prefix) >= max_tokens:
        raise ValueError("knowledge title metadata exceeds embedding token limit")
    blocks = [block.strip() for block in re.split(r"\n{2,}", normalized_body) if block.strip()]
    packed: list[str] = []
    current = ""
    for block in blocks:
        for unit in _split_oversized_block(block, prefix, count_tokens, max_tokens):
            candidate = unit if not current else f"{current}\n\n{unit}"
            if count_tokens(prefix + candidate) <= max_tokens:
                current = candidate
                continue
            if current:
                packed.append(current)
            current = unit
    if current:
        packed.append(current)
    return [
        PreparedKnowledgeChunk(
            body=chunk,
            embedding_input=prefix + chunk,
            search_text="\n".join(
                (normalized_title, normalized_category, normalized_section, chunk)
            ),
        )
        for chunk in packed
    ]


def chunk_public_article(body: str, max_chars: int = 1200, overlap: int = 120) -> list[str]:
    """Legacy fixed-character helper retained only for migration/regression fixtures."""
    normalized = _normalize_public_text(body, preserve_blank_lines=False)
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


def _normalize_public_text(value: str, preserve_blank_lines: bool) -> str:
    normalized = unicodedata.normalize("NFKC", value.replace("\r\n", "\n").replace("\r", "\n"))
    normalized = "".join(
        character
        for character in normalized
        if character in {"\n", "\t"} or not unicodedata.category(character).startswith("C")
    )
    lines = [re.sub(r"[ \t]+", " ", line).strip() for line in normalized.split("\n")]
    if not preserve_blank_lines:
        return "\n".join(line for line in lines if line)
    result: list[str] = []
    blank = False
    for line in lines:
        if line:
            result.append(line)
            blank = False
        elif result and not blank:
            result.append("")
            blank = True
    return "\n".join(result).strip()


def _split_oversized_block(
    block: str,
    prefix: str,
    count_tokens: Callable[[str], int],
    max_tokens: int,
) -> list[str]:
    if count_tokens(prefix + block) <= max_tokens:
        return [block]
    lines = block.splitlines()
    if len(lines) > 1:
        units: list[str] = []
        current = ""
        for line in lines:
            candidate = line if not current else f"{current}\n{line}"
            if count_tokens(prefix + candidate) <= max_tokens:
                current = candidate
                continue
            if current:
                units.extend(_split_oversized_block(current, prefix, count_tokens, max_tokens))
            current = line
        if current:
            units.extend(_split_oversized_block(current, prefix, count_tokens, max_tokens))
        return units
    sentences = [item.strip() for item in re.split(r"(?<=[.!?。！？])\s+", block) if item.strip()]
    if len(sentences) > 1:
        units = []
        current = ""
        for sentence in sentences:
            candidate = sentence if not current else f"{current} {sentence}"
            if count_tokens(prefix + candidate) <= max_tokens:
                current = candidate
                continue
            if current:
                units.extend(_split_oversized_block(current, prefix, count_tokens, max_tokens))
            current = sentence
        if current:
            units.extend(_split_oversized_block(current, prefix, count_tokens, max_tokens))
        return units
    return _split_text_to_token_bound(block, prefix, count_tokens, max_tokens)


def _split_text_to_token_bound(
    text: str,
    prefix: str,
    count_tokens: Callable[[str], int],
    max_tokens: int,
) -> list[str]:
    remaining = text.strip()
    result: list[str] = []
    while remaining:
        low, high = 1, len(remaining)
        best = 0
        while low <= high:
            middle = (low + high) // 2
            if count_tokens(prefix + remaining[:middle]) <= max_tokens:
                best = middle
                low = middle + 1
            else:
                high = middle - 1
        if best == 0:
            raise ValueError("knowledge content cannot fit embedding token limit")
        if best < len(remaining):
            whitespace = remaining.rfind(" ", 0, best + 1)
            if whitespace >= max(1, best // 2):
                best = whitespace
        part = remaining[:best].strip()
        if not part:
            raise ValueError("knowledge content split made no progress")
        result.append(part)
        remaining = remaining[best:].strip()
    return result


def _vector_literal(vector: list[float]) -> str:
    return "[" + ",".join(f"{value:.8f}" for value in vector) + "]"


def _known_input_tokens(
    embedded: list[
        tuple[str | PreparedKnowledgeChunk, list[float], ProviderCallReceipt | None]
    ],
) -> int:
    receipts = {
        receipt.call_id: receipt
        for _, _, receipt in embedded
        if receipt is not None
        and receipt.usage_status == UsageStatus.KNOWN
        and receipt.usage is not None
    }
    return sum(
        receipt.usage.input_total_tokens
        for receipt in receipts.values()
        if receipt.usage is not None
    )


def embedding_artifact_spec(
    model_snapshot: str,
    dimension: int,
    normalization_version: str,
    final_embedding_input: str,
) -> EmbeddingArtifactSpec:
    fields = (
        model_snapshot,
        str(dimension),
        normalization_version,
        final_embedding_input,
    )
    digest = hashlib.sha256()
    digest.update(EMBEDDING_ARTIFACT_KEY_VERSION.encode())
    for field in fields:
        encoded = field.encode()
        digest.update(len(encoded).to_bytes(8, "big"))
        digest.update(encoded)
    return EmbeddingArtifactSpec(
        artifact_key=digest.hexdigest(),
        model_snapshot=model_snapshot,
        dimension=dimension,
        normalization_version=normalization_version,
        input_sha256=hashlib.sha256(final_embedding_input.encode()).hexdigest(),
    )


def _fake_embedding(text: str) -> list[float]:
    digest = hashlib.sha256(text.encode()).digest()
    values = [((digest[index % len(digest)] / 255.0) - 0.5) for index in range(1536)]
    magnitude = math.sqrt(sum(value * value for value in values)) or 1.0
    return [value / magnitude for value in values]


def _ordered_embedding_vectors(data: object | None, expected_count: int) -> list[list[float]]:
    if not isinstance(data, (list, tuple)) or len(data) != expected_count:
        raise ValueError("embedding response count mismatch")
    ordered: list[list[float] | None] = [None] * expected_count
    for item in data:
        index = value(item, "index")
        vector = value(item, "embedding")
        if (
            not isinstance(index, int)
            or isinstance(index, bool)
            or index < 0
            or index >= expected_count
            or ordered[index] is not None
        ):
            raise ValueError("embedding response index mapping is invalid")
        if not isinstance(vector, (list, tuple)) or len(vector) != EMBEDDING_DIMENSION:
            raise ValueError("embedding response vector dimension is invalid")
        normalized: list[float] = []
        for component in vector:
            if (
                not isinstance(component, (int, float))
                or isinstance(component, bool)
                or not math.isfinite(float(component))
            ):
                raise ValueError("embedding response vector value is invalid")
            normalized.append(float(component))
        ordered[index] = normalized
    if any(vector is None for vector in ordered):
        raise ValueError("embedding response mapping is incomplete")
    return [vector for vector in ordered if vector is not None]


def _parse_vector_literal(value_text: str, expected_dimension: int) -> list[float]:
    if not value_text.startswith("[") or not value_text.endswith("]"):
        raise ValueError("stored embedding vector is malformed")
    values = [float(value) for value in value_text[1:-1].split(",")]
    if len(values) != expected_dimension or any(not math.isfinite(value) for value in values):
        raise ValueError("stored embedding vector dimension is invalid")
    return values


def _artifact_row_matches(row: dict[str, object], spec: EmbeddingArtifactSpec) -> bool:
    return (
        str(row["artifact_key"]) == spec.artifact_key
        and row["key_version"] == EMBEDDING_ARTIFACT_KEY_VERSION
        and row["model_snapshot"] == spec.model_snapshot
        and row["embedding_dimension"] == spec.dimension
        and row["normalization_version"] == spec.normalization_version
        and str(row["embedding_input_sha256"]) == spec.input_sha256
    )


def _validate_new_embedding_artifact(
    spec: EmbeddingArtifactSpec,
    final_input: str,
    vector: list[float],
    receipt: ProviderCallReceipt,
) -> None:
    if spec != embedding_artifact_spec(
        spec.model_snapshot,
        spec.dimension,
        spec.normalization_version,
        final_input,
    ):
        raise ValueError("embedding artifact key does not match the final input")
    if (
        len(spec.artifact_key) != 64
        or len(spec.input_sha256) != 64
        or any(character not in "0123456789abcdef" for character in spec.artifact_key)
        or any(character not in "0123456789abcdef" for character in spec.input_sha256)
    ):
        raise ValueError("embedding artifact digest is invalid")
    if (
        not spec.model_snapshot
        or len(spec.model_snapshot) > 160
        or not spec.normalization_version
        or len(spec.normalization_version) > 80
    ):
        raise ValueError("embedding artifact contract identity is invalid")
    if spec.dimension != EMBEDDING_DIMENSION or len(vector) != spec.dimension:
        raise ValueError("embedding artifact vector dimension is invalid")
    if any(not math.isfinite(value) for value in vector):
        raise ValueError("embedding artifact vector value is invalid")
    if receipt.actual_model is None:
        raise ValueError("embedding artifact requires provider model identity")


def _index_event_is_current(
    connection: Connection,
    workspace_key: str,
    article_id: UUID,
    source_version: int,
    event_id: UUID,
    artifact_generation: int,
    reconciliation_run_id: UUID | None,
    lease_owner: str | None,
) -> bool:
    state = connection.execute(
        """
        select source_version, action, event_id from ai_kb_article_state
        where workspace_key = %s and article_id = %s for update
        """,
        (workspace_key, article_id),
    ).fetchone()
    if reconciliation_run_id is None:
        return bool(
            state
            and state["source_version"] == source_version
            and state["action"] == "UPSERT"
            and state["event_id"] == event_id
        )
    job = connection.execute(
        """
        select source_version, action, artifact_generation, reconciliation_run_id,
               status, lease_owner
        from ai_kb_index_jobs where event_id = %s for update
        """,
        (event_id,),
    ).fetchone()
    state_is_newer_or_withdrawn = bool(
        state
        and (
            state["source_version"] > source_version
            or (state["source_version"] == source_version and state["action"] != "UPSERT")
        )
    )
    return bool(
        job
        and job["source_version"] == source_version
        and job["action"] == "UPSERT"
        and job["artifact_generation"] == artifact_generation
        and job["reconciliation_run_id"] == reconciliation_run_id
        and job["status"] == "LEASED"
        and job["lease_owner"] == lease_owner
        and not state_is_newer_or_withdrawn
    )
