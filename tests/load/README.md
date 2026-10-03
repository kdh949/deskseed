# Deskseed k6 load suite

부하 발생기는 Deskseed·모니터링 호스트와 분리한다. `agent-read`는 disposable load 또는 지정한 개인 스테이징을 대상으로 사용할 수 있다. 개인 스테이징은 production + personal-staging-observability 구성을 유지하며 load Compose overlay를 섞지 않는다. 이 문서의 명령을 준비하는 것과 실제 부하 실행은 별개다.

## agent-read 입력 준비

실제 합성 티켓 데이터에서 여러 검색어를 추출한다. 다음 스크립트는 전체 파일을 스트리밍하면서 티켓·고객과 PUBLIC/INTERNAL 댓글을 reservoir sampling하고, 기본 8개 군 × 20개 = 160종을 만든다. DB/API 호출, 검색 정답·순위 평가는 하지 않는다.

```bash
python3 scripts/load/build-search-corpus.py \
  --data-dir /Users/donghyunkim/Downloads/Deskseed-demo-data/deskseed-demo-full \
  --output /absolute/path/to/load-fixtures/search-corpus.json
```

- 입력: `manifest.json`과 tickets, ticket_comments, customers, support_groups, staff_accounts의 TSV gzip 파일. synthetic=true인 fixture만 허용한다.
- 옵션: `--seed 20260919`, `--sample-size 4000`(테이블 또는 댓글 visibility별), `--per-group 20`. 결과는 mode 0600으로 생성하고 기존 파일을 덮어쓰지 않는다.
- 번호 10%, 요청자 10%, 구체적 표현 20%, 주제어 20%, 공통 표현 15%, 짧은 단어 10%, 내부 메모 10%, 없는 값 후보 5%다.
- 없는 값은 합성 후보이며 서버에서 0건이라고 보증하지 않는다. 실제 데이터 적재 확인과 빈 결과 관측은 실행 준비 단계에서 한다.
- 검색어는 군별로 seed를 이용해 섞은 뒤 순환한다. 전역 iteration 번호로 선택하므로 VU마다 같은 검색어부터 반복하지 않는다. 100 iterations 단위로 지정 비중을 유지하고, 군 안에서 전체 목록을 사용한 뒤 재사용한다.
- non-smoke는 최소 80개 고유 검색어를 요구한다. 개별 값의 장기 요청 비중은 최대 5%다. 기본 생성값은 최대 1%다.

corpus 형식은 version=1, datasetId, groups 배열이다. 각 group은 고정 queryClass, 정수 weight, queries 문자열 배열을 가진다. 원문·이메일이 포함되므로 Git에 커밋하지 않는다. 비밀 없는 샘플 형식과 선택 동작은 `search-workload.test.mjs`에서 확인할 수 있다.

계정은 외부 mode-0600 `staff-accounts.json`에 `[{"email":"...","password":"..."}]` 형태로 준비할 수 있다. ACTIVE AGENT를 VU당 한 명씩 배치하고 계정 수는 MAX_VUS 이상이어야 한다. 첨부 fixture의 직원은 로그인 가능한 계정이 아니다. 계정 파일 없이 STAFF_EMAIL/STAFF_PASSWORD를 사용하면 한 계정을 공유하며 manifest에 single-account로 기록한다.

## agent-read 실행 설정

mode-0600 환경 파일 예시:

```dotenv
TARGET_URL=https://your-staging-host.example
TEST_ENVIRONMENT=personal-staging
TEST_RUN_ID=agent-read-baseline-001
LOAD_PROFILE=baseline
CONFIRM_DESKSEED_LOAD_TARGET=your-staging-host.example
TARGET_ITERATIONS_PER_SECOND=10
PREALLOCATED_VUS=45
MAX_VUS=45
WARMUP_DURATION=3m
LOAD_DURATION=10m
AGENT_READ_MODE=composite
AGENT_SEARCH_SORT=updatedAt:desc,ticketNumber:desc
STAFF_VIEW_KEYS=pending
K6_PROMETHEUS_RW_SERVER_URL=http://your-monitoring-host.example:9090/api/v1/write
```

```bash
./scripts/load/run-k6.sh agent-read \
  /absolute/path/to/load.env \
  /absolute/path/to/results \
  /absolute/path/to/load-fixtures
```

네 번째 인자의 폴더에서 search-corpus.json과 선택적인 staff-accounts.json을 읽기 전용으로 마운트한다. 모든 fixture JSON과 env 파일은 group/others 권한이 없어야 한다. 실행마다 별도 결과 디렉터리를 만들어 이전 결과를 보존한다. TEST_RUN_ID도 매 실행 고유하게 지정해 remote-write 결과를 구분한다.

기존 mode-0600 env를 비밀값 노출 없이 재사용하는 진단 A/B는 runner process에 `DESKSEED_LOAD_RUN_ID_OVERRIDE`와 `DESKSEED_AGENT_SEARCH_SORT_OVERRIDE`를 지정할 수 있다. 두 값만 container 환경에서 env-file 값을 덮어쓰며 인증정보나 대상은 덮어쓰지 않는다.

| 옵션 | 동작 |
|---|---|
| TARGET_ITERATIONS_PER_SECOND | 초당 시나리오 시작 수. 기존 TARGET_RPS는 같은 단위의 호환 이름; 함께 주면 값이 같아야 함 |
| AGENT_READ_MODE=composite | 큐 → 상세 → 검색. 정상 완료 시 업무 HTTP 약 3배, 로그인 별도 |
| AGENT_READ_MODE=search-only | 인증된 검색만 수행. 시나리오/s = 검색/s |
| AGENT_SEARCH_SORT | 검색 정렬 계약. 기본은 `updatedAt:desc,ticketNumber:desc`; 관련도 경로 대조 시 `score:desc,ticketNumber:desc`를 명시하고 서로 다른 run ID로 기록 |
| WARMUP_DURATION | 기본 0. 같은 scenario/VU에서 준비 구간 뒤 측정; LOAD_DURATION에는 준비 시간을 더해 실행 |
| LOAD_DURATION | 측정 구간. 일반 기본 5m, soak 기본 30m |
| PREALLOCATED_VUS / MAX_VUS | agent-read는 두 값이 같아야 함. 기본 max는 preallocated와 같음 |
| STAFF_VIEW_KEYS | 쉼표로 구분한 유효 view key 목록 순환. 미지정 시 STAFF_VIEW_KEY 또는 pending |
| SEARCH_SEED | 검색어 순서 고정용 uint32. 기본 20260919 |
| HTTP_TIMEOUT | 업무·로그인 요청 timeout, 기본 15s. redirect는 따라가지 않음 |
| TEST_ENVIRONMENT | load / personal-staging / local, 기본 load. 대상 서버 설정을 변경하지 않는 메트릭 구분 값 |
| STAFF_SEARCH_CORPUS / STAFF_ACCOUNTS_FILE | 직접 k6 실행 시 사용할 입력 파일 경로. Docker runner의 네 번째 인자는 기본 파일명으로 자동 설정 |

1 VU 반복 연결 확인은 LOAD_PROFILE=smoke, SMOKE_ITERATIONS=10, SMOKE_MAX_DURATION=45s로 실행한다. 기본은 1회다. 명시적인 STAFF_SEARCH_QUERY는 smoke에서만 사용할 수 있고 무의미한 기본 검색어는 없다. corpus가 없는 non-smoke는 시작 전에 실패한다. `k6 inspect`는 계정/입력 없이 설정만 검사할 수 있다.

로그인은 VU에서 한 번 하고 쿠키를 iteration 간 유지한다. 로그인 후 CSRF를 다시 읽는다. 검색·상세는 각각 새 interaction ID를, 개별 요청은 서로 다른 request ID를 가지며 한 journey는 correlation ID를 공유한다. 계정/세션 실패 시 자동 재로그인·재시도로 부하를 바꾸지 않는다.

## 측정과 판정

- `agent_operation_duration`: 큐·상세·검색의 요청 지연. operation/phase/query_class를 구분한다.
- `agent_journey_duration`: 로그인 제외 순차 업무 지연. 실패 journey도 포함하며 완료율을 함께 본다.
- `agent_journeys_started`, `agent_journeys_completed`, `agent_search_reached`: 초기화 실패·중간 중단·검색 미도달을 드러낸다.
- `agent_search_requests`: 검색군별 실제 호출 수. manifest는 군별 준비된 고유 검색어 수와 측정 요청 수를 기록한다. 실제 고유 사용 수를 전역 집계하는 metric은 만들지 않는다.
- `agent_search_empty_results`: 실제 빈 결과 비율. 정답 판정에 사용하지 않는다.
- `agent_search_outcomes`: 정상 페이지(`page`), 검색어 구체화 요청(`refine`), 비정상 응답(`invalid`)의 수. `agent_operation_duration`도 같은 outcome으로 분리해 서로 다른 계약의 지연을 합치지 않는다.
- `agent_late_authentications`: 준비 구간이 끝난 뒤 처음 사용된 VU의 인증 수. 이 값이 발생한 실행은 준비 시간을 늘려 재측정하거나 인증 영향이 있는 결과로 표시한다.

기본 요청 상태·JSON 응답 구조만 확인한다. 정확한 결과 수, 검색된 티켓 ID, 순위, 적합도는 평가하지 않는다. 검색·상세 요청에는 필수 interaction 헤더가 전달되고 기존 서버의 STAFF 인증·감사 의미를 따른다. 정상적인 빈 결과도 성공이다.

실행 유효성은 checks=100%, unexpected_status=0, dropped_iterations=0, journey 완료율=100%, 검색 도달률=100%다. non-smoke는 측정 구간에서 각 검색군이 한 번 이상 호출돼야 한다. warmup도 요청 실패는 숨기지 않지만 지연 threshold는 measurement만 사용한다. 인증 요청은 phase=authentication이다.

기본 지연 예산(ms): 큐 p95/p99=300/600, 상세 500/1000, 검색 500/1000, composite journey 1500/3000, search-only journey 500/1000. `QUEUE_P95_MS`, `QUEUE_P99_MS`처럼 QUEUE/DETAIL/SEARCH/JOURNEY별로 덮어쓸 수 있다. 검색군별로 같은 검색 예산을 적용한다. smoke에는 지연 예산을 적용하지 않는다. 낮은 표본 수의 p99는 용량 증거가 아니다.

raw URL·query·actor/ticket/request ID를 metric label에 넣지 않는다. 원문 검색어와 계정은 summary/manifest에 출력하지 않는다. `TEST_RUN_ID`는 k6에만 적용하고 서버 메트릭은 대상·시간으로 대조한다.

runner 산출물:

- runner-manifest.json: UTC 시작/종료, 종료 코드, 고정 k6 이미지, checkout SHA, 실행 스크립트 SHA256, corpus SHA256
- exit-code.txt: k6/Docker 종료 코드. 실패도 유지하며 wrapper 역시 동일 코드로 종료
- agent-read-summary.json: measurement submetric과 전체 k6 결과
- agent-read-manifest.json: 부하 옵션, 입력 종류·비중·측정 호출 수, 계정 배치 방식, threshold. 원문/비밀번호 없음
- agent-read-raw-summary.json: k6 기본 summary export

checkout SHA만으로 dirty script 내용을 대표하지 않는다. runner의 파일별 hash를 함께 보존한다. 실제 배포 SHA·DB 데이터량·서버/생성기 사양·DB 통계·Grafana 시간 창은 실행 계획에 따라 별도 기록한다. JSON summary가 있어도 원격 telemetry ingest나 서비스 용량이 입증되는 것은 아니다.

## 다른 시나리오

public-request/customer-auth-limiter/collaboration-websocket은 기존 세 번째 인자까지의 runner 호출을 유지한다. agent-read 전용 mode/corpus/지연 옵션은 적용되지 않는다. 공통 TARGET_RPS 단위, 고유 request ID, 환경 tag 및 선택적 STAFF_ACCOUNTS_FILE은 공유한다.

public-request는 CONFIRM_DESTRUCTIVE_WRITES=true가 추가로 필요하다. customer-auth-limiter는 auth-sustained/auth-burst/auth-safety를 사용하고 safety는 CONFIRM_AUTH_SAFETY=true가 필요하다. 모든 non-smoke는 대상 host와 CONFIRM_DESKSEED_LOAD_TARGET이 같아야 한다. 자격증명·URL은 외부 env 파일에 둔다.

## 계약과 스크립트 검증 범위

이 slice의 목표는 다양한 실제 검색 입력으로 STAFF/AGENT_UI의 인증된 읽기 부하를 재현하는 것이다. REQ-PERF-002/REQ-SRCH-001, D-018/048/064/065, ADR 0018/0030/0033/0036/0047/0048을 따른다. OPS-004/PERF-001/003/ACC-007의 실측 준비를 지원하며, 아래 로컬 스크립트 검증이 서버 gate 통과를 뜻하지 않는다.

제품/OpenAPI/DB migration·권한·transaction·idempotency·retention은 변경하지 않는다. 실제 실행은 기존 세션 및 access/search 감사 쓰기를 발생시키므로 읽기 부하라도 DB 쓰기 비용을 포함한다. API 실패를 재시도하거나 필수 감사를 생략하지 않는다. 원문·credential을 결과물에 기록하지 않는다.

```bash
node --test tests/load/search-workload.test.mjs tests/load/agent-read-smoke.test.mjs
K6_DOCKER_IMAGE=grafana/k6:2.0.0 node --test tests/load/agent-read-smoke.test.mjs
```

두 번째 명령은 Docker Desktop의 host.docker.internal로 loopback 모의 서버에 연결한다. 첫 번째는 로컬 k6를 사용한다. 검사 대상은 세션·CSRF·헤더, 비중·순환, warmup 구분, 정상 빈 결과 허용, 실패/파싱 오류 집계이며 실제 Deskseed 검색 성능을 측정하지 않는다.

k6 동작 근거: [test lifecycle](https://grafana.com/docs/k6/latest/using-k6/test-lifecycle/), [execution identifiers](https://grafana.com/docs/k6/latest/javascript-api/k6-execution/), [cookie persistence](https://grafana.com/docs/k6/latest/using-k6/k6-options/reference/#no-cookies-reset).
