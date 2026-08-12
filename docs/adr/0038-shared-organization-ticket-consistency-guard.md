# ADR 0038 — Shared organization and ticket consistency guard

## Status

Accepted

## Context

Organization administration serialized staff, group and membership mutations with a
PostgreSQL transaction advisory lock. Ticket commands validated the same current group,
staff and membership rows without that lock. A ticket assignment or membership-based
authorization check could therefore race a group/member deactivation and both
transactions could commit from incompatible snapshots.

## Decision

- Organization administration and every staff ticket command acquire one shared
  transaction-scoped PostgreSQL advisory lock before reading organization-dependent
  authorization or assignment state.
- Pure request validation may run before the lock. Command replay locks, ticket reads,
  assignment checks and mutations run after it, establishing one lock order.
- The consumer-owned guard port is exposed through the ticketing module root API and
  implemented by the organization adapter; neither module imports another module's
  internals and the existing dependency direction remains acyclic.
- PostgreSQL remains the only coordination dependency. A deterministic trigger/barrier
  integration test proves that the later transaction waits and revalidates committed
  state.

## Alternatives

- Rely on foreign keys and optimistic ticket versions: rejected because they do not
  express active-state or active-membership predicates across rows.
- Lock individual group, staff and membership rows: deferred because create/update/
  transfer/child commands touch different combinations and would require a larger
  ordered-lock protocol before measured contention justifies it.
- Add Redis or a distributed coordinator: rejected because the database transaction is
  the consistency boundary and the current topology is a modular monolith.

## Consequences

- Organization mutation and staff ticket command throughput is serialized for the
  current single-organization installation. Correctness is preferred until contention
  evidence warrants keyed locks.
- A ticket command that wins the lock commits before deactivation is evaluated; a
  deactivation that wins causes the later ticket command to fail assignment or write
  authorization checks.
- Read-only ticket and organization queries do not acquire the guard.
