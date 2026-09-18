from __future__ import annotations

import base64
import os
from datetime import UTC, datetime, timedelta

import pytest

from deskseed_ai.evaluation import EvaluationSnapshotCodec, EvaluationSnapshotError, build_case, build_resource


def key() -> str:
    return base64.b64encode(os.urandom(32)).decode("ascii")


def case(source: str, family: str, body: str = "승인된 PUBLIC 원문 sentinel"):
    return build_case(
        feature="ticket.reply_draft",
        source_identity={"ticketId": source},
        source_revision="a" * 64,
        family_key=family,
        public_input={"comments": [{"role": "CUSTOMER", "body": body}]},
        labels={"expectedAbstention": False},
    )


def test_snapshot_is_authenticated_body_encrypted_and_round_trips() -> None:
    codec = EvaluationSnapshotCodec(key())
    created_at = datetime(2026, 9, 19, tzinfo=UTC)
    selected = case("ticket-1", "refund")
    resource = build_resource(
        source_identity={"articleId": "article-1"},
        source_revision="b" * 64,
        public_content={"title": "공개 환불 문서", "body": "공개 KB 원문 sentinel"},
    )

    sealed = codec.seal([selected], resources=[resource], ttl_days=30, now=created_at)

    assert "승인된 PUBLIC 원문 sentinel".encode() not in sealed
    assert "공개 KB 원문 sentinel".encode() not in sealed
    opened = codec.open(sealed, now=created_at + timedelta(days=1))
    assert opened.expires_at == created_at + timedelta(days=30)
    assert opened.cases[0].public_input == selected.public_input
    assert opened.resources[0].public_content == resource.public_content
    assert "PUBLIC 원문" not in repr(opened)
    assert "PUBLIC 원문" not in repr(opened.cases[0])
    assert "KB 원문" not in repr(opened.resources[0])


def test_family_split_never_crosses_tune_and_holdout() -> None:
    first = case("ticket-1", "same-problem")
    second = case("ticket-2", "same-problem")
    different = case("ticket-3", "different-problem")

    assert first.family_hash == second.family_hash
    assert first.split == second.split
    assert len({first.case_id, second.case_id, different.case_id}) == 3


def test_tamper_wrong_key_and_expiry_fail_closed() -> None:
    created_at = datetime(2026, 9, 19, tzinfo=UTC)
    codec = EvaluationSnapshotCodec(key())
    sealed = codec.seal([case("ticket-1", "refund")], ttl_days=7, now=created_at)

    tampered = bytearray(sealed)
    tampered[-10] = ord("A") if tampered[-10] != ord("A") else ord("B")
    with pytest.raises(EvaluationSnapshotError):
        codec.open(bytes(tampered), now=created_at)
    with pytest.raises(EvaluationSnapshotError):
        EvaluationSnapshotCodec(key()).open(sealed, now=created_at)
    with pytest.raises(EvaluationSnapshotError, match="expired"):
        codec.open(sealed, now=created_at + timedelta(days=7))


def test_ttl_is_capped_and_invalidation_removes_case_before_model_use() -> None:
    codec = EvaluationSnapshotCodec(key())
    first = case("ticket-1", "refund")
    second = case("ticket-2", "login")
    with pytest.raises(EvaluationSnapshotError, match="TTL"):
        codec.seal([first], ttl_days=31)

    now = datetime(2026, 9, 19, tzinfo=UTC)
    snapshot = codec.open(codec.seal([first, second], now=now), now=now)
    active = snapshot.active_cases(now=now, invalidated_case_ids={first.case_id})
    assert [item.case_id for item in active] == [second.case_id]


def test_duplicate_case_identity_is_rejected() -> None:
    selected = case("ticket-1", "refund")
    with pytest.raises(EvaluationSnapshotError, match="unique"):
        EvaluationSnapshotCodec(key()).seal([selected, selected])
