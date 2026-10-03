# P02 고객 동의 정책 관리와 가입 복구

## Goal / scenario

ADMIN이 빈 정책부터 초안을 작성하고 검토 후 발행·개정·보관하여 고객 가입에 필요한 현재 동의 정책을 준비한다. 고객은 약관 미준비·조회 오류에서 재확인/홈 복귀하고 변경된 정책에 다시 동의한다. C-F02와 ADM-F07의 동의 준비 부분이다. 실제 법률 문구 작성·seed·배포 정책 변경은 하지 않는다.

## References / contract

REQ-CONSENT-001/002, REQ-AUTH-003/004; D-058, docs 25/26/30/31/32/33/34/39/40/51/56, Accepted ADR 0006/0042/0044와 기존 독립 앱 경계를 따른다. FROZEN list/create/get/update/publish/archiveCustomerConsentPolicy 및 listCurrentCustomerConsentPolicies만 사용한다. 기존 HTTP/schema 계약 변화 없음.

## Actor / invariants

ADMIN_UI의 현재 staff session + ADMIN/customer-consent:manage, expected-actor, CSRF, create If-None-Match:*와 update/publish/archive의 aggregateVersion If-Match를 기존 requestStaffResource로 전달한다. 서버가 최종 권한/문서/시점/현재정책수 및 lifecycle을 검증한다. 불변 published versions/history와 acceptance는 수정하지 않는다. metadata-only admin audit와 command가 함께 commit/rollback하는 경계, CUSTOMER_PORTAL의 current-version acceptance 원자성은 변경하지 않는다.

## UI / reuse plan

기존 staff DsButton/Notification/ScreenState와 admin form/surface/table 구성을 재사용한다. 문서는 consent의 7개 허용 block 타입에 맞는 관리 폼으로 편집하며 raw HTML/JSON 입력이나 법률 template는 제공하지 않는다. 신규 디자인 시스템 API·타 앱 컴포넌트 복사·인프라는 없다. 고객은 독립 customer DS의 기존 ScreenState/RetryButton/Link 조합을 사용한다.

## Failure / privacy

loading/empty/error/denied와 409/412 충돌, mutation pending/성공/불확실 응답을 표시한다. pending은 중복 action을 막고 draft를 잠근다. 실패 시 draft를 유지하고 불확실 또는 stale 응답 후에는 최신 상태 조회·비교·명시적 선택 뒤 다시 저장한다. 발행/보관은 별도 확인한다. 재시도 자동 실행 없음. 폼 본문·password/token을 log/storage에 저장하지 않고 안전한 text/HTTPS 렌더만 사용한다. 기존 retention·transaction·외부 I/O 의미 변경 없음.

## Acceptance / verification

- 신규정책 생성→초안저장→명시적 발행→현재 버전/과거 불변 버전 조회→보관. context/key는 생성 이후 변경할 수 없다.
- API 세션/actor/CSRF/If-None-Match/If-Match 검증, malformed projection 거부, 403/412/503에서 입력 보존, 최신값을 읽어도 자동 덮어쓰기하지 않는다.
- 고객 약관 빈 목록/조회 오류에서 재확인·홈 이동, 정책 변경시 입력 보존·동의 초기화.
- CONSENT-001/002의 frontend 경계, UI-002/004/005/006; 양 앱 MCP 문서/지침·focused/full story/a11y·preview, unit/typecheck/build/boundaries, mock full-page E2E·키보드/뷰포트 검증.

실제 backend DB의 audit rollback/동시성/정책 cap·SMTP·배포는 이번 frontend slice에서 재검증하지 않는다. Migration/backfill/의존성 추가 없음. UI commit revert로 복구하며 성능 수치 주장은 없다.

## Completion

구현: `/admin/customer-consent-policies`와 관리자 메뉴, 7개 canonical block 편집/안전한 조회, 상태·context 필터/페이지, 생성·수정·발행·보관/불변 이력을 연결했다. 고객 가입은 빈 정책·조회 오류에서 수동 재확인과 홈 복귀를 제공한다. 정책 key/context는 생성 후 잠그고 법률 내용을 대신 결정하지 않는다. 문서 입력은 발행 가능한 50,000자/200,000바이트까지 허용하며, 더 큰 기존 초안은 읽어서 줄일 수 있다.

검증 결과:

- Passed: customer unit 121개/28파일, staff unit 258개/44파일 (`--maxWorkers=2`). 초기 병렬 실행 중 기존 비동기 테스트 4개가 시간 초과했고 제한된 worker의 전체 재실행에서 통과했다. 마지막 문서 검증 수정 후 API unit 3개 재통과.
- Passed: 양 앱 typecheck/build, design-system boundaries, `contract:check`, 변경 파일 ESLint/Prettier, `git diff --check`. 기존 staff bundle 크기 warning은 남는다.
- Passed: 실제 Deskseed MCP 문서/지침 조회 후 customer full 74개, staff full 299개 story 및 a11y. 초기 다른 검증과 동시 실행한 staff full의 기존 workspace story 시간 초과는 단독 full 재실행에서 모두 통과했다. `get-changed-stories`의 root/타 앱 coverage gap은 `get-stories-by-component`로 소비자를 확인했다.
- Passed: mock Chromium E2E 4개. 관리자 1280·1448에서 생성→명시적 발행→이력→보관, 고객 390·768에서 약관 미준비→키보드 재확인→미동의 상태를 확인했다. 모든 페이지 a11y/가로 넘침 검사 통과. `frontend/test-results/customer-consent-managemen-*/policy-*.png`를 생성했고 대표 desktop/mobile 캡처를 직접 확인했다.
- Not run: 실제 서버 정책 mutation·backend DB/감사 rollback·정책 cap·SMTP·배포, Firefox/WebKit, 기준 이미지 픽셀 비교. 로컬 합성 검증을 운영 결과로 간주하지 않는다. 법률 문구·seed·운영 정책 변경 없음.

MCP preview (staff 5개/customer 3개 대표; 나머지는 changed-stories 필터로 조회):

- http://localhost:6009/?path=/story/06-admin-admin-customer-consent-page--create-publish-archive
- http://localhost:6009/?path=/story/06-admin-admin-customer-consent-page--conflict-preserves-draft
- http://localhost:6009/?path=/story/06-admin-admin-customer-consent-page--uncertain-save
- http://localhost:6009/?path=/story/06-admin-customer-consent-document-fields--read-only-document
- http://localhost:6009/?path=/story/05-shells-layouts-adminshell--mail-operations
- http://localhost:6007/?path=/story/customer-portal-onboarding-pages--policies-not-ready
- http://localhost:6007/?path=/story/customer-portal-onboarding-pages--policies-unavailable
- http://localhost:6007/?path=/story/customer-portal-onboarding-pages--changed-policy-recovery
- http://localhost:6009/?statuses=affected;modified;new
- http://localhost:6007/?statuses=affected;modified;new

CONSENT-001/002의 frontend 경계와 UI-002/004/005/006을 위 근거로 확인했다. 실제 서버 gate 전체의 재실행이나 운영 준비 완료를 주장하지 않는다. migration/계약/의존성 변화 없이 UI commit revert로 복구한다.
