# P06 고객 검색 결과와 커서 복구 UI

## Goal / references

고객이 관련도 순서의 결과를 제목·일치 문맥·부모 이름으로 빠르게 비교하고, 오래된 커서가 거절되면 같은 검색어로 첫 페이지부터 재검색한다. C-F03, REQ-KB-003/004, REQ-UI-005/007; D-036/D-054/D-055/D-061, Accepted ADR-0008/0018/0025/0044, docs28~34/39/40/51/55, 선행 #251/#253와 `2026-10-03-help-search-relevance.md`를 따른다. UI-002/004/005/006.

## Actor / contract / invariants

익명 또는 authenticated CUSTOMER/CUSTOMER_PORTAL의 기존 POST searchHelpArticles만 사용한다. 서버 순서를 유지하며 cursor를 불투명 값으로 전달하고 프런트가 순위·총건수·confidence를 계산하지 않는다. API/권한/감사/transaction/retention 변경 없음. query는 기존 request body 범위이며 log/storage에 추가 저장하지 않는다. excerpt는 text/mark로만 렌더하고 HTML을 주입하지 않는다. 단순 literal 검색어 일치만 강조하며 OR/phrase 의미를 UI에서 재구현하지 않는다.

## Reuse / scope

문서화된 고객 DsButton/RetryButton/ScreenState와 기존 검색 form·목록·주제 sidebar를 재사용한다. 1위 확정 배지/차별 아이콘을 제거하고 제목·짧은 발췌·경로를 같은 밀도로 표시한다. 기존 semantic token과 spacing으로 CSS를 조정하며 새 DS/framework/인프라 없음. 검색 ranking/corpus 검증은 서버 선행 PR의 범위다.

## Failure / acceptance

초기 loading/empty/error, 다음 페이지 실패/400, 재검색을 구분한다. 자동 retry는 하지 않고 다음 페이지503은 기존 결과를 보존해 추가 조회만 재시도한다. 다음 페이지400은 원인을 단정하지 않는 문구와 첫 페이지부터 재검색 버튼을 제공한다. 재검색은 기존 pages/cursor를 reset하고 입력으로 focus를 돌린다. 첫 페이지 refetch 오류에서는 오래된 결과를 성공처럼 표시하지 않는다. 새 검색어는 이전 페이지를 섞지 않는다.

동일 서버순서/opaque cursor, 오류 복구·키보드·safe excerpt, 390/768/1448 및 axe를 unit/MCP/full-page mock E2E로 검증한다. type/build/boundary/contract/lint/format/docs gate 실행. 실제 corpus/rank/권한/audit/p95·운영/배포/Firefox/WebKit/pixel baseline은 UI에서 재검증하지 않는다. migration/seed/추가 I/O 없음, UI revert로 복구 가능.

## Completion evidence

Passed: customer unit121/28 files (새 cursor reset/focus·literal 강조/HTML escape2개), typecheck, customer build, boundaries, contract check, 변경 파일 lint/format, docs-check, diff-check. 실제 MCP focused 및 full74 story/a11y 통과. shared CSS는 consumer lookup과 full suite로 확인했다. 캡처에서 기존 sidebar의 모든 span에 auto margin을 적용해 아이콘/문의 버튼이 오른쪽으로 밀리던 선택자를 화살표 span으로 좁히고 관련 전체 검증을 재실행했다.

Mock Chromium E2E3(390/768/1448) 통과: 서버 결과 순서 보존, HTML 문자와 literal mark, 400→cursor 없이 재검색/focus, 503→동일 cursor 추가 페이지 재시도, 검색어 변경시 cursor 미혼합, axe·가로 넘침. `frontend/test-results/customer-help-search-*/` 대표390/1448 캡처를 직접 확인했다. 이 mock 순서는 서버 ranking 정확도나 운영 corpus 품질의 증거가 아니다. 실제 PostgreSQL/audit/performance는 #253의 별도 증거이며 이번 UI에서는 운영 E2E/배포·Firefox/WebKit·pixel baseline 비교를 실행하지 않았다.

MCP previews:

- http://localhost:6007/?statuses=affected;modified;new
- http://localhost:6007/?path=/story/customer-portal-help-center-pages--search-results
- http://localhost:6007/?path=/story/customer-portal-help-center-pages--search-cursor-recovery
- http://localhost:6007/?path=/story/customer-portal-help-center-pages--search-next-page-recovery
- http://localhost:6007/?path=/story/customer-portal-help-center-pages--article
