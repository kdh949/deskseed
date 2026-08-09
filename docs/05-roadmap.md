# Delivery Roadmap

## Strategy

먼저 상담 업무가 끝까지 동작하는 포트폴리오를 만든다. 이후 보안 감사와 외부 연동을 제품의 두 번째 기반으로 추가하고, 같은 코드베이스에서 SLA·통계·자동화·Kafka까지 깊게 확장한다.

각 단계는 “기술을 사용했다”가 아니라 사용자 시나리오, 실패 처리, 검증 근거를 완료 조건으로 가진다.

# Core MVP

## M0 — Foundation

- Kotlin/Spring Boot, Spring MVC
- PostgreSQL/Flyway
- modular monolith verification
- React/TypeScript
- request ID, UTC Clock, RFC 9457 errors
- actor/source/correlation/command context seam
- OpenAPI, ADR, AGENTS rules

Exit:

- module verification passes
- schema is migration-owned
- public/internal/audit projections are explicitly separated

## M1 — Anonymous request and customer view

- anonymous request form
- Customer create/reuse without trusting email ownership
- Ticket with first `PUBLIC` comment
- human ticket number
- hashed opaque request token
- public-only request view
- creation TicketAudit

Internet hardening deferred but documented:

- verification email
- rate limit/CAPTCHA
- token expiry/revoke/reissue
- spam handling

## M2 — Staff identity and workspace

- StaffAccount roles: `ADMIN`, `AGENT`, reserved `SECURITY_AUDITOR`
- session-based staff auth
- Group and GroupMembership
- ticket queue and detail
- public reply/internal note
- status/priority update
- customer card

## M3 — Atomic update, change audit, concurrency

- combined update command
- one command → one TicketAudit with ordered events
- field-aware optimistic concurrency
- 409 conflict contract and red banner
- ticket-local audit timeline
- DB-level append-only guard

## M4 — Assignment and transfer

- group/assignee invariant
- same-group and cross-group transfer
- optional internal transfer reason
- assignment history audit

## M5 — Internal child collaboration

- `PARENT_CHILD` relation
- internal child create
- parent ownership retained
- relationship-based parent read
- open-child warning on parent solve
- customer invisibility

## M6 — Minimum admin

- staff lifecycle
- group/membership management
- customer access mode
- basic security settings
- all setting changes audited

# Portfolio Release Gate

## R1 — Access audit foundation

This is required before calling the first portfolio release complete.

- semantic `TICKET_VIEWED`
- `CUSTOMER_PROFILE_VIEWED`
- `SEARCH_EXECUTED`
- `SEARCH_RESULT_OPENED`
- actor/session/IP/client/request metadata
- access event persistence before successful sensitive response
- no new view event for background refresh
- protected search query policy

## R2 — Unified Audit Explorer

- read-only `SECURITY_AUDITOR`
- tabs or filters for ticket changes, access/search, admin/security
- filter by actor, ticket, customer, event, field, source, date, outcome
- structured field before/after without opening each ticket
- optional inline immutable comment reveal with separate permission
- audit log view/reveal/export is itself audited
- CSV export with field-level authorization

## R3 — Portfolio evidence

- reproducible end-to-end demo
- architecture and threat diagrams
- OpenAPI contract
- audit integrity tests
- search/view forensic scenario
- query-plan baseline/improvement
- AI decision log and rejected alternatives

# Integration v1

## I1 — Integration client foundation

- `IntegrationClient`
- scoped API key shown once and hashed
- expiry, revoke, rotate with overlap
- resource constraints
- last-used metadata
- credential lifecycle security audit

## I2 — Platform REST API

- separate `/api/v1/platform` surface
- create/read/update ticket
- internal comment write by default
- external reference create/read
- cursor pagination
- RFC 9457 problems
- ETag/If-Match concurrency
- rate limit and 429/Retry-After
- every read/write attributed to IntegrationClient

## I3 — Idempotency

- required `Idempotency-Key` for external writes
- canonical request hash
- same request replay
- same key/different payload conflict
- retention policy
- crash/retry tests

## I4 — External references

- ExternalSystem configuration
- ticket/customer ↔ order/payment/refund/user/store/ops-case links
- safe deep links
- host allowlist
- limited metadata snapshot
- no server-side fetch by default

## I5 — Outbound webhooks

- versioned event envelope
- HMAC signature and secret rotation
- retry/backoff/jitter
- delivery/attempt log
- dead letter and manual replay
- duplicate delivery test
- n8n and Workato sample workflows

## I6 — SDK release

- OpenAPI 3.1 contract lint
- TypeScript SDK
- Python SDK
- JVM/Kotlin SDK
- Postman or equivalent examples
- semantic versioning
- breaking-change diff gate
- SDK smoke tests against a running test server

## I7 — Incremental export

- stable cursor
- ticket/update/audit event stream
- tombstone/redaction semantics
- duplicate-safe consumer examples
- export authorization and audit

# Post-MVP product depth

## P1 — Customer identity and production security

- email verification and magic link
- customer account
- account merge/link recovery
- MFA/SSO for privileged staff
- credential rotation UX
- abuse controls

## P2 — Fine-grained authorization

- group access matrix: `NONE`, `READ`, `READ_WRITE`
- agent scope: all, own groups, assigned
- relation-based parent visibility
- integration field/resource constraints
- permission explanation endpoint
- denied access audit

## P3 — SLA and operational timers

- BusinessSchedule and holidays
- versioned SlaPolicy
- SlaTargetInstance snapshots
- status and assignment intervals
- first/next reply
- requester wait and resolution
- child group OLA

## P4 — Trigger and automation

- ordered `all`/`any` conditions
- actions through normal ticket commands
- dry run and activation version
- execution depth/state fingerprint loop prevention
- scheduled automation cursor/lease
- action provenance in audit

## P5 — Explore-like analytics and exports

- ticket fact
- update/audit fact
- access/security fact
- status/assignment intervals
- SLA fact
- daily backlog snapshots
- automation/webhook fact
- curated dashboards before arbitrary report builder

## P6 — Search

- PostgreSQL full-text search with permission filtering
- subject/public/internal scope semantics
- audit search separate from ticket search
- measured relevance/latency limitations
- Elasticsearch only after evidence

## P7 — App and embed extension platform

### Agent App SDK

- sandboxed iframe
- locations: ticket sidebar, customer sidebar, top bar
- manifest and scopes
- host bridge
- server-side secret proxy
- origin and nonce validation

### Embed SDK

- short-lived signed embed token
- create/list/detail panel for external admin
- no long-lived browser API key
- delegated user attribution only with verifiable identity

## P8 — Files and channels

- S3-compatible attachments
- malware/content-type/size controls
- email channel and threading
- later chat/messaging channels

## P9 — Event-driven scale

- durable Modulith publication
- outbox recovery dashboards
- selected Kafka externalization
- idempotent consumers
- CQRS projections
- Redis only for measured coordination/cache need
- partition and archive plan for audit/analytics

## Release gate for every new technology

Before adding a major technology, record:

1. current user or operational problem
2. baseline measurement
3. simplest rejected alternative
4. expected benefit and failure modes
5. data consistency model
6. rollout and rollback plan
7. post-change measurement
