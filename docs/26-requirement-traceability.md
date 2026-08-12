# 요구사항 추적 매트릭스 (Requirement Traceability Matrix)

## 1. 목적

이 문서는 대화에서 확정된 제품 요구사항이 설계 문서, 구현 단계, 검증 기준 중 어디에 반영되어 있는지 추적한다. 기능을 추가하거나 범위를 바꿀 때 이 표를 먼저 수정한다.

상태 정의:

- `IMPLEMENTATION_READY`: 현재 문서만으로 첫 구현을 시작할 수 있다.
- `BLUEPRINT_READY`: 장기 구조와 경계는 정해졌지만, 구현 직전에 정책값이나 API 계약을 동결해야 한다.
- `PROVISIONAL`: 운영·법률·보안 정책의 최종 결정이 필요하다.
- `DEFERRED`: 의도적으로 뒤 단계에 배치했다.

## 2. 제품·배포·기술 요구사항

| ID | 요구사항 | 상태 | 구현 단계 | 기준 문서 | 완료 증거 |
|---|---|---:|---|---|---|
| REQ-PROD-001 | 한 설치 인스턴스가 한 조직을 위한 self-hosted 서비스여야 한다 | IMPLEMENTATION_READY | M0 | 00, 03, 36 | Docker Compose 설치와 신규 인스턴스 부팅 |
| REQ-PROD-002 | Zendesk와 유사한 고객지원 행동 모델을 갖는다 | IMPLEMENTATION_READY | 전 단계 | 00, 01, 02, 30 | E2E 업무 시나리오 |
| REQ-TECH-001 | Kotlin/Spring/PostgreSQL 기반으로 시작한다 | IMPLEMENTATION_READY | M0 | 03, 22, 27 | 빌드·테스트 통과 |
| REQ-TECH-002 | 모듈러 모놀리스로 시작하고 필요 시 이벤트·Kafka로 진화한다 | IMPLEMENTATION_READY | M0→P9 | 03, 34, 38 | Modulith 검증, 도입 ADR |
| REQ-TECH-003 | React/TypeScript/Vite 프론트엔드를 사용한다 | IMPLEMENTATION_READY | M0 | 22, 28, 29 | 프론트 빌드·E2E |
| REQ-PORT-001 | 먼저 작동하는 포트폴리오를 만들고 이후 성능·Kafka까지 깊게 확장한다 | IMPLEMENTATION_READY | 전체 | 05, 11, 27, 41 | 릴리스별 증거 문서 |

## 2.1 인증·초기 상담원 가시성

| ID | 요구사항 | 상태 | 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-AUTH-001 | 고객 계정 인증은 DB-backed single-use email magic link로 시작한다 | IMPLEMENTATION_READY | P1 | 37, 49, 53 | expiry/replay/enumeration/session 테스트 |
| REQ-AUTH-002 | 같은 이메일만으로 익명 티켓을 자동 claim하지 않는다 | IMPLEMENTATION_READY | P1 | 37, 53 | explicit token/claim 테스트 |
| REQ-AUTH-005 | 직원은 email/password와 server-side session으로 로그인하고 disabled/expired session 또는 browser expected-actor 불일치는 접근할 수 없다 | IMPLEMENTATION_READY | M2 | 01, 25, 30, 31, 33, 35 ADR, 39, 52 | `StaffAuthIntegrationTest`의 invalid/mismatch·activity/controller/mutation/audit 비진입, `client.test.ts`의 held-CSRF actor snapshot, `StaffSessionContext.test.tsx`의 교차 탭 owner 보존, `staff-auth-admin.spec.ts` |
| REQ-AUTH-006 | 최초 ADMIN은 저장소 밖 secret file로만 bootstrap되고 로그인 실패는 안전하게 제한·감사된다 | IMPLEMENTATION_READY | M2 | 19, 23, 35 ADR, 52 | `FirstAdminBootstrapIntegrationTest`, lockout/generic error/secret scan |
| REQ-PERM-001 | 초기에는 모든 활성 상담사가 모든 staff-visible 티켓을 읽을 수 있다 | IMPLEMENTATION_READY | M2 | 33, 53 | `AgentTicketReadIntegrationTest`의 cross-group queue/direct URL 및 inactive/customer 거부; 검색은 후속 |
| REQ-PERM-002 | 직원·그룹·멤버십 관리는 ADMIN만 수행하고 API와 직접 URL 모두에서 거부된다 | IMPLEMENTATION_READY | M2/M6 | 30, 33, 35 ADR | `AdminOrganizationIntegrationTest`, direct URL E2E |

## 3. 고객 문의와 티켓 처리

| ID | 요구사항 | 상태 | 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-TKT-001 | 최초 채널은 고객 웹 문의 폼이다 | IMPLEMENTATION_READY | M1 | 01, 04, 30, 37 | 익명 접수 E2E |
| REQ-TKT-002 | 익명 고객은 이름·이메일로 접수할 수 있다 | IMPLEMENTATION_READY | M1 | 01, 02, 32, 37, ADR 0006 | `PublicRequestIntegrationTest`의 동일 미검증 이메일별 Customer 격리·동시 생성·토큰 교차 접근 거부와 verified-only unique constraint |
| REQ-TKT-003 | 고객이 자신의 요청과 공개 답변을 조회할 수 있다 | IMPLEMENTATION_READY | M1/M6 | 01, 30, 37 | 내부 메모 비노출 E2E |
| REQ-TKT-004 | 관리자 설정으로 익명·선택 가입·가입 필수 모드를 바꿀 수 있다 | IMPLEMENTATION_READY | P1 | 01, 37, 52, 53 | 설정별 계약 테스트 |
| REQ-TKT-005 | email magic link로 로그인하고 기존 익명 티켓을 명시적으로 연결한다 | IMPLEMENTATION_READY | P1 | 02, 37, 49, 53 | single-use·claim·격리 테스트 |
| REQ-TKT-006 | 문의 본문은 Ticket.description이 아니라 첫 PUBLIC Comment다 | IMPLEMENTATION_READY | M1 | 01, 02, 32, 34 | TKT-001 |
| REQ-TKT-007 | 상담사는 공개 답변과 내부 메모를 모두 본다 | IMPLEMENTATION_READY | M3 | 01, 30, 33 | `AgentTicketReadIntegrationTest`, `AgentTicketWorkspacePage.test.tsx`, `customer-request.full-stack.spec.ts`의 실제 PUBLIC/INTERNAL 저장 회귀 |
| REQ-TKT-008 | 고객은 공개 코멘트만 본다 | IMPLEMENTATION_READY | M1/M3 | 04, 30, 33, 37 | TKT-002, `customer-request.full-stack.spec.ts`의 PUBLIC 노출·INTERNAL 비노출 E2E |
| REQ-TKT-009 | 상담사가 고객 문의 없이 직접 티켓을 생성할 수 있다 | IMPLEMENTATION_READY | M3 | 04, 30, 39 | Agent create E2E |
| REQ-TKT-010 | 상태·우선순위·그룹·담당자를 관리한다 | IMPLEMENTATION_READY | M3/M4 | 01, 31, 34 | transition/permission 테스트, `AgentTicketWorkspacePage.test.tsx`의 통합 command body 회귀 |
| REQ-TKT-011 | 담당 상담사는 지정된 그룹의 활성 멤버여야 한다 | IMPLEMENTATION_READY | M4 | 02, 33, 34, ADR 0038 | `TransferChildTicketIntegrationTest`의 active group/member 거부·원자적 rollback 및 `OrganizationConcurrencyIntegrationTest`의 ticket assignment/group disable 공유 잠금 |
| REQ-TKT-012 | 상담사 간·그룹 간 이관이 가능하다 | IMPLEMENTATION_READY | M4 | 02, 30, 34 | `TransferChildTicketIntegrationTest`, `transfer-child-ticket.spec.ts`, full-stack transfer E2E |
| REQ-TKT-013 | 한 번의 저장에 코멘트와 필드 변경을 함께 반영한다 | IMPLEMENTATION_READY | M3 | 04, 31, 34 | one command/one audit, `AgentTicketCommandIntegrationTest`의 exact/misuse/concurrent replay와 `AgentTicketWorkspacePage.test.tsx`의 persisted command-ID retry·exact `changedFields`·comment 통합 요청 |
| REQ-TKT-014 | 서로 다른 필드는 병합하고 같은 필드 충돌은 경고한다 | IMPLEMENTATION_READY | M3 | 01, 04, 31, 34 | TKT-006, `ticket-composer-conflict.spec.ts`의 두 browser context same-field/non-overlap E2E |
| REQ-TKT-015 | 충돌 시 좌측 필드 패널 상단에 빨간 배너를 보여준다 | IMPLEMENTATION_READY | M3 | 30, 31 | `ticket-command-conflict-preserves-drafts.png`, banner focus·request ID·draft 보존 E2E |

## 4. 부모·자식 티켓 협업

| ID | 요구사항 | 상태 | 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-CHILD-001 | 부모 티켓에서 내부 자식 티켓을 생성한다 | IMPLEMENTATION_READY | M5 | 01, 02, 30, 34 | `TransferChildTicketIntegrationTest`, V8 relation migration, component/browser/full-stack child E2E |
| REQ-CHILD-002 | 자식 티켓은 고객에게 완전히 숨겨진다 | IMPLEMENTATION_READY | M5 | 01, 33, 37 | child PUBLIC command 원자적 거부, INTERNAL-only workspace, 고객 API shape·DOM·parent token child-number 404 통합/full-stack E2E |
| REQ-CHILD-003 | 부모 소유권은 최초 상담사·그룹에 유지된다 | IMPLEMENTATION_READY | M5 | 02, 34 | transfer-vs-child ownership 비교 통합 테스트와 full-stack selected-group 회귀 |
| REQ-CHILD-004 | 자식 담당자는 부모 대화 전체를 읽을 수 있다 | IMPLEMENTATION_READY | M5 | 30, 33 | `AgentTicketReadAuthorizationPolicyTest`의 relation grant seam; launch `ALL_TICKETS` 중복 grant 및 parent write 비승격 통합 테스트 |
| REQ-CHILD-005 | 그룹별 NONE/READ/READ_WRITE 권한으로 확장한다 | BLUEPRINT_READY | P2 | 33, 38 | 정책 행렬 테스트 |
| REQ-CHILD-006 | 미해결 자식이 있어도 부모 해결을 허용하되 경고한다 | IMPLEMENTATION_READY | M5 | 01, 30, 34 | structured count/numbers command test, `transfer-child-solve-warning.png`, non-blocking browser 경고 |
| REQ-CHILD-007 | 자식 해결은 부모 상태를 자동 변경하지 않는다 | IMPLEMENTATION_READY | M5 | 02, 34 | `TransferChildTicketIntegrationTest`의 child solve/parent version 불변 회귀 |

## 5. 변경·접근·보안 감사

| ID | 요구사항 | 상태 | 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-AUD-001 | 누가 언제 어떤 티켓 내용을 어떻게 수정했는지 기록한다 | IMPLEMENTATION_READY | M3 | 19, 32, 34 | CHG-001~005 |
| REQ-AUD-002 | 티켓별 열람 없이 전역 화면에서 변경 전후를 조회한다 | IMPLEMENTATION_READY | R2 | 19, 30, 39 | `AuditExplorerIntegrationTest`, `audit-explorer.spec.ts`의 3개 desktop 폭 + Axe, 실제 Compose `audit-explorer.full-stack.spec.ts` before/after 직접 URL E2E |
| REQ-AUD-003 | 어떤 상담원이 어떤 티켓을 열었는지 기록한다 | IMPLEMENTATION_READY | R1 | 19, 31, 34 | `AgentTicketReadIntegrationTest`: 모든 성공 detail의 `API_RESOURCE_READ`, navigation 1건, 동일 interaction refetch의 추가 semantic view 0건, background semantic view 0건, audit 실패 fail-closed |
| REQ-AUD-004 | 상담원이 실행한 검색어와 결과 열람 연결을 기록한다 | IMPLEMENTATION_READY | R1/R2 | 19, 23, 34, ADR 0037 | `AgentTicketSearchIntegrationTest`의 filter/count/context와 `SEARCH_RESULT_OPENED` linkage/dedupe 및 encryption-key rotation 후 same-session origin 검증; detail linked-open 100개 제한·full count; real-stack search→ticket DB-ledger E2E |
| REQ-AUD-005 | 검색어 원문은 암호화 저장하고 routine audit에는 내용 비포함 marker·HMAC 지문만 유지한다 | IMPLEMENTATION_READY | R1 | 19, 23, 53, ADR 0036, ADR 0037 | `SearchQueryProtectionTest`의 content-free marker/exact round-trip/tamper/AAD/encryption rotation, fixed-size independent session fingerprint, V13 scrub·constraint, missing-key startup, DB plaintext-column 부재, 로그 캡처, 30일 expiry·retention rollback |
| REQ-AUD-006 | 감사 로그를 본 사람과 export한 사람도 감사한다 | IMPLEMENTATION_READY | R2 | 19, 33, 34 | `SecurityAuditorAuthorizationIntegrationTest`의 default-deny·명시 grant/revoke·session revalidation·audit rollback, list/detail/reveal/export/rebuild self-audit 장애 주입, 실제 Compose DB self-audit, export job/artifact placeholder 원자성 |
| REQ-AUD-007 | Ticket change audit은 변경과 같은 트랜잭션에 기록한다 | IMPLEMENTATION_READY | M3 | 03, 19, 32 | CHG-001 |
| REQ-AUD-008 | 민감 조회 감사 저장 실패 시 성공 응답을 보내지 않는다 | IMPLEMENTATION_READY | R1 | 03, 19 | ticket detail/search와 Explorer list/detail/reveal self-audit 장애 주입 시 503·민감 원문/projection 미반환, retention audit 장애 시 delete rollback |
| REQ-AUD-009 | 감사 보존 기간·원문 공개 정책을 관리자 설정으로 관리한다 | PROVISIONAL | R2/P2 | 23, 36 | retention job·권한 테스트 |

## 6. 외부 전산·API·SDK

| ID | 요구사항 | 상태 | 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-INT-001 | 사설망 scoped API key 기반 Platform API v1을 제공한다 | IMPLEMENTATION_READY | I2/I3 | 18, 20, 39, 53 | OpenAPI·scope·idempotency test |
| REQ-INT-002 | 머신 주체 IntegrationClient와 scope/자원 제한을 사용한다 | IMPLEMENTATION_READY | I1 | 18, 33 | INT-AUTH-001~004 |
| REQ-INT-003 | 외부 쓰기는 Idempotency-Key를 지원한다 | IMPLEMENTATION_READY | I3 | 18, 20 | IDEM-001~004 |
| REQ-INT-004 | 외부 수정은 ETag/If-Match로 충돌을 제어한다 | IMPLEMENTATION_READY | I3 | 18, 20 | CONC-001 |
| REQ-INT-005 | 주문·결제 등은 ExternalReference로 연결한다 | IMPLEMENTATION_READY | I4 | 18, 32 | EXT-001~004 |
| REQ-INT-006 | 외부 시스템에 signed webhook을 보낸다 | BLUEPRINT_READY | I5 | 18, 20, 38 | WH-001~005 |
| REQ-INT-007 | n8n/Workato에서 webhook으로 자동화할 수 있다 | BLUEPRINT_READY | I5/I7 | 18, 38 | 예제 workflow smoke test |
| REQ-INT-008 | TypeScript·Python·JVM SDK를 생성한다 | BLUEPRINT_READY | I6 | 20, 39 | SDK-001~003 |
| REQ-INT-009 | Agent App SDK와 Embed SDK로 내부 전산에 UI를 연결한다 | BLUEPRINT_READY | P7 | 18, 28, 38 | sandbox/embed security test |
| REQ-INT-010 | 증분 export와 snapshot export를 제공한다 | BLUEPRINT_READY | I7/P5 | 18, 20, 38 | EXP-001/002 |

## 7. SLA·통계·자동화·검색·추출

| ID | 요구사항 | 상태 | 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-SLA-001 | versioned First Reply SLA의 만족·위반 여부를 계산한다 | IMPLEMENTATION_READY | P3 | 12, 16, 44, 53 | SLA-001/002/004/005/006/008 |
| REQ-SLA-002 | 관리자가 timezone·평일/주말·시간구간·휴일을 수정한다 | IMPLEMENTATION_READY | P3 | 44, 52, 53 | schedule preview/version/audit |
| REQ-SLA-003 | First Reply SLA는 기본적으로 PENDING 동안 정지한다 | IMPLEMENTATION_READY | P3 | 44, 53 | pause interval rebuild |
| REQ-ANL-001 | Zendesk Explore 유사 통계와 대시보드를 제공한다 | BLUEPRINT_READY | P5 | 12, 16, 30, 46 | ANA-001~008 |
| REQ-AUT-001 | 티켓 이벤트 조건 기반 trigger를 제공한다 | BLUEPRINT_READY | P4 | 12, 34, 45 | AUT-001~008 |
| REQ-AUT-002 | 시간 경과 기반 automation을 제공한다 | BLUEPRINT_READY | P4 | 12, 45 | AUT-009 |
| REQ-EXP-001 | 티켓 상세·변경 이력·필터 결과를 추출한다 | BLUEPRINT_READY | P5 | 18, 20, 30, 46 | ANA-007, EXP-001/002 |
| REQ-SRCH-001 | PostgreSQL 검색으로 시작하고 측정 후 Elasticsearch로 확장한다 | IMPLEMENTATION_READY | P6/P9 | 03, 11, 47 | frozen POST search contract, parameterized PostgreSQL authorized query, exact count/stable sort, fixed 2-SQL query-count test, component+real-stack E2E |
| REQ-PERF-001 | 대규모 fixture와 EXPLAIN ANALYZE로 성능 근거를 남긴다 | IMPLEMENTATION_READY | R3/P9 | 11, 21, 35 | `docs/performance/audit-explorer-1m-query-plan.md`: PostgreSQL 100만 행 first/actor/ticket/action `EXPLAIN (ANALYZE, BUFFERS)` 및 index 저장 비용 |


## 8. 티켓 구성·파일·채널·확장 기능

| ID | 요구사항 | 상태 | 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-CFG-001 | 태그와 조건 기반 saved view를 제공한다 | BLUEPRINT_READY | P2/P6 | 38, 47 | view query/permission/audit 테스트 |
| REQ-CFG-002 | typed custom field와 form을 제공한다 | BLUEPRINT_READY | P6 | 38, 47 | type validation/migration/projection 테스트 |
| REQ-CFG-003 | 상담사가 macro를 preview한 뒤 하나의 command로 적용한다 | BLUEPRINT_READY | P6 | 38, 45, 47 | preview/no-side-effect/one-audit 테스트 |
| REQ-FILE-001 | private object storage 기반 첨부파일을 제공한다 | BLUEPRINT_READY | P8 | 38, 48 | upload/scan/download/access 테스트 |
| REQ-FILE-002 | rich text와 redaction은 안전한 canonical format과 별도 권한을 사용한다 | BLUEPRINT_READY | P8 | 48 | XSS/redaction/audit 테스트 |
| REQ-CHAN-001 | 이메일 수신·발신을 Ticket/Comment channel adapter로 제공한다 | BLUEPRINT_READY | P8 | 38, 49 | threading/dedup/outbox/bounce 테스트 |
| REQ-CHAN-002 | 채팅·메시징은 나중에 같은 conversation model 위에 추가한다 | DEFERRED | P8+ | 38, 49 | session/transcript/channel adapter 테스트 |
| REQ-CHAN-003 | 개발·CI outbound email은 Mailpit을 사용하고 production provider는 adapter로 분리한다 | IMPLEMENTATION_READY | P1 | 49, 53 | Mailpit API delivery test |
| REQ-NOTIF-001 | 고객 알림은 ticket transaction 밖의 durable outbox로 전달한다 | IMPLEMENTATION_READY | P1/P8 | 45, 49, 53 | retry/idempotency/delivery status 테스트 |
| REQ-AI-001 | AI 요약·답변 제안은 검색·권한·감사·평가 기반이 준비된 뒤 선택적으로 추가한다 | DEFERRED | P10 | 38, 49 | 데이터 경계/평가/사람 승인 테스트 |

## 9. 프론트엔드 경험

| ID | 요구사항 | 상태 | 단계 | 기준 문서 | 최소 검증 |
|---|---|---:|---|---|---|
| REQ-UI-001 | Zendesk Agent Workspace와 유사한 고밀도 업무 UI를 제공한다 | IMPLEMENTATION_READY | M2~ | 28, 29, 30 | `frontend-system.spec.ts`의 Agent Home/View/Workspace 1280·1440·1920 Deskseed baseline |
| REQ-UI-002 | Views 목록과 티켓 테이블을 제공한다 | IMPLEMENTATION_READY | M2 | 28, 30 | `AgentViewsPage.test.tsx`, `FrontendSystem.test.tsx`, keyboard row-open E2E |
| REQ-UI-003 | 좌측 속성·중앙 대화·우측 context panel 구조를 제공한다 | IMPLEMENTATION_READY | M2 | 29, 30 | `frontend-system-workspace-{1280,1440,1920}.png` 및 keyboard separator E2E |
| REQ-UI-004 | 고객·앱·자식 티켓·외부 참조를 context panel에서 전환한다 | BLUEPRINT_READY | M5/I4/P7 | 28, 30 | 고객/로컬 기록/실제 parent-child projection·dialog 구현; 앱·external projection은 후속 |
| REQ-UI-005 | WCAG 2.2 AA 수준과 키보드 조작을 목표로 한다 | IMPLEMENTATION_READY | 전 단계 | 29, 35, 40 | `npm run test:e2e:dev`: 41/41, axe 0, dialog focus entry/trap/restore, child relation·solve warning 시각 회귀 |
| REQ-UI-006 | Zendesk 상표·로고를 복제하지 않고 독립 브랜드를 사용한다 | IMPLEMENTATION_READY | M0 | 29 | Deskseed fixture baseline, Garden import/notice 및 proprietary asset scan |

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
