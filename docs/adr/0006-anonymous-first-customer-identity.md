# ADR 0006: Anonymous-first customer access with an upgrade path

- Status: Accepted
- Date: 2026-08-10

## Context

The first intake channel is a low-friction web form, while the long-term product should support registered customers and administrator-controlled access modes.

## Decision

Separate the customer profile from authentication credentials. The first slice accepts name/email, stores an unverified Customer, and returns a high-entropy opaque request-view token whose hash alone is persisted. Later modes are `REGISTRATION_OPTIONAL` and `REGISTRATION_REQUIRED`, selected in admin settings.

## Consequences

- Reusing the same unverified email does not prove identity and must not reveal prior tickets.
- Production exposure requires verification, expiry/revocation, rate limits, abuse controls, and secure session exchange.
- Historical request linking happens only after verified ownership.
