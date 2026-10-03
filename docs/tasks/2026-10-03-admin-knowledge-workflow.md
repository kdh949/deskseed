# P17 — 관리자 지식 문서 전체 검색과 편집 보호

## Goal and references

ADMIN이 저장된 제목으로 전체 KB를 찾고 상태·섹션·대상 필터와 cursor를 유지하며 편집 초안을 잃지 않는다. [P17 승인 계약](2026-10-03-admin-knowledge-search-contract.md)의 REQ-KB-001/REQ-AUD-004/REQ-UI-005, D-018/019/020/048/061, ADR-0018/0033/0037/0044와 KB-001/ADMIN-KB-SEARCH-001/UI-002/004/005를 적용한다.

## Scope, actor and invariants

`searchAdminKnowledgeArticles`와 기존 GET 목록 latestRevision title/summary, 직원 앱 검색/필터/상세 포커스/dirty 보호를 수직 구현한다. Active ADMIN, ADMIN_UI + STAFF_SESSION/CSRF/expected actor만 허용한다. query/cursor는 POST body·일시 메모리이며 cache key/URL/storage/log에 두지 않는다. 최신 제목은 본문 없는 별도 projection이고 currentPublishedRevision/고객·상담사 API의 의미는 유지한다.

같은 REPEATABLE_READ에서 전체 EXACT count와 keyset 결과를 읽고 필수 보호 감사와 원자적으로 commit한다. 실패는 503이고 검색색인/발행/티켓 state·outbox·외부 I/O는 변경하지 않는다. 기존 raw-query protection·append-only/expiry를 재사용하며 reveal 대상은 확대하지 않는다. 검색 retry마다 새로운 interaction과 감사이며 쓰기 명령의 If-Match·불변조건은 유지한다.

V100은 기존 audit CHECK 확장만 하며 새 엔진·index·table·seed·backfill은 없다. 앱 rollback 시 확장 CHECK 유지 가능, append-only event 삭제 없음. migration/performance·권한/개인정보·브라우저 회귀와 UI 계약 확인은 아래에 근거를 기록한다. 완료 전까지 runtime/Storybook/운영 검증 통과를 주장하지 않는다.

## Validation pending

backend search/authorization/audit-failure/cursor/projection 회귀, migration/contract/fast gate; frontend MCP documentation/focused/full/preview, typecheck/unit/build/DS boundary, Chromium 1280/1440/1920/axe/focus/dirty guard, docs-check. merge/deploy, 실제 데이터/메일/권한 변경, production 성능 검증은 범위 밖이다.

## Recovery checkpoint — 2026-10-03

작업 디렉터리 소실 후 538b671e 계약 커밋에서 새 managed worktree를 만들고, 보존된 자체 세션의 apply_patch 5개와 순차 수정 스크립트 12개를 새 경로에 재적용했다. 복구 스크립트는 /private/tmp/p17-recovery/에 남긴다. 이전 원격 PR #264는 유지한다. 이 체크포인트는 검증 완료 선언이 아니며 새 경로에서 다시 검증한 결과를 후속 커밋에 기록한다.

소실 전 로그에는 backend fast/contract/migration/관리자 KB·직원 검색 integration BUILD SUCCESSFUL, 직원 unit 43 files/257 tests, focused MCP 5 stories, Chromium 4 tests(1280/1440/1920·axe·route/back/pending)가 기록되어 있다. 복구 후 재검증과 구분하며 전체 MCP/이미지 직접 검수는 아직 완료되지 않았다. 소실된 기존 test-results 캡처는 재생성한다.
