# ADR 0014: Protect audited search queries with redaction, keyed fingerprinting, and optional encryption

- Status: Accepted
- Date: 2026-08-10

## Context

Internal audit needs to know what an agent searched, but search boxes can contain highly sensitive or accidentally pasted secrets. Plaintext indefinite storage creates a new breach surface.

## Decision

For each search, store:

- a redacted human-readable query;
- a keyed HMAC fingerprint over normalized query;
- optional authenticated ciphertext of the raw query when an external key is configured;
- filters, sort, result count, and interaction ID.

Raw query reveal requires a separate permission, a reason, and creates its own audit event. Raw ciphertext has shorter retention than metadata.

## Alternatives considered

- Plaintext query only: rejected for privacy and secret exposure.
- Hash only: rejected because auditors cannot understand the search.
- Do not log queries: rejected because it fails the stated investigation requirement.

## Consequences

- Key management and redaction policies are required.
- Normal explorer queries use redacted text/fingerprint.
- Full-text investigation is slower and privileged by design.
