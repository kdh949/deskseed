# Documentation and Implementation Status v0.6

## Purpose

This repository combines the v0.6 documentation seed with an executable M0 backend bootstrap, M1 anonymous customer-request vertical slice, and M2 staff-authentication/organization vertical slice. Documentation status and implementation status are deliberately separate: a documented contract is not evidence that its runtime behavior exists.

## Implemented baseline

- Kotlin 2.4 / Java 21 / Spring Boot 4.1 backend with a pinned Gradle Wrapper.
- Spring MVC, Validation, JPA, PostgreSQL, Flyway, Spring Security, Actuator, and Spring Modulith dependencies.
- PostgreSQL-backed Testcontainers integration tests and `ApplicationModules.verify()`.
- Flyway-owned ticket, comment, customer, request-token, and immutable ticket-audit schema.
- UTC Hibernate configuration, request/correlation identifiers, CORS allowlist, and actuator exposure allowlist.
- production-profile seam for separate runtime and migration database credentials.
- customer portal request submit and public-only lookup with an opaque stored-hash access token.
- initial React customer portal and Docker Compose development setup.
- Garden 9.15.7 primitives behind the canonical `frontend/src/design-system` API, one Deskseed Agent Shell, and keyboard-focus regression tests.
- BCrypt staff login with bounded server sessions, CSRF, PostgreSQL throttling, active-account revalidation, and password-file first-ADMIN bootstrap.
- ADMIN-only staff/group/membership APIs and UI with API/method authorization, direct-route guards, current-assignment protection, and transactional admin/security audit.
- pinned frontend lockfile with Prettier, ESLint, Vitest, strict type checks, and production build gates.
- CI parity for documentation validation, backend tests, frontend quality gates, and an isolated Docker Compose health smoke.

## Implementation-ready core scope

The following v0.6 contracts are `IMPLEMENTATION_READY`: implementation can begin using the stated API, data, authorization, UI, and verification boundaries, but code may still be absent.

- repository/architecture baseline and anonymous web request/public-only customer detail.
- staff accounts, groups, Agent Views/workspace, and PUBLIC/INTERNAL conversations.
- atomic ticket command, change audit, field-aware concurrency, transfer, and internal child-ticket collaboration.
- minimum admin settings, access/search audit, and Unified Audit Explorer.
- IntegrationClient foundation, idempotency/ETag/external-reference semantics.
- independently branded Zendesk-inspired frontend information architecture, interaction states, accessibility, and visual acceptance.

## Blueprint-ready later scope

Detailed implementation specifications exist for Platform API/webhooks/exports/SDKs, views/tags/custom fields/forms/macros/search, SLA/OLA, automation, analytics, attachments/rich text/redaction, email channels, and Agent App/Embed SDKs. Before coding one, freeze its bounded vertical slice using `docs/39-api-contract-freeze-plan.md` and the matching task brief.

## Current verification boundary

- M1 regression tests cover first-public-comment/no-description, public-only projection, token hashing, change-audit ordering, append-only audit triggers, and customer actor/request/correlation attribution.
- M2 regression tests cover generic and disabled login failure, lockout, logout/expiry, session/security headers, ADMIN API and method authorization, duplicate membership, audit rollback, direct admin URL denial, and CSRF-protected browser CRUD.
- `V2__add_ticket_audit_command_context.sql` is additive. It preserves existing canonical audit rows with the bounded `legacy-migration` marker and requires context on new writes without bypassing append-only triggers.
- Testcontainers requires a Docker-compatible container runtime. The repository does not use H2 as PostgreSQL proof.
- `scripts/validate_documentation.py` verifies documentation structure and machine-readable contracts; it does not verify Kotlin/React runtime behavior.
- `scripts/compose-smoke.sh` uses a unique Compose project name and removes only the disposable containers and volume it creates.

## Not yet implemented

- ticket workspace queues, replies, internal notes, transfer, child-ticket collaboration, and field-aware concurrency.
- Access/Search Audit, Audit Explorer, protected reveal, export, retention jobs, and Platform API runtime surfaces.
- scoped integration credentials, ETags, ExternalReference, webhook delivery, incremental export, generated SDKs, and extension SDKs.
- production credential/KMS provisioning, backup/restore runbooks, and performance evidence.

## Provisional production decisions

- retention periods, legal hold, employee-monitoring policy, and search-query ciphertext/KMS policy.
- IdP, SSO, MFA, public/private Platform API exposure, final product name/license, and storage/mail/webhook providers.
- scale thresholds for Kafka, Elasticsearch/OpenSearch, Redis, and Kubernetes.

## Next implementation action

Continue with the next `IMPLEMENTATION_READY` vertical slice in `docs/14-execution-backlog.md` only after creating a task brief from `CODEX_TASK_TEMPLATE.md`. Each task must cite at least one `REQ-*` ID and its verification gate.

## 2026-08-10 decision overlay

Newly implementation-ready: customer email magic link/My Requests, all-agent global ticket read, private Platform API v1 ticket operations, encrypted raw-search preservation, configurable business schedule, First Reply SLA, and Mailpit outbound email foundation. See docs 53–54.
