# ADR 0030 — All active agents can initially read all tickets

## Context

The first deployment should not require an administrator to configure a group permission matrix before agents can find and collaborate on tickets.

## Decision

The initial agent read scope is `ALL_TICKETS`. Every active agent can read every staff-visible ticket projection and search result. Future settings may restrict read scope to own groups, assigned tickets, or an explicit group matrix. Cross-group write is not granted by this ADR; the conservative default remains assignee or active ticket-group membership.

## Consequences

Queue and search queries are simpler initially, while access and search auditing becomes more important. Relation-based parent read grants remain in the model for future restrictive modes.
