# AI 비용 절감 S11 — 평가 승인 cohort 저가 모델 라우팅

## Goal

품질·비용 평가가 승인된 짧고 단순한 공개 도움말 답변 cohort와 안정적인 상담사 rollout bucket에서만 Luna를 먼저 사용하고, 알려진 schema·citation 검증 실패에 한해 Terra로 한 번 승격하여 품질 경계와 두 호출의 총비용을 함께 보존한다.

## Decision and source references

- Decision IDs: D-009, D-054, D-066.
- Accepted ADR: 0049. LiteLLM provider fallback이 아니라 server-owned explicit route이며 hidden retry는 계속 꺼 둔다.
- Requirements: REQ-AI-003, REQ-AI-005.
- Plan: `docs/ai-v1/COST_REDUCTION_PLAN.md` S11 and sections 2, 3, 5, 6, 10, 13, 14.
- API contracts: `getAdminAiSettings`, `updateAdminAiSettings`, `readAiPolicy`.
- Verification gates: AI-API-001, AI-LIFE-001, AI-COST-001, AI-REPLY-001, AI-OBS-001, AI-OPS-001.
- 선행 구현: S10 source-backed context memory PR #206.

## Actor, policy and audit boundary

- ADMIN만 기존 CSRF, expected actor, expected settings version으로 routing policy를 변경한다. settings row 변경과 `AI_SETTINGS_UPDATED` Admin/Security audit는 함께 commit/rollback한다.
- Backend가 routing mode, approved cohort set, rollout percentage, evaluation approval version을 소유하고 internal AI policy에 전달한다. AI worker나 client가 정책을 선택하지 않는다.
- `STANDARD_ONLY`가 migration/default/rollback 상태다. 이 상태에서는 모든 reply generation이 기존 Terra 경로를 사용한다.
- `EVALUATED_COHORT`는 `reply-single-public-article-short-v1` cohort, `10 | 50 | 100` rollout, bounded `evaluationApprovalVersion`을 모두 요구한다. 비어 있거나 unknown cohort, 임의 percentage, approval 없는 활성화는 400이고 mutation/audit success가 없다.
- 최초 10% activation approval은 동일 holdout에서 Luna route와 Terra 기준선의 품질·비용 gate를 통과했다는 human-owned 외부 승인이다. 50%/100% 확대 approval은 직전 단계 최소 100개 완료 요청과 7일 관찰 중 더 늦은 시점의 검토를 의미한다. 현재 paid/human evidence가 없으므로 코드와 계약은 구현하되 production settings는 `STANDARD_ONLY`로 유지한다.

## Route cohort v1

`reply-single-public-article-short-v1` 후보는 아래 조건을 모두 만족해야 한다. 조건은 body를 log/metric에 복사하지 않는 deterministic local 판정이며 모델 confidence를 사용하지 않는다.

- reply feature이고 approved current PUBLIC knowledge가 정확히 한 chunk, 한 article, 한 revision이다.
- latest CUSTOMER raw query는 reviewed tokenizer 256 tokens 이하, protected recent PUBLIC conversation은 512 tokens 이하, approved knowledge input은 768 tokens 이하이다.
- 같은 실행에서 S10 context memory build/update call이 발생하지 않았다. 기존 exact-valid memory를 read-only로 재사용한 경우는 별도 평가 전까지 후보에서 제외한다.
- 질문 또는 knowledge에 versioned risk marker가 없다. v1은 account/security/권한 변경, billing/payment/refund/금액·통화, 날짜·기한, 비교 수치, 조건·예외·정책 conflict marker를 보수적으로 제외한다. marker set과 Unicode/대소문자 정규화는 `route-risk-markers-v1`로 고정한다.
- no-evidence, 여러 source, retrieval/source authorization drift, source conflict 또는 현재 문제 추출 실패는 후보가 아니다.
- 이 deterministic 조건은 품질 증명이 아니다. evaluation approval이 없는 동일 구조 요청은 Terra로 간다.

## Rollout selection

- candidate cohort가 approved set에 있고 mode가 active일 때만 rollout bucket을 계산한다.
- bucket은 server secret HMAC의 `workspaceKey + requesterStaffId + cohortVersion`에서 0~99로 계산한다. secret, digest, requester ID는 log/metric/trace에 넣지 않는다. caller가 seed/bucket을 제출하지 않는다.
- bucket이 percentage 미만이면 Luna route, 그 외는 Terra다. 같은 actor/cohort는 policy percentage가 커질 때 포함 관계를 유지한다.
- routing secret이 없거나 짧음, policy field 불일치, tokenizer/pricing 부재는 fail closed Terra다. 권한·정책 장애는 Terra fallback이 아니라 기존 typed failure다.
- route policy version, cohort version, marker version, percentage, both model aliases를 reply result-cache/shared-execution key에 포함한다. cache hit를 위해 embedding/retrieval을 반복하지 않는다.

## Calls, escalation and cost

- normal route는 `GENERATION` Terra 1회다. low-cost route는 `GENERATION_LOW_COST` Luna 1회다.
- Luna의 response/receipt가 알려진 상태에서 strict JSON schema, request-local source ref 또는 canonical citation membership 검증이 실패한 경우에만 `GENERATION_ESCALATION` Terra를 최대 1회 호출한다.
- no evidence, cancellation, deadline, policy/source revision change, permission loss, budget failure, provider delivery UNKNOWN, receipt unavailable/inconsistent는 승격 이유가 아니다. UNKNOWN을 Terra 호출로 덮지 않는다.
- 승격 직전 current context revision, policy version/mode/cohort/aliases, cancellation/deadline과 남은 workspace/actor/job budget을 다시 확인한다.
- 각 call은 다른 durable operation key/stage를 가지고 upper bound를 먼저 예약한다. Luna 비용은 Terra 승격 후에도 보존하고 job cap은 두 generation call과 query embedding의 합계에 적용한다.
- S10 memory build/update가 이미 한 generation slot을 사용한 request는 low-cost route/승격을 시작하지 않고 Terra 1회로 끝낸다. interactive generation 최대 2회는 유지한다.
- cohort 평균 generation cost는 `C_luna + escalationRate × C_terra`이며 같은 cohort Terra 기준보다 엄격히 작아야 한다. UNKNOWN/invalid/discarded 결과와 escalation 비용을 분자에서 제외하지 않는다.

## Result and observability

- external `ReplyDraftResult`와 citation contract는 바꾸지 않는다. 모델명, route cohort, rollout bucket, escalation reason을 상담사/customer response에 추가하지 않는다.
- AI job metadata에는 bounded route decision (`STANDARD | LOW_COST | ESCALATED`), cohort/version, requested route alias, escalation reason과 policy version을 body 없이 저장한다.
- call ledger/trace는 `GENERATION`, `GENERATION_LOW_COST`, `GENERATION_ESCALATION`을 구분하고 실제 model/usage/cost를 보존한다. route/bucket outcome은 bounded enum만 사용하고 ID/body는 metric label에 넣지 않는다.
- fake provider 통과, HTTP 200, Luna schema valid만으로 품질 gate를 통과했다고 보고하지 않는다. 사람 평가와 실제 비용 receipt가 없으면 activation evidence는 `NOT_ESTABLISHED`다.

## In scope

- Core OpenAPI의 typed routing settings와 internal AI source OpenAPI policy extension.
- Backend additive migration, admin settings read/update/validation/audit, source policy projection and contract regression.
- AI strict policy decoder, HMAC rollout selector, deterministic cohort/risk marker classifier.
- distinct cost/call stages, Luna generation and bounded Terra escalation with freshness/budget recheck.
- route-aware cache/shared key, body-free job/trace metadata and PostgreSQL/Redis integration tests.
- fake Luna success, known invalid output/citation escalation, UNKNOWN/no-evidence/no-budget/no-approval no-escalation fixtures.

## Out of scope

- production routing activation, paid canary, 실제 PUBLIC 원문 export, human holdout scoring. 승인된 예산·평가가 생길 때까지 settings는 `STANDARD_ONLY`다.
- model confidence router, per-request LLM classifier, provider-managed fallback/retry, more than one escalation.
- arbitrary model aliases, dynamic provider list, multivariate experimentation service.
- S12 rewrite, S13 prompt cache, S14 embedding reuse/batch.
- Admin/Agent UI, merge·deploy, evidence screenshots/logs/one-off scripts commit.

## Invariants and failure semantics

- current authorization/PUBLIC source/KB validation precede route generation. Route never broadens source or mutation capability.
- Backend settings mutation and Admin/Security audit are atomic. AI provider/network calls remain outside Backend and AI DB transactions.
- stable route operation keys and shared execution ensure one leader owns Luna/Terra calls; followers do not duplicate cost.
- known invalid Luna response can escalate once; UNKNOWN Luna response cannot. Both known receipts remain immutable after result failure.
- policy changes during execution fail closed before next call/result commit. A lower/off policy immediately prevents new low-cost calls; settlement/retention continues.
- final citation authorization and context/source/policy freshness checks apply equally to Luna, escalated Terra and cached/coalesced results.

## Acceptance scenarios

1. migration/default `STANDARD_ONLY` policy and any unapproved/unknown cohort use Terra only.
2. valid approved cohort + stable actor bucket inside 10% uses Luna once; the same actor remains selected at 50%/100%, while an outside bucket remains Terra until included.
3. multiple chunks/articles, long context/knowledge, context memory use, account/billing/security/date/amount/condition marker or no evidence use Terra or typed no-evidence without Luna.
4. Luna valid schema with authorized source refs succeeds with one generation call and stores `LOW_COST` metadata.
5. Luna known invalid JSON, duplicate/unknown source ref or citation membership failure preserves its receipt and calls Terra exactly once as `GENERATION_ESCALATION`.
6. Luna UNKNOWN, timeout with ambiguous delivery, cancellation, policy/source drift or insufficient remaining budget does not call Terra and returns the existing typed failure/review state.
7. escalation recheck sees routing disabled, alias/evaluation version changed or context superseded and stops before Terra.
8. shared 20-request same-input execution performs one route and at most two generation calls total; consumer ciphertext/audit/cancel remain independent.
9. route settings update with stale expectedVersion or injected audit failure changes neither settings nor audit success. rollback to `STANDARD_ONLY` is immediate and allowed.
10. call/job/trace metadata contains bounded route fields and costs but no PUBLIC body, KB body, prompt, bucket digest, requester ID or secret.

## Validation

- Backend focused admin policy/source OpenAPI integration tests and migration upgrade probe.
- `cd backend && GRADLE_USER_HOME=/private/tmp/deskseed-gradle-s11 ./gradlew test`
- `cd ai && .venv/bin/ruff check src tests scripts`
- `cd ai && .venv/bin/mypy`
- `cd ai && .venv/bin/pytest -q`
- `cd ai && .venv/bin/python scripts/export_openapi.py` and internal OpenAPI parity.
- `cd ai && .venv/bin/python scripts/evaluate_fake.py` — contract/failure regression only.
- deterministic bucket distribution/inclusion fixture, route/cost ledger/UNKNOWN/escalation/shared-execution PostgreSQL tests.
- `make docs-check`, `git diff --check`, latest PR HEAD CI.

## Compatibility and migration

- Core settings request/response and internal AI policy are additive fields but strict clients/decoders require coordinated contract-first deployment.
- Backend migration adds default standard-only route fields. AI migration adds route metadata and distinct generation stage constraints without rewriting prior jobs/calls.
- deploy order is contracts/migrations → Backend standard-only policy → AI router code → fake/offline evaluation → separately approved production activation. The code PR does not activate routing.
- rollback sets `STANDARD_ONLY` first, drains workers, then rolls back application if needed. Additive columns/history and settled Luna/Terra receipts are preserved.

## Human explanation

Luna가 싸다는 이유만으로 모든 답변을 보내면 복합 정책과 조건에서 품질 비용이 더 커질 수 있다. 따라서 구조적으로 단순한 한 공개 문서·짧은 문의 cohort라도 사람 평가가 승인된 버전과 안정적인 rollout bucket이 함께 있을 때만 Luna를 호출한다. 알려진 출력 검증 실패만 Terra로 한 번 승격하고, 도달 여부가 불명인 호출은 재시도하지 않는다. 현재는 paid/human 기준선이 없어 기능과 검증 경계만 구현하고 운영 기본값은 Terra-only로 남긴다.
