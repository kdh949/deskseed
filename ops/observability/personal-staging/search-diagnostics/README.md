# Personal-staging search diagnostics

기존 `production + personal-staging-observability`를 유지하는 선택적 준비 패키지다. 실제 적용·검증 상태는 [작업 기록](../../../../docs/tasks/2026-09-19-search-diagnostics-preflight.md)에 보존한다.

## Collector 준비

1. 애플리케이션 호스트의 private directory에 이 디렉터리의 `compose.yaml`, `postgres-queries.yaml` 및 `scripts/operations/prepare-search-diagnostics-db.sh`를 복사한다.
2. 기존 DB가 idle인 구간에 다음 명령으로 설정을 백업하고 statement 통계와 별도 monitor role을 준비한다. 최초 실행은 DB를 재시작한다. 기존 preload가 다른 확장을 포함하면 자동으로 덮어쓰지 않고 종료한다.

   ```bash
   CONFIRM_PERSONAL_STAGING_DIAGNOSTICS=true \
     bash prepare-search-diagnostics-db.sh /home/deskseed-deploy/search-diagnostics
   ```

3. 같은 디렉터리의 mode-0600 `.env`에 `DIAGNOSTICS_UID`, `DIAGNOSTICS_GID`, `DIAGNOSTICS_PASSWORD_FILE`, private `DIAGNOSTICS_BIND_ADDRESS`, 기존 `POSTGRES_DB`를 지정한다. 기본 network는 `deskseed_database`다. bind IP는 wildcard/public 주소를 쓰지 않고 호스트의 기존 private monitoring 경계 안에서 사용한다.
4. `docker compose -p deskseed-search-diagnostics config --quiet` 후 `up -d`. `pg_up=1`, `pg_exporter_last_scrape_error=0`, node CPU/memory/disk 통계가 나와야 한다. app schema의 SELECT 권한을 monitor에 부여하지 않는다.
5. `prometheus-jobs.yaml`은 기존 같은 이름의 job에 병합한다. 다른 프로젝트 설정을 보존하고 중복 scrape를 만들지 않는다. `promtool check config` 후 reload. 네 개 target 모두 `environment="personal-staging"`이어야 한다.

PG exporter 0.17.1의 custom query 설정은 deprecated 기능이므로 버전을 고정했다. 업그레이드할 때 쿼리와 노출 이름을 실제 PostgreSQL 17로 재검증한다. `pg_stat_statements`의 전체 SQL text export와 per-query 전 범위 collector는 사용하지 않는다. 참고: [PostgreSQL 통계 문서](https://www.postgresql.org/docs/17/pgstatstatements.html), [고정 exporter 버전 문서](https://github.com/prometheus-community/postgres_exporter/blob/v0.17.1/README.md).

## Backend 배포와 trace 연결

- 현재 배포 SHA에 계측 패치만 반영한 이미지를 사용한다. 개인 스테이징 deployer의 SHA/이미지 revision 검증을 유지한다.
- 기존 배포 env file에 `DESKSEED_SEARCH_DIAGNOSTICS_ENABLED=true`를 설정한다. 변경한 `compose.personal-staging-observability.yaml`이 이 값을 backend에 전달한다. 이전 SHA의 overlay는 이 변수를 전달하지 않으므로 코드·overlay를 함께 반영한다.
- mode-0600 bootstrap 파일의 소유 UID:GID와 `DESKSEED_RUNTIME_USER`를 맞춘다. 현재 호스트 값은 `1001:1001`이며, 값이 빠지면 이미지 기본 사용자로 시작해 파일을 읽지 못한다. 파일 권한을 넓히지 않고 runtime user를 유지한다.
- preflight 동안 `DESKSEED_PERSONAL_STAGING_TRACE_SAMPLING_PROBABILITY=1.0`, 이후 원래 값(현재 0.05)로 복원한다. latency 비교 시 양쪽 telemetry 설정을 동일하게 기록한다.
- `grafana-dashboard.json`은 UID `deskseed-search-diagnostics`, Prometheus/Loki UID `prometheus`/`loki`를 사용한다.
- `tempo-datasource.json`은 별도 UID `deskseed-search-tempo`이며 `pyroscope-datasource.json`의 UID `pyroscope`로 `tracesToProfiles`를 연결한다. API로 생성할 때의 JSON이며 provision YAML로 변환할 때 Grafana의 `$` 환경 변수 보간에 맞춰 escaping을 확인한다. 다른 프로젝트의 공용 datasource를 덮어쓰지 않는다.
- backend profiler는 `compose.personal-staging-diagnostics.yaml`을 추가한 경우에만 켠다. 기본 observability overlay에는 Java agent와 span processor가 없다. allocation/lock은 별도 overlay를 짧게 결합할 때만 `512k`/`10ms`로 시작한다.

## 본 부하 진입 확인

1. 별도 발생기에서 기존 보호 계정/검색 corpus로 1 VU smoke를 실행한다. `TEST_ENVIRONMENT=personal-staging`과 고유 `TEST_RUN_ID`를 지정한다. 단일 계정/관리자 smoke는 용량 측정이 아니다.
2. k6의 검색군 지표가 해당 실행 ID로 보인다. 짧은 smoke는 종료 후 stale marker가 있으므로 정확한 시간 구간 또는 `last_over_time(...[1m])`로 확인한다. k6 JSON은 ms, remote-write time trend는 seconds다.
3. Tempo에서 다음 query로 같은 요청의 count/page/audit span을 연다. `deskseed.search.query_class`, `deskseed.test_run_id`, `deskseed.search.case_index`가 입력 manifest와 맞는지 확인한다. case index는 seeded/shuffled group 내부 index이고 원문 query가 아니다.

   ```traceql
   {resource.service.name = "deskseed-backend" && resource.deployment.environment.name = "personal-staging" && span.deskseed.test_run_id = "실행-ID"}
   ```

4. 그 trace의 `Logs for this trace`를 열어 같은 `trace_id`의 고정 phase 완료 로그를 확인한다. 이어서 root span의 `Profiles for this span`에서 flame graph를 연다. query/SQL/bind/exception message가 없어야 한다. service가 `deskseed-observability-probe`인 transport probe는 이 gate를 대체하지 못한다.
5. DB count/page calls 증가, 실행 시간/읽기 I/O/temp blocks, blocking, host CPU/disk, Hikari acquire/pending을 같은 절대 구간으로 캡처한다. 통계 reset 또는 statement eviction은 측정 구간을 나누는 조건이다.
6. 모니터링 저장 공간이 최소 5GiB 및 10% 이상이고 예상 run 저장량의 두 배 이상인지 확인한다. 이는 collector 운영의 최소값이며 본 부하 계획이 20% 등 더 엄격한 기준을 두면 그 기준을 따른다. Loki ingest 오류/디스크 throttle이 없어야 한다. 이 값은 개인 서버의 초기 운영 gate이며 capacity SLA가 아니다.
7. 위 조건 하나라도 미확인이면 본 부하는 시작하지 않는다. 검색 결과 count/순위/일치도를 평가하는 gate는 추가하지 않는다.

## 결론을 낼 수 있는 범위

span은 요청별 phase wall time, `pg_stat_statements`는 SQL군별 집계 실행 통계다. 서로 같은 queryid/request를 직접 join한 데이터가 아니다. pool 대기, 감사 저장, count/page, I/O 또는 lock 대기를 분리하는 근거로 사용한다. 특정 인덱스나 조인/정렬 연산이 근본 원인이라는 결론에는 동일 입력·버전의 `EXPLAIN (ANALYZE, BUFFERS)` 등 추가 근거가 필요하다. 증거가 부족하면 Grafana만으로 원인을 확정했다고 기록하지 않는다.

실행계획은 개인 스테이징에서 실행하지 않는다. 같은 corpus case와 배포 SHA를 load DB에서 `backend`의 `captureSearchPlan` task로 재현하고, 생성된 `metadata.json`의 queryid/family/corpus hash를 보호 artifact index에 등록한다. `plan.json`은 합성 literal을 포함할 수 있으므로 Grafana/Prometheus에 원문을 넣지 않는다.
