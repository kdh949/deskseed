from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any
from uuid import UUID

from pydantic import ValidationError

from .call_receipts import ProviderCallReceipt, ReceiptRecorder, UsageStatus
from .config import Settings
from .pricing import PricingCatalog, Usage
from .prompting import context_memory_prompt, prompt_for
from .retrieval import KnowledgeChunk
from .schemas import (
    ContextMemoryPayload,
    ContextMemoryProviderOutput,
    Feature,
    ProviderOutput,
    ReplyDraftResult,
    ReplyProviderOutput,
    SourceContext,
    SummaryResult,
    TriageResult,
)
from .usage_normalization import bounded_text, normalize_litellm_usage, value


@dataclass(frozen=True)
class ProviderResult:
    result: ProviderOutput | ReplyDraftResult
    receipt: ProviderCallReceipt
    prompt_version: str


@dataclass(frozen=True)
class ProviderResponseEnvelope:
    raw_structured_output: str | None
    receipt: ProviderCallReceipt


class InvalidProviderOutputError(RuntimeError):
    pass


class GenerationProvider:
    settings: Settings
    def context_memory(
        self,
        context: SourceContext,
        previous: ContextMemoryPayload | None,
        call_id: UUID,
        record_receipt: ReceiptRecorder,
    ) -> ProviderResult:
        raise NotImplementedError

    def summary(
        self,
        context: SourceContext,
        options: Mapping[str, str],
        call_id: UUID,
        record_receipt: ReceiptRecorder,
    ) -> ProviderResult:
        raise NotImplementedError

    def triage(
        self,
        context: SourceContext,
        options: Mapping[str, str],
        call_id: UUID,
        record_receipt: ReceiptRecorder,
    ) -> ProviderResult:
        raise NotImplementedError

    def reply(
        self,
        context: SourceContext,
        knowledge: list[KnowledgeChunk],
        options: Mapping[str, str],
        call_id: UUID,
        record_receipt: ReceiptRecorder,
        memory: ContextMemoryPayload | None = None,
    ) -> ProviderResult:
        raise NotImplementedError

    def estimate_input_tokens(
        self,
        pricing: PricingCatalog,
        feature: Feature,
        context: SourceContext,
        knowledge: list[KnowledgeChunk],
        options: Mapping[str, str],
        memory: ContextMemoryPayload | None = None,
    ) -> int:
        model, schema, output_limit = _feature_config(self.settings, feature)
        request = _request_contract(
            model, context, schema, knowledge, options, feature, output_limit, memory
        )
        return pricing.count_json_tokens(model, request)

    def estimate_context_memory_input_tokens(
        self,
        pricing: PricingCatalog,
        context: SourceContext,
        previous: ContextMemoryPayload | None,
    ) -> int:
        request = _context_memory_request(self.settings.model_fast, context, previous)
        return pricing.count_json_tokens(self.settings.model_fast, request)


class FakeGenerationProvider(GenerationProvider):
    def __init__(self, settings: Settings):
        self.settings = settings

    def context_memory(
        self,
        context: SourceContext,
        previous: ContextMemoryPayload | None,
        call_id: UUID,
        record_receipt: ReceiptRecorder,
    ) -> ProviderResult:
        prior = list(previous.confirmedFacts) if previous is not None else []
        items = prior + [
            {
                "text": comment.body[:1000],
                "sourceRefs": [f"C{comment.sequence or index}"],
            }
            for index, comment in enumerate(context.comments, start=1)
        ]
        conflict_refs = [
            f"C{comment.sequence or index}"
            for index, comment in enumerate(context.comments, start=1)
            if "[CONFLICT]" in comment.body
        ]
        result = ContextMemoryProviderOutput(
            confirmedFacts=items[-30:],
            attemptsAndOutcomes=(
                list(previous.attemptsAndOutcomes) if previous is not None else []
            ),
            openQuestions=list(previous.openQuestions) if previous is not None else [],
            conflictSourceRefs=conflict_refs,
        )
        request = _context_memory_request(self.settings.model_fast, context, previous)
        receipt = _fake_receipt(
            call_id,
            self.settings.model_fast,
            _fake_usage(json.dumps(request, ensure_ascii=False), result.model_dump_json()),
        )
        record_receipt(receipt)
        return ProviderResult(result, receipt, context_memory_prompt().version)

    def summary(
        self,
        context: SourceContext,
        options: Mapping[str, str],
        call_id: UUID,
        record_receipt: ReceiptRecorder,
    ) -> ProviderResult:
        text = _conversation(context)
        result = SummaryResult(
            problem=text[:500],
            attemptedActions=[],
            unresolvedItems=["상담사 확인이 필요한 공개 문의"],
            nextChecks=["공개 대화의 최신 상태 확인"],
        )
        prompt = prompt_for(Feature.SUMMARY)
        usage = _fake_usage(text + json.dumps(dict(options), sort_keys=True), result.model_dump_json())
        receipt = _fake_receipt(call_id, self.settings.model_fast, usage)
        record_receipt(receipt)
        return ProviderResult(
            result,
            receipt,
            prompt.version,
        )

    def triage(
        self,
        context: SourceContext,
        options: Mapping[str, str],
        call_id: UUID,
        record_receipt: ReceiptRecorder,
    ) -> ProviderResult:
        text = _conversation(context).lower()
        urgent = any(word in text for word in ("긴급", "즉시", "장애", "결제"))
        if "결제" in text or "환불" in text:
            topic = "BILLING"
        elif "로그인" in text or "계정" in text:
            topic = "ACCOUNT_ACCESS"
        elif "오류" in text or "장애" in text:
            topic = "TECHNICAL_ISSUE"
        elif "정책" in text:
            topic = "POLICY"
        else:
            topic = "OTHER"
        result = TriageResult(
            topicCode=topic,
            suggestedTagIds=[],
            suggestedPriority="HIGH" if urgent else None,
            reasons=["PUBLIC 대화의 합성 규칙 기반 분류"],
        )
        prompt = prompt_for(Feature.TRIAGE)
        usage = _fake_usage(text + json.dumps(dict(options), sort_keys=True), result.model_dump_json())
        receipt = _fake_receipt(call_id, self.settings.model_fast, usage)
        record_receipt(receipt)
        return ProviderResult(
            result,
            receipt,
            prompt.version,
        )

    def reply(
        self,
        context: SourceContext,
        knowledge: list[KnowledgeChunk],
        options: Mapping[str, str],
        call_id: UUID,
        record_receipt: ReceiptRecorder,
        memory: ContextMemoryPayload | None = None,
    ) -> ProviderResult:
        if not knowledge:
            raise ValueError("reply provider requires approved knowledge")
        first = knowledge[0]
        answer = f"문의해 주셔서 감사합니다. 공개 도움말 기준으로 안내드립니다: {first.content[:500]}"
        result = ReplyProviderOutput(answer=answer, sourceRefs=["S1"])
        prompt = prompt_for(Feature.REPLY_DRAFT)
        usage = _fake_usage(
            _conversation(context)
            + (memory.model_dump_json() if memory is not None else "")
            + json.dumps(dict(options), sort_keys=True),
            result.model_dump_json(),
        )
        receipt = _fake_receipt(call_id, self.settings.model_standard, usage)
        record_receipt(receipt)
        return ProviderResult(
            result,
            receipt,
            prompt.version,
        )


class LiteLlmGenerationProvider(GenerationProvider):
    def __init__(self, settings: Settings):
        self.settings = settings

    def context_memory(
        self,
        context: SourceContext,
        previous: ContextMemoryPayload | None,
        call_id: UUID,
        record_receipt: ReceiptRecorder,
    ) -> ProviderResult:
        from litellm import completion

        request = _context_memory_request(self.settings.model_fast, context, previous)
        response = completion(
            model=self.settings.model_fast,
            api_key=self.settings.openai_api_key.get_secret_value(),
            messages=request["messages"],
            response_format=request["response_format"],
            reasoning_effort="none",
            store=False,
            num_retries=0,
            timeout=min(45, self.settings.job_timeout_seconds),
            max_completion_tokens=1024,
            service_tier="default",
        )
        receipt = _litellm_receipt(call_id, self.settings.model_fast, response)
        record_receipt(receipt)
        raw = getattr(response.choices[0].message, "content", None)
        if raw is None:
            raise InvalidProviderOutputError("provider response has no context memory output")
        try:
            result = ContextMemoryProviderOutput.model_validate_json(raw)
        except (ValidationError, ValueError, TypeError) as exception:
            raise InvalidProviderOutputError("provider context memory output is invalid") from exception
        return ProviderResult(result, receipt, context_memory_prompt().version)

    def summary(
        self,
        context: SourceContext,
        options: Mapping[str, str],
        call_id: UUID,
        record_receipt: ReceiptRecorder,
    ) -> ProviderResult:
        return self._complete(
            self.settings.model_fast,
            context,
            SummaryResult,
            [],
            options,
            Feature.SUMMARY,
            call_id,
            record_receipt,
        )

    def triage(
        self,
        context: SourceContext,
        options: Mapping[str, str],
        call_id: UUID,
        record_receipt: ReceiptRecorder,
    ) -> ProviderResult:
        return self._complete(
            self.settings.model_fast,
            context,
            TriageResult,
            [],
            options,
            Feature.TRIAGE,
            call_id,
            record_receipt,
        )

    def reply(
        self,
        context: SourceContext,
        knowledge: list[KnowledgeChunk],
        options: Mapping[str, str],
        call_id: UUID,
        record_receipt: ReceiptRecorder,
        memory: ContextMemoryPayload | None = None,
    ) -> ProviderResult:
        return self._complete(
            self.settings.model_standard,
            context,
            ReplyProviderOutput,
            knowledge,
            options,
            Feature.REPLY_DRAFT,
            call_id,
            record_receipt,
            memory,
        )

    def _complete(
        self,
        model: str,
        context: SourceContext,
        schema: type[ProviderOutput],
        knowledge: list[KnowledgeChunk],
        options: Mapping[str, str],
        feature: Feature,
        call_id: UUID,
        record_receipt: ReceiptRecorder,
        memory: ContextMemoryPayload | None = None,
    ) -> ProviderResult:
        from litellm import completion

        output_limit = _output_limit(schema)
        request = _request_contract(
            model, context, schema, knowledge, options, feature, output_limit, memory
        )
        response = completion(
            model=model,
            api_key=self.settings.openai_api_key.get_secret_value(),
            messages=request["messages"],
            response_format=request["response_format"],
            reasoning_effort="none" if model == self.settings.model_fast else "low",
            store=False,
            num_retries=0,
            timeout=min(45, self.settings.job_timeout_seconds),
            max_completion_tokens=output_limit,
            service_tier="default",
        )
        receipt = _litellm_receipt(call_id, model, response)
        envelope = ProviderResponseEnvelope(
            raw_structured_output=getattr(response.choices[0].message, "content", None),
            receipt=receipt,
        )
        record_receipt(envelope.receipt)
        if envelope.raw_structured_output is None:
            raise InvalidProviderOutputError("provider response has no structured output")
        try:
            result = schema.model_validate_json(envelope.raw_structured_output)
        except (ValidationError, ValueError, TypeError) as exception:
            raise InvalidProviderOutputError("provider structured output is invalid") from exception
        return ProviderResult(result, receipt, prompt_for(feature).version)


def provider_for(settings: Settings) -> GenerationProvider:
    if settings.provider_mode == "litellm":
        return LiteLlmGenerationProvider(settings)
    return FakeGenerationProvider(settings)


def _conversation(context: SourceContext) -> str:
    return "\n".join(comment.body for comment in context.comments)


def _fake_usage(input_text: str, output_text: str) -> Usage:
    return Usage(max(1, len(input_text) // 4), 0, 0, max(1, len(output_text) // 4))


def _fake_receipt(call_id: UUID, model: str, usage: Usage) -> ProviderCallReceipt:
    return ProviderCallReceipt(
        call_id=call_id,
        provider_request_id=f"fake-{call_id}",
        requested_alias=model,
        actual_model=model,
        usage_schema_version="synthetic-v1",
        usage_status=UsageStatus.KNOWN,
        usage=usage,
        usage_issue_code=None,
        service_tier="standard",
        context_price_band="short",
    )


def _feature_config(
    settings: Settings,
    feature: Feature,
) -> tuple[str, type[ProviderOutput], int]:
    if feature == Feature.REPLY_DRAFT:
        return settings.model_standard, ReplyProviderOutput, 2048
    if feature == Feature.SUMMARY:
        return settings.model_fast, SummaryResult, 1024
    return settings.model_fast, TriageResult, 768


def _output_limit(schema: type[ProviderOutput]) -> int:
    if schema is ReplyProviderOutput:
        return 2048
    if schema is SummaryResult:
        return 1024
    return 768


def _request_contract(
    model: str,
    context: SourceContext,
    schema: type[ProviderOutput],
    knowledge: list[KnowledgeChunk],
    options: Mapping[str, str],
    feature: Feature,
    output_limit: int,
    memory: ContextMemoryPayload | None = None,
) -> dict[str, Any]:
    prompt = prompt_for(feature)
    public_messages = [
        {
            "commentRef": f"C{comment.sequence if memory is not None and comment.sequence is not None else index}",
            "authorRole": comment.authorRole.value if comment.authorRole is not None else "UNKNOWN",
            "sequence": comment.sequence if comment.sequence is not None else index,
            "createdAt": comment.createdAt.isoformat(),
            "body": comment.body,
        }
        for index, comment in enumerate(context.comments, start=1)
    ]
    public_knowledge = [
        {
            "sourceRef": f"S{index}",
            "title": item.title,
            "content": item.content,
        }
        for index, item in enumerate(knowledge, start=1)
    ]
    user_payload: dict[str, Any] = {
        "options": dict(options),
        "publicConversation": public_messages,
        "approvedPublicKnowledge": public_knowledge,
    }
    if memory is not None:
        user_payload["publicConversationMemory"] = memory.model_dump(mode="json")
    return {
        "model": model,
        "messages": [
            {"role": "system", "content": prompt.content},
            {
                "role": "user",
                "content": json.dumps(user_payload, ensure_ascii=False),
            },
        ],
        "response_format": {
            "type": "json_schema",
            "json_schema": {"name": schema.__name__, "strict": True, "schema": schema.model_json_schema()},
        },
        "max_completion_tokens": output_limit,
        "reasoning_effort": "none" if schema is not ReplyProviderOutput else "low",
        "service_tier": "default",
    }


def _context_memory_request(
    model: str,
    context: SourceContext,
    previous: ContextMemoryPayload | None,
) -> dict[str, Any]:
    prompt = context_memory_prompt()
    comments = [
        {
            "commentRef": f"C{comment.sequence or index}",
            "authorRole": comment.authorRole.value if comment.authorRole is not None else "UNKNOWN",
            "sequence": comment.sequence if comment.sequence is not None else index,
            "createdAt": comment.createdAt.isoformat(),
            "body": comment.body,
        }
        for index, comment in enumerate(context.comments, start=1)
    ]
    return {
        "model": model,
        "messages": [
            {"role": "system", "content": prompt.content},
            {
                "role": "user",
                "content": json.dumps(
                    {
                        "previousMemory": (
                            previous.model_dump(mode="json") if previous is not None else None
                        ),
                        "publicConversationDelta": comments,
                    },
                    ensure_ascii=False,
                ),
            },
        ],
        "response_format": {
            "type": "json_schema",
            "json_schema": {
                "name": ContextMemoryProviderOutput.__name__,
                "strict": True,
                "schema": ContextMemoryProviderOutput.model_json_schema(),
            },
        },
        "max_completion_tokens": 1024,
        "reasoning_effort": "none",
        "service_tier": "default",
    }


def _litellm_receipt(call_id: UUID, requested_alias: str, response: object) -> ProviderCallReceipt:
    status, usage, issue = normalize_litellm_usage(value(response, "usage"))
    service_tier = bounded_text(value(response, "service_tier"), 24)
    if service_tier in {None, "default"}:
        service_tier = "standard"
    return ProviderCallReceipt(
        call_id=call_id,
        provider_request_id=bounded_text(value(response, "id"), 200),
        requested_alias=requested_alias,
        actual_model=bounded_text(value(response, "model"), 160),
        usage_schema_version="litellm-1.101-v1",
        usage_status=status,
        usage=usage,
        usage_issue_code=issue,
        service_tier=service_tier,
        context_price_band="short",
    )
