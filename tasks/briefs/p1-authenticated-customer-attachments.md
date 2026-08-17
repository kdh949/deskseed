# P1 Authenticated Customer Follow-up Attachments

Status: **IMPLEMENTATION_READY**

Parallel coordination: `tasks/briefs/p1-parallel-followup-coordination.md`

## Goal

인증 고객이 자신의 문의 follow-up에 최대 5개의 PUBLIC attachment를 quarantine upload하고 CLEAN 결과만 댓글에 연결하며, 공개 대화에 연결된 CLEAN attachment만 감사 가능한 방식으로 다운로드한다.

## Assumptions fixed by this brief

1. 기존 `AddAuthenticatedCustomerComment.attachmentIds`가 comment link 계약이다. 별도 comment endpoint를 만들지 않는다.
2. 기존 private object storage, synchronous quarantine/MIME/malware scan, CLEAN handle pipeline을 재사용한다.
3. authenticated customer surface와 request-token surface는 auth adapter가 다르므로 별도 path/operationId를 유지한다.
4. customer는 어떤 경우에도 INTERNAL attachment를 upload/link/download할 수 없다.
5. linked attachment delete/redaction과 asynchronous scan UI는 별도 계약이며 이 slice에 포함하지 않는다.

## Decision and source references

- Decision IDs: D-006, D-018, D-037, D-040, D-051, D-053, D-054
- Accepted ADRs: 0005, 0006, 0018, 0026, 0029, 0037
- Requirements: REQ-TKT-003, REQ-TKT-005, REQ-TKT-008, REQ-FILE-001
- Surface/route: Customer request/account portal, `/account/requests/:ticketNumber`
- Existing operationId: `addAuthenticatedCustomerComment`, `getCustomerRequest`
- New operationIds: `createAuthenticatedCustomerAttachmentUpload`, `downloadAuthenticatedCustomerAttachment`
- Gates: ARCH-001/002/004, ACC-007, AUD-003, CONC-001, IDEM-001, FILE-001/003/004/006, RET-001, frontend Storybook/axe/keyboard gates

## Actor and source

- Actor: verified `CUSTOMER` from server customer session
- Source: `CUSTOMER_PORTAL`
- Resource constraint: `tickets.requester_id == principal.customerId`, `kind == CUSTOMER_REQUEST`, path ticket number match
- Upload/link visibility: `PUBLIC` only
- POST authentication: `customerSession` + customer CSRF
- GET authentication: `customerSession`
- Download access audit: `ATTACHMENT_DOWNLOADED`, auth type `CUSTOMER_SESSION`, purpose-bound non-secret session fingerprint
- Failure of required download audit persistence: no bytes returned

## Frozen API contract

### Upload

```text
POST /api/v1/customer/requests/{ticketNumber}/attachments/uploads
operationId: createAuthenticatedCustomerAttachmentUpload
security: customerSession
header: X-CSRF-TOKEN required
content-type: multipart/form-data
schema: AttachmentUploadForm
201: AttachmentUpload + Cache-Control: no-store
400/401/403/404/413/415/422/429/503: RFC 9457 Problem
```

의미:

- server가 session customer의 own ticket을 찾은 뒤 upload handle을 그 ticket ID, CUSTOMER actor, PUBLIC visibility에 묶는다.
- bounded private quarantine, checksum, detected MIME, malware scan을 통과한 CLEAN handle만 201로 반환한다.
- object key, checksum, scan detail, public/signed URL, bytes를 JSON에 넣지 않는다.
- CSRF 실패는 403, 다른 고객/없는 ticket은 existence-safe 404다.

### Download

```text
GET /api/v1/customer/requests/{ticketNumber}/attachments/{attachmentId}/download
operationId: downloadAuthenticatedCustomerAttachment
security: customerSession
200: application/octet-stream + Content-Disposition + Cache-Control: no-store
400/401/404/409/503: RFC 9457 Problem
```

의미:

- session customer가 소유한 path ticket의 PUBLIC comment에 연결된 CLEAN, non-expired attachment만 stream한다.
- guessed ID, 다른 ticket, INTERNAL, unlinked, quarantined, infected, failed, deleted, expired object는 동일한 not-found-safe 응답으로 처리한다.
- stream을 반환하기 전에 required access audit을 commit한다.

### Supporting contract changes

- customer mutation용 `CustomerCsrfHeader` parameter를 추가한다. staff `CsrfHeader` 설명을 재사용하거나 왜곡하지 않는다.
- `AddAuthenticatedCustomerComment` example에 `attachmentIds`를 포함한다.
- `CustomerRequestDetail`/`PublicComment`의 기존 `attachments: TicketAttachment[]` 계약을 frontend authenticated decoder에도 적용한다.
- new operations는 `x-deskseed-contract-status: FROZEN`, P1 requirements, human-owned Korean purpose/examples/errors/security를 가진다.

### Compatibility

- 두 path는 additive다.
- 기존 request-token attachment operations와 anonymous headers는 바뀌지 않는다.
- 기존 text-only authenticated follow-up request도 attachmentIds 생략으로 계속 동작한다.
- migration은 예상하지 않는다. 현재 access audit `auth_type`은 bounded varchar이며 DB enum/check 확장이 필요하지 않다.

## Backend design

### Customer session audit identity

`AccessAuditAuthType`에 `CUSTOMER_SESSION`을 추가한다. request-token은 계속 `CUSTOMER_CAPABILITY`다.

Customer session fingerprint는 raw cookie/token을 저장하지 않는 purpose-bound HMAC이어야 한다.

권장 implementation:

- `customer_sessions.id`를 session resolution query에서 함께 읽는다.
- customer auth module 내부에서 `fingerprintKey`와 `customer-session:<sessionId>`를 HMAC한다.
- `CustomerPrincipal` root API에 authenticated session에서만 채워지는 fingerprint를 제공한다.
- controller는 cookie 원문이나 `customerauth.internal` helper를 import하지 않는다.
- download audit validator는 CUSTOMER actor + CUSTOMER_PORTAL + CUSTOMER_SESSION + nonblank fingerprint를 요구한다.

### Own-ticket authorization API

`CustomerTicketPortal` root API에 다음과 동등한 named method를 추가한다.

```kotlin
fun findOwnedTicketId(requesterId: UUID, ticketNumber: Long): UUID?
```

query는 requester, ticket number, `CUSTOMER_REQUEST` kind를 함께 확인한다. controller가 attachment ID를 먼저 조회하여 존재 oracle을 만들지 않는다.

### Portal application service

두 use case를 추가한다.

1. `uploadAttachment(principal, ticketNumber, file metadata/content, context)`
2. `downloadAttachment(principal, ticketNumber, attachmentId, access context)`

Upload 순서:

```text
session principal
→ own ticket ID resolve
→ bounded stream outside ticket mutation transaction
→ AttachmentUploadCommand(CUSTOMER, boundTicketId, PUBLIC)
→ CLEAN handle response
```

Comment link transaction은 existing `addFollowUp`을 사용한다. 이 transaction이 requester ownership, actor, bound ticket, PUBLIC visibility, CLEAN/unlinked/unexpired 상태를 다시 검사하므로 upload 이후 ownership/state 변화에 fail closed한다.

Download 순서:

```text
session principal
→ own path ticket ID resolve
→ AttachmentDownloadCommand(ticketId, ticketNumber, PUBLIC only)
→ CLEAN/link/expiry recheck
→ required CUSTOMER_SESSION access audit commit
→ private stream response
```

Controller는 HTTP multipart/binary translation과 safe `Content-Disposition`만 소유한다.

### Concurrency and idempotency

- upload handle은 one-time link다. 두 comment가 같은 ID를 경쟁하면 하나만 link된다.
- authenticated/anonymous follow-up은 ticket row → advisory command lock → replay lookup → mutation 순서를 공유한다.
- `(requesterId, clientCommandId)` exact replay는 원 comment와 attachments를 반환하고 두 번째 link/audit/mail intent를 만들지 않는다.
- same ID를 다른 body/ticket/attachment set으로 재사용하면 409다.
- network/5xx ambiguous failure에서 frontend는 같은 clientCommandId와 attachment IDs를 유지한다.

### Transaction and external I/O

- object store/scanner I/O는 ticket/comment transaction 밖에서 실행한다.
- comment, attachment link, TicketAudit, ordered events, mail intent는 기존 transaction 경계를 유지한다.
- download audit 실패나 object store failure는 성공 stream을 반환하지 않는다.

## Frontend Reuse Plan

UI write 전에 Storybook MCP documentation discovery/instructions/component documentation을 실행한다.

- Reuse: `CustomerRequestConversation`, `CustomerFollowUpForm`, `AttachmentUploadField`, `AttachmentList`, existing notification/screen states
- Compose: authenticated customer client callbacks를 existing optional upload/download props에 연결
- Extend: 없음이 기본. 필요한 상태가 기존 attachment component에 실제로 없을 때만 canonical design-system API를 검토
- Add: 없음

Garden을 feature code에서 직접 import하지 않는다.

## Frontend client design

`frontend/src/features/customer-portal/api/customerPortalClient.ts`에 다음을 추가한다.

```ts
uploadAuthenticatedCustomerAttachment(ticketNumber, file)
downloadAuthenticatedCustomerAttachment(ticketNumber, attachmentId)
addCustomerFollowUp(ticketNumber, body, clientCommandId, attachmentIds)
```

- upload 전에 `/api/v1/customer/csrf`를 조회한다.
- `FormData` 사용 시 browser boundary가 설정되도록 `Content-Type`을 직접 넣지 않는다.
- 모든 request는 `credentials: 'include'`, `cache: 'no-store'`, `referrerPolicy: 'no-referrer'`를 유지한다.
- download는 server `Content-Disposition`/`Content-Type`을 검증하고 Blob을 반환한다.
- customer detail/comment decoder가 `TicketAttachment[]` metadata를 읽는다.
- INTERNAL visibility field나 object locator가 response에 나타나면 정상 contract로 사용하지 않는다.
- parallel conflict를 줄이기 위해 central `frontend/src/api/client.ts` attachment decoder refactor는 이 slice에서 하지 않는다. 동일 shared type shape를 contract tests로 맞춘다.

## Frontend behavior

- `CustomerRequestDetailPage`가 existing conversation에 upload/download callbacks와 attachment IDs를 받는 submit callback을 전달한다.
- pending/rejected upload가 있으면 submit disabled이며 text + screen-reader 상태를 제공한다.
- CLEAN handle만 submit에 포함한다.
- upload 중 route unload warning을 유지한다.
- definite validation/403/404/409에서는 command identity를 rotate하되 draft recovery contract를 따른다.
- ambiguous network/5xx에서는 body, attachment IDs, command identity를 유지한다.
- 다운로드 실패는 request ID와 denied/expired-safe message를 표시하고 새 탭/public URL을 만들지 않는다.
- authenticated customer DOM에는 PUBLIC comments/attachments만 존재한다.

## States

- 선택/업로드 진행/검사 중/CLEAN
- 감염·격리/검사 실패/MIME·크기 거부
- unlinked handle 제거
- 만료/삭제/다운로드 거부
- session expired/denied
- follow-up validation/conflict/ambiguous failure
- loading/empty/error/not-found customer detail

Backend가 현재 synchronous scan 후 CLEAN 또는 problem만 반환하더라도 UI component의 검사/실패 상태 contract는 유지한다. fixture를 production 화면에 import하지 않는다.

## Tasks

### Task B1 — OpenAPI and audit identity

**Acceptance:** two FROZEN operations, customer CSRF parameter, CUSTOMER_SESSION audit semantics가 계약과 코드에 존재한다.

**Verify:** documentation quality/OpenAPI parse/runtime drift, access audit unit/integration tests.

### Task B2 — Session fingerprint and own-ticket authorization

**Acceptance:** raw cookie 없이 stable fingerprint가 authenticated principal에 전달되고 own-ticket lookup이 existence-safe하다.

**Verify:** customer session tests, customer A/B isolation integration tests, module verification.

### Task B3 — Upload/download HTTP vertical slice

**Acceptance:** own-ticket PUBLIC upload/download가 existing pipeline을 사용하고 all unsafe/unauthorized states가 fail closed한다.

**Verify:** `AttachmentPipelineIntegrationTest`, `CustomerRequestPortalIntegrationTest`.

### Task B4 — Authenticated frontend wiring

**Acceptance:** existing customer composer/timeline이 upload, CLEAN gating, attachmentIds submit, metadata download를 지원한다.

**Verify:** customer client/page/form/conversation tests, Storybook interaction/axe.

### Task B5 — Real-stack and full gates

**Acceptance:** customer login → own request → upload → follow-up → reload → download가 real stack에서 동작하고 another customer/INTERNAL denial이 증명된다.

**Verify:** customer Playwright real-stack plus repository gates.

## Acceptance scenarios

1. **Own-ticket upload/link** — Given 로그인 고객이 자신의 OPEN 문의를 보고 있을 때, When CLEAN file을 upload하고 follow-up을 보내면, Then PUBLIC comment 하나에 attachment가 한 번 연결된다.
2. **Refresh/download** — Given linked PUBLIC attachment가 있을 때, When page reload 후 다운로드하면, Then safe filename binary와 no-store response를 받고 `ATTACHMENT_DOWNLOADED` audit이 CUSTOMER_SESSION으로 남는다.
3. **Customer isolation** — Given customer B가 customer A의 ticket number/attachment ID를 알 때, When upload 또는 download하면, Then 404이며 bytes/metadata가 노출되지 않는다.
4. **CSRF** — Given customer session cookie만 있고 CSRF header가 없을 때, When upload POST를 보내면, Then 403이고 quarantine metadata/object가 생성되지 않는다.
5. **PUBLIC-only** — Given INTERNAL attachment ID를 추측하거나 복사했을 때, Then authenticated customer download는 404다.
6. **Unsafe state** — Given quarantined/infected/failed/deleted/expired/unlinked attachment이면, Then download bytes가 반환되지 않는다.
7. **Cross-ticket link** — Given 같은 고객의 ticket A에 bound된 upload를 ticket B follow-up에 넣으면, Then command는 실패하고 comment/audit/mail/link가 생성되지 않는다.
8. **Replay** — Given follow-up success response가 유실됐을 때, When same requester/clientCommandId/body/attachmentIds를 재전송하면, Then original comment를 반환하고 attachment/link/audit/mail을 중복 생성하지 않는다.
9. **Conflicting replay** — Given 같은 clientCommandId에 다른 attachment set을 보내면, Then 409이고 mutation이 없다.
10. **Audit failure** — Given access audit insert가 실패할 때, When download하면, Then 503이고 stream은 열리거나 반환되지 않는다.
11. **Raw token privacy** — Given upload/download가 성공해도, Then session cookie/CSRF/object key/checksum은 log, audit metadata, JSON response에 존재하지 않는다.

## Validation commands

```bash
cd backend && ./gradlew test --tests '*AttachmentPipelineIntegrationTest' --tests '*CustomerRequestPortalIntegrationTest' --tests '*PublicRequestIntegrationTest'
cd backend && ./gradlew test
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_api_documentation_quality.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_documentation.py --write
cd frontend && npm run typecheck
cd frontend && npm run lint
cd frontend && npm run format:check
cd frontend && npm run test
cd frontend && npm run test:storybook
cd frontend && npm run check:design-system-boundaries
cd frontend && npm run build
cd frontend && npm run test:e2e
bash scripts/run-p1-contract-e2e.sh
```

추가로 Storybook MCP focused/full `run-story-tests`, changed story preview, authenticated customer real-stack Playwright를 실행한다.

## Commit sequence

```text
docs: 인증 고객 첨부 계약 동결

feat: 인증 고객 첨부 권한과 감사 연결 추가

feat: 고객 후속 답변 첨부 UX 연결

test: 인증 고객 첨부 보안 회귀 검증 추가
```

## Completion report requirements

- changed route/component
- new operationIds와 reused schemas
- actor/source/resource constraint와 CUSTOMER_SESSION audit
- transaction/external I/O/idempotency behavior
- Storybook stories와 preview URLs
- exact commands와 Passed/Failed/Not run
- migration 없음의 근거
- sibling Saved View PR 및 `PARALLEL_BASE_SHA`
- deletion/rich-text non-goal과 P0 anonymous attachment regression 여부
