# FE-P0-AGENT-WRITE task brief

## Goal

상담사가 Deskseed production workspace `/agent/tickets/:ticketNumber`에서 서버가 허용한 실제 티켓만 읽고, 한 atomic command로 상태·우선순위·그룹·담당자·PUBLIC 답변 또는 INTERNAL 메모를 저장하며, draft·모호한 재시도·409 필드별 복구를 안전하게 수행하게 한다.

## Decision and source references

- Decision IDs: `D-003`, `D-006`, `D-018`, `D-040`, `D-046`, `D-053`
- Accepted ADRs: `0039`
- Requirements: `REQ-TKT-007`, `REQ-TKT-010`, `REQ-TKT-011`, `REQ-TKT-013`, `REQ-TKT-014`, `REQ-TKT-015`, `REQ-CHILD-002`, `REQ-AUD-001`, `REQ-UI-001`, `REQ-UI-002`, `REQ-UI-003`, `REQ-UI-005`
- Screen/route IDs: `AGT-004`, `/agent/tickets/:ticketNumber`
- Frozen operations: `getAgentTicket`, `updateAgentTicket`, `listTicketAssignmentOptions`, `getCurrentStaff`
- Checked but intentionally not surfaced: `transferAgentTicket`, `createChildTicket` (no partial UI)
- Verification gates: `TKT-002`, `TKT-003`, `TKT-006`, `CHG-001`, `CHG-002`, `UI-002`, `UI-003`, `UI-004`, `UI-005`

## Actor, capability, and audit

- Actor/source: authenticated `STAFF` / `STAFF_WEB`; route gate requires `AGENT_WORKSPACE` and the detail's `UPDATE` capability is authoritative for write controls.
- `getAgentTicket` uses one navigation interaction ID and reuses it for background refresh. The UI never emits an extra semantic view by polling or draft recovery.
- `updateAgentTicket` is the only write path. The backend owns the one `TicketAudit` and ordered event set for an atomic comment/field command; the UI sends the server-required expected version and client command ID.
- `assignmentOptions` embedded in the detail is the only source for selectable group/member values. No hard-coded group, agent, availability, organization, tag, product, language, or avatar data is allowed.

## Product and UX contract

- Reuse documented `DsTabs`, `DsSplitButton`, `Notification`, `ScreenState`, `RetryButton`, `StatusBadge`, `DsPropertyField`, and documented button/status primitives.
- Use native semantic `<select>` controls for controlled field updates because the documented `DsSelect` contract does not expose a controlled change API.
- PUBLIC and INTERNAL drafts remain separate. A child ticket offers INTERNAL mode only. If `UPDATE` is absent, the route renders an explicit read-only projection without inert mutation controls.
- Display exact `NEW`, `OPEN`, `PENDING`, `ON_HOLD`, `SOLVED`, and `CLOSED` meanings; no status collapsing and no `updatedAt`-as-`createdAt` presentation.
- The Agent shell has no fixed ticket tabs, synthetic availability, or image avatar. It renders an initial avatar from the authenticated staff display name, and exposes the real `/agent/tickets/new` link only for an AGENT/ADMIN session with `AGENT_WORKSPACE`.

## In scope

- Replace the production fixture-model workspace import path with the real `AgentTicketDetail` and existing `useTicketEditor` flow.
- Capability-gated field controls, PUBLIC/INTERNAL composer, actual save/refresh, local draft/navigation guard, ambiguous retry, and 409 field-by-field recovery.
- Remove production routing/imports for frontend-system fixtures and replace fixture-route browser checks with production API-mocked E2E evidence.
- Focused unit, Storybook interaction/a11y, browser tests, traceability/progress evidence, and one vertical-slice commit.

## Out of scope

- Backend/OpenAPI/migration changes; attachments, macros, favorites, customer-profile action, synthetic availability, transfer UI, child-ticket creation UI, external-reference mutation, and admin surfaces.
- `transferAgentTicket` and `createChildTicket` are hidden rather than represented by incomplete buttons.

## Failure, privacy, and recovery semantics

- A 409 ticket-field conflict retains both drafts and current local fields, refreshes only through a background read, and requires an explicit per-field server/local decision before another command.
- Network/5xx ambiguity preserves the exact local draft and `clientCommandId`; definite validation, authorization, and command-ID misuse clear pending identity only when retry must become a new command.
- Successful command results trigger a real query refresh. A refresh failure reports “saved but not refreshed” without fabricating server state.
- Staff-only projection may contain INTERNAL comments, but no customer route or customer cache is changed. No secret, token, synthetic identity, or unavailable server field is rendered.

## Acceptance scenarios

- An `UPDATE`-capable agent sends a PUBLIC reply, INTERNAL note, or changed fields through one real command with `expectedVersion` and stable `clientCommandId`.
- Group selection limits assignee choices to the returned active group members and clears an incompatible local assignee.
- An ambiguous save retry sends the same command ID and does not create a duplicate logical write.
- A same-field 409 preserves the draft, loads current fields, and permits field-by-field server/local resolution; a disjoint update merges per existing model policy.
- A read-only capability projection has no save, transfer, child, fake data, or inert action controls.

## Validation

- RED/GREEN component and hook-facing tests for capability gating, field command payload, separate drafts, retry identity, conflict resolution, and query refresh.
- Storybook current-design states and interaction/a11y tests for editable, read-only, PUBLIC/INTERNAL, and conflict recovery views.
- Playwright: agent public reply, internal note, field update, ambiguous retry no duplicate, 409 draft preservation, denied route, and no production fixture route/data.

## Compatibility and human explanation

This is a frontend-only recomposition of frozen Agent operations. The previous fixture workspace is not a compatibility surface: production no longer routes or imports it. Keeping command and draft logic inside `useTicketEditor` makes the UI recoverable without treating the browser as the source of ticket truth.

## Completion evidence

- Focused unit: `npm test -- --run src/App.routes.test.ts src/features/agent-shell/AgentShell.test.tsx src/features/ticket-workspace/AgentTicketWorkspacePage.test.tsx src/features/ticket-workspace/AgentTicketEditorWorkspace.test.tsx src/features/ticket-workspace/model/ticketEditorModel.test.ts` — PASS (5 files, 32 tests).
- Storybook MCP: focused editable/read-only/child/editor and Agent shell interaction/a11y tests — PASS; full interaction/a11y pass — PASS.
- Browser: `npm run test:e2e -- agent-views-workspace.spec.ts ticket-workspace.spec.ts agent-ticket-write.spec.ts frontend-system.spec.ts` — PASS (15 tests).
- Static gates: `npm run format:check`, `npm run lint` (one pre-existing `public/mockServiceWorker.js` warning), `npm run typecheck`, `npm run check:design-system-boundaries`, and `npm run build` — PASS.
- The fresh build contains only Deskseed brand assets; no `ticketWorkspaceFixture`, frontend fixture route, `agent-mina-park`, fixed-ticket, or synthetic availability asset/string is emitted. Full whole-goal suites remain pending FE-P0-ADMIN-OPS.
