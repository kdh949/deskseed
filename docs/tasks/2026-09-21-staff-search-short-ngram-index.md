# Staff search short-query character index vertical slice

## Goal

상담사가 1~2자 검색어를 사용해도 기존 literal substring, exact count, 순위, cursor, 권한·감사 계약을 유지하면서 검색 결과를 더 빨리 받는다.

## Decision and source references

- Decision IDs: D-008, D-018, D-036, D-041, D-045, D-048
- Accepted ADRs: ADR-0008, ADR-0018, ADR-0025, ADR-0030, ADR-0033, ADR-0036, ADR-0037, ADR-0047, ADR-0048
- Requirements: REQ-SRCH-001, REQ-PERF-001, REQ-PERF-002
- API contract: 기존 `POST /api/v1/agent/search`; 공개 계약 변경 없음
- Verification gates: ARCH-001, ARCH-002, ACC-002, ACC-003, ACC-007, PERF-001, SEARCH-AUD-001, SEARCH-AUD-002

## Actor and source

- Actor: active STAFF Agent/Admin
- Source: AGENT_WORKSPACE
- Scope: 기존 `ALL_TICKETS` SQL authorization
- request/correlation/search audit 의미는 변경하지 않는다.

## In scope

- `staff_document`를 Unicode 문자 배열로 변환하는 immutable PostgreSQL 함수
- 같은 PostgreSQL의 GIN 표현식 인덱스
- 1~2자 query의 인덱스 후보 조건과 기존 literal `LIKE` 재검증
- SQL 구조·실제 PostgreSQL index plan·검색 결과 회귀 테스트
- 개인 스테이징 배포 후 동일 corpus/seed의 bounded 재측정

## Out of scope

- 별도 DB, replica, Elasticsearch/OpenSearch 또는 다른 외부 검색 저장소
- approximate/capped count, 최소 검색 길이 변경, 검색 순위·결과 정확성 변경
- pool, PostgreSQL planner GUC, 제품 timeout 변경
- 원문 검색어를 로그·metric·공유 증거에 기록하는 변경

## Invariants and failure semantics

- GIN 조건은 query의 모든 문자를 포함하는 문서를 후보로 선택한다.
- GIN 조건 뒤에 기존 escaped literal `LIKE`를 유지해 문자 인접성·중복과 결과 의미를 바꾸지 않는다.
- 3자 이상 query는 기존 `pg_trgm` 경로를 유지한다.
- exact count는 cursor 적용 전 snapshot과 filters에 일치하는 전체 후보 수다.
- score/updated order, ticket-number tie-break와 signed cursor는 바뀌지 않는다.
- 성공한 검색은 required `SEARCH_EXECUTED` audit 뒤에만 반환한다.
- DB 또는 audit 실패는 기존 problem contract로 fail closed한다.

## Data and privacy

- 새 canonical data row는 만들지 않는다. 인덱스는 기존 staff-only projection의 파생 구조다.
- 검색 query 원문은 JDBC parameter로만 전달하며 migration, index, log, metric label에 저장하지 않는다.
- PUBLIC/INTERNAL 분리와 staff-only projection 경계는 그대로다.
- primary-row 삭제와 projection rebuild 계약은 기존 V35 경계를 따른다.

## Acceptance scenarios

1. Given 1자 또는 2자 literal이 subject/comment에 있을 때, when staff search를 실행하면, then 기존 결과와 exact count가 반환되고 SQL은 short character GIN 후보 조건을 사용한다.
2. Given `%`, `_`, `\\` 같은 wildcard 문자가 포함된 1~2자 query일 때, when 검색하면, then 문자는 wildcard가 아니라 literal로 취급된다.
3. Given 3자 이상 query일 때, when 검색하면, then 기존 `staff_document LIKE ... escape '\\'`와 trigram index 경로가 유지된다.
4. Given short character index를 사용할 수 없는 DB 상태일 때, when migration/startup이 실행되면, then 배포는 실패하고 인덱스 없는 새 SQL을 성공으로 제공하지 않는다.
5. Given access audit persistence failure일 때, when 검색하면, then 검색 결과는 반환되지 않는다.

## Validation

- `StaffTicketSearchSqlPlanTest`: short/long SQL 분기와 parameter-only query
- `StaffTicketQueryEvidenceIntegrationTest`: short literal 결과와 forced planner index 선택
- 직접 Flyway API를 사용하는 migration/integration tests는 `flyway.postgresql.transactional.lock=false`를 명시해 non-transactional V94를 같은 방식으로 검증한다.
- clean Flyway migration 및 Hibernate validation: ARCH-002
- CI backend/module/contract gates
- 개인 스테이징: exact SHA, 같은 DB/corpus/seed, 20회 bounded sequential 비교와 Grafana/pg_stat_statements 증거
- 생성기 자원 또는 run-correlated trace가 계속 미수집이면 본 단계 상승 부하는 실행하지 않는다.

## Compatibility and migration

- OpenAPI와 response schema는 변경하지 않는다.
- V94는 immutable function과 GIN index를 추가한다. index는 `CREATE INDEX CONCURRENTLY`로 같은 DB에서 작성한다.
- Flyway migration은 non-transactional script이고 PostgreSQL advisory lock은 session lock을 사용한다. production의 별도 Flyway CLI `db-migrate`에도 같은 설정을 전달하며 Compose 계약에서 고정한다.
- rollback은 이전 application SHA로 되돌리는 것이다. 추가 function/index는 무해하게 남기고, 제거는 별도 forward migration에서만 수행한다.
- index build 실패는 배포 실패로 남기고 Flyway repair 후 재실행한다. 데이터 volume은 삭제하거나 교체하지 않는다.

## Human explanation

- PostgreSQL `pg_trgm`은 추출 가능한 trigram이 없는 짧은 패턴에서 후보를 충분히 줄일 수 없다.
- 이번 구조는 원문을 새 table에 복제하지 않고 기존 rebuildable staff projection에 문자 membership index만 보강한다.
- 2자 query의 두 문자가 떨어져 있거나 같은 문자의 개수가 부족한 후보는 기존 literal `LIKE`가 제거한다.
- exact result semantics와 audit contract를 유지하려고 GIN 조건을 최종 판정으로 사용하지 않고 기존 literal `LIKE`를 함께 적용한다.
- 인덱스 크기·갱신 비용과 검색 지연의 전후 실측을 함께 기록하며, 비용이 이익보다 크면 외부 검색 저장소가 아니라 먼저 이 index를 rollback 후보로 둔다.
