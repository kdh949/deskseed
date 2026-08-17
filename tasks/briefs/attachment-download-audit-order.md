# Attachment Download Audit-before-open

Status: **IMPLEMENTATION_READY**

## Goal

필수 attachment download access audit이 실패하면 private object stream을 열거나 HTTP bytes를 반환하지 않는다.

## Decision and source references

- Decision IDs: D-018, D-037, D-040
- Accepted ADRs: ADR-0018, ADR-0026
- Requirements: REQ-AUD-008, REQ-FILE-001
- Existing API operations: attachment download operations 전체
- Verification gates: ACC-002, ACC-007, AUD-003, FILE-003, FILE-004, FILE-006

## Actor and source

- 기존 STAFF/CUSTOMER actor와 AGENT_WORKSPACE/CUSTOMER_PORTAL source를 유지한다.
- scope/resource constraint, PUBLIC/INTERNAL projection과 existence-safe 응답은 변경하지 않는다.
- required `ATTACHMENT_DOWNLOADED` access audit의 actor/auth/session semantics를 변경하지 않는다.

## In scope

- downloadable metadata/authorization 재검사 후 required audit을 먼저 commit한다.
- audit commit이 성공한 뒤에만 private object stream을 연다.
- audit failure에서 object-store open 호출이 0임을 증명하는 회귀 테스트를 추가한다.

## Out of scope

- access audit schema/action/outcome 변경
- object-store provider 또는 streaming controller 변경
- signed URL 도입
- OpenAPI나 database migration 변경

## Invariants and failure semantics

- guessed/unauthorized/unsafe attachment는 audit success나 object open 없이 기존 safe failure를 유지한다.
- required audit failure는 503이며 private stream을 열거나 반환하지 않는다.
- audit 성공 후 object store open이 실패하면 503이며 bytes는 반환하지 않는다.
- access audit metadata에는 file bytes, object key, checksum, raw session/cookie가 없다.

## Acceptance scenarios

1. Given downloadable CLEAN attachment와 정상 audit store, When download하면, Then audit commit 후 private stream을 반환한다.
2. Given audit writer insert failure, When download하면, Then 503이고 object store `openPrivate` 호출은 0이다.
3. Given 기존 customer/request-token/staff download flows, When focused integration suite를 실행하면, Then PUBLIC/INTERNAL 및 own-ticket 경계가 회귀하지 않는다.

## Validation

```bash
cd backend && ./gradlew test --tests '*AttachmentDownloadAuditOrderTest' --tests '*AttachmentPipelineIntegrationTest' --tests '*CustomerRequestPortalIntegrationTest' --tests '*PublicRequestIntegrationTest' --console=plain
cd backend && ./gradlew test --rerun-tasks --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_api_documentation_quality.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_documentation.py --write
```

## Compatibility and migration

- OpenAPI/migration/backfill: 없음
- 성공 응답 shape와 audit event shape: 변경 없음
- failure ordering만 frozen acceptance scenario와 일치하도록 수정한다.

## Human explanation

민감 파일 I/O는 필수 감사보다 먼저 시작할 수 없다. 별도 two-phase audit나 provider abstraction을 추가하지 않고 기존 transaction boundary의 호출 순서만 바로잡는 것이 현재 계약을 만족하는 최소 수정이다.
