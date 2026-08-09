# ADR 0007: Field-aware optimistic concurrency

- Status: Accepted
- Date: 2026-08-10

## Context

Agents may edit the same ticket concurrently. Pure last-write-wins silently loses work, while pessimistic locks obstruct normal collaboration.

## Decision

Use an optimistic ticket version and field-aware conflict detection. A stale request may merge when its changed fields do not overlap changes since `expectedVersion`. A stale write to the same field returns HTTP 409 with `conflictingFields` and the current version. The UI displays a red conflict banner at the top of the field sidebar.

Comments are append-only commands and should not conflict merely because a metadata field changed.

## Consequences

- M3 must store enough audit/version metadata to identify fields changed since a version.
- The API must send an explicit change set, not a blindly serialized ticket object.
- The UI needs reload, keep-local, and retry affordances.
