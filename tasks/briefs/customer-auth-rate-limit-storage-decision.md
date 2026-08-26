# Customer authentication rate-limit storage decision

## Goal

고객 인증 limiter를 구현하기 전에 PostgreSQL hot-row/고카디널리티 부하와 Redis 운영 비용을 비교하고,
전체 adapter를 중복 구현하지 않는 benchmark-gated 선택 계약을 문서화한다.

## User decision context

- 사용자 우려: 인증 시도마다 PostgreSQL write가 발생하면 공격자가 DB lock/I/O를 고갈시킬 수 있다.
- 사용자 대안: PostgreSQL 구현 후 Redis로 교체하는 낭비를 피하기 위해 Redis를 처음부터 도입하는 편이
  단순할 수 있다.
- 현재 승인 결정: storage-independent port를 먼저 동결하고 production adapter/migration 전에 target
  PostgreSQL benchmark를 실행한다. 통과하면 PostgreSQL adapter만, 실패하면 결정·계약을 갱신한 뒤 Redis
  adapter만 구현한다.

## Decision and source references

- Decision ID: D-060
- Accepted ADRs: ADR 0008, 0035, 0042, 0043
- Requirements: REQ-AUTH-003, REQ-AUTH-004
- Verification gates: AUTH-001, AUTH-005, AUTH-006, AUTH-007, AUTH-008, DOC-001
- API operations: customer registration, verification, password session/reset, magic-link request/consume,
  passwordless completion

## Actor, privacy, and failure boundary

- Actor/source: `CUSTOMER_ANONYMOUS` or current `CUSTOMER_ACCOUNT`, source `CUSTOMER_PORTAL`
- Limiter keys: purpose-bound keyed destination/network fingerprints; no raw email/IP/credential/session data
- External failures: generic `429` plus `Retry-After`, or generic fail-closed `503`
- Transaction boundary: limiter check finishes before adaptive password work, mail delivery, session mutation, or
  required security-audit transaction
- Upstream coarse limiting and bounded deny cache may reduce load but cannot grant allowance

## In scope

- ADR 0043 and D-060
- storage-neutral OpenAPI limiter metadata
- AUTH-006 benchmark and isolation evidence
- docs/56 Task 7/9 sequencing, risks, and selected decision history
- documentation quality and traceability checks

## Out of scope

- PostgreSQL limiter migration/repository/cleanup worker
- Redis dependency, container, client, script/function, Sentinel/Cluster, or managed-service configuration
- benchmark execution or performance claims
- authentication runtime, UI, push, PR, or deployment changes

## Acceptance

1. 문서가 사용자의 Redis-first 고민과 현재 benchmark-gated PostgreSQL-first 결정을 함께 보존한다.
2. OpenAPI는 throttling behavior를 유지하되 특정 storage engine을 public/committed behavior로 고정하지 않는다.
3. benchmark는 target/SLO 선등록, hot-key/high-cardinality/mixed-workload matrix, lock/pool/WAL/latency evidence를 요구한다.
4. PostgreSQL 통과와 Redis 전환 조건이 결정적이며 benchmark unavailable은 `Blocked`로 기록한다.
5. runtime, migration, dependency, deployment topology는 변경하지 않는다.

## Validation

- `make docs-check`
- `python3 scripts/test_api_documentation_quality.py`
- `git diff --check`
- changed-file scan confirming docs/OpenAPI documentation tests only
