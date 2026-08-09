# Codex Brief 19 — Attachments and Email Channel

## Goal

Add secure customer/staff attachments and one inbound/outbound email channel without changing the canonical Ticket/Comment model.

## Requirements

REQ-FILE-001, REQ-FILE-002, REQ-CHAN-001, REQ-NOTIF-001.

## Sequence

1. private object-storage attachment metadata/upload/scan/download.
2. attachment use in web-form and agent comments.
3. outbound notification outbox and delivery status.
4. inbound email adapter, deduplication and threading.
5. bounce/failure/admin health UI.
6. rich text and redaction only after plain-text/file path is stable.

## Required sources

`docs/48`, `49`, ADR 0026, ADR 0027, `docs/23`, `docs/52`.

## Non-negotiable

- no public bucket/object URL.
- unscanned files are not downloadable.
- content disposition and MIME are server-controlled.
- provider calls happen after commit.
- inbound provider retry cannot duplicate comments.
- reply token cannot enumerate tickets.
- raw email and file retention are explicit settings/policies.

## Exit evidence

Malware/quarantine, unauthorized download, duplicate inbound message, bounce, provider outage, and restore tests pass.
