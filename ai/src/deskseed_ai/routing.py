from __future__ import annotations

import hashlib
import hmac
import re
import unicodedata
from dataclasses import dataclass
from typing import Callable, Literal
from uuid import UUID

from .backend_client import AiPolicy
from .retrieval import KnowledgeChunk
from .schemas import AuthorRole, ContextMemoryPayload, SourceContext

ROUTE_COHORT_VERSION = "reply-single-public-article-short-v1"
ROUTE_MARKER_VERSION = "route-risk-markers-v1"
ROUTE_POLICY_VERSION = "reply-route-v1"

_RISK_MARKERS = (
    "account",
    "billing",
    "payment",
    "permission",
    "policy",
    "refund",
    "security",
    "unless",
    "except",
    "only if",
    "계정",
    "결제",
    "권한",
    "금액",
    "기한",
    "날짜",
    "보안",
    "비밀번호",
    "예외",
    "정책",
    "조건",
    "환불",
    "경우",
    "제외",
    "이상",
    "이하",
    "미만",
    "초과",
)
_RISK_PATTERN = re.compile(
    r"(?:[$€£¥₩]\s*\d|\d\s*(?:원|달러|usd|krw|%|일|시간|분)|\d{4}[-/.]\d{1,2}[-/.]\d{1,2})",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class ReplyRouteDecision:
    route: Literal["STANDARD", "LOW_COST"]
    cohort: str | None
    marker_version: str
    policy_version: str
    rollout_percent: int


def reply_routing_policy_fingerprint(policy: AiPolicy) -> tuple[object, ...]:
    return (
        policy.version,
        policy.fastModelAlias,
        policy.standardModelAlias,
        policy.replyRoutingMode,
        tuple(policy.replyRoutingCohorts),
        policy.replyRoutingRolloutPercent,
        policy.replyRoutingEvaluationApprovalVersion,
    )


def select_reply_route(
    *,
    context: SourceContext,
    knowledge: list[KnowledgeChunk],
    memory: ContextMemoryPayload | None,
    policy: AiPolicy,
    workspace_key: str,
    requester_id: UUID,
    bucket_secret: str,
    count_tokens: Callable[[str], int],
) -> ReplyRouteDecision:
    standard = ReplyRouteDecision(
        "STANDARD", None, ROUTE_MARKER_VERSION, ROUTE_POLICY_VERSION, 0
    )
    if (
        getattr(policy, "replyRoutingMode", "STANDARD_ONLY") != "EVALUATED_COHORT"
        or ROUTE_COHORT_VERSION not in getattr(policy, "replyRoutingCohorts", [])
        or getattr(policy, "replyRoutingEvaluationApprovalVersion", None) is None
        or getattr(policy, "replyRoutingRolloutPercent", 0) not in {10, 50, 100}
        or len(bucket_secret) < 32
        or memory is not None
        or len(knowledge) != 1
    ):
        return standard
    chunk = knowledge[0]
    latest_customer = next(
        (
            comment.body
            for comment in reversed(context.comments)
            if comment.authorRole == AuthorRole.CUSTOMER
        ),
        context.comments[-1].body,
    )
    conversation = "\n".join(comment.body for comment in context.comments)
    if (
        count_tokens(latest_customer) > 256
        or count_tokens(conversation) > 512
        or count_tokens(chunk.content) > 768
        or _contains_risk_marker(latest_customer)
        or _contains_risk_marker(chunk.content)
    ):
        return standard
    bucket = _stable_bucket(
        bucket_secret, workspace_key, requester_id, ROUTE_COHORT_VERSION
    )
    rollout_percent = policy.replyRoutingRolloutPercent
    if bucket >= rollout_percent:
        return standard
    return ReplyRouteDecision(
        "LOW_COST",
        ROUTE_COHORT_VERSION,
        ROUTE_MARKER_VERSION,
        ROUTE_POLICY_VERSION,
        rollout_percent,
    )


def _contains_risk_marker(value: str) -> bool:
    normalized = unicodedata.normalize("NFKC", value).casefold()
    return any(marker in normalized for marker in _RISK_MARKERS) or _RISK_PATTERN.search(
        normalized
    ) is not None


def _stable_bucket(secret: str, workspace_key: str, requester_id: UUID, cohort: str) -> int:
    payload = f"{workspace_key}\x1f{requester_id}\x1f{cohort}".encode()
    digest = hmac.new(secret.encode(), payload, hashlib.sha256).digest()
    return int.from_bytes(digest[:8], byteorder="big", signed=False) % 100
