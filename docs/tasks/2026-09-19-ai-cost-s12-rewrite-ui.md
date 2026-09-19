# AI 비용 절감 S12 UI — PUBLIC 답변 초안 문체·길이 재작성

## Goal

상담사가 같은 티켓에서 현재도 삽입 가능한 서버 생성 PUBLIC 답변 초안을 원본으로 선택해 닫힌 한국어 문체·길이 옵션으로 재작성하고, 검증된 성공 결과만 별도 preview에서 확인한 뒤 기존 PUBLIC 초안 추가·교체 절차로 명시적으로 사용할 수 있게 한다.

## Decision and source references

- Decision IDs: D-009, D-030, D-031, D-032, D-054, D-066, D-067.
- Accepted ADRs: 0025, 0039, 0044, 0049, 0050.
- Requirements: REQ-AI-001, REQ-AI-003, REQ-AI-006, REQ-UI-003, REQ-UI-005, REQ-UI-007.
- Plan: `docs/ai-v1/COST_REDUCTION_PLAN.md` S12 and sections 2, 3, 5, 6, 10, 11, 13, 14.
- Server slice: `docs/tasks/2026-09-19-ai-cost-s12-public-draft-rewrite.md` and PRs #209/#210.
- API operations: `createAgentAiJob`, `listAgentAiJobs`, `getAgentAiJob`, `cancelAgentAiJob`.
- Verification gates: AI-API-001, AI-COST-001, AI-REPLY-001, AI-REWRITE-001, AI-RET-001, UI-002~006.

## Actor, route and design source

- Actor: active `STAFF`; source: `AGENT_WORKSPACE`; route: `/agent/tickets/:ticketNumber` AGT-004 AI context panel.
- Existing staff session, expected-actor guard, CSRF, current ticket authorization, feature allowlist and required AI activity/result-read audit remain authoritative.
- Before component, story or rendered UI work, use the staff-console project-local Storybook MCP documentation inventory, current story instructions and exact component documentation. After changes run focused and full story tests, changed-story discovery and preview.

## Source and option contract

- Rewrite is offered only on a `ticket.reply_draft` job that is `SUCCEEDED`, non-stale, `canInsert=true`, has a server-owned candidate ID and still has a decoded result. The browser sends only that job's `jobId` as `sourceJobId`; it never sends the answer or citations back as source fields.
- Options are closed controls: language is fixed to Korean, tone is `calm | formal`, and length is `concise | standard`. Default selection is `calm/standard` and labels explain the choice without promising quality or cost reduction.
- The create command always sends `feature=ticket.reply_rewrite`, `generationMode=REUSE_OR_CREATE`, current expected ticket version, selected options and a browser-owned idempotency key. `NEW_CANDIDATE`, arbitrary prompt, free-form tone, translation and rewrite-of-rewrite are unavailable.
- Changing an option after a completed rewrite creates a new logical command only after the user explicitly requests it. Option changes alone do not call the server or replace either preview.

## Result and insertion contract

- A rewrite is a separate job and separate preview. The source reply card remains visible and unchanged while the rewrite is queued, running, cancelled, failed, denied, stale, expired, `UNKNOWN`/needs-review or malformed.
- Only a strict `ticket.reply_rewrite` result with Korean language, frozen tone/length enum, bounded answer and valid non-empty citations is rendered as rewritten content.
- The rewrite preview identifies the selected tone and length in text. It does not display preservation verdict internals, protected spans, provider/model identity, prompt, cost, source ciphertext or audit metadata.
- Explicit rewrite insertion reuses the existing S03b PUBLIC add/replace choice, current ticket/version/composer/draft recheck and candidate lineage. It never auto-inserts, auto-sends or mutates an INTERNAL draft.
- If rewrite insertion fails current authorization, source/citation freshness, candidate, draft race or composer checks, both previews remain available and the user's existing draft is unchanged.

## Idempotency and failure semantics

- One explicit rewrite click owns one exact source job, ticket version, normalized options and idempotency key.
- Network, `5xx` or malformed-success ambiguity preserves that command in React memory and offers only an explicit same-request retry with the same key and payload. It does not silently start another rewrite.
- Definite `4xx` clears pending recovery. A `409` or stale response asks the user to refresh the source; a bounded `429` reports server retry guidance without automatic retry or countdown.
- Ticket or authenticated actor transition clears pending rewrite recovery and local option/result state before another resource can reuse it. No source ID, idempotency key, answer or citation is written to localStorage, sessionStorage, URL or analytics.
- Polling and cancellation use the existing per-job lifecycle. Background refresh never emits semantic `TICKET_VIEWED`.

## In scope

- Strict frontend feature, request, receipt and rewrite-result decoding for the frozen Core contract.
- Source-bound rewrite controls on usable PUBLIC reply results, closed tone/length controls and separate source/rewrite previews.
- Loading, waiting, success, needs-review, error, denied, stale, cancelled, expired, retry and insertion-choice states with non-color text.
- Existing PUBLIC draft add/replace and S03b candidate attribution integration.
- Unit, Storybook, integrated workspace and browser regressions using synthetic content.

## Out of scope

- Staff-edited composer text, INTERNAL notes, English/translation, arbitrary instructions, extra styles, rewrite chain, automatic comparison or best-result selection.
- Core OpenAPI, Backend, AI, migration or server behavior changes; retrieval, model routing, prompt-cache or cost-ledger changes.
- Live provider calls, current-server content export, human preservation scoring, realized savings claims, merge or deployment.

## Acceptance scenarios

1. A usable original reply exposes rewrite controls with `calm/standard` selected; summary, triage, rewrite results and unusable reply jobs do not become sources.
2. Explicit submit sends one `ticket.reply_rewrite` request with the source job ID, current version, `REUSE_OR_CREATE`, selected closed options and one idempotency key; option changes alone send nothing.
3. While rewrite is active, cancelled, failed, needs-review or stale, the original reply preview and existing composer draft remain unchanged.
4. A valid successful rewrite renders a distinct preview with its normalized tone/length and exact citations. Malformed enums, empty citations, missing source binding or mismatched feature fail the whole receipt.
5. Explicit add/replace re-fetches the rewrite with result, rechecks current state and uses the existing PUBLIC candidate lineage path. INTERNAL mode, stale result, missing candidate or draft race fails closed.
6. Ambiguous create retry reuses the exact source/options/version/key. Definite failure or success rotates the key for the next logical command.
7. Ticket/actor transition clears source selection and pending recovery, and no protected content or command identity is browser-persisted.
8. Keyboard and focus operation, loading, empty, denied and failure states remain available without color-only meaning.

## Validation

- Staff Storybook documentation inventory, current story instructions and exact AI panel/control/notice documentation before implementation.
- Focused API and `AiAssistantPanel` unit tests for strict decode, source/options transport, separate preview, exact retry, fallback and insertion guard.
- Focused `run-story-tests` for rewrite ready/running/success/needs-review and integrated workspace, then full staff Storybook suite.
- `get-changed-stories`, component coverage discovery and `preview-stories` for representative rewrite states.
- Frontend format, lint, typecheck, staff/full tests, build, design-system boundary, contract check and relevant browser E2E.
- `make docs-check`, `git diff --check`, latest PR HEAD CI.
- Synthetic results prove UI and contract behavior only; provider preservation quality and realized savings remain `NOT_ESTABLISHED`.

## Compatibility and rollback

- The server reader/writer, source authorization and AI workflow are already frozen and deployed before this UI in the stack. The frontend decoder adds the new feature/result union while continuing to read legacy summary, triage and reply jobs.
- Rollback removes rewrite controls and preview but does not alter original reply jobs, rewrite jobs, source links, receipts, cost rows or audits.
- No migration, backfill, customer-portal bundle or shared design-system API is included. If a missing reusable UI capability is discovered, it requires documented staff design-system API and Storybook coverage in the same code slice.

## Human explanation

재작성은 작성기 문장을 모델에 다시 보내는 기능이 아니라, 서버가 이미 검증해 보관한 PUBLIC 답변 결과 하나의 표현만 바꾸는 기능이다. 원 답변은 화면에 그대로 남고, 사실·부정 의미·인용 보존을 서버가 확인한 별도 결과만 상담사가 직접 추가하거나 교체할 수 있다. 검증이 불명확하거나 현재 권한·최신성이 달라지면 새 문장을 쓰지 않고 원 답변으로 돌아간다.
