# Staff search two-phase PAGE vertical slice

## Goal

상담사 검색의 exact COUNT와 권한·감사 계약을 유지하면서, PAGE가 상위 ticket 후보를 먼저 제한하고 선택된 행에만 상세 projection join을 수행하게 한다.

## Decision and source references

- Decision IDs: D-008, D-018, D-036, D-041, D-045, D-048
- Accepted ADRs: ADR-0008, ADR-0018, ADR-0025, ADR-0030, ADR-0033, ADR-0036, ADR-0037
- Requirement: REQ-SRCH-001
- API contract: 기존 `POST /api/v1/agent/search`; 공개 계약 변경 없음
- Verification gates: ARCH-001, ACC-002, ACC-003, ACC-007, PERF-001, SEARCH-AUD-001, SEARCH-AUD-002

## Actor and source

- Actor: active STAFF Agent/Admin
- Source: AGENT_UI
- Read scope: 기존 `ALL_TICKETS` SQL authorization 유지
- request/correlation/interaction/search audit 의미는 변경하지 않는다.

## In scope

- `StaffTicketSearchSqlPlanFactory`의 PAGE SQL을 two-phase 구조로 변경한다.
- score/updated 정렬, 두 cursor, snapshot, status/priority/group/assignee/SLA filter를 유지한다.
- 선택된 ticket에 대해서만 customer/group/assignee/SLA/open-child summary를 조립한다.
- SQL 구조 fast test와 PostgreSQL-backed 검색 계약 회귀를 추가한다.

## Out of scope

- exact COUNT 의미 또는 COUNT SQL 변경
- 검색 ranking 공식, projection, schema/index, API, pool 변경
- 감사 암호화·fail-closed 의미 변경
- personal-staging 배포와 부하 재실행

## Invariants and failure semantics

- 결과 ticket 순서, score, cursor 경계, snapshot, exact count가 기존 계약과 같아야 한다.
- PUBLIC/INTERNAL staff projection과 `ALL_TICKETS` authorization을 유지한다.
- 성공한 검색은 required `SEARCH_EXECUTED` audit 뒤에만 반환하고 audit 실패는 계속 fail closed다.
- 외부 I/O, 새 transaction, migration은 추가하지 않는다.

## Data and privacy

- 기존 ticket/customer/group/assignee/SLA/search projection만 읽는다.
- 검색 원문은 parameter로만 전달하며 SQL, log, metric label에 삽입하지 않는다.
- audit의 `[PROTECTED]`, keyed fingerprint, authenticated ciphertext 계약은 변경하지 않는다.

## Acceptance scenarios

- Given score sort 또는 updated sort, when 첫 페이지와 cursor 다음 페이지를 조회하면 stable tie-break와 snapshot이 유지된다.
- Given status/priority/group/assignee/SLA filters, when 검색하면 필터 밖 ticket은 count/items/cursor에 영향을 주지 않는다.
- Given one PAGE query, when SQL plan을 구성하면 limit이 상세 projection join보다 먼저 적용된다.
- Given required audit insert failure, when 검색하면 결과 없이 기존 audit-unavailable problem을 반환한다.

## Validation

- `cd backend && ./gradlew fastTest --tests 'dev.deskseed.ticketing.internal.StaffTicketSearchSqlPlanTest'`
- `cd backend && ./gradlew integrationTest --tests 'dev.deskseed.staffaccess.internal.AgentTicketSearchIntegrationTest' --tests 'dev.deskseed.ticketing.internal.StaffTicketQueryEvidenceIntegrationTest'`
- `cd backend && ./gradlew contractTest --tests 'dev.deskseed.architecture.ArchitectureTest'`

## Compatibility and rollback

- OpenAPI/schema/migration 변화 없음.
- 이전 단일-phase `pageSql` 생성으로 되돌릴 수 있다.
- 배포·HTTP 성능 A/B는 별도 승인 뒤 동일 corpus/seed와 고유 run ID로 검증한다.

## Human explanation

- 측정상 PAGE가 모든 후보의 상세 join을 수행한 뒤 작은 page를 반환했다. 후보 선정에 필요한 최소 필드만 먼저 처리하면 상세 join의 read amplification을 줄일 수 있다.
- exact COUNT와 넓은 substring candidate scan은 남으므로 이 변경을 검색 병목 전체 해결로 주장하지 않는다.

## Implementation result

- PAGE SQL은 materialized candidate CTE에서 stable order/cursor/limit을 먼저 적용하고, 선택된 ticket에만 상세 projection join을 수행한다.
- SLA filter가 없으면 candidate 단계에서 SLA fact join을 생략하고, SLA filter가 있으면 기존 predicate 의미를 위해 candidate 단계에 유지한다.
- exact COUNT, ranking, authorization, required audit, API/schema/index/pool 계약은 변경하지 않았다.
- fast SQL-structure test, PostgreSQL search integration/evidence test, architecture contract test가 통과했다.
- deterministic 10k-ticket smoke harness에서 exact COUNT p95 3.582 ms, score first PAGE p95 4.218 ms로 250 ms database-component budget을 통과했다. 이 값은 HTTP 또는 personal-staging 성능 주장이 아니다.
- personal-staging 배포와 고유 `TEST_RUN_ID`를 사용한 Grafana HTTP/COUNT/PAGE/오류/회복 A/B는 수행하지 않았다.
