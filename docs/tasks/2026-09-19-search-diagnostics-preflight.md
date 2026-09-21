# Search SQL observability preparation

상태: 2026-09-19 개인 스테이징 계측 배포·실제 검색 trace/log/metric 연결 검증 완료. 본 부하와 SQL 튜닝은 미실행.

## Goal and scope

운영자가 개인 스테이징의 agent search 지연을 Grafana에서 조사하기 전에, 환경 라벨·DB/호스트 수집·검색 단계 trace 연결을 준비한다. 본 부하와 SQL/인덱스 최적화는 실행하지 않는다. 부하 실행 계획은 별도 [agent-read 계획](2026-09-19-agent-read-search-performance-plan.md)에 유지한다.

- Decisions: D-005, D-018, D-039, D-064, D-065; Accepted ADR-0047/0048의 private telemetry·비감사 데이터 경계.
- Requirements: REQ-OPS-002, REQ-PERF-002. Gates: OPS-004 중 metrics/log/trace/privacy, ACC-007. CPU profiling을 포함한 OPS-004 전체 통과는 주장하지 않는다.
- Actor/source: 운영자 SYSTEM 관측 준비; smoke는 기존 STAFF 세션 / AGENT_UI와 필수 SEARCH_EXECUTED 감사를 사용한다.
- API: 기존 agent search 요청/응답·권한 계약 유지. 선택적 진단 header는 인증/권한/검색 동작에 영향을 주지 않는다.
- 이번 사용자 요청으로 ADR-0048 최초 작업 범위 밖이었던 DB 통계와 제한된 호스트 지표를 별도 opt-in collector로 추가한다. 원래 Alloy의 Docker socket/host filesystem 접근 경계는 그대로 유지한다.

## Implementation boundaries

- `SearchDiagnostics`는 기본 비활성이다. 관측 profile에서 `DESKSEED_SEARCH_DIAGNOSTICS_ENABLED=true`로 켠다.
- count SQL, page SQL, query 보호/필수 감사 저장 호출에 고정 이름의 span과 histogram을 남긴다. JDBC 계측은 네트워크·드라이버·row mapping을 포함한 wall time이고, audit 계측은 최종 transaction commit을 포함하지 않는다.
- search class는 고정 목록으로 정규화하고 잘못된 값은 `unclassified`다. run ID·case index·request/correlation ID는 trace 필드로만 전달한다. SQL 원문·검색어·bind 값·예외 메시지·사용자/티켓 ID는 추가하지 않는다.
- canonical audit의 fail-closed, transaction, cursor, idempotency, 재시도 및 서버 권한 검사는 변경하지 않는다. telemetry는 audit을 대체하지 않는다.
- PostgreSQL 17의 statement 통계에서 count/page SQL군을 식별해 고정 phase와 queryid만 export한다. 최대 64개 statement. queryid는 단일 trace와 직접 결합된 ID가 아니며, 검색군별 DB 평균을 뜻하지 않는다.
- 별도 `deskseed_search_monitor`는 `pg_monitor`, DB CONNECT와 schema USAGE만 갖는다. application table SELECT/write 권한을 부여하지 않는다. 기본 read-only, connection limit 3, statement timeout 5s, password file 0600.
- node-exporter는 read-only `/proc`, `/sys`와 CPU/memory/diskstats/pressure collectors만 사용한다. rootfs, Docker socket, host PID/network, privileged 권한을 추가하지 않는다. 9100/9187은 private IP에만 bind한다.
- 새 제품 migration, OpenAPI 변경, retained audit 데이터 변경은 없다. 운영 extension/role/DB 설정만 별도 관리한다.

## Live changes — 2026-09-19 KST

대상: `deskseed-deploy@172.16.16.19`, monitoring `kdh949@172.16.16.18`.

1. 기존 배포 SHA `2c417b249dfefd7743804c11e4608675fb049ddc`, production + personal-staging-observability를 유지했다. 데이터 통계상 tickets 1,000,000, staff 64, search projection 1,000,138행이며 정확한 fixture 동일성 검증은 별도다.
2. DB 설정 백업: `/home/deskseed-deploy/search-diagnostics/before-zwsB8jZh/`. `shared_preload_libraries=pg_stat_statements`, `track_io_timing=on`, utility statement 추적 off를 적용하고 DB를 재시작했다. 기존 서비스 health 정상 복귀.
3. 별도 Compose project `deskseed-search-diagnostics`로 PostgreSQL/node exporter를 설치했다. 파일 위치 `/home/deskseed-deploy/search-diagnostics/`. `pg_up=1`, `pg_exporter_last_scrape_error=0` 확인.
4. monitoring의 기존 Prometheus 설정에서 활성 Deskseed 4개 job의 environment만 `load` → `personal-staging`으로 변경했다. `promtool check config` 통과 후 HUP reload, 4개 target 모두 up=1. 백업 `/home/kdh949/deskseed-search-preflight-20260919/prometheus.before.yaml`. 기존 비활성 load의 containers/nginx/redis target은 별도이며 이 readiness에 포함하지 않는다.
5. Loki가 ready를 반환하면서도 `Ingester is shutting down`으로 쓰기를 거부하는 상태를 확인해 해당 container를 재시작했다. 사용자 승인으로 공유 unused Docker build cache를 정리했다. Docker 보고 회수량 8.575GB, filesystem 여유 약 919MB → 8.5GB, 사용률 99% → 86%.
6. `deskseed-search-diagnostics` 대시보드를 기존 file provider에 추가했다. root 소유 shared Tempo datasource는 수정하지 않고, `deskseed-search-tempo`를 추가해 native OTLP `trace_id`로 로그를 연결했다.

## Verification evidence and limits

- 로컬 `SearchDiagnosticsTest` 4개 + 개인 스테이징 관측 설정 테스트 3개 통과: parent trace 상속, 고정 label, run/case trace 필드, 민감한 예외 내용 배제, disabled 경로, metric registry 실패가 업무 결과/예외를 바꾸지 않는지 검증.
- 계측을 켠 `AgentTicketSearchIntegrationTest` 8개 통과: 기존 검색/권한/필수 감사 저장 및 실패 처리, 실제 검색의 count/page/audit 지표 생성 확인.
- pinned k6 2.0.0 mock smoke + workload tests: 5개 통과. 새 diagnostic header와 기존 세션·검색 다양성·빈 결과 허용 검증.
- Mac을 별도 발생기로 실제 HTTPS 검색 1회 실행. bootstrap 관리자 단일 세션 사용으로 상담사 용량 증거가 아니다. checks 5/5, 업무 검색 1회 성공, 약 211ms는 연결 확인용 단일 관측값이다.
- run `search-preflight-20260919`; 결과 `/private/tmp/deskseed-search-preflight-20260919/results/agent-read-20260919T071124Z-Fj3li0/`. credential 파일은 같은 보호 디렉터리 안에만 있고 저장소에 포함하지 않는다.
- 수집 복구 후 counter 증가와 dashboard 표시 재확인을 위해 별도 1회 smoke `search-preflight-20260919-verify`도 통과했다. 결과 디렉터리 suffix `agent-read-20260919T072544Z-t53M0x`. 총 업무 검색은 2회이며 본 부하는 아니다.
- PostgreSQL count/page 각각 1회: queryid `-7630809241842061132` / `-3771927671543802771`; DB 실행 시간 약 8.69ms / 2.62ms. 부하 성능/원인 결론으로 사용하지 않는다.
- Prometheus remote write에 `k6_agent_operation_duration_p95=0.211091792` seconds가 저장됐다. k6 JSON summary의 211.091792ms와 단위 대조. 짧은 smoke 종료 후 stale marker로 instant query가 비는 것은 정상이며 range/last_over_time으로 확인했다.
- operator transport probe trace ID `75eeaf3e4c8afe20d0f1d12e3b68e930`, service `deskseed-observability-probe`. Alloy 수신 200, Loki `trace_id` 조회, Grafana Tempo의 같은 1ms trace 표시 모두 확인했다. 애플리케이션이 생성한 검색 trace로 주장하지 않는다.
- Production Compose contract, 변경 문서 링크, dashboard JSON, live Prometheus의 18개 panel query 문법, shell syntax, diff whitespace 검증 통과. `make docs-check`는 기존 `docs/frontend-audit-2026-09-11.md`의 절대경로/줄번호 링크와 기존 screenshot asset 검사에서 실패했다. 해당 비관련 문서/이미지는 수정하지 않았다.
- 아래 최종 배포에서 실제 검색의 count/page/audit span, 동일 trace ID의 로그 및 지표를 검증했다. 본 부하, CPU profile, SQL 실행계획 수집/튜닝, 전체 CI suite는 미실행이다. 이미지 게시 workflow의 배포 계약 검사와 두 이미지 빌드는 통과했다.

## Instrumentation publication

사용자 승인으로 현재 배포 SHA를 기반으로 별도 worktree/branch `feature/search-diagnostics-preflight`를 생성했다. 계측과 테스트·배포 brief 9개 파일만 커밋한 SHA는 `1ef90c0ce932067c1095486ccc2a6273aa0b8d8a`다. 현재 로컬 HEAD의 AI 기능과 기존 부하 스크립트 변경은 이 배포 커밋에 포함하지 않았다.

- 서버 기준 SHA에서도 검색 8 + 진단 4 + 관측 profile 5 = **17개 테스트**와 personal staging deployment/production Compose 계약 통과. 테스트 XML에서 tests=17, failures=0, errors=0을 확인했다.
- [계측 이미지 빌드](https://github.com/kdh949/deskseed/actions/runs/35429854317)와 [최종 배포 설정 보완 빌드](https://github.com/kdh949/deskseed/actions/runs/35430382270)의 validation/backend/frontend가 모두 success다.

## 최종 배포 및 실제 연결 확인

- 최종 SHA: `4c9a3bff87b3535b0c9bfacc786b2c714f250a14`. 서버 clean checkout, backend/frontend tag 및 OCI revision이 모두 일치한다. 기준 SHA 대비 변경은 검색 계측·테스트·배포 brief와 기존 runtime UID를 보존하는 Compose 설정뿐이다. AI 제품 기능이나 SQL/인덱스 변경은 없다.
- 첫 배포에서 image 기본 user `deskseed`(UID 100)가 mode-0600 bootstrap 파일(UID:GID 1001:1001)을 읽지 못해 backend가 종료됐다. 기존 UID로 복구한 뒤 `DESKSEED_RUNTIME_USER`를 Compose에 반영하고 1001:1001을 env에 고정했다. 파일 권한·비밀번호·계정은 변경하지 않았다. 최종 배포는 임시 override 없이 canonical deployer로 통과했다.
- 기존 deployer의 image pull/up 과정에서 PostgreSQL과 Redis container도 재생성됐다. 최종 PostgreSQL 17.11 / Redis 8.2.9를 이후 측정 기준으로 기록한다. volume 삭제나 통계 reset은 하지 않았다. `pg_stat_statements`와 monitor 권한/수집은 정상 유지됐다.
- `db-migrate`, `db-permissions` exit 0. backend/frontend 및 기존 의존 서비스 정상. 개인 스테이징 private health와 HTTPS health 200/UP, public `/actuator/prometheus` 404, private `:9090/actuator/prometheus` 200.
- `production + personal-staging-observability`, `DESKSEED_SEARCH_DIAGNOSTICS_ENABLED=true`, runtime user 1001:1001을 확인했다. 임시 sampling 1.0은 **0.05로 복원**하고 backend/frontend를 재생성해 실제 runtime env와 health를 재확인했다.
- 보호 env 백업: `/home/deskseed-deploy/search-diagnostics/production.env.before-instrumentation-1ef90c0`. 일시 복구에 사용한 `runtime-user.yaml`은 최종 Compose에 사용하지 않는다.

실제 HTTPS 검색은 준비 전체에서 총 5회 실행했고 각 smoke checks 5/5, 오류 0이었다. 앞의 미계측 2회, 첫 계측 이미지 1회, 최종 이미지 2회다. bootstrap 관리자 단일 세션과 단일 실제 티켓 번호이므로 입력 순환·상담사 용량·성능 SLA를 검증한 결과가 아니다. 검색 정답/순위/count oracle은 실행하지 않았다.

| Run | 최종 증거 |
|---|---|
| `search-preflight-20260919-instrumented` | trace `484750b76a6d4c46cc279b9eaed64dde`, 첫 계측 이미지 연결 확인 |
| `search-preflight-20260919-final` | trace `d14707385eedef1485c5dc39b90d545a`, 최종 SHA 16:52:14 KST 검색 |
| `search-preflight-20260919-final-repeat` | 16:52:54 KST smoke, 호출 counter/phase histogram 증가 확인 |

- 최종 trace에서 `deskseed.search.count/page/audit`가 같은 HTTP 요청 trace와 같은 parent span에 연결됐다. `deskseed.search.query_class=single-smoke`, `deskseed.test_run_id=search-preflight-20260919-final`, `deskseed.search.case_index=0`, environment=personal-staging을 확인했다.
- Grafana의 실제 `Logs for this trace` 링크로 이동해 동일 trace ID의 고정 phase 로그 3개를 확인했다. 최종 trace와 Loki 로그를 나란히 표시한 화면을 대화 안에 캡처했다. 별도 PNG 파일로 저장했다고 주장하지 않는다.
- Prometheus에서 count/page/audit histogram과 Hikari acquire histogram을 확인했다. DB count/page 호출은 각각 2 → 5로 증가했고 reset timestamp는 유지됐다. 필수 4 target up=1, pg_up=1, exporter scrape error=0.
- [고정 구간 대시보드](http://172.16.16.18:3000/d/deskseed-search-diagnostics?from=1789804290000&to=1789804590000&var-environment=personal-staging&var-run=%24__all). 검색군 HTTP 지표, 단계 지표, DB·pool·호스트를 같은 구간에서 조회한다. backend/DB 지표는 환경 단위이며 run 필터가 해당 요청만 분리하는 것은 아니다.
- 보호 증거 디렉터리 `/private/tmp/deskseed-search-preflight-20260919/`: `final-trace-proof.json`, `final-trace-search.json`, `final-prometheus-proof.json`, 실행별 summary/manifest. 최종 smoke 결과 suffix는 `agent-read-20260919T075210Z-a2cF42`, `agent-read-20260919T075254Z-bl7oAB`다.

## 본 부하에서 추가 확인할 조건

관측 연결 완료와 본 부하 진입 승인은 구분한다. 실제 ACTIVE AGENT 계정 풀, corpus 입력 순환, fixture/projection 정합성, 저장 여유와 예상 증가량 등 실행 계획의 시작 조건을 확인해야 한다. 모니터링 디스크는 최종 확인 시 약 7.9GiB/13% 여유였다. 실행 계획의 디스크 20% 중단 기준에는 미달하므로 장시간/상승 부하는 추가 공간을 확보하거나 측정된 저장량을 근거로 기준을 명시적으로 재결정하기 전까지 시작하지 않는다.

현재 계측은 count/page/audit, pool 대기, DB 실행 통계/I/O/lock, 호스트 자원을 분리할 근거다. 특정 인덱스·조인·정렬 연산이 병목 원인이라는 확정에는 해당 입력·버전의 실행계획 등 추가 증거가 필요하다. pg_stat_statements queryid를 개별 trace와 직접 연결했다고 주장하지 않는다.

## Rollback and trade-off

- 계측은 `DESKSEED_SEARCH_DIAGNOSTICS_ENABLED=false`, trace sampling은 기존 0.05로 복원 가능하다. 계측 전후 비교에는 같은 설정을 사용한다.
- collector만 중지할 때 해당 별도 Compose project에서 `stop`을 사용한다. main application project나 volume은 제거하지 않는다.
- DB는 백업의 `postgresql.auto.conf`를 복원하고 기존 preload 설정으로 재시작한다. extension/monitor role 제거는 exporter 중지 후 별도 확인하며 application data는 변경하지 않는다.
- Prometheus는 백업 파일 내용을 기존 bind-mounted inode에 복원하고 promtool 검증 후 HUP reload한다. 전용 대시보드/datasource는 추가한 UID만 제거할 수 있다.
- 통계/샘플 trace에는 관측 비용과 누락 가능성이 있다. 동시 발생만으로 원인을 확정하지 않는다. 특정 인덱스·조인·정렬 원인을 확정하려면 해당 입력/버전의 실행계획 증거가 추가로 필요하다.
