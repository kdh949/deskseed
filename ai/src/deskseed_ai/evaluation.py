from __future__ import annotations

import base64
import hashlib
import json
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any, Literal
from uuid import UUID, uuid4

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

EvaluationFeature = Literal["ticket.summary", "ticket.triage", "ticket.reply_draft"]
EvaluationSplit = Literal["tune", "holdout"]
_FEATURES = {"ticket.summary", "ticket.triage", "ticket.reply_draft"}
_AAD_PREFIX = b"deskseed-evaluation-snapshot-v1:"


class EvaluationSnapshotError(ValueError):
    pass


@dataclass(frozen=True)
class EvaluationCase:
    case_id: str
    family_hash: str
    split: EvaluationSplit
    feature: EvaluationFeature
    source_revision: str
    source_identity: dict[str, str] = field(repr=False)
    public_input: dict[str, Any] = field(repr=False)
    labels: dict[str, Any] = field(repr=False)


@dataclass(frozen=True)
class EvaluationResource:
    resource_id: str
    source_revision: str
    source_identity: dict[str, str] = field(repr=False)
    public_content: dict[str, Any] = field(repr=False)


@dataclass(frozen=True)
class EvaluationSnapshot:
    snapshot_id: UUID
    created_at: datetime
    expires_at: datetime
    cases: tuple[EvaluationCase, ...] = field(repr=False)
    resources: tuple[EvaluationResource, ...] = field(repr=False)

    def active_cases(
        self,
        *,
        now: datetime | None = None,
        invalidated_case_ids: set[str] | None = None,
    ) -> tuple[EvaluationCase, ...]:
        current = now or datetime.now(UTC)
        if current >= self.expires_at:
            raise EvaluationSnapshotError("evaluation snapshot has expired")
        invalidated = invalidated_case_ids or set()
        return tuple(case for case in self.cases if case.case_id not in invalidated)


class EvaluationSnapshotCodec:
    def __init__(self, encoded_key: str):
        try:
            key = base64.b64decode(encoded_key, validate=True)
        except ValueError as exception:
            raise EvaluationSnapshotError("evaluation key is not valid base64") from exception
        if len(key) != 32:
            raise EvaluationSnapshotError("evaluation key must contain exactly 32 bytes")
        self._cipher = AESGCM(key)

    def seal(
        self,
        cases: list[EvaluationCase],
        *,
        resources: list[EvaluationResource] | None = None,
        ttl_days: int = 30,
        now: datetime | None = None,
        snapshot_id: UUID | None = None,
    ) -> bytes:
        if not 1 <= ttl_days <= 30:
            raise EvaluationSnapshotError("evaluation snapshot TTL must be between 1 and 30 days")
        if not cases:
            raise EvaluationSnapshotError("evaluation snapshot requires at least one case")
        case_ids = [case.case_id for case in cases]
        if len(case_ids) != len(set(case_ids)):
            raise EvaluationSnapshotError("evaluation case IDs must be unique")
        selected_resources = resources or []
        resource_ids = [resource.resource_id for resource in selected_resources]
        if len(resource_ids) != len(set(resource_ids)):
            raise EvaluationSnapshotError("evaluation resource IDs must be unique")
        created_at = (now or datetime.now(UTC)).astimezone(UTC)
        selected_id = snapshot_id or uuid4()
        payload = {
            "schemaVersion": 1,
            "snapshotId": str(selected_id),
            "createdAt": created_at.isoformat(),
            "expiresAt": (created_at + timedelta(days=ttl_days)).isoformat(),
            "cases": [_case_payload(case) for case in cases],
            "resources": [_resource_payload(resource) for resource in selected_resources],
        }
        plaintext = json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        nonce = __import__("os").urandom(12)
        ciphertext = self._cipher.encrypt(nonce, plaintext, _aad(selected_id))
        envelope = {
            "schemaVersion": 1,
            "snapshotId": str(selected_id),
            "nonce": base64.b64encode(nonce).decode("ascii"),
            "ciphertext": base64.b64encode(ciphertext).decode("ascii"),
        }
        return json.dumps(envelope, sort_keys=True, separators=(",", ":")).encode("ascii")

    def open(self, sealed: bytes, *, now: datetime | None = None) -> EvaluationSnapshot:
        try:
            envelope = json.loads(sealed)
            if envelope["schemaVersion"] != 1:
                raise EvaluationSnapshotError("unsupported evaluation snapshot envelope")
            snapshot_id = UUID(envelope["snapshotId"])
            nonce = base64.b64decode(envelope["nonce"], validate=True)
            ciphertext = base64.b64decode(envelope["ciphertext"], validate=True)
            plaintext = self._cipher.decrypt(nonce, ciphertext, _aad(snapshot_id))
            payload = json.loads(plaintext)
        except (InvalidTag, KeyError, TypeError, ValueError, json.JSONDecodeError) as exception:
            if isinstance(exception, EvaluationSnapshotError):
                raise
            raise EvaluationSnapshotError("evaluation snapshot authentication failed") from exception
        if payload.get("schemaVersion") != 1 or payload.get("snapshotId") != str(snapshot_id):
            raise EvaluationSnapshotError("evaluation snapshot identity is inconsistent")
        try:
            created_at = datetime.fromisoformat(payload["createdAt"]).astimezone(UTC)
            expires_at = datetime.fromisoformat(payload["expiresAt"]).astimezone(UTC)
            cases = tuple(_case_from_payload(item) for item in payload["cases"])
            resources = tuple(_resource_from_payload(item) for item in payload.get("resources", []))
        except (KeyError, TypeError, ValueError) as exception:
            raise EvaluationSnapshotError("evaluation snapshot payload is invalid") from exception
        if expires_at <= created_at or expires_at > created_at + timedelta(days=30):
            raise EvaluationSnapshotError("evaluation snapshot retention is invalid")
        snapshot = EvaluationSnapshot(snapshot_id, created_at, expires_at, cases, resources)
        snapshot.active_cases(now=now)
        return snapshot


def build_case(
    *,
    feature: EvaluationFeature,
    source_identity: dict[str, str],
    source_revision: str,
    family_key: str,
    public_input: dict[str, Any],
    labels: dict[str, Any] | None = None,
) -> EvaluationCase:
    if feature not in _FEATURES:
        raise EvaluationSnapshotError("unsupported evaluation feature")
    if not source_identity or not all(key and value for key, value in source_identity.items()):
        raise EvaluationSnapshotError("evaluation source identity must be non-empty")
    if not source_revision or not family_key or not public_input:
        raise EvaluationSnapshotError("evaluation source revision, family and PUBLIC input are required")
    canonical_identity = json.dumps(source_identity, sort_keys=True, separators=(",", ":"))
    case_id = hashlib.sha256(
        f"deskseed-eval-v1\0{feature}\0{canonical_identity}\0{source_revision}".encode("utf-8")
    ).hexdigest()
    family_hash = hashlib.sha256(f"deskseed-eval-family-v1\0{family_key}".encode("utf-8")).hexdigest()
    split: EvaluationSplit = "tune" if int(family_hash[:8], 16) % 10 < 7 else "holdout"
    return EvaluationCase(
        case_id=case_id,
        family_hash=family_hash,
        split=split,
        feature=feature,
        source_revision=source_revision,
        source_identity=dict(source_identity),
        public_input=dict(public_input),
        labels=dict(labels or {}),
    )


def build_resource(
    *,
    source_identity: dict[str, str],
    source_revision: str,
    public_content: dict[str, Any],
) -> EvaluationResource:
    if not source_identity or not all(key and value for key, value in source_identity.items()):
        raise EvaluationSnapshotError("evaluation resource identity must be non-empty")
    if not source_revision or not public_content:
        raise EvaluationSnapshotError("evaluation resource revision and PUBLIC content are required")
    canonical_identity = json.dumps(source_identity, sort_keys=True, separators=(",", ":"))
    resource_id = hashlib.sha256(
        f"deskseed-eval-resource-v1\0{canonical_identity}\0{source_revision}".encode("utf-8")
    ).hexdigest()
    return EvaluationResource(
        resource_id=resource_id,
        source_revision=source_revision,
        source_identity=dict(source_identity),
        public_content=dict(public_content),
    )


def _aad(snapshot_id: UUID) -> bytes:
    return _AAD_PREFIX + snapshot_id.hex.encode("ascii")


def _case_payload(case: EvaluationCase) -> dict[str, Any]:
    return {
        "caseId": case.case_id,
        "familyHash": case.family_hash,
        "split": case.split,
        "feature": case.feature,
        "sourceRevision": case.source_revision,
        "sourceIdentity": case.source_identity,
        "publicInput": case.public_input,
        "labels": case.labels,
    }


def _case_from_payload(payload: dict[str, Any]) -> EvaluationCase:
    case = EvaluationCase(
        case_id=payload["caseId"],
        family_hash=payload["familyHash"],
        split=payload["split"],
        feature=payload["feature"],
        source_revision=payload["sourceRevision"],
        source_identity=payload["sourceIdentity"],
        public_input=payload["publicInput"],
        labels=payload["labels"],
    )
    if (
        len(case.case_id) != 64
        or len(case.family_hash) != 64
        or case.split not in {"tune", "holdout"}
        or case.feature not in _FEATURES
        or not case.source_identity
        or not case.public_input
    ):
        raise EvaluationSnapshotError("evaluation case payload is invalid")
    return case


def _resource_payload(resource: EvaluationResource) -> dict[str, Any]:
    return {
        "resourceId": resource.resource_id,
        "sourceRevision": resource.source_revision,
        "sourceIdentity": resource.source_identity,
        "publicContent": resource.public_content,
    }


def _resource_from_payload(payload: dict[str, Any]) -> EvaluationResource:
    resource = EvaluationResource(
        resource_id=payload["resourceId"],
        source_revision=payload["sourceRevision"],
        source_identity=payload["sourceIdentity"],
        public_content=payload["publicContent"],
    )
    if (
        len(resource.resource_id) != 64
        or not resource.source_revision
        or not resource.source_identity
        or not resource.public_content
    ):
        raise EvaluationSnapshotError("evaluation resource payload is invalid")
    return resource
