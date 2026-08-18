# ADR 0042 — Advisory single-instance presence topology

## Status

Accepted — 2026-08-18

## Context

Wave 1 adds authenticated staff WebSocket presence and safe stale-version notifications. Presence is intentionally advisory: PostgreSQL ticket versions and normal optimistic commands remain the source of truth, and a delayed or missing signal must never prevent a ticket command or overwrite a local draft.

The delivered adapter holds only short-lived connection and presence state in memory. A multi-instance deployment without a shared, reviewed delivery boundary would split a staff member's presence and risk broadcasting a stale signal to only one process. Adding Redis, Kafka, or another broker solely to mask that topology would exceed the measured-evidence architecture policy.

## Decision

- Run the in-memory presence bus only for a declared single application instance. A production profile with more than one declared instance fails startup before serving WebSocket traffic.
- Keep presence and stale notifications advisory, metadata-only, and after-commit. They never acquire ticket locks, alter ticket versions, or roll back a committed command.
- Retain connection presence for at most the configured 60-second heartbeat TTL. Do not persist message bodies, customer data, provider credentials, or connection IDs in a shared table, ordinary log, or audit ledger.
- A future multi-instance implementation requires a new Accepted ADR with measured topology/load evidence, delivery ordering and duplicate behavior, privacy/retention policy, failure isolation, and real-stack verification before any shared broker or cache is introduced.

## Alternatives

- Silently run separate in-memory buses on each replica: rejected because users would receive inconsistent presence without an explicit deployment boundary.
- Introduce Redis or Kafka now: rejected because no measured independent consumer, scale, or operator requirement justifies a new infrastructure dependency.
- Persist presence as ticket state: rejected because advisory collaboration must not become a ticket lock or alter the audited ticket source of truth.

## Consequences

- Operators must declare a single instance for this Wave 1 presence adapter; a mismatched production declaration is a deliberate fail-closed deployment error.
- Browser reconnection, rate-limit, logout, and authorization failures remain connection-local. A client can still save/recover drafts and execute normal ticket commands when presence is unavailable.
- The integration preview can validate bundle, compile, migrations, and single-node real-stack behavior, but multi-instance delivery remains an explicit non-goal until the follow-up decision is adopted.

## References

- D-005, D-007, D-008, D-010, D-018, D-050, D-055, D-057
- ADR 0002, 0005, 0007, 0008, 0010, 0018, 0040, 0041
- REQ-COL-002, REQ-FND-003, REQ-FND-004
