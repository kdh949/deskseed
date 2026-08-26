# ADR 0043 — Redis-backed customer authentication throttling store

## Status

Accepted — 2026-08-25; amended by product owner — 2026-08-26

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

- The product owner selected Redis as the one authoritative customer-authentication limiter store and waived
  the PostgreSQL comparison benchmark. This is an explicit operational-risk decision, not measured evidence
  that Redis meets the target or that PostgreSQL would fail it.
- Express the committed HTTP/OpenAPI contract in terms of purpose, destination, network identity, generic
  problems, and `Retry-After`; do not freeze PostgreSQL or Redis as an HTTP contract property.
- Application services depend on an `AuthenticationAttemptLimiter` port. Key derivation, generic responses,
  dummy password work, security audit, and `Retry-After` semantics remain outside the storage adapter.
- Implement one Redis adapter with an atomic expiring server-side script. Do not implement or dual-write a
  customer-authentication PostgreSQL limiter adapter. The staff-authentication PostgreSQL lockout and the
  separate public-request limiter are unchanged.
- A reverse proxy or ingress applies coarse path/network throttling before the application. It is a protective
  capacity layer, not a replacement for the enumeration-safe application limiter.
- Once a shared-store decision blocks a fingerprint, each instance may keep a bounded deny cache until the
  returned window end. The cache may reduce repeated store calls but cannot grant requests or extend the
  authoritative allowance.
- Limiter persistence/check transactions finish before adaptive password comparison, mail work, or security
  audit persistence. Store timeout/unavailability returns the generic contracted `503`; it never bypasses
  throttling.

This ADR supersedes ADR 0042's storage-specific customer-authentication limiter statement and applies ADR
0008's separately approved shared-coordination exception to this one capability. ADR 0042's throttling
dimensions, generic response, `429`, `Retry-After`, dummy-work, and authentication invariants remain accepted.

## Declared capacity target and unmeasured release evidence

The product owner declared the following target before implementation:

- 20 requests/second sustained;
- 100 requests/second for a 60-second malicious burst;
- concurrency 100;
- 2x safety factor;
- limiter p95 at most 20 ms and p99 at most 50 ms;
- representative ordinary-transaction p95 degradation at most 10%;
- zero limiter errors, timeouts, or database connection-pool starvation at the target.

The owner explicitly chose not to run the comparison benchmark because customer-authentication writes under
malicious traffic were considered an avoidable PostgreSQL I/O and lock-pressure risk. These values are targets,
not observations. Task 7 may implement Redis, but AUTH-006 performance/capacity evidence remains `Not run` and
release readiness cannot claim that the target or SLO passed until a supported-deployment test records the
environment, traffic matrix, p50/p95/p99, errors/timeouts, Redis memory/eviction behavior, and ordinary workload
impact under `docs/performance/`.

## Redis requirements

- Use an atomic expiring counter operation or reviewed server-side script/function; separate `INCR` and expiry
  calls with a leak window are not acceptable.
- Keys contain purpose-bound keyed fingerprints, never raw email, IP, password, token, session, or company data.
- Define TTL, memory bound/eviction behavior, TLS/authentication, network placement, health/metrics, and local/CI
  topology.
- Define failover data-loss tolerance and fail-closed behavior. Redis replication or persistence is not assumed
  to make limiter state strongly consistent.
- Preserve the same external `429`, `Retry-After`, generic `503`, enumeration, audit, and privacy contract.
- Use Redis only for customer-authentication limiter counters. It is not the customer account, credential,
  session, token, consent, audit, or ticket source of truth.

## Alternatives considered

### Introduce Redis immediately without measurement

Accepted by the product owner in the 2026-08-26 amendment. The trade-off is one more required networked
dependency and an unverified capacity target in exchange for keeping malicious limiter writes and lock pressure
off the transactional PostgreSQL source of truth and avoiding a disposable PostgreSQL adapter.

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

- The current decision is Redis-first for customer-authentication throttling only.
- Task 7 begins by recording this amendment, then implements the storage-neutral port and one Redis adapter.
- Storage replacement is localized to an adapter because application behavior depends on a stable port.
- Redis adoption requires explicit documentation and operational ownership, but no public API version change.
- The declared performance target remains an unverified AUTH-006 release input, not an implementation claim.

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
- Spring Data Redis scripting: <https://docs.spring.io/spring-data/redis/reference/redis/scripting.html>
- Official Redis container image: <https://hub.docker.com/_/redis>
- Redis replication: <https://redis.io/docs/latest/operate/oss_and_stack/management/replication/>
