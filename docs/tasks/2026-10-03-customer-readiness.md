# P07 공지 복구와 고객 포털 준비 확인

## Goal / actor / sources

고객은 공지 미등록·오류에서도 도움말 탐색을 이어가고, ADMIN은 고객 접근 모드 화면에서 가입 약관과 공개 공지 준비 상태 및 수정 경로를 확인한다. C-F08/ADM-F07, REQ-KB-001/004, REQ-CONSENT-001, REQ-AUTH-003, REQ-UI-005/007. D-058/D-061, Accepted ADR-0018/0040/0042/0044와 docs 26/28~34/39/40/51/55/56의 기존 KB·고객 동의·독립 앱 경계를 따른다.

기존 getHelpSection, getCustomerAccessModeSetting, listCustomerConsentPolicies, listKnowledgeCategories/Sections/Articles만 사용한다. article의 기존 sectionId/audience 필터를 클라이언트에 연결한다. API/권한/cursor 의미 변화 없음. ADMIN_UI staff session/expected actor 및 required access audit는 서버가 소유한다. 고객은 help audience projection만 받는다.

## Scope / reuse

고객 home와 announcements section의 404·200 빈 목록은 안전한 빈 상태, 그 밖의 오류는 수동 재확인과 탐색 링크를 제공한다. 관리자 준비 확인은 저장된 접근 모드와 발행된 가입 정책 metadata, 활성 announcements 섹션·부모 주제 및 PUBLIC 발행 문서 존재 여부를 조합한다. 명시적인 확인 버튼으로 조회하며 준비 상태 확인이 설정을 변경하지 않는다.

Reuse/Compose: 각 앱의 문서화된 DsButton/ScreenState/Notification/RetryButton 및 기존 page/surface/form/Link. 신규 DS API/인프라/법률 문구/seed 없음. P02 위에 적층한다.

## Failure / boundaries

loading/empty/error/denied/성공을 구분하고 조회 오류를 미설정으로 단정하지 않는다. 공지 404는 고객에게 숨겨진 문서·운영 구성을 노출하지 않는다. 재확인은 읽기만 수행하고 자동 polling은 없다. 설정 수정·동시성·idempotency/transaction·audit/retention 의미 변화 없음. 관리자 검사는 가입·공지의 설정 snapshot이며 메일 전달이나 실제 고객의 E2E 성공을 보장하지 않는다. 개인정보/문서 본문을 저장하거나 log에 남기지 않는다.

## Acceptance / validation

- 홈·전체 공지 404와 200 empty에서 빈 상태 및 문서 탐색 가능. 503은 오류/재확인 후 성공. stale 실패에서 오래된 공지를 성공처럼 노출하지 않는다.
- 관리자 미조회→명시적 확인→약관 없음/비활성 부모·섹션/공개 발행 문서 없음/준비됨/403/503를 검증한다. sectionId/PUBLIC/PUBLISHED query filter와 read-only 요청 확인.
- UI-002/004/005/006; 양 앱 MCP 문서/지침, focused/full story/a11y/preview, unit/type/build/boundaries/contract/lint/format, mock full-page E2E와 390/768/1448 시각·키보드/a11y.

## Compatibility / limits

Migration/backfill/계약/의존성 변경 없음. UI revert로 복구. 실제 서버 권한/감사 rollback·SMTP·운영 설정/배포·Firefox/WebKit·pixel baseline 비교는 범위 밖이며 별도 보고한다. read 요청 수를 고정된 작은 snapshot으로 제한하고 성능 수치 주장은 하지 않는다.

## Completion evidence

Passed: customer unit 121/28 files, staff unit 258/44 files (`--maxWorkers=2`), typecheck, 양 앱 build, boundaries, contract:check, 변경 파일 lint/format, diff check. 기존 staff bundle 크기 warning은 남는다. 실제 MCP customer full 78개·staff full 310개 story/a11y 통과. get-changed-stories의 app/root coverage 경고는 get-stories-by-component와 전체 실행으로 확인했다.

Mock Chromium E2E 3개 통과: 고객 390/768에서 홈404→전체 공지→503→키보드 재시도→200 empty, 관리자1448에서 명시적 확인 전0추가조회→GET-only/sectionId·audience·lifecycle 필터→503 구분→재확인 성공. 초기 E2E의 관리자 경로와 로컬 /_staff basename 기대값을 실제 route에 맞춰 수정한 뒤 재실행했다. axe·가로 넘침 검사 통과 및 `frontend/test-results/customer-readiness-*/` 대표390/1448 캡처 직접 확인. 실제 서버 mutation/audit/SMTP/배포·Firefox/WebKit·pixel baseline 비교는 실행하지 않았다.

MCP previews (전체 변경 fallback 포함):

- http://localhost:6007/?statuses=affected;modified;new
- http://localhost:6014/?statuses=affected;modified;new
- http://localhost:6007/?path=/story/customer-portal-help-center-pages--home-announcements-not-found
- http://localhost:6007/?path=/story/customer-portal-help-center-pages--announcements-not-found
- http://localhost:6007/?path=/story/customer-portal-help-center-pages--announcements-failure-recovery
- http://localhost:6014/?path=/story/06-admin-admin-customer-readiness-panel--missing-configuration
- http://localhost:6014/?path=/story/06-admin-admin-customer-readiness-panel--ready
- http://localhost:6014/?path=/story/06-admin-admin-customer-readiness-panel--denied
- http://localhost:6014/?path=/story/06-admin-admin-customer-access-mode-page--save-policy

### 통합 E2E 후속 보완

선택 환경변수 `PLAYWRIGHT_CUSTOMER_BASE_URL`이 없는 기본 CI/dev 환경에서도 `/`로 이동하도록 빈 문자열 fallback을 추가했다. 제품 코드 변경은 없다. 해당 변수를 지정하지 않고 기존 고객 dev 서버(45285)를 baseURL로 사용한 `customer-readiness.spec.ts --grep 'customer announcements'` 390/768 두 시나리오와 변경 파일 lint/format 통과.

### 최종 고객 여정 검수 보완

공지 첫 페이지를 읽은 뒤 추가 페이지가 404로 바뀌면 빈 상태와 이전 성공 목록이 함께 표시되던 조건을 수정했다. 이전 목록과 더 보기를 숨기고 공지 제목·재확인·홈 복귀는 유지한다. 키보드 재확인이 성공하면 목록으로 돌아온다. REQ-KB-001/004, UI-004/005/006 범위의 기존 error 계약 처리이며 숨겨진 문서나 운영 구성 상세는 노출하지 않는다.

- Passed: 관련 MCP story 4개+a11y, 변경/소비자 조회와 preview, mock Chromium 고객 E2E 3개(390/768 초기404·503 및390 추가페이지404·키보드복구), 타입검사, 변경 파일 lint/format, `git diff --check`. 새390 캡처에서 빈 상태와 기존 목록이 중복되지 않고 제목/복구 경로가 남는 것을 확인했다.
- P02 최신 보완(가입 정책 미준비 안내·로그인 복귀)을 먼저 병합하여 PR 기반을 최신화했다. 실제 서버 상태변경·배포·SMTP·Firefox/WebKit·pixel baseline 비교는 재실행하지 않았다.
- http://localhost:6024/?statuses=affected;modified;new
- http://localhost:6024/?path=/story/customer-portal-help-center-pages--announcements-removed-during-pagination
- Passed: P02를 포함한 고객 full MCP 79개+a11y. 최초 reused Storybook의 full 실행은 결과 반환이 멈춰 완료로 간주하지 않았고, 소유 서버만 재시작한 fresh 실행이 21파일/79개 모두 통과 결과를 반환했다.
