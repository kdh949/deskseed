# P15 — 관리자 directory 검색 구현

## Goal and contract

계약 PR #250의 [승인된 P15 계약](2026-10-03-admin-directory-search-contract.md)을 한 수직 슬라이스로 구현한다. 직원·그룹·구성원·추가 후보를 전체 범위에서 검색하고 보호 검색 감사 없이 성공 응답하지 않는다.

REQ-PERM-002/REQ-AUD-004/REQ-UI-005, ADMIN-SEARCH-001/ACC-002/ACC-003/SEARCH-AUD-001/UI-002/004/005. Decision/ADR, actor/source, 데이터 경계, 권한, 동시성·실패·retention 계약은 선행 brief와 동일하다. query는 component state/POST body에만 두고 persistent cache key에는 넣지 않는다.

## Scope

- 기존 organization 목록 projection과 페이지 헤더를 재사용하는 두 POST 검색 API.
- 기존 보호 함수·검색 detail/ciphertext·retention을 재사용하는 typed admin audit writer와 V99 constraint 확장.
- 직원/그룹 및 구성원/추가 후보의 검색·상태·페이지·키보드 UI.
- backend PostgreSQL 권한/페이지/감사 실패/privacy 회귀, frontend unit·Storybook MCP·Playwright 검증 후 두 operation을 FROZEN으로 승격.

## Constraints

그룹/멤버십/티켓 불변조건과 권한을 변경하지 않는다. query는 literal substring이며 SQL wildcard/control character를 다룬다. 성공마다 접근 감사 event를 남기며 idempotency/reveal/ticket origin을 추가하지 않는다. KB 및 generic search/settings는 제외한다. 운영 데이터 변경·merge/deploy는 하지 않는다.

## Actor, invariants and data boundary

- STAFF/ADMIN_UI, active ADMIN, 기존 CSRF/expected actor 검증을 사용한다. 그룹 포함/제외 조건은 상호 배타적이고 인가 뒤 ACTIVE 그룹을 확인한다. 공개·고객 API에는 추가하지 않는다.
- 이름/이메일과 그룹 이름을 literal substring으로 전체 PostgreSQL 범위에서 검색한다. 동일 요청은 REPEATABLE_READ에서 count/page/projection을 읽고 보호 감사까지 commit한다. 페이지 사이 snapshot 보존이나 membership 명령의 사전 성공 보장은 없다.
- 검색 성공마다 두 typed action 중 하나를 access/search ledger에 기록한다. 보호 또는 필수 저장 실패는 결과 없는 503이며 부분 감사도 rollback한다. interactionId는 요청별 UUID이고 retry dedupe가 아니다. window focus/reconnect polling은 사용하지 않는다.
- 그룹/직원/티켓 상태, 배정, 소유권, 기존 명령 권한을 변경하지 않는다. 외부 I/O, outbox, idempotency 키, 신규 권한은 없다.
- `[PROTECTED]`, HMAC fingerprint, ciphertext/key version/expiry와 기존 append-only/retention을 재사용한다. URL/cache key/storage/log/error/projection에 원문을 추가하지 않는다. exact reveal은 기존 SEARCH_EXECUTED만 허용하며 관리자 검색은 `protectedContentAvailable=false`다.

## UX and reuse

`DsButton`, `ScreenState`, `RetryButton`, `Notification`과 기존 관리자 양식/table 구성을 재사용한다. DsButton의 이미 존재하던 네이티브 type/onClick을 FormActions story로 명시했으며 런타임 API·스타일·토큰 확장은 없다. 신규 공용 컴포넌트도 없다.

직원/그룹 검색과 구성원/추가 후보 검색은 명시 제출, 조건 변경 시 첫 페이지, 전체 결과 수, 빈 결과·로딩·오류·권한 상태를 가진다. 기존 구성원 GET에도 누락된 페이지 이동을 제공한다. 검색 실행 중 생성/편집 입력을 유지하며 그룹 전환 때 다른 그룹 검색·선택 후보를 지운다. 검색어는 opaque key를 가진 component state에 두고 검색 cache는 gcTime 0으로 제거한다.

목록/상세 배치, 생성 양식 접기, 그룹 오류 위치와 미저장 보호, 역할 표시는 후속 P16이며 이 PR에서 완료됐다고 주장하지 않는다. KB/태그 검색·폼 미리보기는 별도 P17/P19다.

## Validation

- ADMIN-SEARCH-001/ACC-002/003/SEARCH-AUD-001: PostgreSQL `AdminDirectorySearchIntegrationTest` 5개 PASS. 직원·그룹 110개 전체 페이지/정렬/count, literal wildcard와 email case, membership/role/status, inactive/missing 그룹, 권한/CSRF/actor, query validation, ciphertext 실패 시 rollback/503, 보호 metadata·30일 expiry·append-only·원문 비노출·reveal 거부 확인.
- 기존 `AdminOrganizationIntegrationTest`, `AuditExplorerIntegrationTest`, `AgentCustomerSearchIntegrationTest` PASS. fastTest 146개, contractTest 25개, migrationTest 39개 PASS. FROZEN 승격 후 contractTest 재실행 PASS.
- UI-002/004/005: staff unit 43 files/257 tests PASS, typecheck/build:staff, 변경 파일 ESLint/Prettier, design-system-boundaries 4 tests+검사, P1 contract check PASS.
- Storybook 6010 MCP tools/list → list-all-documentation → instructions → 개별 API 문서 실제 조회. focused 11 stories 및 전체 staff story-test/a11y PASS. get-changed-stories의 공유 파일 coverage gap은 AdminStaffPage/AdminGroupsPage/버튼 story 소비자를 get-stories-by-component로 재조회하고 전체 suite로 검증했다.
- Chromium Playwright 1280×900/1440×900/1920×900 3개 PASS. 전체 검색 페이지·구성원 페이지·추가 후보 범위·CSRF/actor·request별 interactionId·URL/storage privacy·가로 overflow 확인. 1440 axe 위반 0. full-page 캡처 3개 직접 열어 확인. CI `test:e2e:dev`에 등록했다.
- 최초 E2E는 독립 staff dev server에 `/admin`을 사용해 Vite base 안내 페이지에서 실패했다. `PLAYWRIGHT_STAFF_PATH_PREFIX=/_staff`로 독립 앱 경로를 지정한 재실행은 통과했으며 CI의 통합 경로 기본값은 그대로다.
- docs-check: bundle 1/API documentation 36/validator 2/semantic PASS. 생성 outline은 source fragment/base로 재생성한다.
- 미실행: 전체 backend integration/slow, production browser·실데이터·메일, Firefox/WebKit, Linux 이미지 baseline 갱신, 고객 앱 별도 build, 대규모 query latency/EXPLAIN. CI 결과는 PR에서 별도 확인한다. 기존 staff 번들 500 kB 경고가 남아 있다.

## Compatibility, migration and trade-off

두 POST operation을 FROZEN으로 승격한다. 기존 GET/명령 응답과 Audit Explorer wire shape는 호환된다. V99는 기존 access source/action/shape CHECK만 확장하고 새 관리자 action의 STAFF/ADMIN_UI/session/SUCCEEDED/200 조건을 강제한다. 테이블·인덱스·backfill·job 추가는 없다. 앱을 되돌릴 때 V99는 유지할 수 있지만 새 canonical event가 생긴 뒤 constraint를 단순 축소하면 안 된다. 기존 감사 row 삭제로 rollback하지 않는다.

간단한 PostgreSQL substring 검색에 정확한 전체 수와 기존 보호 감사 경계를 더한 최소안이다. 110개 fixture는 기능 증거이며 운영 성능 증거가 아니다. 광범위한 데이터에서 측정한 latency/계획이 필요성을 입증하기 전에는 새 검색 엔진이나 인덱스를 추가하지 않는다.

## Storybook previews

P20의 작업 트리/6008을 보존하고 이 작업은 6010을 사용한다.

- [전체 변경](http://localhost:6010/?statuses=affected;modified;new)
- [직원 전체 검색](http://localhost:6010/?path=/story/06-admin-admin-staff-page--directory-search)
- [그룹·구성원·후보 검색](http://localhost:6010/?path=/story/06-admin-admin-groups-page--directory-and-membership-search)
- [구성원 페이지](http://localhost:6010/?path=/story/06-admin-admin-groups-page--member-pagination)
- [검색 실패 복구](http://localhost:6010/?path=/story/06-admin-admin-staff-page--search-error)
- [버튼 폼 사용](http://localhost:6010/?path=/story/02-primitives-dsbutton--form-actions)
