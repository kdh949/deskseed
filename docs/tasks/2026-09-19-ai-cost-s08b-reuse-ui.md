# AI 비용 절감 S08b — 최근 결과 재사용과 새 후보 UI

## Goal

상담사가 AI 기능을 요청할 때 기본 동작은 현재 입력과 일치하는 완료 결과 또는 진행 실행을 안전하게 재사용하고, 별도 비용이 들 수 있는 다른 결과는 명시적인 새 후보 동작으로만 요청하도록 Staff Console을 S08a 서버 계약에 연결한다.

## Decision and source references

- Decision IDs: D-030, D-031, D-032, D-053, D-054, D-061, D-066.
- Accepted ADRs: 0025, 0039, 0044, 0049.
- Requirements: REQ-AI-001, REQ-AI-005, REQ-UI-003, REQ-UI-005, REQ-UI-007.
- Plan: `docs/ai-v1/COST_REDUCTION_PLAN.md` S08b and sections 8.1~8.4, 11, 12.
- API operations: `createAgentAiJob`, `listAgentAiJobs`, `getAgentAiJob`, `cancelAgentAiJob`.
- Verification gates: AI-API-001, AI-OPS-001, AI-RET-001, UI-002~006.
- Prerequisite: S08a reuse/new-candidate contract and runtime.

## Actor, route and design source

- Actor: active `STAFF`; source: `AGENT_WORKSPACE`; route: `/agent/tickets/:ticketNumber` AGT-004 AI context panel.
- Existing staff session, expected-actor guard, CSRF, current ticket read authorization, required AI activity/result-read audit and feature allowlist remain authoritative.
- Before component, story or rendered UI work, use the staff-console project-local Storybook MCP documentation inventory, current story instructions and exact component documentation. After changes run focused and full story tests, changed-story discovery and preview.

## Product and UX contract

- The primary action always sends `generationMode=REUSE_OR_CREATE`. With no usable result it is labelled as result check/generation; with a prior result it lets the server resolve the current exact input again.
- `NEW_CANDIDATE` is a separate `다른 초안 생성` action. It is offered only after the feature has a completed usable result, so the first normal request does not consume the two-candidate allowance.
- The UI explains that a different candidate is limited to two requests for the same input in 24 hours. It does not derive remaining quota from `candidateSequence`, because refunds and the rolling window are server-owned.
- `CACHE_HIT`, `COALESCED` and `GENERATED` are rendered as bounded human-readable route states. The UI does not expose cache keys, model aliases, provider identities, shared-execution IDs, counterfactual savings or internal cost values.
- An active coalesced job is announced as waiting for the same-input execution. A completed cache/coalesced job remains a normal result and uses the same freshness, authorization and insertion checks as a generated job.
- A `429` new-candidate failure displays the bounded `Retry-After` guidance without automatic retry or countdown. It does not claim whether candidate quota, admission control or budget was the sole cause.
- Loading, empty, error, denied, stale/conflict, needs-review, budget/rate limit, shared-wait, cancelled and expired states remain distinguishable by text and not color alone.
- Existing legacy receipts without generation metadata remain readable. The next primary click uses explicit `REUSE_OR_CREATE`; the UI never silently reinterprets a legacy job as a cache hit.

## Idempotency and failure semantics

- Every explicit click creates one browser-owned idempotency key together with the exact feature, generation mode and expected ticket version.
- Network, `5xx` or malformed-success ambiguity preserves that exact create command in memory and offers an explicit same-request retry using the same key and payload. It does not automatically create another job.
- Definite `4xx`, including `409` and `429`, discards the pending key. A later user action is a new logical command with a new key.
- Successful create clears pending recovery and replaces only that feature's displayed job. Cancel and poll behavior remains per logical job.
- Ticket or authenticated actor transition discards pending browser recovery before another resource can use it. No idempotency key, job/candidate identity or AI result is written to localStorage, sessionStorage, URL or analytics.

## Result and insertion contract

- The strict decoder accepts only the frozen generation-mode/reuse-kind enums, positive candidate sequence and canonical server UUID candidate identity; malformed combinations fail the whole response.
- Reply insertion still performs `includeResult=true`, checks current ticket/version/composer/draft, requires `SUCCEEDED`, non-stale, `canInsert=true` and a server-owned candidate ID, then uses the S03b PUBLIC lineage path.
- Summary and triage remain display-only. Reuse metadata never causes automatic field mutation, draft insertion or send.
- INTERNAL drafts and customer surfaces are unchanged.

## In scope

- Staff AI API request typing, strict receipt decoding and explicit generation-mode transport.
- Primary reuse-or-create and secondary new-candidate actions for the existing summary, triage and reply features.
- Bounded reuse/shared/generated status copy, candidate-limit copy and `Retry-After` error copy.
- Exact ambiguous-create recovery in React memory.
- Unit, story, workspace and browser regression coverage with synthetic data.

## Out of scope

- S12 reply-rewrite controls, admin AI settings, analytics dashboard or quota management screen.
- Changing the S08a 24-hour/two-candidate policy, exposing remaining quota, comparing candidates automatically or selecting a best result.
- OpenAPI, Backend, AI migration or server behavior changes; live provider calls, current-server content extraction, human quality scoring, savings claims, merge or deployment.

## Acceptance scenarios

1. Empty feature primary action sends one `REUSE_OR_CREATE` request and renders the returned job.
2. A matching completed result reports recent-result reuse; an active coalesced receipt reports same-input wait without exposing implementation identifiers.
3. After a usable result, `다른 초안 생성` sends `NEW_CANDIDATE`; repeated browser clicks while in flight create only one request.
4. A `429` on new candidate preserves the current result, shows bounded retry guidance and does not auto-retry.
5. An ambiguous create failure preserves exact mode/version/key; explicit retry sends the same payload and idempotency key. A definite failure or success rotates it for the next action.
6. Legacy result metadata remains renderable, while the next primary request explicitly opts into reuse.
7. Cache/coalesced reply insertion still fails closed on stale, denied, missing candidate, draft race or INTERNAL composer and succeeds only through S03b lineage.
8. Ticket/actor transition clears pending create recovery and no AI command identity is browser-persisted.

## Validation

- staff-console Storybook documentation inventory, story instructions and exact AI panel/button/notice documentation before implementation.
- focused API and `AiAssistantPanel` unit tests for strict decode, both modes, exact retry, 429 and reuse states.
- focused `run-story-tests` for AI panel and integrated workspace, then full staff Storybook suite.
- `get-changed-stories` and `preview-stories` for reuse, shared-wait and candidate-limit states.
- frontend typecheck, lint, format, staff tests, build, design-system boundary, contract check and relevant browser E2E.
- `make docs-check`, `git diff --check`, latest PR HEAD CI.
- Fake/synthetic results prove UI and contract behavior only; provider quality and realized savings remain `NOT_ESTABLISHED`.

## Compatibility and rollback

- S08a fields are already optional in the Core contract. This UI deploys only after the server reader/writer and AI intent path.
- Legacy receipts stay readable, but all newly initiated normal actions send explicit mode and therefore activate exact reuse/shared behavior.
- Rollback to the prior UI returns to mode omission and new generation semantics without changing stored jobs, candidate quota rows, cost ledgers or audits.
- No migration, backfill, customer-portal bundle or shared visual-system change is included.

## Human explanation

기본 버튼은 같은 입력으로 이미 만든 안전한 결과가 있으면 다시 쓰고, 같은 실행이 진행 중이면 그 결과를 기다린다. 비용이 들 수 있는 다른 결과는 상담사가 별도 버튼을 눌렀을 때만 만들며 서버가 24시간 두 번으로 제한한다. 화면은 재사용 경로를 알려 주지만 절감액이나 품질을 추정하지 않고, 답변을 작성기에 넣을 때는 기존과 똑같이 현재 권한과 최신성을 다시 확인한다.
