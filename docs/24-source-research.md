# Source Research — Integration and Audit

Checked: 2026-08-10

This document records behavior and design references. It does not authorize copying source code. Product semantics are re-designed for Deskseed.

## 1. Zendesk Access Logs

Source:

- https://developer.zendesk.com/api-reference/ticketing/account-configuration/access_logs/
- https://support.zendesk.com/hc/en-us/articles/8261581851034-Monitoring-user-activity-with-access-logs

Observed ideas:

- read/write access can be queried separately from ticket modification history
- useful records include ticket/profile/search access
- fields include timestamp, actor/user, IP, authentication/client, HTTP method/path or query, result/outcome metadata
- access-log retention is distinct from account audit retention
- use cases include investigating what an agent searched and what records were accessed

Deskseed decision:

- create a separate AccessAuditEvent ledger
- record semantic UI view/search actions rather than treating every polling GET as a human view
- link `SEARCH_EXECUTED` to `SEARCH_RESULT_OPENED`
- make retention configurable rather than hard-code another product's period

## 2. Zendesk Audit Logs

Source:

- https://developer.zendesk.com/api-reference/ticketing/account-configuration/audit_logs/
- https://support.zendesk.com/hc/en-us/articles/4408885906586-Viewing-the-audit-log-for-changes

Observed ideas:

- account-level configuration changes are a different audit domain from ticket changes and access logs
- actor, time, IP, source, and change description are investigation-relevant
- privileged log access is itself a permission concern

Deskseed decision:

- separate AdminSecurityAuditEvent
- audit staff/role/group/settings/integration credential/webhook/retention changes
- audit Audit Explorer use, protected reveal, and export

## 3. Zendesk Ticket Audits

Source:

- https://developer.zendesk.com/api-reference/ticketing/tickets/ticket_audits/

Observed ideas:

- a ticket update can contain multiple ordered events
- comments and field changes belong to an immutable ticket history
- ticket audits are not the same thing as account audit/access logs

Deskseed decision:

- one command per ticket creates one TicketAudit and ordered TicketAuditEvents
- current Ticket row remains source of truth; this is not Event Sourcing
- global Audit Explorer normalizes the canonical ledger without replacing it

## 4. Zendesk Apps Framework and ticket sidebar

Sources:

- https://developer.zendesk.com/documentation/apps/app-developer-guide/making-api-requests-from-a-zendesk-app/
- https://developer.zendesk.com/api-reference/apps/apps-support-api/ticket_sidebar/
- https://developer.zendesk.com/documentation/apps/app-developer-guide/zaf-client-api/

Observed ideas:

- apps can run in ticket-side locations and access a controlled ticket context/event API
- app API requests can be proxied to third-party services
- secrets should not be placed in browser-visible code
- host/app interaction is asynchronous and capability-oriented

Deskseed decision:

- deep links and generated REST SDK first
- later sandboxed iframe Agent App SDK with manifest, scope, origin, nonce, host bridge
- named server-side connections for external secrets
- no arbitrary plugin code in the Spring process

## 5. Zendesk webhooks and incremental exports

Sources:

- https://developer.zendesk.com/documentation/webhooks/creating-and-monitoring-webhooks/
- https://developer.zendesk.com/api-reference/ticketing/ticket-management/incremental_exports/

Observed ideas:

- webhook delivery can be retried and duplicate; consumers need idempotency
- delivery attempts and failures need operational visibility
- incremental export is a separate mechanism for durable change consumption
- cursor-based export is preferable for continuous ingestion

Deskseed decision:

- stable event ID across retries, separate delivery/attempt IDs
- HMAC signature, retry/dead-letter/replay
- cursor export with duplicate-safe consumer behavior and tombstones

## 6. Zendesk Profiles and custom objects

Sources:

- https://developer.zendesk.com/api-reference/ticketing/profiles/profiles/
- https://developer.zendesk.com/api-reference/custom-data/custom-objects/custom-object-records/

Observed ideas:

- customer identity can be linked across systems
- external IDs are useful for idempotent cross-system records
- external business context needs stable identifiers and pagination

Deskseed decision:

- keep Customer separate from external identity
- use ExternalSystem and ExternalReference before data mirroring
- preserve external IDs as strings

## 7. Chatwoot APIs and signed webhooks

Sources:

- https://developers.chatwoot.com/api-reference/introduction
- https://developers.chatwoot.com/api-reference/webhooks/update-a-webhook-object
- https://github.com/chatwoot/chatwoot

Observed ideas:

- separate API surfaces can serve application users, platform management, and client/public use cases
- webhook signatures can include timestamp and raw body
- delivery IDs aid deduplication/traceability

Deskseed decision:

- separate customer/staff/admin/platform API surfaces
- HMAC over timestamp and raw body
- delivery ID distinct from business event ID

## 8. OWASP logging guidance

Sources:

- https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html
- https://owasp.org/Top10/2025/A09_2025-Security_Logging_and_Monitoring_Failures/

Observed ideas:

- logs can contain sensitive data and require masking/encryption/minimization
- log access should be restricted and itself monitored
- tampering, unauthorized modification/deletion, and injection must be addressed
- security-relevant events require consistent format and alerting

Deskseed decision:

- separate operational logs from canonical audit
- no secret/body dumping
- append-only DB controls and later external signed checkpoints
- log injection tests
- audit the auditor

## 9. IETF API standards

Sources:

- RFC 9457, Problem Details for HTTP APIs: https://www.rfc-editor.org/rfc/rfc9457
- RFC 9700, Best Current Practice for OAuth 2.0 Security: https://www.rfc-editor.org/rfc/rfc9700
- OpenAPI Specification: https://spec.openapis.org/oas/latest.html
- CloudEvents: https://cloudevents.io/

Deskseed decision:

- RFC 9457 for stable machine-readable API errors
- OpenAPI 3.1 as Platform API contract source
- OAuth only when needed and implemented according to current security BCP
- CloudEvents-inspired event envelope; do not claim conformance until verified

## 10. Current Spring/Kotlin baseline

Sources:

- Spring Boot reference: https://docs.spring.io/spring-boot/index.html
- Spring Boot Kotlin support: https://docs.spring.io/spring-boot/reference/features/kotlin.html
- Spring Modulith reference: https://docs.spring.io/spring-modulith/reference/
- Kotlin releases: https://kotlinlang.org/docs/releases.html

Deskseed decision as of 2026-08-10:

- Spring Boot 4.1.0
- Kotlin 2.4.10
- Java 21
- Spring MVC/JPA/PostgreSQL/Flyway
- Spring Modulith boundaries

Pin versions in the repository and re-check compatibility when bootstrapping.

## 11. Research limitations

- Zendesk plan availability and product UI can change; Deskseed requirements do not depend on matching a paid plan.
- This research supports behavior and architecture, not legal compliance claims.
- Open-source code licenses must be checked at the file/repository revision used. Do not copy AGPL code into a differently licensed project without a deliberate decision.
- Vendor behavior is inspiration, not a requirement to reproduce every detail.
## 2026-08-10 decision verification

- Spring Security One-Time Token Login: framework supports the two-step token request/consume flow, custom delivery handler for magic-link email, and recommends JDBC-backed storage for production.
- Zendesk schedules: business hours, weekdays, holidays, SLA policies, views, triggers, automations, and reporting share schedule semantics; multiple schedule assignment is a later capability.
- Zendesk Access Logs: read/write access records include query parameters, actor, IP, authorization type, client, and browser URL, supporting exact-search investigation requirements.
- Mailpit: development SMTP server, web UI, Docker image, and REST API suitable for email integration tests.
- OWASP Logging guidance: sensitive log content requires minimization and protection; Deskseed therefore stores required original search text as protected ciphertext, not plaintext operational logging.


### Access-log retention comparison

Zendesk documents a 90-day window for its agent/admin access log. Deskseed does not copy that duration for recoverable raw search text: because the ciphertext remains highly sensitive, the initial product default is 30 days, administrator-configurable, and explicitly non-legal. The metadata ledger may have a different retention category.
