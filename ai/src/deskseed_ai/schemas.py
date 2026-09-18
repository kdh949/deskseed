from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Feature(StrEnum):
    SUMMARY = "ticket.summary"
    TRIAGE = "ticket.triage"
    REPLY_DRAFT = "ticket.reply_draft"


class JobStatus(StrEnum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    RETRY_WAIT = "RETRY_WAIT"
    SUCCEEDED = "SUCCEEDED"
    NEEDS_REVIEW = "NEEDS_REVIEW"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"
    SUPERSEDED = "SUPERSEDED"
    EXPIRED = "EXPIRED"


class JobPhase(StrEnum):
    QUEUED = "QUEUED"
    AUTHORIZE = "AUTHORIZE"
    RETRIEVE = "RETRIEVE"
    GENERATE = "GENERATE"
    VALIDATE = "VALIDATE"
    COMPLETE = "COMPLETE"


class InputScope(StrEnum):
    PUBLIC_ONLY = "PUBLIC_ONLY"
    PUBLIC_KB_ONLY = "PUBLIC_KB_ONLY"


class AuthorRole(StrEnum):
    CUSTOMER = "CUSTOMER"
    STAFF = "STAFF"
    INTEGRATION_CLIENT = "INTEGRATION_CLIENT"
    SYSTEM = "SYSTEM"
    UNKNOWN = "UNKNOWN"


class JobEnvelope(StrictModel):
    schemaVersion: Literal[1]
    eventId: UUID
    jobId: UUID
    workspaceKey: Annotated[str, Field(min_length=1, max_length=80)]
    requesterId: UUID
    ticketId: UUID
    ticketNumber: Annotated[int, Field(gt=0)]
    feature: Feature
    contextRevision: Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]
    contextPolicyVersion: Literal["public-comments-v1", "public-comments-v2"]
    dataClass: Literal["PUBLIC_ONLY"]
    requestRevision: Annotated[int, Field(gt=0)]
    options: dict[str, Annotated[str, Field(min_length=1, max_length=40)]] = Field(default_factory=dict, max_length=2)
    createdAt: datetime
    deadlineAt: datetime
    traceparent: Annotated[str | None, Field(max_length=512)] = None
    tracestate: Annotated[str | None, Field(max_length=512)] = None

    @model_validator(mode="after")
    def deadline_after_creation(self) -> "JobEnvelope":
        if self.deadlineAt <= self.createdAt:
            raise ValueError("deadlineAt must be after createdAt")
        allowed = {"language"} if self.feature != Feature.REPLY_DRAFT else {"language", "tone"}
        if self.options.keys() - allowed:
            raise ValueError("unsupported feature option")
        if self.contextPolicyVersion == "public-comments-v2":
            expected = {"language": "ko"}
            if self.feature == Feature.REPLY_DRAFT:
                expected["tone"] = "calm"
            if self.options != expected:
                raise ValueError("v2 feature options must be normalized by the Backend")
        return self


class CancellationEnvelope(StrictModel):
    schemaVersion: Literal[1]
    eventId: UUID
    jobId: UUID
    workspaceKey: Annotated[str, Field(min_length=1, max_length=80)]
    requestRevision: Annotated[int, Field(gt=1)]
    createdAt: datetime
    traceparent: Annotated[str | None, Field(max_length=512)] = None
    tracestate: Annotated[str | None, Field(max_length=512)] = None


class PublicComment(StrictModel):
    id: UUID
    sequence: Annotated[int | None, Field(gt=0)] = None
    authorRole: AuthorRole | None = None
    body: Annotated[str, Field(min_length=1, max_length=100_000)]
    createdAt: datetime


class SourceContext(StrictModel):
    jobId: UUID
    ticketId: UUID
    ticketNumber: int
    ticketVersion: int
    feature: Feature
    requestRevision: int
    contextRevision: str
    contextPolicyVersion: str
    inputScope: Literal["PUBLIC_ONLY"]
    comments: list[PublicComment] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_policy_shape(self) -> "SourceContext":
        if self.contextPolicyVersion == "public-comments-v1":
            if any(comment.sequence is not None or comment.authorRole is not None for comment in self.comments):
                raise ValueError("v1 comments cannot contain v2 role or sequence fields")
            return self
        if self.contextPolicyVersion != "public-comments-v2":
            raise ValueError("unsupported context policy version")
        if any(comment.sequence is None or comment.authorRole is None for comment in self.comments):
            raise ValueError("v2 comments require role and sequence")
        sequences = [comment.sequence for comment in self.comments if comment.sequence is not None]
        if sequences != list(range(1, len(sequences) + 1)):
            raise ValueError("v2 PUBLIC comment sequence must be contiguous and increasing")
        return self


class SummaryResult(StrictModel):
    type: Literal["ticket.summary"] = "ticket.summary"
    problem: Annotated[str, Field(min_length=1, max_length=2000)]
    attemptedActions: list[Annotated[str, Field(min_length=1, max_length=500)]] = Field(max_length=20)
    unresolvedItems: list[Annotated[str, Field(min_length=1, max_length=500)]] = Field(max_length=20)
    nextChecks: list[Annotated[str, Field(min_length=1, max_length=500)]] = Field(max_length=20)


class TriageResult(StrictModel):
    type: Literal["ticket.triage"] = "ticket.triage"
    topicCode: Literal[
        "ACCOUNT_ACCESS", "BILLING", "USAGE", "TECHNICAL_ISSUE",
        "POLICY", "FEEDBACK", "OTHER",
    ]
    suggestedTagIds: list[UUID] = Field(max_length=20)
    suggestedPriority: Literal["LOW", "NORMAL", "HIGH", "URGENT"] | None = None
    reasons: list[Annotated[str, Field(min_length=1, max_length=300)]] = Field(max_length=10)


class Citation(StrictModel):
    articleId: UUID
    revisionId: UUID
    chunkId: UUID
    title: Annotated[str, Field(min_length=1, max_length=300)]
    url: Annotated[str, Field(pattern=r"^/help/articles/[a-z0-9-]+$")]


class ReplyDraftResult(StrictModel):
    type: Literal["ticket.reply_draft"] = "ticket.reply_draft"
    answer: Annotated[str, Field(min_length=1, max_length=6000)]
    citations: list[Citation] = Field(max_length=8)


TypedResult = SummaryResult | TriageResult | ReplyDraftResult


class GenerationProvenance(StrictModel):
    modelAlias: Annotated[str, Field(min_length=1, max_length=100)]
    actualModel: Annotated[str, Field(min_length=1, max_length=160)]
    promptVersion: Annotated[str, Field(min_length=1, max_length=80)]
    configVersion: Annotated[str, Field(min_length=1, max_length=80)]
    generatedAt: datetime
    publicCommentIds: list[UUID] = Field(max_length=500)
    contextRevision: Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]


class JobReceipt(StrictModel):
    jobId: UUID
    feature: Feature
    status: JobStatus
    phase: JobPhase
    generation: int
    leaseEpoch: int
    requestRevision: int
    createdAt: datetime
    deadlineAt: datetime
    completedAt: datetime | None = None
    resultExpiresAt: datetime | None = None
    pollAfterMs: int = 750
    cancelRequested: bool
    contextRevision: str
    contextPolicyVersion: str
    inputScope: InputScope
    stale: bool
    canInsert: bool
    errorCode: str | None = None
    result: TypedResult | None = Field(default=None, discriminator="type")
    provenance: GenerationProvenance | None = None
    costMicrousd: int | None = None


class FeedbackRequest(StrictModel):
    schemaVersion: Literal[1]
    eventId: UUID
    jobId: UUID
    workspaceKey: Annotated[str, Field(min_length=1, max_length=80)]
    requesterId: UUID
    feedbackType: Literal["helpful", "unhelpful", "inserted", "edited"]
    reasonCode: Annotated[str | None, Field(min_length=1, max_length=40)] = None
    sourceRevision: Annotated[int, Field(gt=0)]
    requestRevision: Annotated[int, Field(gt=1)]
    createdAt: datetime


class IndexEvent(StrictModel):
    schemaVersion: Literal[1]
    eventId: UUID
    workspaceKey: Annotated[str, Field(min_length=1, max_length=80)]
    articleId: UUID
    revisionId: UUID
    action: Literal["UPSERT", "DELETE"]
    sourceVersion: Annotated[int, Field(gt=0)]
    publicRevision: Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]
    createdAt: datetime


class OperationRequest(StrictModel):
    operationId: UUID
    action: Literal["RETRY", "CANCEL", "RECONCILE", "RETENTION"]
    reason: Annotated[str, Field(min_length=3, max_length=500)]
    expectedGeneration: Annotated[int | None, Field(ge=0)] = None


class Accepted(StrictModel):
    accepted: Literal[True] = True
    replayed: bool
    jobId: UUID | None = None


class TelemetryDropCounters(StrictModel):
    jobStart: Annotated[int, Field(ge=0, description="job trace 시작 export 실패 누적 횟수", examples=[0])]
    jobUpdate: Annotated[int, Field(ge=0, description="job outcome update export 실패 누적 횟수", examples=[0])]
    jobEnd: Annotated[int, Field(ge=0, description="job trace 종료 export 실패 누적 횟수", examples=[0])]
    providerObservation: Annotated[
        int,
        Field(ge=0, description="canonical receipt 저장 뒤 provider observation export 실패 누적 횟수", examples=[1]),
    ]
    flush: Annotated[int, Field(ge=0, description="exporter flush 실패 누적 횟수", examples=[0])]


class TelemetryExporterStatus(StrictModel):
    enabled: Annotated[bool, Field(description="현재 프로세스에 export client가 구성되었는지 여부")]
    dropped: TelemetryDropCounters
    feedbackRetries: Annotated[
        int,
        Field(ge=0, description="feedback score export 실패로 durable retry가 필요한 누적 횟수", examples=[2]),
    ]


class DependencyStatus(StrictModel):
    postgres: bool
    redis: bool


class BudgetStatus(StrictModel):
    reservedMicrousd: Annotated[int, Field(ge=0)]
    settledMicrousd: Annotated[int, Field(ge=0)]
    unknownMicrousd: Annotated[int, Field(ge=0)]


class KnowledgeIndexStatus(StrictModel):
    publicRevisions: Annotated[int, Field(ge=0)]
    lastReconciledAt: datetime | None


class ServiceStatus(StrictModel):
    ready: bool
    dataAsOf: datetime
    dependencies: DependencyStatus
    providerMode: Literal["fake", "litellm"]
    liveProviderEnabled: bool
    langfuseEnabled: bool
    telemetry: TelemetryExporterStatus
    jobCounts: dict[str, Annotated[int, Field(ge=0)]]
    budget: BudgetStatus
    index: KnowledgeIndexStatus
    deadLetterCount: Annotated[int, Field(ge=0)]
