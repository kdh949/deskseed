# 도움말의 실제 부모 경로

상태: P05 / C-F06 서버 구현 및 해당 회귀 검증 완료. 계약 PR #248의 BLUEPRINT를 실제 고객 응답으로 승격했다. 고객 UI 연결은 후속이다.

## Goal

고객이 검색에서 문서를 바로 열어도 실제 카테고리와 섹션으로 돌아갈 수 있다.

## Decision and source references

REQ-KB-001/004, D-036/D-054/D-055, Accepted ADR 0018/0025/0044. docs/02·25·30·33·39·55, `getHelpSection`, `getHelpArticle`. Gates: DOC-001, ARCH-001/002, UI-002/004/006 및 PostgreSQL audience/cache 회귀.

## Actor and product contract

익명 또는 authenticated CUSTOMER가 기존 `/sections/:slug`, `/articles/:slug`를 읽는다. section 응답에 `category: {slug,title}`, article 응답에 `category`와 `section`을 추가한다. 실제 active 부모와 reader에게 허용된 PUBLISHED article을 함께 조회한다. 브라우저의 이전 방문 경로, 검색 query, 임의 ID로 부모를 추정하지 않는다.

고객 UI는 `모든 문서 > 카테고리 > 섹션`의 의미 있는 링크와 현재 페이지 제목을 제공한다. 카테고리의 섹션 카드와 섹션의 문서 목록은 기존 고객 앱 디자인 체계에서 계층을 유지한다. loading/empty/error/404, keyboard focus, 모바일 390·768·desktop 상태를 후속 UI slice에서 확인한다.

## Scope / boundaries

추가 DTO projection과 고객 응답 계약, article ETag, focused 회귀가 범위다. 관리자/상담사 HTTP projection, 새 endpoint, 새 hierarchy/service/cache, DB migration, 검색 정렬은 제외한다. P06 검색 개선은 별도 계약이다.

## Invariants and failure semantics

- 기존 audience SQL·active category/section·PUBLISHED lifecycle 조건을 유지한다. 제한·inactive·unpublished는 부모 메타데이터 없이 기존 404다.
- PUBLIC anonymous cache 정책과 signed-in no-store를 유지한다. article ETag에 revision/audience뿐 아니라 응답의 부모 slug/title도 포함해 rename/move 후 오래된 breadcrumb에 304를 반환하지 않는다. Last-Modified는 계속 published revision 시각이다.
- 기존 read-only transaction 안에서 현재 데이터 projection을 만든다. mutation/idempotency/concurrency 의미와 ticket 도메인은 바꾸지 않는다.
- public read에 새 privileged audit를 추가하지 않는다. staff의 기존 required knowledge access audit와 source/request/correlation 문맥을 바꾸지 않는다.
- PII, secret, staff group 설정, audit metadata를 추가하지 않는다. logging/retention/export/webhook은 불변이다.

## Acceptance and validation

1. 공개 section/article 조회는 정확한 category/section slug·title을 반환한다.
2. 직접 article 접근, 서로 다른 부모로 이동 및 부모 rename 뒤 경로가 현재 값이고 이전 ETag는 200으로 갱신된다.
3. 같은 응답은 304이며 제한 audience·비활성 부모는 기존 ETag를 보내도 404다.
4. 관리자/상담사 응답에는 customer path field를 새로 넣지 않는다.
5. 계약 PR은 bundle/docs-check/diff-check를 실행한다. 구현은 PostgreSQL-backed `AdminKnowledgeIntegrationTest`와 architecture gate를 실행하고 UI는 target-app Storybook MCP, keyboard/axe와 브라우저에서 확인한다.

## Compatibility and human explanation

응답 필드 추가이며 기존 slug/id는 유지한다. public HelpArticle schema를 관리자 aggregate와 분리해 실제 고객 projection을 문서화한다. 기존 잘못 공유된 admin `version` required는 고객 DTO에 강제하지 않는다. migration/backfill 없이 코드 revert로 복구할 수 있으며 이전 ETag는 한 번 200으로 갱신된다. 기존 join에서 부모 표시값을 함께 읽고 추가 round trip을 만들지 않는다. latency/load 효과는 측정 전 주장하지 않는다.


## Observed verification

기존 조회 join에서 부모 slug/title을 함께 projection하며 새 round trip이나 migration은 없다. 새 회귀의 수정 전 실패 확인 후 `AdminKnowledgeIntegrationTest` 5개, `ArchitectureTest` 1개와 `ApiDocumentationIntegrationTest` 5개, Core bundle/docs-check/diff-check를 통과했다. 실제 부모 rename/move, 같은 응답의 304, 이전 ETag에 대한 갱신 200, hidden audience·archived parent의 404를 검증했다. UI·전체 backend suite·production·latency/load 검증은 로컬에서 실행하지 않았다.
