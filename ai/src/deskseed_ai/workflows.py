from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, TypedDict
from uuid import UUID

from langgraph.graph import END, START, StateGraph

from .call_receipts import ReceiptRecorder
from .providers import GenerationProvider, ProviderResult
from .retrieval import KnowledgeChunk, KnowledgeRepository
from .schemas import Citation, Feature, ReplyDraftResult, SourceContext


class ReplyState(TypedDict, total=False):
    context: SourceContext
    workspace_key: str
    authorized: bool
    knowledge: list[KnowledgeChunk]
    authorize_candidates: Callable[[list[Citation]], list[Citation]]
    options: dict[str, str]
    authorized_citations: dict[UUID, Citation]
    generation: ProviderResult
    query_call_id: UUID
    query_receipt_recorder: ReceiptRecorder
    prepare_generation: Callable[[SourceContext, list[KnowledgeChunk]], tuple[UUID, ReceiptRecorder]]
    validated: bool


@dataclass(frozen=True)
class ReplyExecution:
    generation: ProviderResult


class ReplyWorkflow:
    """Acyclic and statically bounded: AUTHORIZE -> RETRIEVE -> GENERATE -> VALIDATE."""

    def __init__(self, provider: GenerationProvider, knowledge: KnowledgeRepository):
        self.provider = provider
        self.knowledge = knowledge
        graph = StateGraph(ReplyState)
        graph.add_node("authorize", self._authorize)
        graph.add_node("retrieve", self._retrieve)
        graph.add_node("validate_sources", self._validate_sources)
        graph.add_node("generate", self._generate)
        graph.add_node("validate", self._validate)
        graph.add_edge(START, "authorize")
        graph.add_edge("authorize", "retrieve")
        graph.add_edge("retrieve", "validate_sources")
        graph.add_edge("validate_sources", "generate")
        graph.add_edge("generate", "validate")
        graph.add_edge("validate", END)
        self._graph = graph.compile()

    def invoke(
        self,
        context: SourceContext,
        workspace_key: str,
        authorize_candidates: Callable[[list[Citation]], list[Citation]],
        options: dict[str, str],
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
                "query_call_id": query_call_id,
                "query_receipt_recorder": query_receipt_recorder,
                "prepare_generation": prepare_generation,
            },
            config={"recursion_limit": 8},
        )
        if not state.get("validated"):
            raise ValueError("reply validation did not complete")
        return ReplyExecution(state["generation"])

    def _authorize(self, state: ReplyState) -> dict[str, Any]:
        context = state["context"]
        if context.inputScope != "PUBLIC_ONLY" or context.feature != Feature.REPLY_DRAFT:
            raise ValueError("reply context authorization failed")
        return {"authorized": True}

    def _retrieve(self, state: ReplyState) -> dict[str, Any]:
        context = state["context"]
        query = reply_query(context)
        knowledge, _receipt = self.knowledge.retrieve_with_receipt(
            state["workspace_key"],
            query,
            state["query_call_id"],
            state["query_receipt_recorder"],
            limit=5,
        )
        return {"knowledge": knowledge}

    def _validate_sources(self, state: ReplyState) -> dict[str, Any]:
        candidates = [
            Citation(
                articleId=chunk.article_id,
                revisionId=chunk.revision_id,
                chunkId=chunk.chunk_id,
                title=chunk.title,
                url=f"/help/articles/{chunk.slug}",
            )
            for chunk in state["knowledge"]
        ]
        authorize = state["authorize_candidates"]
        authorized = authorize(candidates)
        authorized_by_chunk = {item.chunkId: item for item in authorized}
        if len(authorized_by_chunk) != len(candidates) or any(
            item.chunkId not in authorized_by_chunk for item in candidates
        ):
            raise ValueError("knowledge candidate authorization is incomplete")
        return {"authorized_citations": authorized_by_chunk}

    def _generate(self, state: ReplyState) -> dict[str, Any]:
        call_id, recorder = state["prepare_generation"](state["context"], state["knowledge"])
        return {
            "generation": self.provider.reply(
                state["context"], state["knowledge"], state["options"], call_id, recorder
            ),
        }
    def _validate(self, state: ReplyState) -> dict[str, Any]:
        generated = state["generation"]
        result = generated.result
        if not isinstance(result, ReplyDraftResult):
            raise ValueError("reply workflow received a non-reply result")
        approved = {chunk.chunk_id: chunk for chunk in state["knowledge"]}
        authorized = state["authorized_citations"]
        canonical: list[Citation] = []
        for citation in result.citations:
            chunk = approved.get(citation.chunkId)
            if (
                chunk is None
                or citation.articleId != chunk.article_id
                or citation.revisionId != chunk.revision_id
            ):
                raise ValueError("reply contains an unapproved citation")
            canonical.append(authorized[chunk.chunk_id])
        return {
            "validated": True,
            "generation": ProviderResult(
                result=result.model_copy(update={"citations": canonical}),
                receipt=generated.receipt,
                prompt_version=generated.prompt_version,
            ),
        }


def reply_query(context: SourceContext) -> str:
    return "\n".join(comment.body for comment in context.comments)[-4000:]
