# ADR 0012: Separate the public Platform API from staff and customer APIs

- Status: Accepted
- Date: 2026-08-10

## Context

External shopping, operations, and admin systems need stable machine-to-machine integration. Reusing staff controllers would expose UI-shaped DTOs, session assumptions, broad permissions, and unstable internal behavior.

## Decision

Create a separate `/api/v1/platform/**` adapter. It invokes shared application commands but owns external DTOs, scoped machine authentication, resource constraints, idempotency, ETag concurrency, rate limits, OpenAPI compatibility, and integration-specific audit attribution.

## Alternatives considered

- Reuse `/agent/**` with API tokens: rejected because authorization and compatibility differ.
- Let integrations access the database: rejected because it bypasses invariants, audit, and migrations.
- Build provider-specific endpoints first: rejected because a stable general contract should precede connectors.

## Consequences

- Some DTO/controller duplication is intentional.
- Domain logic remains shared through application services.
- Public compatibility becomes a product responsibility.
- Generated SDKs can depend on a clear external contract.
