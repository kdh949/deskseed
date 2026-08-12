# Codex Brief 20 — Outbound Email Foundation with Mailpit

## Goal

Create a provider-neutral, durable outbound-email path and a Mailpit development adapter without implementing inbound email.

## Requirements

REQ-NOTIF-001, REQ-CHAN-003.

## In scope

- `OutboundMailPort` and provider-neutral message model.
- post-commit outbox/delivery attempt state machine.
- Mailpit in Docker Compose (`1025` SMTP, `8025` UI).
- templates for magic link, request received, and public agent reply.
- Mailpit API integration assertions.
- retry, idempotency, recipient/header safety, delivery audit.

## Out of scope

Production provider, inbound mailbox, bounce webhook, attachments, rich text.

## Acceptance

CHN-005 through CHN-009, plus duplicate-safe retries and proof that INTERNAL comments enqueue no customer email.

## Required verification IDs

`MAIL-001`, `MAIL-002`, `CHN-005`, `CHN-006`, `CHN-007`, `CHN-008`, `CHN-009`, `OPS-003`, `OPS-004`.

## Decision and source references

- Decision IDs: `D-002`, `D-005`, `D-010`, `D-038`, `D-039`, `D-040`, `D-046`.
- Accepted ADR: `0034-mailpit-development-outbound-mail-adapter.md`.
- Requirement IDs: `REQ-NOTIF-001`, `REQ-CHAN-003`.
- API operations: none; this slice adds no HTTP surface.
- Framework sources: Spring Boot 4.1 email auto-configuration, Spring scheduling, PostgreSQL `SKIP LOCKED`, Mailpit Docker/API docs.

## Actor and source

- Request received intent: `CUSTOMER` / `CUSTOMER_PORTAL`, preserving request/correlation/command IDs.
- Public agent reply intent: `STAFF` / `AGENT_UI`, preserving the ticket command context.
- Delivery attempts: `SYSTEM` / `SYSTEM_JOB`, correlated to the original intent.
- Manual retry seam: explicit `STAFF` or `SYSTEM` actor, bounded reason and command context; no HTTP authorization surface in this PR.

## Invariants and failure semantics

- Request/ticket/comment/audit/access-token and request-received intent commit or roll back together.
- PUBLIC agent comment/audit and its mail intent commit or roll back together.
- INTERNAL comments never create outbound mail intent rows.
- Worker claim commits before SMTP I/O; attempt completion commits in a later transaction.
- SMTP failure cannot roll back or replay the business command.
- `(idempotency_key)` is unique and an advisory transaction lock serializes duplicate enqueue calls.
- `FOR UPDATE SKIP LOCKED` claims one due row across workers; a lease recovers abandoned attempts.
- Stable `Message-ID` and intent ID remain unchanged across attempts. SMTP cannot prove exactly-once delivery after an ambiguous accept/ack loss; production provider selection must supply provider idempotency or reconciliation.
- Default attempt schedule: immediate, +1 minute, +5 minutes, +30 minutes, +2 hours; exhaustion is terminal `FAILED`.
- Manual retry reuses the same intent and grants one new configured retry cycle; it never creates a new comment.

## Data and privacy

- Intent retains recipient, sender, subject, rendered plain-text body, template key/version, ticket/comment/customer correlation and command context.
- Attempt/event rows retain status, bounded failure codes and timestamps, not SMTP response bodies or exception messages.
- Operational logs contain intent ID, attempt number and bounded failure code only; recipient, subject, link and body are excluded.
- This slice does not define retention deletion. Outbound content follows support-content policy until a reviewed category-specific job is added.

## Acceptance scenarios

- Given an outbox insert failure, request creation leaves no customer/ticket/comment/audit/token/mail row and sends nothing.
- Given SMTP failure after business commit, ticket/comment remain and the intent becomes retryable or terminal.
- Given a retryable failure followed by success, one intent and one delivered message exist with attempt history.
- Given INTERNAL then PUBLIC comments, only PUBLIC creates a customer mail intent.
- Given CR/LF or malformed recipients/headers, enqueue is rejected before persistence or transport.
- Given terminal failure, an explicit manual retry records actor/source/reason and reuses the intent.
- Given Mailpit, API inspection proves recipient, subject, magic link and absence of duplicate delivery.

## Compatibility and rollback

- Flyway `V18` is forward-only and additive.
- Rollback is application rollback with the tables retained; dropping delivery tables is not an automatic down migration.
- No OpenAPI compatibility change.
- Production provider, inbound email, bounce webhook, attachments and rich text remain out of scope.
