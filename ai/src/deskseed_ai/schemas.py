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
    REPLY_REWRITE = "ticket.reply_rewrite"


class GenerationMode(StrEnum):
    REUSE_OR_CREATE = "REUSE_OR_CREATE"
    NEW_CANDIDATE = "NEW_CANDIDATE"


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
    PUBLIC_DRAFT_ONLY = "PUBLIC_DRAFT_ONLY"
    PUBLIC_KB_ONLY = "PUBLIC_KB_ONLY"


class AuthorRole(StrEnum):
    CUSTOMER = "CUSTOMER"
    STAFF = "STAFF"
    INTEGRATION_CLIENT = "INTEGRATION_CLIENT"
    SYSTEM = "SYSTEM"
    UNKNOWN = "UNKNOWN"


class JobEnvelope(StrictModel):
    schemaVersion: Literal[1, 2, 3, 4]
    eventId: UUID
    jobId: UUID
    workspaceKey: Annotated[str, Field(min_length=1, max_length=80)]
    requesterId: UUID
    ticketId: UUID
    ticketNumber: Annotated[int, Field(gt=0)]
    feature: Feature
    contextRevision: Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]
    contextPolicyVersion: Literal["public-comments-v1", "public-comments-v2"]
    aiInputRevision: Annotated[str | None, Field(pattern=r"^[0-9a-f]{64}$")] = None
    inputPolicyVersion: Literal[
        "summary-input-v1", "triage-input-v1", "reply-input-v1", "rewrite-input-v1"
    ] | None = None
    generationMode: GenerationMode | None = None
    candidateId: UUID | None = None
    candidateSequence: Annotated[int | None, Field(gt=0)] = None
    sourceJobId: UUID | None = None
    dataClass: Literal["PUBLIC_ONLY", "PUBLIC_DRAFT_ONLY"]
    requestRevision: Annotated[int, Field(gt=0)]
    options: dict[str, Annotated[str, Field(min_length=1, max_length=40)]] = Field(default_factory=dict, max_length=3)
    createdAt: datetime
    deadlineAt: datetime
    traceparent: Annotated[str | None, Field(max_length=512)] = None
    tracestate: Annotated[str | None, Field(max_length=512)] = None

    @model_validator(mode="after")
    def deadline_after_creation(self) -> "JobEnvelope":
        if self.deadlineAt <= self.createdAt:
            raise ValueError("deadlineAt must be after createdAt")
        allowed = {
            Feature.SUMMARY: {"language"},
            Feature.TRIAGE: {"language"},
            Feature.REPLY_DRAFT: {"language", "tone"},
            Feature.REPLY_REWRITE: {"language", "tone", "length"},
        }[self.feature]
        if self.options.keys() - allowed:
            raise ValueError("unsupported feature option")
        if self.contextPolicyVersion == "public-comments-v2":
            expected = {"language": "ko"}
            if self.feature == Feature.REPLY_DRAFT:
                expected["tone"] = "calm"
            elif self.feature == Feature.REPLY_REWRITE:
                expected |= {
                    "length": self.options.get("length", ""),
                    "tone": self.options.get("tone", ""),
                }
                if expected["length"] not in {"concise", "standard"} or expected["tone"] not in {"calm", "formal"}:
                    raise ValueError("rewrite options are outside the closed catalog")
            if self.options != expected:
                raise ValueError("v2 feature options must be normalized by the Backend")
        expected_input_policy = {
            Feature.SUMMARY: "summary-input-v1",
            Feature.TRIAGE: "triage-input-v1",
            Feature.REPLY_DRAFT: "reply-input-v1",
            Feature.REPLY_REWRITE: "rewrite-input-v1",
        }[self.feature]
        if self.schemaVersion == 1:
            if any(
                value is not None
                for value in (
                    self.aiInputRevision,
                    self.inputPolicyVersion,
                    self.generationMode,
                    self.candidateId,
                    self.candidateSequence,
                    self.sourceJobId,
                )
            ):
                raise ValueError("v1 jobs cannot contain v2 input revision metadata")
        elif self.schemaVersion == 2 and (
            self.contextPolicyVersion != "public-comments-v2"
            or self.aiInputRevision is None
            or self.inputPolicyVersion != expected_input_policy
            or self.generationMode is not None
            or self.candidateId is not None
            or self.candidateSequence is not None
        ):
            raise ValueError("v2 jobs require matching input revision metadata")
        elif self.schemaVersion == 3:
            if (
                self.contextPolicyVersion != "public-comments-v2"
                or self.aiInputRevision is None
                or self.inputPolicyVersion != expected_input_policy
                or self.generationMode is None
            ):
                raise ValueError("v3 jobs require input revision and generation intent")
            if self.generationMode == GenerationMode.NEW_CANDIDATE and (
                self.candidateId is None or self.candidateSequence is None
            ):
                raise ValueError("NEW_CANDIDATE requires server candidate identity")
            if self.generationMode == GenerationMode.REUSE_OR_CREATE and (
                self.candidateId is not None or self.candidateSequence is not None
            ):
                raise ValueError("REUSE_OR_CREATE cannot contain candidate identity")
        elif self.schemaVersion == 4:
            if (
                self.feature != Feature.REPLY_REWRITE
                or self.contextPolicyVersion != "public-comments-v2"
                or self.aiInputRevision is None
                or self.inputPolicyVersion != "rewrite-input-v1"
                or self.generationMode != GenerationMode.REUSE_OR_CREATE
                or self.sourceJobId is None
                or self.candidateId is not None
                or self.candidateSequence is not None
                or self.dataClass != "PUBLIC_DRAFT_ONLY"
            ):
                raise ValueError("v4 jobs require a server-bound PUBLIC reply source")
        if self.schemaVersion != 4 and (
            self.feature == Feature.REPLY_REWRITE
            or self.sourceJobId is not None
            or self.dataClass != "PUBLIC_ONLY"
        ):
            raise ValueError("reply rewrite requires a v4 envelope")
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
    aiInputRevision: Annotated[str | None, Field(pattern=r"^[0-9a-f]{64}$")] = None
    inputPolicyVersion: Literal["summary-input-v1", "triage-input-v1", "reply-input-v1"] | None = None
    inputScope: Literal["PUBLIC_ONLY"]
    comments: list[PublicComment] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_policy_shape(self) -> "SourceContext":
        if self.feature == Feature.REPLY_REWRITE:
            raise ValueError("reply rewrite must not read PUBLIC conversation bodies")
        if self.contextPolicyVersion == "public-comments-v1":
            if (
                self.aiInputRevision is not None
                or self.inputPolicyVersion is not None
                or any(comment.sequence is not None or comment.authorRole is not None for comment in self.comments)
            ):
                raise ValueError("v1 comments cannot contain v2 role or sequence fields")
            return self
        if self.contextPolicyVersion != "public-comments-v2":
            raise ValueError("unsupported context policy version")
        if any(comment.sequence is None or comment.authorRole is None for comment in self.comments):
            raise ValueError("v2 comments require role and sequence")
        expected_input_policy = {
            Feature.SUMMARY: "summary-input-v1",
            Feature.TRIAGE: "triage-input-v1",
            Feature.REPLY_DRAFT: "reply-input-v1",
        }[self.feature]
        if (self.aiInputRevision is None) != (self.inputPolicyVersion is None):
            raise ValueError("v2 context input revision metadata must be paired")
        if self.inputPolicyVersion is not None and self.inputPolicyVersion != expected_input_policy:
            raise ValueError("v2 context input policy does not match the feature")
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


class ReplyRewriteResult(StrictModel):
    type: Literal["ticket.reply_rewrite"] = "ticket.reply_rewrite"
    answer: Annotated[str, Field(min_length=1, max_length=6000)]
    citations: list[Citation] = Field(min_length=1, max_length=8)
    language: Literal["ko"] = "ko"
    tone: Literal["calm", "formal"]
    length: Literal["concise", "standard"]


TypedResult = SummaryResult | TriageResult | ReplyDraftResult | ReplyRewriteResult


class ReplyProviderOutput(StrictModel):
    answer: Annotated[str, Field(min_length=1, max_length=6000)]
    sourceRefs: list[Annotated[str, Field(pattern=r"^S[1-8]$")]] = Field(min_length=1, max_length=8)

    @model_validator(mode="after")
    def source_refs_are_unique(self) -> "ReplyProviderOutput":
        if len(set(self.sourceRefs)) != len(self.sourceRefs):
            raise ValueError("sourceRefs must be unique")
        return self


class ReplyRewriteProviderOutput(StrictModel):
    answer: Annotated[str, Field(min_length=1, max_length=6000)]
    sourceRefs: list[Annotated[str, Field(pattern=r"^S[1-8]$")]] = Field(min_length=1, max_length=8)

    @model_validator(mode="after")
    def source_refs_are_unique(self) -> "ReplyRewriteProviderOutput":
        if len(set(self.sourceRefs)) != len(self.sourceRefs):
            raise ValueError("rewrite sourceRefs must be unique")
        return self


class ReplyRewritePreservationVerdict(StrictModel):
    preserved: bool
    changedCategories: list[
        Literal["NAME", "POLICY", "AMOUNT", "DATE", "CONDITION", "NEGATION"]
    ] = Field(max_length=6)

    @model_validator(mode="after")
    def verdict_shape_is_consistent(self) -> "ReplyRewritePreservationVerdict":
        if self.preserved != (not self.changedCategories):
            raise ValueError("preservation verdict is inconsistent")
        if len(set(self.changedCategories)) != len(self.changedCategories):
            raise ValueError("preservation categories must be unique")
        return self


class ContextMemoryItem(StrictModel):
    text: Annotated[str, Field(min_length=1, max_length=1000)]
    sourceRefs: list[Annotated[str, Field(pattern=r"^C[1-9][0-9]{0,5}$")]] = Field(
        min_length=1, max_length=20
    )

    @model_validator(mode="after")
    def source_refs_are_unique(self) -> "ContextMemoryItem":
        if len(set(self.sourceRefs)) != len(self.sourceRefs):
            raise ValueError("context memory source refs must be unique")
        return self


class ContextMemoryPayload(StrictModel):
    confirmedFacts: list[ContextMemoryItem] = Field(max_length=30)
    attemptsAndOutcomes: list[ContextMemoryItem] = Field(max_length=30)
    openQuestions: list[ContextMemoryItem] = Field(max_length=30)


class ContextMemoryProviderOutput(ContextMemoryPayload):
    conflictSourceRefs: list[
        Annotated[str, Field(pattern=r"^C[1-9][0-9]{0,5}$")]
    ] = Field(max_length=20)

    @model_validator(mode="after")
    def conflict_refs_are_unique(self) -> "ContextMemoryProviderOutput":
        if len(set(self.conflictSourceRefs)) != len(self.conflictSourceRefs):
            raise ValueError("context memory conflict refs must be unique")
        return self


ProviderOutput = (
    SummaryResult
    | TriageResult
    | ReplyProviderOutput
    | ReplyRewriteProviderOutput
    | ReplyRewritePreservationVerdict
    | ContextMemoryProviderOutput
)


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
    sourceJobId: UUID | None = None
    contextRevision: str
    contextPolicyVersion: str
    inputScope: InputScope
    stale: bool
    canInsert: bool
    errorCode: str | None = None
    result: TypedResult | None = Field(default=None, discriminator="type")
    provenance: GenerationProvenance | None = None
    costMicrousd: int | None = None
    generationMode: GenerationMode | None = None
    candidateSequence: int | None = None
    reuseKind: Literal["GENERATED", "CACHE_HIT", "COALESCED"] | None = None
    providerDispatched: bool = False


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


class EmbeddingBatchStatus(StrictModel):
    counts: dict[str, Annotated[int, Field(ge=0)]]
    cleanupPendingCount: Annotated[int, Field(ge=0)]
    oldestCleanupPendingAt: datetime | None


class ServiceStatus(StrictModel):
    ready: bool
    dataAsOf: datetime
    dependencies: DependencyStatus
    providerMode: Literal["fake", "litellm"]
    liveProviderEnabled: bool
    langfuseEnabled: bool
    telemetry: TelemetryExporterStatus
    jobCounts: dict[str, Annotated[int, Field(ge=0)]]
    sharedExecutionCounts: dict[str, Annotated[int, Field(ge=0)]]
    budget: BudgetStatus
    index: KnowledgeIndexStatus
    embeddingBatches: EmbeddingBatchStatus
    deadLetterCount: Annotated[int, Field(ge=0)]
