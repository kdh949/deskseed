# FE-P0-ADMIN-OPS task brief

## Goal

ADMIN이 현재 production API만 사용해 메일 전달 상태, 직원·그룹, 고객 접근 모드, 영업 시간표와 First Reply SLA를 안전하게 운영한다.

## Decision and source references

- Decision IDs: D-018, D-030, D-032, D-034, D-038, D-044, D-046, D-047, D-050, D-052, D-053.
- Accepted ADRs: ADR 0039; `docs/44-sla-ola-business-hours-implementation-spec.md`; `docs/49-email-notifications-and-channel-adapter-spec.md`; `docs/52-admin-settings-catalog.md`.
- Requirement IDs: REQ-AUTH-005, REQ-PERM-002, REQ-TKT-004, REQ-NOTIF-001, REQ-CHAN-003, REQ-SLA-001, REQ-SLA-002, REQ-SLA-003.
- OpenAPI operation IDs: `getOutboundMailSummary`, `listOutboundMailIntents`, `getOutboundMailIntent`, `retryOutboundMailIntent`, `listStaffAccounts`, `createStaffAccount`, `disableStaffAccount`, `listGroups`, `createGroup`, `updateGroup`, `disableGroup`, `listGroupMembers`, `createGroupMembership`, `deleteGroupMembership`, `getCustomerAccessModeSetting`, `updateCustomerAccessModeSetting`, schedule and SLA policy operation groups, `getFirstReplySlaAnalytics`.
- Verification gates: REQ-AUTH-005, REQ-PERM-002, SCHED-001, SCHED-002, SLA-001, SLA-002, SLA-003, SLA-009, UI-002 through UI-005.

## Actor and source

- Actor type/source: authenticated STAFF / `ADMIN_UI` only.
- Required role: current staff projection must be `ADMIN` and have `ADMIN_MANAGE`; the server remains the authoritative role gate.
- Every admin mutation uses the existing staff CSRF and expected-actor guard. The frontend neither chooses an actor nor sends an alternate identity.

## Product and UX contract

- Routes: `/admin/operations/mail`, `/admin/staff`, `/admin/groups`, `/admin/settings/customer-access-mode`, `/admin/business-rules/schedules`, `/admin/business-rules/sla`.
- All screens provide loading, empty, error, denied and mutation conflict/stale recovery where their contract exposes it.
- The mail screen consumes only the masked operations projection. It must never render a body, token, raw recipient, provider response, request/correlation identifier, or protected content. Its reason is write-only and required for retry.
- Customer access mode and versioned schedules/SLA preserve local input after a 409/412. They only overwrite it after a deliberate refresh or successful save.

## In scope

- Production Admin shell/guard, API client decoding for the frozen masked mail operations contract, all listed route screens, focused unit/Storybook/E2E coverage, evidence and traceability updates.

## Out of scope

- Backend/OpenAPI changes, mail content inspection, credential or SMTP configuration, arbitrary audit reveal, customer policy fields absent from the frozen operation, and an incomplete transfer/child UI.

## Invariants and failure semantics

- Admin/server security audit is owned by each committed backend mutation; the browser does not synthesize audit events.
- Retry re-queues the same terminal `FAILED` intent only. A 409 leaves the typed reason intact and reports a distinct conflict state.
- Group membership options use the actual staff list and active account status only. Disable and membership mutations refresh authoritative query data.
- Schedule/SLA changes create immutable versions and activation uses the response aggregate version in `If-Match`; no client-side optimistic success is claimed.

## Data and privacy

- Mail read state contains only contract-validated safe operational fields and a locally held signed cursor. Retry reason is not stored by the UI after success.
- Staff email is an authorized organization projection. Password input is sent only to `createStaffAccount`, immediately cleared, and never rendered from a response.
- No new retention, export, webhook, or persistence behavior is introduced.

## Acceptance scenarios

1. Given an ADMIN session, a failed masked mail intent can be retried only after a nonblank reason; CSRF/expected actor are sent and success, conflict, and failure are distinguishable.
2. Given an AGENT or SECURITY_AUDITOR session, every `/admin/*` route renders a denied state and does not query the protected operation.
3. Given stale customer access/schedule/SLA data, a 409/412 retains the local selection/form and offers an explicit refresh path.
4. Given actual staff, groups, schedules, and policies, every select/list reflects only the returned projection and no fixture fallback.
5. Given a mail projection that contains a raw recipient or an unsupported shape, decoding fails closed before the value reaches the DOM.

## Validation

- Focused unit tests cover mail decoder/retry headers, the route guard, and customer access-mode conflict preservation.
- Focused Storybook interaction/a11y covers every Admin page story; local `npm run test:storybook -- src/features/admin` passed 7 files / 13 stories.
- Browser E2E covers the required failed-mail retry, mail secret/raw-recipient non-rendering, and ADMIN route denial. Access-mode conflict preservation has focused unit coverage; schedule/SLA version editing has focused Storybook coverage.
- Final frontend gates and the whole-goal audit can proceed after the required Storybook MCP runner and changed-story/preview calls pass.

## Execution evidence — PASS

- Implementation: added the six Admin routes, `AdminRoute` (`ADMIN` + `ADMIN_MANAGE`), production API client decoding for masked outbound-mail projections, and no-fixture staff/group/access-mode/schedule/SLA screens.
- Passed: `npm ci --no-audit --no-fund`; `npm run format:check`; `npm run lint` (only existing `public/mockServiceWorker.js` warning); `npm run typecheck`; `npm run check:design-system-boundaries`; `npm run test` (37 files / 185 tests); `npm run test:storybook` (54 files / 191 stories); `npm run build`; `npm run test:e2e` (22 tests).
- Storybook MCP documentation discovery and component-contract lookup passed before implementation. The configured 6006 port was occupied by another workspace, so a scoped Deskseed Storybook validation server was started at 6010 without changing repository configuration or stopping the other workspace. Direct Deskseed MCP validation at 6010 passed a focused 13-story Admin batch and the full project `run-story-tests` pass; no interaction or a11y failure was returned.
- `get-changed-stories` identified 46 pre-commit affected stories. Representative Admin shell, mail retry, access-mode conflict, schedule-version, and SLA-version previews were returned from `http://localhost:6010`.
- Result: FE-P0-ADMIN-OPS is PASS. The port collision is an environment diagnostic only and no longer blocks the whole-goal completion audit.

## Compatibility and migration

- No API, migration, or dependency change. The client implements already frozen Core OpenAPI operations. Legacy removed Admin routes remain absent until this slice adds their canonical replacements.
