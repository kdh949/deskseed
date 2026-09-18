# AI 비용 절감 S06a — 요약·분류 완료 결과 정확 일치 캐시

## Goal

같은 상담사·티켓의 schema v2 요약 또는 분류 요청이 동일한 PUBLIC 입력과 생성 구성을 가질 때, 현재 권한과 source freshness를 다시 검증한 뒤 유효한 `SUCCEEDED` 결과를 새 logical job에 재암호화해 provider 호출 없이 재사용한다.

## Decision and source references

- Decision IDs: D-003, D-008, D-009, D-018, D-021, D-054, D-066.
- Accepted ADRs: 0003, 0008, 0009, 0018, 0025, 0049.
- Requirements: REQ-AI-002, REQ-AI-003, REQ-AI-004, REQ-AI-005.
- Plan: `docs/ai-v1/COST_REDUCTION_PLAN.md` S06 and sections 8.1~8.2, 11, 12.
- API operations: AI internal `accept_job_internal_v1_jobs_post`, `get_job_internal_v1_jobs__job_id__get`; Backend `getInternalAiRequestContext`, `getInternalAiRequestContextRevision`, `getInternalAiPolicy`.
- Verification gates: AI-API-001, AI-SRC-001, AI-LIFE-001, AI-RET-001, AI-OBS-001.
- 선행 구현: S05 input revision PR #192. 이 slice는 Accepted decision이나 외부 Staff API/UI 계약을 바꾸지 않는다.

## Actor, source and resource boundary

- 요청 actor는 기존 active `STAFF`/`AGENT_UI`, source read actor는 registered job에 고정된 `INTEGRATION_CLIENT`/`AI_SERVICE`다.
- cache scope는 server-owned workspace, requester staff, ticket, feature로 고정한다. caller는 cache key, reuse scope, TTL 또는 원 결과를 선택하지 않는다.
- cache lookup 전 Backend source context와 policy를 정상 호출해 current owner binding, active staff, ticket read capability, cancellation, deadline, feature enablement, `contextRevision`, `aiInputRevision`을 다시 검증한다.
- cache hit도 새 logical job과 기존 polling/result-read audit 경계를 유지한다. 원 job 권한이나 과거 audit을 새 요청 권한 근거로 재사용하지 않는다.
- S06a는 `ticket.summary`와 `ticket.triage`만 cache 후보로 한다. `ticket.reply_draft`는 canonical PUBLIC corpus revision과 published index generation이 아직 없으므로 보수적으로 miss하며 S06b에서 별도 완성한다.

## Exact cache identity and eligibility

- key version은 `result-cache-summary-triage-v1`이며 HMAC-SHA-256을 사용한다. secret은 DB/Git/log/metric에 저장하지 않는다.
- canonical key 입력은 workspace, requester, ticket, feature, `aiInputRevision`, `inputPolicyVersion`, normalized language/tone, prompt digest/version, output schema version, model route version, resolved model alias, Backend policy version, AI config version과 context-builder version이다.
- key 입력은 모두 lookup 전에 server-owned metadata로 구성한다. PUBLIC body, email/phone, raw ticket/customer data와 raw idempotency key를 key·로그·metric label에 넣지 않는다.
- schema v2이며 paired input revision이 있는 job만 eligible하다. schema v1과 pre-S05 `public-comments-v2` legacy job은 캐시를 읽거나 쓰지 않는다.
- 원 결과는 uncancelled `SUCCEEDED`, ciphertext 존재, result/cache expiry 전, 요약·분류 feature, 동일 requester/ticket이어야 한다. `NEEDS_REVIEW`, `FAILED`, `UNKNOWN` cost, `CANCELLED`, `SUPERSEDED`, `EXPIRED`는 cache entry가 될 수 없다.
- TTL은 최초 생성 시각 기준 24시간과 원 result expiry 중 이른 값이다. hit는 TTL을 연장하지 않으며 hit job은 다시 cache origin이 되지 않는다.

## Activation contract

- 기존 UI는 generation mode를 보내지 않으므로 S06a cache 경로는 기본 `off`다.
- 이 PR의 test-only mode는 production에서 시작을 거부하고 직접적인 server/AI 회귀 테스트에만 사용한다. S08a가 `REUSE_OR_CREATE | NEW_CANDIDATE`를 계약화하기 전에는 운영 flag를 켜지 않는다.
- mode 생략 요청은 계속 신규 생성 의미를 유지한다. S06a 구현만 배포해 기존 버튼이 몰래 cache hit로 바뀌지 않는다.
- S08a 이후에도 `NEW_CANDIDATE`는 이 cache를 우회하고, `REUSE_OR_CREATE`만 유효 cache를 사용한다.

## In scope

- 이 S06a task brief.
- AI additive migration 008: `ai_result_cache`와 logical job의 origin/reuse metadata. 원 job/execution 참조, HMAC key, version, created/expires/invalidated timestamps만 저장한다.
- 요약·분류 exact key builder, test-only activation guard, cache lookup/commit/invalidation repository API.
- 원 결과 복호화 후 Typed result 재검증, 새 job UUID AAD로 재암호화, generatedAt/provenance와 원 만료 상한 보존.
- source/policy 재검증 뒤 provider reservation 전에 hit를 완료하고, miss일 때만 기존 provider 경로를 실행한다.
- 원 job cancel, result retention purge, malformed/decryption failure, stale/expired entry의 fail-closed miss/invalidation.
- PostgreSQL 동시 hit, cancellation, expiry, encryption AAD, content-free telemetry 회귀 테스트.

## Out of scope

- reply draft cache와 canonical corpus/index generation 구축(S06b).
- 진행 중 동일 입력 coalescing/shared execution(S07).
- 외부 generation mode, 새 후보 제한, reuse reason/cost API와 UI(S08a/S08b).
- cache hit job을 새 origin으로 쓰는 chain, cross-requester/cross-ticket/cross-workspace reuse, sliding TTL.
- Redis cache, provider prompt cache(S13), live provider, human quality evaluation, merge·배포.
- raw PUBLIC body, 감사 증빙, screenshot, one-off script, 부하 로그의 Git 커밋.

## Invariants and failure semantics

- cache key 일치는 authorization, required source access audit, cancellation, deadline, feature policy, source/context freshness를 대체하지 않는다.
- cache lookup과 cache-hit result commit은 짧은 PostgreSQL transaction에서 origin row와 entry를 lock한다. provider network I/O나 Backend call을 transaction 안에서 실행하지 않는다.
- origin ciphertext는 origin job UUID AAD로만 복호화하고 소비 job UUID AAD로 새 nonce/ciphertext를 만든다. ciphertext를 그대로 복사하지 않는다.
- decrypt/schema/provenance/expiry 불일치는 원문이나 unbounded 오류를 반환하지 않고 entry를 invalid 처리한 뒤 miss한다.
- cache hit commit은 current generation/lease epoch/status로 fence한다. fence를 잃으면 새 result를 저장하지 않는다.
- 원 job cancel은 해당 cache entry를 invalid 처리한다. 이미 materialize된 소비 job은 자신의 current Backend authorization/cancellation 검증으로만 사용 가능하다.
- cleanup은 만료된 result와 cache reference를 bounded `FOR UPDATE SKIP LOCKED`로 처리하며 idempotent하다. cache가 result 7일 보존을 연장하지 않는다.
- cache hit의 증분 provider 비용은 0이고 origin 생성비를 소비 job에 복제하지 않는다. 실제 saved cost는 counterfactual이므로 청구 절감액으로 보고하지 않는다.

## Data and privacy

- 읽기: job binding/revision/config metadata, origin encrypted typed result와 provenance.
- 저장: HMAC key, origin job reference, bounded key/version/status timestamps, 소비 job의 새 ciphertext/nonce와 origin reference.
- 저장하지 않음: PUBLIC/INTERNAL body, prompt, raw key material, email/phone, Authorization, provider secret, raw idempotency key.
- result는 기존 7일 authenticated-encryption retention을 따르고 cache entry는 최대 24시간이다. metadata는 기존 30일 cleanup 범위를 넘기지 않는다.
- cache metadata와 result는 customer API/webhook/ordinary export/Langfuse metadata에 노출하지 않는다.

## Threats changed

- cross-actor/result disclosure: requester/ticket/workspace가 key와 row validation에 모두 포함되고 current source authorization을 다시 지불한다.
- key enumeration/content leakage: secret HMAC과 versioned length-delimited canonical encoding을 사용하며 key material/content를 관측하지 않는다.
- ciphertext substitution: origin/consumer job UUID AAD와 typed result validation으로 차단한다.
- stale result reuse: input/prompt/model/policy/config version과 absolute TTL을 key/entry에 고정하고 source freshness를 hit 직전에 재검증한다.
- cancellation race: origin/consumer row locking과 fenced commit, origin cancellation invalidation으로 fail closed한다.
- mixed deployment: migration과 reader를 먼저 배포하고 cache mode는 off를 유지한다. unknown key version/legacy job은 miss한다.

## Acceptance scenarios

1. 같은 requester/ticket/summary 또는 triage의 동일 v2 input/config 요청 두 개를 test-only mode에서 순차 실행하면 첫 job만 provider call/cost를 만들고 둘째는 새 job ciphertext로 같은 typed result를 받는다.
2. 둘째 job의 ciphertext/nonce는 원 job과 다르고 각 job UUID AAD에서만 복호화된다. generatedAt과 origin expiry 상한은 보존되고 cache TTL은 늘지 않는다.
3. 다른 requester, ticket, feature, language/tone, input revision/policy, prompt digest, output schema, model route/alias, Backend policy version 또는 config version은 miss한다.
4. INTERNAL/assignee/group 변경만 있고 새 요청의 `aiInputRevision`과 생성 구성이 같으며 current authorization이 유효하면 hit할 수 있다. `contextRevision`은 각 새 job의 source 검증에 사용한다.
5. schema v1/pre-S05 legacy job, reply draft, `NEEDS_REVIEW`, failed/unknown/cancelled/superseded/expired origin은 read/write 모두 miss한다.
6. 원 job cancel 또는 retention purge 후 새 요청은 miss하며, 이미 materialize된 소비 job의 current permission 검증은 독립적이다.
7. cache lookup/복호화/typed validation/DB commit 실패는 provider를 중복 호출하기 전에 miss 또는 bounded failure로 수렴하고 plaintext/error detail을 log하지 않는다.
8. 같은 cache key를 동시에 조회해도 source result를 안전하게 각각 재암호화하며 provider call은 이미 완료된 origin에 대해서는 0회다. 진행 중 요청 합치기는 하지 않는다.
9. production에서 test-only cache mode를 켜면 startup이 실패하고, 기본 off/legacy UI 요청은 기존 신규 생성 경로를 유지한다.

## Validation

- `cd ai && .venv/bin/ruff check src tests scripts`
- `cd ai && .venv/bin/mypy`
- `cd ai && .venv/bin/pytest -q`
- PostgreSQL-backed migration/cache/AAD/TTL/cancel/retention/concurrency integration tests.
- fake provider call ledger에서 hit job provider call 0, origin cost 비복제, distinct ciphertext 확인.
- `cd ai && .venv/bin/python scripts/export_openapi.py` and generated internal OpenAPI parity.
- `cd ai && .venv/bin/python scripts/evaluate_fake.py` — contract evidence only; live quality/cost claim 금지.
- Backend regression suite because current source authorization remains the cache gate.
- `make docs-check`
- `git diff --check`
- latest PR HEAD CI.

## Compatibility and migration

- AI migration 008은 신규 cache table과 nullable origin/reuse metadata를 additive로 추가한다. existing jobs는 cache-ineligible이며 backfill하지 않는다.
- reader/migration/cache writer를 배포해도 activation은 off다. production은 S08a 전 test-only mode를 거부한다.
- rollback은 cache activation off, 신규 lookup/write 중지, 진행 job drain 후 애플리케이션 rollback 순서다. cache rows와 additive columns는 즉시 삭제하지 않고 TTL/metadata cleanup으로 소진한다.
- external Core/Staff API, Backend DB/OpenAPI와 current UI decoder에는 변경이 없다.
- S06b/S08에서 key namespace 또는 intent contract가 바뀌면 새 version으로 자연스럽게 miss시키며 기존 entry를 재해석하지 않는다.

## Human explanation

S06a는 이미 완료된 정확 일치 결과만 PostgreSQL에서 재사용한다. Redis나 공유 실행 lifecycle을 추가하지 않고, current Backend 권한·source audit를 매 요청 다시 수행한 뒤 encrypted result만 새 job에 재귀속한다. reply는 공개 KB 전체의 freshness version이 아직 없으므로 포함하지 않는다. 운영 activation은 S08a의 명시적 사용자 의도 계약까지 닫아 두어 기존 UI 의미를 바꾸지 않는다.
