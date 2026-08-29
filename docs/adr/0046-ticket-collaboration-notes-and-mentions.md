# ADR 0046: Append-only ticket collaboration notes and bounded mention notifications

## Status

Accepted — 2026-08-29

## Context

Presence shows who is viewing or editing a ticket but does not provide the compact staff-only collaboration thread represented in the Agent Workspace. Reusing ticket INTERNAL comments would mix the main chronological conversation with side coordination, while an unaudited feature-local thread would weaken ticket authorization and accountability.

## Decision

- A ticket owns an append-only collaboration-note thread separate from `TicketComment`. Notes are staff-only plain text with structured mentioned staff IDs; they are never projected to customer, Platform API, webhook, export, search, or outbound mail surfaces.
- Creating a note requires the normal ticket write policy. Mention targets must be active staff who can read the ticket. One stable `clientCommandId` is unique per author and exact replay returns the original note without another audit or notification.
- Note, mention rows, recipient notification rows, one TicketAudit, and one `COLLABORATION_NOTE_CREATED` ordered event commit or roll back together. Audit metadata contains note ID and mentioned staff IDs, never the note body.
- Notification payloads contain notification ID, ticket number, note ID, actor summary, timestamps, and read state only. The collaboration WebSocket sends a post-commit notification ID hint and never sends note text or customer data.
- Reading the thread is an explicit sensitive read with a fail-closed access audit. Marking the recipient-owned notification read is idempotent and does not mutate the ticket row or version.

## Consequences

- The context card can show a bounded preview and a paginated drawer without changing the ticket timeline.
- Notes cannot be edited or deleted in this slice. Mention delivery is in-app only; email/push delivery is excluded.
- Ticket optimistic concurrency remains authoritative for ticket fields/comments; collaboration notes use their own append-only identity and idempotency boundary.

## References

- D-005, D-007, D-018, D-030, D-032, D-041, D-042, D-055
- REQ-COL-002, REQ-COL-003, REQ-UI-003, REQ-UI-004, REQ-UI-005
- PERM-001, AUD-001, IDEM-001, UI-002
