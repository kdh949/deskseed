# ADR 0008: 데이터·검색·감사는 PostgreSQL부터 시작

- Status: Accepted
- Date: 2026-08-10

## Context

MVP에는 트랜잭션, 제약조건, 필터와 기본 검색이 필요합니다. 처음부터 저장소를 여러 개 쓰면 어느 데이터가 원본인지, 변경을 어떻게 맞출지부터 복잡해집니다.

## Decision

MVP의 애플리케이션 데이터는 PostgreSQL에 저장합니다. 인덱스는 실제 쿼리의 `EXPLAIN (ANALYZE, BUFFERS)` 결과를 보고 추가합니다. 검색 요구나 측정 결과에서 필요성이 확인되면 Elasticsearch를 비동기 검색 데이터 저장소로 도입합니다. Redis도 캐시·작업 조율·요청 제한의 측정 결과를 근거로 선택합니다.

## Consequences

- 하나의 원본 저장소를 사용해 로컬 실행과 복구를 단순하게 유지합니다.
- 조회용 데이터를 따로 만들 때는 이벤트 버전, 재전달·재구축 절차와 반영 지연을 확인할 방법이 필요합니다.
- 이후 AI V1의 공개 도움말 vector index는 ADR 0049에 따라 별도 PostgreSQL+pgvector에 저장합니다. Elasticsearch/OpenSearch나 다른 기능의 저장소 추가는 각각 따로 결정합니다.
