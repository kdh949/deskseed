# Codex Brief 12 — Canonical Deskseed Frontend System

## Requirements

REQ-UI-001~006.

## In scope

The single `frontend/src/design-system/` root, one production `AgentShell`, reusable Queue/Workspace patterns, current API-backed routes, production-component fixtures, accessibility, architecture enforcement, and canonical visual regression baselines.

## Non-negotiable

- independent Deskseed branding.
- no Zendesk logo or copied proprietary asset.
- Garden Apache notices retained.
- no alternate UI root, compatibility export, token alias, fixture shell, or environment-selected Agent UI.
- reusable presentation is owned by `frontend/src/design-system/**`.

## Acceptance

- docs 29/40 and UI-001~005 gates.
- keyboard navigation.
- no color-only state.
- Queue and Ticket Workspace share the canonical AgentShell and visual grammar.
- API-backed `/agent/views/:viewKey` and `/agent/tickets/:ticketNumber` use the canonical production components.
- `npm run check:design-system-boundaries` passes in CI.
