# ADR 0037 — Stable audit origin and projection snapshots

## Status

Accepted

## Context

Search-result origin validation and the unified Audit Explorer depend on facts that must
remain stable after key rotation and after mutable staff or ticket state changes. The
previous implementation derived a session fingerprint from the active search-encryption
key, rebuilt historical actor/group fields from current rows, and let projection writers
race a full rebuild.

## Decision

- Search-origin ownership uses a dedicated 32-byte session-fingerprint key. The stored
  format is the fixed `v1:<base64url HMAC-SHA256>` contract and does not include the
  rotating search-encryption key version.
- Canonical ticket/access audit rows capture the actor display and support group needed
  by the unified projection at insert time. V15 gives older rows the best value available
  at migration time; subsequent rebuilds copy only canonical snapshots.
- Incremental projection writers take a shared transaction advisory lock and rebuild
  takes the exclusive counterpart. Projection state stores its row count, and API status
  derives `REBUILDING` from lock availability rather than an unobservable in-transaction
  intermediate value.
- Audit detail returns at most 100 linked result-open rows and reports the full count and
  truncation state.

## Alternatives

- Reuse retained encryption keys for origin validation: rejected because validation
  would depend on encryption-key lifecycle and expand the comparison surface.
- Join mutable staff/ticket tables during rebuild: rejected because historical meaning
  would change after a rename or transfer.
- Add a queue or external projection store: rejected because PostgreSQL locking and
  snapshots meet the measured scope without a new runtime dependency.

## Consequences

- Production deployments must provide a second audit key and rotate it independently.
- Pre-V15 rows cannot recover facts that were already overwritten before migration; the
  backfill is explicitly the best migration-time snapshot.
- Rebuild briefly blocks canonical projection refresh, but canonical audit transactions
  remain consistent and resume without duplicate projection rows.
