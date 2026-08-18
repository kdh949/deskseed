# Goal Foundation F3 — frontend extension host

## Goal

Provide one deterministic frontend composition point so each Wave 1 feature can contribute its own route, agent-shell navigation item, and ticket-workspace/composer slot without changing central route, shell, or workspace wiring again.

## Decision and source references

- Decision IDs: D-008, D-010, D-012, D-033, D-036, D-054, D-055
- Accepted ADRs: ADR 0008, 0010, 0012, 0024, 0025, 0039, 0040
- Requirement: REQ-FND-004
- Verification gates: FRONTEND-001, FRONTEND-004, FRONTEND-006, ARCH-003

## Contribution contract

- A feature adds only `src/extensions/<feature>/feature-contribution.tsx`; Vite discovers this fixed pattern eagerly and deterministically.
- Every route, navigation, or workspace contribution has a globally unique dotted ID and a non-negative order. Conflicting ID, route, or surface/slot order rejects application startup.
- Route metadata is surface-scoped (`customer`, `agent`, or `admin`). Agent navigation is limited to `/agent/...` paths. Customer routes cannot declare a staff-only access policy.
- Agent navigation and workspace slots apply both role and required-capability checks from the authenticated staff session. Agent/admin routes are gated before their contribution renders; the backend remains the authority for every feature API.
- Ticket slots receive only a ticket number and the PUBLIC/INTERNAL composer mode. The host supplies no ticket detail, customer data, token, mutation function, or API client.
- Each slot contribution has its own error boundary. A failing optional contribution renders nothing and cannot blank the ticket workspace. The boundary records neither errors nor user data.

## In scope

- static feature contribution discovery and deterministic validation;
- agent navigation and route integration points;
- production `AgentTicketEditorWorkspace` context, composer-toolbar, and composer-status slots;
- focused role/capability, duplicate metadata, denied route, and error-isolation tests.

## Out of scope

- concrete Wave 1 feature modules, routes, API calls, or UI;
- server authorization changes, endpoint additions, migrations, background delivery, and data persistence;
- Storybook MCP documentation/preview/test execution, because `deskseed-design-proj` tools were not registered for this session.

## Security and failure semantics

The host never turns a client role/capability check into authorization: each server route/API must enforce the matching policy. A missing capability hides navigation and prevents a workspace contribution from rendering; an unauthorized direct agent/admin route receives the denied state before feature code runs. A feature failure is isolated, no sensitive context is logged, and an absent contribution changes no existing screen behavior.

## Compatibility and rollback

F3 is additive and has no migration, OpenAPI, or persistence change. The empty contribution set preserves current routes and UI. Rollback is a normal revert of this stacked commit; no applied migration or stored data needs repair.

## Validation record

- Passed locally: OpenAPI/MSW contract; TypeScript typecheck; ESLint (one pre-existing generated-service-worker warning, no errors); Prettier; design-system boundary; production build; 241 unit tests; 210 package Storybook tests (including AgentShell extension navigation); and 20 Chromium E2E scenarios including axe coverage of the ticket workspace.
- Pending: remote GitHub CI.
- Not run: Storybook MCP `list-all-documentation`, instructions, changed-story lookup, preview, and `run-story-tests`; the documented MCP endpoint exists in `frontend/.mcp.json`, but no corresponding live MCP tools are registered in this task session.
