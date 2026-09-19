# AI 비용 절감 S12 — PUBLIC 답변 초안 문체·길이 재작성

## Goal

상담사가 같은 티켓에서 서버가 생성하고 현재도 사용할 수 있는 PUBLIC 답변 초안의 문체·길이만 조정하고, 사실·조건·부정 의미·인용 보존이 확인되지 않으면 원 답변을 안전하게 유지한다.

## Decision and source references

- Decision IDs: D-009, D-054, D-066, D-067.
- Accepted ADR: 0049, 0050.
- Requirements: REQ-AI-001, REQ-AI-002, REQ-AI-003, REQ-AI-006.
- Plan: `docs/ai-v1/COST_REDUCTION_PLAN.md` S12 and sections 2, 3, 5, 6, 10, 11, 13, 14.
- API contracts: Core `createAgentAiJob`/`getAgentAiJob`, machine source-use authorization, AI internal job/result schemas.
- Verification gates: AI-API-001, AI-SRC-001, AI-LIFE-001, AI-COST-001, AI-REPLY-001, AI-REWRITE-001, AI-OBS-001, AI-RET-001.
- 선행 구현: S11 model routing PR #208.

## Actor and source

- Actor: active `STAFF`; source: `AGENT_WORKSPACE`; current ticket read와 AI feature allowlist가 필요하다.
- create request는 `sourceJobId`와 style options만 받는다. source는 같은 workspace/requester/ticket에 결합된 `ticket.reply_draft`여야 하며 source/rewrite chain과 임의 본문은 거부한다.
- machine source-use 요청은 fixed `INTEGRATION_CLIENT`와 rewrite job binding으로만 승인한다. Backend는 caller가 제출한 requester/ticket/source owner를 신뢰하지 않는다.
- source ciphertext 사용 전 current staff/ticket/feature/context, source binding과 required `AI_RESULT_READ` audit를 통과한다. 실패하면 source body를 복호화하거나 provider를 호출하지 않는다.

## Product and UX contract

- feature는 `ticket.reply_rewrite`다.
- 첫 catalog는 `language=ko`, `tone=calm|formal`, `length=concise|standard`다. Backend가 누락 값을 `ko/calm/standard`로 정규화한다.
- v1 rewrite는 `REUSE_OR_CREATE`만 허용한다. 상담사 편집 본문, INTERNAL composer, 영어·friendly tone, rewrite-of-rewrite, 자동 삽입은 제외한다.
- UI는 성공 결과만 별도 preview로 표시하고 명시적 PUBLIC draft 추가/교체를 재사용한다. preservation 실패·stale·UNKNOWN이면 rewrite body를 표시하지 않고 원 source reply preview와 선택 상태를 유지한다.
- loading/cancel/error/denied/stale 상태에서 source reply를 지우거나 composer draft를 덮어쓰지 않는다. keyboard/focus와 non-color status는 기존 AI panel gate를 따른다.

## In scope

- Core/internal/source OpenAPI의 feature, `sourceJobId`, normalized style options, typed rewrite result와 machine source-use capability.
- Backend additive migration, rewrite feature setting/allowlist, source binding/idempotency/input revision, current authorization와 required source-result access audit.
- AI additive migration, source-result decrypt/read boundary, no-retrieval rewrite workflow, protected marker/citation validation, distinct rewrite/validation cost calls와 encrypted typed result.
- strict schema, exact citation order/membership, current context/policy/source/citation recheck와 reusable PostgreSQL regression tests.
- 후속 UI slice를 위한 typed client contract. 실제 UI 변경은 staff-console Storybook documentation MCP가 사용 가능한 후속 PR에서 수행한다.

## Out of scope

- staff-edited PUBLIC/INTERNAL text, arbitrary prompt, translation, extra tone/length values, rewrite chain.
- ticket/KB retrieval, context memory, S11 low-cost route, provider fallback, automatic reply insertion or ticket mutation.
- production activation, paid canary, actual PUBLIC body export, human holdout scoring, merge/deploy.
- Storybook MCP가 없는 상태에서 component API를 추정하는 UI 구현.

## Invariants and failure semantics

- source job ID는 rewrite request/outbox/AI job에 immutable하게 결합되고 idempotency fingerprint에 포함된다. 다른 payload의 key reuse는 409다.
- Backend request/outbox/activity audit와 source-use required access audit는 각각 자신의 transaction에서 fail closed다. provider I/O는 Backend/ticket transaction 밖이다.
- AI는 source job이 `SUCCEEDED`, unexpired, same binding이고 authenticated decryption이 성공할 때만 원 answer/citations를 메모리에 올린다. source plaintext는 새 DB row, log, trace, Langfuse에 복제하지 않는다.
- original citation을 `S1..S8`로 안정적으로 매핑하고 rewrite output은 같은 ref 집합과 순서를 반환해야 한다. current Backend citation authorization 결과가 원본과 같아야 한다.
- deterministic protected marker 검증 뒤 `GENERATION_REWRITE_VALIDATION` verdict가 이름·정책·금액·날짜·조건·부정 의미 보존을 모두 승인해야 결과를 commit한다.
- invalid output/marker/citation/verdict, cancellation, deadline, source expiry, permission/policy/context/citation drift, budget denial 또는 delivery `UNKNOWN`은 rewritten body 없이 종료한다. source reply job과 composer는 바뀌지 않는다.
- rewrite와 validation은 각 최대 1회, 합계 2회다. validation 실패를 rewrite 재호출이나 원 reply 재생성으로 덮지 않는다.

## Data and privacy

- 읽기: source job의 암호화된 PUBLIC answer/citation, body-free current authorization/revision/policy. 전체 PUBLIC conversation과 KB body는 읽지 않는다.
- 쓰기: sourceJobId metadata, encrypted successful rewrite result, bounded preservation reason/call receipts/cost. protected spans와 validator input/output 본문은 저장하지 않는다.
- 결과 7일, execution/source link metadata 30일, unresolved UNKNOWN cost는 기존 별도 보존을 따른다.
- 원/재작성 본문, prompt, citation title/url, protected span, user identifier는 application log, metric label, OTel/Langfuse에 넣지 않는다.

## Acceptance scenarios

1. 같은 requester/ticket의 current `ticket.reply_draft` 성공 job과 기본 options는 normalized rewrite job 하나로 접수되고 exact replay는 같은 job을 반환한다.
2. 다른 requester/ticket/workspace, rewrite source, 만료/실패/미완료 source, client answer/citation field 또는 `NEW_CANDIDATE`는 provider 호출 없이 거부된다.
3. source-use authorization/audit persistence 실패는 source decrypt, rewrite call, result body를 모두 막는다.
4. rewrite worker는 source answer/citation만 사용하고 PUBLIC context/KB retrieval endpoint를 호출하지 않는다.
5. valid output은 exact original source refs, deterministic markers, preservation verdict와 current citation authorization을 통과해 encrypted `ReplyRewriteResult`를 저장한다.
6. 이름·정책·금액·날짜·조건·부정 marker 또는 citation ref가 바뀌면 validation 전후 어느 단계든 usable body 없이 `NEEDS_REVIEW`가 되고 UI가 사용할 sourceJobId는 유지된다.
7. rewrite call이 invalid이면 validation을 호출하지 않는다. validation `UNKNOWN`/invalid/negative는 rewrite를 재호출하지 않고 각 known/unknown receipt를 보존한다.
8. 각 실제 호출 전 cancellation/deadline/policy/context/source freshness와 잔여 budget을 확인하며 병렬 요청이 workspace/actor/job cap을 넘지 않는다.
9. result GET은 current feature/ticket/context/citation/source expiry와 required result-read audit를 다시 확인하고 성공 결과에만 `canInsert=true`를 반환한다.
10. logs/metrics/traces/Langfuse와 audit detail에 answer, prompt, protected span, KB body가 없고 metric label에는 job/requester/ticket ID가 없다.

## Validation

- Backend focused source binding/idempotency/authorization/audit failure/OpenAPI tests and V98→V99 migration upgrade probe.
- `cd backend && GRADLE_USER_HOME=/private/tmp/deskseed-gradle-s12 ./gradlew test`
- `cd ai && .venv/bin/ruff check src tests scripts`
- `cd ai && .venv/bin/mypy`
- `cd ai && .venv/bin/pytest -q`
- `cd ai && .venv/bin/python scripts/export_openapi.py` and internal OpenAPI parity.
- `cd ai && .venv/bin/python scripts/evaluate_fake.py` — contract/failure regression only, not quality evidence.
- PostgreSQL tests for encrypted source binding, exact citation/marker/verdict failure, budget/UNKNOWN/fencing and no-retrieval behavior.
- `make docs-check`, `git diff --check`, latest PR HEAD CI.
- UI 후속은 Storybook `list-all-documentation`, current story instructions, focused/full `run-story-tests`, changed-story preview와 browser E2E가 필요하다. 현재 MCP unavailable이면 Pending으로 보고한다.

## Compatibility and migration

- feature enum과 conditional `sourceJobId`, result union 추가는 additive처럼 보여도 strict Backend/AI/frontend decoder에는 coordinated breaking change다.
- deploy order는 contract reader → Backend V99/source authorization writer(default rewrite off) → AI 015 reader/workflow → UI → 별도 평가 후 flag activation이다.
- Backend와 AI는 각 additive migration을 사용하고 기존 checksum SQL을 수정하지 않는다. backfill은 없다.
- rollback은 rewrite feature off → 신규 접수/다음 call 차단 → 진행 job drain/cancel 순서다. 기존 reply draft, settled receipts, UNKNOWN 조사, retention은 유지한다.

## Human explanation

문체를 바꾸려고 티켓과 KB를 다시 검색하면 비용뿐 아니라 서로 다른 근거로 새 답변이 생성될 위험이 있다. 그래서 현재 승인된 서버 결과 하나만 source로 삼고 citation map을 그대로 유지한다. 모델의 자신감은 보존 증거가 아니므로 deterministic guard와 별도 verdict가 모두 통과해야 하며, 조금이라도 불명확하면 상담사가 이미 보던 원 답변으로 돌아간다. 이 경계 때문에 임의로 편집한 작성기 문장은 이번 기능에 넣지 않는다.
