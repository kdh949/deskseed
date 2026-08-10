# Codex Brief 22 — Private Platform API v1 Ticket Operations

## Goal

Allow an IntegrationClient on the private network to create, read, and update tickets and add INTERNAL comments through a frozen v1 contract.

## Requirements

REQ-INT-001 through REQ-INT-004.

## In scope

- scoped API-key authentication and resource constraints.
- create/read/update ticket and add INTERNAL comment.
- Idempotency-Key state machine for commands.
- ETag/If-Match for updates.
- 60 rpm/client default rate limit and documented headers.
- private-network deployment/trusted proxy contract.
- RFC 9457 errors, OpenAPI contract tests, access/change audit.

## Out of scope

PUBLIC comments, OAuth, webhooks, admin APIs, internet-public deployment.

## Acceptance

INT-AUTH-001~004, IDEM-001~004, CONC-001, ACC-006; no staff impersonation or scope bypass.

## Required verification IDs

`PLAT-001`, `PLAT-002`, `INT-AUTH-001`, `INT-AUTH-002`, `INT-AUTH-003`, `INT-AUTH-004`, `IDEM-001`–`IDEM-004`, `CONC-001`, `ACC-006`.
