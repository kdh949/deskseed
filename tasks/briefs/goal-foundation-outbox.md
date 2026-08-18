# Goal Foundation F2 — transactional domain-event outbox

## Goal

Ticket creation and representative updates create a bounded integration-event intent in the same PostgreSQL transaction, without treating audit history as an event store or placing external delivery inside a ticket command.

## Decision and source references

- Decision IDs: D-002, D-008, D-010, D-012, D-033, D-035, D-036, D-054, D-055
- Accepted ADRs: ADR 0002, 0008, 0010, 0012, 0024, 0025, 0039, 0040
- Requirements: REQ-FND-003
- Verification: ARCH-002, ARCH-003, PostgreSQL migration/transaction gate

## Actor and source

- Actors: CUSTOMER, STAFF, and INTEGRATION_CLIENT ticket-command actors.
- Sources: CUSTOMER_PORTAL, AGENT_UI, and PLATFORM_API command contexts.
- Boundary: an integration event carries only bounded delivery metadata; comment bodies, credentials, authorization material, and audit payloads are excluded.

## In scope

- versioned event envelope and its committed JSON Schema;
- PostgreSQL outbox with per-subject sequence, claim lease, delivery state, and expired-lease recovery;
- atomic representative ticket-created, ticket-updated, comment-created, and status-changed intents;
- V36 additive migration and focused migration/transaction tests.

## Out of scope

- webhook subscriptions, serialization, HTTP dispatch, retry policy, dead-letter UI, and consumer checkpoint rows (Wave 1 integrations lane);
- workflow-trigger execution and analytics consumers;
- category change event: the current Ticket kind/category is immutable and has no mutation command.

## Invariants and failure semantics

- `EventPublicationPort.append` requires an existing transaction; a persistence failure aborts the ticket command with its audit write.
- a worker claim, recovery, or future delivery failure does not roll back an already committed ticket command.
- ordering is only within `subject`; consumers must not infer global ordering.
- internal child ticket facts and internal comments remain `INTERNAL`; non-child ticket metadata and public comments are explicitly `PUBLIC`.
- delivery is at least once: an event ID and subject sequence remain stable across lease recovery; receivers must deduplicate by ID.

## Privacy and compatibility

The envelope limits metadata keys and values, rejects control characters and sensitive keys, and preserves actor/source/request/correlation/causation metadata. V36 is forward-only and additive. Operational rollback is application rollback plus a forward repair migration; applied Flyway history is never edited.

## Acceptance scenarios

1. Given a transaction marked rollback-only after append, `domain_event_outbox` contains no intent.
2. Given an expired lease, the same event is claimed again with its ID and subject sequence intact and its attempt count incremented.
3. Given ticket creation or an agent/platform update, the outbox receives metadata-only ticket/comment/status facts with the corresponding PUBLIC or INTERNAL visibility.
4. Given an unbounded body, secret, token, control character, or invalid event type, envelope construction fails before persistence.
