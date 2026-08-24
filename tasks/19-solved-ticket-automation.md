# Codex Brief 19 — Solved Ticket Close Automation

## User scenario

An admin versions and activates a policy such as “close tickets 72 hours after they were solved.” The scanner discovers eligible SOLVED intervals in bounded batches. A machine actor rechecks the current interval and performs the system-only SOLVED to CLOSED transition with one TicketAudit.

## Actor and decisions

- definition actor: authenticated ADMIN with `automation:manage`, source `ADMIN_UI`
- execution actor: `ActorType.AUTOMATION`, source `AUTOMATION`
- requirements: `REQ-AUT-002`
- accepted design: ADR 0024, docs 34/45
- verification: `AUT-009`, `docs/21-minimum-verification-gates.md`

## Data and failure boundaries

- public/internal content is not read or copied; discovery uses status, solvedAt, ticket ID and number only.
- immutable policy version and activation history use admin/security audit in the same transaction.
- candidate identity is `(automationVersion, ticketId, solvedAt)` within its automation aggregate.
- scanner coordination is a PostgreSQL advisory transaction lock; batch limit is 100 and default interval is 60 seconds.
- claim uses a lease and `FOR UPDATE SKIP LOCKED`; expired leases are recoverable.
- a ticket reopened or re-solved after discovery is skipped rather than closed from stale evidence.
- retry/dead-letter state never creates a partial TicketAudit or ticket transition.

## Vertical slices

- [x] versioned solved-age policy, activation and dry-run contract
- [x] indexed bounded candidate discovery and interval idempotency
- [x] leased execution and system-only close command provenance
- [x] latest solved interval skip, lease recovery, retry and dead-letter rollback
