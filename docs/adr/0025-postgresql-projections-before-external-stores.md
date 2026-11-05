# ADR 0025: 조회용 데이터도 PostgreSQL부터 사용

## Status
Accepted

## Context
검색, 대시보드와 export에는 조회에 맞춘 데이터가 필요합니다. 초기 규모에서는 Kafka·Elasticsearch·warehouse를 함께 운영할 만큼의 요구가 확인되지 않았습니다.

## Decision
PostgreSQL 쿼리, view, 시간 구간·조회용 테이블과 materialized view부터 사용하고 실행계획과 지연시간을 기록합니다. 기능이나 성능의 한계가 측정되면 외부 저장소를 검토하며, 데이터 재구축과 동기화 방법을 함께 정합니다.

## Alternatives
- 초기부터 Elasticsearch와 warehouse 사용: 초기 요구에 비해 운영 비용이 큽니다.
- 모든 과거 지표를 현재 업무 row에서만 계산: 과거 상태를 잘못 해석하거나 반복 조회 비용이 커질 수 있습니다.

## Consequences
조회용 데이터마다 처리 위치를 나타내는 checkpoint와 재구축 절차를 둡니다. 원본 데이터는 PostgreSQL에서 관리합니다.

이후 AI V1의 공개 도움말 vector index는 ADR 0049에 따라 별도 PostgreSQL+pgvector에 저장합니다. 원본 도움말과 현재 공개 여부는 계속 Deskseed Backend에서 관리하고, AI 인덱스는 검색을 위한 데이터로 사용합니다.
