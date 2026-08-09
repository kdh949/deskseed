# ADR 0023 — SLA uses policy snapshots and interval facts

## Status
Accepted

## Context
Current ticket rows cannot reproduce past wait times, business-hour calculations, or results after policies change.

## Decision
Version business schedules and SLA policies; create target instances that snapshot applied policy/target values; maintain rebuildable status/assignment/reply intervals; use an injected deterministic clock.

## Alternatives
- Calculate all SLA from current ticket rows: historically incorrect.
- Store only final SLA booleans: not explainable or recalculable.
- Event Sourcing: unnecessary for the whole product.

## Consequences
SLA has explicit calculation versions, rebuild tests, DST/holiday tests, and separate child OLA semantics.
