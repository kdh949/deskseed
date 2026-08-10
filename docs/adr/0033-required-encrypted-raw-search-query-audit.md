# ADR 0033 — Required encrypted raw search-query audit

## Context

Security investigations require the exact search text, but search queries may contain highly sensitive customer or credential data.

## Decision

Store the exact query using authenticated encryption with a key outside the database, plus a redacted representation and keyed HMAC fingerprint. Raw storage is required when access audit is enabled; missing key/configuration fails startup rather than silently dropping the original. Default ciphertext retention is 30 days and configurable. Reveal is permissioned, reason-gated, no-store, non-bulk, and self-audited.

## Consequences

Application logs, analytics, webhooks, and standard exports must never contain the plaintext. Key rotation and retention tests are required.
