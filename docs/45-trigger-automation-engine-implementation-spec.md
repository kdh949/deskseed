# Trigger and Automation Engine

## 1. 개념 분리

- **Trigger**: 티켓이 생성되거나 업데이트되는 순간 조건을 평가한다.
- **Automation**: 시간 경과 또는 주기적 스캔으로 조건을 평가한다.
- **Webhook**: action/delivery mechanism 중 하나다.
- **Macro**: 상담사가 검토 후 적용하는 UI draft/template다.

이 네 개를 하나의 generic script engine으로 만들지 않는다.

## 2. Preconditions

- 모든 action에 대응하는 정상 Ticket command가 먼저 존재
- one command/one audit
- actor/source/correlation
- webhook outbox
- idempotency and loop safety
- admin version/audit

## 3. Definition model

```text
Trigger
  id, name, active, position
  current_version_id

TriggerVersion
  trigger_id, version
  all_conditions[]
  any_conditions[]
  actions[]
  created_by, created_at
  immutable after activation
```

Condition envelope:

```json
{"field":"priority","operator":"IS","value":"URGENT"}
```

Initial fields/operators are allowlisted. No arbitrary SQL/SpEL/JavaScript.

## 4. Initial conditions

- ticket created/updated
- status/priority/group/assignee
- channel/kind
- requester verified state
- tags/custom select later
- comment added and visibility/actor type
- parent/child marker
- external reference presence

Operators:

```text
IS, IS_NOT
PRESENT, NOT_PRESENT
IN, NOT_IN
CHANGED, CHANGED_FROM, CHANGED_TO
CONTAINS for bounded string fields later
```

## 5. Initial actions

- set status/priority
- assign group/assignee
- add/remove tag
- set custom field
- add internal note from template
- enqueue webhook event
- create child ticket later with strict guard
- send public reply only after explicit safety policy; default excluded

All actions call normal application commands and produce audit.

## 6. Ordered execution

Within one root operation:

```text
user/platform command commits or reaches defined trigger evaluation boundary
→ active trigger versions ordered by position
→ evaluate against evolving ticket state
→ execute actions
→ next trigger sees previous resulting state
```

Implementation choice between same transaction and post-commit durable processing requires ADR. Recommended evolution:

- simple synchronous deterministic field actions can execute in controlled command pipeline;
- network/delivery actions are durable intents after commit;
- expensive or failure-prone automation moves to durable worker.

Never perform outbound HTTP in ticket transaction.

## 7. Provenance

Every action records:

```text
source TRIGGER|AUTOMATION
trigger_id/version or automation_id/version
root_correlation_id
causation audit/event
execution_id
```

TicketAudit UI explains “Trigger X v3 changed group from A to B”.

## 8. Loop prevention

Use multiple controls:

- max execution depth per root correlation
- max action count
- `(rule version, ticket state fingerprint)` repetition detection
- no-op action suppression
- time budget
- circuit breaker/disable after repeated failures

Loop block is observable and audited.

## 9. Dry run and preview

Admin can select a sample ticket or definition snapshot and see:

```text
matched conditions
unmatched conditions
proposed actions
permission/invariant failures
possible following trigger matches
```

Dry run never mutates ticket or sends webhook.

## 10. Activation and editing

- draft version editable
- validate
- preview/test
- activate atomically
- old version preserved
- reorder audited
- deactivate immediate
- rollback by reactivating previous version creates new activation event

## 11. Automation model

```text
Automation
  id/name/active/position/current_version
  schedule or scan frequency
  conditions including time-since fields
  actions
```

Time conditions use indexed candidate query, not load all tickets.

Examples:

- pending for 72 business hours → internal reminder
- solved for configured time → close
- urgent unassigned for 10 minutes → assign/escalate

## 12. Scanner coordination

- stable cursor/checkpoint
- database lease/advisory lock or documented single worker first
- bounded batch
- idempotent execution key `(automation_version, ticket, window)`
- recover after crash
- no repeated action each scan unless explicitly repeatable

## 13. Webhook/n8n/Workato

Trigger action creates outbound event intent. Generic signed webhook delivers to n8n/Workato. External workflow writing back uses Platform API with its own IntegrationClient and idempotency key.

Correlation chain:

```text
Ticket command
→ Trigger execution
→ Webhook event/delivery
→ n8n workflow
→ Platform API command
```

Loop controls must cross this boundary using event/correlation IDs and integration policy.

## 14. Security

- no arbitrary code
- no secret interpolation into comment/body/log
- templating allowlist and output limits
- webhook endpoint managed separately
- action permissions are service policy, not creator's expired session
- sensitive customer fields unavailable unless explicit rule field permission

## 15. Admin UI

- ordered list with active/status/last execution/error
- condition builder all/any
- action builder
- version history/diff
- preview/dry run
- execution history with resulting audit links

## 16. Gates

- `AUT-001`: condition operator truth table
- `AUT-002`: ordered triggers see evolving state
- `AUT-003`: no-op suppression and one audit provenance
- `AUT-004`: invariant/authorization failure is explicit and safe
- `AUT-005`: webhook action is post-commit durable intent
- `AUT-006`: depth/fingerprint loop detection
- `AUT-007`: dry run has zero side effects
- `AUT-008`: version activation/rollback and audit
- `AUT-009`: automation scanner idempotency/crash recovery

## 17. Initial durable evaluation boundary

The first implementation listens to the synchronous `TicketSubmitted` application event with transaction propagation `MANDATORY`. It snapshots active trigger IDs, immutable versions, and positions into one `trigger_evaluation_jobs` row before the originating customer, staff, or Platform API transaction commits.

- no active trigger means no job row;
- job insertion failure rolls back the ticket command and its audit because otherwise the trigger event would be lost;
- later definition deactivation cannot rewrite an already captured version snapshot;
- the job appender performs no condition action and no network I/O;
- webhook delivery failure occurs after both ticket and trigger command commits and cannot roll either mutation back.

The worker claims jobs with `FOR UPDATE SKIP LOCKED` and a 60-second lease in a separate transaction. One execution transaction walks the captured versions in `(position, triggerId)` order, reloads the latest ticket before every rule, invokes a typed `ActorType.TRIGGER` ticket command, appends `TRIGGER_APPLIED` provenance, and then appends the metadata-only `ticket.trigger.executed` event. A failed invariant rolls the entire execution transaction back and moves the job through bounded retry to dead letter; it never leaves a partial ticket mutation or webhook intent.

## 18. Initial solved-ticket automation boundary

The first time-based slice only accepts a versioned elapsed-time condition (`solvedAgeMinutes`) and the system-only `CLOSE_TICKET` action. A 60-second scanner uses one PostgreSQL advisory transaction lock, the partial `(solved_at, id)` index for `SOLVED` tickets, and a maximum batch of 100 candidate pairs.

Candidate identity is `(automationId, automationVersion, ticketId, solvedAt)`. The discovery query excludes existing identities before its limit so repeated scans cannot starve later rows. Reopening and solving a ticket creates a different interval identity; rescanning the same SOLVED interval does not.
