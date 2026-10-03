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

AI V1의 공개 KB vector index는 ADR 0049가 승인한 별도 PostgreSQL+pgvector 파생 projection이다. canonical KB와 공개성 판단은 계속 Deskseed Backend가 소유하며 AI index가 source of truth가 되지 않는다.
Every projection has a checkpoint/rebuild contract. PostgreSQL remains source of truth.
