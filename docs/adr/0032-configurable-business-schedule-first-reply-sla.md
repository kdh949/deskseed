# ADR 0032 — Configurable business schedule for First Reply SLA

## Context

The first SLA metric needs a usable default but business days and hours differ between installations.

## Decision

Seed one Asia/Seoul schedule with Monday–Friday 09:00–18:00. Administrators can configure timezone, enabled weekdays including weekends, multiple daily intervals, holidays, and exceptions. First Reply SLA is the first metric and pauses in PENDING by default. Policies and schedules are versioned; historical target instances retain their applied versions.

## Consequences

No SLA is active until priority targets are supplied and the policy is activated. A deterministic business-time calculator and admin preview are required before UI countdowns.
