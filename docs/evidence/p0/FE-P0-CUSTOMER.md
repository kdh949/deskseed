# FE-P0-CUSTOMER task brief

## Goal

고객이 Deskseed production UI에서 익명 문의를 접수하고, 이메일 링크의 fragment capability로 PUBLIC 대화를 조회·후속 답변하며, 이메일 magic link로 로그인해 자신의 문의를 조회·답변·로그아웃할 수 있게 한다.

## Decision and source references

- Decision IDs: `D-003`, `D-006`, `D-018`, `D-040`, `D-046`, `D-053`
- Accepted ADRs: `0039`
- Requirements: `REQ-TKT-001`, `REQ-TKT-003`, `REQ-TKT-005`, `REQ-TKT-006`, `REQ-TKT-008`, `REQ-AUTH-001`, `REQ-AUTH-002`, `REQ-UI-005`, `REQ-UI-006`
- Screen/route IDs: `PUB-001` through `PUB-004`; `/`, `/requests/new`, `/requests/lookup`, `/requests/:ticketNumber`, `/customer/sign-in`, `/customer/sign-in/consume`, `/account/requests`, `/account/requests/:ticketNumber`
- Frozen operations: `createCustomerRequest`, `getAnonymousRequest`, `addCustomerRequestComment`, `requestCustomerMagicLink`, `consumeCustomerMagicLink`, `getCustomerCsrfToken`, `deleteCustomerSession`, `getCurrentCustomer`, `listCustomerRequests`, `getCustomerRequest`, `addAuthenticatedCustomerComment`
- Verification gates: `TKT-002`, `UI-002`, `UI-004`, `AUTH-001`, `AUTH-002`, `AUTH-003`, `UI-005`

## Actor and source

- Anonymous actor/source: `CUSTOMER` / `CUSTOMER_PORTAL`, constrained by one ticket-scoped opaque access token.
- Authenticated actor/source: verified `CUSTOMER` / `CUSTOMER_PORTAL`, constrained by the server session and ownership projection.
- The browser never selects an actor. Customer session cookies remain HttpOnly server state; anonymous access proof is only an `X-Request-Access-Token` request header.
- A follow-up command keeps one generated `clientCommandId` for ambiguous network/5xx retry and changes it only after a definite failure or confirmed success.

## Product and UX contract

- Reuse: documented `DeskseedBrandMark`, `DsButton`, `DsStatusIndicator`, `ScreenState`, `Notification`, and `RetryButton` contracts.
- Compose: a customer layout, navigation, request forms, public conversation, and account lists from those contracts and semantic HTML.
- Extend: none.
- Add: none.
- Every customer route applies `Referrer-Policy: no-referrer`; loading, empty, validation, denied, expired/not-found, rate-limited, unavailable, and conflict states use explicit text and recovery actions.

## In scope

- Production Customer site routes and session provider with sign-in-aware navigation and CSRF-protected logout.
- Anonymous request create, fragment capture/removal before network use, ticket-scoped session storage, PUBLIC detail, and anonymous PUBLIC follow-up.
- Authenticated magic-link consume, owned request list/detail, and authenticated PUBLIC follow-up.
- Unit, component, Storybook interaction/a11y, and Playwright coverage for the Customer critical paths.

## Out of scope

- Backend, OpenAPI, migration, mail rendering, claim UX, attachment upload, staff/admin UI, and any mocked fallback for an unavailable server behavior.
- No account auto-claim: a matching email is never treated as a proof of ownership.

## Invariants and failure semantics

- Customer DOM and client models only contain the PUBLIC request projection. INTERNAL comments, child relations, staff fields, audit metadata, token, mail body, and recipient are neither rendered nor retained in client state.
- `#token=` is read once, removed with `history.replaceState` before a request, then stored only in `sessionStorage` using a ticket-scoped key. It is not placed in a query string, path, localStorage, error, analytics, or log.
- The token is sent only as `X-Request-Access-Token`; anonymous read/write responses are `no-store`.
- Definite 400/403/404/409/429 errors preserve drafts but require an explicit next action; a 429 presents `Retry-After`. Ambiguous network/5xx keeps the exact draft and command ID for deliberate retry.
- Customer writes must not present optimistic success. Successful writes refresh the corresponding no-store projection.

## Data and privacy

- Browser form fields and PUBLIC comments are customer content; they are not logged by the UI.
- Request-access and magic-link tokens are capability secrets. No persistent client storage is permitted except ticket-scoped `sessionStorage` for the request-access token.
- Customer session cookies and CSRF tokens remain transient transport values and are never copied to storage.

## Acceptance scenarios

- Given a valid form, when an anonymous customer submits it, then the UI shows the ticket number and a safe request URL with a fragment token, not a query token.
- Given an email-link-equivalent fragment, when the detail route opens, then it removes the fragment before its first network request, reads PUBLIC detail, and can post one idempotent PUBLIC follow-up.
- Given a magic-link fragment, when it is consumed, then a server session is created, My Requests lists only owned PUBLIC summaries, an authenticated follow-up includes CSRF and a stable command ID, and logout invalidates the UI session.
- Given denied, expired, rate-limited, unavailable, or conflicting responses, then no unsafe data is shown and drafts are preserved with an accessible recovery path.

## Validation

- Focused RED/GREEN unit and component tests for fragment handling, storage scope, request headers, command-ID reuse/rotation, PUBLIC-only rendering, CustomerSessionProvider, and route states.
- Focused Storybook interaction/a11y tests for each newly introduced customer component, then changed/full story validation.
- Playwright: anonymous submit → fragment detail → follow-up; magic link → account list → follow-up → logout; denied customer routes; production no-fixture scan.
- Slice completion additionally runs `format:check`, `lint`, `typecheck`, design-system boundary check, unit tests, Storybook tests, build, and the relevant E2E tests from `frontend/`.

## Compatibility and migration

- The existing frozen customer contracts are consumed unchanged. This is an additive frontend-only route restoration with no schema/data migration.
- Rollback removes the route composition; server APIs and stored customer data remain unchanged.

## Human explanation

Fragment-only transport prevents request access proof from entering URLs sent to the server, browser history, and referrers. Keeping the proof ticket-scoped and session-only minimizes the time and surface over which a customer capability can leak while retaining a deliberate browser-retry path.
