# Execution Backlog

각 항목은 vertical slice, migration, authorization, audit, negative tests, docs를 함께 완료한다. Gate IDs는 `docs/21-minimum-verification-gates.md`를 참조한다.

# Phase 0 — Bootstrap and core MVP

## DS-001 — Bootstrap repository

- Spring Initializr values from `docs/22-spring-initializr-and-dependencies.md`
- PostgreSQL/Flyway
- Modulith verification
- request/actor/correlation context
- OpenAPI directory and ADR rules

Done:

- ARCH-001 through ARCH-004 applicable parts pass
- clean clone starts with documented commands

## DS-010 — Anonymous customer request

- Customer
- Ticket
- first PUBLIC comment
- request token hash
- creation audit
- public-only lookup

Gates: TKT-001, TKT-002, CHG-001/002 basic

## DS-020 — Staff identity and organization

- StaffAccount roles
- login/session
- SupportGroup/GroupMembership
- active/inactive rules
- staff/security audit for lifecycle

## DS-030 — Agent queue and detail

- cursor ticket queue
- staff detail projection
- public/internal conversation
- requester card
- no N+1 baseline

Gates: TKT-002, PERF-001 baseline

## DS-040 — Reply, fields, and atomic update

- public reply/internal note
- status/priority
- one save/one audit
- field-aware conflict
- red conflict banner
- ticket-local audit timeline

Gates: TKT-006, CHG-001 through CHG-004

## DS-050 — Assignment and transfer

- group/assignee invariant
- same/cross-group transfer
- optional internal reason
- audit

Gates: TKT-003, TKT-004 transfer

## DS-060 — Child-ticket collaboration

- relation
- internal child
- parent read by relation
- open-child solve warning
- customer invisibility

Gates: TKT-004 child, TKT-005

## DS-070 — Minimum admin

- staff/group/membership UI
- customer access mode
- basic security settings
- setting audit

# Phase 1 — Portfolio Security & Audit Gate

Use `tasks/01-access-audit-foundation.md` and `tasks/02-audit-explorer.md`.

## DS-100 — Access Audit model and strict persistence

- AccessAuditEvent
- access action vocabulary
- strict failure semantics
- actor/session/interaction metadata
- runtime append-only privileges

Gates: ACC-001, ACC-002, ACC-005, ACC-007

## DS-110 — Search audit

- `SEARCH_EXECUTED`
- redaction policy
- HMAC fingerprint
- encrypted raw-query port and key version
- result count/filter capture
- `SEARCH_RESULT_OPENED` linkage

Gates: ACC-003, ACC-004, RET-004

## DS-120 — Security Auditor and Audit Explorer backend

- read-only role/scopes
- normalized activity query
- actor/ticket/action/field/date/source/outcome filters
- cursor
- canonical detail links
- projection rebuild

Gates: CHG-005, AUD-001, AUD-005, PERF-002

## DS-130 — Audit Explorer UI and protected reveal

- tabs/facets
- structured field diff
- search-to-view chain
- protected comment/search query reveal
- reason and no-store response
- self-audit

Gates: AUD-002, AUD-003

## DS-140 — Audit export and retention baseline

- export job/manifest/artifact
- field authorization
- short-lived URL
- request/download/deletion audit
- category retention job

Gates: AUD-004, RET-001 through RET-003

## DS-150 — Portfolio release evidence

- forensic demo scenarios
- audit threat model
- 1M activity query plan
- access write overhead measurement
- read/write failure injection
- README/architecture diagrams

Gates: AUD-006 baseline, PERF-002/003

# Phase 2 — Integration v1

Use `tasks/03-platform-api-foundation.md`, `04-external-reference.md`, `05-outbound-webhooks-and-sdk.md`.

## DS-200 — IntegrationClient and credential lifecycle

- client admin UI/API
- scoped key once-display/hash
- expiry/revoke/rotate overlap
- resource constraints
- last-used metadata
- lifecycle security audit

Gates: INT-AUTH-001, INT-AUTH-002

## DS-210 — Separate Platform API adapter

- `/api/v1/platform`
- auth and actor context
- create/read/update ticket
- internal comment
- RFC 9457
- cursor and rate-limit foundation
- OpenAPI

Gates: INT-AUTH-002 through 004, ACC-006

## DS-220 — Idempotency and ETag

- idempotency record/state machine
- canonical request hash
- replay/mismatch/concurrent duplicate
- crash-point tests
- ETag/If-Match

Gates: IDEM-001 through IDEM-004, CONC-001

## DS-230 — ExternalSystem and ExternalReference

- system definitions and host allowlist
- link CRUD
- safe deep link
- metadata constraints
- Agent sidebar panel
- Platform API projection

Gates: EXT-001 through EXT-004

## DS-240 — Outbound event and webhook delivery

- public event mapping
- subscription
- HMAC
- delivery/attempt states
- retry/dead-letter/replay
- SSRF controls
- admin delivery UI

Gates: WH-001 through WH-005

## DS-250 — Incremental export

- event sequence/cursor
- duplicate semantics
- tombstone
- consumer example

Gates: EXP-001, EXP-002

## DS-260 — Generated SDKs

- complete OpenAPI 3.1
- generator config
- TypeScript/Python/JVM packages
- examples
- contract diff
- test server smoke tests

Gates: SDK-001 through SDK-003

## DS-270 — n8n and Workato examples

- inbound signed webhook verification workflow
- idempotent event handling
- Platform API callback with credential handling
- replay demonstration
- no provider-specific backend code unless justified

# Phase 3 — Product depth

## DS-300 — Customer verified account

- email verification/magic link
- account linking
- customer reply
- recovery/revoke

## DS-320 — Fine-grained authorization

- group access matrix
- agent scope
- relation-based access
- integration field constraints
- denied-access audit

## DS-340 — SLA

- calendar
- policy version
- target instances
- intervals
- first/next reply

## DS-360 — Trigger and automation

- ordered evaluator
- normal command/audit pipeline
- dry run/version
- loop prevention
- scheduled cursor/lease

## DS-380 — Explore-like analytics

- ticket/update/access/SLA/automation facts
- backlog snapshots
- curated dashboards
- metric glossary

## DS-400 — App and Embed SDK

- sandboxed ticket-sidebar app
- manifest/scope/origin
- host bridge/server proxy
- short-lived embed token
- internal admin create/list/detail panel

## DS-420 — Measured scale evolution

- PostgreSQL search first
- durable Modulith publication
- selected Kafka externalization
- Elasticsearch/analytics store only after evidence
