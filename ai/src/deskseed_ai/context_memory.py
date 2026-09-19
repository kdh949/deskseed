from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass

from .repository import ContextMemoryRecord
from .schemas import (
    AuthorRole,
    ContextMemoryPayload,
    ContextMemoryProviderOutput,
    PublicComment,
    SourceContext,
)

MEMORY_SCHEMA_VERSION = "context-memory-v1"
MEMORY_POLICY_VERSION = "context-memory-policy-v1"
RAW_REPLY_LIMIT_BYTES = 8_000
FULL_REBUILD_AFTER_UPDATES = 2
FULL_REBUILD_INTERVAL = FULL_REBUILD_AFTER_UPDATES + 1


class ContextMemoryValidationError(RuntimeError):
    pass


class ContextMemoryConflictError(RuntimeError):
    pass


class ContextMemorySourceChangedError(RuntimeError):
    def __init__(self, record: ContextMemoryRecord):
        super().__init__("context memory source prefix changed")
        self.record = record


@dataclass(frozen=True)
class ContextMemoryPlan:
    target_sequence: int
    source_prefix_digest: str
    build_context: SourceContext | None
    recent_context: SourceContext
    previous_payload: ContextMemoryPayload | None
    existing: ContextMemoryRecord | None
    full_rebuild: bool

    @property
    def requires_provider_call(self) -> bool:
        return self.build_context is not None


def plan_context_memory(
    context: SourceContext,
    existing: ContextMemoryRecord | None,
) -> ContextMemoryPlan | None:
    if context.contextPolicyVersion != "public-comments-v2":
        return None
    if _context_bytes(context.comments) <= RAW_REPLY_LIMIT_BYTES:
        return None
    latest_customer_index = next(
        (
            index
            for index in range(len(context.comments) - 1, -1, -1)
            if context.comments[index].authorRole == AuthorRole.CUSTOMER
        ),
        len(context.comments) - 1,
    )
    protected = context.comments[latest_customer_index:]
    if _context_bytes(protected) > RAW_REPLY_LIMIT_BYTES:
        return None
    prefix = context.comments[:latest_customer_index]
    if not prefix:
        return None
    target_sequence = prefix[-1].sequence
    if target_sequence is None:
        return None

    valid_existing = existing
    if valid_existing is not None:
        if (
            valid_existing.covered_through_sequence > target_sequence
            or not _payload_refs_within(
                valid_existing.payload, valid_existing.covered_through_sequence
            )
        ):
            raise ContextMemorySourceChangedError(valid_existing)
        else:
            covered = context.comments[: valid_existing.covered_through_sequence]
            if (
                len(covered) != valid_existing.covered_through_sequence
                or source_prefix_digest(covered) != valid_existing.source_prefix_digest
            ):
                raise ContextMemorySourceChangedError(valid_existing)

    recent_start = valid_existing.covered_through_sequence if valid_existing is not None else target_sequence
    if valid_existing is not None and valid_existing.covered_through_sequence == target_sequence:
        return ContextMemoryPlan(
            target_sequence=target_sequence,
            source_prefix_digest=source_prefix_digest(prefix),
            build_context=None,
            recent_context=_with_comments(context, context.comments[target_sequence:]),
            previous_payload=valid_existing.payload,
            existing=valid_existing,
            full_rebuild=False,
        )

    full_rebuild = valid_existing is None or valid_existing.update_count >= FULL_REBUILD_AFTER_UPDATES
    build_comments = prefix if full_rebuild else context.comments[recent_start:target_sequence]
    if not build_comments:
        return None
    previous_payload = None
    if not full_rebuild:
        assert valid_existing is not None
        previous_payload = valid_existing.payload
    return ContextMemoryPlan(
        target_sequence=target_sequence,
        source_prefix_digest=source_prefix_digest(prefix),
        build_context=_with_comments(context, build_comments),
        recent_context=_with_comments(context, context.comments[target_sequence:]),
        previous_payload=previous_payload,
        existing=valid_existing,
        full_rebuild=full_rebuild,
    )


def validate_provider_output(
    output: ContextMemoryProviderOutput,
    target_sequence: int,
) -> ContextMemoryPayload:
    known = {f"C{sequence}" for sequence in range(1, target_sequence + 1)}
    if output.conflictSourceRefs:
        if not set(output.conflictSourceRefs) <= known:
            raise ContextMemoryValidationError("context memory conflict refs are outside coverage")
        raise ContextMemoryConflictError("context memory sources conflict")
    items = output.confirmedFacts + output.attemptsAndOutcomes + output.openQuestions
    if not items:
        raise ContextMemoryValidationError("context memory output is empty")
    for item in items:
        if not set(item.sourceRefs) <= known:
            raise ContextMemoryValidationError("context memory source refs are outside coverage")
    return ContextMemoryPayload(
        confirmedFacts=output.confirmedFacts,
        attemptsAndOutcomes=output.attemptsAndOutcomes,
        openQuestions=output.openQuestions,
    )


def source_prefix_digest(comments: list[PublicComment]) -> str:
    canonical = [
        {
            "id": str(comment.id),
            "sequence": comment.sequence,
            "authorRole": comment.authorRole.value if comment.authorRole is not None else None,
            "createdAt": comment.createdAt.isoformat(),
            "body": comment.body,
        }
        for comment in comments
    ]
    encoded = json.dumps(canonical, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _context_bytes(comments: list[PublicComment]) -> int:
    return sum(len(comment.body.encode("utf-8")) for comment in comments)


def _payload_refs_within(payload: ContextMemoryPayload, covered_sequence: int) -> bool:
    known = {f"C{sequence}" for sequence in range(1, covered_sequence + 1)}
    return all(
        set(item.sourceRefs) <= known
        for item in payload.confirmedFacts + payload.attemptsAndOutcomes + payload.openQuestions
    )


def _with_comments(context: SourceContext, comments: list[PublicComment]) -> SourceContext:
    return context.model_copy(update={"comments": comments})
