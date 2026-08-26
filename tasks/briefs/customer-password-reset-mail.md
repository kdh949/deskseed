# Customer password reset mail foundation

## Goal

`PASSWORD_RESET` proof를 로그인·등록 확인 링크와 구분된 versioned protected mail로 durable outbox에 저장해,
후속 reset request가 SMTP network call 없이 한 transaction에서 enqueue할 수 있게 한다.

## Decision and source references

- Decisions: D-046, D-057
- Accepted ADRs: ADR 0029, ADR 0042
- Requirements: REQ-AUTH-003, REQ-NOTIF-001
- Plan: docs/49 outbound foundation, docs/56 Task 10
- Gates: AUTH-007, MAIL-001, MAIL-002, ARCH-004, OPS-001

## Actor/source and boundaries

- Future actor/source는 `SYSTEM`, `CUSTOMER_PORTAL`이다.
- 이 foundation slice는 HTTP surface, permission, account eligibility 결정을 추가하지 않는다.
- Raw reset link를 포함한 본문은 `PROTECTED`이며 ordinary outbox column에는 canonical placeholder만 남는다.
- Provider delivery는 기존 worker/retry/dead-letter 경로에서 transaction commit 뒤 수행한다.

## In scope

- `CUSTOMER_PASSWORD_RESET` template/version과 protected content type
- 전용 한국어 subject/body 및 existing absolute HTTP(S) URL safety 검증
- 30분 기본 reset proof TTL과 query/fragment 없는 browser route 설정
- V82 mail-template constraint 확장과 clean/upgrade migration tests
- renderer, secret-string, production configuration tests

## Out of scope

- token 발급/소비, recipient eligibility, reset request/consume HTTP endpoint
- password hashing/session revocation/security audit
- SMTP provider/retry, HTML template, frontend route

## Invariants and failure semantics

- Password reset mail은 magic login 또는 registration verification으로 분류·렌더링되지 않는다.
- Raw reset link는 content `toString`, ordinary `text_body`, audit, log에 나타나지 않는다.
- `OutboundMailPort.enqueue`는 caller transaction에 참여하며 encryption/insert 실패는 caller mutation과 함께 rollback한다.
- 기존 네 template row/key는 V82 upgrade 뒤 그대로 유효하다.

## Validation

- focused `CustomerAuthPropertiesTest`, `OutboundMailPolicyTest`
- focused `CustomerPasswordResetMailMigrationTest`
- `./gradlew --no-daemon fastTest contractTest migrationTest`
- `make docs-check`
- `git diff --check`

## Compatibility and rollback

- OpenAPI/UI는 변경하지 않는다.
- V82는 template allowlist만 확장하고 기존 row를 보존한다.
- Application rollback은 새 allowlisted value를 사용하지 않으면 된다. 적용된 Flyway history는 down migration/checksum edit 없이
  backup restore 또는 reviewed forward fix로 복구한다.

## Human explanation

- 독립 template key는 recovery proof를 normal login link로 오인해 잘못 재전송·분류하는 것을 막는다.
- 기존 protected outbox와 delivery worker를 재사용해 새 secret 저장소나 network boundary를 만들지 않는다.
- 이 schema/render slice에는 별도 performance benchmark가 필요하지 않다.
