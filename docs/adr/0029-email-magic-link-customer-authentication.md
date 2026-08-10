# ADR 0029 — Email magic-link customer authentication

## Context

고객은 처음에는 익명 문의가 가능하지만 이후 Zendesk와 유사한 My Requests 포털이 필요하다. 비밀번호 저장을 첫 인증 방식으로 도입할 필요는 없다.

## Decision

CustomerAccount authentication begins with a DB-backed, single-use email magic link. Prefer Spring Security One-Time Token integration. The default token TTL is 15 minutes and configurable. Requests use enumeration-safe responses. Existing anonymous requests are not automatically claimed by email match; claim requires the existing request access token or a signed claim flow.

## Consequences

Outbound email becomes a prerequisite. Token lifecycle, session security, rate limiting, replay prevention, and security audit are mandatory. Password reset is not needed until password authentication exists.
