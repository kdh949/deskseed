# Implementation Decision Register

This is a concise checklist for the owner and Codex. Accepted ADRs contain the rationale.

| ID | Decision | Status | Revisit trigger |
|---|---|---|---|
| D-001 | Kotlin/Spring Boot backend | accepted | product/team strategy changes |
| D-002 | modular monolith first | accepted | independent deploy/scale boundary measured |
| D-003 | ticket body is first public comment | accepted | no revisit planned |
| D-004 | transfer and child delegation are separate commands | accepted | no revisit planned |
| D-005 | current state + append-only audit, not Event Sourcing | accepted | audit cannot satisfy required reconstruction |
| D-006 | anonymous customer first, account later | accepted | production identity requirements |
| D-007 | field-aware optimistic concurrency | accepted | measured conflict/complexity failure |
| D-008 | PostgreSQL first for data/search/audit | accepted | measured limits |
| D-009 | one installation = one organization | accepted | SaaS decision |
| D-010 | local events before Kafka | accepted | independent durable consumers |
| D-011 | generic signed webhooks before product-specific connectors | accepted | provider UX/auth requires connector |
| D-012 | separate public Platform API adapter | accepted | no revisit planned |
| D-013 | separate audit ledgers, unified read projection | accepted | evidence shows unmanageable complexity |
| D-014 | search query redacted+HMAC+optional ciphertext | accepted | privacy/security review changes |
| D-015 | ExternalReference before external data mirroring | accepted | live data use case requires projection |
| D-016 | scoped API key first, OAuth later | accepted | third-party/delegated app requirement |
| D-017 | sandboxed Agent App SDK later, no backend plugin execution | accepted | isolated plugin runtime strategy approved |
| D-018 | strict audit persistence for sensitive reads/writes | accepted | explicit availability/compliance policy change |
| D-019 | Security Auditor is read-only by default | accepted | organization needs dual role with explicit grant |
| D-020 | audit view/reveal/export is audited | accepted | no revisit planned |
| D-021 | integration client cannot impersonate staff | accepted | verified delegated OAuth only |
| D-022 | Platform API writes require idempotency | accepted | no revisit planned |
| D-023 | external update uses ETag/If-Match | accepted | alternative standard chosen before v1 release |
| D-024 | webhook is at-least-once and duplicate-safe | accepted | no revisit planned |
| D-025 | SDK generated from OpenAPI | accepted | no revisit planned |
| D-026 | raw search query retention proposal 30 days | provisional | operator policy/legal review |
| D-027 | access metadata retention proposal 180 days | provisional | operator policy/storage review |
| D-028 | app ticket sidebar is first extension location | provisional | real extension use case |
| D-029 | embed SDK begins read/create, not full editor | provisional | external admin workflow evidence |

## How to use this register

Before a Codex task:

1. list the decision IDs it relies on;
2. state whether it changes any decision;
3. if yes, create/update ADR before code;
4. include verification gate IDs;
5. record the final decision and evidence in the PR.
