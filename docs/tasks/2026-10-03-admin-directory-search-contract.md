# P15 — 직원·그룹·구성원 전체 검색 계약

Status: 승인된 blueprint. 이 PR은 계약만 정의하며 runtime은 구현하지 않는다.

## Goal

ADMIN이 직원 이름/이메일, 그룹 이름, 선택 그룹의 구성원과 추가 후보를 현재 페이지에 제한되지 않고 검색한다.

## Decision and source references

- REQ-PERM-002, REQ-AUD-004, REQ-UI-005. 기존 구현 상태를 낮추거나 새 검색 구현이 완료됐다고 표시하지 않는다.
- D-008/013/014/018/019/020/026/027/048/050/061; Accepted ADR-0018/0035/0037/0039/0044. 새 기술·운영 정책·권한은 추가하지 않는다.
- PRD의 ADMIN 조직 관리, docs19/23의 보호 검색어와 엄격한 감사 저장, docs31/33/34/39/52/55.
- operation: `searchAdminStaffAccounts`, `searchAdminGroups`; 기존 GET `listStaffAccounts`, `listGroups`, `listGroupMembers`는 변경하지 않는다.
- gate: ADMIN-SEARCH-001, ACC-002/003, SEARCH-AUD-001, UI-002/004/005의 해당 범위. ticket search-to-view ACC-004는 적용하지 않는다.

## Actor and source

- STAFF / ADMIN_UI, active ADMIN + 기존 staff session/CSRF/expected-actor guard.
- Security Auditor 또는 AGENT에게 별도 검색 권한을 만들지 않는다. ADMIN 인가 전에 그룹 존재를 조회하지 않는다.
- `memberOfGroupId`/`excludeGroupId`는 둘 중 하나만 허용한다. 기존 `activeGroup`처럼 nonexistent/disabled 모두 `ACTIVE_GROUP_NOT_FOUND` 404다.
- 각 명시적 검색과 결과 페이지 요청에 UUID `interactionId`를 둔다. 성공 응답마다 새 event ID로 감사하며 retry dedupe 또는 idempotency key로 오해하지 않는다.

## Product and UX contract

- 직원 `POST /api/v1/admin/staff/search`: 표시 이름 또는 이메일 literal substring, optional role/status, memberOfGroupId 또는 excludeGroupId, page/size. 기존 StaffAccount 응답으로 직원 관리·구성원 검색·추가 후보를 다룬다.
- 그룹 `POST /api/v1/admin/groups/search`: 이름 literal substring, optional status, page/size. 기존 Group 응답을 사용한다.
- query는 trim 후 nonblank, 원래 입력은 최대 254자이며 제어 문자 거부. %, _, 역슬래시를 SQL wildcard로 해석하지 않는다. 대소문자 무시; 내부 공백을 분리·토큰화하지 않는다.
- query와 정해진 필터는 전체 DB 범위에 적용한다. 직원은 displayName/id 오름차순, 그룹은 name/id 오름차순이다. page는 0 기반, size는 1~100(기본 50)이고 기존 `X-Page-*`, `X-Total-*` exact count 헤더를 유지한다.
- 한 응답의 count/page는 같은 DB snapshot에서 읽는다. 서로 다른 페이지 요청 사이의 동시 변경까지 고정하는 cursor/snapshot API는 추가하지 않는다.
- query가 비어 있으면 기존 GET 목록으로 복귀한다. 기존 구성원은 paged group members GET으로 복귀한다. 새 구성원 후보는 입력한 이름/이메일로 찾고 `status=ACTIVE` + `excludeGroupId`를 보낸다. 필터만으로 검색하는 새 의미나 가짜 암호화 query를 만들지 않는다.
- 검색은 명시 제출로 실행하고 필터/검색어 변경 시 첫 페이지로 돌아간다. loading/empty/error/denied를 표시하며 이전 범위 결과를 새 범위에 섞지 않는다. 순번이나 현재 페이지 내 filter로 전체 검색을 대체하지 않는다.
- 원문은 입력 중인 component state와 요청 body의 짧은 수명에만 둔다. URL/history/referrer, persistent query cache·query key, local/session storage, logs/APM/trace attributes에 넣지 않는다. 검색 결과도 `no-store`다.
- 이 계약 PR에는 UI/Storybook 변경이 없다. 구현 PR에서 직원/그룹/구성원 검색 키보드·1280/1440/1920·상태/페이지 경계 검증을 수행한다.

## Typed access/search audit blueprint

다음은 아직 runtime에 없는 **pending 확장**이며 기존 `getAuditActivity` 등 FROZEN operation의 현재 의미를 바꾸지 않는다. 구현 PR에서 migration·writer·projection·계약 회귀가 함께 통과할 때 승격한다.

- action: `ADMIN_STAFF_SEARCH_EXECUTED`, `ADMIN_GROUP_SEARCH_EXECUTED`. 두 action은 source `ADMIN_UI`, resource type `SEARCH`, resource/ticket/origin ID null을 강제한다.
- `AccessAuditWriter`에 admin search 전용 typed event/writer를 추가한다. 기존 ticket `SearchExecutedAccessAudit`와 ticket result-item membership을 재사용하지 않는다.
- 기존 `access_audit_events`, `search_audit_details`, `search_audit_query_ciphertexts`를 사용한다. 새 generic JSON ledger 또는 검색 엔진은 만들지 않는다.
- `SearchQueryProtector`의 `[PROTECTED]` routine marker, HMAC fingerprint, authenticated ciphertext/key version/expiresAt과 별도 session fingerprint를 재사용한다. 원문은 enum/metadata 컬럼에 넣지 않는다.
- normalized filters는 허용된 role/status/group UUID/page/size만 저장하고, 고정 sort와 전체 exact result count를 저장한다. `resultCountRelation=EXACT`; ticket/customer result-item 테이블에는 쓰지 않는다.
- 요청 actor/display snapshot/session/request/correlation과 interactionId를 canonical ledger에 남긴다. 필수 보호·감사 저장은 검색 read와 같은 transaction에서 완료하고 실패 시 stable `audit-write-unavailable` 503, 결과 미반환이다.
- 성공 검색이 의무 대상이다. 권한 거절·actor mismatch·잘못된 조건은 성공 검색 감사를 만들지 않으며 기존 security denial 경계를 보존한다. 새 실패/거절 event type은 추가하지 않는다.
- audit routine list/detail/export는 두 action의 안전한 metadata, `[PROTECTED]`, fingerprint, 필터/정렬/count만 노출한다. raw query는 금지한다. projection 변경도 이 pending 계약의 구현 범위다.
- 기존 exact reveal은 `SEARCH_EXECUTED` 한 건만 허용하는 계약을 유지한다. ADMIN 검색 ciphertext에 대한 reveal을 자동으로 확대하지 않는다. 조직 관리 ADMIN이 auditor projection을 읽을 새 권한도 없다.
- 기존 만료 job은 `search_audit_query_ciphertexts`의 expiresAt을 기준으로 동작하므로 새 저장소/retention job 없이 재사용한다. canonical metadata append-only와 기존 30일 ciphertext/180일 metadata 정책을 바꾸지 않는다.

## In scope / out of scope

- 이 PR: owned OpenAPI fragment·bundle·blueprint actor registry, 접근성/보안/페이지 회귀 gate와 데이터/감사 계약.
- 후속 P15 구현: PostgreSQL 검색, typed admin search writer, 최소 constraint migration, 안전한 projection, 직원/그룹/구성원 UI, API 상태 승격.
- 후속 P16은 같은 좁은 staff/group API와 감사 기반을 재사용한다. KB 제목/cursor는 P17에서 실제 draft/review/published projection을 확정한 뒤 별도 계약으로 추가한다.
- 제외: generic search/settings, KB 검색 operation 선등록, 새 권한, protected reveal 확대, 페이지 snapshot 보존, 배포·운영 데이터 변경.

## Invariants and failure semantics

- 그룹 소유권·멤버십·배정·audit 권한을 검색이 변경하지 않는다. 후보 반환 뒤 멤버십이 바뀌면 기존 추가 명령이 최종 불변조건을 검증한다.
- idempotency/If-Match는 읽기 POST에 요구하지 않는다. 재시도는 새 성공 검색 event를 만들 수 있다. 자동 polling 검색은 구현하지 않는다.
- 네트워크 외부 I/O/outbox 없음. 새 rate limiter 정책 없이 bounded request/page와 기존 인증 경계를 사용한다. 성능 지표를 추정하지 않는다.
- 오류와 필드 검증 응답에 query 원문을 포함하지 않는다. 입력 bound를 검증한 뒤 SQL binding/보호 함수로 전달한다.

## Acceptance scenarios

1. 100명·100그룹보다 뒤에 있는 이름/이메일을 검색해 전체 count와 정렬된 페이지에서 찾는다. SQL wildcard 문자는 literal로 동작한다.
2. 선택 그룹의 ACTIVE 구성원 검색은 그 그룹에 속한 직원만, 추가 후보는 활성 직원 중 그 관계가 없는 직원만 반환한다. 반대/동시 group filter, 미존재·비활성 그룹, role/status 조건을 검증한다.
3. AGENT/SECURITY_AUDITOR/anonymous, invalid/mismatched actor, CSRF 실패는 query 실행·성공 검색 감사 전에 거부한다.
4. query 보호 또는 canonical 감사 insert 실패 시 503이며 검색 결과·원문이 오류/log에 존재하지 않는다. transaction에는 부분 감사 row가 남지 않는다.
5. 두 성공 action에 marker/fingerprint/ciphertext/context/exact count를 저장하고 ticket origin/result rows는 생성하지 않는다. raw query의 routine UI/export/reveal 누출을 검증한다.
6. ciphertext 만료가 새 action에도 적용되고 canonical metadata update/delete는 runtime role로 실패한다. 기존 ticket/customer 검색·origin·reveal 회귀는 유지한다.

## Validation and completion report

- 계약 검증: `make docs-check` 통과(bundle 1개, API documentation 36개, validator 2개, semantic validator PASS), `git diff --check` 통과.
- 구현 전에는 backend/브라우저/Storybook 테스트와 성능 측정을 실행하지 않는다. 두 operation은 `FROZEN`을 부여하지 않고 actor blueprint registry에 등록한다.
- 이 PR은 신규 endpoint·event·migration을 운영에 제공하지 않는다. 기존 endpoint와 projection은 호환된다. 구현 PR에서 additive migration과 rollback 경계를 별도 검증한다.
- Human trade-off: 간단한 PostgreSQL directory 검색을 전체 범위에 적용하면서 민감 이름/이메일의 암호화 감사 경계를 기존 인프라로 지킨다. 유일한 새 감사 의미는 관리자 검색 action이며 티켓 검색으로 위장하지 않는다.
