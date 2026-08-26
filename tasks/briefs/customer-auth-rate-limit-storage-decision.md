# Customer authentication rate-limit storage decision

## Goal

고객 인증 limiter의 Redis owner decision을 기록하고 storage-neutral port, atomic Redis adapter,
enumeration-safe `429`/`503`, local/CI topology를 구현한다.

## User decision context

- 사용자 우려: 인증 시도마다 PostgreSQL write가 발생하면 공격자가 DB lock/I/O를 고갈시킬 수 있다.
- 사용자 대안: PostgreSQL 구현 후 Redis로 교체하는 낭비를 피하기 위해 Redis를 처음부터 도입하는 편이
  단순할 수 있다.
- 2026-08-26 승인 결정: PostgreSQL 비교 benchmark를 생략하고 Redis adapter 하나를 바로 구현한다.
- 선언 target: 20 req/s sustained, 100 req/s 60-second burst, concurrency 100, safety factor 2x.
- 선언 SLO: limiter p95 <= 20 ms, p99 <= 50 ms, ordinary transaction p95 degradation <= 10%,
  zero errors/timeouts/database-pool starvation.
- Target/SLO는 측정값이 아니며 supported-deployment evidence 전까지 `Not run`이다.

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
- storage-neutral `AuthenticationAttemptLimiter` and the magic-link request integration
- atomic Redis TTL script, keyed fingerprints, generic 429/503, local Compose and Testcontainers topology
- docs/56 Task 7 sequencing, risks, selected decision history, documentation quality, and traceability checks

## Out of scope

- PostgreSQL customer-auth limiter migration/repository/cleanup worker
- Sentinel/Cluster or a production deployment manifest
- benchmark execution or performance claims
- credential/token schema, Argon2, registration, password login/reset, passwordless completion, or UI

## Acceptance

1. 문서가 Redis 직접 선택, 비교 benchmark waiver, declared-but-unmeasured target/SLO를 함께 보존한다.
2. OpenAPI는 throttling behavior를 유지하되 특정 storage engine을 public/committed behavior로 고정하지 않는다.
3. Redis script는 purpose/global/destination/network budget을 원자적으로 증가시키고 TTL을 함께 설정한다.
4. raw email/IP/password/token/session은 key, response, audit, log에 들어가지 않는다.
5. 제한은 `429`와 `Retry-After`, Redis 실패는 generic `503`이며 인증 DB transaction 밖에서 끝난다.
6. local Compose와 focused Testcontainers evidence가 있고 production TLS/auth/noeviction/health 경계를 문서화한다.

## Validation

- `make docs-check`
- `python3 scripts/test_api_documentation_quality.py`
- focused fast/integration tests
- `git diff --check`
- secret/log scan and changed-file review
