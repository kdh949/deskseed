# ADR 0009: One installed instance represents one organization

- Status: Accepted
- Date: 2026-08-10

## Context

The product is intended to be self-hosted like an open-source help desk, not initially operated as a multi-company SaaS.

## Decision

Do not add `tenant_id`, workspace routing, tenant-aware caches, or tenant administration to every model. One deployment/database is one support organization.

## Consequences

- The schema and authorization model remain simpler.
- A future hosted SaaS offering is a significant product/architecture initiative, not a hidden boolean switch.
