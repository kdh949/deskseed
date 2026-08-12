# SLA, OLA, and Business Hours Specification

## 1. 목표

티켓의 현재 상태만 보고 SLA를 계산하지 않는다. 정책 버전, 적용 대상, 업무 시간, 상태 구간, 응답 이벤트를 명시적으로 기록하여 과거 결과를 재현한다.

- SLA: 고객과의 서비스 목표
- OLA: 내부 그룹/자식 티켓 협업 목표

## 2. Prerequisites

구현 전 필요한 사실:

- comment author/visibility/channel
- ticket status change audit
- assignment/group change audit
- precise timestamps and injected Clock
- public reply semantics
- status/assignment intervals

## 3. BusinessSchedule

```text
BusinessSchedule
  id
  name
  timezone IANA
  weekly intervals
  holidays/exceptions
  version
  active
```

Rules:

- timezone is part of schedule, not server default
- overlapping intervals rejected
- intervals are local-time half-open ranges `[start, end)`; overnight ranges are rejected
- adjacent ranges are allowed; zero or multiple ranges may be configured per enabled day
- holiday `CLOSED` has no ranges; `OPEN` has one or more ranges and replaces that date's weekly rule
- version applied to target is immutable snapshot/reference
- a DST gap boundary shifts forward by the transition duration
- a DST overlap uses the earlier offset at the start and later offset at the end, including both repeated wall-clock occurrences
- effective intervals collapsed by DST resolution contribute zero time
- elapsed business minutes truncate a final partial minute; adding zero minutes returns the input instant
- daylight saving behavior is tested with `America/New_York` fixtures even if initial locale is Korea


## 3.1 Accepted launch defaults and admin controls

Seed one active schedule candidate:

```text
name: Default Support Hours
timezone: Asia/Seoul
Monday–Friday: 09:00–18:00
Saturday/Sunday: closed
```

Administrators can edit the timezone, enable or disable every weekday including weekends, add multiple non-overlapping intervals per day, and manage holiday/exception dates. Each save creates a new schedule version. First Reply SLA pauses in `PENDING` by default, but pause statuses are policy data and editable. No SLA policy is active until priority targets are entered and activated.

The schedule administration slice is exposed at
`/admin/business-rules/schedules` and the matching Core Admin API. Creating a
version does not activate it. Version creation and activation require the
schedule aggregate `If-Match` value; stale writes fail with `412`. Definition
rows and activation history are immutable, while the root row only advances
latest/active pointers. Admin audit persistence is required for a successful
mutation.

## 4. SLA policy

```text
SlaPolicy
  id
  name
  priority/order
  active
  version
  all_conditions
  any_conditions
  schedule_id/version
  targets by priority
```

Initial conditions:

- ticket kind/customer request only
- group
- priority
- channel
- tags/custom select later

First matching policy by explicit order applies. A dry-run preview must explain why a policy matched.

## 5. Target instance

When a policy applies, create immutable target instances.

```text
SlaTargetInstance
  ticket_id
  policy_id/version
  metric_type
  target_minutes
  schedule snapshot/reference
  started_at
  due_at
  achieved_at
  breached_at
  status ACTIVE|PAUSED|ACHIEVED|BREACHED|CANCELLED
  calculation_version
```

Changing policy later does not rewrite old target results unless explicit recalculation job/version is run and audited.

## 6. Metric semantics

### First reply time

Start: first customer-visible ticket creation/first public customer comment.
Stop: first qualifying public reply by staff/system policy.
Exclude: internal notes, automated acknowledgement unless policy explicitly counts it.

### Next reply time

Start: each qualifying public customer reply after agent response.
Stop: next qualifying public agent reply.

### Requester wait time

Accumulates while ticket is waiting for staff according to status semantics.

### Agent work time

Accumulates while waiting for requester or in configured internal statuses, depending on glossary.

### Resolution time

Start at ticket creation; stop at solved. Reopen either resumes same target or creates cycle according to versioned policy. Default: total resolution includes reopened time until final solve, with cycle facts preserved.

## 7. Status intervals

Canonical interval projection:

```text
TicketStatusInterval
  ticket_id
  status
  started_at
  ended_at
  source_audit_id
```

The current open interval is derived/maintained transactionally from status changes. Rebuild from ticket audits must be testable.

## 8. Pause rules

Each metric declares pause statuses. Example:

```text
First reply: usually no pause before first reply
Next reply: may pause on PENDING/HOLD by policy
Resolution: may or may not pause; explicit
```

No hidden global pause rule.

## 9. Recalculation

Events that may require recalculation:

- policy initially applied
- priority/group/form field change
- schedule/holiday correction with explicit effective policy
- status/comment event
- reopen

Use idempotent calculation command and store calculation version/checkpoint.

## 10. OLA for child tickets

Child OLA starts when child ticket is created or assigned to target group. It can measure:

- first internal response
- child resolution
- time in target group

Parent customer SLA does not automatically pause merely because child work exists unless SLA policy says so.

## 11. UI

Ticket header/properties:

- next target and due time
- at-risk/breached/achieved label
- policy name/version in detail
- countdown uses server due time; browser clock skew does not change canonical result

Admin:

- policy order
- conditions
- target minutes by priority
- business schedule
- preview against sample ticket

Reporting:

- achieved/breached counts and rate
- excluded/no-policy tickets clearly separated

## 12. Jobs and concurrency

A scheduled scanner marks due ACTIVE targets breached. Correctness cannot depend solely on scanner timing: read logic can determine overdue state, and scanner materializes/audits transition idempotently.

- lease/checkpoint for multiple instances
- bounded batch
- idempotent update
- clock injection
- recovery after downtime

## 13. Audit

Events:

```text
SLA_POLICY_APPLIED
SLA_TARGET_CREATED
SLA_TARGET_PAUSED/RESUMED
SLA_TARGET_ACHIEVED
SLA_TARGET_BREACHED
SLA_POLICY_CHANGED
SLA_RECALCULATED
```

TicketAudit may reference target events; admin policy edits go to AdminSecurityAudit.

## 14. Gates

- `SLA-001`: business minute calculator across boundaries/DST/holidays
- `SLA-002`: first reply excludes internal notes
- `SLA-003`: next reply creates correct cycles
- `SLA-004`: status pause intervals rebuild from audit
- `SLA-005`: policy version snapshot preserves history
- `SLA-006`: scanner is idempotent and downtime-safe
- `SLA-007`: child OLA independent from parent state
- `SLA-008`: dashboard numerator/denominator matches metric glossary
