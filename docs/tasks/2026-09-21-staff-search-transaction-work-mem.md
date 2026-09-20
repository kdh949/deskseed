# Staff search transaction-local work memory vertical slice

## Goal

상담사 검색의 exact `resultCount`, literal substring, rank/cursor, 권한·감사 계약을 유지하면서 PAGE SQL의 디스크 temp spill을 줄인다.

## Decision and source references

- Decision IDs: D-008, D-018, D-036, D-041, D-045, D-048
- Accepted ADRs: ADR-0008, ADR-0018, ADR-0025, ADR-0030, ADR-0033, ADR-0036, ADR-0037, ADR-0048
- Requirements: REQ-SRCH-001, REQ-PERF-001, REQ-PERF-002
- API operation: 기존 `POST /api/v1/agent/search`; 공개 계약 변경 없음
- Verification gates: ARCH-001, ACC-002, ACC-003, ACC-007, PERF-001, SEARCH-AUD-001, SEARCH-AUD-002

## Actor, source, and data boundaries

- Actor: active STAFF Agent/Admin
- Source: AGENT_UI
- Read scope: 기존 `ALL_TICKETS` SQL authorization 유지
- PUBLIC/INTERNAL staff projection, protected query audit, request/correlation/interaction context를 변경하지 않는다.

## In scope

- 검색 트랜잭션에만 PostgreSQL `work_mem=64MB`를 `SET LOCAL`로 적용한다.
- 검색 SQL, result count, ranking, cursor, API, schema/index, Hikari pool은 변경하지 않는다.
- personal-staging의 기존 단일 DB와 고정 corpus/seed에서 배포 전후를 비교한다.

## Out of scope

- 전역/role/database PostgreSQL 설정 변경
- DB 복제 또는 신규 DB
- 검색 결과 정확성·순위 변경
- Elasticsearch/OpenSearch 도입

## Invariants and failure semantics

- 설정은 현재 Spring transaction이 끝나면 자동으로 원복되어 다른 요청에 남지 않는다.
- 검색 SQL 또는 required audit가 실패하면 기존과 같이 성공을 반환하지 않는다.
- exact count, stable ordering, snapshot cursor, authorization 결과는 기존과 동일해야 한다.

## Measurement basis

- personal-staging 기본 `work_mem`은 4MB였다.
- 수정 전 동일 20건 실행의 PAGE SQL은 `pg_stat_statements` delta에서 temp read 146,251 blocks, temp write 75,450 blocks를 기록했다.
- 효과 판정은 고유 `TEST_RUN_ID`의 HTTP latency와 Grafana PAGE/DB wait/host IO, 같은 시간대 `pg_stat_statements` delta로 수행한다.
- generator CPU/RAM/network 또는 run-correlated trace가 계속 미수집이면 저부하 검증에서 중단하고 용량 결론을 내리지 않는다.

## Validation and rollback

- CI: backend test/architecture/contract gates
- live: personal-staging 단일 요청, 동일 20건 smoke, 회복 구간 확인
- 효과가 없거나 메모리/GC/Hikari/DB wait가 악화되면 배포 SHA를 이전 기준 SHA로 되돌린다.

## Human explanation

이 변경은 전역 DB 메모리를 늘리는 튜닝이 아니라, 이미 temp spill이 관측된 검색 statement가 실행되는 transaction에만 제한된 예산을 준다. broad substring 후보 자체가 크다는 근본 비용은 남으므로 temp I/O가 줄어도 전체 병목 해결로 단정하지 않는다.
