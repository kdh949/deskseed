# BE-P0-PUBLIC-ABUSE task brief

## Goal

공개 문의 접수 API가 다중 인스턴스에서도 이메일 대상·신뢰된 네트워크·전체 버킷 기준으로 남용을 견디고, 제한 시 안전한 `Retry-After` 응답을 반환한다.

## Decision and source references

- Decision IDs: `D-006`, `D-008`, `D-018`
- Accepted ADRs: `0006`, `0008`, `0018`
- Requirements: `REQ-TKT-002`, `REQ-TKT-003`
- Contract operation: `createCustomerRequest`
- Verification gates: `AUTH-001`, `ARCH-003`

## Actor and source

- Actor/source: anonymous or authenticated `CUSTOMER` / `CUSTOMER_PORTAL`.
- Resource boundary: limiter runs before customer/ticket creation and trusts forwarding headers only from configured proxy CIDRs.

## In scope

- Add a Portal-owned PostgreSQL fixed-window limiter for `POST /api/v1/requests`.
- Use keyed HMAC fingerprints for normalized destination and trusted client address plus a global bucket; never store raw email/IP.
- Validate limits, HMAC key, trusted proxy CIDRs and forwarded-hop bound at startup; return `429` and `Retry-After` atomically under concurrency.
- Fail closed on malformed/oversized forwarded chains from a trusted proxy and on unavailable limiter persistence.
- Add the next additive Flyway migration for buckets/expiry cleanup/indexes and PostgreSQL concurrency/spoof tests.

## Out of scope

- Shared generic limiter, Redis, CDN/WAF policy, CAPTCHA, account login throttling, or reuse of Platform API's internal limiter.

## Invariants and failure semantics

- One upsert increments a bucket or rejects it; the count is durable and multi-instance safe.
- A non-trusted peer cannot choose its source identity with `X-Forwarded-For`.
- Limiter storage failure produces no request creation and a safe unavailable response.

## Data and privacy

- Rows contain bucket type, HMAC fingerprint, count and expiry only. No raw destination, IP, token, ticket body, or request header is persisted.

## Acceptance scenarios

- Concurrent requests crossing a limit admit only the configured number.
- A spoofed forwarding header from an untrusted peer does not change the client bucket.
- Trusted proxy malformed/too-long forwarding chain fails closed; window expiry admits a new request.

## Validation

- PostgreSQL bucket concurrency and expiry integration tests; MockMvc `Retry-After` and spoofed-forwarding tests.
- Contract/document validation and focused backend tests recorded in the progress log.

## Compatibility and migration

- Existing customer create request remains compatible below configured limits.
- V28+ is additive and forward-only; operational rollback disables the limiter only by deliberate configuration, not schema deletion.

## Human explanation

PostgreSQL is already the authoritative deployment dependency, so a small atomic bucket table is simpler and safer than an unmeasured distributed cache.
