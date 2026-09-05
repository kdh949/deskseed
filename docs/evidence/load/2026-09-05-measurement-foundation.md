# Load measurement foundation verification — 2026-09-05

## Scope and outcome

REQ-OPS-001 / REQ-PERF-002 / REQ-AUTH-003 / REQ-COL-002; D-005, D-018, D-039, D-060, D-064; Accepted ADR 0047.

운영자가 기존 계측으로 부하 실행의 유효성, 사용자 영향, 앱/DB/호스트 병목을 같은 시간대에 비교할 수 있도록 수정했다. 대시보드는 overview 20개, diagnostics 43개 패널이며 각 dashboard의 측정 범위 안내 패널을 포함한다. 기존 panel ID와 dashboard UID를 유지했다.

- k6 percentile의 잘못된 합산, 시간 단위, 존재하지 않는 interrupted metric, 혼합 단위와 호스트 필터를 수정했다. HTTP method, Hikari pool, WebSocket type/code 등 범례의 진단 차원을 보존한다.
- load profile에서 기존 GC/Hikari acquire/usage histogram과 Tomcat MBean registry를 활성화했다. Prometheus의 7개 target에 공통 `host` label을 추가하고 기존 PostgreSQL row/read/write counters의 drop을 제거했다.
- limiter, WebSocket connection outcomes, DB wait/transaction age/statement I/O, Hikari hold/timeout, GC pause fraction, container CPU/throttle/memory, disk/network 및 scrape 상태 패널을 추가했다. 새 업무 metric이나 앱 span은 추가하지 않았다.
- WebSocket 기본 cookie를 `DESKSEED_SESSION`에 맞추고, 해당 ticket snapshot 수신을 필수 check로 만들었다. 잘못된 JSON/null 메시지를 실패로 기록한다.
- k6 전역 run tag, runner 종료 코드 artifact, 결과 덮어쓰기 방지, threshold 없는 manifest의 `INCOMPLETE`, 절대 시간 dashboard 링크를 추가했다. 기존 gauge 대시보드에 맞춰 runner의 native histogram 모드를 명시적으로 끈다.

## Passed

| Verification | Result / evidence |
| --- | --- |
| Node regressions | `node --test ops/observability/tests/*.test.mjs tests/load/tests/*.test.mjs`: 39 passed, 0 failed |
| Actual Prometheus registry | `cd backend && ./gradlew --no-daemon test --tests '*LoadObservabilityConfigurationTest'`: 3 passed. 실제 load YAML을 적용한 registry에서 GC/acquire/usage Timer의 bucket/count 및 environment/service label 확인 |
| Backend fast suite | `cd backend && ./gradlew --no-daemon fastTest`: 130 passed, 0 failed/skipped. 위 focused test를 포함하므로 별도 합산하지 않음 |
| Dashboard PromQL | Prometheus 3.14.0 `promtool`: 115개 실제 query 파싱, operation별 percentile 보존/다른 host·stack 제외/미수집 series의 empty 결과 3개 합성 사례 통과 |
| Prometheus configuration | 3.14.0 `promtool check config`: scrape config 및 기존 alert 8개 문법 통과. 로컬 임시 사본에서 rule file 절대 경로만 실제 checkout으로 치환. alert 동작/임계값 검증을 의미하지 않음 |
| Compose | 합성 bind/endpoint 값으로 `compose.yaml` + `compose.observability.yaml`, `observability` profile의 `config --quiet` 통과. 컨테이너를 시작하지 않음 |
| k6 inspection | 공식 k6 2.0.0 macOS arm64 binary로 agent-read/public-request/customer-auth-limiter/collaboration-websocket/mixed-workload의 smoke options 5개 inspect 통과. 전역 및 executor별 run tag 확인 |
| Actual k6 remote write | `scripts/load/check-k6-export.py`: 고정 k6 2.0.0 → 임시 loopback Prometheus 3.14.0 수집 통과. 실제 load option/metric 모듈을 import하고 HTTP operation 2개, VU/check/counter tag, Time Trend 단위 검증. [합성 export 결과](2026-09-05-k6-export-contract.json) |
| Documentation | `make docs-check`: core bundle check, bundle regression 1개, API documentation quality 36개, documentation validator 2개 및 문서 계약 검증 통과 |
| Patch/shell | `git diff --check`, `sh -n scripts/load/run-k6.sh scripts/validate-observability-config.sh` 통과 |

WebSocket cookie/snapshot/malformed-message 및 runner identity/exit/overwrite 회귀 검증은 수정 전 실패를 확인하고 수정 후 통과했다. query selector 검증도 대문자가 포함된 TCP metric의 누락을 검출하도록 보완하고 수정했다.

## Unit correction verified at runtime

초기 검토의 “k6 gauge percentile은 ms” 판단은 실제 remote-write 검증으로 정정했다. `k6_http_req_duration_p95`라는 이름은 유지되지만 **값은 초**다. 제어된 250ms Time Trend 입력은 `0.25`로 수집된다. k6 summary와 threshold `*_MS` 입력만 ms를 유지한다. 따라서 overview #2의 단위는 `s`다. [고정 버전의 gauge 변환](https://github.com/grafana/k6/blob/v2.0.0/internal/output/prometheusrw/remotewrite/trend.go#L50-L52), [초 단위 정규화](https://github.com/grafana/k6/blob/v2.0.0/internal/output/prometheusrw/remotewrite/trend.go#L221-L231).

## Not run / remaining acceptance

- Docker daemon이 실행 중이지 않아 전체 `scripts/validate-observability-config.sh`, container 기반 Alloy/Nginx 검증과 실제 observability stack 기동은 미실행이다. Compose 파싱 및 native Prometheus/k6 검증은 위와 같이 별도로 수행했다.
- 실제 서버 배포, VPN/firewall reachability, backend/DB/exporter 실제 series 생성, Grafana import/render, Loki/Tempo/Pyroscope ingest/correlation은 검증하지 않았다. loopback synthetic export는 Deskseed 앱 통합 검증이 아니다.
- 실제 인증된 one-VU smoke, 원격 `verify-metrics.mjs`, PERF-001/002/003/004, AUTH-006, CHN-012, OPS-004의 live acceptance, telemetry overhead A/B, 포화·회복·최대 용량 측정은 미실행이다.
- 신규 JDBC/audit/dependency span, worker 세부 계측, 신규 business metric, native histogram 전환, alert 논리/알림 경로 재설계는 구현하지 않았다.

## Boundaries, compatibility and operational trade-offs

- 운영 계측 actor/source는 SYSTEM/load이며 합성 사용자 요청은 기존 STAFF/CUSTOMER 인증 경로를 쓴다. PUBLIC/INTERNAL projection, scope/resource constraints, 감사 persistence, transaction/concurrency/idempotency/retry, 제품 데이터와 privacy/retention 정책은 바꾸지 않았다. metric label에 ticket/request/actor ID, SQL text, 본문, 검색어, credential을 추가하지 않았다.
- schema migration/OpenAPI 변경은 없다. 결과 manifest는 schema version 2이며 `INCOMPLETE`와 `dashboardLinks`가 추가된다. 기존 consumer는 새 상태를 이해해야 한다. runner는 이제 env file의 명시적 `TEST_RUN_ID`를 요구하고 같은 결과 prefix를 덮어쓰지 않는다.
- 서버 metrics의 `host` label을 먼저 수집한 뒤 새 dashboard를 사용해야 한다. 이전 데이터에는 label이 없어 새 selector로 조회되지 않는다. k6 run ID는 앱·DB metric을 격리하지 않으므로 같은 host의 겹친 부하를 별도 실행처럼 해석하지 않는다.
- histogram/보존 counter로 series 수가 늘어난다. 샘플 수와 scrape 시간을 확인하고 부하 측정 시 overhead를 검증해야 한다. `No data`는 정상 zero가 아니며 counter 증가량 추정·누적 percentile·선택 시간 범위를 최종 summary와 구별해야 한다.
- rollback은 이 작업의 load 설정, scrape fragment, dashboard와 runner를 이전 버전으로 복원하는 것이다. 기존 사용자 수정은 유지했으며 운영 서버 변경, commit/push는 수행하지 않았다.

실제 적용 순서와 판정 기준은 [운영 runbook](../../runbooks/deskseed-load-observability.md)의 3–5절을 따른다. 새 패널은 원인을 좁히는 근거를 제공하며 앱의 처리 용량을 증명하지 않는다.
