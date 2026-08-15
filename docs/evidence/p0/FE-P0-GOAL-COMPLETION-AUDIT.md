# Deskseed frontend P0 goal completion audit

Date: 2026-08-15
Goal: production API와 documented design system을 사용하는 Customer, Agent write, Admin operations P0 frontend를 세 vertical slice로 완성한다.

## Audit outcome

**IN PROGRESS.** 이전 PASS 기록은 PR review에서 확인된 Documentation contracts 및 Linux visual E2E 실패보다 앞선 증거였다. 현재 corrective stack은 고객 캐시 격리, 상담사 저장 경쟁, Linux 기준선, 관리자 운영 상태를 보정하고 있다. 이 문서는 최상위 수정 브랜치의 전체 frontend gate와 그 결과를 포함한 GitHub Actions head SHA가 모두 확인되기 전까지 PASS가 될 수 없다.

Review 당시 원격 실패는 Documentation contracts의 PUB-001, PUB-004, AGT-004 표기, Linux Agent workspace screenshot 세 해상도, 그리고 backend test job의 OOM 후 DB 연결 종료였다. 전자의 문서 표기와 Linux 기준선은 corrective stack에서 수정했지만, 수정 브랜치는 아직 기존 PR head에 연결되지 않아 원격 CI 재실행 증거가 없다.

## Slice and route audit

| Slice | Production routes and API operation IDs | Historical slice result |
|---|---|---|
| FE-P0-CUSTOMER | `/`, `/requests/new`, `/requests/lookup`, `/requests/:ticketNumber`, `/customer/sign-in`, `/customer/sign-in/consume`, `/account/requests`, `/account/requests/:ticketNumber`; `createCustomerRequest`, `getAnonymousRequest`, `addCustomerRequestComment`, `requestCustomerMagicLink`, `consumeCustomerMagicLink`, `getCustomerCsrfToken`, `deleteCustomerSession`, `getCurrentCustomer`, `listCustomerRequests`, `getCustomerRequest`, `addAuthenticatedCustomerComment` | PASS; corrective validation pending |
| FE-P0-AGENT-WRITE | `/agent/tickets/:ticketNumber`; `getAgentTicket`, `updateAgentTicket`, embedded `assignmentOptions`, `getCurrentStaff` | PASS; corrective validation pending |
| FE-P0-ADMIN-OPS | `/admin/operations/mail`, `/admin/staff`, `/admin/groups`, `/admin/settings/customer-access-mode`, `/admin/business-rules/schedules`, `/admin/business-rules/sla`; outbound-mail, organization, customer-access-mode, business-schedule, SLA policy and first-reply analytics operation groups | PASS; corrective validation pending |

Each slice has a `CODEX_TASK_TEMPLATE.md`-shaped brief with REQ IDs, Decisions/ADRs, actor/source, authorization, failure semantics, privacy, acceptance scenarios, and verification gates: `FE-P0-CUSTOMER.md`, `FE-P0-AGENT-WRITE.md`, and `FE-P0-ADMIN-OPS.md`.

## Security, privacy, and production-data audit

- Customer link capability: only `#token=` is parsed. `history.replaceState` removes it before the enabled detail query; a valid value is stored only under a ticket-scoped `sessionStorage` key and is sent solely as `X-Request-Access-Token` with `cache: no-store` and `referrerPolicy: no-referrer`. `CustomerSiteLayout` applies `Referrer-Policy: no-referrer` for every customer route.
- Customer surface: response decoders and conversation models retain only the allowlisted PUBLIC projection. INTERNAL comments, child relations, staff fields, audit metadata, and capability tokens have no DOM path.
- Agent surface: `useTicketEditor` constructs one `updateAgentTicket` command with the server version and a stable client command ID. It retains local PUBLIC/INTERNAL drafts, retries ambiguity with the same ID, refreshes after success, and resolves 409s field-by-field. Only returned `assignmentOptions` populate group/member controls. `ON_HOLD` and `CLOSED` are represented explicitly.
- Admin surface: `AdminRoute` requires both `ADMIN` role and `ADMIN_MANAGE`; denied routes do not request protected Admin endpoints. Mail decoders accept only a masked operational projection, so body, token, raw recipient, provider response, and unsupported response fields cannot reach a rendered component. Retry requires a nonblank reason and the existing CSRF/expected-actor transport. Customer access mode and versioned schedule/SLA forms preserve local input on 409/412.
- Fixture removal: no `ticketWorkspaceFixture`, legacy fixture route, fixed `#1042/#1038` tabs, Kim Ji-yeon fallback, synthetic availability/avatar, fake group/agent/org/tag/product/language data, or fake note is in the production route/import graph. The fresh `frontend/dist` scan found no `ticketWorkspaceFixture`, `frontend-system-fixtures`, `김지연`, `가짜 내부 메모`, or `fixture-created` string. Fixture components remain test/Storybook-only and their former production route is a canonical not-found page.
- Scope check: the P0 commit range changes frontend and evidence only; `frontend/package.json` changes E2E script inclusion, not dependencies. There is no backend or `api/` contract change. Transfer, child creation, and external-reference mutation controls remain intentionally hidden, satisfying the complete-or-hidden rule.

## Required E2E audit

| Required scenario | Evidence | Corrective-stack local result |
|---|---|---|
| 1. Anonymous submit → fragment detail → follow-up | `customer-portal.spec.ts`: `anonymous submit → fragment detail → PUBLIC follow-up uses the production customer API boundaries` | PASS — current local 22-test E2E run |
| 2. Magic link → My Requests → authenticated follow-up → logout | `customer-portal.spec.ts`: `magic link → My Requests → authenticated PUBLIC follow-up → logout uses a server customer session` | PASS — current local 22-test E2E run |
| 3. Agent PUBLIC reply | `agent-ticket-write.spec.ts`: `agent PUBLIC reply sends one expected-version command and refreshes the ticket` | PASS — current local 22-test E2E run |
| 4. Agent INTERNAL note | `agent-ticket-write.spec.ts`: `agent INTERNAL note sends an internal command without a public fallback` | PASS — current local 22-test E2E run |
| 5. Agent field update | `agent-ticket-write.spec.ts`: `agent field update uses only the assignment options returned by the ticket API` | PASS — current local 22-test E2E run |
| 6. Ambiguous retry has no duplicate | `agent-ticket-write.spec.ts`: `an ambiguous retry reuses one clientCommandId and does not create a duplicate command` | PASS — current local 22-test E2E run |
| 7. 409 preserves draft | `agent-ticket-write.spec.ts`: `a 409 conflict preserves the draft and requires a field-by-field decision` | PASS — current local 22-test E2E run |
| 8. Failed-mail retry | `admin-operations.spec.ts`: `admin failed-mail retry requires CSRF and a reason, then requeues the same safe intent` | PASS — current local 22-test E2E run |
| 9. Role-denied routes | `access-surface.spec.ts`: `SECURITY_AUDITOR remains denied from the Agent Workspace`; `non-admin staff is denied before any admin operations endpoint is requested` | PASS — current local 22-test E2E run |
| 10. Production UI has no fixture data | `frontend-system.spec.ts`: legacy fixture path is not routable; production Agent projection has no fixture names, fake data, inert controls, or fixture avatar | PASS — current local 22-test E2E run |

## Corrective-stack final gate status

The table below intentionally distinguishes historical slice evidence from the corrective stack. A historical PASS is not evidence that the current head is green.

| Command | Historical local evidence | Corrective-stack status |
|---|---|---|
| `npm ci --no-audit --no-fund` | PASS | PASS with a temporary npm cache; the default home cache had pre-existing root-owned files |
| `npm run format:check` | PASS | PASS |
| `npm run lint` | PASS; one existing unused-disable warning in `public/mockServiceWorker.js` | PASS; the same pre-existing warning remains |
| `npm run typecheck` | PASS | PASS |
| `npm run check:design-system-boundaries` | PASS | PASS |
| `npm run test` | PASS — 37 files, 185 tests | PASS — 41 files, 199 tests |
| `npm run test:storybook` | PASS — 54 files, 191 stories | PASS — 54 files, 196 stories |
| `npm run build` | PASS; non-blocking Vite chunk-size advisory only | PASS; non-blocking Vite chunk-size advisory only |
| `npm run test:e2e` | PASS — 22 tests | PASS — 22 host tests; Linux Agent visual E2E also PASS — 6 tests |
| GitHub Actions on corrective head SHA | Not applicable | PENDING — correction branches are pushed but are not attached to a PR head |

## Storybook MCP audit

Before every UI slice, Storybook MCP `list-all-documentation`, `get-storybook-story-instructions`, and the required `get-documentation`/story-specific contract lookup were performed. The configured `6006` service belonged to another workspace, so a scoped Deskseed Storybook MCP server was started at `http://localhost:6010` without changing repository configuration or stopping that process.

- Historical focused/full evidence is retained below, but does not replace corrective-stack verification.
- Corrective focused Admin `run-story-tests`: PASS — group switch removal reset, access-mode save lock, schedule ambiguous save, SLA ambiguous save; no a11y violation.
- Corrective full project `run-story-tests`: PASS — all discovered stories, with no interaction or a11y failure.
- Corrective `get-changed-stories` and affected-component discovery: PASS.
- Corrective `preview-stories` results:
  - `http://localhost:6011/?path=/story/06-admin-admin-groups-page--switching-groups-clears-member-removal`
  - `http://localhost:6011/?path=/story/06-admin-admin-customer-access-mode-page--save-locks-selection`
  - `http://localhost:6011/?path=/story/06-admin-admin-business-schedules-page--ambiguous-version-save`
  - `http://localhost:6011/?path=/story/06-admin-admin-first-reply-sla-page--ambiguous-version-save`
- Historical representative previews:
  - `http://localhost:6010/?path=/story/05-shells-layouts-adminshell--mail-operations`
  - `http://localhost:6010/?path=/story/06-admin-admin-mail-page--failed-intent-retry`
  - `http://localhost:6010/?path=/story/06-admin-admin-customer-access-mode-page--conflict-preserves-selection`
  - `http://localhost:6010/?path=/story/06-admin-admin-business-schedules-page--version-review-and-edit`
  - `http://localhost:6010/?path=/story/06-admin-admin-first-reply-sla-page--version-review-and-edit`

## Remaining work and compatibility

All corrective local frontend gates are PASS. GitHub Actions on a corrective head SHA remains pending because the correction branches are not attached to an existing or newly created PR. The backend OOM/DB shutdown is an environment or backend-job result until a new CI run proves otherwise; it is not recorded as a frontend fix. Explicit anonymous-ticket claim, transfer, child creation, and external-reference mutation UI remain separately deferred product surfaces with no incomplete controls exposed. This frontend-only delivery remains additive at the route layer and requires no migration or rollback of backend data.
