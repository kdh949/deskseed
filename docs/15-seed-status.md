# Documentation Seed Status v0.3

## Purpose

This package is documentation-only. It does not claim that Spring Boot, database migrations, UI, access auditing, Platform API, webhooks, or SDKs are implemented.

## Fully specified at baseline level

- product and MVP boundaries
- ticket/comment/transfer/child semantics
- actor/source/correlation model
- modular monolith boundary
- ticket change audit
- access/search/admin audit separation
- Audit Explorer behavior
- search query privacy model
- IntegrationClient, scope, resource constraints
- Platform API rules
- idempotency and concurrency contract
- ExternalReference
- signed webhooks, delivery/replay
- incremental export
- generated SDK governance
- App/Embed SDK direction
- retention proposal
- minimum verification gates
- Spring Initializr values/dependencies
- Codex implementation briefs

## Provisional decisions requiring production confirmation

- exact audit/search/IP retention periods
- encryption key/KMS implementation
- privileged reveal reauthentication/MFA
- internet/network exposure of Platform API
- API key maximum expiry and rotation overlap
- audit external archive/WORM destination
- OAuth provider/authorization server
- legal hold and privacy deletion policy

## Not implemented by this artifact

- Kotlin/Spring project
- migrations/entities/controllers
- authentication
- frontend
- OpenAPI complete schema
- generated SDK packages
- test execution
- Docker deployment

## First implementation action

Use `docs/22-spring-initializr-and-dependencies.md`, then execute DS-001 and DS-010 from `docs/14-execution-backlog.md`. Codex tasks must use `CODEX_TASK_TEMPLATE.md` and cite verification gate IDs.
