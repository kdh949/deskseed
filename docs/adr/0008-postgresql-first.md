# ADR 0008: PostgreSQL before Redis and Elasticsearch

- Status: Accepted
- Date: 2026-08-10

## Context

The MVP needs transactions, constraints, filtering, and basic search. Adding multiple data stores immediately would obscure data ownership and consistency behavior.

## Decision

Use PostgreSQL as the only application data store in the MVP. Add indexes only for concrete queries backed by `EXPLAIN (ANALYZE, BUFFERS)`. Introduce Elasticsearch as an asynchronous read model only after search requirements or measurements justify it. Introduce Redis only for a measured caching, coordination, or rate-limit need.

## Consequences

- One authoritative store keeps local setup and recovery simple.
- Later projections require versioned events, replay/rebuild procedures, and lag observability.
