# PostgreSQL 상담사 검색 개선 — 실행 및 인계 문서

작성일: 2026-09-21. 대화 기록 없이 후속 에이전트가 이어서 실행하기 위한 문서다.

**현재 상태: 두 개의 제품 후보를 PR·배포·반복 검증했다. 최신순 인덱스 후보는 동일 문제 입력과 고정 corpus에서 채택 기준을 충족했다. generator telemetry가 없어 본 부하 상승은 미실행이다.**

## 1. 재개 위치와 완료 상태

| 항목 | 상태 |
| --- | --- |
| 목표 | 검색 비용을 몰라도 업무에 유용한 결과를 빠르게 확인 |
| 기술 범위 | PostgreSQL 쿼리 → 통계/접근 경로 → 검색 projection → 필요한 경우 점진적 제공 |
| 제외 | OpenSearch 및 다른 외부 검색엔진, 신규 Redis/Kafka, 근거 없는 자원 증설 |
| 작업 경로 | `/private/tmp/deskseed-postgresql-search-20260921` |
| 작업 브랜치 | `feature/postgresql-search-latest-index` |
| 출발 SHA | `0459830f81cdeb4b4c6e7b04d7767ea984499015` |
| 실제 서버 | 2026-09-21 11:15 UTC backend/frontend가 `b6be5ae34b54d442f933b660e88abb1d262a8dc0`과 일치 |
| 실제 DB | PostgreSQL 17.11 / Flyway 97 / 티켓 1,000,000건 / 기존 단일 DB |
| 증거 루트 | `/Users/donghyunkim/Documents/deskseed/artifacts/search-load/postgresql-search-20260921T083600Z` |
| 실제 서버 기준선 | **실행 완료:** 1 VU / 검색 20건, 15 page + 5 refine |
| 요청 trace | **확보:** 번호 성공, 2글자 사전 거절, 느린 본문 검색 성공 요청 |
| 동일 규모 실행 계획 | **미실행:** 기존 load DB가 구버전·1만 건이고 split projection 없음; personal staging EXPLAIN ANALYZE 금지 |
| 제품 변경 / 배포 / 개선 A/B | **완료:** PR #233·#234, exact SHA 배포, 고정 corpus 3회와 동일 topic 3회 비교 |
| 첫 유용한 결과 시간·관련도 | **미측정:** 현재 k6는 HTTP 시간과 응답 종류만 평가 |
| 다음 작업 | generator telemetry 추가 후 1→5→10 req/s 본 부하; 동일 규모 load DB가 확보되면 executor node 확인; 검색 품질/TTFUR 별도 검증 |

원래 checkout은 `feature/staff-search-single-pass-count-page` / `1b91a47`이며 운영보다 오래되었다. 기존 dirty 파일은 E-002에 보존했다. reset/stash/덮어쓰기를 하지 않는다. 작업은 위 worktree에서 하고, 인계 문서는 요청된 원래 checkout의 `docs/tasks/`에도 동기화한다.

증거 파일은 호스트의 `artifacts/search-load/postgresql-search-20260921T083600Z/` 아래 `evidence-index.md`, `execution-log.md`, `comparison.md`에 있다. 이 절대 경로는 실행 호스트의 repository root를 기준으로 해석한다.

이 문서만 전달하지 말고 증거 디렉터리도 함께 전달한다. 일회성 캡처·실험 결과는 제품 코드와 함께 commit하지 않는다. 증거의 절대 경로는 현재 호스트 기준이며 다른 호스트로 인계할 때 경로 매핑을 기록한다.

## 2. 목표, 도메인 및 작업 계약

- Actor는 인증된 active `STAFF`, source는 `AGENT_UI`다. 현재 `ALL_TICKETS` staff 읽기 범위를 따르며 customer 접근을 확대하지 않는다. 임의 header로 직원을 사칭하지 않는다.
- 문의 본문은 첫 PUBLIC comment다. PUBLIC/INTERNAL 권한은 **후보 생성과 점수 계산부터** 적용한다.
- 검색 projection은 권한의 근거가 아니다. 결과 제공 시 현재 티켓·staff 상태·필터·삭제 여부를 확인한다.
- 검색 결과에 필요한 `SEARCH_EXECUTED`와 결과 membership 감사가 commit되기 전에는 성공 결과를 반환하지 않는다. 감사 실패는 fail closed다.
- 원문은 기존 암호문·keyed fingerprint 정책을 따른다. 검색 원문·댓글·이메일·인증정보를 일반 로그, metric label, URL, 평문 캐시, 공유 증거에 넣지 않는다.
- 티켓/read transaction 안에서 외부 I/O를 실행하지 않는다. canonical 티켓과 append-only audit을 되돌리거나 수정하지 않는다.
- `CLOSED`만 terminal이다. `SOLVED`는 다시 열릴 수 있으므로 active다. terminal의 현재 동결 필드 의미를 무단 변경하지 않는다.
- 삭제 commit 이후 검색·추가 배치·재검증 응답에 삭제 티켓을 포함하지 않는다. 오래된 cursor/cache도 이 검사를 우회하지 못한다.
- 기존 literal 부분문자열 일치를 유지한다. 관련 검색을 추가하면 기본 일치 결과와 구분한다.
- 업무 관련도를 우선하고 최신성·상태는 보조 기준으로 사용한다. active라는 이유로 모든 CLOSED 결과를 뒤로 보내는 기존 계약은 변경 전에 ADR/OpenAPI에 명시한다.
- 표시된 행의 순서·선택·포커스를 자동 변경하지 않는다. 확장 실패 후 이미 표시한 결과는 유지한다.
- 현재 5초 timeout은 마지막 안전장치다. 빠른 422 안내나 HTTP 200을 검색 성공으로 계산하지 않는다.

### 근거와 요구사항

- 필수 문서: AGENTS.md, CODEX_TASK_TEMPLATE.md, docs/00,01,02,03,07, 관련 18~25, 26,27,32,33,34,39. UI 작업은 frontend 및 Staff Console AGENTS, docs/28~31,40,51도 읽는다.
- Requirements: REQ-SRCH-001, REQ-PERM-001, REQ-AUD-003/004/005/008, REQ-PERF-001/002, REQ-OPS-002.
- Decisions: D-008, D-018, D-033, D-036, D-041, D-045, D-048, D-064, D-065, D-067, D-068, D-069.
- ADR: 0018(필수 감사), 0025(search projection), 0030(staff 읽기), 0033/0036/0037(원문 보호·검색 origin), 0047/0048(진단), 0050(count/budget), 0051(lifecycle projection), 0052(active 우선 cursor).
- Operation: `searchAgentWorkspace`, `POST /api/v1/agent/search`.
- Gates: ARCH-001~004, ACC-002/003/004/007, SEARCH-AUD-001/002, PERM-001, PERF-001/003, OPS-004, DOC-001. UI 변경 시 UI-002/004/005/006 추가.
- 동작·schema 변경은 authored core OpenAPI와 generated outline, ADR, decision register, traceability, migration, 테스트를 함께 갱신한다. 적용된 Flyway migration은 수정하지 않는다.

## 3. 현재 구현과 기존 시도

아래는 출발 SHA 기준이며 수정 전 다시 확인한다.

| 위치 | 현재 동작 |
| --- | --- |
| `ticketing/internal/StaffTicketSearchSqlPlan.kt` | parameterized JDBC SQL. 번호 exact와 literal 본문 조건을 OR로 결합하고 후보 점수 계산 후 LIMIT |
| `ticketing/internal/StaffTicketQueryRepository.kt` | active→terminal 순차 조회. 각 statement의 5초 budget. 뒤 조회 실패 시 앞에서 확보한 결과도 반환하지 못함 |
| `staffaccess/internal/AgentTicketSearchApplicationService.kt` | 필터 없는 비숫자 3 code point 미만 입력 거절. limit+1, count relation, v3 cursor, 보호된 필수 검색 감사 |
| `foundation/SearchDiagnostics.kt` | PAGE에 JDBC·mapping 포함. audit timer는 commit 제외. scan/score/sort 내부 시간을 별도 측정하지 않음 |

이미 적용된 조치는 새 해결책으로 다시 제안하지 않는다: exact count 제거, lower-bound count, 5초 제한과 안내, 기존 trigram GIN, active/terminal 물리 분리, 페이지 후보 선택 후 상세 hydration.

이전 자료는 원래 checkout의 `artifacts/search-load/search-terminal-projection-20260921T060136Z/`에 있다. 이전 20건 자료도 15 page / 5 refine이었다. 기존 10k bigram 실험과 한 검색어의 소수 실행계획 결과는 백만 건 환경의 해결 증거가 아니다.

기존 계획의 “검색 정확성·순위 평가 제외” 조건은 **이번 사용자 요청으로 대체**되었다. 현재 baseline runner의 검사는 아직 page 또는 refine 응답을 통과로 취급하므로, 그 checks 100%는 새 사용자 목표 달성을 뜻하지 않는다.

## 4. 이번에 확보한 실제 서버 증거

### 환경과 기준선

- DB: PostgreSQL 17.11, Flyway 96. active 600,858건, CLOSED 399,142건. split projection의 모든 확인 대상 인덱스가 valid/ready다. E-003.
- 설정: work_mem 4MiB, shared_buffers 128MiB, max_connections 100, track_io_timing on. 읽기 전용 확인이며 설정은 변경하지 않았다.
- 기준선 run ID: `pg-search-baseline-20260921T084421Z`.
- 실제 UTC: **2026-09-21 08:44:22.110428~08:44:33.158605**.
- 경로: 실제 `https://deskseed.dhkim.cloud`, 별도 로컬 Docker k6, search-only, 1 VU, 20건. 기존 protected env/corpus를 사용하고 TLS 검증을 해제하지 않았다.
- 응답: **15 page / 5 refine**, local dropped iterations 0, 페이지 응답의 빈 목록 0.
- HTTP 검색 전체: p50 **205.990ms**, p95 **836.989ms**, 최대 **5078.433ms**.
- page만 분리한 p95: 556.883ms. 이 값으로 전체 검색 성공이나 개선을 주장하지 않는다.
- refine 5건 중 빠른 사전 거절이 포함된다. 25%가 결과를 받지 못했으므로 새 사용자 성공 기준을 충족하지 못한다.
- E-011에는 측정 구간의 DB statement-timeout 취소 한 줄만 보존했다. SQL·bind·본문은 포함하지 않았다.

원시 run 자료: `runs/agent-read-20260921T084422Z-pZEitx/`. 실제 명령과 조건: `runs/baseline-command.json`.

[기준선 Grafana 고정 구간](http://172.16.16.18:3000/d/deskseed-search-load-readiness/deskseed-search-load-readiness-and-rca?from=2026-09-21T08:44:10.000Z&to=2026-09-21T08:45:30.000Z&timezone=utc&var-environment=personal-staging&var-run=pg-search-baseline-20260921T084421Z)

### 요청별 trace

자연 샘플링의 기준선 trace 조회는 0건이었다(E-008). 이후 **진단 요청에 sampled W3C parent를 붙여** 실제 업무 요청 trace를 확보했다. 서버 설정은 변경하지 않았다. 샘플링 조건이 달라 baseline과 성능 A/B로 비교하지 않는다.

| case / 결과 | HTTP 시간 | 서버 내부 | trace ID |
| --- | --- | --- | --- |
| 원본 ticket-number:0 / 200, 1행 | 67.278ms | HTTP 56.39ms, PAGE 29.49ms, audit 21.02ms | `1eaa63f3f3314c85b4d189c258e9546d` |
| 원본 short:0 / 422 | 14.421ms | overall 0.45ms, PAGE 없음: DB 이전 거절 | `56356c055c7440bc88014ea3c6a25702` |
| 원본 topic:0 / 422 | 16.656ms | overall 0.60ms, PAGE 없음: DB 이전 거절 | `ebffc5352e7f4d639694f71472f4d242` |
| 원본 topic:3 / 200, 25행 | **3055.954ms** | HTTP 2928.48ms, PAGE **2887.64ms**, audit 33.05ms, assembly 0.43ms | `d38fc053c88746ab900c4c326363837f` |

느린 요청은 `pg-search-slow-trace-20260921T085439Z`, 완료 시각 08:54:43.671 UTC다. 반복 시 timeout 대신 약 3초 후 결과를 얻었다. 쿼리·캐시·실행 조건에 따른 변동이 있으므로 한 번의 성공을 개선으로 해석하지 않는다.

trace는 E-018/E-022에서 attribute allowlist를 적용해 보존했다. 개별 실행의 시간 차이를 분해할 수 있지만, 중첩 span을 더하거나 여러 p95를 합산해서 전체 시간을 만들지 않는다. 현재 PAGE span 하나는 SQL 내부 연산이나 실제 SQL 호출 수를 증명하지 않는다.

### 원인 판정

| 분류 | 판정 |
| --- | --- |
| 확인된 지연 구간 | 느린 성공 요청의 대부분이 PAGE 안에서 소요됨. baseline에서는 DB timeout도 재현됨 |
| 확인된 별도 UX 문제 | 2글자 입력이 SQL 전 거절되고, 번호와 본문이 같은 경로에 있으며 전체 조회 완료 전에는 결과를 제공하지 못함 |
| 주원인 후보 | 넓은 후보의 문자열 검사·점수 계산·정렬, heap/TOAST 및 temp I/O |
| 아직 확정 못한 부분 | 위 후보별 기여도, 실제 executor node, SQL 반복 호출 여부 |
| 지지되지 않는 주원인 | 해당 구간의 풀 고갈·blocking·GC·필수 감사 실패. ORM N+1은 구조적 전제가 아님 |
| 해석 주의 | 낮은 호스트 평균 CPU로 개별 SQL의 CPU 비용을 배제하지 않는다. pgss 누적 통계는 취소된 요청이나 특정 trace와 1:1 대응하지 않음 |

E-010의 완료 SQL calls는 E-003 대비 32→47, 누적 실행시간 증가 약 3193.288ms다. 취소된 statement가 이 완료 통계에 포함되지 않으므로 전체 PAGE 시간을 이 차이로 설명할 수 없다.

### 재현 식별자에서 발견한 주의점

`createWorkload`의 `caseIndex`는 **seed로 섞인 group의 index**, `SearchPlanCapture --case-id`는 **원본 corpus index**다. 숫자가 같다고 동일 검색어로 간주하면 안 된다.

`runs/E-019-baseline-case-map.json`에 20개 iteration의 대응을 원문 없이 저장했다. 예를 들어 baseline iteration 0은 common/shuffled 0이지만 원본 common:12다. 느린 iteration 14는 topic/shuffled 3 → 원본 topic:3이다. 후속 plan capture는 반드시 이 매핑을 사용한다.

### 계측 공백과 수정 기록

- application phase metric의 실제 이름은 `deskseed_search_phase_seconds_*`이며 **test_run_id label이 없다**. 환경+UTC로 비교한다. request 연결은 trace로 한다.
- E-009/E-012의 phase 조회에는 잘못된 metric/label selector가 있었다. 빈 결과를 계측 부재로 해석하지 않는다. 올바른 조회와 결과는 **E-013**이다. 실패 조회도 보존했다.
- E-006/E-013과 실제 Grafana 화면에서 generator telemetry gap이 확인되었다. 본 부하 상승은 미실행이다.
- 최초 trace probe는 잘못된 interaction header로 400을 반환했다. E-015는 transport 확인만 가능하다. 올바른 `X-Interaction-Id`로 재실행한 자료는 E-017/E-018이다.
- native Python HTTPS probe는 인증 요청 이전에 인증서 검증에 실패했다. 검증을 끄지 않고 기존 k6 runtime으로 진단했다. 이를 서버 장애로 계산하지 않는다.
- Grafana 캡처는 실제 브라우저에서 수행해 대화에 표시했다. 내구성 있는 PNG 파일은 확보하지 못했다. JSON·고정 URL을 보존했으며 PNG가 있는 것처럼 링크하지 않는다.

## 5. 재현 환경 공백과 다음 실행 조건

현재 기존 load container `deskseed-search-plan-before`는 user `deskseed`, DB `deskseed_perf`, PostgreSQL **17.10**, tickets/search_documents **10,000건**, split projection 없음, Flyway 이력 없음이다(E-014). 삭제·변경하지 않았다.

로컬 파일시스템 여유 공간은 약 **18GiB**다(E-016). 같은 규모 데이터 복제, 인덱스와 재색인·build 여유를 확보했다고 볼 수 없다. 기존 Docker cache/volume을 임의로 삭제하거나 서비스 DB를 대신 분석 대상으로 바꾸지 않는다.

사용자에게 별도 load 서버 지정 / 로컬 공간 확보 / 현재 재현 결과까지 인계 중 다음 진행 조건을 요청했다. 응답이 없으면 동일 규모 복제·실행 계획·제품 쿼리 전환은 보류하되 문서와 확보 자료 검증은 완료한다.

[ADR-0048](../adr/0048-personal-staging-private-observability.md)의 명시 규칙:

> Personal staging never runs `EXPLAIN ANALYZE`.

실제 서비스에서는 HTTPS·trace·허용된 읽기 전용 통계를 수집하고, 실행 node 분석은 동일 데이터/버전의 별도 load DB에서 한다. 다른 환경을 `load`라고만 표시해 이 제한을 우회하지 않는다.

## 6. 작은 수직 슬라이스와 채택 기준

한 번에 가설 하나·변경 하나를 검증한다. 실패 실험도 보존한다.

| 슬라이스 | 구현·실험 | 채택 기준 |
| --- | --- | --- |
| S0 | 동일 환경, 계측, 정답 corpus, 기준선·실행 계획 | 사실/가설/미확인 사항 구분, 재현 식별자 일치 |
| S1 | 독립 번호 인덱스 조회 | active·CLOSED exact가 본문 정렬을 기다리지 않고 권한·삭제·필터·감사 유지 |
| S2 | 조건별 query path, 조기 필터, 중복 문자열 계산 감소, 정렬 행 폭/연산 축소 | 기존 literal 의미와 문서화된 정렬 유지, 실제 처리량·지연 감소, 분류별 회귀 없음 |
| S3 | 검증된 통계/접근 경로 교정, 필드별 후보 projection·사전 계산 특성 | 백만 건에서 속도·품질 이득과 쓰기/WAL/저장/backfill 비용 수용 가능 |
| S4 | SQL만으로 목표 미달이면 후보 배치·점진적 응답/UI | 첫 유용한 결과를 막지 않음, 부분 상태·이어 읽기·실패 후 결과 유지 |
| S5 | 실제 서버 반복 A/B·회복·최종 인계 | 배포 SHA 증거와 사용자 성능·품질·보안 gate 통과 |

확인 항목: estimated/actual rows, filter/recheck 제거량, loops, sort 입력 폭·방식·spill, shared/temp blocks, heap/TOAST 접근, 필터 선택도, 통계 최신성, 해당되는 경우 prepared/generic plan 차이. CTE나 alias가 문자열 식을 한 번만 계산한다고 가정하지 않고 실제 계획으로 확인한다.

임의의 최신 N건만 재랭킹하고 전역 관련도 상위 결과라고 표시하지 않는다. 완전성을 증명하지 못하면 부분 결과와 continuation을 제공하고 누락된 관련 결과를 측정한다. 동기 exact count를 복원하지 않는다.

캐시는 첫 개선에서 제외한다. 필요성이 반복률/비용으로 확인되면 PostgreSQL 후보 ID 캐시만 별도로 검토한다. query HMAC·권한/인덱스 세대·만료·삭제 재검증을 결합하고 평문 원문은 저장하지 않는다.

### 점진적 API가 필요한 경우만

SQL이 목표를 달성하면 기존 API를 유지한다. 필요한 경우 계약부터 별도 슬라이스로 확정한다.

- POST `/api/v1/agent/search-sessions`: 세션과 준비된 첫 배치.
- POST `/api/v1/agent/search-sessions/{id}/batches`: 불투명 cursor로 추가 배치.
- DELETE `/api/v1/agent/search-sessions/{id}`: 남은 작업 취소.
- 응답: 세션/배치 ID, 기본·관련 결과, 감사 event ID, 다음 cursor, 부분 여부, 진행/완료/일부 실패/취소/만료.
- 초기 TTL 5분, actor와 로그인 세션에 귀속. 배치 재요청도 현재 권한·삭제 상태를 다시 확인한다.
- 민감한 결과 배치는 감사 commit 이후 반환한다. 결과 없는 상태 polling을 TICKET_VIEWED나 중복 의미의 SEARCH_EXECUTED로 기록하지 않는다.
- 내부 후보 한도 도달을 전체 검색 완료로 표시하지 않는다. 원문이 필요한 비동기 상태는 보호된 단기 데이터로 관리하고 감사 보존 정책과 분리한다.

이 API는 아직 구현·동결되지 않았다. S4가 필요하다는 자료 없이 미리 도입하지 않는다.

## 7. 정답 corpus와 사용자 합격 기준

기존 corpus는 `/private/tmp/deskseed-agent-read-20260919/search-corpus.json`, seed **20260919**, SHA-256은 다음과 같다.

`5d4df63a1912af7a991ed71bc61b7d579d92c7b6e862db96e523d0bef597173d`

8군×20개다. `absent`는 검증된 무결과 oracle이 아니다. 원문을 공유 문서에 출력하지 않는다.

보강 사례: 번호 active/CLOSED/삭제/없음/필터 불일치, 제목·요청자·이메일, PUBLIC 전용/INTERNAL 전용/양쪽, 흔한·반복 본문, 1/2/3글자 이상, 특수·긴 입력, true no-result, 깊은 cursor, 댓글·배정·CLOSED·삭제·권한 변경.

case별 허용된 정답 ID와 0~3 관련도 등급을 보호된 자료로 만든다. 조정용·검증용을 분리한다. 합성 규칙 검증과 실제 업무 관련도 판단을 구분하며 미판정 품질을 통과로 표시하지 않는다.

| 지표 | 초기 목표 |
| --- | --- |
| 번호 exact: 제출→사용 가능한 행 | p95 ≤300ms |
| 첫 유용한 결과 시간(TTFUR) | p95 ≤2s, 중앙값 목표 ≤1s |
| 2글자 이상 known-result | refine만 반환하고 끝나지 않음 |
| 품질 | Success@20, nDCG@10, 확장 후 정답 회수율을 분류별 비교 |
| 비동기 projection을 도입한 경우 신선도 | 일반 검색 p95 ≤5s, exact는 현재 DB 즉시 조회 |
| 권한·삭제·감사 위반 | 0건 |
| 확장 실패 | 기존 행·선택·포커스 유지 |

TTFUR은 판정된 유용한 결과가 화면에서 사용 가능해질 때 종료한다. HTTP 200·빈 부분 응답·spinner 해제·422는 종료 조건이 아니다. timeout/중단/미제공을 분모에서 빼지 않는다. 실패 때문에 목표 percentile을 충족할 수 없으면 SLO 실패로 기록한다. 실제 무결과는 별도의 정확한 무결과 완료 지표로 평가한다.

percentile 주장은 분류별 최소 200회 관측과 3회 반복을 확보한다. 현재 20건은 진단 smoke다. warm/cold, 표본 수, 실패율을 함께 기록한다.

## 8. 실제 서버 실행·증거 수집 절차

대상 화면은 https://deskseed.dhkim.cloud/agent/search, 앱 서버 기록은 `172.16.16.19`, Grafana는 `http://172.16.16.18:3000`이다. 재개 시 확인하고 로컬 backend 결과를 실제 서버 결과로 대체하지 않는다.

1. 이미지 SHA/OCI revision, Flyway/PG, projection/index 상태, 데이터·corpus hash, 계정 권한, 서버/생성기 자원·시계·background 작업을 기록한다.
2. app/DB/k6/generator 및 실제 요청 trace를 확인한다. `No data`는 미확인이다. shared pgss를 편의를 위해 reset하지 않는다.
3. 감사가 켜진 정상 인증 경로로 1 VU 기준선을 수집한다.
4. 동일 버전·데이터의 별도 load DB에서 plan capture를 실행한다.
5. 검증한 변경 한 가지를 기존 exact-SHA 배포 경로로 반영하고 image/migration/health/restart를 확인한다.
6. 같은 corpus·필터·정렬·페이지 크기·권한으로 서버 HTTPS와 브라우저를 비교한다. 본 부하는 readiness 통과 후 search-only 1→5→10 req/s, 준비 3분+측정 10분, 고정 VU/계정 수를 초기값으로 한다.
7. 단계마다 회복 확인. 측정 중 배포·인덱스 생성·적재·통계 수동 갱신을 하지 않는다. 공유 서비스 cache를 지워 cold 조건을 만들지 않는다.

### 실제 사용한 기준선 명령

아래 protected env에는 인증정보가 있으므로 내용을 출력하지 않는다. 동일 파일을 재사용할 때 run ID는 새 파일로 바꾼다.

```bash
cd /private/tmp/deskseed-postgresql-search-20260921
bash scripts/load/run-k6.sh agent-read \
  /private/tmp/deskseed-agent-read-20260919/pg-search-baseline-20260921T084421Z.env \
  /Users/donghyunkim/Documents/deskseed/artifacts/search-load/postgresql-search-20260921T083600Z/runs \
  /private/tmp/deskseed-agent-read-20260919
```

필수 설정: `TEST_ENVIRONMENT=personal-staging`, 고유 `TEST_RUN_ID`, `AGENT_READ_MODE=search-only`, `SEARCH_SEED=20260919`. 본 부하는 대상 확인 값, 고정 PREALLOCATED_VUS/MAX_VUS와 일치하는 active-agent 계정 풀이 필요하다. 기존 runner의 500ms 기본 threshold와 이번 2초 사용자 목표를 혼동하지 말고 명시적인 설정과 측정 단위를 기록한다.

### 기록 빈도와 형식

| 시점 | 남길 자료 |
| --- | --- |
| 실험 전 | 가설·변경 한 가지·환경/SHA·기준 Grafana/DB 통계 |
| 시작 | 실제 명령·run ID·시작 UTC·부하/corpus/계정 조건 |
| 실행 중 | **최소 1분마다** 캡처+Inspect JSON/CSV/조회 결과. 짧은 실행도 진행 중 캡처 1회 이상 |
| 이상 발생 | 즉시 고정 시간 구간, 오류/timeout, 안전한 trace와 대기/SQL 통계 |
| 종료 | 종료 UTC·원시 k6·검색군 결과·reset/eviction 확인한 pgss 차이 |
| 회복 | 자원·대기열·오류·지연의 기준 상태 복귀 여부 |
| 다음 실험 전 | 채택/기각/보류와 근거·다음 행동을 문서에 반영 |

E-ID마다 파일·run/SHA·UTC·dashboard UID/panel/datasource/query·단위·집계·표본 수·주장·한계를 연결한다. URL은 절대 시간으로 고정한다. pgss에 다른 트래픽이 섞이면 오염 가능성을 표시한다.

캡처 파일 저장이 안 되면 `캡처 파일 미확보`로 기록하고 실제 쿼리 결과·고정 URL을 보존한다. 대화에 표시한 이미지는 repository PNG가 아니다. 원시 plan에 합성 literal이 포함되면 보호 디렉터리에 두고 공유용 node 요약을 별도로 만든다.

증거 루트의 `evidence-index.md`, `execution-log.md`, `comparison.md`에서 전체 자료를 찾을 수 있다. 실패한 조회·probe·실험도 삭제하지 않는다.

## 9. 검증·중단·rollback

```bash
cd /private/tmp/deskseed-postgresql-search-20260921
make docs-check
git diff --check
cd backend
./gradlew test \
  --tests 'dev.deskseed.staffaccess.internal.AgentTicketSearchIntegrationTest' \
  --tests 'dev.deskseed.staffaccess.internal.AgentTicketSearchCursorCodecTest' \
  --tests 'dev.deskseed.ticketing.internal.StaffTicketSearchSqlPlanTest' \
  --tests 'dev.deskseed.ticketing.internal.StaffTicketQueryEvidenceIntegrationTest' \
  --tests 'dev.deskseed.ticketing.internal.SplitTicketSearchProjectionMigrationTest'
```

위 목록은 실행 절차이며 아래 기록 없이 통과로 간주하지 않는다. 확인된 버그는 실패 회귀 테스트를 먼저 추가한다. SQL 문자열 테스트만으로 성능이나 권한 안전성을 증명하지 않는다.

UI 변경은 target Staff Console documentation MCP를 먼저 사용하고 focused `run-story-tests`, changed stories/preview 및 실제 브라우저 흐름을 검증한다. 다른 앱의 도구나 package script로 대체하지 않는다. 미연결은 검증 공백으로 남긴다.

- 즉시 중단: 권한/INTERNAL 노출, 삭제 결과 재등장, 필수 감사 실패, 손상, OOM/재시작, 예상 WAL/audit 증가를 감당할 공간 부족.
- 상승 중단: generator/trace 공백, dropped iterations, pool timeout, 지속 p95>2초. bounded 진단으로 원인을 확인한다.
- monitoring 시작 기준: 20% 및 5GiB 이상, 예상 저장량 2배 여유. 과거 수치가 아닌 현재 값을 기록한다.
- 각 구현 PR에 task brief·REQ·gate와 API/정렬/cursor 호환성을 명시한다. 계약/ADR부터 확정한다.
- 후보 flag 또는 이전 정확한 SHA로 rollback한다. 기존 projection/index 세대는 검증 기간 유지한다. additive migration은 남기고 티켓/audit 데이터·공유 통계를 되돌리지 않는다.
- rollback 후 동일 smoke와 서버 회복 증거를 남긴다. 현재는 제품 변경이 없어 실제 rollback을 수행하지 않았다.
- projection/cache를 도입하면 쓰기·WAL·저장·backfill·retention·revision·중복/역순 재시도·삭제·reconciliation을 검증한다.

## 10. 실행 기록과 최종 비교 상태

| UTC | 단계 | 결과·증거 | 다음 작업 |
| --- | --- | --- | --- |
| 08:35~08:36 | 서버/작업 경로 | E-001 SHA 확인, E-002 dirty 보존, isolated worktree 생성 | DB/계측 |
| 08:42 전후 | DB/계측 | E-003 PG17.11/V96/백만 건, E-006 generator 없음 | bounded baseline |
| 08:44:22~08:44:33 | 기준선 20건 | 15 page/5 refine, 최대5.078초, E-011 DB 취소 | trace·재현 DB |
| 08:44~08:49 | phase/DB 확인 | E-013 올바른 phase 조회, E-014 구버전1만 건 load DB | 환경 공백 기록 |
| 08:51:51 | 첫 sampled probe | 잘못된 interaction header로400, E-015는 transport만 입증 | 올바른 header 재실행 |
| 08:52:51 | 정상 sampled probe | E-017/E-018 번호 성공과 사전422 trace | 느린 case 매핑 |
| 08:54 전후 | 원문 없는 case 매핑 | E-019 shuffled↔original 대응 | topic:3 재현 |
| 08:54:43 | 느린 요청 trace | HTTP3.056초, PAGE2.888초, audit33ms, E-021/E-022 | 동일 규모 node 분석 |

각 갱신은 상태·가설·SHA/환경·명령·시작/종료 UTC·E-ID·성능/품질 결과·판정·rollback·다음 항목을 포함한다.

최종 개선 비교에 필요한 항목은 분류별 표본 수, TTFUR p50/p95, 2초 내 유용한 결과 비율, timeout/refine/무결과/실패 수, Success@20/nDCG@10/recall, 확장 동작, 신선도, 자원·쓰기·저장 비용, 회복이다. 이번에는 bounded backend candidate A/B를 완료했지만 품질·브라우저 TTFUR·capacity percentile은 아직 계산하지 않는다.

| 완료 항목 | 현재 상태 |
| --- | --- |
| 문서·증거 목록 | 작성 및 검증 완료 |
| 실제 서버 기준선·진단 trace | 완료, 제한된 요청 수·조건 명시 |
| 정답/관련도 oracle | 미작성 |
| 동일 규모 SQL 실행 계획 | 환경 미확정으로 미실행 |
| 제품 코드·migration·API·UI 변경 | PR #233 검색 경로/기본 정렬, PR #234 V97 인덱스 완료; API/권한/감사 계약 유지 |
| 제품 테스트·배포·개선 A/B·rollback 실험 | 테스트·두 exact-SHA 배포·A/B 완료; 후보가 채택 기준을 충족해 rollback 미실행 |
| 사용자 관점 성능 개선 달성 | 동일 문제 입력 HTTP 47.876~49.899ms로 반복 확인; 실제 브라우저 TTFUR·관련도는 미검증 |

핵심 trade-off: PostgreSQL 안에서 실제 처리량을 줄이는 변경을 먼저 검증한다. 부분 후보 선택이 필요하면 완전성 한계를 명시하고 유용성을 측정한다. 권한·삭제·필수 감사 비용을 제외하고 빠르다고 주장하지 않는다.

### 이번에 실행한 문서 검증

- `make docs-check`: PASS. OpenAPI bundle 검사 1개, documentation quality 36개, documentation validation 2개 및 최종 validator PASS. 제품 회귀 테스트를 대신하지 않는다.
- `git diff --check`: PASS. 새 문서의 공백·증거 파일 링크·corpus hash·root/worktree mirror 일치도 별도 확인했다.
- E-023 회복 확인: 필수 targets 4개 up, PG up, pool pending/timeout 0, 직전 5분 required audit failure 증가 0, app CPU 약0.10%. generator 지표는 미확보다.
- E-024는 초기 진단 종료 시점에 서버 backend/frontend SHA가 `0459830`이었음을 보존한 역사적 증거다. 이후 후속 실행에서 PR #233과 #234를 순서대로 배포했으며 현재 상태는 아래 11절을 따른다.

## 11. 2026-09-21 후속 구현·배포·A/B 결과

### PR과 변경

1. [PR #233](https://github.com/kdh949/deskseed/pull/233), commit `547c300fd57d7a65355619d8ea6e992650a0d337`
   - 숫자 검색을 exact ticket-number 경로로 분리했다.
   - Staff Console 기본 정렬을 `updatedAt:desc,ticketNumber:desc`로 바꾸고 이 경로에서 관련도 점수 계산을 제거했다.
   - 권한 projection, active/terminal, 삭제 재검증, 필수 감사, cursor, 5초 budget은 유지했다.
   - 전체 CI, exact-SHA 이미지, personal staging 배포를 완료했다.
2. [PR #234](https://github.com/kdh949/deskseed/pull/234), commit `b6be5ae34b54d442f933b660e88abb1d262a8dc0`
   - V97에서 `tickets(updated_at desc, ticket_number desc) include(id)` B-tree를 추가했다.
   - migration test, schema blueprint, ADR-0054, D-071, traceability를 함께 갱신했다.
   - 첫 CI attempt의 무관한 credential concurrency test 실패는 failed-job 재실행에서 통과했다. 최종 CI와 exact-SHA 이미지가 성공했다.

### 실제 단일 DB 배포

- 배포 SHA: `b6be5ae34b54d442f933b660e88abb1d262a8dc0`
- backend/frontend 시작: 11:15:44 UTC, OCI revision exact match, restart count 0
- Flyway 97 success, index valid/ready, 크기 47MB
- PostgreSQL 17.11과 기존 1,000,000-ticket DB 하나만 사용했다. DB/replica를 추가하지 않았고 pool/work_mem/server setting을 바꾸지 않았다.
- root filesystem 54GiB free, health `UP`.

### 고정 corpus 전후

동일 protected corpus SHA, seed `20260919`, 계정, sort, 1 VU, 20 iterations를 사용했다. 각 실행은 16 page/4 refine, local dropped 0이었다.

| 후보 | 3회 max | 3회 page p95 | 판정 |
|---|---:|---:|---|
| PR #233 | 593.333 / 3384.772 / 3545.194ms | 455.468 / 1186.265 / 1286.029ms | broad tail 재현, 완전한 해결 아님 |
| PR #234 | 603.689 / 494.915 / 402.265ms | 426.251 / 416.726 / 398.445ms | 3초 tail 미재현, bounded candidate 채택 |

동일 protected `topic:3`은 PR #233에서 4166.014ms였고 Tempo PAGE 3814.715ms였다. PR #234 뒤 세 번은 49.899/49.748/47.876ms였다. 상세 확인한 두 trace의 PAGE는 9.55/7.31ms였다. 한 pre 표본 대비 단건 HTTP 감소율 98.8%는 percentile이나 capacity 개선율이 아니다.

### 원인 판정

- **관측 사실:** 이전 느린 요청은 대부분 PAGE에서 소요됐다. Hikari 대기/timeout, 필수 감사 실패, GC, response assembly가 같은 요청의 주원인은 아니었다.
- **원인 후보:** broad literal substring 결과를 최신순으로 반환할 때 order-compatible 접근 경로가 없어 후보 검사·heap read·정렬 비용이 커졌다.
- **확인된 원인 범위:** PR #233과 PR #234 사이의 유일한 runtime 변경은 latest-first B-tree다. 같은 코드/데이터/입력의 3회 corpus와 3회 동일 topic에서 tail이 제거됐고 pgss post-window는 46 broad calls/총 6215.464ms, 추가 temp writes 0이었다. 따라서 **최신순 접근 경로 부재가 이번 재현 지연에 기여했고 V97이 이를 해결했다**고 판정한다.
- **확정 불가:** PostgreSQL이 선택한 정확한 executor node와 substring/heap/TOAST/sort별 기여도. personal staging에서는 EXPLAIN ANALYZE를 실행하지 않았고 동일 규모 load DB가 없다.

### Grafana와 회복

- 실제 대시보드, Prometheus Explore, Tempo trace, Query Inspector를 렌더링해 커서 없는 화면을 대화에 캡처했다. 내구성 있는 PNG는 확보하지 못했다.
- R3 Grafana k6 class p95는 topic/page 58.224ms, common/page 393.851ms 등 8 series가 조회됐다. application histogram의 같은 UTC 창 topic PAGE p95는 약 27.4ms였다.
- Hikari pending/timeout 0, required audit failure 0, blocking 0, `pg_up=1`, backend restart 0, health `UP`으로 회복했다. DB log에 timeout/cancel/deadlock/ERROR/FATAL 일치 행은 없었다.
- generator CPU/RAM/network와 DB wait series는 No data다. dashboard의 짧은 k6 run 상세 패널은 stale marker 때문에 No data였고 동일 datasource의 Explore `last_over_time` 결과와 구분했다.

### 남은 작업과 중단 범위

- generator telemetry가 없으므로 1→5→10 req/s 본 부하는 실행하지 않았다. 이 공백을 0 또는 정상으로 해석하지 않는다.
- 최소 200회 분류별 percentile, 브라우저 TTFUR, Success@20/nDCG@10, 권한·삭제 실패 주입, 동일 규모 executor plan은 미검증이다.
- 제품 쿼리·인덱스·pool의 추가 변경은 하지 않았다. 현재 채택 후보는 personal staging에 배포돼 있다.
- 상세 실행·query·trace·제약은 evidence root의 E-033~E-044와 `comparison.md`에 있다.
