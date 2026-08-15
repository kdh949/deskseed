# Deskseed frontend P0 goal completion audit

Date: 2026-08-15
Goal: production API와 documented design system을 사용하는 Customer, Agent write, Admin operations P0 frontend를 세 vertical slice로 완성한다.

## Audit outcome

**PASS.** `FE-P0-CUSTOMER`, `FE-P0-AGENT-WRITE`, `FE-P0-ADMIN-OPS`의 각 task brief, required contract, focused validation, Storybook evidence, progress checkpoint와 final frontend gate가 모두 PASS다. Backend, OpenAPI, migration 또는 production dependency는 이 goal에서 변경하지 않았다.

## Slice and route audit

| Slice | Production routes and API operation IDs | Result |
|---|---|---|
| FE-P0-CUSTOMER | `/`, `/requests/new`, `/requests/lookup`, `/requests/:ticketNumber`, `/customer/sign-in`, `/customer/sign-in/consume`, `/account/requests`, `/account/requests/:ticketNumber`; `createCustomerRequest`, `getAnonymousRequest`, `addCustomerRequestComment`, `requestCustomerMagicLink`, `consumeCustomerMagicLink`, `getCustomerCsrfToken`, `deleteCustomerSession`, `getCurrentCustomer`, `listCustomerRequests`, `getCustomerRequest`, `addAuthenticatedCustomerComment` | PASS |
| FE-P0-AGENT-WRITE | `/agent/tickets/:ticketNumber`; `getAgentTicket`, `updateAgentTicket`, embedded `assignmentOptions`, `getCurrentStaff` | PASS |
| FE-P0-ADMIN-OPS | `/admin/operations/mail`, `/admin/staff`, `/admin/groups`, `/admin/settings/customer-access-mode`, `/admin/business-rules/schedules`, `/admin/business-rules/sla`; outbound-mail, organization, customer-access-mode, business-schedule, SLA policy and first-reply analytics operation groups | PASS |

Each slice has a `CODEX_TASK_TEMPLATE.md`-shaped brief with REQ IDs, Decisions/ADRs, actor/source, authorization, failure semantics, privacy, acceptance scenarios, and verification gates: `FE-P0-CUSTOMER.md`, `FE-P0-AGENT-WRITE.md`, and `FE-P0-ADMIN-OPS.md`.

## Security, privacy, and production-data audit

- Customer link capability: only `#token=` is parsed. `history.replaceState` removes it before the enabled detail query; a valid value is stored only under a ticket-scoped `sessionStorage` key and is sent solely as `X-Request-Access-Token` with `cache: no-store` and `referrerPolicy: no-referrer`. `CustomerSiteLayout` applies `Referrer-Policy: no-referrer` for every customer route.
- Customer surface: response decoders and conversation models retain only the allowlisted PUBLIC projection. INTERNAL comments, child relations, staff fields, audit metadata, and capability tokens have no DOM path.
- Agent surface: `useTicketEditor` constructs one `updateAgentTicket` command with the server version and a stable client command ID. It retains local PUBLIC/INTERNAL drafts, retries ambiguity with the same ID, refreshes after success, and resolves 409s field-by-field. Only returned `assignmentOptions` populate group/member controls. `ON_HOLD` and `CLOSED` are represented explicitly.
- Admin surface: `AdminRoute` requires both `ADMIN` role and `ADMIN_MANAGE`; denied routes do not request protected Admin endpoints. Mail decoders accept only a masked operational projection, so body, token, raw recipient, provider response, and unsupported response fields cannot reach a rendered component. Retry requires a nonblank reason and the existing CSRF/expected-actor transport. Customer access mode and versioned schedule/SLA forms preserve local input on 409/412.
- Fixture removal: no `ticketWorkspaceFixture`, legacy fixture route, fixed `#1042/#1038` tabs, Kim Ji-yeon fallback, synthetic availability/avatar, fake group/agent/org/tag/product/language data, or fake note is in the production route/import graph. The fresh `frontend/dist` scan found no `ticketWorkspaceFixture`, `frontend-system-fixtures`, `김지연`, `가짜 내부 메모`, or `fixture-created` string. Fixture components remain test/Storybook-only and their former production route is a canonical not-found page.
- Scope check: the P0 commit range changes frontend and evidence only; `frontend/package.json` changes E2E script inclusion, not dependencies. There is no backend or `api/` contract change. Transfer, child creation, and external-reference mutation controls remain intentionally hidden, satisfying the complete-or-hidden rule.

## Required E2E audit

| Required scenario | Evidence | Result |
|---|---|---|
| 1. Anonymous submit → fragment detail → follow-up | `customer-portal.spec.ts`: `anonymous submit → fragment detail → PUBLIC follow-up uses the production customer API boundaries` | PASS |
| 2. Magic link → My Requests → authenticated follow-up → logout | `customer-portal.spec.ts`: `magic link → My Requests → authenticated PUBLIC follow-up → logout uses a server customer session` | PASS |
| 3. Agent PUBLIC reply | `agent-ticket-write.spec.ts`: `agent PUBLIC reply sends one expected-version command and refreshes the ticket` | PASS |
| 4. Agent INTERNAL note | `agent-ticket-write.spec.ts`: `agent INTERNAL note sends an internal command without a public fallback` | PASS |
| 5. Agent field update | `agent-ticket-write.spec.ts`: `agent field update uses only the assignment options returned by the ticket API` | PASS |
| 6. Ambiguous retry has no duplicate | `agent-ticket-write.spec.ts`: `an ambiguous retry reuses one clientCommandId and does not create a duplicate command` | PASS |
| 7. 409 preserves draft | `agent-ticket-write.spec.ts`: `a 409 conflict preserves the draft and requires a field-by-field decision` | PASS |
| 8. Failed-mail retry | `admin-operations.spec.ts`: `admin failed-mail retry requires CSRF and a reason, then requeues the same safe intent` | PASS |
| 9. Role-denied routes | `access-surface.spec.ts`: `SECURITY_AUDITOR remains denied from the Agent Workspace`; `non-admin staff is denied before any admin operations endpoint is requested` | PASS |
| 10. Production UI has no fixture data | `frontend-system.spec.ts`: legacy fixture path is not routable; production Agent projection has no fixture names, fake data, inert controls, or fixture avatar | PASS |

## Final gate evidence

All commands were run from `frontend/` against the final source state before this documentation-only audit update.

| Command | Result |
|---|---|
| `npm ci --no-audit --no-fund` | PASS |
| `npm run format:check` | PASS |
| `npm run lint` | PASS; one existing unused-disable warning in `public/mockServiceWorker.js` |
| `npm run typecheck` | PASS |
| `npm run check:design-system-boundaries` | PASS |
| `npm run test` | PASS — 37 files, 185 tests |
| `npm run test:storybook` | PASS — 54 files, 191 stories |
| `npm run build` | PASS; non-blocking Vite chunk-size advisory only |
| `npm run test:e2e` | PASS — 22 tests |

## Storybook MCP audit

Before every UI slice, Storybook MCP `list-all-documentation`, `get-storybook-story-instructions`, and the required `get-documentation`/story-specific contract lookup were performed. The configured `6006` service belonged to another workspace, so a scoped Deskseed Storybook MCP server was started at `http://localhost:6010` without changing repository configuration or stopping that process.

- Focused Admin `run-story-tests`: PASS — 13 stories.
- Full project `run-story-tests`: PASS — all returned stories, with no interaction or a11y failures; the local Storybook test suite reports 54 files / 191 stories.
- `get-changed-stories`: PASS — found the 46 pre-commit affected stories; after commit-state comparison, no additional changed story remained.
- Representative `preview-stories` results:
  - `http://localhost:6010/?path=/story/05-shells-layouts-adminshell--mail-operations`
  - `http://localhost:6010/?path=/story/06-admin-admin-mail-page--failed-intent-retry`
  - `http://localhost:6010/?path=/story/06-admin-admin-customer-access-mode-page--conflict-preserves-selection`
  - `http://localhost:6010/?path=/story/06-admin-admin-business-schedules-page--version-review-and-edit`
  - `http://localhost:6010/?path=/story/06-admin-admin-first-reply-sla-page--version-review-and-edit`

## Remaining work and compatibility

There is no blocker for this goal. Explicit anonymous-ticket claim, transfer, child creation, and external-reference mutation UI are separately deferred product surfaces; no incomplete controls are exposed. This frontend-only delivery is additive at the route layer and requires no migration or rollback of backend data.
