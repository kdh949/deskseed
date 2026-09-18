# AI 비용 절감 S05 — 기능별 AI 입력 리비전

## Goal

Backend가 AI가 실제로 읽을 수 있는 ordered PUBLIC 대화의 기능별 canonical digest를 `aiInputRevision`으로 소유해, ticket metadata만 바뀐 요청은 같은 AI 입력으로 식별하면서 기존 `contextRevision` freshness와 v1 job 처리를 보존한다.

## Decision and source references

- Decision IDs: D-003, D-008, D-009, D-018, D-021, D-054, D-066.
- Accepted ADRs: 0003, 0008, 0009, 0018, 0025, 0049.
- Requirements: REQ-AI-002, REQ-AI-003.
- Plan: `docs/ai-v1/COST_REDUCTION_PLAN.md` S05 and section 8.1.
- API operations: `getInternalAiRequestContext`, `getInternalAiRequestContextRevision`, AI internal `accept_job_internal_v1_jobs_post`.
- Verification gates: AI-API-001, AI-SRC-001, AI-LIFE-001, AI-OBS-001.
- 이 slice는 Accepted decision이나 external Staff UI 계약을 바꾸지 않는다. S06 exact cache가 사용할 입력 identity만 먼저 만든다.

## Actor, source and resource boundary

- 요청 actor는 기존 active `STAFF`/`AGENT_UI`, source read actor는 registered job에 고정된 `INTEGRATION_CLIENT`/`AI_SERVICE`다.
- workspace, requester, ticket, feature와 source policy는 Backend가 소유한다. caller는 revision 값이나 revision에 포함할 필드를 선택하지 않는다.
- digest 입력은 ordered PUBLIC comment의 ID, PUBLIC-only sequence, persisted author role, createdAt, body digest와 feature/input-policy domain separator뿐이다.
- INTERNAL comment, assignee, group, status, priority, customer profile, collaboration/child relation, protected audit와 비공개 KB는 현재 세 feature의 `aiInputRevision` 입력이 아니다.
- `ticketVersion`과 `contextRevision`은 기존 command concurrency 및 in-flight freshness 검증을 위해 유지한다. `aiInputRevision`은 이를 대체하거나 권한 증명으로 사용하지 않는다.

## Revision contract

- 신규 job outbox schema v2는 `aiInputRevision`과 `inputPolicyVersion`을 필수로 전달한다. AI ingress는 schema v1과 v2를 함께 읽되 v1 payload를 v2 의미로 재해석하지 않는다.
- 기능별 input policy는 `summary-input-v1`, `triage-input-v1`, `reply-input-v1`의 닫힌 값이다. 현재는 세 기능 모두 같은 PUBLIC comment 필드를 읽지만 feature domain separator와 policy version을 분리해 향후 한 기능의 입력 확대가 다른 기능의 cache identity를 바꾸지 않게 한다.
- canonical serialization은 길이 경계와 UTF-8을 사용해 필드 결합 모호성을 없애고, body 원문 대신 SHA-256만 중간 representation에 넣는다. 최종 `aiInputRevision`도 lowercase SHA-256 64자다.
- v2 source context와 revision response는 bound `aiInputRevision`/`inputPolicyVersion`을 반환한다. Backend는 current PUBLIC projection으로 값을 다시 계산해 bound 값과 다르면 source body를 반환하지 않는다.
- legacy `public-comments-v1`/schema v1 job은 두 신규 필드가 null이며 기존 `contextRevision` 검증만 수행한다. nullable metadata가 있다고 v1 job을 cache 후보로 승격하지 않는다.
- 외부 Core `AiJobReceipt`/provenance와 현재 Staff UI response에는 신규 필드를 추가하지 않는다. S08의 명시적 generation mode와 UI rollout 전에는 현재 decoder와 신규 생성 의미를 유지한다.

## In scope

- S05 입력 identity를 설명하는 이 task brief.
- Backend additive migration V95: `ai_requests.ai_input_revision`, `input_policy_version`, v1/v2 paired shape constraint.
- AI additive migration 007: `ai_jobs.ai_input_revision`, `input_policy_version`, 같은 paired shape constraint.
- Backend feature별 canonical revision 계산, 신규 request persistence와 outbox schema v2.
- source context/revision API v2 필드와 OpenAPI v1/v2 compatibility 계약.
- AI ingress schema v1/v2 validation, persistence, claim/source/final freshness validation.
- metadata-only 변경과 PUBLIC 입력 변경, feature/policy domain separation, malformed mixed-version payload의 회귀 테스트.

## Out of scope

- S06 completed-result cache, cache key/HMAC, TTL, result re-encryption과 cache hit.
- S07 shared execution/coalescing, S08 generation mode/API/UI, S09 retrieval, S10 memory, S11 routing, S12 rewrite, S13 prompt cache, S14 batching.
- status/priority/group/assignee를 model input에 추가하는 제품 변경. 향후 추가 시 해당 feature input policy와 source/OpenAPI를 함께 version-up한다.
- Core Staff API/Frontend response 변경, rendered UI/Storybook, live provider, 사람 품질 평가, merge·배포.
- raw PUBLIC body, 감사 증빙, screenshot, one-off script, 부하 로그의 Git 커밋.

## Invariants and failure semantics

- `aiInputRevision` 일치는 현재 권한, active staff, feature enablement, cancellation, deadline, required audit 또는 `contextRevision` freshness 검사를 생략하지 않는다.
- v2 payload에서 revision/policy 중 하나만 있거나 policy가 feature와 맞지 않으면 AI ingress는 fail closed하고 job을 만들지 않는다.
- Backend DB와 outbox는 같은 transaction에서 동일한 revision/policy를 commit한다. audit 실패 시 요청/outbox도 rollback하는 기존 경계를 유지한다.
- AI ingress는 event fingerprint와 job unique key로 replay를 수렴시키며, 같은 event/job에 다른 revision은 conflict다.
- source read는 bound v2 revision을 current PUBLIC projection과 비교한다. mismatch는 body/audit success를 반환하지 않고 기존 superseded 의미로 끝낸다.
- digest나 PUBLIC body를 log, metric label, trace, Langfuse metadata, Redis message에 넣지 않는다. bounded policy version만 metadata로 허용한다.
- 외부 network I/O는 Backend request/source transaction 안에서 실행하지 않는다.

## Data and privacy

- 읽기: PUBLIC comment ID, sequence, persisted author role, createdAt, body.
- 저장: body가 없는 64자 digest와 bounded input policy version. 기존 context revision과 job binding은 유지한다.
- 제외: INTERNAL/고객 profile/staff-only metadata/KB 원문/secret/원 idempotency key.
- 보존: revision metadata는 기존 request/execution metadata 보존 범위를 따르며 result TTL을 연장하지 않는다.
- export/webhook/customer API에는 노출하지 않는다.

## Threats changed

- revision spoofing: 서버가 계산하고 machine caller도 override할 수 없다.
- digest ambiguity: versioned length-delimited canonical encoding으로 구분한다.
- stale reuse: S05는 reuse를 수행하지 않고, 향후 S06도 current authorization/context/KB/policy 검증을 별도로 지불한다.
- mixed deployment: v1은 null/new fields absent, v2는 paired required로 검증해 silent downgrade/upgrade를 막는다.
- content leakage: digest와 policy version만 저장·관측하며 본문은 기존 protected source response 밖으로 확장하지 않는다.

## Acceptance scenarios

1. 같은 PUBLIC comments와 feature에서 assignee/group 변경 또는 INTERNAL comment 추가로 `ticketVersion/contextRevision`이 바뀌어도 새 job의 `aiInputRevision`은 같다.
2. PUBLIC comment body, persisted role, sequence, createdAt 또는 ID가 바뀌면 해당 feature `aiInputRevision`이 달라진다.
3. 동일 PUBLIC projection이라도 summary/triage/reply는 서로 다른 feature policy/domain으로 digest가 분리된다.
4. 신규 Backend job은 schema v2 envelope와 DB row에 동일한 revision/policy를 원자적으로 저장하고 AI DB도 정확히 보존한다.
5. legacy schema v1/public-comments-v1 envelope는 신규 필드 없이 수용되고 기존 context freshness로 실행되며 cache-eligible metadata를 얻지 않는다.
6. schema v2에서 revision 누락, policy 누락, 잘못된 64자 digest, feature-policy mismatch 또는 v1에 신규 필드 혼합은 422/contract rejection이며 job row가 없다.
7. v2 source read에서 current `aiInputRevision`이 bound 값과 다르면 PUBLIC body와 successful access audit를 반환하지 않고 superseded 처리한다.
8. context revision이 다르면 aiInputRevision이 같아도 기존 in-flight source/result freshness는 계속 차단된다.
9. logs/trace/Redis message에는 revision 원문 입력과 digest가 추가되지 않고 bounded policy version만 허용된 metadata에 남는다.

## Validation

- `python3 scripts/bundle_core_openapi.py --check`
- `cd backend && GRADLE_USER_HOME=./.gradle-user-home ./gradlew --no-daemon test --tests '*AgentAiRequestIntegrationTest*'`
- Backend migration empty/upgrade and feature digest unit/integration tests.
- `cd ai && .venv/bin/ruff check src tests scripts`
- `cd ai && .venv/bin/mypy`
- `cd ai && .venv/bin/pytest -q`
- AI PostgreSQL migration/ingress replay tests for v1/v2 paired metadata and source freshness.
- generated AI internal OpenAPI parity and committed `ai-source-api-v1.yaml` checks.
- `cd ai && .venv/bin/python scripts/evaluate_fake.py` — contract evidence only; live quality/cost claim 금지.
- `make docs-check`
- `git diff --check`
- latest PR HEAD CI.

## Compatibility and migration

- Backend V95와 AI 007은 nullable paired columns를 추가한다. 기존 rows는 null/null이며 backfill하거나 과거 hash를 재해석하지 않는다.
- 신규 Backend writer는 outbox schema v2를 emit한다. 배포 순서는 v2를 수용하는 AI ingress/migration 준비 후 Backend writer 전환이다. rollback은 신규 admission을 멈추고 v1 writer/dual reader로 복귀해 v2 job을 drain한 뒤 수행한다.
- source OpenAPI는 `public-comments-v1`을 그대로 유지하고 v2에 신규 required fields를 추가한다. legacy v1 response shape에는 신규 필드를 넣지 않는다.
- external Core Staff API와 UI payload는 additive change도 하지 않아 현재 strict decoder에 영향을 주지 않는다.
- forward fix가 기본이다. 컬럼 제거 rollback은 v2 writer/reader 중단과 v2 job drain을 확인한 뒤 constraint와 두 컬럼을 제거한다.

## Human explanation

`ticketVersion`은 티켓 전체 동시성이고 `contextRevision`은 현재 in-flight 결과의 보수적 freshness다. 둘을 cache identity로 쓰면 AI가 읽지 않는 assignee·group·INTERNAL 변경에도 비용을 다시 낸다. S05는 PUBLIC-only 입력 사실만 기능별 digest로 분리하되, 권한·감사·취소·freshness를 약화시키지 않는다. PostgreSQL과 기존 outbox를 그대로 사용하며 cache나 새 인프라는 아직 추가하지 않는다.
