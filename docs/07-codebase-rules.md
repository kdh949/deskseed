# Codebase Rules

These rules are normative unless an Accepted ADR changes them.

## 1. Domain

1. Ticket has no description field; the request body is the first `PUBLIC` comment.
2. `PUBLIC` and `INTERNAL` visibility is enforced server-side.
3. Transfer changes ownership of the existing ticket.
4. Child-ticket delegation retains parent ownership.
5. Open children warn but do not block parent solve.
6. Assignee must be an active member of the assigned group.
7. UI, Platform API, Trigger, and Automation use the same application commands for the same business action.
8. External system data is linked before arbitrary data is mirrored.

## 2. Actor and request context

Every command and sensitive read carries:

```text
actorType
actorId
source
requestId
interactionId?
sessionId?
commandId?
correlationId
causationId?
integrationClientId?
```

- Never infer a human actor from an untrusted header.
- Machine calls default to `INTEGRATION_CLIENT`.
- Trigger/Automation/System are explicit actors.
- Display snapshots must not contain secrets or excessive PII.

## 3. Modules

1. Keep a modular monolith until measured evidence justifies extraction.
2. Import only another module's root API or named interface.
3. Never import another module's `internal` package.
4. Keep `platform-api`, `staff-access`, and `portal` as separate adapters.
5. Do not create generic `common`, `utils`, `helpers`, or `shared` dumping grounds.
6. JPA entities never cross module/API boundaries.
7. `ApplicationModules.verify()` must pass.

## 4. Application services and transactions

1. One externally meaningful command has one application service entry point.
2. Application services own transaction boundaries.
3. Domain objects/policies own invariants.
4. Controllers translate HTTP only.
5. Adapters own persistence, cryptography, and external I/O.
6. Ticket mutation and TicketAudit must commit or roll back together.
7. Sensitive read and AccessAuditEvent must complete before success response.
8. No external network call inside ticket mutation/read transaction.
9. Outbound intent is persisted and delivered after commit.

## 5. Persistence

1. PostgreSQL and Flyway own schema evolution.
2. Hibernate uses `ddl-auto=validate` only.
3. Merged migrations are never edited; add a new migration.
4. Use UUID internal IDs and monotonic numeric ticket numbers.
5. Add indexes from a query and execution-plan hypothesis, not by habit.
6. Preserve before/after query-plan evidence under `docs/performance/`.
7. Canonical audit tables are append-only by application and DB privilege/trigger.
8. Audit read projection is rebuildable and not a source of truth.
9. Idempotency records have explicit retention and uniqueness constraints.

## 6. Time

1. Store timestamps as UTC `Instant`.
2. Inject `Clock`; do not call `Instant.now()` in business code.
3. Business schedule calculations store their timezone/version.
4. Audit timestamps are generated server-side.
5. Client-provided time may be metadata but never canonical occurrence time without validation.

## 7. API

1. Use `/api/v1` and keep customer/staff/admin/audit/platform surfaces separate.
2. OpenAPI 3.1 is authoritative for Platform API.
3. Use RFC 9457 Problem Details for errors.
4. Do not expose stack traces, SQL, internal class names, or protected resource existence.
5. Use opaque cursor pagination.
6. External write requires `Idempotency-Key`.
7. External update requires `If-Match` or equivalent expected version.
8. 429 includes `Retry-After`.
9. SDKs are generated from the committed contract and versioned with it.
10. API examples must include retries, conflict, insufficient scope, and idempotency reuse.

## 8. Integration security

1. Integration secret is shown once and stored as a secure hash when verification is sufficient.
2. Rotation supports overlap and explicit revocation.
3. Scope is combined with resource constraints; scope alone never means all records.
4. External deep links allow only `https` and configured hosts.
5. The server does not fetch arbitrary reference URLs by default.
6. Browser code never receives long-lived API client credentials.
7. Webhook signatures cover timestamp and raw body.
8. Webhook receivers must tolerate duplicates; sender event ID is stable across attempts.
9. Delivery failure does not roll back ticket data.
10. Inbound provider webhooks require provider-specific signature validation before normalization.

## 9. Audit and privacy

1. Keep Ticket Change, Access/Search, Admin/Security, and Delivery logs distinct.
2. Ticket change events use structured allowed fields, not entity serialization.
3. `TICKET_VIEWED` is a semantic user navigation event, not every GET/poll.
4. Search audit stores redacted query and keyed fingerprint; encrypted raw query is policy-controlled.
5. Full search query reveal requires a separate permission and reason.
6. Audit Explorer view, reveal, and export are audited.
7. Never store passwords, token/secret values, authorization headers, or session cookies.
8. Ordinary operational logs do not contain comment body or search query.
9. Sanitize control characters to prevent log injection.
10. Retention is category-specific and cannot be an unreviewed hard-coded delete.
11. Legal hold, if added, always overrides automated deletion.

## 10. Concurrency and idempotency

1. Staff ticket updates use field-aware optimistic concurrency.
2. Same-field stale writes return 409 with `conflictingFields`.
3. Disjoint changes may merge only through an explicit tested policy.
4. Integration idempotency identity includes client, operation, and key.
5. Same key/different payload returns 409.
6. Replayed response must not repeat side effects.
7. Webhook event ID and delivery attempt ID are distinct.

## 11. Events and automation

1. Domain events are immutable, past-tense internal facts.
2. Integration events are separate, versioned, public-safe contracts.
3. Trigger action invokes normal commands and creates normal audits.
4. Trigger execution has definition ID/version, root operation, depth, and state fingerprint.
5. Automation jobs have durable cursor/lease/idempotency before production.
6. No arbitrary code execution in the application process.

## 12. Testing

1. Add pure domain tests for invariants.
2. Add module tests for use cases.
3. Use PostgreSQL-backed tests for transaction, JSON, lock, index, trigger, and privilege behavior.
4. Contract tests validate OpenAPI examples and generated SDKs.
5. Security tests cover missing scope, resource restriction, secret leakage, impersonation, SSRF boundary, audit permissions.
6. Failure injection tests cover audit insert failure, duplicate idempotency, webhook timeout, retry, and replay.
7. A bug fix starts with a failing regression test unless automation is impossible; document exceptions.
8. The relevant checklist in `docs/21-minimum-verification-gates.md` must pass.

## 13. Documentation

1. Behavior changes update PRD, domain model, API, tests, and audit semantics together.
2. Architecture changes require ADR.
3. Public API changes include compatibility classification.
4. Every substantial PR records commands run and those not run.
5. AI output is not evidence; test output, query plans, and reproducible scenarios are evidence.
