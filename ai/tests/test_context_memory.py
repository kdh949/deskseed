from __future__ import annotations

from dataclasses import replace
from datetime import UTC, datetime
from uuid import uuid4

import pytest

from deskseed_ai.context_memory import (
    ContextMemoryConflictError,
    ContextMemorySourceChangedError,
    ContextMemoryValidationError,
    plan_context_memory,
    source_prefix_digest,
    validate_provider_output,
)
from deskseed_ai.repository import ContextMemoryRecord
from deskseed_ai.schemas import (
    AuthorRole,
    ContextMemoryItem,
    ContextMemoryPayload,
    ContextMemoryProviderOutput,
    Feature,
    PublicComment,
    SourceContext,
)


def _context(bodies: list[tuple[AuthorRole, str]]) -> SourceContext:
    comments = [
        PublicComment(
            id=uuid4(),
            sequence=index,
            authorRole=role,
            body=body,
            createdAt=datetime(2026, 9, 19, 1, index, tzinfo=UTC),
        )
        for index, (role, body) in enumerate(bodies, start=1)
    ]
    return SourceContext(
        jobId=uuid4(),
        ticketId=uuid4(),
        ticketNumber=1042,
        ticketVersion=1,
        feature=Feature.REPLY_DRAFT,
        requestRevision=1,
        contextRevision="a" * 64,
        contextPolicyVersion="public-comments-v2",
        aiInputRevision="b" * 64,
        inputPolicyVersion="reply-input-v1",
        inputScope="PUBLIC_ONLY",
        comments=comments,
    )


def _record(context: SourceContext, covered: int, payload: ContextMemoryPayload) -> ContextMemoryRecord:
    now = datetime(2026, 9, 19, tzinfo=UTC)
    return ContextMemoryRecord(
        memory_id=uuid4(),
        workspace_key="default",
        requester_id=uuid4(),
        ticket_id=context.ticketId,
        memory_version=1,
        covered_through_sequence=covered,
        source_prefix_digest=source_prefix_digest(context.comments[:covered]),
        schema_version="context-memory-v1",
        policy_version="context-memory-policy-v1",
        prompt_version="context-memory-v1:test",
        model_alias="openai/gpt-5.6-luna",
        update_count=0,
        payload=payload,
        created_at=now,
        expires_at=now,
    )


def test_short_context_does_not_plan_memory() -> None:
    context = _context([(AuthorRole.CUSTOMER, "짧은 문의")])

    assert plan_context_memory(context, None) is None


def test_long_context_builds_prefix_and_keeps_latest_customer_suffix_raw() -> None:
    context = _context(
        [
            (AuthorRole.CUSTOMER, "첫 문의 " + "가" * 4_500),
            (AuthorRole.STAFF, "시도 결과 " + "나" * 4_500),
            (AuthorRole.CUSTOMER, "최신 질문 ERR-42"),
            (AuthorRole.STAFF, "확인 중입니다"),
        ]
    )

    plan = plan_context_memory(context, None)

    assert plan is not None
    assert plan.target_sequence == 2
    assert [comment.sequence for comment in plan.build_context.comments] == [1, 2]
    assert [comment.sequence for comment in plan.recent_context.comments] == [3, 4]
    assert plan.previous_payload is None
    assert plan.full_rebuild is True


def test_existing_memory_uses_delta_and_source_drift_fails_closed() -> None:
    context = _context(
        [
            (AuthorRole.CUSTOMER, "가" * 4_100),
            (AuthorRole.STAFF, "나" * 4_100),
            (AuthorRole.STAFF, "새 시도"),
            (AuthorRole.CUSTOMER, "최신 질문"),
        ]
    )
    payload = ContextMemoryPayload(
        confirmedFacts=[ContextMemoryItem(text="첫 문의", sourceRefs=["C1"])],
        attemptsAndOutcomes=[],
        openQuestions=[],
    )
    existing = _record(context, 2, payload)

    plan = plan_context_memory(context, existing)

    assert plan is not None
    assert [comment.sequence for comment in plan.build_context.comments] == [3]
    assert plan.previous_payload == payload
    changed = context.model_copy(
        update={
            "comments": [
                context.comments[0].model_copy(update={"body": "수정된 원문"}),
                *context.comments[1:],
            ]
        }
    )
    with pytest.raises(ContextMemorySourceChangedError):
        plan_context_memory(changed, existing)

    full_rebuild = plan_context_memory(context, replace(existing, update_count=2))
    assert full_rebuild is not None
    assert [comment.sequence for comment in full_rebuild.build_context.comments] == [1, 2, 3]
    assert full_rebuild.previous_payload is None
    assert full_rebuild.full_rebuild is True

    invalid_refs = replace(
        existing,
        payload=ContextMemoryPayload(
            confirmedFacts=[ContextMemoryItem(text="잘못된 저장값", sourceRefs=["C99"])],
            attemptsAndOutcomes=[],
            openQuestions=[],
        ),
    )
    with pytest.raises(ContextMemorySourceChangedError):
        plan_context_memory(context, invalid_refs)


def test_memory_output_requires_known_sources_and_holds_conflicts() -> None:
    valid = ContextMemoryProviderOutput(
        confirmedFacts=[ContextMemoryItem(text="오류 코드", sourceRefs=["C1"])],
        attemptsAndOutcomes=[],
        openQuestions=[],
        conflictSourceRefs=[],
    )
    assert validate_provider_output(valid, 1).confirmedFacts[0].text == "오류 코드"

    unknown = valid.model_copy(
        update={
            "confirmedFacts": [ContextMemoryItem(text="잘못된 참조", sourceRefs=["C2"])]
        }
    )
    with pytest.raises(ContextMemoryValidationError):
        validate_provider_output(unknown, 1)

    conflict = valid.model_copy(update={"conflictSourceRefs": ["C1"]})
    with pytest.raises(ContextMemoryConflictError):
        validate_provider_output(conflict, 1)
