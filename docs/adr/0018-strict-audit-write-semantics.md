# ADR 0018: Fail closed when required audit persistence fails

- Status: Accepted
- Date: 2026-08-10

## Context

The product promises that sensitive staff reads/searches and business mutations are auditable. Returning protected data or committing a change while silently dropping the corresponding audit record would make that promise unreliable.

## Decision

- Ticket/admin mutation and its change audit commit atomically.
- Sensitive ticket/customer/search/audit reads persist their AccessAuditEvent before returning success.
- If required audit persistence fails, return a stable service problem and do not silently succeed.
- Rebuildable explorer projection failure does not invalidate a canonical ledger write.

## Alternatives considered

- Best-effort asynchronous access logs: rejected for silent gaps.
- Write operational log only: rejected because it is not canonical or protected.
- Fail closed for every public/health read: rejected; only defined sensitive operations carry the obligation.

## Consequences

- Audit storage availability becomes part of sensitive-path availability.
- Performance overhead must be measured and optimized.
- The policy can be revisited only through an explicit compliance/availability ADR.
