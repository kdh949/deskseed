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
