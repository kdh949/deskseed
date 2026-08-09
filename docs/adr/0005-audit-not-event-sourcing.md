# ADR 0005: Append-only audits without event sourcing

- Status: Accepted
- Date: 2026-08-10

## Context

Support operations require trustworthy history. Event sourcing could provide history but would make reconstruction, schema evolution, and developer onboarding substantially harder for the first product.

## Decision

The current ticket row is the source of truth. Each user-visible save creates one immutable `TicketAudit` containing ordered `TicketAuditEvent` records. Application code never edits audits, and PostgreSQL triggers reject update/delete operations.

## Alternatives

- Mutable activity log: insufficient trust and weak grouping semantics.
- Full event sourcing: excessive initial complexity.

## Consequences

- Current-state queries stay straightforward.
- Audits explain changes but are not guaranteed to reconstruct every future read model.
- Domain/integration events remain separate concepts from audit records.
