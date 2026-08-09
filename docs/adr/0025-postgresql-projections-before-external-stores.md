# ADR 0025 — PostgreSQL projections before external search/analytics stores

## Status
Accepted

## Context
Search, dashboards, and exports will need read-optimized data, but early scale does not justify Kafka, Elasticsearch, or a warehouse.

## Decision
Start with PostgreSQL queries, views, interval/projection tables, and materialized views. Record query plans and latency. Add external stores only after measured functional or performance limits and a rebuild/consistency plan.

## Alternatives
- Elasticsearch and warehouse from day one: rejected as operationally premature.
- Query operational entities for every historical metric forever: rejected for correctness/performance.

## Consequences
Every projection has a checkpoint/rebuild contract. PostgreSQL remains source of truth.
