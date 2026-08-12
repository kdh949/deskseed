# Customer My Requests Portal Evidence

- Date: 2026-08-12 (Asia/Seoul)
- Branch: `feat/16-customer-my-requests-portal`
- Stack base: `feat/15-customer-magic-link-auth`
- Requirements: `REQ-AUTH-002`, `REQ-TKT-003`, `REQ-TKT-004`, `REQ-TKT-005`, `REQ-TKT-008`
- Decisions: `D-002`, `D-005`, `D-012`, `D-014`, `D-021`, `D-028`, `D-032`, `D-038`, `D-040`, `D-046`
- ADRs: 0029, 0034
- Verification gates: `AUTH-003`, `AUTH-004`, `TKT-002`, `CHN-005`, `CHN-006`, `CHN-007`, `ACC-007`, `UI-001`, `UI-002`, `UI-004`, `UI-005`, `MAIL-001`
- Dataset: synthetic only

## Implemented scenario and projection boundary

A magic-link-authenticated CUSTOMER can list and open only `CUSTOMER_REQUEST` tickets whose current
`requester_id` is the session customer. The server projects ticket number, subject, customer status,
timestamps and PUBLIC comments. INTERNAL comments, child relations, staff/group/assignee fields,
ticket/access/security audit metadata and outbound delivery state do not enter the response DTO.
Cross-customer access and guessed ticket numbers return the same not-found boundary.

The portal supports loading, empty, retryable error, denied/not-found/expired proof and stale setting
conflict states. Follow-up and claim failures preserve input and move keyboard focus to a textual
status. The ADMIN access screen explains the effect of all three access modes before an
expected-version update.

## Claim state machine

| Input/current state | Result | Atomic effects |
|---|---|---|
| Active ticket request token + matching verified account | `CLAIMED` | requester transfer, all request tokens revoked, one `REQUESTER_CHANGED` ticket audit, one `CUSTOMER_REQUEST_CLAIMED` security audit |
| Active signed grant + matching verified account | `CLAIMED` | same effects plus grant consume |
| Tampered, expired, consumed or wrong-ticket proof | `NOT_FOUND` | denied security audit; no requester change |
| Valid proof + different verified email | `DENIED` | denied security audit; grant remains unconsumed |
| Already verified requester or non-customer ticket | `NOT_FOUND` | no ownership change |

Email equality is only checked after a ticket-scoped capability is proven. Account creation and
magic-link login never search for or transfer historical tickets by email.

## Follow-up transaction and mail boundary

One authenticated CUSTOMER follow-up transaction locks the owned ticket and stable
`(requesterId, clientCommandId)` command identity, then commits the PUBLIC comment, optional
PENDING→OPEN transition, one TicketAudit with ordered events and
`customer-follow-up-received:{commentId}` outbound intent. An exact replay returns the canonical
comment. Reuse for a different ticket/body conflicts. SOLVED/CLOSED remain unchanged and reject the
follow-up; automatic reopen is not part of this slice.

SMTP is post-commit. The existing worker claims and leases the intent in its own transaction, calls
the provider-neutral transport, and records success/retry/terminal failure separately. Delivery
failure cannot roll back the committed ticket transaction, and business rollback leaves no mail
intent for the worker.

| Cycle attempt | Backoff | Retryable failure |
|---:|---:|---|
| 1 | immediate | `RETRY_WAIT` |
| 2 | 1 minute | `RETRY_WAIT` |
| 3 | 5 minutes | `RETRY_WAIT` |
| 4 | 30 minutes | `RETRY_WAIT` |
| 5 | 2 hours | terminal `FAILED` |

Manual retry requeues the same terminal intent with an audited reason and a new retry cycle. It does
not create a second ticket comment, business audit or mail intent.

## Verification log

| Gate | Result | Evidence |
|---|---|---|
| Backend full suite | PASS | `./gradlew clean test`: 175 tests, 0 failed/error/skipped; includes architecture and Flyway migration coverage |
| Portal PostgreSQL integration | PASS | A/B isolation, guessed number, PUBLIC-only projection, token/grant success, tamper, expiry, replay, different email, access modes, follow-up replay |
| Claim privacy regression | PASS | expired proof test asserts raw token and customer email are absent from persisted proof state and captured application output |
| Mailpit Testcontainers API | PASS | 3 scenarios: direct magic mail, auth magic link consume/replay, PUBLIC follow-up replay; actual SMTP and Mailpit REST inspection |
| Frontend repository gate | PASS | format, lint, typecheck, 18 files/161 tests, production build (203 modules) |
| Browser UI gate | PASS | Chromium: 45/45; portal axe, expired proof focus/input retention, failed follow-up draft/stable command retry |
| Visual gate | PASS | reviewed Darwin Chromium snapshots at 1280×800 and 1440×900 |
| Real Compose Playwright | PASS | 6/6; includes anonymous submit → magic link → explicit claim → My Requests → PUBLIC follow-up → exact replay |
| Compose smoke | PASS | backend `:18080/actuator/health` and Mailpit `:18025/readyz`, exit 0; owned containers/network/volume cleaned |
| Docs/contracts | PASS | `validate_documentation.py` and `verify_seed.py` |

## Mailpit API verification log

The Testcontainers and production-shaped Compose flows delete the synthetic mailbox first, then
verify through `/api/v1/messages` and `/api/v1/message/{id}`:

- exact generated `follow-up-mailpit-<uuid>@example.com` or `portal-<timestamp>@example.com` recipient;
- magic-link subject and fragment-token link, followed by successful single consume and replay denial;
- request-received subject containing the exact ticket number and body link ending in
  `/requests/{ticketNumber}`;
- exact customer follow-up replay returns 201 but leaves one follow-up comment, one intent, one
  delivery attempt and one additional Mailpit receipt;
- a second worker pass processes zero due rows and the mailbox count remains unchanged.

SMTP accept followed by acknowledgement loss is not an exactly-once boundary. Stable intent key,
stable `Message-ID`, row lease and attempt history prevent application/concurrent-worker duplicates
in the verified paths; provider idempotency or reconciliation remains a production-provider gate.

## Migration, compatibility and exclusions

Flyway V20 is additive: a claim-grant table, settings optimistic version and two portal/replay indexes.
It does not rewrite requester ownership. Existing anonymous request access tokens and customer
sessions remain compatible; rollback is an application forward-fix with the additive schema left in
place. No down migration was added.

No dedicated portal load/soak or new EXPLAIN ANALYZE run was performed. The ownership-first list uses
the V20 partial index and page size is bounded to 100. Organization-shared requests, automatic SOLVED
reopen, password/SSO/social login, production mail provider, inbound mail, bounce handling,
attachments and rich text are not implemented.

## Human-owner trade-off

The current Ticket row remains the ownership source of truth. Explicit proof changes that row and is
fully audited instead of building an email-based account merge or event-sourced ownership overlay.
PostgreSQL row locks, digest uniqueness and canonical audit replay keep the first slice simple and
correct; measured contention or latency should drive any later queue/cache redesign.
