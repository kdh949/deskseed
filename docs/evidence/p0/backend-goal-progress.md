# Backend P0 goal progress

## Scope and sequencing

This log tracks the four independently committed vertical slices requested by the P0 operating-blocker goal. All test data is synthetic. A `PASS` entry means the named command actually completed successfully; unrun or environment-blocked checks stay explicit.

| Slice | Contract frozen | Migration | Implementation | Targeted tests | Status | Commit |
|---|---|---|---|---|---|---|
| BE-P0-CUSTOMER-ACCESS | PASS | not required | PASS | PASS | PASS | `b7fdf57` |
| BE-P0-PUBLIC-ABUSE | PASS | V28 | PASS | PASS | PASS | `feat: 공개 문의 남용 제한 추가` |
| BE-P0-MAIL-OPERATIONS | PASS | V29 | PASS | PASS | PASS | `feat: 운영 메일 전송 관리 추가` |
| BE-P0-PRODUCTION-CONSISTENCY | pending | pending | pending | pending | PENDING | — |

## Discovery checkpoint — 2026-08-15

- Read contract, state, authorization, mail, SLA, verification-gate, runbook, decision, and relevant accepted ADR sources listed in the four task briefs.
- Existing foundations found: hashed request access tokens, customer follow-up replay/audit mechanics, durable outbound mail intents, protected content cipher, Mailpit tests, Platform idempotency, and First Reply local-event projection.
- P0 gaps confirmed: anonymous follow-up contract has no implementation; request/reply mail has no fragment grant and only magic-link bodies encrypt; agent ticketing directly dispatches mail; public create has no durable abuse limiter; production mail worker is profile-disabled; Platform checks `prod` rather than `production` and creation misses `TicketSubmitted`.
- Risk: P0 crosses Portal, Ticketing, Outbound Mail, Staff Access, Integration, and SLA boundaries. Each slice preserves the current transaction/outbox boundary and avoids adding a broker/cache.

## BE-P0-CUSTOMER-ACCESS checkpoint — 2026-08-15

- Contract frozen: `addCustomerRequestComment` in `api/core-api-outline-v1.yaml`; it now requires the header capability and stable `clientCommandId`, documents not-found/conflict/availability responses, and marks manual documentation review.
- Migration: none. The existing hashed `request_access_tokens` table and protected outbox columns satisfy this slice; no prior Flyway migration was edited.
- Implementation: Portal locks the active token in the follow-up transaction, Ticketing creates only a PUBLIC customer comment with one TicketAudit and replay descriptor, Portal issues a fresh grant and intent only for first execution. PENDING becomes OPEN; SOLVED/CLOSED reject. Public agent replies emit a Ticketing root fact; a synchronous Portal listener creates the fresh grant and protected mail intent without a Ticketing-to-Portal dependency.
- Privacy: request and reply links use the fragment form only. `RenderedMailSensitivity.PROTECTED` controls encryption for magic, request-received, and public-reply bodies; raw token stays out of normal body columns, audit metadata, errors, and logs.
- Red test evidence: before implementation, `PublicRequestIntegrationTest` had three new failures with `401` on the absent anonymous follow-up authorization path. During the first implementation run, replay attempted a new token/intent and correctly exposed an idempotency conflict; replay is now explicitly side-effect-free.
- PASS: `./gradlew --no-daemon test --tests dev.deskseed.portal.internal.PublicRequestIntegrationTest` (33 tests); `./gradlew --no-daemon test --tests dev.deskseed.architecture.ArchitectureTest --tests dev.deskseed.outboundmail.internal.OutboundMailPolicyTest --tests dev.deskseed.portal.internal.CustomerRequestPortalIntegrationTest --tests dev.deskseed.staffaccess.internal.AgentTicketCommandIntegrationTest`; `./gradlew --no-daemon test --tests dev.deskseed.staffaccess.internal.ApiDocumentationIntegrationTest`; `python3 scripts/test_api_documentation_quality.py`; `python3 scripts/validate_documentation.py`.
- Known risk/non-goal: no migration and no production SMTP operation change in this slice; Mailpit/worker production activation and admin mail operations remain BE-P0-MAIL-OPERATIONS. Full backend suite remains the final-slice gate.

## BE-P0-PUBLIC-ABUSE checkpoint — 2026-08-15

- Contract frozen: `createCustomerRequest` now documents the pre-creation PostgreSQL fixed-window boundary, `429 /problems/request-rate-limit-exceeded`, numeric `Retry-After`, `no-store`, and safe 503 behavior. `docs/39` records trusted forwarding and the independent limiter/ticket transaction boundary.
- Migration: additive `V28__public_request_rate_limit_buckets.sql` adds only `GLOBAL`, `CLIENT`, and `DESTINATION` HMAC-fingerprint buckets with an expiry cleanup index. No raw email, IP, forwarding header, ticket body, token, customer, or audit-reference column exists.
- Implementation: Portal resolves an effective address only from a configured trusted proxy; a non-trusted peer cannot select a client identity with `X-Forwarded-For`. Malformed, duplicate, or too-long trusted chains fail closed before bucket/customer/ticket work. The limiter atomically upserts global, client, then destination buckets in one `REQUIRES_NEW` transaction; a later ticket/audit/outbox rollback intentionally still consumes the anti-abuse budget. A bounded `SKIP LOCKED` job removes expired rows.
- Failure semantics: a bucket denial rolls back all bucket increments for that denied request and returns a safe 429. JDBC limiter failure becomes `503 /problems/request-rate-limit-unavailable` before any Customer/Ticket creation. Limiter configuration validates whole-second window, positive limits/batch, HMAC key size/Base64, CIDRs, and forwarded-hop bound at startup; production requires a provisioned fingerprint key and defaults to ignoring forwarded headers unless proxies are explicitly configured.
- PASS: `./gradlew --no-daemon test --tests dev.deskseed.portal.internal.PublicRequestIntegrationTest --tests dev.deskseed.portal.internal.PublicRequestRateLimitPropertiesTest --tests dev.deskseed.portal.internal.PublicRequestRateLimitIntegrationTest` (from `backend/`); the added integration class covers destination/client/global limits, trusted/untrusted forwarding, malformed/oversized chains, 8-way concurrent upserts with exactly two admissions, expiry cleanup, and injected persistence failure. `./gradlew --no-daemon test --tests dev.deskseed.staffaccess.internal.ApiDocumentationIntegrationTest` (from `backend/`); `python3 scripts/test_api_documentation_quality.py`; `python3 scripts/validate_documentation.py`; `git diff --check`.
- Execution note: an initial documentation test invocation from repository root failed because this repository stores the Gradle wrapper in `backend/`; the identical Gradle test was rerun there and passed. This was not a code or test failure.
- Known risk/non-goal: values are deliberately configurable deployment policy, not a generic shared limiter, Redis/CDN/WAF/CAPTCHA integration, or customer-account login throttle. PostgreSQL provides correctness; no production throughput measurement or capacity claim is made in this slice.
