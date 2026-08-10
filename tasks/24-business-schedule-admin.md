# Codex Brief 24 — Business Schedule Administration

## Goal

Let an administrator version and preview the schedule later used by SLA, views, triggers, and analytics.

## Requirements

REQ-SLA-002.

## In scope

- seed Asia/Seoul Monday–Friday 09:00–18:00 schedule.
- timezone, weekday/weekend enablement, multiple daily intervals.
- holidays and exceptional open/closed intervals.
- overlap/range validation and preview of business minutes.
- versioning, activation, admin UI, admin audit.

## Out of scope

SLA target instances, trigger conditions, multiple schedule assignment to tickets.

## Acceptance

SLA-001 fixtures across days/weekends/holidays/DST, version immutability, permission and audit tests.

## Required verification IDs

`SCHED-001`, `SCHED-002`, `SLA-001`, `AUD-003`, `OPS-005`.
