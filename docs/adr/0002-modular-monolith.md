# ADR 0002: Modular monolith before microservices

- Status: Accepted
- Date: 2026-08-10

## Context

Ticketing has multiple domains—customer identity, tickets, staff, settings, automation, SLA, reporting—but the initial team and deployment are small. Starting with services, Kafka, and distributed transactions would add operational failure modes before domain boundaries are understood.

## Decision

Build one Spring Boot deployable organized as Spring Modulith application modules. Modules expose narrow root-package APIs and keep persistence/framework details in `internal` packages. CI verifies module boundaries.

## Alternatives

- Package-by-layer monolith: easy initially, but encourages cross-domain coupling.
- Microservices from day one: adds network, consistency, deployment, and observability complexity without evidence.

## Consequences

- One database and local transactions give fast delivery and strong consistency for the MVP.
- Domain events may coordinate modules; external integration events and Kafka come only after durable outbox requirements exist.
- A future service extraction must be justified by ownership, scaling, deployment, or reliability pressure.
