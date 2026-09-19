from __future__ import annotations

import hashlib
import json
from collections.abc import Callable
from dataclasses import dataclass, replace
from typing import Any, Literal, TypedDict
from uuid import UUID

from langgraph.graph import END, START, StateGraph

from .call_receipts import ReceiptRecorder
from .providers import GenerationProvider, ProviderResult
from .retrieval import (
    KnowledgeChunk,
    KnowledgeRepository,
    RetrievalQuery,
    build_retrieval_query,
)
from .schemas import AuthorRole, Citation, Feature, ReplyDraftResult, ReplyProviderOutput, SourceContext


class InvalidReplyOutputError(RuntimeError):
    pass


class InvalidSourceAuthorizationError(RuntimeError):
    pass


class ReplyState(TypedDict, total=False):
    context: SourceContext
    workspace_key: str
    authorized: bool
    knowledge: list[KnowledgeChunk]
    approved_knowledge: list[KnowledgeChunk]
    authorize_candidates: Callable[[list[Citation]], list[Citation]]
    options: dict[str, str]
    source_map: dict[str, Citation]
    source_map_digest: str
    source_chunk_ids: tuple[UUID, ...]
    generation: ProviderResult
    query_call_id: UUID
    query_receipt_recorder: ReceiptRecorder
    retrieval_query: RetrievalQuery
    prepare_generation: Callable[[SourceContext, list[KnowledgeChunk]], tuple[UUID, ReceiptRecorder]]
    no_evidence: bool
    validated: bool


@dataclass(frozen=True)
class ReplyExecution:
    generation: ProviderResult | None
    no_evidence: bool
    source_map_digest: str | None
    source_chunk_ids: tuple[UUID, ...]


class ReplyWorkflow:
    """Acyclic and bounded: authorize -> retrieve -> validate -> generate|no-evidence -> validate."""

    def __init__(self, provider: GenerationProvider, knowledge: KnowledgeRepository):
        self.provider = provider
        self.knowledge = knowledge
        graph = StateGraph(ReplyState)
        graph.add_node("authorize", self._authorize)
        graph.add_node("retrieve", self._retrieve)
        graph.add_node("validate_sources", self._validate_sources)
        graph.add_node("no_evidence", self._no_evidence)
        graph.add_node("generate", self._generate)
        graph.add_node("validate", self._validate)
        graph.add_edge(START, "authorize")
        graph.add_edge("authorize", "retrieve")
        graph.add_edge("retrieve", "validate_sources")
        graph.add_conditional_edges(
            "validate_sources",
            self._route_after_source_validation,
            {"generate": "generate", "no_evidence": "no_evidence"},
        )
        graph.add_edge("no_evidence", END)
        graph.add_edge("generate", "validate")
        graph.add_edge("validate", END)
        self._graph = graph.compile()

    def invoke(
        self,
        context: SourceContext,
        workspace_key: str,
        authorize_candidates: Callable[[list[Citation]], list[Citation]],
        options: dict[str, str],
        retrieval_query: RetrievalQuery,
        query_call_id: UUID,
        query_receipt_recorder: ReceiptRecorder,
        prepare_generation: Callable[
            [SourceContext, list[KnowledgeChunk]], tuple[UUID, ReceiptRecorder]
        ],
    ) -> ReplyExecution:
        state = self._graph.invoke(
            {
                "context": context,
                "workspace_key": workspace_key,
                "authorize_candidates": authorize_candidates,
                "options": options,
                "retrieval_query": retrieval_query,
                "query_call_id": query_call_id,
                "query_receipt_recorder": query_receipt_recorder,
                "prepare_generation": prepare_generation,
            },
            config={"recursion_limit": 8},
        )
        if state.get("no_evidence"):
            return ReplyExecution(None, True, None, ())
        if not state.get("validated"):
            raise ValueError("reply validation did not complete")
        return ReplyExecution(
            state["generation"],
            False,
            state["source_map_digest"],
            state["source_chunk_ids"],
        )

    def _authorize(self, state: ReplyState) -> dict[str, Any]:
        context = state["context"]
        if context.inputScope != "PUBLIC_ONLY" or context.feature != Feature.REPLY_DRAFT:
            raise ValueError("reply context authorization failed")
        return {"authorized": True}

    def _retrieve(self, state: ReplyState) -> dict[str, Any]:
        knowledge, _receipt = self.knowledge.retrieve_with_receipt(
            state["workspace_key"],
            state["retrieval_query"],
            state["query_call_id"],
            state["query_receipt_recorder"],
            limit=5,
        )
        return {"knowledge": knowledge}

    def _validate_sources(self, state: ReplyState) -> dict[str, Any]:
        candidates = [_citation_for(chunk) for chunk in state["knowledge"]]
        candidates_by_chunk = {item.chunkId: item for item in candidates}
        knowledge_by_chunk = {item.chunk_id: item for item in state["knowledge"]}
        if len(candidates_by_chunk) != len(candidates) or len(knowledge_by_chunk) != len(state["knowledge"]):
            raise InvalidSourceAuthorizationError("knowledge candidates contain duplicate chunks")
        authorized = state["authorize_candidates"](candidates)
        authorized_by_chunk: dict[UUID, Citation] = {}
        positions: list[int] = []
        candidate_positions = {item.chunkId: index for index, item in enumerate(candidates)}
        for item in authorized:
            expected = candidates_by_chunk.get(item.chunkId)
            if (
                expected is None
                or item.chunkId in authorized_by_chunk
                or item.articleId != expected.articleId
                or item.revisionId != expected.revisionId
            ):
                raise InvalidSourceAuthorizationError("knowledge authorization contains an invalid candidate")
            authorized_by_chunk[item.chunkId] = item
            positions.append(candidate_positions[item.chunkId])
        if positions != sorted(positions):
            raise InvalidSourceAuthorizationError("knowledge authorization changed candidate order")

        approved_knowledge = [
            replace(
                knowledge_by_chunk[item.chunkId],
                title=authorized_by_chunk[item.chunkId].title,
                slug=authorized_by_chunk[item.chunkId].url.removeprefix("/help/articles/"),
            )
            for item in candidates
            if item.chunkId in authorized_by_chunk
        ]
        source_map = {
            f"S{index}": authorized_by_chunk[chunk.chunk_id]
            for index, chunk in enumerate(approved_knowledge, start=1)
        }
        if not approved_knowledge:
            return {
                "approved_knowledge": [],
                "source_map": {},
                "no_evidence": True,
            }
        return {
            "approved_knowledge": approved_knowledge,
            "source_map": source_map,
            "source_map_digest": source_map_digest(source_map),
            "source_chunk_ids": tuple(chunk.chunk_id for chunk in approved_knowledge),
            "no_evidence": False,
        }

    def _route_after_source_validation(self, state: ReplyState) -> Literal["generate", "no_evidence"]:
        return "no_evidence" if state.get("no_evidence") else "generate"

    def _no_evidence(self, _state: ReplyState) -> dict[str, Any]:
        return {"no_evidence": True}

    def _generate(self, state: ReplyState) -> dict[str, Any]:
        knowledge = state["approved_knowledge"]
        call_id, recorder = state["prepare_generation"](state["context"], knowledge)
        return {
            "generation": self.provider.reply(
                state["context"], knowledge, state["options"], call_id, recorder
            ),
        }

    def _validate(self, state: ReplyState) -> dict[str, Any]:
        generated = state["generation"]
        result = generated.result
        if not isinstance(result, ReplyProviderOutput):
            raise InvalidReplyOutputError("reply workflow received a non-reply provider output")
        if not result.sourceRefs or len(result.sourceRefs) > 8 or len(set(result.sourceRefs)) != len(result.sourceRefs):
            raise InvalidReplyOutputError("reply source references are empty, duplicated, or over limit")
        source_map = state["source_map"]
        try:
            citations = [source_map[source_ref] for source_ref in result.sourceRefs]
        except KeyError as exception:
            raise InvalidReplyOutputError("reply contains an unknown source reference") from exception
        return {
            "validated": True,
            "generation": ProviderResult(
                result=ReplyDraftResult(answer=result.answer, citations=citations),
                receipt=generated.receipt,
                prompt_version=generated.prompt_version,
            ),
        }


def source_map_digest(source_map: dict[str, Citation]) -> str:
    canonical = [
        {
            "sourceRef": source_ref,
            "articleId": str(citation.articleId),
            "revisionId": str(citation.revisionId),
            "chunkId": str(citation.chunkId),
            "title": citation.title,
            "url": citation.url,
        }
        for source_ref, citation in source_map.items()
    ]
    payload = json.dumps(canonical, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _citation_for(chunk: KnowledgeChunk) -> Citation:
    return Citation(
        articleId=chunk.article_id,
        revisionId=chunk.revision_id,
        chunkId=chunk.chunk_id,
        title=chunk.title,
        url=f"/help/articles/{chunk.slug}",
    )


def reply_query(
    context: SourceContext,
    count_tokens: Callable[[str], int] | None = None,
) -> RetrievalQuery:
    current_problem = next(
        (comment.body for comment in reversed(context.comments) if comment.authorRole == AuthorRole.CUSTOMER),
        context.comments[-1].body,
    )
    return build_retrieval_query(current_problem, count_tokens)
