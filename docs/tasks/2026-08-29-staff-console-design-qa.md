# Staff Console Reference Isolation Design QA

Status: **PASSED**

## Scope and authority

- Routes: `/agent/login`, `/agent/views/:viewKey`, `/agent/search`, `/agent/tickets/new`, `/agent/tickets/:ticketNumber`
- Visual authority: the five images attached to the 2026-08-29 task, used only for hierarchy, density, spacing, and composition
- Product authority: committed Core OpenAPI and REQ-AUTH-005/006, REQ-UI-001/002/003/005/006, REQ-PERM-001, REQ-TKT-006/007/009/010/013/014, REQ-SRCH-001/002, REQ-VIEW-001
- Canonical presentation root: `frontend/apps/staff-console/src/design-system/canonical.ts` and `canonical-index.css`

## Route and reference comparison

The in-app browser rendered the real Vite routes with deterministic synthetic contract responses in the QA tab. Request interception was ephemeral browser state and did not change application code, OpenAPI, or runtime fixtures.

| Route | Reference viewport | Actual capture | Side-by-side comparison |
|---|---:|---|---|
| Login | 1672×941 | `login-route.png` | `compare-login.png` |
| New ticket | 1448×1086 | `create-route.png` | `compare-create.png` |
| Search | 1448×1086 | `search-route.png` | `compare-search.png` |
| Queue/saved view | 1448×1086 | `queue-route.png` | `compare-queue.png` |
| Ticket workspace | 1448×1086 | `ticket-workspace-route-1448-final.jpg` | `ticket-workspace-comparison-1448.jpg` |
| Ticket workspace, full context rail | 1600×1086 | `ticket-workspace-route-1600-final.jpg` | `ticket-context-comparison.jpg` |

Capture directory: `/Users/donghyunkim/.codex/visualizations/2026/08/28/01a04a08-e94b-7490-b877-0d7022de28b8/`

Visible review result: all five routes use the same cream canvas, dark-green navigation frame, compact controls, consistent borders/radii/elevation, and information-dense panel composition. Contract-backed content is intentionally less dense than the references where the references show unsupported fields or menus.

## Responsive and interaction gates

- UI-001: the actual ticket route was recaptured at `1448×1086` and `1600×1086`. At 1448 px the context rail intentionally moves to the drawer; at 1600 px the full properties/conversation/context composition is visible. The 200% equivalent `724×543` check has no page-level horizontal overflow.
- UI-002: canonical buttons, links, rows, tabs, fields, drawer, and composer expose visible focus and semantic labels. Drawer Escape, focus trap, and focus restoration are implemented in `SeedDrawer`.
- UI-003: PUBLIC and INTERNAL draft separation, persisted ambiguous-retry identity, save locking, remote draft recovery, and read-only behavior remain covered by unit and Storybook interaction tests.
- UI-004: loading, empty, error, denied, validation, conflict, read-only, stale/recovery, and responsive compositions are represented across canonical component and route stories.
- UI-005: the final workspace reference and Storybook screen were joined into one `2896×1086` comparison image and judged region by region; no screenshot or crop is shipped in the UI.
- UI-006: canonical route and Storybook files are enumerated by `check-design-system-boundaries.mjs`; old entrypoints, symbols, class prefixes, stylesheets, cross-app tokens, and screenshot imports fail the gate.

## Verification evidence

- `npm run typecheck`: PASS
- `npm test`: PASS — customer 22 files/57 tests and staff 30 files/202 tests
- `npm run format:check`, `npm run lint`, `npm run typecheck`, `npm run build`: PASS
- Staff production build: main 805.35 kB/234.22 kB gzip; lazy rich-editor chunk 399.07 kB/125.75 kB gzip. The editor split reduced the initial main chunk by 399.36 kB/125.63 kB gzip from the pre-split build; the main-chunk size advisory remains.
- `npm run check:design-system-boundaries`: PASS — 4 boundary tests plus canonical surface scan
- Storybook MCP `run-story-tests` with `a11y=true`: PASS — 60 stories
- 100-comment Storybook interaction: PASS — 100 전체/20 INTERNAL/100 전체 전환, 가로 overflow 0; 앱 내 Browser 왕복 측정 320 ms/305 ms로 브라우저 제어 transport를 포함하므로 순수 렌더링 벤치마크로 해석하지 않는다.
- `make docs-check`: PASS — Core OpenAPI bundle, documentation quality 36 tests, documentation validation
- Backend `./gradlew --no-daemon test`: PASS — 550 categorized tests
- Storybook route/component previews: generated for foundations, primitives, components, workspace patterns, login, queue, search, create-ticket states, writable/read-only workspace states, and the routed ticket screen
- `git diff --check`: PASS

## Legacy isolation and deletion

- Active route, canonical design-system, and Storybook entry files import only `design-system/canonical` and `canonical-index.css`.
- Retired Queue/View navigation, old AgentShell/WorkspaceNavigationRail, old ticket workspace panels/composer/timeline/fixtures, old route stylesheets, old presence stylesheet, and old frontend-system fixture presentation were deleted after migration.
- The remaining legacy root design-system supports out-of-scope admin/audit routes only; it is excluded from the five agent route and canonical Storybook dependency boundary.

## Contract-first omissions

The workspace reference still shows controls without committed support. The implementation therefore omits Cc/Bcc, remote image URLs, arbitrary HTML/style/iframe, customer phone/address/local time/join date, editable collaboration notes, and unsupported product navigation. No unsupported endpoint, field, menu, audit event, or behavior was invented.

## Delivery boundaries

- OpenAPI change: additive closed `content` envelopes plus collaboration-note and notification operations; legacy `body` remains the derived compatibility projection.
- Database migration/rollback: V83–V85 add rich content, collaboration notes/notifications, and plain-text write compatibility. Rollback is application-version compatible through retained `body`; destructive schema rollback is not automated.
- Backend transaction/concurrency: comment body/content, ticket mutation, mail intent and TicketAudit remain atomic. Collaboration note, mention notification and audit commit together; realtime delivery is post-commit and carries notification ID only. Stable command IDs protect retry/idempotency.
- Privacy/security: canonical allowlist validation rejects arbitrary HTML, unsafe link protocols and remote images; audits hash normalized content without retaining body/document JSON. PUBLIC/INTERNAL and ticket-local CLEAN attachment isolation remain server-owned.
- Remote CI, deployment, commit, and PR: not run/not created in this task

Human trade-off: visual density follows the references, but only committed server projections are rendered. Unsupported reference controls are omitted so the UI does not imply capability, permission, or audit behavior that the product does not have.
