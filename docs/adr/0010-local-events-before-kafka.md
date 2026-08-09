# ADR 0010: Local domain events before Kafka

- Status: Accepted
- Date: 2026-08-10

## Context

The target learning path includes event-driven architecture and Kafka, but the MVP does not require distributed consumers.

## Decision

Publish immutable in-process domain events after domain state changes. Keep external side effects out of the database transaction. When multiple independently deployed consumers or reliability requirements emerge, add a transactional outbox and externalize versioned integration events to Kafka.

## Consequences

- Events are designed now without pretending the system is distributed.
- In-process listeners must not be mistaken for durable delivery.
- The outbox/Kafka milestone includes idempotency, replay, lag, dead letters, and observability—not merely a broker dependency.
