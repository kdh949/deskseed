# AI 비용 절감 S03b — PUBLIC 답변 lineage와 전송 UI

## Goal

상담사가 AI 답변을 PUBLIC 작성기에 삽입·수정·전송하는 동안 candidate lineage를 안전하게 유지하고, 최종 댓글 command에 서버 검증 가능한 attribution 상태를 함께 보낸다.

## Decision and source references

- Decision IDs: D-030, D-031, D-032, D-053, D-061, D-066, D-070.
- Accepted ADRs: 0019, 0020, 0021, 0039, 0044, 0049, 0053.
- Requirements: REQ-AI-001, REQ-AI-005, REQ-UI-003, REQ-UI-005, REQ-UI-007.
- API operations: `getAgentAiJob`, `updateAgentTicket`.
- Verification gates: UI-002~006, AI-API-001, AI-USE-001.
- Prerequisite: S03b sent-attribution server contract/runtime.

## Actor, route and design source

- Actor: active STAFF on `/agent/tickets/:ticketNumber`.
- Surface: staff-console Agent Workspace, PUBLIC/INTERNAL composer and AGT-004 AI panel.
- Existing ticket read/write permission, staff session, expected actor, CSRF, expectedVersion and stable `clientCommandId` remain authoritative.
- Before any component/story/rendered UI change, use the staff-console project-local Storybook MCP: documentation inventory, current story instructions, exact component documentation, focused tests, changed-story preview and URLs.

## Product and UX contract

- AI answer insertion stores job/candidate/original-answer lineage only in the PUBLIC draft state. It never enters INTERNAL draft state or browser persistence.
- PUBLIC and INTERNAL drafts remain independent. Switching tabs preserves each draft but removes AI lineage from content explicitly moved or pasted into INTERNAL.
- replacing the whole PUBLIC draft with a new AI candidate replaces lineage; inserting candidates into a non-empty AI-derived draft produces bounded multi-source lineage; full deletion clears lineage.
- manual typing without AI lineage sends `NO_AI_LINEAGE`. A draft that previously had lineage but can no longer prove it sends `LINEAGE_LOST`; it never guesses or reports sent for an old candidate.
- successful send clears the submitted draft and lineage. ambiguous network failure preserves exact command ID, body and attribution payload for one explicit retry. confirmed conflict refreshes server state while preserving editable fields and lineage until the user resolves it.
- inserted feedback remains an interaction signal. UI copy must not describe insertion as sent, accepted, correct or cost saving.

## States and accessibility

- loading: AI result or ticket command progress disables only the relevant action and announces status.
- empty: no AI result and manual PUBLIC composer remain usable.
- error: AI result/attribution preparation error does not erase manual draft; final comment server error retains exact retry identity.
- denied: current ticket/result authorization denial removes candidate use action without exposing resource existence.
- stale/conflict: stale AI result cannot be newly inserted; already edited manual text remains sendable as unattributed. ticket version conflict preserves draft and lineage for explicit user resolution.
- keyboard/focus: insert/replace and send/retry are reachable and focus returns to the PUBLIC composer or error summary according to documented component contract. State and attribution are not color-only.

## In scope

- strict API decoding of candidate ID and optional attribution request schema.
- PUBLIC draft lineage model, insert/replace/edit/delete/tab/ticket/actor transition behavior.
- exact retry payload integration in `useTicketEditor` and production workspace.
- interaction/unit/story/browser tests for single source, multi-source, lineage lost, INTERNAL zero, success clear, ambiguous retry and conflict preservation.

## Out of scope

- S08b recent-result/new-candidate controls and generation-limit UI.
- analytics dashboard, content accuracy judgment, live provider call or savings claim.
- storing AI answer/lineage in localStorage, sessionStorage, URL, telemetry, screenshots or fixtures containing real customer text.

## Invariants and failure semantics

- lineage is a property of the PUBLIC draft, not the AI panel or ticket globally.
- switching ticket or authenticated actor clears candidate lineage before another resource can consume it.
- exact command retry reuses the exact body, ordered sources, original candidate text and `clientCommandId`; editing any of them creates a new command identity.
- attribution state never changes comment visibility, permission, TicketAudit or conflict rules.
- if the UI cannot construct valid lineage, it sends explicit lost/no-lineage state or omits for legacy compatibility; it does not fabricate candidate IDs.

## Data and privacy

- original AI answer remains only in React draft memory until send/clear/navigation. It is sent to Backend solely for binding verification/edit calculation.
- no answer, comment, candidate/job ID or edit diff is sent to frontend analytics, ordinary log, Storybook real fixture or screenshot.
- stories use synthetic PUBLIC content and no Zendesk assets.

## Acceptance scenarios

1. Insert only and cancel/navigation yields no sent event.
2. Insert, edit and successful PUBLIC send submits one lineage source and clears draft/lineage.
3. timeout after commit preserves exact payload/key; explicit retry produces one server sent record.
4. PUBLIC↔INTERNAL switching preserves separate text but INTERNAL send has no attribution.
5. replacing with another candidate replaces lineage; combining two candidates submits ordered multi-source state.
6. deleting all AI-derived content clears lineage; untrackable paste/large replacement becomes `LINEAGE_LOST`.
7. actor or ticket switch clears lineage and cannot send another resource's candidate.
8. stale/denied result remains non-insertable while manual PUBLIC reply remains available.

## Validation

- staff-console MCP `list-all-documentation`, `get-storybook-story-instructions` and exact component docs before implementation.
- focused unit tests and `run-story-tests` for affected workspace/AI stories; full story suite if impact is broad.
- `get-changed-stories` and `preview-stories` URLs for relevant states.
- frontend typecheck, staff tests, build, design-system boundary check and browser E2E.
- latest PR HEAD CI. If the project-local MCP is unavailable, do not implement by guessed component contracts or claim Storybook verification.

## Compatibility and rollback

- Backend field is optional; deployed old UI remains valid but uninstrumented.
- UI deploy follows server support. Rollback removes attribution payload while preserving ordinary PUBLIC command behavior.
- no migration, generated client shared across apps or customer-portal change in this slice.

## Human explanation

AI 패널의 버튼 클릭이 아니라 PUBLIC 작성기의 실제 command 성공이 사용 지표다. 그래서 lineage는 초안과 함께 움직이고 재시도 payload에도 포함되지만, INTERNAL 전환·다른 티켓·다른 actor에는 따라가지 않는다. 추적이 끊기면 비용 0으로 추정하지 않고 명시적으로 미귀속 처리한다.
