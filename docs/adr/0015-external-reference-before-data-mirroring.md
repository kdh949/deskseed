# ADR 0015: Link external objects before mirroring external domain data

- Status: Accepted
- Date: 2026-08-10

## Context

Agents need order, payment, refund, member, store, or operations context. Copying entire external objects into Deskseed creates stale data, schema coupling, privacy duplication, and synchronization failure modes.

## Decision

Start with `ExternalSystem` and `ExternalReference`, containing stable external type/ID, display label, safe deep link, and an optional small allowlisted metadata snapshot. The external system remains source of truth. Deskseed does not fetch arbitrary reference URLs by default.

## Alternatives considered

- Mirror all external records: rejected until specific search/report/offline needs are measured.
- Store only an arbitrary URL: rejected because stable identity, host policy, and object type are needed.
- Custom ticket fields for every integration: rejected because they mix external identity with support workflow fields.

## Consequences

- Initial UI can provide useful deep links quickly.
- Live data requires later adapter/projection work.
- URL validation and host allowlists are security-critical.
