# Architecture Decision Record Index

Every file below is an accepted design decision unless its own status says otherwise.
`Accepted` does **not** mean the corresponding product feature is implemented. The
release-state column prevents architecture intent from being mistaken for shipped code.

| ADR | Decision | Portfolio release state |
|---|---|---|
| [0001](../adr/0001-kotlin-spring.md) | Kotlin and Spring Boot backend | Implemented |
| [0002](../adr/0002-modular-monolith.md) | Modular monolith before microservices | Implemented and architecture-tested |
| [0003](../adr/0003-first-comment-no-description.md) | Request body is the first PUBLIC comment | Implemented |
| [0004](../adr/0004-transfer-vs-child-ticket.md) | Transfer and child are different commands | Implemented |
| [0005](../adr/0005-audit-not-event-sourcing.md) | Append-only audit without Event Sourcing | Implemented |
| [0006](../adr/0006-anonymous-first-customer-identity.md) | Anonymous-first customer access | Anonymous phase implemented; account upgrade planned |
| [0007](../adr/0007-field-aware-optimistic-concurrency.md) | Field-aware optimistic concurrency | Implemented |
| [0008](../adr/0008-postgresql-first.md) | PostgreSQL before Redis/Elasticsearch | Implemented constraint |
| [0009](../adr/0009-single-instance-no-tenancy.md) | One installation is one organization | Implemented constraint |
| [0010](../adr/0010-local-events-before-kafka.md) | Local events before Kafka | Architecture constraint; Kafka absent |
| [0011](../adr/0011-generic-webhooks-before-connectors.md) | Generic webhook before connectors | Blueprint only |
| [0012](../adr/0012-separate-platform-api-surface.md) | Separate Platform API surface | Contract only; no runtime surface |
| [0013](../adr/0013-separate-audit-ledgers-unified-explorer.md) | Separate ledgers, unified projection | Implemented |
| [0014](../adr/0014-search-query-audit-privacy.md) | Redaction, keyed fingerprint and encryption | Implemented except routine representation; superseded there by 0036 |
| [0015](../adr/0015-external-reference-before-data-mirroring.md) | Link external objects before mirroring | Blueprint only |
| [0016](../adr/0016-scoped-api-key-first-oauth-later.md) | Scoped API key before OAuth | Blueprint only |
| [0017](../adr/0017-sandboxed-app-sdk-later.md) | Sandboxed Agent App SDK later | Blueprint only |
| [0018](../adr/0018-strict-audit-write-semantics.md) | Fail closed when required audit persistence fails | Implemented |
| [0019](../adr/0019-garden-ui-with-independent-brand.md) | Garden UI with Deskseed branding | Implemented |
| [0020](../adr/0020-three-panel-agent-workspace.md) | Three-panel ticket workspace | Implemented |
| [0021](../adr/0021-frontend-state-separation.md) | Separate server, URL, draft and layout state | Implemented |
| [0022](../adr/0022-ticketing-depth-after-core.md) | Views/tags/fields/macros/search after core | Views/search implemented; tags/fields/macros planned |
| [0023](../adr/0023-sla-snapshots-and-intervals.md) | SLA snapshots and interval facts | Blueprint only |
| [0024](../adr/0024-ordered-automation-without-arbitrary-code.md) | Typed automation without arbitrary code | Blueprint only |
| [0025](../adr/0025-postgresql-projections-before-external-stores.md) | PostgreSQL projections before external stores | Implemented for current audit/search scope |
| [0026](../adr/0026-object-storage-attachment-pipeline.md) | Object-storage attachment pipeline | Blueprint only |
| [0027](../adr/0027-email-as-channel-adapter.md) | Email as Ticket/Comment channel adapter | Blueprint only |
| [0028](../adr/0028-docker-compose-first-self-hosted.md) | Docker Compose first topology | Implemented |
| [0029](../adr/0029-email-magic-link-customer-authentication.md) | Customer email magic link | Blueprint only |
| [0030](../adr/0030-all-agents-read-all-tickets-initially.md) | All active agents read staff-visible tickets | Implemented |
| [0031](../adr/0031-private-platform-api-v1.md) | Private-network Platform API v1 | Contract only; no runtime surface |
| [0032](../adr/0032-configurable-business-schedule-first-reply-sla.md) | Configurable First Reply SLA schedule | Blueprint only |
| [0033](../adr/0033-required-encrypted-raw-search-query-audit.md) | Required encrypted exact search-query audit | Implemented |
| [0034](../adr/0034-mailpit-development-outbound-mail-adapter.md) | Mailpit development mail adapter | Blueprint only |
| [0035](../adr/0035-staff-password-session-bootstrap.md) | Staff password session and first-admin bootstrap | Implemented |
| [0036](../adr/0036-content-free-routine-search-audit.md) | Content-free routine search audit representation | Implemented by V13 migration and regressions |

The concise `D-*` decision register remains in
[`docs/25-implementation-decision-register.md`](../25-implementation-decision-register.md).
When a decision changes, add a superseding ADR; do not rewrite this history to match a new
implementation.
