# Goal Ticket Configuration A — contract freeze

## Goal

An ADMIN can safely define typed ticket fields, versioned forms, normalized tags, and category-compatible custom statuses; subsequent slices can connect the frozen contract to PostgreSQL and the authorized ticket command pipeline.

## Decision and source references

- Decision IDs: D-002, D-005, D-007, D-008, D-010, D-012, D-033, D-036, D-054, D-056
- Accepted ADRs: ADR 0002, 0005, 0007, 0008, 0010, 0012, 0024, 0025, 0039, 0040, 0041
- Requirements: REQ-CFG-010, REQ-CFG-011, REQ-CFG-012, REQ-CFG-013
- OpenAPI operations: `*TicketField*`, `*TicketForm*`, `*TicketTag*`, `*TicketStatus*`, `getAgentTicketConfiguration`, and `updateAgentTicketConfiguration`
- Verification: DOC-001, ARCH-001, ARCH-002, CFG-001 through CFG-005

## Actor and source

- Configuration actor/source: active ADMIN / `ADMIN_UI`; direct Agent, customer, Security Auditor, and integration-client access is denied.
- Runtime actors: customer and agent projections are separate; agent commands retain the current ticket write scope and expected-version/client-command identity.
- Resource constraints: a runtime field is visible/editable only in the server-projected form; Platform surface is deliberately not expanded in this first contract slice.

## In scope

- owned Core OpenAPI fragment, accepted data/status/rule decision, requirement reservation, evidence record, and deterministic generated bundle;
- all required admin and core runtime operation families as reviewer-visible contract shapes: #83 freezes implemented ADMIN fields/forms, #87 freezes implemented ADMIN tags/statuses, and customer/agent runtime shapes remain `BLUEPRINT_READY` until the final stacked implementation PR.
- V40 additive persistence foundation and ADMIN vertical slices: typed field definition/stable single-select option lifecycle, versioned form draft/preview/validation/publish/archive, normalized tag catalog, and category-compatible custom status catalog; `If-Match` precondition, CSRF/session authorization, Foundation `WorkflowCatalog` condition contribution, and atomic Admin/Security audit.

## Out of scope

- runtime field values/tags/status ticket command, customer/agent projection, Platform API additions, outbox payload additions, View/Search SQL contributors, and a customer form rendering change;
- generated Core bundle is never edited manually.

## Invariants and failure semantics

- field values are typed and one-per-field; type changes after values exist are rejected.
- publish rejects rule cycles and contradictory final effects; server re-evaluates a submitted form snapshot.
- tags/status/value mutations use one ticket command and one ticket audit; admin configuration writes are atomically admin-audited.
- `CLOSED` remains terminal and custom labels do not replace persisted status categories.

## Data and privacy

Sensitive field values remain out of ordinary logs, customer projection, default analytics, and non-protected audit payloads. The contract exposes no credential, session, comment body, or raw search content.

## Acceptance scenarios

1. Given a staff-only field, a customer form projection and validation response never reveal its definition, count, option, or value.
2. Given a form with a rule cycle or contradictory effects, validation reports structured issues and publish rejects it without a partial version.
3. Given a custom status label mapped to `CLOSED`, a ticket in that category remains non-mutable.
4. Given a stale definition/form/tag/status version, the write returns a precondition/conflict problem and does not overwrite current configuration.

## Compatibility and rollback

The Core API additions and V40 schema are additive. V40 is forward-only; rollback is an application rollback or a reviewed additive repair, never an applied Flyway edit. Configuration rows are lifecycle-deactivated rather than deleted.
