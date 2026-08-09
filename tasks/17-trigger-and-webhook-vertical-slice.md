# Codex Brief 17 — Ordered Trigger and Webhook Vertical Slice

## Goal

When an urgent unassigned ticket is created, an ordered trigger assigns a configured group and creates a signed outbound webhook intent without performing network I/O in the ticket transaction.

## Requirements

REQ-AUT-001, REQ-INT-006, REQ-INT-007, REQ-NOTIF-001.

## In scope

- versioned trigger definition/draft/activation.
- conditions: created, priority, group absent.
- action: set group, enqueue webhook event.
- ordered evaluation and provenance.
- dry-run against sample ticket.
- HMAC delivery, retry, dead letter, replay.
- n8n example consumer and Platform API callback with idempotency.

## Out of scope

Time-based automation, arbitrary scripts, public automated replies, complex template language.

## Required sources

`docs/18`, `20`, `45`, ADR 0024, integration event JSON Schema.

## Acceptance

AUT-001~008 and WH-001~005. Failure-injection proves committed ticket mutation is not rolled back by delivery failure.
