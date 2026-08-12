# ADR 0036 — Content-free routine search audit representation

## Status

Accepted

## Context

ADR 0014 required a human-readable redacted search query in the ordinary audit
projection. Release security review showed that a pattern-based redactor cannot make
arbitrary search text safe: health information, identifiers, opaque capabilities,
non-Latin free text and future secret formats can remain plaintext-equivalent even when
known email, card and credential patterns are masked.

The keyed fingerprint already supports equality/correlation, and authenticated
ciphertext with bounded retention already supports an individually authorized exact
reveal. Keeping a lossy content preview in every routine audit row therefore creates a
second disclosure surface without a reliable privacy boundary.

## Decision

This ADR supersedes ADR 0014 only for the ordinary human-readable representation.

- `search_audit_details.query_redacted` and the Audit Explorer projection store the
  input-independent marker `[PROTECTED]`.
- The keyed HMAC fingerprint over normalized input remains available for equality and
  correlation without revealing content.
- The exact query remains authenticated ciphertext with a key version and shorter
  retention. One-event reveal continues to require dedicated permission, a non-empty
  reason, the configured recent-authentication policy, `Cache-Control: no-store`, and a
  new self-audit event.
- A forward migration scrubs existing canonical and projection values, restores the
  append-only guard, and adds constraints that prevent content-bearing routine values
  from returning.
- Ordinary list/detail/export metadata must not infer or recreate query meaning from the
  marker. Investigators who need exact content use the protected reveal workflow.

ADR 0014's decisions to avoid plaintext, retain a keyed fingerprint, encrypt the exact
query and apply shorter ciphertext retention remain in force.

## Alternatives

- Expand regular-expression redaction: rejected because arbitrary language and future
  secret/health/identity formats cannot be exhaustively classified.
- Store a prefix, category or token summary: rejected because each remains content
  derived and can disclose rare or identifying text.
- Delete exact-query support entirely: rejected because the accepted investigation
  requirement is preserved by the bounded, reason-gated reveal path.

## Consequences

- Routine Audit Explorer rows are less informative; fingerprint correlation, filters,
  result count and linked ticket opens remain available without privileged reveal.
- Search-content investigation becomes deliberately slower and more visible because an
  exact reveal is separately authorized and audited.
- V13 performs a production data backfill. It updates logical rows but cannot promise
  immediate erasure from pre-migration backups, retained WAL or physical page remnants;
  backup retention, WAL lifecycle and storage reuse remain operator responsibilities.
- The column name `query_redacted` remains for additive schema compatibility even though
  its only permitted value is now a content-free marker.
