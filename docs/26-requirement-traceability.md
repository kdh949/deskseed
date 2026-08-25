# 요구사항 추적 매트릭스 (Requirement Traceability Matrix)

## 1. 목적

이 문서는 대화에서 확정된 제품 요구사항이 설계 문서, 구현 단계, 검증 기준 중 어디에 반영되어 있는지 추적한다. 기능을 추가하거나 범위를 바꿀 때 이 표를 먼저 수정한다.

상태 정의:

- `IMPLEMENTATION_READY`: 현재 문서만으로 첫 구현을 시작할 수 있다.
- `BLUEPRINT_READY`: 장기 구조와 경계는 정해졌지만, 구현 직전에 정책값이나 API 계약을 동결해야 한다.
- `PROVISIONAL`: 운영·법률·보안 정책의 최종 결정이 필요하다.
- `DEFERRED`: 의도적으로 뒤 단계에 배치했다.

ADR 0039 이후 이 상태는 주로 서버/도메인 계약의 구현 준비도를 뜻한다. 현재 React route 제공 여부는 별도이며, Customer/Admin/Audit/Search/Integration/SLA 및 아직 재조합되지 않은 화면은 `docs/55-frontend-capability-recomposition-matrix.md`에서 `DEFERRED_UI`로 추적한다. 이미 완료된 서버 요구사항을 UI가 없다는 이유로 미완료로 되돌리지 않는다.

## 2. 제품·배포·기술 요구사항

| ID | 요구사항 | 상태 | 구현 단계 | 기준 문서 | 완료 증거 |
|---|---|---:|---|---|---|
| REQ-PROD-001 | 한 설치 인스턴스가 한 조직을 위한 self-hosted 서비스여야 한다 | IMPLEMENTATION_READY | M0 | 00, 03, 36 | Docker Compose 설치와 신규 인스턴스 부팅 |
| REQ-PROD-002 | Zendesk와 유사한 고객지원 행동 모델을 갖는다 | IMPLEMENTATION_READY | 전 단계 | 00, 01, 02, 30 | E2E 업무 시나리오 |
| REQ-TECH-001 | Kotlin/Spring/PostgreSQL 기반으로 시작한다 | IMPLEMENTATION_READY | M0 | 03, 22, 27 | 빌드·테스트 통과 |
| REQ-TECH-002 | 모듈러 모놀리스로 시작하고 필요 시 이벤트·Kafka로 진화한다 | IMPLEMENTATION_READY | M0→P9 | 03, 34, 38 | Modulith 검증, 도입 ADR |
| REQ-TECH-003 | React/TypeScript/Vite 프론트엔드를 사용한다 | IMPLEMENTATION_READY | M0 | 22, 28, 29 | 프론트 빌드·E2E |
| REQ-TECH-004 | 커밋된 OpenAPI 계약을 사람이 검토한 한국어 도메인 설명·합성 예시와 함께 탐색 가능한 API Reference로 제공한다 | IMPLEMENTATION_READY | M0 | 21, 22, 39, D-054 | `ApiDocumentationIntegrationTest`, `DOC-001`, `make docs-check` |
| REQ-PORT-001 | 먼저 작동하는 포트폴리오를 만들고 이후 성능·Kafka까지 깊게 확장한다 | IMPLEMENTATION_READY | 전체 | 05, 11, 27, 41 | 릴리스별 증거 문서 |

## 2.0 Wave 1 knowledge base

| ID | 요구사항 | 상태 | 구현 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-KB-001 | 고객·상담사·관리자가 Category→Section→Article과 immutable revision lifecycle을 audience에 맞게 사용한다 | IN_PROGRESS | Wave 1 | Goal 03, 02, 03, 25, 32, 33, 34, ADR 0013, 0018, 0040 | hierarchy/FK/order/slug·revision/publish conflict·public/agent/admin lifecycle PostgreSQL tests |
| REQ-KB-002 | 상담사는 PUBLIC·INTERNAL·selected group 문서를 권한으로 검색·열람하고 결과 열람은 required access audit 뒤에 반환한다 | IN_PROGRESS | Wave 1 | Goal 03, 19, 33, 34, ADR 0018 | audience matrix, restricted detail/search audit failure injection, no count/snippet leakage tests |
| REQ-KB-003 | PostgreSQL FTS/GIN/trigram search는 hidden 문서가 rank/count/excerpt에 영향을 주지 않고 stable cursor·corpus·latency evidence를 제공한다 | IN_PROGRESS | Wave 1 | Goal 03, 08, 25, 32, ADR 0008, 0025 | Korean/English/identifier corpus, permission boundary, cursor, rebuild and p95 evidence |
| REQ-KB-004 | canonical Deskseed block document와 safe renderer는 XSS를 차단하고 publish/cache revision 일관성을 보장한다 | IN_PROGRESS | Wave 1 | Goal 03, 23, 39, 40, ADR 0018 | unsafe URL/HTML/unknown block rejection, canonical adapter round-trip, ETag 304/publish and audience cache tests |

## 2.1 인증·초기 상담원 가시성

| ID | 요구사항 | 상태 | 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-AUTH-001 | 고객 passwordless 인증은 DB-backed purpose-bound single-use email magic link를 사용한다 | IMPLEMENTATION_READY | P1 | 37, 49, 53, ADR 0029/0042 | `CustomerMagicLinkAuthIntegrationTest`의 expiry/replay/race/enumeration/session/CSRF/rollback, `MailpitApiE2ETest`의 실제 전달·단일 소비, `customer-portal.spec.ts` magic-link→My Requests→logout; password 계정 eligibility 제한은 REQ-AUTH-004에서 추적 |
| REQ-AUTH-002 | 같은 이메일만으로 익명 티켓을 자동 claim하지 않는다 | IMPLEMENTATION_READY | P1 | 37, 53 | `CustomerRequestPortalIntegrationTest`의 request-token/signed-grant 성공·tamper·expiry·replay·different-email 및 ownership/audit 원자성; FE-P0-CUSTOMER는 claim UI를 만들지 않고 matching email을 proof로 사용하지 않음 |
| REQ-AUTH-003 | 고객은 password registration과 email verification을 거쳐 로그인하고 single-use reset으로 credential과 기존 session을 안전하게 교체한다 | IMPLEMENTATION_READY | P0 auth | 21, 23, 25, 33, 34, 37, 39, 52, 56, ADR 0042 | `requestCustomerRegistration`, `verifyCustomerRegistration`, `createCustomerPasswordSession`, `requestCustomerPasswordReset`, `resetCustomerPassword`, `getCurrentCustomer` implementation contracts와 documentation contract tests; 미구현 operation은 runtime 정합성을 뜻하는 `FROZEN`을 구현 PR까지 보류; AUTH-005/006/007, ARCH-004, MAIL-001/002, DOC-001 |
| REQ-AUTH-004 | magic-link login은 passwordless identity에만 허용하고 명시적 registration completion은 password/profile/current consent를 원자적으로 설정하되 이메일로 티켓을 claim하지 않는다 | IMPLEMENTATION_READY | P0 auth | 21, 23, 25, 33, 34, 37, 39, 52, 56, ADR 0029/0042 | passwordless-only `requestCustomerMagicLink`/`consumeCustomerMagicLink`, `completePasswordlessCustomerRegistration`, credential/registration state projection implementation contracts와 documentation contract tests; magic eligibility/current-customer projection/completion runtime parity가 들어오는 구현 PR까지 관련 operation의 `FROZEN`을 보류; AUTH-001/002/003/004/008, ARCH-004, MAIL-001/002, DOC-001 |
| REQ-AUTH-005 | 직원은 email/password와 server-side session으로 로그인하고 disabled/expired session 또는 browser expected-actor 불일치는 접근할 수 없다 | IMPLEMENTATION_READY | M2 | 01, 25, 30, 31, 33, 35 ADR, 39, 52 | `StaffAuthIntegrationTest`의 invalid/mismatch·activity/controller/mutation/audit 비진입, `client.test.ts`의 held-CSRF actor snapshot, `StaffSessionContext.test.tsx`의 교차 탭 owner 보존, `access-surface.spec.ts`의 ADMIN route guard |
| REQ-AUTH-006 | 최초 ADMIN은 저장소 밖 secret file로만 bootstrap되고 로그인 실패는 안전하게 제한·감사된다 | IMPLEMENTATION_READY | M2 | 19, 23, 35 ADR, 52 | `FirstAdminBootstrapIntegrationTest`, lockout/generic error/secret scan |
| REQ-PERM-001 | 초기에는 모든 활성 상담사가 모든 staff-visible 티켓을 읽을 수 있다 | IMPLEMENTATION_READY | M2 | 33, 53 | `AgentTicketReadIntegrationTest`의 cross-group queue/direct URL 및 inactive/customer 거부; 검색은 후속 |
| REQ-PERM-002 | 직원·그룹·멤버십 관리는 ADMIN만 수행하고 API와 직접 URL 모두에서 거부된다 | IMPLEMENTATION_READY | M2/M6 | 30, 33, 35 ADR, 39 ADR, 55 | `AdminOrganizationIntegrationTest`, `/admin/staff`/`/admin/groups`, `Admin*.stories.tsx`, `access-surface.spec.ts`의 ADMIN route guard, focused/full Storybook MCP interaction/a11y: PASS |

## 2.2 고객 동의 정책

| ID | 요구사항 | 상태 | 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-CONSENT-001 | ADMIN은 `customer-consent:manage` 권한으로 registration/request-submission 동의 정책을 draft하고 immutable version으로 publish/archive하며 과거 수락 문서를 재현할 수 있다 | BLUEPRINT_READY | P0 consent | 21, 23, 25, 33, 34, 39, 56, D-058 | Core OpenAPI freeze 후 `IMPLEMENTATION_READY`; role/capability, If-Match, immutable published history, server-owned immediate effective/published timestamp, safe document, audit rollback; CONSENT-001, DOC-001 |
| REQ-CONSENT-002 | registration과 request submission은 final transaction에서 current required policy versions를 검증하고 append-only acceptance를 account/ticket mutation과 원자적으로 저장한다 | BLUEPRINT_READY | P0 consent | 21, 23, 25, 33, 34, 39, 56, D-058 | Core OpenAPI freeze 후 `IMPLEMENTATION_READY`; missing/duplicate/stale/wrong-context rejection, server time, atomic audit/acceptance, body-free logs/audits; CONSENT-002, DOC-001 |

## 3. 고객 문의와 티켓 처리

| ID | 요구사항 | 상태 | 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-TKT-001 | 최초 채널은 고객 웹 문의 폼이다 | IMPLEMENTATION_READY | M1 | 01, 04, 30, 37 | 익명 접수 E2E, FE-P0-CUSTOMER `/requests/new` browser flow |
| REQ-TKT-002 | 익명 고객은 이름·이메일로 접수할 수 있다 | IMPLEMENTATION_READY | M1 | 01, 02, 32, 37, ADR 0006 | `PublicRequestIntegrationTest`의 동일 미검증 이메일별 Customer 격리·동시 생성·토큰 교차 접근 거부, `PublicRequestRateLimitIntegrationTest`의 대상/client/global PostgreSQL bucket·forwarded spoof/fail-closed·429/503, `Issue24RemediationMigrationTest`의 V15→V17 verified-only unique constraint |
| REQ-TKT-003 | 고객이 자신의 요청과 공개 답변을 조회할 수 있다 | IMPLEMENTATION_READY | M1/M6 | 01, 30, 37, 39 ADR, 55 | `PublicRequestIntegrationTest`의 token-scoped 익명 PUBLIC follow-up/replay/mismatch·expiry·rollback과 `CustomerRequestPortalIntegrationTest` 고객 A/B 격리·PUBLIC-only projection; FE-P0-CUSTOMER anonymous/account detail과 `customer-portal.spec.ts` |
| REQ-TKT-004 | 관리자 설정으로 익명·선택 가입·가입 필수 모드를 바꿀 수 있다 | IMPLEMENTATION_READY | P1 | 01, 37, 52, 53, 55 | `CustomerAccessModeIntegrationTest` submit/view 행렬·optimistic conflict·감사 원자성, `customerAuthClient.test.ts`, `/admin/settings/customer-access-mode`의 expected-version/409 입력 보존 unit/Storybook; focused/full Storybook MCP interaction/a11y: PASS |
| REQ-TKT-005 | email magic link로 로그인하고 기존 익명 티켓을 명시적으로 연결한다 | IMPLEMENTATION_READY | P1 | 02, 37, 49, 53, 55 | `CustomerRequestPortalIntegrationTest` single-use proof claim·격리·PUBLIC follow-up audit/outbox와 `PublicRequestIntegrationTest`의 anonymous capability follow-up; FE-P0-CUSTOMER magic-link/session UI는 구현했고 explicit anonymous-ticket claim UI는 `DEFERRED_UI` |
| REQ-TKT-006 | 문의 본문은 Ticket.description이 아니라 첫 PUBLIC Comment다 | IMPLEMENTATION_READY | M1 | 01, 02, 32, 34 | TKT-001 |
| REQ-TKT-007 | 상담사는 공개 답변과 내부 메모를 모두 본다 | IMPLEMENTATION_READY | M3 | 01, 30, 33, 55 | `AgentTicketReadIntegrationTest`의 PUBLIC/INTERNAL projection, exact ON_HOLD/CLOSED status, createdAt 및 server-authorized capability; `AgentTicketEditorWorkspace`의 실제 PUBLIC/INTERNAL composer와 Storybook/E2E 구분 |
| REQ-TKT-008 | 고객은 공개 코멘트만 본다 | IMPLEMENTATION_READY | M1/M3 | 04, 30, 33, 37, 55 | 고객 API PUBLIC-only integration test와 token-scoped anonymous follow-up regression, `customerPortalClient.test.ts`, FE-P0-CUSTOMER allowlist projection/DOM/E2E |
| REQ-TKT-009 | 상담사가 고객 문의 없이 직접 티켓을 생성할 수 있다(`/agent/tickets/new`). 검색으로 찾은 기존 고객을 `customerId`로 재사용하거나, 생성 시점에 활성 그룹/구성원(`GET /api/v1/agent/assignment-options`)을 지정할 수 있다 | IMPLEMENTATION_READY | M3 | 04, 30, 39, 55 | `AgentTicketCommandIntegrationTest`(customerId 재사용/미존재/혼합 요청 포함), `AgentTicketReadIntegrationTest`(assignment-options), `CreateAgentTicketPage.test.tsx` |
| REQ-TKT-010 | 상태·우선순위·그룹·담당자를 관리한다 | IMPLEMENTATION_READY | M3/M4 | 01, 31, 34, 55 | transition/permission integration tests, `ticketEditorModel.test.ts`, capability-gated `AgentTicketEditorWorkspace` field command/E2E |
| REQ-TKT-011 | 담당 상담사는 지정된 그룹의 활성 멤버여야 한다 | IMPLEMENTATION_READY | M4 | 02, 33, 34, ADR 0038 | `TransferChildTicketIntegrationTest`의 active group/member 거부·원자적 rollback 및 `OrganizationConcurrencyIntegrationTest`의 ticket assignment/group disable 공유 잠금; editor는 detail `assignmentOptions`만 선택지로 사용 |
| REQ-TKT-012 | 상담사 간·그룹 간 이관이 가능하다 | IMPLEMENTATION_READY | M4 | 02, 30, 34, 55 | `TransferChildTicketIntegrationTest`; UI는 `DEFERRED_UI`/후속 재조합 |
| REQ-TKT-013 | 한 번의 저장에 코멘트와 필드 변경을 함께 반영한다 | IMPLEMENTATION_READY | M3 | 04, 31, 34 | one command/one audit, `AgentTicketCommandIntegrationTest`의 exact/misuse/concurrent replay와 `AgentTicketEditorWorkspace`의 persisted command-ID retry·exact `changedFields`·comment 통합 요청/E2E |
| REQ-TKT-014 | 서로 다른 필드는 병합하고 같은 필드 충돌은 경고한다 | IMPLEMENTATION_READY | M3 | 01, 04, 31, 34, 55 | `AgentTicketCommandIntegrationTest`, `ticketEditorModel.test.ts`, production editor conflict Storybook/E2E의 draft 보존 및 필드별 resolution |
| REQ-TKT-015 | 충돌 시 좌측 필드 패널 상단에 빨간 배너를 보여준다 | IMPLEMENTATION_READY | M3 | 30, 31, 55 | production editor conflict region, Storybook과 headless/browser draft 보존 테스트 |
| REQ-BULK-001 | 상담사는 명시 선택한 최대 100개 티켓에 상태·우선순위·담당자 또는 transfer를 부분 성공 방식으로 적용한다 | IMPLEMENTATION_READY | P1 | 31, 33, 34, 39, 50 | PostgreSQL `AgentTicketCommandIntegrationTest`의 100 상한/duplicate item 거부/독립 transaction/authorization/audit rollback/idempotent replay/optimistic conflict/transfer reason·partial result; `BulkTicketActionPanel`의 explicit selection/confirm/progress/item outcome/failed retry Storybook; CHG-001, CONC-001, IDEM-001 |

## 4. 부모·자식 티켓 협업

| ID | 요구사항 | 상태 | 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-CHILD-001 | 부모 티켓에서 내부 자식 티켓을 생성한다 | IMPLEMENTATION_READY | M5 | 01, 02, 30, 34 | `TransferChildTicketIntegrationTest`, V8 relation migration, component/browser/full-stack child E2E |
| REQ-CHILD-002 | 자식 티켓은 고객에게 완전히 숨겨진다 | IMPLEMENTATION_READY | M5 | 01, 33, 37 | child PUBLIC command 원자적 거부, INTERNAL-only production editor/Storybook, 고객 API shape·DOM·parent token child-number 404 통합/full-stack E2E |
| REQ-CHILD-003 | 부모 소유권은 최초 상담사·그룹에 유지된다 | IMPLEMENTATION_READY | M5 | 02, 34 | transfer-vs-child ownership 비교 통합 테스트와 full-stack selected-group 회귀 |
| REQ-CHILD-004 | 자식 담당자는 부모 대화 전체를 읽을 수 있다 | IMPLEMENTATION_READY | M5 | 30, 33 | `AgentTicketReadAuthorizationPolicyTest`의 relation grant seam; launch `ALL_TICKETS` 중복 grant 및 parent write 비승격 통합 테스트 |
| REQ-CHILD-005 | 그룹별 NONE/READ/READ_WRITE 권한으로 확장한다 | BLUEPRINT_READY | P2 | 33, 38 | 정책 행렬 테스트 |
| REQ-CHILD-006 | 미해결 자식이 있어도 부모 해결을 허용하되 경고한다 | IMPLEMENTATION_READY | M5 | 01, 30, 34 | structured count/numbers command test, `transfer-child-solve-warning.png`, non-blocking browser 경고 |
| REQ-CHILD-007 | 자식 해결은 부모 상태를 자동 변경하지 않는다 | IMPLEMENTATION_READY | M5 | 02, 34 | `TransferChildTicketIntegrationTest`의 child solve/parent version 불변 회귀 |

## 5. 변경·접근·보안 감사

| ID | 요구사항 | 상태 | 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-AUD-001 | 누가 언제 어떤 티켓 내용을 어떻게 수정했는지 기록한다 | IMPLEMENTATION_READY | M3 | 19, 32, 34 | CHG-001~005, production editor의 one-command `expectedVersion`/stable `clientCommandId` E2E |
| REQ-AUD-002 | 티켓별 열람 없이 전역 화면에서 변경 전후를 조회한다 | IMPLEMENTATION_READY | R2 | 19, 30, 39, 55 | `AuditExplorerIntegrationTest`, `auditInteraction.test.ts`; UI는 `DEFERRED_UI` |
| REQ-AUD-003 | 어떤 상담원이 어떤 티켓을 열었는지 기록한다 | IMPLEMENTATION_READY | R1 | 19, 31, 34 | `AgentTicketReadIntegrationTest`: 모든 성공 detail의 `API_RESOURCE_READ`, navigation 1건, 동일 interaction refetch의 추가 semantic view 0건, background semantic view 0건, audit 실패 fail-closed |
| REQ-AUD-004 | 상담원이 실행한 검색어와 결과 열람 연결을 기록한다 | IMPLEMENTATION_READY | R1/R2 | 19, 23, 34, ADR 0037 | `AgentTicketSearchIntegrationTest`의 filter/count/context와 `SEARCH_RESULT_OPENED` linkage/dedupe 및 encryption-key rotation 후 same-session origin 검증; detail linked-open 100개 제한·full count; real-stack search→ticket DB-ledger E2E |
| REQ-AUD-005 | 검색어 원문은 암호화 저장하고 routine audit에는 내용 비포함 marker·HMAC 지문만 유지한다 | IMPLEMENTATION_READY | R1 | 19, 23, 53, ADR 0036, ADR 0037 | `SearchQueryProtectionTest`의 content-free marker/exact round-trip/tamper/AAD/encryption rotation, fixed-size independent session fingerprint, V13 scrub·constraint, missing-key startup, DB plaintext-column 부재, 로그 캡처, 30일 expiry·retention rollback |
| REQ-AUD-006 | 감사 로그를 본 사람과 export한 사람도 감사한다 | IMPLEMENTATION_READY | R2 | 19, 33, 34 | `SecurityAuditorAuthorizationIntegrationTest`의 default-deny·명시 grant/revoke·session revalidation·audit rollback, `Issue24RemediationMigrationTest`의 V15→V17 무백필, list/detail/reveal/export/rebuild self-audit 장애 주입, 실제 Compose DB self-audit, export job/artifact placeholder 원자성; 프론트엔드 AUD-001/AUD-002 화면(`features/audit/AuditExplorerPage.test.tsx`, `features/audit/AuditExportStatusPage.test.tsx`, `api/client.test.ts`의 `getAuditExport` 커버리지)이 list/detail 조회와 내보내기 요청·상태 폴링을 소비 — protected reveal은 재인증 백엔드 부재로 `docs/55` §3에 `DEFERRED_UI`로 남음 |
| REQ-AUD-007 | Ticket change audit은 변경과 같은 트랜잭션에 기록한다 | IMPLEMENTATION_READY | M3 | 03, 19, 32 | CHG-001 |
| REQ-AUD-008 | 민감 조회 감사 저장 실패 시 성공 응답을 보내지 않는다 | IMPLEMENTATION_READY | R1 | 03, 19 | ticket detail/search와 Explorer list/detail/reveal self-audit 장애 주입 시 503·민감 원문/projection 미반환, `AttachmentDownloadAuditOrderTest`의 required audit 실패 시 private stream open 0회, retention audit 장애 시 delete rollback |
| REQ-AUD-009 | 감사 보존 기간·원문 공개 정책을 관리자 설정으로 관리한다 | PROVISIONAL | R2/P2 | 23, 36 | retention job·권한 테스트 |
| REQ-AUDX-001 | 권한 있는 요청자가 감사 활동 projection을 단기 보관 CSV/JSONL artifact로 내보내고 재검증된 다운로드를 받는다 | IMPLEMENTATION_READY | P1 | 19, 30, 33, 34, 39, 46, 50, 54 | PostgreSQL `AuditExplorerIntegrationTest`의 owner/current capability/lease recovery/streaming checksum/CSV formula escaping/expiry·delete/download audit·audit failure; frontend REQUESTED/RUNNING/READY/FAILED/EXPIRED terminal polling/download/regeneration tests; ANA-007, AUD-003/004, RET-001 |

## 6. 외부 전산·API·SDK

| ID | 요구사항 | 상태 | 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-INT-001 | 사설망 scoped API key 기반 Platform API v1을 제공한다 | IMPLEMENTATION_READY | I2/I3 | 18, 20, 39, 53, ADR 0031 | `PlatformOpenApiContractTest`, `PlatformNetworkBoundaryTest`의 production CIDR fail-closed, `PlatformTicketIntegrationTest`, V62 per-client policy의 `PlatformRateLimitIntegrationTest`; PLAT-001/002 |
| REQ-INT-002 | 머신 주체 IntegrationClient와 scope/자원 제한을 사용한다 | IMPLEMENTATION_READY | I1/I2 | 18, 32~34, 39, ADR 0012/0016/0031 | 기존 IntegrationClient lifecycle suite와 V62 policy/audit/usage의 `AdminIntegrationClientIntegrationTest` + `PlatformTicketIntegrationTest`, `PlatformRateLimitIntegrationTest`; INT-AUTH-001~004·ARCH-001/002/004·ACC-006/007·AUD-001 |
| REQ-INT-003 | 외부 쓰기는 Idempotency-Key를 지원한다 | IMPLEMENTATION_READY | I3 | 18, 20, 32 | `PlatformApiMigrationTest`, `PlatformTicketIntegrationTest`의 replay/key misuse/concurrent claim/audit·receipt crash rollback/final failure replay 및 Platform SLA target/fact exact-once; IDEM-001~004 |
| REQ-INT-004 | 외부 수정은 ETag/If-Match로 충돌을 제어한다 | IMPLEMENTATION_READY | I3 | 18, 20 | `PlatformTicketIntegrationTest` matching/stale/final replay; CONC-001 |
| REQ-INT-005 | 주문·결제 등은 ExternalReference로 연결한다 | IMPLEMENTATION_READY | I4 | 18, 30, 32~34, 39, 55, ADR 0015 | `ExternalReferenceValidationTest`, migration/integration tests, OpenAPI/API types; UI는 `DEFERRED_UI`. Platform API·provider fetch·mirroring은 미구현 |
| REQ-INT-006 | 외부 시스템에 signed webhook을 보낸다 | IMPLEMENTATION_READY | I5 | 18, 20, 39, 53, ADR 0011, ADR 0040 | V60 endpoint/secret/delivery model, FROZEN admin contract, `WebhookSecurityContractTest`, `AdminWebhookIntegrationTest`; worker fan-out/transport/retry evidence continues in `goal-integrations-webhooks-progress.md` |
| REQ-INT-007 | n8n/Workato에서 webhook으로 자동화할 수 있다 | BLUEPRINT_READY | I5/I7 | 18, 38 | 예제 workflow smoke test |
| REQ-INT-008 | TypeScript·Python·JVM SDK를 생성한다 | BLUEPRINT_READY | I6 | 20, 39 | SDK-001~003 |
| REQ-INT-009 | Agent App SDK와 Embed SDK로 내부 전산에 UI를 연결한다 | BLUEPRINT_READY | P7 | 18, 28, 38 | sandbox/embed security test |
| REQ-INT-010 | 증분 export와 snapshot export를 제공한다 | BLUEPRINT_READY | I7/P5 | 18, 20, 38 | EXP-001/002 |

## 7. SLA·통계·자동화·검색·추출

| ID | 요구사항 | 상태 | 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-SLA-001 | versioned First Reply SLA의 만족·위반 여부를 계산한다 | IMPLEMENTATION_READY | P3 | 12, 16, 44, 53, 55 | `FirstReplySlaStateMachineTest`, `FirstReplySlaIntegrationTest`, `FirstReplySlaAdminIntegrationTest`, `PlatformTicketIntegrationTest`의 API customer target/fact exact-once 및 internal exclusion, SLA-001/002/004/005/006/008, ANA-004; Queue/Search 상태·기한과 Workspace 목표/policy/schedule version을 text+icon으로 표시하는 `FirstReplySlaIndicator` Storybook |
| REQ-SLA-002 | 관리자가 timezone·평일/주말·시간구간·휴일을 수정한다 | IMPLEMENTATION_READY | P3 | 44, 52, 53, 55 | `BusinessTimeCalculatorTest`, `BusinessScheduleMigrationTest`, `BusinessScheduleAdminIntegrationTest`, `/admin/business-rules/schedules` Storybook states and focused/full Storybook MCP interaction/a11y: PASS |
| REQ-SLA-003 | First Reply SLA는 기본적으로 PENDING 동안 정지한다 | IMPLEMENTATION_READY | P3 | 44, 53 | `FirstReplySlaIntegrationTest`의 PENDING pause/resume 및 canonical audit 기반 idempotent interval rebuild, SLA-004/009; `/admin/business-rules/sla` pause status version editor |
| REQ-ANL-001 | Zendesk Explore 유사 통계와 대시보드를 제공한다 | BLUEPRINT_READY | P5 | 12, 16, 30, 46 | ANA-001~008 |
| REQ-AUT-001 | 티켓 이벤트 조건 기반 trigger를 제공한다 | BLUEPRINT_READY | P4 | 12, 34, 45, ADR 0024 | `TriggerEngineMigrationTest`, `TriggerDefinitionIntegrationTest`, `TriggerExecutionIntegrationTest`의 typed allowlist/immutable version/ETag/activation·rollback target 검증/reorder/admin capability/audit rollback/dry-run zero-side-effect, 모든 `TicketSubmitted` root transaction의 ordered version snapshot, evolving-state normal command, `TRIGGER_APPLIED` audit, depth/action/fingerprint guard, retry/dead-letter atomic rollback, metadata-only `ticket.trigger.executed` outbox; update event와 후속 action 종류는 계속 BLUEPRINT 범위 |
| REQ-AUT-002 | 시간 경과 기반 automation을 제공한다 | BLUEPRINT_READY | P4 | 12, 34, 45, ADR 0024 | `AutomationMigrationTest`, `AutomationDefinitionIntegrationTest`, `AutomationCandidateScannerIntegrationTest`, `AutomationExecutionIntegrationTest`의 versioned solved-age/CLOSE_TICKET allowlist, admin capability, ETag activation history, zero-side-effect dry-run, partial solved index, advisory single-scanner, 100-row batch, `(version,ticket,solvedAt)` interval idempotency, leased machine command/`AUTOMATION_APPLIED`, stale interval skip, retry/dead-letter rollback으로 AUT-009 충족; 다른 time condition/action은 계속 BLUEPRINT 범위 |
| REQ-EXP-001 | 티켓 상세·변경 이력·필터 결과를 추출한다 | BLUEPRINT_READY | P5 | 18, 20, 30, 46 | ANA-007, EXP-001/002. 이번 P1의 감사 활동 artifact export만으로는 이 넓은 요구사항을 완료 처리하지 않는다. |
| REQ-SRCH-001 | PostgreSQL 검색으로 시작하고 측정 후 Elasticsearch로 확장한다 | IMPLEMENTATION_READY | P6/P9 | 03, 11, 32, 47 | frozen POST search contract, SQL authorization, exact count, score/ticketNumber stable cursor, query protection/audit, V35 versioned staff-only PUBLIC/INTERNAL-separated trigram projection and transactional refresh/rebuild lock, literal wildcard and Korean/English/internal/exact-rank corpus, fixed query-count/index-plan tests, real-stack E2E, 1M EXPLAIN and p50/p95 budget evidence; `/agent/search` raw-query URL exclusion, server filters, opaque cursor history and origin search-event detail handoff unit/Storybook |
| REQ-SRCH-002 | 상담사가 신규 티켓 생성 화면에서 기존 고객을 이름·이메일로 검색해 요청자로 선택할 수 있다 | IMPLEMENTATION_READY | M3 | 30, 33, 39, 55 | `AgentCustomerSearchIntegrationTest`(검색·빈 결과·검증·fail-closed·감사 불변성), `RequesterSearchField.stories.tsx`, `CreateAgentTicketPage.test.tsx` |
| REQ-PERF-001 | 대규모 fixture와 EXPLAIN ANALYZE로 성능 근거를 남긴다 | IMPLEMENTATION_READY | R3/P9 | 11, 21, 35, 39 | release fixture/query-plan evidence와 `AdminOrganizationIntegrationTest`의 100-row max page, staff/group/member row 증가 전후 동일 SQL statement count(각 10 이하) |


## 8. 티켓 구성·파일·채널·확장 기능

| ID | 요구사항 | 상태 | 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-CFG-001 | 태그와 조건 기반 saved view를 제공한다 | BLUEPRINT_READY | P2/P6 | 38, 47 | view query/permission/audit 테스트. 이번 P1은 태그 조건을 의도적으로 제외하므로 이 넓은 요구사항을 완료 처리하지 않는다. |
| REQ-VIEW-001 | 태그를 제외한 allowlisted condition AST 기반의 versioned SYSTEM/PERSONAL/SHARED saved view를 제공한다 | IMPLEMENTATION_READY | P1 | 33, 34, 39, 47, 50 | PostgreSQL `AgentTicketReadIntegrationTest`의 AST allowlist/owner·shared capability/version conflict/reorder/preview/authorization·audit/description 영속성·동일 compiler row-count/one-UNION-ALL count와 batch 단일 `ticketCountAsOf`; frontend description draft/conflict 보존, `EXACT`/`OMITTED_VISIBLE_LIMIT` count metadata 조합의 strict decoder와 negative tests, exact count 기준 시각, server persistence, columns/sort/share scope and cursor reset unit/Storybook; PERF-001 |
| REQ-CFG-002 | typed custom field와 form을 제공한다 | BLUEPRINT_READY | P6 | 38, 47 | type validation/migration/projection 테스트 |
| REQ-CFG-003 | 상담사가 versioned PERSONAL/SHARED macro를 preview한 뒤 하나의 command로 적용한다 | IMPLEMENTATION_READY | Wave 2 | 33, 34, 39, 45, 47, ADR 0024, ADR 0040 | `MacroDefinitionMigrationTest`, `MacroDefinitionIntegrationTest`의 owner/shared capability/immutable version/ETag/activation/audit rollback, preview no-ticket-side-effect/template allowlist/`MACRO_PREVIEWED` fail-closed access audit, apply field/tag/custom-field/edited-comment one-version-one-audit/exact replay 및 `MACRO_APPLIED` provenance |
| REQ-CFG-010 | 관리자가 immutable machine key와 typed EAV validation을 가진 ticket field definition/option을 관리하고, 서버 projection이 customer/staff field visibility를 분리한다 | IMPLEMENTATION_READY | Wave 1 | 02 Goal, 32, 33, 39, 47, ADR 0041 | PostgreSQL type CHECK/option lifecycle/visibility-bypass/audit rollback, owned Core contract and customer/staff projection tests; CFG-001 |
| REQ-CFG-011 | 관리자가 immutable published version과 server-authoritative conditional visibility를 가진 ticket form을 관리한다 | IMPLEMENTATION_READY | Wave 1 | 02 Goal, 33, 34, 39, 47, ADR 0041 | form publish cycle/contradiction/hidden-required/version-stale integration tests and customer/agent projection E2E; CFG-002 |
| REQ-CFG-012 | 관리자가 normalized tag catalog을 관리하고, ticket command·검색·저장형 View에서 tag를 권한과 감사 경계 안에 사용한다 | IMPLEMENTATION_READY | Wave 1 | 02 Goal, 33, 34, 39, 47, ADR 0041 | normalization/concurrent add-remove/audit/authorization/query contributor tests; CFG-003 |
| REQ-CFG-013 | 관리자가 fixed status category에 매핑되는 custom status label을 관리하며 CLOSED terminal compatibility를 보존한다 | IMPLEMENTATION_READY | Wave 1 | 02 Goal, 33, 34, 39, 47, ADR 0041 | default uniqueness/status-category compatibility/closed mutation denial/old-client category integration tests; CFG-004 |
| REQ-CFG-014 | 고객 문의는 server-authorized form/version의 customer-safe projection과 typed candidate values를 사용하고 최종 제출에서 현재 form snapshot을 다시 검증·보존한다 | BLUEPRINT_READY | P0 request form | 21, 23, 25, 26, 33, 34, 39, 47, 56, ADR 0041, D-059 | Core OpenAPI freeze 후 `IMPLEMENTATION_READY`; customer/staff existence-leak regression, stale/current version, conditional projection, typed normalization, selected-form persistence, stable initial-command replay/conflict/single-winner, planned-customer multipart rollback, one TicketAudit; CFG-006, TKT-001/002, CHG-001/002/003, FILE-001/003/004/006, DOC-001 |
| REQ-FILE-001 | private object storage 기반 첨부파일을 제공한다 | IMPLEMENTATION_READY | P1 | 38, 39, 48, 50 | PostgreSQL `AttachmentPipelineIntegrationTest`의 bounded stream/quarantine/SHA-256/MIME mismatch/deterministic malware/clean-only link/PUBLIC-INTERNAL isolation/expiry cleanup/audit failure와 `AttachmentProductionBoundaryTest`의 production local-storage/deterministic-scanner 제외, `CustomerRequestPortalIntegrationTest`의 authenticated own-ticket upload/link/download, CUSTOMER_SESSION 감사, customer A/B 격리, cross-path replay; frontend authenticated metadata strict decode, CSRF multipart upload, attachmentIds follow-up, audited Blob download unit/page/Storybook; FILE-001/003/004/006 |
| REQ-FILE-002 | rich text와 redaction은 안전한 canonical format과 별도 권한을 사용한다 | BLUEPRINT_READY | P8 | 48 | XSS/redaction/audit 테스트 |
| REQ-CHAN-001 | 이메일 수신·발신을 Ticket/Comment channel adapter로 제공한다 | BLUEPRINT_READY | P8 | 38, 49 | threading/dedup/outbox/bounce 테스트 |
| REQ-CHAN-002 | 채팅·메시징은 나중에 같은 conversation model 위에 추가한다 | DEFERRED | P8+ | 38, 49 | session/transcript/channel adapter 테스트 |
| REQ-CHAN-003 | 개발·CI outbound email은 Mailpit을 사용하고 production provider는 adapter로 분리한다 | IMPLEMENTATION_READY | P1 | 49, 53 | `MailpitApiE2ETest`, Compose `mailpit:1025` + `localhost:8025`, `MailDeliveryConfigurationValidatorTest`의 production opt-in SMTP/TLS/키 검증, `/admin/operations/mail`의 masked transport summary |
| REQ-NOTIF-001 | 고객 알림은 ticket transaction 밖의 durable outbox로 전달한다 | IMPLEMENTATION_READY | P1/P8 | 45, 49, 53 | `OutboundMailDeliveryIntegrationTest`의 post-commit/delivery/manual retry race·audit rollback, `AdminOutboundMailIntegrationTest`의 masked admin projection/CSRF, V18 intent/attempt/event 및 V29 operations cursor index, `/admin/operations/mail` + `admin-operations.spec.ts` safe retry evidence; focused/full Storybook MCP interaction/a11y: PASS |
| REQ-AI-001 | AI 요약·답변 제안은 검색·권한·감사·평가 기반이 준비된 뒤 선택적으로 추가한다 | DEFERRED | P10 | 38, 49 | 데이터 경계/평가/사람 승인 테스트 |

## 9. 프론트엔드 경험

| ID | 요구사항 | 상태 | 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-UI-001 | Deskseed Agent Workspace의 고밀도 Queue/Workspace UI를 제공한다 | IMPLEMENTATION_READY | M2~ | 28, 29, 30, 39 ADR, 55 | 실제 API projection 기반 Queue/Workspace Darwin·Linux 1280·1440·1920 current-design baselines와 fixture-route 404 E2E |
| REQ-UI-002 | Views 목록과 티켓 테이블을 제공한다 | IMPLEMENTATION_READY | M2 | 28, 30, 55 | `AgentViewsPage.test.tsx`, Queue Storybook interaction, keyboard row-open E2E |
| REQ-UI-003 | 좌측 속성·중앙 대화·선택 가능한 우측 context panel 구조를 제공한다 | IMPLEMENTATION_READY | M2 | 29, 30, 39 ADR | `frontend-system-workspace-{1280,1440,1920}.png`, 실제 agent detail API projection, 1500px 이하 context toggle E2E |
| REQ-UI-004 | 고객·앱·자식 티켓·외부 참조를 context panel에서 전환한다 | DEFERRED | M5/I4/P7 | 28, 30, 55 | Customer/child/외부 참조 탭과 external lazy CRUD/safe deep link는 구현됨; app marketplace surface는 계속 deferred |
| REQ-UI-005 | WCAG 2.2 AA 수준과 키보드 조작을 목표로 한다 | IMPLEMENTATION_READY | 전 단계 | 29, 35, 40 | 전체 Storybook interaction+axe, Queue keyboard·production composer tab/draft·navigation guard·focus Playwright 검증 |
| REQ-UI-006 | Zendesk 상표·로고를 복제하지 않고 독립 브랜드를 사용한다 | IMPLEMENTATION_READY | M0 | 29, 39 ADR | Deskseed-only current baselines, design-system boundary 및 proprietary asset scan |

## 10. 추적 규칙

1. 새 요구사항은 `REQ-*` ID를 부여한다.
2. 모든 PR은 관련 요구사항 ID와 검증 게이트 ID를 적는다.
3. 요구사항이 `BLUEPRINT_READY`에서 `IMPLEMENTATION_READY`로 이동하려면 다음이 있어야 한다.
   - 상태·권한·실패 의미가 확정된 PRD
   - DB migration 초안
   - OpenAPI 또는 UI contract
   - 최소 테스트 시나리오
   - 운영·보안 영향 기록
4. 구현되지 않은 기능을 README에서 완성 기능처럼 표현하지 않는다.

## 11. Wave 0 and Wave 1 extension foundation

| ID | 요구사항 | 상태 | 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-FND-001 | 병렬 Wave lane은 소유한 OpenAPI fragment·migration 범위·progress/traceability 영역만 변경하고, 생성된 Core bundle이나 다른 lane의 공용 계약을 직접 수정하지 않는다 | IMPLEMENTATION_READY | Wave 0 F1 | ADR 0040, 39, 41, 50 | deterministic bundle parity, duplicate path/method/component rejection, documentation contract gate; delivery-time range registry는 Wave 완료 후 폐기 |
| REQ-FND-002 | 조건·액션·템플릿 변수·검색 predicate·analytics dimension은 versioned descriptor registry로 확장하며 unknown/duplicate/incompatible input은 fail closed한다 | IMPLEMENTATION_READY | Wave 0 F1 | ADR 0040, 34, 45, 46, 47 | duplicate descriptor startup failure, AST bound/unknown-type rejection, action external-I/O separation, ARCH-001 |
| REQ-FND-003 | 공개 가능한 integration event intent는 ticket mutation과 원자적으로 PostgreSQL outbox에 기록되고 worker replay/lease 실패는 committed ticket mutation을 되돌리지 않는다 | IMPLEMENTATION_READY | Wave 0 F2 | ADR 0040, 18, 32, 34, 45 | PostgreSQL outbox atomicity, lease recovery, payload redaction/visibility, ARCH-002/003 |
| REQ-FND-004 | feature lane은 central App/shell을 수정하지 않고 deterministic route/navigation/workspace contribution을 추가하며 duplicate/권한/extension failure를 fail closed한다 | IMPLEMENTATION_READY | Wave 0 F3 | ADR 0040, 28–31, 40, 51, 55 | discovery order, duplicate rejection, denied route/nav, error isolation, typecheck and Storybook gate |

## 12. Wave 1 drafts and presence

| ID | 요구사항 | 상태 | 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-COL-001 | 상담사는 티켓별 PUBLIC/INTERNAL 초안을 분리해 최대 30일 서버와 7일 브라우저에 복구할 수 있고, 다른 직원·다른 channel·CLOSED ticket·낡은 버전의 쓰기는 안전하게 격리한다 | IMPLEMENTATION_READY | Wave 1 D1 | ADR 0040, 31, 34, 48, 50, 55 | `AgentTicketDraftIntegrationTest` owner/channel/CLOSED/conflict/attachment-owner/no-ticket-audit; `JdbcTicketDraftStoreIntegrationTest` TTL/lease; client decoder and local recovery unit tests; ARCH-001/002/004, FILE-001 |
| REQ-COL-002 | 읽기 권한이 있는 상담사는 단일 인스턴스 범위의 authenticated WebSocket presence와 commit 뒤 safe stale 알림을 받고, presence는 ticket lock이나 optimistic concurrency를 대체하지 않는다 | BLUEPRINT_READY | Wave 1 D2 | 31, 33, 34, 39, 50, 55 | Origin/session/ticket authorization, message rate·size, heartbeat TTL, disconnect cleanup, after-commit only notification, no body/PII payload, multi-instance fail-fast, frontend contribution and real-stack two-agent verification |
