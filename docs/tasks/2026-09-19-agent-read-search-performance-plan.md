# agent-read 검색 부하 테스트 실행 계획

작성일: 2026-09-19. 상태: 스테이징 계측 연결 검증 완료 / 단일 검색 smoke 통과 / 본 부하 미실행.

개인 스테이징과 별도 부하 발생기를 사용한다. 아래 목표와 검색 비중은 초기 실험 가정이며 현재 성능이나 서비스 SLA를 뜻하지 않는다. 실행 명령과 설정 형식은 [load suite 사용법](../../tests/load/README.md)을 따른다.

## 1. 측정 목적과 범위

100만 티켓 데이터에서 상담사의 **큐 → 상세 → 검색** 흐름을 재현한다. 다양한 실제 검색 입력에 대해 응답시간과 처리 가능한 부하를 측정하고, 느린 검색군의 원인과 부하 제거 후 회복을 확인한다. 비교할 제품 버전이 준비되면 같은 조건으로 전후 측정한다.

- 대상: ACTIVE AGENT의 STAFF / AGENT_UI 요청. 기존 권한·검색 감사가 켜진 상태로 측정한다.
- 주 시나리오: 큐 50개 조회 → 큐의 티켓 1개 상세 → 검색 첫 페이지 25개. 정상 journey당 업무 HTTP 요청 3개다.
- 보조 시나리오: `AGENT_READ_MODE=search-only`로 검색만 실행한다.
- 측정 단위: journey/s, 검색 req/s, 실제 HTTP req/s, operation·검색군별 지연, 오류·drop, 자원 사용량.
- 검색 입력은 실제 데이터 내용을 다양하게 사용한다. 기대 티켓 목록·정확한 기대 count·결과 순위를 만들거나 대조하지 않는다. 정상적인 빈 결과도 부하에 포함한다.
- 근거 범위: REQ-SRCH-001, REQ-PERF-001/002, REQ-AUD-003/004/005/008 및 PERF-001/003, ACC-007, SEARCH-AUD-001/002, OPS-004의 관련 측정. 이 계획이나 smoke만으로 gate 통과를 선언하지 않는다.

`TARGET_ITERATIONS_PER_SECOND=10`은 10 journey/s다. 복합 흐름이 모두 완료되면 검색 10 req/s와 업무 요청 약 30 req/s가 발생한다. 최초 로그인 요청과 실패로 중단된 흐름은 실제 HTTP 요청 수에서 따로 구분한다.

## 2. 실행 환경과 데이터 준비

### 환경 고정

1. 배포 SHA/OCI revision, Flyway·PostgreSQL 버전, 서버 CPU/RAM·컨테이너 제한, DB 저장장치, JVM heap, 실제 Hikari 설정을 기록한다.
2. 부하 발생기는 앱·모니터링 서버와 분리하고 CPU/RAM/네트워크 사양을 기록한다. 모든 호스트의 시계를 동기화한다.
3. 주 측정은 사용자가 접속하는 스테이징 HTTPS ingress 경로로 고정한다. WAF/프록시 포함 결과와 내부 직결 진단 결과는 별도 실행으로 기록한다.
4. 다른 수동 트래픽을 피하고 SLA/automation/retention/AI worker 등 background 작업의 설정·주기를 기록한다. 비교 실행마다 동일하게 유지한다.
5. 테스트 계정·검색어 파일은 부하 발생기의 보호 디렉터리에 두고 파일 권한을 0600으로 제한한다. 계정 풀은 VU당 로그인 가능한 ACTIVE AGENT 한 명을 준비한다. 단일 계정 실행은 그 제약을 결과에 명시한다.
6. 부하 실행 중 배포·인덱스 생성·대량 적재·projection rebuild·통계 수동 갱신을 하지 않는다. 전후 비교용 기준 snapshot과 복구 절차를 확보한다.

### 데이터 확인

첨부 `Deskseed-demo-data/deskseed-demo-full/manifest.json`의 합성 데이터는 티켓 1,000,000개, 댓글 7,552,760개, 고객 10,000명, 직원 63명, 그룹 15개다. seed는 20260911, 기준 시각은 2026-09-11T14:00:00Z다. 이는 원본 파일의 설명이며 스테이징 적재 완료 증거가 아니다.

- 실제 DB 적재 여부와 티켓·댓글·검색 projection 행 수, projection 누락, 인덱스 valid 상태, 통계 갱신 시각을 확인한다. 준비되지 않았다면 본 측정을 시작하지 않는다.
- 데이터와 측정용 검색어 목록이 같은 데이터셋에서 왔는지 확인한다. 첨부 문서의 importer 사용법 자체를 기존 스테이징 데이터 변경 지시로 취급하지 않는다.
- DB·인덱스·TOAST 크기, 디스크 여유, WAL/audit 증가 여유를 기록한다. ZIP 크기로 DB 용량을 대신하지 않는다.
- 합성 직원의 password_hash는 로그인용으로 쓸 수 없으므로 준비된 부하 계정으로 확인한다. 계정마다 동일한 읽기 권한과 사용할 큐의 접근 가능 여부를 확인한다.
- 단일 고객·티켓 크기가 균등하다고 가정하지 않는다. 이 데이터의 댓글 수 분포를 넘어서는 초대형 티켓 성능은 별도로 남긴다.

### 계측 확인

- ADR 0048의 `production + personal-staging-observability` 환경을 기준으로 한다. disposable load overlay 설정이 개인 스테이징에도 적용됐다고 가정하지 않는다.
- Grafana에서 앱 scrape, k6 remote write, 안전한 correlation 로그와 trace를 확인한다. `No data`는 0이 아니다.
- 본 부하 시작 전 모니터링 저장 여유를 다시 확인한다. 2026-09-19 준비 종료 시 약 7.9GiB/13%로 아래 20% 중단 기준에 미달하므로, 추가 공간 확보 또는 저장량 근거에 따른 명시적 기준 재결정이 선행돼야 한다.
- [검색 진단 readiness](../../ops/observability/personal-staging/search-diagnostics/README.md#본-부하-진입-확인)를 통과한 뒤 시작한다. 동일 요청의 count/page/audit span과 검색군·실행 ID 연결, 모니터링 저장 여유를 확인한다.
- `pg_stat_statements`, `track_io_timing`, DB/host exporter의 실제 가용 여부를 확인한다. 없는 지표는 수집 불가로 기록한다. 허용된 읽기 전용 DB/OS 통계로 보완할 수 있다.
- scrape 10~15초, DB/host sampling 5~10초를 초기안으로 삼고 수집 부하를 확인한다. 계측 설정은 비교 실행마다 고정한다.

## 3. 검색 입력 구성

기본 목록은 **8개 검색군 × 20개, 총 160개**다. 원본 티켓·댓글·고객 파일 전체를 순회하며 표본을 뽑고 검색어를 만든다. corpus 생성은 부하 실행 전에 끝내며 부하 도중 추출용 API/DB 요청을 보내지 않는다.

| 검색군 | 요청 비중 | 입력 출처 |
|---|---:|---|
| ticket-number | 10% | 실제 티켓 번호 |
| requester | 10% | 실제 요청자 이름·이메일 |
| phrase | 20% | 제목·PUBLIC 댓글의 짧은 구절, 그룹·담당자 이름 |
| topic | 20% | 실제 표본에 등장하는 로그인·주문·결제·환불·연동 등 주제어 |
| common | 15% | 여러 표본에 반복되는 3자 이상 단어 |
| short | 10% | 실제 등장하는 2자 단어 |
| internal | 10% | INTERNAL 댓글의 짧은 구절 |
| absent | 5% | 없는 값 검색을 위한 합성 문자열 후보 |

95%는 원본 데이터에서 가져온 값이고 5%는 없는 값 후보다. `absent`는 사전 DB 정답 검증을 마친 값이라는 뜻이 아니다. 실제 빈 결과 비율은 관측값으로 남기며 기대값과 비교해 성능 측정을 실패시키지 않는다.

- 목록은 `search-corpus.json`으로 고정하고 원본 manifest hash, datasetId, 생성 seed, 검색군 비중을 보존한다. 기본 `SEARCH_SEED=20260919`도 전후 동일하게 유지한다.
- 100개 iteration 단위로 검색군 비중을 적용하고, 각 군 안에서 고정 seed로 섞은 값을 순환한다. 한 바퀴 전에 같은 값을 반복하지 않으며 VU마다 첫 검색어부터 다시 시작하지 않는다.
- 기본 목록의 개별 검색어 비중은 완전한 순환 기준 최대 1%다. 측정 창이 순환 중간에 시작·종료되므로 실제 군별 호출 수를 함께 보고한다.
- 기본 설정에서 연속 400개 iteration은 모든 160개 값을 포함한다. 1/s·10분 측정도 이보다 길지만, 검색에 도달하지 못한 흐름이 있다면 계획된 입력 사용량을 달성했다고 하지 않는다.
- manifest에는 군별 입력 종류 수와 실제 측정 검색 호출 수가 남는다. 실제 사용한 고유 검색어 수를 수집한 지표로 오해하지 않는다.
- 원문 검색어·이메일·티켓 식별자를 metric label이나 일반 로그에 남기지 않는다. 결과 공유 시 corpus 원문을 제외한다.

주 비교는 `sort=score:desc,ticketNumber:desc`, `limit=25`, `filters={}`로 고정한다. 지정 큐는 `STAFF_VIEW_KEYS`에서 순환하고 각 큐의 첫 페이지 안에서 상세 티켓을 선택한다. 따라서 깊은 큐 페이지·전체 티켓 상세의 균등 분포·검색 다음 페이지 성능까지 검증한 결과는 아니다.

## 4. 목표와 실행 순서

### 잠정 목표

| 항목 | 10 journey/s에서의 초기 목표 |
|---|---|
| 검색 전체·각 검색군 | p95 ≤ 500ms, p99 ≤ 1,000ms |
| 큐 | p95 ≤ 300ms, p99 ≤ 600ms |
| 상세 | p95 ≤ 500ms, p99 ≤ 1,000ms |
| 복합 journey | 로그인 제외 p95 ≤ 1,500ms, p99 ≤ 3,000ms |
| 실행 유효성 | checks 100%, unexpected_status=0, dropped_iterations=0, 검색 도달·journey 완료율 100% |
| 서버 상태 | 필수 감사 실패·OOM·재시작 없음, 부하 제거 후 회복 |

검색 단독에서는 journey 목표도 500/1,000ms다. 표본이 적은 검색군의 percentile은 탐색 값으로 표시한다. 모든 요청 지연을 섞은 `http_req_duration`을 검색 p95로 쓰지 않고 `phase=measurement`의 operation/검색군 지표로 판정한다.

상담사 수와 VU 수는 같지 않다. 100명이 30초마다 이 흐름을 한 번 수행한다는 가정이면 약 3.3 journey/s다. 10 journey/s는 이 가정의 약 3배인 탐색 목표이며 실제 업무 행동이나 전체 상담업무 수용량을 입증하는 수치는 아니다.

### 실행 단계

| 단계 | 설정·부하 | 시간·반복 | 확인할 내용 |
|---|---|---|---|
| 0. 연결·세션 smoke | smoke, 1 VU, `SMOKE_ITERATIONS=10` | 1회 | 로그인 유지, 헤더, 큐→상세→검색 완료, 기본 응답·감사·계측 |
| 1. 입력 순환 smoke | smoke, 1 VU, `SMOKE_ITERATIONS=400`, `SMOKE_MAX_DURATION=15m` | 1회 | 모든 검색군 입력 순환과 빈 결과 정상 처리; 용량 판정에는 사용하지 않음 |
| 2. 검색 단독 기준선 | search-only, 1 search/s | 준비 3분 + 측정 10분 | 저부하 검색군별 지연·오류와 DB 통계 |
| 3. 복합 저부하 | composite, 1 journey/s | 준비 3분 + 측정 10분 | 전체 경로·계측·검색 도달률 |
| 4. 단계 상승 | 2 → 5 → 10 → 15 → 20 journey/s | 각 준비 3분 + 측정 10분 | 최초 목표 미충족 부하와 포화 자원 |
| 5. 경계 좁히기 | 마지막 통과와 최초 실패 사이의 정수 rate | 준비 3분 + 측정 10분 | 통과·실패 경계, 필요 시 반복 |
| 6. 지속 검증 | 10 또는 마지막 안정 rate | 준비 3분 + 측정 30분, 3회 | 지연·감사·디스크 증가·회복, 검색군 표본 수 |
| 7. 전후 비교 | 비교 버전 A/B, 같은 목표·경계 부하 | 각 3회, 준비 3분 + 측정 20분 이상 | 검색군별 개선·회귀·측정 분산 |

각 단계는 고유한 `TEST_RUN_ID`를 지정해 별도 실행으로 보존하고 단계 사이 회복을 확인한다. 20까지 통과하면 자원 여유에 따라 30→40을 추가 탐색한다. 10 미만에서 실패해도 해당 부하를 기준으로 원인 진단과 비교를 진행한다.

부하 설정 예시: `LOAD_PROFILE=steady`, `AGENT_READ_MODE=composite`, `WARMUP_DURATION=3m`, `LOAD_DURATION=10m`, `TARGET_ITERATIONS_PER_SECOND=10`, `PREALLOCATED_VUS=45`, `MAX_VUS=45`. `LOAD_DURATION`은 준비 구간을 제외한 측정 길이다. 실제 명령·보호 파일 배치는 [사용법](../../tests/load/README.md)을 따른다.

arrival-rate 실행은 응답이 늦어져도 시작 속도를 유지한다. 생각시간은 넣지 않는다. 초기 VU 예산은 `max(10, ceil(rate × 보수적인 journey 시간[초] × 1.5))`로 산정한다. 10/s·3초라면 45 VU부터 예비 실행으로 확인한다. `MAX_VUS=PREALLOCATED_VUS`로 고정하며 계정 풀은 그 수 이상이어야 한다.

준비와 측정은 한 프로세스에서 진행한다. 로그인 시간은 업무 지연에서 제외하지만 로그인 자체가 서버에 주는 부하는 존재한다. `agent_late_authentications`가 측정 중 발생하면 그 영향을 조사하고, 비교용 실행은 준비 조건을 조정해 다시 수행한다. 일부 VU가 준비 중 사용되지 않았을 가능성을 무시하지 않는다.

### 중단과 회복

- 즉시 중단: 필수 감사 저장 실패, OOM·재시작, 데이터 손상 징후, 남은 디스크 10% 미만 또는 예상 WAL/audit 증가를 감당하지 못함.
- 상승 중단: 검색 p95 > 2초가 1분 지속, p99 > 5초, 예상하지 않은 오류 > 1%가 1분 지속, Hikari connection timeout. 적은 표본의 1분 p99만으로 원인을 확정하지 않는다.
- `dropped_iterations`가 있으면 도착 부하를 충족한 실행으로 판정하지 않는다. 생성기 자원 부족과 서버 지연에 따른 VU 점유를 구분한다. 실패 실행도 증거로 보존한다.
- 자동 threshold는 종료 시 판정이다. 위 운영 중단 기준은 모니터링하며 적용하고, threshold가 알아서 즉시 중단할 것으로 기대하지 않는다.
- 높은 CPU만으로 종료하지 않고 지연·대기열·오류와 함께 판단한다. 401/403/400은 인증·권한·헤더·계약 문제로, 429는 발생한 ingress/로그인 정책별로 분류한다.
- 일반적인 지연·포화 후에는 1 journey/s로 낮춰 최대 5분 관찰한다. 저부하 지연, Hikari pending, DB wait/lock, worker backlog가 사전 범위로 돌아와야 다음 실행을 시작한다.
- 감사 실패·재시작·디스크 부족 등 즉시 중단 사유가 있으면 추가 요청을 멈추고 무부하 회복을 확인한다. 5분 내 회복하지 못하면 해당 세션을 종료한다.

## 5. 관측과 병목 진단

각 실행에 대해 **도착 부하 → 사용자 영향 → 최초 포화 자원 → 회복** 순서로 증거를 남긴다.

| 계층 | 관측 항목 |
|---|---|
| k6 | 예정/실제 journey 시작·완료, 실제 HTTP req/s, 검색 도달률, operation/검색군별 p50/p95/p99·max·표본 수, 빈 결과 비율, drop, 연결·TLS·대기·수신 시간 |
| 앱 | route 지연, CPU/heap/GC, Hikari active/idle/pending/acquire/usage/timeout, thread, required-audit 오류 |
| DB | queryid별 calls·exec time·rows·buffer·temp·WAL 전후 차이, active/wait/lock, IO/checkpoint/autovacuum, 테이블·인덱스 크기 |
| 호스트·ingress | CPU/RAM/swap, disk latency·IO/network, container throttling, proxy/WAF latency·status |
| 생성기·계측 | CPU/RAM/network, VU 사용량, remote-write 실패·계측 손실 |

- `pg_stat_statements`는 실행 전후 snapshot 차이를 사용한다. 공유 통계를 임의 reset하지 않는다. `delta total_exec_time / delta calls`는 평균 SQL 시간이며 p95가 아니다. counter reset·entry eviction·다른 트래픽이 있으면 제한을 명시한다.
- 검색 API의 count 쿼리와 page 쿼리를 분리한다. 같은 정규화 SQL에 검색군들이 합쳐지므로, 느린 군의 대표 입력으로 별도 저부하 진단을 수행한다. 주 비교 corpus와 진단 결과를 섞지 않는다.
- 필요한 count/page `EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON)`은 본 측정 창 밖에서 timeout을 두고 수집한다. 실제 파라미터·필터·role을 재현하며 결과 파일을 보호한다. 공유 서버의 cache를 강제로 비우거나 planner를 강제한 결과를 주 측정으로 쓰지 않는다.
- SQL이 느리면 rows·buffers·sort·temp·wait를 확인한다. SQL은 빠른데 API가 느리면 필수 audit·commit/WAL·pool 대기·세션 처리·JSON/GC를 함께 확인한다.
- 서버 지연이 낮은데 HTTP만 느리면 생성기·VPN·TLS·프록시·WAF를 조사한다. Hikari pending은 DB CPU/IO/lock과 transaction 점유시간을 함께 보며 최초 원인과 후속 증상을 구분한다.
- SQL/API/journey의 p95를 빼거나 더해 단계별 p95를 만들지 않는다. 샘플 trace는 전체 SQL 통계가 아니며 여러 run의 p95 평균을 전체 p95로 부르지 않는다.

## 6. 전후 비교와 결과 판정

1. A/B에서 corpus hash·seed·검색 비중·계정 배치·부하·target URL·하드웨어·pool·telemetry·실행 파일 hash를 동일하게 유지한다. 제품 배포 버전과 DB 스키마·인덱스 상태는 각각 기록한다.
2. 목표 부하와 병목 직전 부하를 각각 비교한다. 이전에 실패했던 부하도 별도 재검증한다. 데이터가 동등한 시험 환경에서 AB/BA 순서를 교차하고 각 3회 측정한다.
3. 동일 스테이징을 계속 쓰면 누적 audit·상태 변화·DB 크기·vacuum 차이를 기록한다. 준비 3분을 거친 warm-cache 조건을 주 결과로 삼는다. 동등하지 않은 데이터 조건에서 인과 효과를 확정하지 않는다.
4. 군별 p95는 최소 200표본, p99는 최소 1,000표본을 초기 하한으로 두고 분산이 크면 연장한다. 이 숫자만으로 통계적 확실성이 보장되지는 않는다. 10/s × 20분 × 5% 군은 약 600표본이므로 해당 군 p99에는 최소 34분의 측정이 필요하다. 저부하 기준선은 탐색 결과로 표시한다.
5. 병목 검색군 p95 20% 이상 감소 또는 기존 미충족 목표 달성을 초기 개선 기준으로 삼는다. 3회 간 변동보다 개선 폭이 작으면 불확실로 둔다. p99·오류가 악화되면 개선 판정을 보류한다.
6. 다른 검색군·큐·상세 p95가 10% 넘게 악화되면 회귀 후보로 조사한다. 감사/WAL·인덱스 크기 변화도 보고한다.
7. 허용 부하는 지연·오류·drop·지속·회복 기준을 충족한 최고 **검증 rate**로 기록한다. 최고 스트레스 도달값이나 SQL 단독 개선율을 서비스 운영 용량으로 확대하지 않는다.

검색 결과의 정답 집합·정확한 기대 count·순위 평가는 이 실험에 포함하지 않는다. HTTP 상태·응답 구조·다음 단계 실행 가능 여부는 부하 자체의 유효성 확인에 사용한다.

## 7. 실행별 증거와 최종 보고

runner가 실행마다 만드는 고유 디렉터리에는 다음을 보존한다.

- `runner-manifest.json`: 실행 시작·종료 UTC, 종료 코드, k6 이미지, checkout SHA와 실행 파일별 hash, corpus hash.
- `agent-read-manifest.json`: 대상 환경, mode, 부하·준비 구간·threshold, 입력 종류·비중·측정 호출 수, 계정 배치 방식.
- `agent-read-summary.json`, `agent-read-raw-summary.json`, `exit-code.txt`: operation/검색군·측정 구간 지표와 전체 k6 결과, 종료 상태. 초기화 실패 시 summary가 없을 수 있으며 runner 기록으로 구분한다.

운영자는 같은 실행 디렉터리에 다음을 추가한다. 자격증명·검색 원문은 보고서에 넣지 않는다.

- `environment.json`: 배포/DB 버전, 서버·생성기 사양, 데이터량·projection·통계·디스크 확인 결과, 계측 가용 여부.
- `pgss-before.json`, `pgss-after.json`, `plans/`: DB 통계 차이와 별도 저부하 실행계획. 보호가 필요한 파라미터는 공유본에서 제외한다.
- `grafana-links.md`: 대상·절대 시간 고정 URL, 병목 전·중·회복 패널 캡처, 계측 손실 여부.
- `assessment.md`: 통과/실패/불확실, 실제 도착 부하·검색 도달률, 최초 포화 자원, 회복 시간, 관측 한계.

최종 표는 검색군·부하·표본 수·A/B p50/p95/p99·count/page 평균 SQL 시간·buffers·오류·drop을 나란히 둔다. 결과에는 실측 환경과 데이터 범위를 명시한다. 현재 스테이징 부하·DB 실행계획·성능 수치는 미측정 상태다.

관련 운영 근거: [개인 스테이징 관측 runbook](../runbooks/deskseed-personal-staging-observability.md), [verification gates](../21-minimum-verification-gates.md).
