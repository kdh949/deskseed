# P05 고객 도움말 계층 탐색 UI

## Goal / references

직접 연 문서에서 실제 부모 주제·섹션으로 돌아가고, 주제/섹션 목록의 제목과 설명을 읽어 다음 문서를 선택한다. C-F06, REQ-KB-001/004, REQ-UI-005/007. D-036/D-054/D-055/D-061, Accepted ADR-0018/0025/0044, docs28~34/39/40/51/55, 선행 P05 계약 #248·서버 #249의 `docs/tasks/2026-10-03-help-navigation-path.md`를 따른다. UI-002/004/005/006.

## Actor / API / boundaries

익명 또는 authenticated CUSTOMER/CUSTOMER_PORTAL이 기존 getHelpCategory/Section/Article 응답을 읽는다. 실제 category/section {slug,title}만 경로에 사용하고 방문 이력이나 검색 query로 추정하지 않는다. slug를 encodeURIComponent로 link에 넣는다. 서버 audience/active/publication/cache 경계와 audit/transaction/retention/idempotency 의미는 불변이며 추가 HTTP 요청이나 write가 없다.

새 필드는 현재 계약에서 required이며 UI는 점진 배포/rollback 중 이전 응답에 한해 부모 링크를 생략한다. 필드가 존재하면서 malformed이면 projection 오류로 처리한다. 문자열은 React text로 렌더하고 민감 metadata·staff API는 사용하지 않는다.

## Reuse plan / scope

문서화된 customer ScreenState/RetryButton과 기존 Link/page/breadcrumb 조합을 재사용한다. 주제의 섹션과 섹션의 문서에 공통으로 쓰는 작은 `HelpBrowseList`를 고객 canonical DS에 추가하고 제목·설명·경로·목록 이름만 받는 API/Storybook을 문서화한다. 기존 semantic token/spacing/radius 사용, 타 앱 복사/새 시각 체계 없음. 카드 전체가 하나의 링크이며 설명이 길어도 줄바꿈한다.

## Acceptance / gates

직접 문서 진입→서버 부모 섹션→서버 부모 주제; 이름 변경과 비ASCII/긴 slug/title; 이전 projection에서는 가짜 링크 없음; malformed parent 및404는 부모 metadata 미표시. 목록 loading/empty/error 및 pagination 유지. 키보드/axe/390·768·1448 viewport, unit·type/build/boundaries·실제 MCP focused/full story/preview, mock E2E를 실행한다. 서버의 부모 rename/move ETag·hidden404는 #249 검증을 의존하며 이번 UI에서 DB/실서버/배포·Firefox/WebKit·pixel baseline 비교는 실행하지 않는다.

## Compatibility

#249 branch 위 적층. migration/API 변경/seed/외부 I/O 없음. UI revert로 복구. 성능 수치 주장은 없고 부모 경로를 위한 추가 요청도 없다. P04/P07 및 P06은 별도 PR로 통합한다.

## Completion evidence

Passed: customer unit121/28 files (새 부모 경로·malformed/이전 projection 회귀2개 포함), typecheck, customer build, boundaries, contract check, 변경 파일 lint/format, docs-check, diff-check. 실제 MCP focused 및 full75 story/a11y 통과. 새 DS는 문서 inventory 등록/get-documentation 후 page에서 사용했다. shared CSS/adapter는 get-stories-by-component로 영향 확인하고 전체 suite를 실행했다.

Mock Chromium E2E3에서390/768/1448의 직접 문서→실제 섹션→주제, 서버 부모 이름 변경 후 reload, 제목/설명·키보드·axe·가로 넘침을 검증했다. 최초 E2E에서 확인된 주제의 카드 연결 누락을 보완하고 전부 재통과했다. `frontend/test-results/customer-help-navigation-*/`의 대표 모바일 article/desktop category 캡처를 직접 확인했다. backend/운영/SMTP·Firefox/WebKit·pixel baseline 비교는 미실행이며 선행 서버 검증을 운영 증거로 확대하지 않는다.

MCP previews:

- http://localhost:6007/?statuses=affected;modified;new
- http://localhost:6007/?path=/story/customer-portal-help-center-pages--article
- http://localhost:6007/?path=/story/customer-portal-help-center-pages--article-with-long-parents
- http://localhost:6007/?path=/story/customer-portal-help-center-pages--category-sections
- http://localhost:6007/?path=/story/customer-portal-help-center-pages--section-articles
- http://localhost:6007/?path=/story/customer-design-system-help-browse-list--section-links
