# ADR 0034 — Mailpit development outbound-mail adapter

## Context

Magic links and customer notifications require email before a production provider is selected.

## Decision

Use Mailpit in Docker Compose for local and CI email capture. Application code targets a provider-neutral OutboundMailPort and durable outbox. Integration tests may inspect Mailpit through its REST API. Production SMTP/API providers remain adapters selected later.

## Consequences

Mailpit is not the production provider and does not define inbound email architecture. Internal notes must never enqueue customer mail.
