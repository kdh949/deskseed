from __future__ import annotations

from datetime import datetime
from typing import Annotated, Literal
from uuid import UUID

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

from .config import Settings
from .schemas import Citation, SourceContext


class BackendAuthorizationError(RuntimeError):
    pass


class BackendSupersededError(RuntimeError):
    pass


class BackendPolicyDisabledError(RuntimeError):
    pass


class BackendClient:
    def __init__(self, settings: Settings):
        self.settings = settings

    def read_context(self, job_id: UUID, traceparent: str | None = None) -> SourceContext:
        headers = {
            "X-Deskseed-AI-Key-Id": self.settings.backend_source_key_id,
            "Authorization": f"Bearer {self.settings.backend_source_secret.get_secret_value()}",
        }
        if traceparent:
            headers["traceparent"] = traceparent
        with httpx.Client(base_url=self.settings.backend_base_url, timeout=10.0) as client:
            response = client.get(f"/api/v1/internal/ai/requests/{job_id}/context", headers=headers)
        if response.status_code == 409:
            raise BackendSupersededError("backend PUBLIC projection changed")
        if response.status_code != 200:
            raise BackendAuthorizationError(f"backend source rejected request with status {response.status_code}")
        return SourceContext.model_validate(response.json())

    def read_context_revision(self, job_id: UUID) -> "ContextRevision":
        with httpx.Client(base_url=self.settings.backend_base_url, timeout=10.0) as client:
            response = client.get(
                f"/api/v1/internal/ai/requests/{job_id}/context-revision",
                headers=self._source_headers(),
            )
        if response.status_code != 200:
            raise BackendAuthorizationError(f"backend context revision rejected request with status {response.status_code}")
        revision = ContextRevision.model_validate(response.json())
        if not revision.authorized or revision.cancelRequested:
            raise BackendSupersededError("backend no longer authorizes this job")
        if not revision.featureEnabled:
            raise BackendPolicyDisabledError("AI feature is disabled")
        return revision

    def read_policy(self, feature: str) -> "AiPolicy":
        with httpx.Client(base_url=self.settings.backend_base_url, timeout=10.0) as client:
            response = client.get("/api/v1/internal/ai/policy", headers=self._source_headers())
        if response.status_code != 200:
            raise BackendAuthorizationError(f"backend AI policy rejected request with status {response.status_code}")
        policy = AiPolicy.model_validate(response.json())
        if not policy.enabled or not policy.features.get(feature, False):
            raise BackendPolicyDisabledError("AI feature is disabled")
        return policy

    def authorize_citations(self, job_id: UUID, citations: list[Citation]) -> list[Citation]:
        payload = {
            "candidates": [
                {
                    "articleId": str(item.articleId),
                    "revisionId": str(item.revisionId),
                    "chunkId": str(item.chunkId),
                }
                for item in citations
            ]
        }
        with httpx.Client(base_url=self.settings.backend_base_url, timeout=10.0) as client:
            response = client.post(
                f"/api/v1/internal/ai/requests/{job_id}/kb/authorize",
                json=payload,
                headers=self._source_headers(),
            )
        if response.status_code == 409:
            raise BackendSupersededError("knowledge citation authorization changed")
        if response.status_code != 200:
            raise BackendAuthorizationError(f"backend citation authorization rejected status {response.status_code}")
        try:
            body = AuthorizedKnowledgeResponse.model_validate(response.json())
        except (ValidationError, ValueError, TypeError) as exception:
            raise BackendAuthorizationError("backend citation authorization response is malformed") from exception
        requested = {
            item.chunkId: (index, item.articleId, item.revisionId)
            for index, item in enumerate(citations)
        }
        positions: list[int] = []
        seen: set[UUID] = set()
        authorized: list[Citation] = []
        for item in body.items:
            expected = requested.get(item.chunkId)
            if (
                expected is None
                or item.chunkId in seen
                or item.articleId != expected[1]
                or item.revisionId != expected[2]
            ):
                raise BackendAuthorizationError("backend citation authorization response is invalid")
            positions.append(expected[0])
            seen.add(item.chunkId)
            try:
                authorized.append(Citation.model_validate(item.model_dump()))
            except ValidationError as exception:
                raise BackendAuthorizationError("backend citation metadata is invalid") from exception
        if positions != sorted(positions):
            raise BackendAuthorizationError("backend citation authorization order is invalid")
        return authorized

    def _source_headers(self) -> dict[str, str]:
        return {
            "X-Deskseed-AI-Key-Id": self.settings.backend_source_key_id,
            "Authorization": f"Bearer {self.settings.backend_source_secret.get_secret_value()}",
        }

    def read_public_article(self, article_id: UUID, revision_id: UUID, request_ref: UUID, purpose: str = "INDEX"):
        headers = {
            "X-Deskseed-AI-Key-Id": self.settings.backend_index_key_id,
            "Authorization": f"Bearer {self.settings.backend_index_secret.get_secret_value()}",
            "X-Deskseed-AI-Index-Event-Id": str(request_ref),
        }
        with httpx.Client(base_url=self.settings.backend_base_url, timeout=15.0) as client:
            response = client.get(
                f"/api/v1/internal/ai/kb/articles/{article_id}/revisions/{revision_id}",
                params={"purpose": purpose},
                headers=headers,
            )
        if response.status_code != 200:
            raise BackendAuthorizationError(f"backend knowledge source rejected request with status {response.status_code}")
        return PublicKnowledgeArticle.model_validate(response.json())

    def read_public_manifest(
        self,
        snapshot_token: UUID | None = None,
        cursor: UUID | None = None,
        limit: int = 200,
    ) -> "KnowledgeManifestPage":
        params: dict[str, str | int] = {"limit": limit}
        if snapshot_token is not None:
            params["snapshotToken"] = str(snapshot_token)
        if cursor is not None:
            params["cursor"] = str(cursor)
        with httpx.Client(base_url=self.settings.backend_base_url, timeout=15.0) as client:
            response = client.get(
                "/api/v1/internal/ai/kb/manifest",
                params=params,
                headers=self._index_headers(),
            )
        if response.status_code == 409:
            raise BackendSupersededError("backend knowledge manifest snapshot expired")
        if response.status_code != 200:
            raise BackendAuthorizationError(f"backend knowledge manifest rejected request with status {response.status_code}")
        return KnowledgeManifestPage.model_validate(response.json())

    def _index_headers(self) -> dict[str, str]:
        return {
            "X-Deskseed-AI-Key-Id": self.settings.backend_index_key_id,
            "Authorization": f"Bearer {self.settings.backend_index_secret.get_secret_value()}",
        }


class PublicKnowledgeArticle(BaseModel):
    model_config = ConfigDict(extra="forbid")
    articleId: UUID
    revisionId: UUID
    slug: str
    title: str
    categoryTitle: str
    sectionTitle: str
    body: str
    sourceVersion: int
    publicRevision: str
    publishedAt: datetime
    dataClass: str


class KnowledgeManifestItem(BaseModel):
    model_config = ConfigDict(extra="forbid")
    articleId: UUID
    revisionId: UUID
    sourceVersion: int
    publicRevision: str
    publishedAt: datetime


class KnowledgeManifestPage(BaseModel):
    model_config = ConfigDict(extra="forbid")
    snapshotToken: UUID
    expiresAt: datetime
    canonicalPublicCorpusRevision: int | None = Field(default=None, ge=1)
    nextCursor: UUID | None
    items: list[KnowledgeManifestItem]


class ContextRevision(BaseModel):
    model_config = ConfigDict(extra="forbid")
    jobId: UUID
    requestRevision: int
    contextRevision: str
    aiInputRevision: Annotated[str | None, Field(pattern=r"^[0-9a-f]{64}$")] = None
    inputPolicyVersion: Literal["summary-input-v1", "triage-input-v1", "reply-input-v1"] | None = None
    authorized: bool
    cancelRequested: bool
    featureEnabled: bool

    @model_validator(mode="after")
    def input_revision_metadata_is_paired(self) -> "ContextRevision":
        if (self.aiInputRevision is None) != (self.inputPolicyVersion is None):
            raise ValueError("input revision metadata must be paired")
        return self


class AiPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid")
    enabled: bool
    features: dict[str, bool]
    fastModelAlias: str
    standardModelAlias: str
    replyRoutingMode: Literal["STANDARD_ONLY", "EVALUATED_COHORT"]
    replyRoutingCohorts: list[Literal["reply-single-public-article-short-v1"]] = Field(
        max_length=1
    )
    replyRoutingRolloutPercent: Literal[0, 10, 50, 100]
    replyRoutingEvaluationApprovalVersion: Annotated[
        str | None, Field(min_length=1, max_length=80, pattern=r"^[a-z0-9][a-z0-9._-]*$")
    ] = None
    version: int
    updatedAt: datetime
    dataAsOf: datetime
    canonicalPublicCorpusRevision: int | None = Field(default=None, ge=1)

    @model_validator(mode="after")
    def reply_routing_shape_is_consistent(self) -> "AiPolicy":
        if self.replyRoutingMode == "STANDARD_ONLY":
            if (
                self.replyRoutingCohorts
                or self.replyRoutingRolloutPercent != 0
                or self.replyRoutingEvaluationApprovalVersion is not None
            ):
                raise ValueError("STANDARD_ONLY reply routing policy is inconsistent")
        elif (
            self.replyRoutingCohorts != ["reply-single-public-article-short-v1"]
            or self.replyRoutingRolloutPercent not in {10, 50, 100}
            or self.replyRoutingEvaluationApprovalVersion is None
        ):
            raise ValueError("EVALUATED_COHORT reply routing policy is incomplete")
        return self


class AuthorizedKnowledgeCandidate(BaseModel):
    model_config = ConfigDict(extra="forbid")
    articleId: UUID
    revisionId: UUID
    chunkId: UUID
    title: str
    url: str


class AuthorizedKnowledgeResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")
    items: list[AuthorizedKnowledgeCandidate] = Field(max_length=8)
