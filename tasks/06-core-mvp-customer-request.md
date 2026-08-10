# Codex Brief 06 — Anonymous Customer Request Backend Vertical Slice

## Goal

익명 고객이 문의를 생성하고 한 번만 받은 opaque token으로 해당 문의의 공개 projection만 조회한다.

## Decision and source references

- Decisions: D-001, D-002, D-003, D-005, D-006, D-008, D-018
- Accepted ADRs: 0003, 0005, 0006
- Requirements: REQ-TKT-001~003, REQ-TKT-006, REQ-TKT-008, REQ-AUD-007
- OpenAPI operations: `createAnonymousRequest`, `getAnonymousRequest`
- Verification gates: ARCH-001, ARCH-002, ARCH-004, TKT-001, TKT-002, CHG-001, CHG-002, CHG-004, ACC-007

## Actor and source

- Actor: `CUSTOMER`; source: `CUSTOMER_PORTAL`
- Authentication: anonymous create; ticket-number-bound request access token for read
- Resource constraint: the active token hash must resolve to the requested customer ticket
- Request/correlation IDs are bounded server-accepted values and are copied to the creation audit

## In scope

- Customer, Ticket, first PUBLIC Comment, TicketAudit and ordered TicketAuditEvent
- request access grant with SHA-256 verifier, configurable 30-day default expiry, and revocation metadata
- `POST /api/v1/requests` and `GET /api/v1/requests/{ticketNumber}`
- RFC 9457 errors, Flyway migration, PostgreSQL/Testcontainers tests, OpenAPI synchronization

## Out of scope

- customer login, additional customer comments, email, CAPTCHA, attachments, staff workflows, child tickets
- token rotation/revocation HTTP administration and rate limiting; the schema/policy seam is retained for later slices

## Invariants and failure semantics

- Ticket has no description; `message` is the first PUBLIC customer comment.
- Customer, Ticket, Comment, one TicketAudit, two ordered events, and access grant commit together.
- Comment, audit, audit-event, or grant persistence failure rolls the entire command back.
- TicketAudit and TicketAuditEvent are append-only at the database boundary.
- Invalid, expired, revoked, mismatched, and nonexistent ticket/token pairs return the same 404 problem.
- Anonymous request creation is not idempotent; callers must not automatically retry after an unknown outcome.
- No external I/O occurs in the transaction.

## Data and privacy

- Same normalized unverified email reuses the current Customer and refreshes its unverified profile.
- Token plaintext is returned only by create; only its verifier and lifecycle metadata are stored.
- Customer read SQL selects only customer-safe ticket fields and PUBLIC comments.
- Staff/group/assignee, INTERNAL comments, child relations, and audit metadata never enter the customer DTO.
- Operational logs contain neither comment body nor token plaintext.

## Acceptance scenarios

- Given valid input, when create succeeds, then all records and ordered audit events exist atomically.
- Given an INTERNAL comment, when the customer reads, then it is absent before serialization.
- Given wrong token or ticket number, when the customer reads, then the same 404 problem is returned.
- Given comment/audit insertion failure, when create runs, then no Ticket remains.
- Given a created token, then the raw token and message are absent from database verifier fields and logs.

## Compatibility and migration

- The create response is frozen as `ticketNumber`, `status`, `accessToken`, and `createdAt`.
- Customer input limits are name 100, email 254, subject 200, message 20,000.
- Public status is limited to NEW, OPEN, PENDING, SOLVED; internal hold/closed values map to a customer-safe value.
- Flyway migrations are forward-only; rollback is application rollback plus database restore/forward fix.
