# ADR 0041 — Typed EAV ticket configuration and immutable form versions

## Status

Accepted — 2026-08-18

## Context

Wave 1 lets an administrator configure fields, forms, tags, and display labels without a deployment. A per-field column on `ticket` would require an unsafe schema release for every administrator action, while an untyped JSON blob cannot keep field-level authorization, searchable values, stable option identities, and query plans explicit. Custom display labels must not destabilize the six persisted ticket status categories or the terminal `CLOSED` invariant.

Conditional form rules also cannot be executable expressions supplied by an administrator. The frontend may evaluate a descriptor for immediate feedback, but only a server projection can decide whether a submitted value was visible, editable, and required.

## Decision

- Persist ticket field values in one typed EAV row per `(ticket_id, field_definition_id)`. Exactly one type-specific nullable value column is set; a PostgreSQL CHECK constraint and the field definition type are both enforced. `SINGLE_SELECT` stores a stable option UUID, never a display label.
- Field machine keys are immutable. A definition that already has values cannot change type; a new definition plus an explicitly reviewed data migration is required.
- Ticket forms are drafted and then published as immutable versions. Tickets retain their selected form/version so a later edit cannot reinterpret historical data. Publishing rejects a dependency cycle or any input whose rule set can produce contradictory visibility, requiredness, or editability.
- Rules use Foundation's bounded versioned condition AST and feature-owned handlers. Raw expressions, scripts, and runtime code are not stored. The backend always re-evaluates submitted values and drops hidden/readonly values; hidden required fields cannot block a customer submission.
- Tags use a normalized, immutable canonical machine value plus a mutable display label. Tag association is a ticket command with the normal ticket audit and idempotent duplicate-add behavior.
- Custom status definitions map to a fixed `NEW`, `OPEN`, `PENDING`, `ON_HOLD`, `SOLVED`, or `CLOSED` category. The existing category column remains authoritative for state-machine/SLA compatibility; `status_definition_id` is additive. A `CLOSED` ticket remains terminal regardless of its displayed custom label.
- Field, tag, and custom-status configuration changes are ADMIN actions and create canonical Admin/Security audit entries in their transaction. Ticket value/tag/status changes create exactly one TicketAudit with ordered metadata-only events. Sensitive field values use protected audit policy and never enter ordinary logs, unprotected analytics, or customer projections.

## Alternatives

- JSONB-only values: rejected because field typing, selective indexes, and exact authorization would become implicit conventions.
- A new free-form status state machine: rejected because it breaks stable transition, SLA, view, and customer projection semantics.
- Frontend-only conditional visibility: rejected because a caller could submit a hidden or readonly value directly.
- Raw JavaScript/Kotlin expressions for form conditions: rejected because configuration must be data, not deployable code.

## Consequences

- PostgreSQL remains the first and only configuration/query store. Type-specific partial indexes and long-text GIN are introduced only with query evidence; no external search service is added.
- Field, option, tag, status, and form lifecycle APIs need optimistic version preconditions. Applied migrations are additive and repairs are forward migrations.
- Older clients continue to use the persisted category `status`; new projections also return the optional custom status. Deactivated definitions remain resolvable for historical tickets but cannot be newly selected.

## References

- D-002, D-005, D-007, D-008, D-010, D-012, D-033, D-036, D-054, D-056
- ADR 0002, 0005, 0007, 0008, 0010, 0012, 0024, 0025, 0039, 0040
- REQ-CFG-010, REQ-CFG-011, REQ-CFG-012, REQ-CFG-013
