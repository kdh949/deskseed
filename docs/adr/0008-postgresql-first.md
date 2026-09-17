# ADR 0008: PostgreSQL before Redis and Elasticsearch

- Status: Accepted
- Date: 2026-08-10

## Context

The MVP needs transactions, constraints, filtering, and basic search. Adding multiple data stores immediately would obscure data ownership and consistency behavior.

## Decision

Use PostgreSQL as the only application data store in the MVP. Add indexes only for concrete queries backed by `EXPLAIN (ANALYZE, BUFFERS)`. Introduce Elasticsearch as an asynchronous read model only after search requirements or measurements justify it. Introduce Redis only for a measured caching, coordination, or rate-limit need.

## Consequences

ADR 0049는 AI V1의 파생 공개 KB index를 별도 PostgreSQL+pgvector에 두는 좁은 예외다. Elasticsearch/OpenSearch 또는 다른 기능의 외부 store 승인은 아니다.

- One authoritative store keeps local setup and recovery simple.
- Later projections require versioned events, replay/rebuild procedures, and lag observability.
