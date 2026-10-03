# Staff search single-pass exact COUNT and PAGE vertical slice

## Goal

상담사 검색의 exact count, literal substring, 순위, cursor, 권한·감사 계약을 유지하면서 COUNT와 PAGE가 동일한 광범위 후보 집합을 각각 읽는 중복을 제거한다.

## Decision and source references

- Decision IDs: D-008, D-018, D-036, D-041, D-045, D-048
- Accepted ADRs: ADR-0008, ADR-0018, ADR-0025, ADR-0030, ADR-0033, ADR-0036, ADR-0037
- Requirements: REQ-SRCH-001, REQ-PERF-001
- API contract: 기존 `POST /api/v1/agent/search`; 공개 계약 변경 없음
- Verification gates: ARCH-001, ACC-002, ACC-003, ACC-007, PERF-001, SEARCH-AUD-001, SEARCH-AUD-002

## Actor, scope, and privacy

- active STAFF Agent/Admin의 기존 `ALL_TICKETS` SQL authorization을 유지한다.
- query 원문은 parameter로만 전달하며 SQL/log/metric label에 넣지 않는다.
- PUBLIC/INTERNAL staff projection, protected query audit, required audit fail-closed 의미를 유지한다.
- schema/index/pool/제품 API와 외부 I/O는 변경하지 않는다.

## Change

- 권한·snapshot·filter·literal substring predicate와 score를 계산한 `ranked` 후보를 materialize한다.
- `candidate_stats`가 같은 materialized 후보에서 exact count를 계산한다.
- `selected`가 같은 후보에 cursor/order/limit을 적용한다.
- 한 JDBC statement가 `result_count`와 선택된 page rows를 함께 반환한다.
- 선택 행이 없는 cursor 끝/빈 검색에서도 sentinel result row로 exact count를 반환하고 HTTP items에는 포함하지 않는다.

## Invariants and failure semantics

- exact count는 cursor 적용 전 snapshot 전체 후보 수다.
- score/updated sort와 ticket-number tie-break, next cursor, filter 결과는 기존과 같다.
- 성공한 검색은 required `SEARCH_EXECUTED` audit 뒤에만 반환한다.
- DB query 실패 또는 audit 저장 실패는 기존 problem contract로 fail closed한다.

## Acceptance and validation

- SQL structure test: base search projection predicate는 combined statement에 한 번만 존재한다.
- PostgreSQL integration: 첫/다음 cursor page, 빈 결과, internal/exact-number/literal wildcard corpus, 모든 filter, audit failure를 검증한다.
- query counter: comment 수와 무관하게 검색 read SQL은 1 statement다.
- 실행: `cd backend && ./gradlew test --tests 'dev.deskseed.ticketing.internal.StaffTicketSearchSqlPlanTest' --tests 'dev.deskseed.ticketing.internal.StaffTicketQueryEvidenceIntegrationTest' --tests 'dev.deskseed.staffaccess.internal.AgentTicketSearchIntegrationTest' --tests 'dev.deskseed.foundation.SearchDiagnosticsTest'`

## Compatibility and rollback

- OpenAPI, migration, index, pool 변화 없음.
- rollback은 runtime에서 독립 COUNT 뒤 PAGE를 실행하던 이전 commit으로 되돌린다.
- `page` diagnostic span은 이제 materialized candidate scan, exact count, page selection과 detail mapping을 합한 한 JDBC statement 시간이다. 독립 `count` span이 없는 것이 expected다.

## Human explanation

빈도가 높은 substring은 exact count를 위해 모든 일치 후보를 확인해야 한다. 이 변경은 그 필수 비용을 없앴다고 주장하지 않고, 같은 후보 heap을 COUNT와 PAGE가 요청당 두 번 읽던 중복을 한 번으로 줄인다.
