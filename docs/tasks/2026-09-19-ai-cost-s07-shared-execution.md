# AI 비용 절감 S07 — 진행 중 정확 일치 요청 공유 실행

## Goal

같은 상담사·티켓에서 동일한 schema v2 AI 입력과 생성 구성을 가진 요청이 동시에 도착할 때 PostgreSQL의 하나의 shared execution에 결합해 provider 실행을 한 번만 수행하고, 각 logical job의 권한·감사·취소·deadline·결과 암호화는 독립적으로 유지한다.

## Decision and source references

- Decision IDs: D-003, D-008, D-009, D-018, D-021, D-054, D-066.
- Accepted ADRs: 0003, 0008, 0009, 0018, 0025, 0049.
- Requirements: REQ-AI-002, REQ-AI-003, REQ-AI-004, REQ-AI-005.
- Plan: `docs/ai-v1/COST_REDUCTION_PLAN.md` S07 and sections 8.1~8.4, 11, 12.
- API operations: Backend `getInternalAiRequestContext`, `getInternalAiRequestContextRevision`, `getInternalAiPolicy`, `authorizeAiResultKnowledgeCitations`; AI internal `accept_job_internal_v1_jobs_post`, `cancel_job_internal_v1_jobs__job_id__cancel_post`, `get_job_internal_v1_jobs__job_id__get`.
- Verification gates: AI-API-001, AI-SRC-001, AI-LIFE-001, AI-COST-001, AI-KB-001, AI-REPLY-001, AI-RET-001, AI-OBS-001.
- 선행 구현: S05 input revision PR #192, S06 summary/triage cache PR #194, S06 reply cache/index generation PR #196. 이 slice는 외부 Staff API/UI 계약을 바꾸지 않는다.

## Actor, source and resource boundary

- 각 logical request actor는 기존 active `STAFF`/`AGENT_UI`이며 Backend는 job/requester/ticket/feature binding, required audit, idempotency와 cancel intent를 요청별로 계속 소유한다.
- AI worker는 각 job으로 Backend source context를 읽고 current authorization·cancellation·deadline·feature policy·input revision을 확인한 뒤에만 shared execution 결합을 시도한다. execution key는 caller가 공급하지 않는다.
- 결합 scope는 같은 workspace, requester, ticket, feature와 S06 exact generation key 전체다. cross-requester, cross-ticket, cross-workspace, schema v1/pre-S05 job은 결합하지 않는다.
- reply는 current Backend canonical corpus revision과 AI published index generation이 exact match할 때만 결합한다. summary/triage는 KB version을 key에서 제외하는 기존 계약을 유지한다.
- follower도 최초 source read required audit를 수행하고, 완료 후 S06 cache materialization에서 current source/citation을 다시 검증한다. 대표 job의 과거 권한이나 audit을 follower 권한 근거로 재사용하지 않는다.

## Shared execution data ownership

- AI migration 010은 `ai_shared_executions`와 `ai_execution_consumers`를 추가하고 provider call/ledger에 nullable execution binding을 추가한다.
- `ai_shared_executions`는 execution UUID, exact HMAC key/version, workspace/requester/ticket/feature, state, phase, representative job, execution generation·lease epoch, provider-dispatch 여부, terminal reason, created/updated/completed timestamp를 소유한다.
- execution key unique constraint가 유료 실행의 authoritative single-winner coordinator다. Redis는 기존 durable dispatch intent를 전달하고 완료 waiter를 깨우는 용도이며 `SETNX`를 실행 유일성 근거로 사용하지 않는다.
- `ai_execution_consumers`는 execution과 logical job의 unique binding, state(`ACTIVE`, `WAITING`, `COMPLETED`, `CANCELLED`, `FAILED`), joined/woken/completed timestamps를 저장한다. 본문·prompt·query·result plaintext는 저장하지 않는다.
- logical `ai_jobs`는 requester/ticket, source revision, cancel/deadline, polling/result-read audit, job UUID AAD와 UI-visible lifecycle을 계속 소유한다.
- provider call과 cost ledger는 execution UUID·execution generation·stage operation key에 묶고 비용 소유 logical job은 당시 representative 하나다. follower에 원 실행비를 복제하지 않는다.

## Claim, wait and wake state machine

1. worker가 current context/policy/index identity로 S06 exact key를 계산하고 completed cache를 먼저 조회한다.
2. cache miss이면 짧은 DB transaction에서 exact key를 claim한다. row가 없으면 현재 job이 representative `RUNNING` execution을 만들고 `LEADER`가 된다.
3. 같은 key의 `RUNNING` execution이 있으면 현재 job을 consumer로 추가하고 `WAITING`으로 전환한다. worker thread와 DB row lock을 장시간 유지하지 않고 현재 Redis message를 ack한다.
4. waiting job은 generation을 한 번 증가시켜 `RETRY_WAIT`에 두되 즉시 반복 dispatch하지 않는다. representative가 terminal transition을 commit할 때 아직 active인 waiter마다 기존 `ai_dispatch_outbox` intent를 원자적으로 만든다.
5. representative 성공은 result/cache write와 execution `SUCCEEDED` 및 waiter wake를 같은 transaction에 commit한다. waiter는 깨어난 뒤 completed cache를 통해 current source/citation을 재검증하고 자기 job UUID AAD로 결과를 저장한다.
6. no-evidence 또는 deterministic invalid output은 execution terminal reason을 body-free metadata로 저장한다. waiter는 provider 없이 자기 job을 동일 `NEEDS_REVIEW` reason으로 완료한다.
7. terminal known failure는 waiter를 동일 bounded failure category로 완료한다. raw exception/provider body를 공유 metadata에 저장하지 않는다.
8. completed execution에 cache가 없거나 origin이 ineligible하면 success를 추측하지 않는다. execution/cache invariant violation로 fail closed하고 새 provider 실행을 시작하지 않는다.

## Lease, crash and provider uncertainty

- shared execution lease는 logical job lease와 별도 generation/epoch로 fence한다. 대표 worker만 phase 변경, provider reservation, terminal transition을 할 수 있다.
- worker가 provider reservation 전 죽으면 같은 representative job의 Redis recovery/expired lease가 execution lease epoch를 올려 계속할 수 있다.
- provider operation key는 `executionId:executionGeneration:stage`로 고정한다. response receipt 없이 dispatch 가능성이 있는 reservation/call을 발견하면 새 provider call을 하지 않고 기존 `UNKNOWN` 정산 경계를 유지한다.
- provider 전송 후 worker crash, lease loss, receipt conflict 또는 불명 outcome은 execution을 `UNKNOWN` terminal로 만든다. follower는 `PROVIDER_OUTCOME_UNKNOWN`으로 끝나며 lease timeout만 보고 모델을 다시 호출하지 않는다.
- provider가 명시적으로 호출 전 실패한 경우에만 bounded retry가 같은 execution generation 정책 아래 허용된다. retry 여부는 logical job attempt가 아니라 shared execution call lifecycle과 deadline으로 결정한다.
- leader job completion과 execution terminal transition 사이의 gap이 없도록 같은 DB transaction을 사용한다. recovery는 terminal job/cache/call receipt와 execution state를 대조해 idempotently 수렴한다.

## Cancellation and representative transfer

- 각 cancel event는 해당 logical job과 consumer만 `CANCELLED`로 만든다. 다른 consumer의 Backend binding·audit·deadline은 유지된다.
- representative가 provider reservation 전에 취소되거나 deadline을 잃으면 oldest active waiter를 새 representative로 원자적으로 승격하고 그 job의 next generation dispatch를 만든다. 승격된 worker는 자기 Backend source를 다시 읽고 같은 exact key인지 확인한다.
- representative가 provider reservation/dispatch 이후 취소되면 이미 발생한 비용은 정산하되 다른 consumer를 새 provider leader로 승격하지 않는다. known successful result도 취소된 job에 저장하지 않으며, 안전하게 복구 가능한 receipt/result가 없으면 execution은 `UNKNOWN`이다.
- 모든 consumer가 provider reservation 전에 취소/만료되면 execution을 `CANCELLED` terminal로 만들고 provider call을 0회로 유지한다.
- waiter deadline이 지나면 그 job만 `EXPIRED`로 수렴하고 실행은 다른 active consumer를 위해 계속될 수 있다.

## Activation contract

- S07 shared execution은 신규 setting의 기본값 `off`다. `test` mode는 production startup에서 거부하고 직접적인 동시성·복구 회귀 테스트에만 사용한다.
- 기존 UI의 mode 생략 요청은 계속 신규 생성 의미를 유지한다. S08a가 `generationMode=REUSE_OR_CREATE | NEW_CANDIDATE`를 계약화하기 전에는 운영 shared execution을 켜지 않는다.
- S08a 이후 `REUSE_OR_CREATE`만 completed cache/shared execution에 참가하고 `NEW_CANDIDATE`는 별도 candidate key로 기존 cache와 in-flight execution을 모두 우회한다.

## In scope

- 이 S07 task brief.
- AI additive migration 010과 shared execution/consumer/call binding state machine.
- S06 exact key 기반 claim, follower durable wait, leader terminal wake, success cache materialization.
- summary, triage, reply의 동일 입력 동시 요청에서 generation 1회, reply query embedding 1회 보장.
- per-consumer cancellation/deadline, pre-call representative transfer, post-dispatch UNKNOWN, crash/lease recovery 회귀 테스트.
- content-free execution metrics/status와 retention cleanup.

## Out of scope

- Backend/Core generation mode와 새 후보 제한, reuse reason/cost receipt API(S08a).
- Staff UI의 결과 확인/다른 초안/공유 실행 대기 상태(S08b).
- cross-requester/cross-ticket reuse, Redis execution lock, provider-side idempotency 보장 추정.
- 검색 개선(S09), memory(S10), model routing(S11), rewrite(S12), prompt cache(S13), batch embedding(S14).
- live provider·사람 품질 평가, 실제 청구 절감 주장, merge·배포.
- 감사 증빙, screenshot, one-off concurrency script, 부하 로그의 Git 커밋.

## Invariants and failure semantics

- unique execution key와 fenced execution lease만 provider 실행 유일성을 결정한다. worker thread, Redis message, logical job lease만으로 중복 방지를 주장하지 않는다.
- cache lookup, execution claim, consumer join, terminal transition, waiter wake는 짧은 PostgreSQL transaction이다. Backend/provider network I/O 동안 DB lock을 유지하지 않는다.
- representative는 provider call 직전 current active consumer와 exact key를 다시 확인한다. 살아 있는 consumer가 없으면 reservation/call을 만들지 않는다.
- execution 하나의 provider stage마다 하나의 durable operation key와 call receipt만 허용한다. `UNKNOWN`은 자동 retry/re-election 대상이 아니다.
- 각 awakened consumer는 current Backend authorization, cancellation, deadline, input revision, policy, reply corpus/index/citation을 독립적으로 검증한다. 한 consumer 실패가 다른 유효 consumer 검증을 생략하게 하지 않는다.
- follower cost는 0이며 origin 실행비를 복제하지 않는다. 운영 비용 집계는 unique provider call/execution 기준이고 logical request 수와 분리한다.
- execution metadata cleanup은 terminal retention 이후 bounded `FOR UPDATE SKIP LOCKED`로 수행하며 active job/cache FK를 끊지 않는다.

## Data and privacy

- 저장: HMAC execution key/version, bounded owner IDs, logical job/execution references, state/phase/generation/epoch, body-free reason/timestamps, provider call binding.
- 저장하지 않음: PUBLIC/INTERNAL body, prompt, query, result plaintext, citation title/url, chunk content, email/phone, Authorization/provider secret, raw idempotency key.
- result ciphertext·cache TTL은 S06의 7일/24시간 상한을 유지한다. shared execution/consumer metadata는 기존 job operational metadata 30일 상한을 넘기지 않는다.
- execution key나 internal state를 customer API, webhook, ordinary export, Langfuse metadata에 노출하지 않는다.

## Threats changed

- duplicate paid calls: DB unique key, execution-based operation key, lease fencing, UNKNOWN terminal로 차단한다.
- follower privilege inheritance: 요청별 source audit와 완료 전 재인가를 유지하고 대표 권한을 복사하지 않는다.
- cancellation amplification: 한 consumer cancel은 다른 job을 취소하지 않고, all-cancel만 pre-call execution을 중단한다.
- leader crash/re-election race: provider reservation 전만 representative transfer하고 dispatch 가능성이 생긴 뒤에는 timeout 승격을 금지한다.
- result/cost duplication: follower는 S06 재암호화 result와 0 증분 비용을 가지며 call ledger는 execution stage당 하나다.
- backlog/notification storm: follower는 polling dispatch를 반복하지 않고 terminal wake outbox 한 번으로 재개한다.

## Acceptance scenarios

1. 같은 requester/ticket/summary exact key 20개 동시 요청은 logical job 20개와 required source audit 20개를 유지하면서 generation provider call은 1회다.
2. 같은 requester/ticket/reply exact key 20개 동시 요청은 query embedding 1회, generation 1회이며 각 waiter는 current citation 재인가 후 서로 다른 ciphertext/nonce를 받는다.
3. 다른 requester, ticket, feature, language/tone, input revision/policy, prompt/schema/model route, Backend policy, config 또는 reply corpus/index generation은 같은 execution에 결합하지 않는다.
4. cache가 이미 있으면 shared execution을 만들기 전에 S06 hit로 끝난다. 진행 execution 성공 후 waiter는 새 유료 call 없이 cache로 완료된다.
5. leader가 provider reservation 전에 취소되면 active waiter 하나가 대표로 승격되고 전체 provider call은 1회 이하이며 취소 job에는 결과가 없다.
6. follower 하나 취소는 그 job만 `CANCELLED`이고 leader/다른 follower는 정상 완료한다. 전원 pre-call 취소는 provider call 0회다.
7. provider dispatch 후 leader crash/lease expiry/receipt 부재는 execution과 모든 active waiter를 `PROVIDER_OUTCOME_UNKNOWN`으로 끝내고 call을 재시도하지 않는다.
8. no-evidence reply는 query embedding이 이미 필요했다면 1회, generation 0회이고 모든 active consumer가 `NEEDS_REVIEW/NO_APPROVED_KNOWLEDGE`로 수렴한다.
9. waiter가 깨어나기 전 권한·input revision·policy·KB generation·citation이 바뀌면 해당 waiter만 cache materialization을 거부하고 다른 유효 waiter는 독립적으로 완료한다.
10. worker crash 후 같은 leader message recovery, duplicate Redis delivery, terminal wake 재실행은 idempotent하며 execution/provider call/result를 중복 생성하지 않는다.
11. production에서 test shared-execution mode를 켜면 startup이 실패하고 기본 off/legacy UI 요청은 기존 신규 생성 경로를 유지한다.

## Validation

- `cd ai && .venv/bin/ruff check src tests scripts`
- `cd ai && .venv/bin/mypy`
- `cd ai && .venv/bin/pytest -q`
- PostgreSQL/Redis-backed 20-way concurrency, duplicate delivery, leader crash, lease expiry, cancel/election, UNKNOWN, deadline, retention tests.
- fake provider call ledger에서 summary/triage generation 1회, reply query embedding 1회·generation 1회, follower cost 0, distinct ciphertext 확인.
- `cd ai && .venv/bin/python scripts/export_openapi.py` — external schema parity 확인.
- `cd ai && .venv/bin/python scripts/evaluate_fake.py` — contract evidence only; live quality/cost claim 금지.
- Backend regression suite because source authorization/audit remains per logical request even though Backend contract is unchanged.
- `make docs-check`
- `git diff --check`
- latest PR HEAD CI.

## Compatibility and migration

- AI migration 010은 신규 shared tables와 nullable execution references를 additive로 추가한다. 기존 job/call은 execution-ineligible이며 backfill하지 않는다.
- reader/migration/state machine을 배포해도 activation은 off다. mixed worker에서 unknown execution metadata는 신규 결합을 하지 않고 기존 job path를 유지한다.
- rollback은 shared execution off → 신규 join 중지 → RUNNING execution drain/terminal 확인 → application rollback 순서다. provider-dispatch/UNKNOWN execution을 새 standalone job으로 자동 재실행하지 않는다.
- Core/Staff API, Backend DB/OpenAPI와 current UI decoder에는 변경이 없다. S08이 외부 generation intent/reuse receipt를 별도 계약한다.

## Human explanation

S07은 여러 요청을 하나의 UI job으로 합치지 않는다. 요청·감사·취소·deadline·결과 암호화는 그대로 여러 개이고, 비용이 드는 provider 실행만 PostgreSQL의 공유 실행 하나로 묶는다. follower는 leader를 기다리며 worker를 점유하지 않고, 완료되면 S06의 안전한 cache 경로를 다시 통과한다. provider가 이미 호출됐는지 모르는 상태에서는 새 leader를 선출하지 않아 이중 과금 위험을 명시적인 UNKNOWN으로 보존한다.
