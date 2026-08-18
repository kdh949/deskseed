# Goal Wave 1 integration preview

## Goal

Combine the final Foundation F3, Ticket Configuration, Knowledge Base, Integrations/Webhooks, and Drafts/Presence heads on one isolated preview branch. Prove their committed contract bundle, additive migrations, module boundaries, and selected real-stack paths work together without merging `main`.

## Decision and source references

- Decision IDs: D-005, D-007, D-008, D-010, D-018, D-050, D-055, D-056, D-057
- Accepted ADRs: ADR 0040, 0041, 0042
- Requirements: REQ-FND-001 through REQ-FND-004, REQ-CFG-010 through REQ-CFG-013, REQ-KB-001 through REQ-KB-004, REQ-INT-001 through REQ-INT-006, REQ-COL-001, REQ-COL-002
- Verification gates: DOC-001, ARCH-001 through ARCH-004, API-001, MIG-001, FRONTEND-001, FRONTEND-004

## Integration contract

- Merge each lane head with a non-fast-forward commit on `feature/goal/wave1-integration-preview`; do not rebase, force push, merge `main`, or change a lane branch.
- Generated Core bundle, validation report, and manifest are regenerated only from their owned source fragments. A cross-lane OpenAPI component-name collision is resolved by retaining each lane-local component name; wire parameter names and HTTP paths remain unchanged.
- Additive migrations are retained in order: V40, V50–V52, V60–V63, and V70. No applied migration is edited, deleted, renumbered, or rolled back.
- Presence remains a single-instance advisory adapter under ADR 0042. Ticket mutation, audit, outbox, and optimistic concurrency stay independent of WebSocket delivery.

## In scope

- cross-lane merge, OpenAPI bundle/ownership/traceability validation, Kotlin compilation, selected focused integration tests, and a single-node Compose real-stack attempt;
- an integration-preview Draft PR containing exact merge order, migration/rollback safety, verification outcomes, and external CI/MCP gaps.

## Out of scope

- automatic merge, force push, deletion of branches/worktrees, applied migration rewrite, shared broker/cache, unapproved Integration HTTP surfaces, and multi-instance presence delivery;
- claiming Storybook MCP, approved non-loopback webhook receiver, performance, or two-agent browser validation when the evidence was not run.

## Rollback and compatibility

The preview branch is review-only. A preview merge commit can be reverted in reverse lane order; persistent migrations roll forward with a repair or operational feature disablement, never by deleting applied Flyway history. Existing clients retain wire-compatible ticket number path parameters; `TicketDraftTicketNumber` is an OpenAPI component identifier only and does not change the `ticketNumber` parameter on the wire.
