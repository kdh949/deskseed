# AI 비용 절감 S08a — 재사용 의도와 새 후보 서버 계약

## Goal

상담사 AI job 생성 요청에 typed `generationMode`를 추가해 완료 결과 재사용/진행 실행 결합과 의도적인 새 후보 생성을 구별하고, legacy mode 생략 요청의 현재 신규 생성 의미를 유지한다.

## Decision and source references

- Decision IDs: D-003, D-008, D-009, D-018, D-021, D-054, D-066.
- Accepted ADRs: 0003, 0008, 0009, 0018, 0025, 0049.
- Requirements: REQ-AI-001, REQ-AI-002, REQ-AI-003, REQ-AI-004, REQ-AI-005.
- Plan: `docs/ai-v1/COST_REDUCTION_PLAN.md` S08a and sections 8.1~8.4, 11, 12.
- API operations: `createAgentAiJob`, `getAgentAiJob`, `listAgentAiJobs`; AI internal job accept/read.
- Verification gates: AI-API-001, AI-SRC-001, AI-LIFE-001, AI-COST-001, AI-OPS-001, AI-RET-001, AI-OBS-001.
- 선행 구현: S05 input revision #192, S06 cache #194/#196, S07 shared execution #198.

## Actor, source and request contract

- actor는 현재 active `STAFF`/`AGENT_UI`이며 기존 staff session, expected actor, CSRF, ticket read authorization, required access/activity audit를 그대로 적용한다.
- `generationMode`는 `REUSE_OR_CREATE | NEW_CANDIDATE`의 선택 필드다. caller가 cache key, candidate sequence, reuse reason 또는 provider operation identity를 공급하지 않는다.
- mode 생략은 legacy 신규 생성이다. cache hit/shared join에 참가하지 않으며 현재 UI decoder와 request payload를 변경하지 않는다.
- 명시적 `REUSE_OR_CREATE`는 Backend가 계산한 동일 actor/workspace/ticket/feature/input revision/config 범위에서만 완료 cache 또는 진행 shared execution에 참가한다.
- 명시적 `NEW_CANDIDATE`는 Backend가 발급한 candidate UUID/sequence를 AI key에 포함해 기존 완료 cache와 진행 execution을 모두 우회한다.
- 같은 `Idempotency-Key`의 exact replay는 기존 job/candidate를 반환한다. 동일 key의 mode/options/input 불일치는 mutation 없이 409다.

## Candidate limit policy

- 같은 workspace/actor/ticket/feature/`aiInputRevision`에서 24시간 동안 명시적 `NEW_CANDIDATE`는 최대 2회다. 최초 `REUSE_OR_CREATE` 생성은 이 횟수에 포함하지 않는다.
- Backend는 advisory transaction lock 아래 현재 24시간 window 소비량과 전체 monotonic candidate sequence를 결정한다.
- 한도 초과는 `429`와 bounded `Retry-After`를 반환하며 job, audit, outbox, quota row를 만들지 않는다.
- provider dispatch 가능성이 생긴 job과 UNKNOWN은 quota를 유지한다. provider call이 없고 AI가 확정한 pre-dispatch terminal failure는 한 번만 환원할 수 있다.
- 한도는 기존 workspace/actor admission rate와 AI budget을 대체하지 않고 모두 적용한다.
- 이 v1 상수는 운영 중 임의 변경 가능한 generic setting이 아니다. 변경 시 계약·관측 window·UI 문구를 함께 versioning한다.

## Internal envelope and AI behavior

- explicit mode job만 internal envelope schema v3를 사용하고 `generationMode`, server-issued `candidateId`, `candidateSequence`를 전달한다.
- legacy mode omission은 schema v2와 현재 strict decoder 의미를 유지한다.
- AI migration은 job generation intent/candidate identity와 terminal `reuseKind`를 저장한다.
- production activation mode는 schema v3 `REUSE_OR_CREATE`에만 S06 cache/S07 shared execution을 허용한다. test mode의 기존 schema v2 fixtures는 회귀 검증에만 유지한다.
- `NEW_CANDIDATE` key는 candidate identity를 포함하고 결과를 다른 logical request의 exact cache origin으로 공개하지 않는다.
- 완료 receipt의 bounded `reuseKind`는 `GENERATED | CACHE_HIT | COALESCED`다. legacy와 NEW_CANDIDATE provider 결과는 `GENERATED`; waiter cache materialization은 `COALESCED`; 이미 완료된 S06 hit는 `CACHE_HIT`다.
- `incrementalCostMicrousd`는 현재 logical request의 비용이며 cache/coalesced follower는 0이다. 참조 execution 원 비용이나 cache key는 staff API에 노출하지 않는다.
- internal AI receipt는 quota 환원 판단용 `providerDispatched` boolean을 Backend에만 제공하며 staff response에는 노출하지 않는다.

## Transaction, failure and audit semantics

- Backend는 authorization, idempotency lock, mode validation, candidate quota claim, `ai_requests`, content-free outbox, required read/activity audit를 한 transaction으로 commit/rollback한다.
- required audit 실패 시 quota와 job/outbox도 rollback한다.
- quota 환원은 terminal AI receipt와 local job binding을 다시 확인해 single-winner timestamp를 저장한다. 일반 polling 실패, timeout, UNKNOWN, cancel, result invalidation에는 환원하지 않는다.
- AI가 mode/candidate shape가 맞지 않는 schema v3 envelope를 받으면 inbox/job을 만들지 않고 거부한다.
- cache/shared result도 current Backend source authorization, input revision, policy, KB generation/citation, cancel/deadline을 소비 job별로 다시 확인한다.
- ordinary log/audit에는 idempotency 원문, PUBLIC/INTERNAL body, prompt/query/result, cache key, candidate UUID를 저장하지 않는다. activity audit detail은 feature와 generation mode만 저장한다.

## In scope

- Core OpenAPI create/receipt/429 계약과 Backend migration/service/controller/tests.
- AI internal OpenAPI schema v3, persistence, intent-aware cache/shared keying, reuse metadata, quota-refund signal, tests.
- explicit mode request의 20-way reuse/shared 회귀와 NEW_CANDIDATE cache/shared bypass 회귀.
- legacy omission compatibility, exact idempotency, 24시간 2회 limit, atomic concurrency, 429 Retry-After, refund-once 회귀.
- `docs/52-admin-settings-catalog.md` typed policy 기록.

## Out of scope

- Staff UI 버튼·상태·문구와 frontend generated client(S08b).
- candidate 결과 자동 비교/선택, cross-actor/ticket reuse, semantic cache, merge·deploy.
- live provider·사람 품질 평가와 실제 사용 초안당 절감 주장.

## Validation

- Core OpenAPI bundle/description/example/manual-review checks.
- Backend full tests, migration tests, legacy request fixture, 20-way candidate quota concurrency, audit rollback, 429 header.
- AI Ruff, mypy, full pytest, schema v3 decoder and mode-key tests, 20-way explicit reuse, NEW_CANDIDATE bypass, receipt metadata/refund signal.
- fake provider evaluation is contract evidence only; quality remains `NOT_ESTABLISHED`.
- `make docs-check`, `git diff --check`, latest PR HEAD CI.

## Compatibility and rollout

- nullable Backend/AI columns and schema v3 support are additive. 과거 job을 backfill하거나 과거 hash를 재해석하지 않는다.
- deploy order는 OpenAPI/reader/migration → Backend v3 producer and AI v3 consumer → intent activation이다.
- legacy omitted mode remains uncached new generation until S08b explicitly sends `REUSE_OR_CREATE`.
- rollback은 intent activation off → 신규 explicit request 거부/legacy path 유지 → in-flight v3 drain → application rollback 순서다.

## Human explanation

S08a는 동일 입력을 언제 재사용해도 되는지 사용자가 보낸 의도로 구별한다. 기본 재사용 요청은 안전한 완료 결과나 진행 실행을 찾고, “다른 초안”은 서버가 별도 candidate identity를 발급해 이전 결과를 우회한다. 기존 UI 요청은 mode가 없으므로 동작이 바뀌지 않으며, 새 후보는 24시간 두 번으로 제한해 비용과 반복 클릭을 통제한다.
