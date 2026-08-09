# ADR 0011: Generic signed webhooks before bespoke automation connectors

- Status: Accepted
- Date: 2026-08-10

## Context

The product should integrate with n8n, Workato, and other automation platforms. Building a proprietary connector for each platform would duplicate delivery and security logic.

## Decision

First implement a generic outbound webhook subscription and delivery system with versioned events, HMAC signatures, timestamp/event IDs, retries, delivery logs, dead-letter state, and manual replay. Publish example n8n/Workato recipes after the contract stabilizes.

## Consequences

- One integration contract reaches many automation tools.
- Product-specific connectors remain possible later for better UX or marketplace distribution.
- Arbitrary user-supplied code execution is not part of the first automation engine.
