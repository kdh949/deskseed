# Task Brief — First Reply SLA with Configurable Schedule

## Goal and actor

An ADMIN versions, previews, orders, and activates First Reply SLA policies. A human AGENT sees the resulting target on customer tickets and in Views; authorized staff can read the reconciled SLA summary.

## Traceability

- Requirements: `REQ-SLA-001`, `REQ-SLA-003`
- Decisions: `D-005`, `D-008`, `D-010`, `D-030`, `D-034`, `D-036`, `D-044`
- ADRs: `0023`, `0032`
- Screens: ADM-006, AGT-003, AGT-004, RPT-002
- Gates: `SLA-001`, `SLA-002`, `SLA-004`, `SLA-005`, `SLA-006`, `SLA-008`, `SLA-009`, `ANA-004`, `UI-001`–`UI-005`

## User scenarios

1. An ADMIN creates a draft policy version with an exact schedule reference, ordered position, optional allowlisted group/channel match, priority targets, and pause statuses.
2. The ADMIN previews policy selection and due time without mutating tickets, then activates a version. Creation and activation append canonical admin/security audit events in the same transaction.
3. The first PUBLIC CUSTOMER comment on an eligible customer request applies the first matching active policy version that has a target for the ticket priority. The target snapshots policy/version, schedule/version, target minutes, pause statuses, and calculation version.
4. A PUBLIC comment authored by an authenticated human STAFF actor achieves the target once. INTERNAL, SYSTEM, AUTOMATION, and INTEGRATION_CLIENT comments do not achieve it.
5. Entering a configured pause status (default `PENDING`) closes the active clock segment and stores remaining business minutes. Leaving it resumes with a newly calculated due instant. Solving before a qualifying reply cancels the target.
6. A bounded scanner materializes overdue ACTIVE targets as BREACHED. Reads treat an ACTIVE target whose due instant has passed as effectively breached before scanner materialization.
7. Ticket detail and Views show the same effective SLA state. Analytics reconciles target facts and keeps `NO_POLICY` separate from achieved/breached outcomes.

## Policy and matching contract

- Metric is fixed to `FIRST_REPLY`; custom metrics and arbitrary condition expressions are rejected/absent.
- Conditions are limited to optional exact `groupId` and optional exact ticket `channel`.
- Active versions are evaluated by ascending `position`, then policy UUID as a stable tie-breaker.
- A policy version with every priority target null cannot be activated.
- A matching policy with no target for the ticket's current priority is skipped; if no active version supplies a target, the ticket receives an analytics fact with `NO_POLICY` and no target instance.
- A policy version resolves and stores the schedule's active immutable version at version creation. Later schedule/policy edits never rewrite an existing target.
- Policy edits append a new immutable version. Roots and activation pointers are mutable; version, target event, and activation history rows cannot be updated or deleted by the runtime role.

## Time and state contract

```text
NO_POLICY (fact only)

ACTIVE ──enter pause status──> PAUSED ──leave pause status──> ACTIVE
   │                                │
   ├──qualifying staff PUBLIC───────┴──> ACHIEVED
   ├──scanner/read effective time──────> BREACHED
   └──SOLVED/CLOSED before reply────────> CANCELLED
```

- Start instant is the persisted first PUBLIC CUSTOMER comment instant, including the first comment of a web request.
- Business-time addition starts at the instant if open, otherwise at the next opening. Weekly intervals, exceptions, timezone, and DST use the recorded schedule version.
- DST policy is `GAP_SHIFT_FORWARD_OVERLAP_INCLUDE_BOTH`: nonexistent local boundaries shift forward by the gap; an overlap uses the earlier offset for opening and later offset for closing.
- Entering pause at `p` stores `targetMinutes - elapsedBusinessMinutes(start/resume, p)` (never below zero) and clears `dueAt`. Leaving at `r` sets `dueAt = addBusinessMinutes(r, remaining)`.
- A target due at or before `now` is effectively BREACHED. Achievement and breach use a row lock and terminal-state predicate; the winner is final and replay-safe.
- `ACHIEVED`, `BREACHED`, and `CANCELLED` are terminal. A later policy edit, priority change, internal note, or scanner restart does not rewrite them.

## API freeze

- ADMIN: list/get/create root, create immutable version, list versions, preview selection/due time, activate version.
- Agent Views: optional `slaState` filter; ticket summaries expose metric, effective state, due instant, target minutes, and snapshot version labels.
- Analytics: First Reply summary and drill-down use the same authorized ticket population and calculation version.
- All admin mutations require staff session, CSRF, expected-actor guard, `If-Match`, ADMIN authorization, and atomic admin/security audit.

## Data and privacy boundaries

- Public/customer APIs receive no SLA target, policy, schedule, interval, scanner, or analytics fields.
- SLA target/event/fact rows contain identifiers, enums, instants, and numeric durations only. They do not duplicate subject, comment body, customer contact data, session cookies, or secrets.
- Views and analytics are staff-only. Admin policy definitions are ADMIN-only.
- Background reads and scanner execution never emit semantic `TICKET_VIEWED`.

## Transaction, concurrency, and failure semantics

- Ticket/comment/audit persistence and SLA start/achieve/pause/cancel/fact projection commit or roll back together.
- Policy version creation and activation history/admin audit commit or roll back together.
- Policy activation uses root aggregate ETag. A stale version returns 412 without partial mutation.
- Target uniqueness is `(ticket_id, metric)` for this first-reply slice. Creation uses a unique constraint and idempotent insert semantics.
- Scanner claims a bounded indexed batch with `FOR UPDATE SKIP LOCKED`, transitions only ACTIVE rows, and records an observable checkpoint/run summary. Restart scans remaining ACTIVE overdue rows.
- No outbound network call occurs in these transactions.

## Verification fixtures

| Fixture | Start / event | Expected |
|---|---|---|
| Seoul outside hours | Fri 18:30 KST, 60m | Mon 10:00 KST |
| Seoul weekend | Sat closed, 60m | Mon 10:00 KST |
| Weekend open | Sat 09:00–12:00, start 10:00, 90m | Sat 11:30 |
| Holiday closed | Monday closed, Friday 17:30, 60m | Tuesday 09:30 |
| Exceptional open | Sunday 10:00–12:00, start 10:30, 60m | Sunday 11:30 |
| Multiple intervals | 09:00–12:00 / 13:00–18:00, start 11:30, 90m | 14:00 |
| DST spring gap | America/New_York gap boundary | shifted forward deterministically |
| DST fall overlap | America/New_York overlap boundary | opening earlier / closing later offset |
| Pending pause | 30m consumed, pause 2h, 30m remain | resumes with 30 business minutes |
| Internal note | INTERNAL human staff comment | target unchanged |
| No policy | no matching active target | `NO_POLICY`, never achieved |

## Non-goals

- Next Reply, Resolution, Requester Wait, OLA, SLA target cycles, multiple schedule-to-ticket assignment.
- Trigger/automation, Kafka/Redis/external search, arbitrary custom condition expressions, custom-field matching.
- Historical target recalculation UI or destructive rollback migrations.

## Rollback and compatibility

- Flyway migration is forward-only and additive. Rollback disables policy activation/UI and scanner, preserving immutable target history.
- Core staff responses receive additive nullable SLA fields and an additive optional filter. Customer and Platform API contracts are unchanged.
