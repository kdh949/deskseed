# Screen Specifications

## 1. Screen ID convention

- `PUB-*`: customer-facing
- `AGT-*`: agent-facing
- `ADM-*`: admin-facing
- `AUD-*`: auditor-facing
- `INT-*`: integration-facing admin screens

각 화면 구현 PR은 이 ID와 상태표를 수용 기준에 인용한다.

# Public screens

## PUB-001 — Request form

### Goal

로그인하지 않은 고객이 문의를 제출한다.

### Fields

```text
name        required, 1..100
email       required, normalized but not treated as verified
subject     required, 1..200
message     required, 1..configured max
privacy agreement if operator config requires
```

### States

| State | Behavior |
|---|---|
| initial | empty form, submit enabled only when valid |
| submitting | disable duplicate submit, progress label |
| validation error | field-level message + error summary |
| rate limited | retry guidance, no silent retry |
| success | ticket number and safe continuation method |
| server failure | preserve all fields, show request ID |

### Security

- no existing ticket disclosure from email match
- do not log message body
- rate limit/CAPTCHA boundary documented
- autocomplete attributes for name/email

## PUB-002 — Anonymous request detail

### Goal

opaque access grant를 가진 고객이 public conversation과 상태를 본다.

### Content

- ticket number, subject, safe status label
- created/updated time
- public comments only
- customer and agent display names according to policy
- add public reply later or in verified-account phase

### Negative states

- invalid/expired/revoked token uses non-enumerating response
- internal comment, child ticket, staff fields absent from DOM and API

## PUB-003 — Customer sign in / magic link

Post-MVP. See `docs/34-customer-portal-and-identity.md`.

## PUB-004 — My requests

Post-MVP. Filters by status, updated time; no staff-only metadata.

# Agent screens

## AGT-001 — Login

- email/password initially
- generic invalid credential error
- lockout/rate-limit behavior
- optional SSO/MFA later
- successful and failed events audited without password

## AGT-002 — Agent Home

### Regions

```text
Global nav rail
Work categories sidebar
Ticket list
Optional ticket preview drawer
```

### Initial lists

- My open tickets
- Unassigned tickets in my groups
- Pending tickets
- Recently solved by me
- Internal child tasks assigned to me

### Ticket row

```text
channel/status
requester
subject
latest activity summary
priority
group/assignee
updated time
child/SLA indicator
```

### States

- no work: calm empty state
- permission-limited totals: label scope
- stale list: refresh without opening semantic ticket view

## AGT-003 — Saved view / ticket queue

### Filter contract

- status
- priority
- group
- assignee
- requester/customer identifier
- updated range
- child/parent marker
- external reference later

### Behavior

- URL is shareable only when recipient has permission
- cursor pagination
- stable sort with ticket number tiebreaker
- direct row selection opens AGT-004
- background prefetch does not emit `TICKET_VIEWED`

## AGT-004 — Ticket workspace

### Layout

```text
Global nav | Views | Properties | Conversation/Composer | Context rail/panel
```

### Header

- `#number · subject`
- requester/channel/created time
- warning badges
- more actions

### Properties

- requester
- group
- assignee
- status
- priority
- later custom fields/tags/form

### Conversation

- public and internal comments
- event toggle
- filter
- clear author/channel/time

### Composer

- public/internal mode
- draft persistence
- submit + status
- failure retains draft

### Context

- customer
- related parent/children
- external references
- audit summary
- apps later

### Critical scenarios

1. public reply + status pending in one command
2. internal note + transfer in one command
3. same-field stale conflict shows red banner
4. disjoint-field change follows server merge policy
5. open child warning does not block solve
6. child ticket never exposes public reply control to customer because it has no customer projection

## AGT-005 — Create staff ticket

- requester can be searched/created
- origin `STAFF_CREATED`
- first comment visibility defaults `INTERNAL` unless agent explicitly chooses public and requester channel supports it
- audit records actor and creation source

## AGT-006 — Create child ticket dialog

Fields:

```text
subject
internal task description
target group
target assignee optional
priority
due hint later
copy context selector later; MVP links full parent read by authorization
```

On success, show child number and preserve parent ownership.

## AGT-007 — Customer profile

- essentials and verified state
- contact methods
- public ticket history
- staff-only notes only if separately modeled and authorized
- opening full profile emits `CUSTOMER_PROFILE_VIEWED`

# Admin screens

## ADM-001 — Admin home

Shows enabled capabilities, setup status, security warnings, and links. It is not an analytics dashboard.

## ADM-002 — Staff accounts

- list/search/filter active status/role/group
- create, disable, reset/rotate auth path
- no password retrieval
- role changes audited

## ADM-003 — Groups and membership

- groups and active/inactive state
- add/remove members
- warn about currently assigned tickets when deactivating membership/group
- do not silently violate assignment invariant

## ADM-004 — Customer access settings

```text
ANONYMOUS_ALLOWED
REGISTRATION_OPTIONAL
REGISTRATION_REQUIRED
```

Preview operational impact before save. Setting change audited.

## ADM-005 — Ticket configuration

Later: fields, forms, views, macros. See doc 35.

## ADM-006 — SLA policies

Later: see doc 38.

## ADM-007 — Triggers and automations

Later: see doc 40.

# Audit screens

## AUD-001 — Audit Explorer

### Header

- date range
- saved investigation filters later
- export action with permission

### Ledger tabs

```text
Ticket changes
Access and searches
Admin and security
Integrations and deliveries
```

### Filter bar

- actor/actor type
- ticket/customer
- action/event type
- field
- source/outcome
- group
- integration client
- IP/request/correlation
- search fingerprint

### Result row

- timestamp
- actor
- action
- resource
- concise structured diff or outcome
- source/IP/request ID summary

### Detail drawer

- canonical event ID
- actor/session/auth
- before/after values
- correlation/causation/request
- search-to-open links
- protected reveal controls

### Security behavior

- read-only
- protected content hidden by default
- reveal requires reason/scope/reauth policy
- view/reveal/export self-audited

## AUD-002 — Audit export jobs

- requested filter and field set
- permission snapshot
- state/progress/expiry
- download audited
- expired/deleted states

# Integration admin screens

## INT-001 — API clients

- client name/status/scopes/constraints
- create credential, show secret once
- expiry/last used/IP
- rotate/revoke
- lifecycle audit

## INT-002 — External systems

- system key/name
- allowed HTTPS hosts
- deep link template
- object types
- enabled state

## INT-003 — Webhook endpoints

- endpoint URL, event subscriptions
- signing secret once/rotation
- state and recent delivery health
- test event with clear non-production label
- disable/replay

## INT-004 — Webhook deliveries

- delivery/attempt table
- status code/latency/next retry
- payload metadata without secret/full sensitive body
- manual replay reason

# Reporting screens

## RPT-001 — Operations dashboard

Later. Curated metrics only; definitions link to metric glossary.

## RPT-002 — SLA dashboard

Later. At-risk, breached, achieved, grouped by policy/team/priority.

## RPT-003 — Dataset export

Later. Snapshot/incremental export with field authorization.
