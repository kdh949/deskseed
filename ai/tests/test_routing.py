from __future__ import annotations

from datetime import UTC, datetime
from uuid import UUID, uuid4

from deskseed_ai.backend_client import AiPolicy
from deskseed_ai.retrieval import KnowledgeChunk
from deskseed_ai.routing import (
    ROUTE_COHORT_VERSION,
    ROUTE_MARKER_VERSION,
    ROUTE_POLICY_VERSION,
    select_reply_route,
)
from deskseed_ai.schemas import (
    AuthorRole,
    ContextMemoryPayload,
    Feature,
    PublicComment,
    SourceContext,
)

SECRET = "reply-routing-test-secret-at-least-32-bytes"


def test_stable_actor_bucket_is_nested_across_rollout_percentages() -> None:
    context = _context("도움이 필요해요")
    knowledge = [_knowledge("설정 화면에서 기능을 켜세요.")]
    selected: UUID | None = None
    moves_at_fifty: UUID | None = None
    for value in range(1, 2_000):
        actor = UUID(int=value)
        at_ten = _route(context, knowledge, actor, rollout=10)
        at_fifty = _route(context, knowledge, actor, rollout=50)
        if at_ten.route == "LOW_COST":
            selected = actor
        elif at_fifty.route == "LOW_COST":
            moves_at_fifty = actor
        if selected is not None and moves_at_fifty is not None:
            break

    assert selected is not None
    assert moves_at_fifty is not None
    assert _route(context, knowledge, selected, rollout=10).route == "LOW_COST"
    assert _route(context, knowledge, selected, rollout=50).route == "LOW_COST"
    assert _route(context, knowledge, selected, rollout=100).route == "LOW_COST"
    assert _route(context, knowledge, moves_at_fifty, rollout=10).route == "STANDARD"
    assert _route(context, knowledge, moves_at_fifty, rollout=50).route == "LOW_COST"


def test_route_requires_approved_simple_body_free_cohort() -> None:
    actor = UUID(int=1)
    context = _context("도움이 필요해요")
    knowledge = [_knowledge("설정 화면에서 기능을 켜세요.")]
    policy = _policy(100)

    selected = select_reply_route(
        context=context,
        knowledge=knowledge,
        memory=None,
        policy=policy,
        workspace_key="default",
        requester_id=actor,
        bucket_secret=SECRET,
        count_tokens=len,
    )

    assert selected.route == "LOW_COST"
    assert selected.cohort == ROUTE_COHORT_VERSION
    assert selected.marker_version == ROUTE_MARKER_VERSION
    assert selected.policy_version == ROUTE_POLICY_VERSION
    assert selected.rollout_percent == 100

    memory = ContextMemoryPayload(
        confirmedFacts=[], attemptsAndOutcomes=[], openQuestions=[]
    )
    exclusions = [
        (context, knowledge, memory, SECRET),
        (_context("환불 금액을 알려주세요"), knowledge, None, SECRET),
        (context, [_knowledge("2026-09-19까지 신청하세요")], None, SECRET),
        (context, knowledge * 2, None, SECRET),
        (context, knowledge, None, "short"),
    ]
    for candidate_context, candidate_knowledge, candidate_memory, secret in exclusions:
        decision = select_reply_route(
            context=candidate_context,
            knowledge=candidate_knowledge,
            memory=candidate_memory,
            policy=policy,
            workspace_key="default",
            requester_id=actor,
            bucket_secret=secret,
            count_tokens=len,
        )
        assert decision.route == "STANDARD"
        assert decision.cohort is None
        assert decision.rollout_percent == 0


def test_route_fails_closed_for_unapproved_policy_and_token_bounds() -> None:
    context = _context("가" * 257)
    knowledge = [_knowledge("설정 화면에서 기능을 켜세요.")]
    standard_policy = _policy(100).model_copy(
        update={
            "replyRoutingMode": "STANDARD_ONLY",
            "replyRoutingCohorts": [],
            "replyRoutingRolloutPercent": 0,
            "replyRoutingEvaluationApprovalVersion": None,
        }
    )

    assert _select(context, knowledge, _policy(100)).route == "STANDARD"
    assert _select(_context("도움이 필요해요"), knowledge, standard_policy).route == "STANDARD"


def _route(
    context: SourceContext,
    knowledge: list[KnowledgeChunk],
    actor: UUID,
    *,
    rollout: int,
):
    return select_reply_route(
        context=context,
        knowledge=knowledge,
        memory=None,
        policy=_policy(rollout),
        workspace_key="default",
        requester_id=actor,
        bucket_secret=SECRET,
        count_tokens=len,
    )


def _select(
    context: SourceContext,
    knowledge: list[KnowledgeChunk],
    policy: AiPolicy,
):
    return select_reply_route(
        context=context,
        knowledge=knowledge,
        memory=None,
        policy=policy,
        workspace_key="default",
        requester_id=UUID(int=1),
        bucket_secret=SECRET,
        count_tokens=len,
    )


def _policy(rollout: int) -> AiPolicy:
    now = datetime.now(UTC)
    return AiPolicy(
        enabled=True,
        features={Feature.REPLY_DRAFT.value: True},
        fastModelAlias="openai/gpt-5.6-luna",
        standardModelAlias="openai/gpt-5.6-terra",
        replyRoutingMode="EVALUATED_COHORT",
        replyRoutingCohorts=[ROUTE_COHORT_VERSION],
        replyRoutingRolloutPercent=rollout,
        replyRoutingEvaluationApprovalVersion="holdout-v1",
        version=2,
        updatedAt=now,
        dataAsOf=now,
        canonicalPublicCorpusRevision=7,
    )


def _context(body: str) -> SourceContext:
    return SourceContext(
        jobId=uuid4(),
        ticketId=uuid4(),
        ticketNumber=1,
        ticketVersion=1,
        feature=Feature.REPLY_DRAFT,
        requestRevision=1,
        contextRevision="a" * 64,
        contextPolicyVersion="public-comments-v2",
        aiInputRevision="b" * 64,
        inputPolicyVersion="reply-input-v1",
        inputScope="PUBLIC_ONLY",
        comments=[
            PublicComment(
                id=uuid4(),
                sequence=1,
                authorRole=AuthorRole.CUSTOMER,
                body=body,
                createdAt=datetime.now(UTC),
            )
        ],
    )


def _knowledge(content: str) -> KnowledgeChunk:
    return KnowledgeChunk(
        chunk_id=uuid4(),
        article_id=uuid4(),
        revision_id=uuid4(),
        title="도움말",
        slug="help",
        content=content,
        score=1.0,
    )
