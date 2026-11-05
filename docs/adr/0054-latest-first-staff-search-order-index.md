# ADR 0054 — 최신순 상담사 검색 인덱스

- Status: Accepted
- Date: 2026-09-21
- Verification record updated: 2026-10-29
- Decision: D-071
- Requirements: REQ-SRCH-001, REQ-PERF-001, REQ-PERF-002, REQ-OPS-002

## Context

ADR 0053을 100만 티켓 개인 스테이징 DB에 배포한 뒤 고정 검색어 20개로 smoke test를 진행했습니다. timeout은 없었지만 3회 반복 중 2회에서 검색 범위가 넓은 `topic` 요청의 PAGE 처리가 약 3.5초 걸렸습니다. 한 trace에서는 서버 처리 3.84초 중 `deskseed.search.page`가 3.81초, 감사 저장이 10.95ms였습니다. 최신순 부분문자열 SQL의 `pg_stat_statements` 기록은 완료한 45회 호출에서 최대 3.508초, 누적 임시 쓰기 10,341 blocks였습니다.

활성·종료 검색 테이블에는 각각 `staff_document gin_trgm_ops` 인덱스가 있었습니다. 하지만 결과를 `(updated_at desc, ticket_number desc)`로 반환할 때 사용할, 선행 필터 조건이 없는 `tickets` 인덱스가 없었습니다. 기존 큐 인덱스는 상태·우선순위·담당자 같은 컬럼이 앞에 있어 전체 검색의 최신순 정렬을 바로 제공하지 못했습니다.

이 기록을 바탕으로 최신순 인덱스를 후보로 정했습니다. 당시에는 실행계획을 확보하지 못해 실제 planner 선택은 배포 후 확인하기로 했습니다. 개인 스테이징의 자동 `EXPLAIN ANALYZE` 제한을 유지하고, 별도 승인한 2026-09-21 진단 결과를 아래에 기록했습니다.

## Decision

- Flyway V97에서 `tickets_staff_search_latest_idx`를 `(updated_at desc, ticket_number desc) include (id)`로 추가합니다.
- trigram 인덱스, 정확한 번호 검색, 부분문자열 일치, 권한, 활성→종료 조회 순서, cursor v3, 5초 statement timeout과 필수 감사를 유지합니다.
- 인덱스 추가 후 정확한 배포 SHA와 같은 검색 데이터로 반복 측정합니다. 검색 범위가 넓은 요청의 지연이 줄면 채택합니다.
- PostgreSQL 전역 설정, `work_mem`, Hikari 설정은 유지합니다. 추가 DB·캐시·외부 검색 서비스 없이 이 인덱스의 효과를 비교합니다.

## Consequences

- 티켓 생성과 `updated_at` 변경 때 인덱스 쓰기가 추가되고 저장 공간이 늘어납니다.
- Flyway가 인덱스를 만든 뒤 새 백엔드의 health를 확인합니다. 배포 기록에는 migration 시간, 디스크 여유, 이미지 버전, 재시작 횟수와 배포 후 회복 상태를 남깁니다.
- 결과에 포함되는 티켓, 정렬, cursor 형식, 권한과 감사 규칙은 유지합니다. 데이터 backfill이나 검색 테이블 재구축은 필요하지 않습니다.
- 측정 결과가 채택 기준에 못 미치면 애플리케이션을 직전 SHA로 롤백합니다. 사용하지 않는 인덱스는 후속 마이그레이션으로 제거하고, 이미 적용한 V97은 수정하지 않습니다.

## Verification

- migration 테스트에서 인덱스 컬럼 순서와 포함된 티켓 ID를 확인합니다.
- 검색 통합, cursor, SQL 구성, 권한, 감사와 활성·종료 검색 테이블 테스트를 통과했습니다.
- 개인 스테이징에서 고정 검색 데이터와 `topic:3` 진단을 고유 run ID로 반복했습니다. k6 종료 결과, Grafana·Tempo·Prometheus 기록과 누적 `pg_stat_statements` 통계는 각각 확인합니다.

## Follow-up verification — 2026-09-21

V97 배포 후 같은 100만 티켓 개인 스테이징 DB에서 `EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)`을 실행했습니다.

| 조건 | 값 |
| --- | --- |
| 실행 시각 | 2026-09-21 11:52:57–11:54:18 UTC |
| 배포 SHA | `b6be5ae34b54d442f933b660e88abb1d262a8dc0` |
| DB | PostgreSQL 17.11, Flyway 97 |
| 트랜잭션 | read-only, statement timeout 5초, lock timeout 1초 |
| 동시 부하 | 부하 테스트 없이 실행 |

이 진단은 별도로 승인한 일회성 실행입니다. 일반 운영 환경의 자동 진단 정책은 그대로 유지합니다.

- 변경 후 계획은 `tickets_staff_search_latest_idx`의 Index Only Scan으로 최신순 티켓을 읽고 활성·종료 검색 테이블의 PK로 조회했습니다. 두 계획 모두 Sort node와 임시 block I/O가 없었습니다.
- `topic:3` 활성 검색은 티켓 176개를 확인해 26건을 반환했습니다. 첫 실행은 6.777ms, warm 반복은 4.652ms였습니다.
- 종료 검색만 강제로 실행한 진단에서는 티켓 11,609개를 확인해 26건을 반환했습니다. 첫 실행은 65.427ms, warm 반복은 33.553ms였습니다. 실제 애플리케이션은 활성 검색에서 페이지가 채워져 이 요청의 종료 검색을 실행하지 않았습니다.
- 위 수치는 SQL 실행시간이며 HTTP 전후 비교와 별도입니다. V97 적용 전 실행계획은 확보하지 못했으므로 이전 정렬 비용의 비중은 확인되지 않았습니다. 이번 측정은 특정 입력의 실행계획과 시간입니다.

실행 요약과 검색 원문을 제거한 계획은 [검색 개선 인계 문서](../tasks/2026-09-21-postgresql-search-optimization-handoff.md#bounded-explain-analyze-결과)의 E-046에 있습니다. 결과는 2026-09-21에 측정했으며 2026-10-29에는 문서만 동기화했습니다.
