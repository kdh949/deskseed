# ADR 0013: Keep separate audit ledgers and unify them through a read projection

- Status: Accepted
- Date: 2026-08-10

## Context

Ticket field changes, sensitive record access/search, admin/security changes, and webhook delivery have different semantics, transaction requirements, permissions, and retention periods. A single generic log table would either lose domain meaning or become an unbounded JSON dump.

## Decision

Maintain canonical ledgers for:

1. Ticket Change Audit
2. Access/Search Audit
3. Admin/Security Audit
4. Integration Delivery Log

Expose a rebuildable `AuditActivityProjection` or normalized query service for one Audit Explorer.

## Alternatives considered

- One `audit_log` JSON table: rejected because constraints, retention, indexes, and permissions become ambiguous.
- Operational logs only: rejected because they are sampled, mutable, and not domain-atomic.
- Event Sourcing: rejected because current-state reconstruction is not the requirement.

## Consequences

- More schemas and mapping code.
- Clearer ownership, access control, and retention.
- Unified explorer can be rebuilt and optimized independently.
- Cross-ledger correlation IDs become important.
