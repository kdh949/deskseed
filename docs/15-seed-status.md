# Implementation Status v0.3

## Purpose

This repository now contains the documented product baseline plus an executable M0 backend bootstrap and M1 anonymous customer-request vertical slice. Completion remains evidence-based: implementation status means code, migration, and relevant tests exist; it does not imply that later roadmap capabilities are complete.

## Implemented baseline

- Kotlin 2.4 / Java 21 / Spring Boot 4.1 backend with a pinned Gradle Wrapper
- Spring MVC, Validation, JPA, PostgreSQL, Flyway, Spring Security, Actuator, and Spring Modulith dependencies
- PostgreSQL-backed Testcontainers integration tests and `ApplicationModules.verify()`
- Flyway-owned ticket, comment, customer, request-token, and immutable ticket-audit schema
- UTC Hibernate configuration, request/correlation identifiers, CORS allowlist, and actuator exposure allowlist
- production-profile seam for separate runtime and migration database credentials
- customer portal request submit and public-only lookup with an opaque stored-hash access token
- initial React customer portal and Docker Compose development setup

## Specified but not yet implemented

- staff authentication, organization/group management, and staff workspace
- replies, internal notes, transfer, child-ticket collaboration, and field-aware concurrency
- Access/Search Audit, Audit Explorer, protected reveal, export, and retention jobs
- complete public Platform API, scoped integration credentials, idempotency, and ETags
- ExternalReference, webhook delivery, incremental export, generated SDKs, and extension SDKs
- production credential/KMS provisioning, backup/restore runbooks, and performance evidence

## Current verification boundary

- M1 regression tests cover first-public-comment/no-description, public-only projection, token hashing, change-audit ordering, append-only audit triggers, and customer actor/request/correlation attribution.
- `V2__add_ticket_audit_command_context.sql` is an additive migration. It fills pre-existing audit rows with the bounded `legacy-migration` marker without updating canonical append-only rows, then requires context on new writes.
- Testcontainers requires a Docker-compatible container runtime. The repository does not use H2 as PostgreSQL proof.

## Next implementation action

Continue with DS-020 in `docs/14-execution-backlog.md` after recording a task brief from `CODEX_TASK_TEMPLATE.md`. Any expansion of the current customer-access token beyond the documented M1 limitation requires its own security/abuse-control task.
