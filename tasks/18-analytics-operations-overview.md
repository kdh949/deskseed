# Codex Brief 18 — Explore-like Operations Overview

## Goal

Provide an accessible operations dashboard for created, solved, current backlog, aging, and first reply p50/p90/p95 with permission-safe drill-down.

## Requirements

REQ-ANL-001, REQ-EXP-001.

## In scope

- metric definition versions.
- ticket/update/interval facts required by selected metrics.
- daily backlog snapshot with backfill/checkpoint.
- PostgreSQL reporting projection/materialized view as measured.
- dashboard API and UI.
- accessible table equivalents.
- snapshot export with field authorization and expiry.

## Out of scope

Arbitrary drag-and-drop report builder, external warehouse, Kafka, employee productivity ranking.

## Required sources

`docs/16`, `46`, ADR 0025, `docs/23`, `docs/33`.

## Acceptance

ANA-001~008. Dashboard totals reconcile to deterministic canonical fixtures and drill-down never reveals inaccessible ticket IDs.
