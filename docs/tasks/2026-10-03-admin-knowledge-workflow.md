# P17 — 관리자 지식 문서 전체 검색과 편집 보호

## Goal and references

ADMIN이 저장된 제목으로 전체 KB를 찾고 상태·섹션·대상 필터와 cursor를 유지하며 편집 초안을 잃지 않는다. [P17 승인 계약](2026-10-03-admin-knowledge-search-contract.md)의 REQ-KB-001/REQ-AUD-004/REQ-UI-005, D-018/019/020/048/061, ADR-0018/0033/0037/0044와 KB-001/ADMIN-KB-SEARCH-001/UI-002/004/005를 적용한다.

## Scope, actor and invariants

`searchAdminKnowledgeArticles`와 기존 GET 목록 latestRevision title/summary, 직원 앱 검색/필터/상세 포커스/dirty 보호를 수직 구현한다. Active ADMIN, ADMIN_UI + STAFF_SESSION/CSRF/expected actor만 허용한다. query/cursor는 POST body·일시 메모리이며 cache key/URL/storage/log에 두지 않는다. 최신 제목은 본문 없는 별도 projection이고 currentPublishedRevision/고객·상담사 API의 의미는 유지한다.

같은 REPEATABLE_READ에서 전체 EXACT count와 keyset 결과를 읽고 필수 보호 감사와 원자적으로 commit한다. 실패는 503이고 검색색인/발행/티켓 state·outbox·외부 I/O는 변경하지 않는다. 기존 raw-query protection·append-only/expiry를 재사용하며 reveal 대상은 확대하지 않는다. 검색 retry마다 새로운 interaction과 감사이며 쓰기 명령의 If-Match·불변조건은 유지한다.

V100은 기존 audit CHECK 확장만 하며 새 엔진·index·table·seed·backfill은 없다. 앱 rollback 시 확장 CHECK 유지 가능, append-only event 삭제 없음. migration/performance·권한/개인정보·브라우저 회귀와 UI 계약 확인은 아래에 근거를 기록한다. 완료 전까지 runtime/Storybook/운영 검증 통과를 주장하지 않는다.

## Verification — recovered worktree

- Backend `fastTest contractTest migrationTest integrationTest --tests '*AdminKnowledge*IntegrationTest' --tests '*AdminDirectorySearchIntegrationTest'`: PASS. 복구 경로의 입력 해시가 이전 실행과 일치하여 Gradle cache에서 fast 147, contract 25, migration 39, integration 15개 테스트 결과를 복원했다. 새 프로세스에서 테스트를 다시 실행한 결과와 구분한다. 기존 실제 실행 로그는 `/private/tmp/p17-backend-final.log`, 복구 로그는 `/private/tmp/p17-recovery-backend.log`이다.
- Frontend 새 경로 `typecheck`, staff unit 43 files/257 tests, staff build, 변경 파일 ESLint/Prettier, DS boundary 4 tests+scanner, OpenAPI/MSW fixture: PASS. 초기에 혼합 부하로 실패한 티켓 편집 unit은 소스 변경 없이 단일 worker에서 통과했고, 복구 뒤 같은 방식으로 다시 통과했다.
- 실제 staff Storybook HTTP MCP의 tools/list, list-all-documentation, get-storybook-story-instructions, get-documentation을 호출했다. Seed 입력/버튼/알림과 DsDrawer를 재사용하며 새 DS API는 없다. focused admin/agent knowledge 16개와 full 61 files/302 stories 모두 PASS 응답 JSON을 확인했다(`/private/tmp/p17-recovery-focused.log`, `/private/tmp/p17-recovery-full.log`). 변경 metadata의 coverage 경고는 AdminKnowledgePage/AgentKnowledgePanel의 실제 consumer discovery로 보완했다.
- Chromium 4 tests PASS: 1280/1440/1920의 전체 제목 검색·조건 변경 cursor reset·query URL/storage 미기록·50번째 문서 선택/닫기 focus·overflow, 1440 axe 0 violations, dirty close/filter/back/beforeunload·저장 중 route 잠금·412 후 입력 보존. E2E의 요청 기록 검증은 화면 state 전환과 별개인 요청 도착까지 기다리도록 안정화했다. 세 폭 캡처를 직접 열어 겹침/잘림을 확인했다. `frontend/test-results/admin-knowledge-workflow-*`는 로컬 증거이고 고정 시각 baseline으로 승격하지 않았다.
- 새 h1/h2 크기는 knowledge-admin 아래로 한정해 상담사 문서 상세의 기존 표시를 유지한다. 공용 AdminShell이나 다른 앱 토큰을 변경하지 않았다. `useAdminDraftExit`는 P16/P18과 동일 SHA1 `99e352b8079d5114a63a5510456a690c7a7e8853`이다.
- `make docs-check`: PASS. 원격 CI 상태는 최종 handoff에서 별도 확인한다. merge/deploy, production 실사용·성능 부하, Safari/Firefox, 실제 데이터/메일/권한 변경은 실행하지 않았다. substring+전체 COUNT 비용을 감수하며 새 engine/index 없이 기존 50개 keyset을 유지한 것이 핵심 trade-off다.

## Storybook previews

- http://localhost:6123/?statuses=affected;modified;new
- http://localhost:6123/?path=/story/07-screens-admin-knowledge--title-search-and-filters
- http://localhost:6123/?path=/story/07-screens-admin-knowledge--draft-exit-protection
- http://localhost:6123/?path=/story/07-screens-admin-knowledge--pending-save-preserves-input
- http://localhost:6123/?path=/story/07-screens-admin-knowledge--create-draft
- http://localhost:6123/?path=/story/07-screens-agent-knowledge--search-read-insert

## Recovery checkpoint — 2026-10-03

작업 디렉터리 소실 후 538b671e 계약 커밋에서 새 managed worktree를 만들고, 보존된 자체 세션의 apply_patch 5개와 순차 수정 스크립트 12개를 새 경로에 재적용했다. 복구 스크립트는 /private/tmp/p17-recovery/에 남긴다. 이전 원격 PR #264는 유지한다. 초기 체크포인트는 검증 완료 선언이 아니며 새 경로에서 검증한 결과를 위에 별도로 기록했다.

소실 전 로그에는 backend fast/contract/migration/관리자 KB·직원 검색 integration BUILD SUCCESSFUL, 직원 unit 43 files/257 tests, focused MCP 5 stories, Chromium 4 tests(1280/1440/1920·axe·route/back/pending)가 기록되어 있다. 이전 증거를 복구 후 재검증과 구분하며, 소실된 test-results 캡처는 위 Chromium 검증에서 재생성했다.
