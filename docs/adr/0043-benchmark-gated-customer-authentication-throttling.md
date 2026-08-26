# ADR 0043 — Benchmark-gated customer authentication throttling store

## Status

Accepted — 2026-08-25

## Context

Customer authentication must throttle registration, login, password reset, and magic-link traffic by
purpose-bound destination and requester-network fingerprints without revealing whether an account exists.
ADR 0008 selects PostgreSQL first and ADR 0042 originally named PostgreSQL as the customer-authentication
limiter store.

The product owner raised two valid concerns before implementation:

1. writing every authentication attempt to PostgreSQL can turn the limiter into a database hot-row,
   WAL, connection-pool, or lock-contention target during a malicious burst;
2. fully implementing a PostgreSQL limiter and later replacing it with Redis could waste delivery effort,
   so adopting Redis immediately appeared simpler.

There is no hardware-independent PostgreSQL request-per-second threshold. Same-key updates serialize around
the row lock while high-cardinality attacks shift pressure to inserts, indexes, WAL, and the connection pool.
Redis offers a natural expiring atomic-counter model, but introduces another networked dependency, failure
policy, secret/TLS configuration, local/CI topology, monitoring, replication, and recovery semantics. Either
store still requires coarse upstream abuse control because the shared limiter must not be the first system to
absorb an unbounded attack.

## Decision

- Keep PostgreSQL as the preferred initial store, but do not build the production PostgreSQL adapter before
  running the benchmark gate below on the supported deployment topology.
- Express the committed HTTP/OpenAPI contract in terms of purpose, destination, network identity, generic
  problems, and `Retry-After`; do not freeze PostgreSQL or Redis as an HTTP contract property.
- Application services depend on an `AuthenticationAttemptLimiter` port. Key derivation, generic responses,
  dummy password work, security audit, and `Retry-After` semantics remain outside the storage adapter.
- Before Task 7 chooses a production adapter, run a small PostgreSQL prototype/`pgbench` workload rather than
  implementing the full repository, cleanup worker, migration, and operational integration.
- If the benchmark passes the predeclared deployment target and isolation criteria, implement only the
  PostgreSQL adapter. If it fails, amend the affected decision/contract documents and implement only the Redis
  adapter. Do not dual-write limiter state during normal operation.
- A reverse proxy or ingress applies coarse path/network throttling before the application. It is a protective
  capacity layer, not a replacement for the enumeration-safe application limiter.
- Once a shared-store decision blocks a fingerprint, each instance may keep a bounded deny cache until the
  returned window end. The cache may reduce repeated store calls but cannot grant requests or extend the
  authoritative allowance.
- Limiter persistence/check transactions finish before adaptive password comparison, mail work, or security
  audit persistence. Store timeout/unavailability returns the generic contracted `503`; it never bypasses
  throttling.

This ADR supersedes only ADR 0042's storage-specific statement that every customer-authentication limiter is
PostgreSQL-backed. ADR 0042's throttling dimensions, generic response, `429`, `Retry-After`, dummy-work, and
authentication invariants remain accepted. ADR 0008's PostgreSQL-first and measured-Redis trigger remain in
force.

## Benchmark gate

The deployment owner records the target sustained rate, malicious burst rate, concurrency, safety factor,
latency budget, and ordinary-workload SLO before execution. A result without those inputs is exploratory and
cannot select the store.

The reproducible matrix includes:

- one hot global key;
- one hot normalized-destination fingerprint;
- one hot requester-network fingerprint;
- high-cardinality destination/network fingerprints;
- concurrency 1, 10, 50, and 100 plus the declared deployment burst;
- limiter-only traffic and limiter traffic mixed with representative ticket/session transactions.

Record exact hardware/service tier, PostgreSQL settings, schema/indexes, statement, client/pool configuration,
duration, warm-up, throughput, p50/p95/p99 latency, errors/timeouts, tuple/transaction lock waits, pool wait,
WAL bytes, table/index growth, cleanup behavior, and the impact on the ordinary-workload SLO. Store the result
under `docs/performance/`.

PostgreSQL passes only when the declared sustained and burst targets, safety factor, limiter latency budget,
and ordinary-workload isolation all pass without pool starvation or an unbounded lock/WAL/cleanup condition.
Otherwise Redis is justified before production limiter implementation. A missing target environment or
unavailable benchmark is `Blocked`, not evidence that either store passed.

## Redis requirements if the trigger fires

- Use an atomic expiring counter operation or reviewed server-side script/function; separate `INCR` and expiry
  calls with a leak window are not acceptable.
- Keys contain purpose-bound keyed fingerprints, never raw email, IP, password, token, session, or company data.
- Define TTL, memory bound/eviction behavior, TLS/authentication, network placement, health/metrics, and local/CI
  topology.
- Define failover data-loss tolerance and fail-closed behavior. Redis replication or persistence is not assumed
  to make limiter state strongly consistent.
- Preserve the same external `429`, `Retry-After`, generic `503`, enumeration, audit, and privacy contract.

## Alternatives considered

### Introduce Redis immediately without measurement

Rejected for now. It avoids a possible adapter replacement but commits the MVP to another operated system
before the expected deployment target or PostgreSQL failure is demonstrated. It remains the selected fallback
when the benchmark gate fails or a separately approved shared coordination requirement justifies Redis.

### Fully implement PostgreSQL and optimize only after production contention

Rejected. A small hot-key/high-cardinality benchmark is cheaper than production code and exposes the exact
failure mode before migrations, cleanup workers, and repository code are committed.

### Keep storage selection inside authentication application services

Rejected. It would spread infrastructure decisions through enumeration, session, and audit logic and make a
later adapter change unnecessarily expensive.

### Use only reverse-proxy or per-instance memory limits

Rejected as the authoritative control. Distributed attackers and multiple application instances can bypass
local allowances, although these controls remain useful capacity shields.

## Consequences

- The current decision is benchmark-gated PostgreSQL-first, not an unconditional promise to implement either
  PostgreSQL or Redis.
- Task 7 begins with the benchmark artifact and adapter decision before production limiter code or migration.
- Storage replacement is localized to an adapter because application behavior depends on a stable port.
- Redis adoption requires explicit documentation and operational ownership, but no public API version change.
- Performance evidence becomes a release input for AUTH-006 rather than an optional optimization note.

## References

- D-060
- ADR 0008, 0035, 0042
- REQ-AUTH-003, REQ-AUTH-004
- AUTH-001, AUTH-005, AUTH-006, AUTH-007, AUTH-008
- `docs/32-database-schema-and-index-blueprint.md`
- `docs/56-customer-auth-consent-request-form-p0-implementation-plan.md`
- PostgreSQL locking: <https://www.postgresql.org/docs/current/explicit-locking.html>
- PostgreSQL pgbench: <https://www.postgresql.org/docs/current/pgbench.html>
- Redis rate limiter pattern: <https://redis.io/docs/latest/develop/use-cases/rate-limiter/>
- Redis replication: <https://redis.io/docs/latest/operate/oss_and_stack/management/replication/>
