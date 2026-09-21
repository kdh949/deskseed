# 요청-SQL-함수 성능 RCA vertical slice

## Goal

개인 스테이징 또는 load 검색 요청 한 건에서 안전한 trace phase, 고정 코드 위치, 집계 queryid, 보호된 실행계획, root-span profile을 순서대로 탐색할 수 있게 한다.

## Decision and source references

- Decision IDs: D-005, D-018, D-039, D-064, D-065
- Accepted ADRs: ADR-0047, ADR-0048
- PRD/domain: docs/01 sections 12 and 17, docs/23, docs/36
- API contract: 기존 staff search operation 유지; 공개 OpenAPI 변경 없음
- Verification gates: OPS-004, PERF-001, PERF-003, ACC-007, SEARCH-AUD-001, SEARCH-AUD-002

## Actor and source

- Actor: ACTIVE STAFF / AGENT_UI. plan capture는 load 운영자가 별도로 실행하는 SYSTEM 진단 도구다.
- 기존 staff session 권한과 `ALL_TICKETS` 검색 scope를 변경하지 않는다.
- request/correlation/test-run/corpus case ID는 trace/log 또는 보호 artifact에만 두고 metric/profile label에는 두지 않는다.

## Product and UX contract

- Requirement IDs: REQ-OPS-001, REQ-OPS-002, REQ-PERF-001, REQ-PERF-002, REQ-SRCH-001
- Grafana UID `deskseed-personal-staging-overview`와 `deskseed-search-diagnostics`를 유지한다.
- `No data`는 0 또는 정상으로 바꾸지 않고 수집 중단/선택 구간 미존재로 설명한다.

## In scope

- 검색 OVERALL/COUNT/PAGE/AUDIT bounded span과 code/query metadata
- Spring Boot OTel provider에 Pyroscope span processor 결합
- runtime과 load plan capture가 공유하는 검색 SQL plan builder
- load-only `EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON)` artifact
- load 및 opt-in personal-staging diagnostics Compose, datasource, dashboard, alerts, contract tests
- ADR/Decision/requirement/runbook 갱신

## Out of scope

- production 기본 배포의 profiler/exporter 활성화
- monitoring-server 반영, 개인 스테이징 배포, 부하·plan capture 실제 실행
- PostgreSQL schema migration, 공개 API 변경, retention 변경
- auto_explain, 전체 SQL/bind logging, 검색 원문을 metric/profile label로 수집하는 방식

## Invariants and failure semantics

- 검색·감사 transaction과 fail-closed audit 의미는 기존대로 유지한다.
- telemetry 생성·metric 등록·export 실패는 업무 결과나 기존 업무 예외를 바꾸지 않는다.
- 실행계획은 `environment=load`와 40자 SHA를 명시한 별도 명령에서만 수집한다.
- profiler/collector는 명시적 overlay가 없으면 꺼져 있다. rollback은 overlay 제거와 processor property false다.

## Data and privacy

- raw SQL, bind 값, 검색어, exception message, actor/ticket/request ID는 Prometheus/Grafana label 또는 profile label에 넣지 않는다.
- query summary, phase, normalized query class, outcome, 40자 revision만 bounded label/attribute로 사용한다.
- plan JSON은 합성 fixture literal을 포함할 수 있는 보호 artifact이며 Grafana에는 artifact locator만 연결한다.
- 감사 원장과 운영 telemetry는 별도 저장·retention·권한 경계를 유지한다.

## Threats changed

- public/wildcard bind, secret/PII label, raw SQL export, profiler의 production 기본 활성화, collector 과권한을 구성 계약에서 거부한다.
- plan CLI는 PostgreSQL read-only connection과 statement/lock timeout을 쓰고 load 외 환경을 거부한다.

## Acceptance scenarios

- Given diagnostics-enabled search, when one search succeeds, then HTTP 아래 OVERALL과 COUNT/PAGE/AUDIT span이 고정 metadata와 outcome을 가진다.
- Given arbitrary query class or exception content, when diagnostics exports, then class is `unclassified` and 원문/메시지는 유출되지 않는다.
- Given sampled root span and Pyroscope agent, when span starts, then `pyroscope.profile.id`가 붙고 Tempo에서 profile datasource로 이동한다.
- Given synthetic corpus case and load DB, when capture runs, then query family/queryid/SHA/corpus hash와 JSON/요약 artifact가 생성된다.
- Given production compose only, when effective config is inspected, then profiler와 DB/host exporter가 없다.

## Validation

- `cd backend && ./gradlew fastTest --tests 'dev.deskseed.foundation.*' --tests 'dev.deskseed.ticketing.internal.*Search*'`
- `bash scripts/test-production-compose-contract.sh`
- dashboard/datasource/rule JSON/YAML 및 PromQL/TraceQL 정적 계약 테스트
- 실제 live ingest, protected plan capture, profiling on/off overhead와 OPS-004는 별도 실행 권한 후 Pending으로 보고

## Compatibility and migration

- OpenAPI: none. Schema migration/backfill: none.
- 기존 application search semantics, cursor, count/page SQL 의미를 유지한다.
- rollback: diagnostics/profiling overlay 제거 후 이전 dashboard/rule provisioning 복원. telemetry rollback은 업무 데이터 rollback을 요구하지 않는다.

## Human explanation

- trace는 요청별 시간 귀속, pg_stat_statements는 query family 집계, plan은 별도 load 재현, profile은 sampled root span의 코드 실행 근거다. 서로 다른 grain을 하나의 측정치처럼 합치지 않는다.
- production 기본값과 개인 스테이징 opt-in을 분리해 진단 가치와 profiling/collector 위험을 함께 제한한다.
