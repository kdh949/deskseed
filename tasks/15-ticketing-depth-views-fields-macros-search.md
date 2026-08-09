# Codex Brief 15 — Ticketing Depth

## Goal

Add Zendesk-like configuration breadth without destabilizing the core command/audit model.

## Requirements

REQ-CFG-001~003, REQ-SRCH-001.

## Sequence

1. tags and default/shared views.
2. typed custom field definitions and values.
3. ticket forms and field visibility/validation.
4. macro preview/apply.
5. permission-aware PostgreSQL search.

Each item is a separate vertical-slice PR.

## Required sources

- `docs/47-ticketing-depth-views-fields-macros-search.md`
- `docs/33-authorization-permission-matrix.md`
- `docs/34-state-machines-command-event-catalog.md`
- `docs/45-trigger-automation-engine-implementation-spec.md`
- ADR 0022.

## Non-negotiable

- no arbitrary SQL or script condition.
- macro preview has no side effects.
- macro apply uses one normal ticket command/audit.
- search query is permission filtered and access audited.
- custom field type/definition changes are versioned/migrated safely.

## Exit evidence

- view result matches condition truth table.
- internal-field/search leakage tests.
- query plan for common view/search.
- macro preview and one-audit E2E.
