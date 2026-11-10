# 도움말 관련도 정렬과 일치 문맥

상태: CONTRACT_DEFINED. P06 / C-F03. FROZEN operation의 현행 runtime을 유지하고 예정 정렬·cursor 의미를 BLUEPRINT extension으로 구분했다.

## Goal

고객이 `비밀번호`를 검색하면 공통 주의문만 일치하는 최신 문서보다 제목·요약이 관련된 문서를 먼저 보고, 짧은 일치 문맥으로 결과를 판단한다.

## Decision and source references

REQ-KB-002/003/004, D-036/D-054/D-055, Accepted ADR 0008/0018/0025. docs/19·23·25·32·33·39·55. Operations: `searchHelpArticles`, `searchAgentKnowledge`, `suggestAgentKnowledgeForTicket`. Gates: DOC-001, ARCH-001/002, ACC-001 및 PostgreSQL corpus/cursor/audience 회귀; 고객 UI-002/004/006.

PostgreSQL [ranking/headline 문서](https://www.postgresql.org/docs/current/textsearch-controls.html)의 `ts_rank`·`ts_headline`를 기존 V52 weighted vector/GIN에 적용한다. 별도 검색 인프라·AI 호출·새 index/migration은 추가하지 않는다.

## Actor, data and product contract

익명/CUSTOMER public help search와 기존 STAFF/AGENT_UI 검색·PUBLIC ticket-context suggestion이 같은 read path를 사용한다. 기존 server audience filter 뒤에 rank, cursor, snippet을 계산한다. STAFF selected-group membership와 required protected search audit를 계속 재검증한다. 고객 API에 staff-only 문서·group ID·audit 정보·rank score를 추가하지 않는다.

V52의 title=A(1.0), summary=B(0.4), body=C(0.2), PostgreSQL 기본 ts_rank weight를 사용한다. 동점은 published revision created_at DESC, article UUID DESC다. 전체 corpus의 통계를 쓰지 않으므로 hidden 문서가 visible rank에 영향을 주지 않는다. 한국어 형태소 분석·부분어·동의어 검색까지 보장하지 않는다.

검색 cursor v2는 rank(float4 round trip), revision 시각, article ID를 HMAC으로 결합하고 기존 query·reader scope를 검증한다. section/admin 목록 v1은 유지하며 search에서 v1·변조·다른 query/reader cursor를 400으로 거절한다. 배포 후 진행 중 검색은 첫 페이지부터 다시 검색할 수 있다. 현재 권한을 매 페이지 다시 평가하며 corpus 변경 중 snapshot isolation을 제공하지 않는다.

excerpt는 summary/body에서 query 문맥을 고른 뒤 공백을 정리한 최대 240 Unicode 문자 plain text다. ts_headline의 HTML marker는 비활성화하고 결과는 UI의 text node로만 렌더링한다. 본문/요약 일치가 없으면 시작 부분을 쓴다. UI는 제목·category/section과 짧은 발췌를 분리하며 1위에 확정적인 `가장 관련 높은 결과` 주장을 붙이지 않는다. highlight는 HTML 주입 없이 text fragment/mark로 제공한다.

## Scope and invariants

- 기존 HTTP request/response field는 유지하고 정렬·cursor·excerpt 의미만 변경한다.
- 검색 query는 기존 POST body 범위에서 처리하고 ordinary log에 쓰지 않는다. STAFF query encryption/fingerprint/retention 및 mandatory audit 실패 503은 유지한다.
- 현재 canonical published revision과 active 부모, audience 조건을 바꾸지 않는다. archived/unpublished/private 문서는 excerpt·cursor·hasMore에 기여하지 않는다.
- 외부 I/O, 새 캐시, write transaction, idempotency 정책은 없다. FTS filter 뒤 bounded page에만 excerpt를 생성한다.

## Acceptance and validation

1. 오래된 title match가 최신 body boilerplate match보다 우선한다. summary-only와 body-only의 가중치도 비교한다.
2. 동일 rank/시각에서 ID가 tie-break하며 작은 페이지를 반복 조회해 중복·누락 없이 전체 visible corpus를 읽는다.
3. v1·변조·다른 query/reader cursor는 400, section listing v1은 유지된다.
4. STAFF/selected-group 문서가 public 결과·snippet·hasMore에 영향을 주지 않는다. STAFF audit 실패는 결과 없이 503이다.
5. 긴 intro 뒤 일치어가 excerpt에 포함되고 생성 HTML marker가 없다. Korean/English/identifier 및 OR/phrase 구문을 기존 simple websearch 의미에서 확인한다.
6. 단위 cursor/허용 범위, PostgreSQL integration, architecture, Core bundle/docs-check와 UI Storybook MCP·브라우저를 각 slice에서 실행한다. query plan과 합성 fixture elapsed는 기록하되 production p95로 부르지 않는다.

## Compatibility / human explanation

동일 endpoint와 response DTO를 유지한다. signed search v2만 배포 시 새로 시작하며 목록 cursor는 호환된다. migration/backfill 없이 기존 V52 projection을 재사용하므로 rollback은 코드 revert이며 열린 검색은 다시 실행한다. 빈 결과·오류·이전 cursor 안내는 UI에서 분리한다. rank는 lexical relevance heuristic이며 실제 고객 질의 품질을 보장하는 confidence score가 아니다. 더 큰 corpus의 측정 한계가 생길 때만 새 검색 기술을 검토한다.
