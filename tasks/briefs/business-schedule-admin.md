# Task Brief — Business Schedule Administration

## Goal

An authenticated `ADMIN` can create, version, activate, and preview a business schedule without rewriting historical definitions.

## Decision and source references

- Requirement: `REQ-SLA-002`
- Decisions: `D-001`, `D-002`, `D-005`, `D-008`, `D-013`, `D-018`, `D-030`, `D-032`, `D-034`, `D-044`
- ADRs: `0023-sla-snapshots-and-intervals`, `0032-configurable-business-schedule-first-reply-sla`
- Screen: ADM-006 extended with `/admin/business-rules/schedules`
- OpenAPI operations: `listBusinessSchedules`, `createBusinessSchedule`, `getBusinessSchedule`, `listBusinessScheduleVersions`, `createBusinessScheduleVersion`, `activateBusinessScheduleVersion`, `previewBusinessSchedule`
- Gates: `SCHED-001`, `SCHED-002`, `SLA-001`, `AUD-003`, `ARCH-001`, `ARCH-002`, `UI-002`, `UI-004`, `UI-005`, `OPS-005`

## Actor and source

- Actor: authenticated active `STAFF` with `ROLE_ADMIN`
- Source: `ADMIN_UI`
- Resource constraints: none beyond the single-organization installation boundary
- Commands preserve server-issued request/correlation IDs; the browser expected-staff guard and CSRF contract apply to mutations.
- Agent, Security Auditor, customer, and machine identities cannot access this admin surface.

## Product and UX contract

- Route: `/admin/business-rules/schedules`
- UI follows Deskseed admin information architecture and uses no Zendesk assets.
- Loading, empty, error, denied, validation, saving, activation, and stale-version states are explicit.
- Weekdays are keyboard-operable controls with textual enabled/closed state. Interval and exception errors are associated with their fields.
- A draft can be previewed before it creates a version.
- Deterministic screenshot fixture: full schedule editor at a 1440 px viewport.

## In scope

- Seed `Default Support Hours`, `Asia/Seoul`, Monday–Friday 09:00–18:00, weekend closed, version 1 active.
- IANA timezone, weekday enablement, zero/multiple same-day non-overlapping intervals, weekend hours, and closed/open local-date exceptions.
- Immutable schedule versions and append-only activation facts.
- `BusinessTimeCalculator`: add business minutes, elapsed business minutes, next open instant, and next close boundary for preview.
- Flyway schema, Kotlin domain/application/JDBC/admin HTTP, OpenAPI, React admin page, component/API/browser tests, visual evidence.

## Out of scope

- SLA target instances or first-reply target lifecycle.
- Ticket-to-schedule assignment, including multiple assignment.
- SLA policies/priority targets, triggers, automations, scanners, and analytics.
- Overnight intervals and recurring holiday rules.
- Delete/overwrite operations for schedules or versions.

## Invariants and calculation semantics

- Intervals are local-time half-open ranges `[start, end)` and require `start < end`; overnight intervals are rejected.
- Intervals on the same weekday or exception date cannot overlap. Adjacent boundaries are allowed.
- `CLOSED` exceptions contain no intervals. `OPEN` exceptions contain one or more intervals and replace, rather than merge with, that date's weekly rule.
- Enabled weekdays may contain zero intervals; they are effectively closed until an interval is added. Disabled weekdays must contain zero intervals.
- Schedule timezone is an exact `ZoneId` identifier; aliases and unknown IDs are rejected at the API boundary.
- DST gap boundaries move forward by the transition duration. DST overlap uses the earlier valid offset for a start boundary and the later valid offset for an end boundary, so both repeated wall-clock occurrences are included.
- Effective intervals that collapse after DST resolution contribute zero time.
- `elapsedBusinessMinutes(from, to)` requires `from <= to` and truncates a final partial minute.
- `addBusinessMinutes(from, 0)` returns `from`; positive values consume effective intervals from `from`, using the next opening when closed.
- `nextOpenInstant(from)` returns `from` when already open and `null` when no weekly or future exceptional opening exists.
- One schedule row points to its latest and active immutable version. Creating a version never activates it implicitly.
- Creating a schedule/version and activating a version commit with their canonical admin/security audit event or roll back together.
- PostgreSQL triggers reject update/delete of version definitions, interval/exception children, and activation history.

## Concurrency, idempotency, and failure semantics

- Creating a new version requires `If-Match` of the schedule aggregate version; stale writes return `412` without a partial version or audit.
- Activation requires the same aggregate precondition and can reactivate a historical version. Repeating activation of the already-active version is a no-op and creates no duplicate activation/audit.
- Browser/network retries are not implicitly idempotent across successful POSTs; the UI disables duplicate submission and refreshes the aggregate after an ambiguous failure.
- There is no external I/O.
- Audit persistence failure returns `503 /problems/admin-audit-unavailable` and rolls back mutation state.

## Data, privacy, and threat delta

- Schedule names, zones, local dates, and intervals are operational configuration, not PII or secrets.
- Audit metadata contains bounded schedule/version identifiers and counts, never serialized schedule bodies.
- New threat surfaces: admin authorization bypass, stale activation/version overwrite, timezone/DST ambiguity, definition mutation, and oversized/overlapping interval input.

## Acceptance scenarios

1. Given the seeded installation, version 1 is active with Seoul weekday 09:00–18:00 and closed weekends.
2. Given Friday 17:00 Seoul, adding 120 business minutes returns Monday 10:00.
3. Given Saturday business hours, elapsed/add/next-open include the weekend interval.
4. Given split daily intervals, lunch is excluded and adjacent intervals are accepted.
5. Given a closed holiday or an exceptional open weekend, the exception replaces the weekly rule.
6. Given `America/New_York` DST fixtures, gap/overlap calculations follow the documented boundary policy and ignore the server default timezone.
7. Given overlap, invalid range, unknown timezone, invalid exception shape, or stale aggregate version, no version/audit is created and field-level problems are returned.
8. Given a saved edit, the prior version remains byte-for-byte queryable and the active version remains unchanged until activation.
9. Given activation, the aggregate pointer, activation history, and `BUSINESS_SCHEDULE_ACTIVATED` event commit together.
10. Given Agent or Security Auditor credentials, direct API and UI access are denied.

## Validation commands

```text
cd backend && ./gradlew test
cd frontend && npm run typecheck
cd frontend && npm test -- --run
cd frontend && PLAYWRIGHT_BROWSER=chromium npm run test:e2e:dev
docker compose config
```

PostgreSQL-backed integration tests cover migration, seed, immutability, transaction/audit, concurrency, and authorization. Browser tests cover keyboard operation, validation, preview, save, activate, denied navigation, axe, and full-page screenshots at a 1440 px viewport.

## Compatibility and migration

- Additive core Admin API; existing operations are unchanged.
- Forward-only Flyway migration from V17; rollback is application rollback plus forward fix. Do not drop the immutable schedule tables after production data exists.
- No existing ticket or SLA target rows are backfilled because target instances are out of scope.

## Human explanation

- Relational version children make invariants and immutable history explicit while keeping the deterministic calculator independent of JPA.
- A mutable aggregate pointer plus immutable versions supports rollback/activation without changing historical definitions.
- PostgreSQL, Spring transactions, and the existing admin/security ledger are sufficient; no broker, cache, search store, or background worker is introduced.
