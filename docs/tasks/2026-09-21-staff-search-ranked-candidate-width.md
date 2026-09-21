# Staff search ranked-candidate width reduction

## Goal

상담사 검색의 exact count, literal substring, score/cursor, 권한·감사 계약을 유지하면서 광범위 검색에서 materialized ranked 후보와 temp I/O의 row 폭을 줄인다.

## Decision and source references

- Decision IDs: D-008, D-018, D-036, D-041, D-045, D-048
- Accepted ADRs: ADR-0008, ADR-0018, ADR-0025, ADR-0030, ADR-0033, ADR-0036, ADR-0037, ADR-0047
- Requirements: REQ-SRCH-001, REQ-PERF-001
- API contract: 기존 `POST /api/v1/agent/search`; 공개 계약 변경 없음
- Verification gates: ARCH-001, ACC-002, ACC-003, ACC-007, PERF-001, SEARCH-AUD-001, SEARCH-AUD-002

## Actor, data, and privacy boundaries

- active STAFF Agent/Admin의 기존 `ALL_TICKETS` SQL authorization을 유지한다.
- 검색 원문은 parameter로만 전달하며 SQL/log/metric label과 공유 evidence에 넣지 않는다.
- PUBLIC/INTERNAL staff projection, protected search audit, required audit fail-closed 의미를 유지한다.
- schema/index/pool/OpenAPI/외부 I/O는 변경하지 않는다.

## Change

- materialized ranked 후보에서 UUID `ticket_id`를 제거하고 stable unique `ticket_number`로 선택 행의 detail을 결합한다.
- score sort에서는 정렬과 cursor에 쓰지 않는 `updated_at`도 ranked 후보에서 제거한다.
- updated sort에서는 `updated_at`을 유지한다.
- exact count와 selected page는 같은 materialized 후보를 계속 공유한다.

## Invariants and failure semantics

- exact count는 cursor 적용 전 snapshot과 filters에 일치하는 전체 후보 수다.
- score/updated order, tie-break, cursor, numeric substring, literal wildcard, INTERNAL 검색 의미를 바꾸지 않는다.
- ticket number는 canonical unique human identifier이며 projection과 ticket row는 같은 transaction/rebuild 계약을 따른다.
- 성공한 검색은 required `SEARCH_EXECUTED` audit 뒤에만 반환한다.
- DB 또는 audit 실패는 기존 problem contract로 fail closed한다.

## Verification

- SQL structure와 PostgreSQL 통합 테스트에서 score/updated cursor, 빈 결과, exact count, filter, literal substring, INTERNAL, numeric 검색을 확인한다.
- 보호된 disposable load DB에서 변경 전후 plan의 latency, buffers, temp blocks와 join shape를 비교한다.
- 개인 스테이징에 exact SHA를 배포한 뒤 동일 corpus/seed의 1건과 bounded 1 VU 순차 실행을 Grafana에서 비교한다.
- 생성기 telemetry가 미수집이면 단계 상승 부하는 수행하지 않는다.

## Compatibility, rollback, and limits

- API, migration, index, pool 변화가 없어 wire/schema 호환성은 유지된다.
- rollback은 detail join key를 selected `ticket_id`로 복원한다.
- arbitrary literal substring과 exact result count는 여전히 모든 일치 후보 확인을 요구한다. 이 변경은 후보 cardinality나 1~2자 검색의 full scan 가능성을 제거한다고 주장하지 않는다.
