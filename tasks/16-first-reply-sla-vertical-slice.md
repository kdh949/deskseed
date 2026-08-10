# Codex Brief 16 — First Reply SLA Vertical Slice

## Goal

Show and report whether a customer ticket received its first qualifying public reply within a versioned business-hours target.

## Requirements

REQ-SLA-001.

## In scope

- BusinessSchedule with IANA timezone, weekly hours, exceptions.
- versioned SLA policy with ordered conditions and target by priority.
- First Reply target instance snapshot.
- public customer comment start and public staff reply stop.
- internal note does not satisfy target.
- due/at-risk/breached badge in ticket and view column.
- policy admin create/preview/activate.
- target facts for analytics.
- deterministic scanner/recovery.

## Out of scope

Next reply, requester wait, resolution, custom-field conditions, arbitrary policy builder, Kafka.

## Required sources

`docs/44`, ADR 0023, `docs/16`, `docs/52`.

## Acceptance

SLA-001, SLA-002, SLA-005, SLA-006, SLA-008 plus:

- policy change does not rewrite historical target.
- DST/holiday fixture even when default timezone is Asia/Seoul.
- browser countdown cannot change canonical state.
- no-policy tickets are distinguishable from achieved tickets.
## Accepted v0.6 defaults

Seed Asia/Seoul Monday–Friday 09:00–18:00, expose full weekday/weekend/interval/holiday admin editing, and pause First Reply in PENDING by default.
