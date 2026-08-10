# ADR 0031 — Private-network Platform API v1

## Context

Shopping-mall, operations, and admin systems need stable machine-to-machine ticket access.

## Decision

Expose `/api/v1/platform/**` for private-network deployments using scoped API keys issued to IntegrationClient actors. v1 supports ticket create/read/update and INTERNAL comments. Commands require idempotency; updates use ETag/If-Match; every operation is attributed and audited. Default rate limit is 60 requests per minute per client and configurable.

## Consequences

OAuth, public-comment scope, webhooks, admin APIs, and internet-public deployment are excluded from this vertical slice. Network placement does not replace authentication or authorization.
