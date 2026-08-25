# Execution Prompt — P1 Authenticated Customer Attachments

아래 프롬프트를 새 Codex 작업에 그대로 전달한다. 시작 전에 `<PARALLEL_BASE_SHA>`를 coordination owner가 확정한 동일한 full SHA로 치환한다.

```text
당신은 Deskseed의 Staff-level 풀스택 엔지니어다.

목표는 로그인한 고객이 자신의 문의 follow-up에 PUBLIC attachment를 upload/link/download하는 흐름을 기존 private attachment pipeline과 customer portal UI에 연결하는 것이다. 기존 익명 request-token 첨부 API나 P0 화면을 대체하지 않는다.

PARALLEL_BASE_SHA=<PARALLEL_BASE_SHA>
SIBLING_WORKSTREAM=feature/p1-saved-view-metadata
BRANCH=feature/p1-authenticated-customer-attachments

이 작업은 Saved View 메타데이터 작업과 동시에 진행된다. 두 작업은 같은 PARALLEL_BASE_SHA에서 별도 worktree/branch로 시작해야 한다. sibling branch를 merge/cherry-pick하거나 그 worktree를 수정하지 않는다.

필수 절차:

1. 루트 AGENTS.md, frontend/AGENTS.md를 읽고 모두 따른다.
2. tasks/briefs/p1-parallel-followup-coordination.md와 tasks/briefs/p1-authenticated-customer-attachments.md를 정본 implementation brief로 읽는다.
3. CODEX_TASK_TEMPLATE.md, DESIGN_SYSTEM_MANIFEST.md, docs/21, docs/25~34, docs/39, docs/40, docs/48, docs/50, docs/51, docs/55와 Accepted ADR 0005/0006/0018/0026/0029/0037을 읽는다.
4. api/core-api-outline-v1.yaml의 FROZEN 계약을 먼저 수정한다. 이름이나 의미를 brief와 다르게 바꿔야 할 근거가 있으면 구현을 멈추고 blocker를 보고한다.
5. git fetch 후 PARALLEL_BASE_SHA가 존재하고 PR #70 head 814fee56247444a8ac10b1a726703dd60cfa0864를 포함하는지 확인한다. 같은 SHA에서 BRANCH와 전용 worktree를 만든다. primary/다른 worktree의 dirty change를 건드리지 않는다.
6. UI write 전에 frontend/에서 Storybook MCP list-all-documentation을 한 번 호출한다. component/story/rendered UI 변경 전에 get-storybook-story-instructions를 호출한다. 사용하는 각 design-system component documentation ID에 get-documentation을 호출한다. MCP가 없으면 contract를 추측하지 말고 gap을 보고한다.
7. 실패·보안 회귀 테스트를 먼저 또는 구현과 함께 작성한다.

고정 계약:

- POST /api/v1/customer/requests/{ticketNumber}/attachments/uploads
  operationId createAuthenticatedCustomerAttachmentUpload
- GET /api/v1/customer/requests/{ticketNumber}/attachments/{attachmentId}/download
  operationId downloadAuthenticatedCustomerAttachment
- POST는 customerSession + customer CSRF, GET은 customerSession이다.
- AddAuthenticatedCustomerComment.attachmentIds, AttachmentUploadForm, AttachmentUpload, TicketAttachment과 기존 object-store/scanner pipeline을 재사용한다.
- customer upload/link/download visibility는 PUBLIC only다.
- attachment download audit auth type은 CUSTOMER_SESSION이며 purpose-bound session fingerprint를 사용한다.
- other customer/other ticket/INTERNAL/unlinked/quarantined/infected/failed/deleted/expired는 not-found-safe하게 bytes를 차단한다.
- audit persistence failure는 success stream을 반환하지 않는다.
- migration은 예상하지 않는다. 필요성이 생기면 번호를 임의 선택하지 말고 blocker로 coordinator에게 보고한다.
- attachment delete/redaction/rich text/asynchronous scanner 재설계는 구현하지 않는다.

구현 순서:

A. OpenAPI/docs/traceability와 customer CSRF parameter
B. CUSTOMER_SESSION audit type + raw-token-free customer session fingerprint
C. CustomerTicketPortal own-ticket locator
D. portal application/controller upload/download adapter
E. ownership/PUBLIC/CLEAN/audit failure/concurrency integration tests
F. customerPortalClient attachment metadata/upload/download/attachmentIds
G. CustomerRequestDetailPage가 existing CustomerRequestConversation/CustomerFollowUpForm/AttachmentUploadField/AttachmentList에 callbacks 연결
H. Storybook interaction/axe, Playwright, real-stack, full gates

병렬 파일 규칙:

- tasks/briefs/p1-parallel-followup-coordination.md의 Workstream B ownership만 수정한다.
- api/core-api-outline-v1.yaml, docs/26, docs/55에서는 authenticated attachment section만 수정한다.
- saved-view backend/frontend 파일과 V34는 수정하지 않는다.
- central frontend api/client.ts의 attachment decoder를 refactor하지 않는다. customer portal client에서 같은 shared type shape를 strict decode하고 contract tests로 일치시킨다.
- baseline frontend e2e Prettier failure를 feature commit에 섞지 않는다. 최신 base에서 이미 고쳐졌는지 확인하고 미해결이면 별도 baseline failure로 보고한다.

보안/transaction 요구:

- attachment ID를 먼저 조회해 existence oracle을 만들지 말고 requesterId + path ticketNumber로 own ticket ID를 먼저 resolve한다.
- controller에서 customer session cookie 원문이나 customerauth.internal을 import하지 않는다.
- object store/scanner I/O는 ticket/comment transaction 밖이다.
- comment/link/TicketAudit/ordered events/mail intent는 기존 transaction을 유지한다.
- anonymous/authenticated follow-up의 ticket row → advisory lock → replay lookup → mutation 순서를 보존하고 cross-path concurrent replay를 테스트한다.
- raw session/CSRF/object key/checksum/bytes/comment body를 ordinary log/audit metadata에 저장하지 않는다.

커밋은 다음 논리 단위와 한국어 형식을 사용한다.

docs: 인증 고객 첨부 계약 동결
feat: 인증 고객 첨부 권한과 감사 연결 추가
feat: 고객 후속 답변 첨부 UX 연결
test: 인증 고객 첨부 보안 회귀 검증 추가

explicit path만 stage하고 각 commit 전에 staged diff와 secret/token 문자열을 점검한다. 공개된 shared branch를 rebase/force-push하지 않는다.

검증:

- brief의 focused backend tests
- ./gradlew test
- documentation quality/validation과 generated files clean check
- npm run typecheck
- npm run lint
- npm run format:check
- npm run test
- npm run test:storybook
- npm run check:design-system-boundaries
- npm run build
- npm run test:e2e
- Storybook MCP focused/full run-story-tests, changed stories preview
- authenticated customer real-stack Playwright와 bash scripts/run-p1-contract-e2e.sh

완료되면 branch를 push하고 Draft PR을 연다. 공통 docs branch가 아직 main에 없으면 그 branch를 base로 열고, 포함된 뒤 main으로 retarget한다. 자동 merge하지 않는다. PR body에는 REQ-FILE-001/REQ-TKT-003/005/008, gate IDs, PARALLEL_BASE_SHA, sibling branch/PR, operationIds, actor/source/resource constraint, audit/failure/idempotency, tests Passed/Failed/Not run, Storybook preview URLs, migration 없음, non-goals와 익명 P0 회귀 여부를 표로 적는다.

문서/계약에 없는 endpoint, field, mock success를 발명하지 않는다. required gate가 실패하면 원인을 숨기지 말고 merge-ready를 주장하지 않는다.
```
