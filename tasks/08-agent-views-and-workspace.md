# Codex Brief 08 — Agent Views and Ticket Workspace Read Path

## Requirements

REQ-UI-001~004, REQ-AUD-003.

## In scope

Default views, cursor table, ticket detail projection, three-panel layout, semantic view interaction.

## Acceptance

- unauthorized tickets omitted.
- meaningful ticket open writes one TICKET_VIEWED.
- background refetch deduplicated.
- visual snapshots at 1280/1440/1920.
## Accepted v0.6 authorization

All active agents can read every staff-visible ticket, view, and search result. Cross-group write remains group-or-assignee by default.
