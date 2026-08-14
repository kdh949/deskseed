# Backend P0 goal progress

## Scope and sequencing

This log tracks the four independently committed vertical slices requested by the P0 operating-blocker goal. All test data is synthetic. A `PASS` entry means the named command actually completed successfully; unrun or environment-blocked checks stay explicit.

| Slice | Contract frozen | Migration | Implementation | Targeted tests | Status | Commit |
|---|---|---|---|---|---|---|
| BE-P0-CUSTOMER-ACCESS | PASS | not required | PASS | PASS | PASS | pending |
| BE-P0-PUBLIC-ABUSE | pending | pending | pending | pending | PENDING | — |
| BE-P0-MAIL-OPERATIONS | pending | pending | pending | pending | PENDING | — |
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
