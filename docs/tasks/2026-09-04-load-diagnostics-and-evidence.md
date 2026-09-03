# Load Diagnostics and Evidence Task Brief

## Goal

운영자가 한 번의 Deskseed 부하 실행에서 도착 부하, 사용자 영향, 최초 포화 자원, 추적·로그·프로파일 상관관계와 재현 정보를 함께 확인한다.

## Decision and source references

- Decision IDs: D-005, D-018, D-039, D-060, D-064
- Accepted ADRs: 0018, 0028, 0043, 0047
- Requirements: REQ-OPS-001, REQ-PERF-002, REQ-COL-002, REQ-AUTH-003
- API operations: product OpenAPI change 없음; private management/exporter surface만 변경
- Verification gates: OPS-004, PERF-001/002/003/004, CHN-012, AUTH-006

## Actor and source

- Actor: telemetry는 SYSTEM, 합성 부하는 기존 CUSTOMER/STAFF 인증 경계를 사용한다.
- Source: 격리된 load Compose 환경, 별도 k6 host, 기존 private monitoring server
- Required access: VPN-private scrape/ingest 경로와 기존 customer/staff 권한
- Correlation: `test_run_id`, request/correlation ID는 로그·trace·실행 증거에만 두고 metric identity label로 확장하지 않는다.

## In scope

- query text 없는 `pg_stat_statements` 호출/총·평균 실행시간 진단
- Nginx 내부 status exporter와 원문 URL·IP·cookie·Authorization 없는 bounded access log
- Alloy accepted/refused span과 exporter queue 상태
- mail/webhook backlog oldest-age 및 처리율
- run-filtered Grafana overview/diagnostics와 Tempo/Loki/Pyroscope 이동 경로
- 선언형 k6 latency/error budget, 실행 evidence manifest, 혼합 HTTP/WebSocket workload

## Out of scope

- 기존 monitoring server의 자동 원격 변경
- product OpenAPI, DB schema, 권한, audit event, canonical transaction 변경
- 직접 span-profile ID bridge, production collector topology, capacity 최적화
- 실제 SLA/capacity 수치 확정; 지원 환경 실행 전까지 `NOT RUN`

## Invariants and failure semantics

- telemetry 실패는 ticket/audit/mail/webhook transaction 결과를 바꾸지 않는다.
- required audit는 계속 fail-closed이며 지표가 대체 원장이 되지 않는다.
- non-smoke와 write 부하는 명시적 target/write 확인 없이는 시작하지 않는다.
- expected 429, unexpected response, server 5xx, dropped iteration을 서로 다른 결과로 판정한다.
- exporter/metric labels와 Nginx 로그에는 raw URL, query, actor/ticket/email, secret가 없다.

## Data and privacy

- Read: bounded route/status/timing/bytes, aggregate worker state, DB queryid aggregate, safe trace resource attributes
- Write: Prometheus/Loki/Tempo/Pyroscope telemetry와 local k6 evidence artifact
- Forbidden: body, search query, email, password, token, cookie, Authorization, IP, full URL, SQL query text
- Retention/export: 기존 private monitoring retention을 따르며 product export/webhook에는 노출하지 않는다.

## Acceptance scenarios

- Given load overlay, when monitoring scrapes PostgreSQL and Nginx, then query text와 요청 원문 없이 queryid aggregate와 bounded route 지표를 얻는다.
- Given Tempo outage or Alloy pressure, when spans arrive, then accepted/refused/sent/failed와 queue utilization로 손실 위치를 구분한다.
- Given mail/webhook backlog, when workers process it, then backlog count, oldest age, success/failure rate를 함께 본다.
- Given a named non-smoke run, when required budgets or confirmations are absent, then k6 fails before load generation.
- Given a completed run, when evidence is reviewed, then commit/environment/fixture/load model/telemetry/budget/result가 한 manifest에 있다.
- Given mixed traffic, when the operator declares per-flow rates and WebSocket connections, then each flow remains separately tagged and independently diagnosable.

## Validation

- `node --test ops/observability/tests/*.test.mjs`
- `./scripts/validate-observability-config.sh`
- `cd backend && ./gradlew --no-daemon test --tests '*BacklogMetricsTest' --tests '*WebhookDeliveryMetricsTest'`
- k6 2.0 `inspect` for every scenario including mixed workload
- OPS-004 live ingest, smoke, AUTH-006, CHN-012, PERF evidence: private deployment에서 별도 실행

## Compatibility and migration

- OpenAPI/product DB migration: 없음
- Default Compose: observability profile 미사용 시 exporter port 추가 없음
- Rollback: 각 수직 슬라이스 commit revert, overlay 중지, monitoring fragment 제거. Canonical data 정리 불필요

## Human explanation

- 가정한 병목을 최적화하지 않고 user impact에서 bounded resource signal로 내려가는 진단 경로를 먼저 만든다.
- 성능 예산은 hardware-independent 고정값을 발명하지 않고 실행자가 환경별로 선언하며 manifest에 남긴다.
- 직접 span-profile bridge 대신 현재 ADR 0047의 service/environment/time-window correlation 경계를 유지한다.
