# P01 고객 접수 필드 오류와 입력 복구

## Goal / actor

CUSTOMER_PORTAL 고객이 접수의 확정적인 400 필드 오류를 해당 입력 옆에서 읽고 수정해 다시 제출한다. C-F01의 관찰된 접수 실패 원인 자체는 배포 서버 증거가 없어 미확정이다.

## Decisions / contracts / gates

- REQ-TKT-001/002, REQ-CFG-014; ADR 0006/0041, D-059; docs 30/31/37/40/51/56 및 기존 ticket-form-runtime task를 따른다.
- 기존 JSON/multipart `createCustomerRequest`, `Problem.fieldErrors`와 `FieldError.field/message` 계약을 사용한다. Core HTTP/schema 변경 없음.
- UI-002/004/005/006, CFG-006/TKT-001/002의 기존 frontend 경계 회귀. 서버 gate를 새로 충족한다고 주장하지 않는다.

## Scope / reuse plan

- Reuse: Storybook에서 확인한 DsButton/Notification, 기존 CustomerField 오류 표시.
- Compose: DTO의 requester.name/requester.email/subject/message를 현재 editable 고객 입력에만 연결. raw 알 수 없는 경로/메시지는 렌더하지 않는다.
- Extend/Add: 디자인 시스템 API·의존성 없음. 필드 오류의 aria-invalid/description과 첫 오류 focus를 연결한다.
- 현재 backend custom form validation은 필드 정보 없이 existence-safe 400을 반환한다. 없는 custom field 상세를 추정하거나 내부 필드 정보를 노출하지 않는다.
- generic 400을 설정 오류로 바꾸지 않는다. 실제 접수 실패 서버 원인, 동의 관리, 새 오류 계약은 범위 밖이다.

## Invariants / privacy / failure semantics

입력/첨부/동의·조건부 값은 실패 후 유지한다. 확정 400 뒤 수정은 새 command를 만들고, 기존 5xx/network 불확실 상태는 원래 command ID/payload/첨부를 고정한 채 재확인한다. 후속 확정 오류가 최초 성공 여부를 증명하지 않으므로 uncertain은 유지하며 필드 수정 안내를 새로 붙이지 않는다. 자동 retry 없음.

ticket의 최초 PUBLIC comment, server-authorized projection, token/session/CSRF, actor/source/request/audit, transaction·receipt/동의 원자성은 변화 없다. raw body/token을 log/storage/audit에 추가하지 않는다. 알려진 표시 메시지는 React text로만 렌더하며 HTML 해석하지 않는다. migration/backfill/retention/외부 I/O 변화 없음.

## Acceptance / verification

1. Given 400에 requester.email/subject 오류, When 제출, Then 두 입력의 오류·aria 연결과 첫 오류 focus를 보여주고 입력·첨부를 유지한다.
2. When 한 입력을 수정, Then 그 입력의 서버 오류만 지운다. 재제출 성공 시 수정된 payload와 새 command를 사용한다.
3. Given generic/알 수 없는 field path 400, Then 기존 공통 안내/request ID를 유지하고 설정 변경으로 추정하지 않는다.
4. Given network→400→성공, Then 모든 시도의 command/payload/첨부가 같고 편집은 잠긴다.

MCP 문서/instructions, focused/full run-story-tests + a11y, get-changed-stories/preview, customer unit/typecheck/build/boundaries, 관련 lint/format, mock complete-page 실패→수정 흐름을 검증한다. 실제 backend·SMTP·배포는 미실행, 성능 수치 주장 없음. rollback은 UI commit revert다.

## Completion

- Passed: 변경 전 새 필드 연결/포커스 unit 회귀 실패를 확인한 뒤, 수정 후 customer unit 28 files / 122 tests 통과. network→429 및 network→400에서도 명령/본문/첨부 보존을 검증했다.
- Passed: Customer Storybook MCP 전체 74 stories + a11y, 최종 focused 3 stories, `get-changed-stories`와 `get-stories-by-component`로 13 consumers 확인 및 preview.
- Passed: typecheck, customer build, design-system boundaries, 변경 파일 ESLint/Prettier 및 diff check. 테스트의 잘못된 쿼리 옵션으로 타입 검사 1회 실패 후 수정하여 통과했다.
- Passed: mock Chromium 전체 페이지에서 실제 API decoder→필드 오류→입력 수정→새 명령→완료 라우트 흐름 390×844/1448×1086 2 tests. 전체 페이지 PNG를 직접 확인했다.
- Not run: 실제 backend/SMTP/DB·배포, 다른 브라우저 엔진. 알 수 없는/미표시 필드 및 필드 정보 없는 오류의 구체 원인은 추가하지 않았다.

requirement status와 기존 API/audit 의미를 바꾸지 않는 표시 연결이 핵심 trade-off다. 미리보기는 로컬 서버 실행 중 사용할 수 있다.

- http://localhost:6007/?statuses=affected;modified;new
- http://localhost:6007/?path=/story/06-customer-customer-request-form--server-field-errors
- http://localhost:6007/?path=/story/06-customer-customer-request-form--generic-validation-failure
- http://localhost:6007/?path=/story/06-customer-customer-request-form--ambiguous-retry-keeps-payload
