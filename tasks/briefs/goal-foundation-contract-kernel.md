# Goal Foundation F1 — contract and workflow kernel

## Goal

Wave 1 lane owners can add their own Core API contract and typed rule descriptors without editing a generated API bundle or a central condition/action switch.

## Decision and source references

- Decision IDs: D-002, D-008, D-010, D-012, D-033, D-035, D-036, D-054
- Accepted ADRs: ADR 0002, 0008, 0010, 0012, 0024, 0025, 0039, 0040
- Requirements: REQ-FND-001, REQ-FND-002
- Verification: ARCH-001, ARCH-002 and contract/documentation gates

## Actor and source

- Actor: STAFF administrator for future descriptor-catalog reads; no new public route is delivered in F1.
- Source: future ADMIN_UI; descriptor resolution is server authoritative.
- Boundary: descriptors never carry handler code, secrets, or runtime ticket data.

## In scope

- deterministic Core OpenAPI fragment/bundle source relationship;
- Wave branch/migration/fragment ownership validation;
- safe versioned workflow AST and descriptor registry contracts;
- ADR, traceability, progress, and this task brief.

## Out of scope

- custom fields, tags, statuses, knowledge, webhooks, drafts, presence, and analytics behavior;
- transactional integration-event outbox (F2);
- rendered frontend contribution host and builder Storybook surface (F3; MCP is unavailable in this run).

## Invariants and failure semantics

- duplicate path/method, operation ID component ownership, or stale generated contract fails the contract gate.
- unknown, duplicate, oversized, or invalid rule descriptors fail closed.
- no external I/O is performed by a workflow handler contract.
- V35 is preserved; Foundation can only add V36–V39.

## Data and privacy

No production business data, body, secret, token, or audit record is added by F1. Descriptor metadata is intentionally non-executable and must not include secrets.

## Acceptance scenarios

1. Given an owned fragment with a duplicate Core path/method, bundling fails before any generated artifact is accepted.
2. Given valid empty reserved fragments, bundling produces the committed Core contract deterministically.
3. Given overlapping lane migration/fragment ownership, the ownership validator fails.
4. Given unknown or duplicate workflow descriptors, the server registry rejects them without a runtime fallback.

## Compatibility and migration

The Core API artifact remains semantically compatible with the current committed contract. This F1 change contains no Flyway migration; the Foundation reservation is V36–V39 because V35 is already applied on the frozen base.
