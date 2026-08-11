# Codex Brief 23 — Encrypted Raw Search Query Storage and Reveal

## Goal

Persist the exact executed search query for investigations without creating a plaintext logging channel.

## Requirements

REQ-AUD-004, REQ-AUD-005, REQ-AUD-009.

## In scope

- authenticated application-layer encryption port.
- ciphertext, nonce/metadata, key version, redacted query, HMAC fingerprint.
- raw-storage REQUIRED_ENCRYPTED startup validation.
- 30-day configurable raw ciphertext retention, as accepted by D-045/ADR 0033.
- privileged single-event reveal with reason, no-store, and self-audit.
- key rotation and retention tests.

## Out of scope

Bulk decryption, raw query in list projection, plaintext DB column, SIEM export of plaintext.

## Acceptance

ACC-003/004, AUD-002/003, RET-001/002/004, secret/log capture tests, and missing-key startup failure.

## Required verification IDs

`SEARCH-AUD-001`, `SEARCH-AUD-002`, `ACC-003`, `ACC-004`, `AUD-002`, `AUD-003`, `RET-001`, `RET-002`, `RET-004`, `ACC-007`.
