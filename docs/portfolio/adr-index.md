# Architecture Decision Record Index

The table lists architecture decisions and their implementation status for the portfolio release.

| ADR | Decision | Implementation |
|---|---|---|
| [0001](../adr/0001-kotlin-spring.md) | Kotlin and Spring Boot backend | Implemented |
| [0002](../adr/0002-modular-monolith.md) | Modular monolith before microservices | Implemented and architecture-tested |
| [0003](../adr/0003-first-comment-no-description.md) | Request body is the first PUBLIC comment | Implemented |
| [0004](../adr/0004-transfer-vs-child-ticket.md) | Transfer and child are different commands | Implemented |
| [0005](../adr/0005-audit-not-event-sourcing.md) | Append-only audit without Event Sourcing | Implemented |
| [0006](../adr/0006-anonymous-first-customer-identity.md) | Anonymous-first customer access | Customer registration, session authentication and explicit anonymous-ticket claim implemented |
| [0007](../adr/0007-field-aware-optimistic-concurrency.md) | Field-aware optimistic concurrency | Implemented |
| [0008](../adr/0008-postgresql-first.md) | PostgreSQL before Redis/Elasticsearch | PostgreSQL for core data; customer-auth throttling uses Redis under ADR 0043 |
| [0009](../adr/0009-single-instance-no-tenancy.md) | One installation is one organization | Implemented constraint |
| [0010](../adr/0010-local-events-before-kafka.md) | Local events before Kafka | Architecture constraint; Kafka absent |
| [0011](../adr/0011-generic-webhooks-before-connectors.md) | Generic webhook before connectors | Signed webhook subscriptions, delivery, retry and replay implemented |
| [0012](../adr/0012-separate-platform-api-surface.md) | Separate Platform API surface | Separate Platform API controllers and authentication implemented |
| [0013](../adr/0013-separate-audit-ledgers-unified-explorer.md) | Separate ledgers, unified projection | Implemented |
| [0014](../adr/0014-search-query-audit-privacy.md) | Redaction, keyed fingerprint and encryption | Implemented except routine representation; superseded there by 0036 |
| [0015](../adr/0015-external-reference-before-data-mirroring.md) | Link external objects before mirroring | ExternalReference management implemented; arbitrary data mirroring remains out of scope |
| [0016](../adr/0016-scoped-api-key-first-oauth-later.md) | Scoped API key before OAuth | Scoped API keys, client lifecycle and resource constraints implemented |
| [0017](../adr/0017-sandboxed-app-sdk-later.md) | Sandboxed Agent App SDK later | Blueprint only |
| [0018](../adr/0018-strict-audit-write-semantics.md) | Fail closed when required audit persistence fails | Implemented |
| [0019](../adr/0019-garden-ui-with-independent-brand.md) | Garden UI with Deskseed branding | Implemented |
| [0020](../adr/0020-three-panel-agent-workspace.md) | Three-panel ticket workspace | Implemented |
| [0021](../adr/0021-frontend-state-separation.md) | Separate server, URL, draft and layout state | Implemented |
| [0022](../adr/0022-ticketing-depth-after-core.md) | Views/tags/fields/macros/search after core | Views, tags, fields, macros and search implemented |
| [0023](../adr/0023-sla-snapshots-and-intervals.md) | SLA snapshots and interval facts | Business schedules and First Reply SLA implemented; Next Reply and Resolution remain planned |
| [0024](../adr/0024-ordered-automation-without-arbitrary-code.md) | Typed automation without arbitrary code | Typed triggers and scheduled automation implemented |
| [0025](../adr/0025-postgresql-projections-before-external-stores.md) | PostgreSQL projections before external stores | Implemented for current audit/search scope |
| [0026](../adr/0026-object-storage-attachment-pipeline.md) | Object-storage attachment pipeline | Private upload, scan, comment linking and authorized download implemented |
| [0027](../adr/0027-email-as-channel-adapter.md) | Email as Ticket/Comment channel adapter | Outbound mail implemented; inbound email ticket channel remains planned |
| [0028](../adr/0028-docker-compose-first-self-hosted.md) | Docker Compose first topology | Implemented |
| [0029](../adr/0029-email-magic-link-customer-authentication.md) | Customer email magic link | Passwordless magic-link access implemented; password accounts follow ADR 0042 |
| [0030](../adr/0030-all-agents-read-all-tickets-initially.md) | All active agents read staff-visible tickets | Implemented |
| [0031](../adr/0031-private-platform-api-v1.md) | Private-network Platform API v1 | Private-network Platform Ticket API implemented |
| [0032](../adr/0032-configurable-business-schedule-first-reply-sla.md) | Configurable First Reply SLA schedule | Versioned business schedules and First Reply SLA implemented |
| [0033](../adr/0033-required-encrypted-raw-search-query-audit.md) | Required encrypted exact search-query audit | Implemented |
| [0034](../adr/0034-mailpit-development-outbound-mail-adapter.md) | Mailpit development mail adapter | Mailpit development SMTP adapter and outbound delivery implemented |
| [0035](../adr/0035-staff-password-session-bootstrap.md) | Staff password session and first-admin bootstrap | Implemented |
| [0036](../adr/0036-content-free-routine-search-audit.md) | Content-free routine search audit representation | Implemented by V13 migration and regressions |
| [0037](../adr/0037-stable-audit-origin-and-projection-snapshots.md) | Stable search origin key and immutable projection snapshots | Implemented by V15 migration and regressions |
| [0038](../adr/0038-shared-organization-ticket-consistency-guard.md) | Shared organization and ticket consistency guard | Implemented by organization/ticket concurrency regression |

## Later decisions

| ADR | Decision |
| --- | --- |
| [0039](../adr/0039-agent-workspace-surface-and-headless-capability-preservation.md) | Agent Workspace surface and headless capability preservation |
| [0040](../adr/0040-additive-extension-foundation.md) | Additive extension foundation for parallel Wave 1 delivery |
| [0041](../adr/0041-ticket-configuration-typed-eav-and-versioned-forms.md) | Typed EAV ticket configuration and immutable form versions |
| [0042](../adr/0042-password-primary-customer-authentication.md) | Password-primary customer authentication with passwordless magic-link onboarding |
| [0043](../adr/0043-benchmark-gated-customer-authentication-throttling.md) | Redis-backed customer authentication throttling store |
| [0044](../adr/0044-customer-portal-frontend-surface-isolation.md) | Customer Portal frontend surface isolation |
| [0045](../adr/0045-structured-rich-text-comment-content.md) | Versioned structured rich-text comment content |
| [0046](../adr/0046-ticket-collaboration-notes-and-mentions.md) | Append-only ticket collaboration notes and bounded mention notifications |
| [0047](../adr/0047-private-load-observability-pipeline.md) | Private load observability pipeline |
| [0048](../adr/0048-personal-staging-private-observability.md) | Personal-staging private observability |
| [0049](../adr/0049-ai-v1-isolated-execution-boundary.md) | AI V1 격리 실행 경계 |

The concise `D-*` decision register remains in
[`docs/25-implementation-decision-register.md`](../25-implementation-decision-register.md).
When a decision changes, add a superseding ADR; do not rewrite this history to match a new
implementation.
