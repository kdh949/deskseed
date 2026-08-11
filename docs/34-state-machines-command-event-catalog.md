# State Machines, Commands, Audit Events, Domain Events

## 1. Ticket status model

Canonical persisted statuses:

```text
NEW
OPEN
PENDING
ON_HOLD
SOLVED
CLOSED
```

The customer projection currently exposes `NEW`, `OPEN`, `PENDING`, and `SOLVED`; staff reads preserve all six canonical values. `CLOSED` remains system-only, and custom status-to-base-category mapping is post-MVP work.

## 2. Transition table

| From | To | Actor | Notes |
|---|---|---|---|
| NEW | OPEN | Agent/Admin/System | take/first handling |
| NEW | PENDING | Agent/Admin | reply requesting customer info 가능 |
| NEW | SOLVED | Agent/Admin | direct resolution 가능 |
| OPEN | PENDING | Agent/Admin | customer response wait |
| OPEN | SOLVED | Agent/Admin | open child warning only |
| PENDING | OPEN | Agent/Admin/Customer event | customer replies or agent resumes |
| PENDING | SOLVED | Agent/Admin | resolution |
| SOLVED | OPEN | Agent/Admin/Customer follow-up policy | reopen |
| SOLVED | CLOSED | System | configured delay later |
| CLOSED | any | none | immutable; follow-up creates new linked ticket |

## 3. Core commands

```text
CreateAnonymousRequest
CreateAgentTicket
AddTicketComment
UpdateTicket
AssignTicket
TransferTicket
CreateChildTicket
SolveTicket
ReopenTicket
LinkExternalReference
RemoveExternalReference
```

원칙:

- HTTP endpoint는 command와 1:1일 필요가 없지만 의미를 명확히 한다.
- `UpdateTicket`은 changed fields + optional comment를 원자적으로 처리한다.
- Transfer와 CreateChildTicket은 절대 같은 command가 아니다.

## 4. Command context

```text
actor(type, id)
source
requestId
correlationId
causationId
idempotencyKey optional
interactionId optional
searchSessionId optional
clock
```

## 5. Ticket Change Audit event catalog

```text
TICKET_CREATED
COMMENT_CREATED
STATUS_CHANGED
PRIORITY_CHANGED
GROUP_CHANGED
ASSIGNEE_CHANGED
REQUESTER_CHANGED
TICKET_SOLVED
TICKET_REOPENED
TICKET_CLOSED
CHILD_TICKET_CREATED
TICKET_RELATION_CREATED
TICKET_RELATION_REMOVED
EXTERNAL_REFERENCE_CREATED
EXTERNAL_REFERENCE_REMOVED
TAG_ADDED
TAG_REMOVED
ATTACHMENT_ADDED
```

Audit event는 사람이 읽는 문자열 대신 구조화된 old/new 값을 가진다.

## 6. Access & Search event catalog

```text
TICKET_VIEWED
CUSTOMER_PROFILE_VIEWED
SEARCH_EXECUTED
SEARCH_RESULT_OPENED
ATTACHMENT_VIEWED
ATTACHMENT_DOWNLOADED
EXPORT_REQUESTED
EXPORT_DOWNLOADED
API_RESOURCE_READ
EXTERNAL_REFERENCE_OPENED
```

## 7. Admin & Security event catalog

```text
LOGIN_SUCCEEDED
LOGIN_FAILED
ACCESS_DENIED
STAFF_CREATED
STAFF_DISABLED
ROLE_CHANGED
GROUP_CREATED
GROUP_CHANGED
GROUP_MEMBERSHIP_CHANGED
SETTING_CHANGED
CUSTOMER_ACCESS_MODE_CHANGED
INTEGRATION_CLIENT_CREATED
INTEGRATION_CLIENT_ROTATED
INTEGRATION_CLIENT_REVOKED
WEBHOOK_CREATED
WEBHOOK_SECRET_ROTATED
WEBHOOK_DISABLED
AUDIT_LOG_VIEWED
AUDIT_SENSITIVE_CONTENT_REVEALED
AUDIT_EXPORT_REQUESTED
AUDIT_EXPORT_DOWNLOADED
RETENTION_POLICY_CHANGED
RETENTION_JOB_EXECUTED
```

## 8. Domain/application event catalog

내부 모듈 협업용 사실:

```text
TicketCreated
TicketUpdated
TicketCommentAdded
TicketAssigned
TicketTransferred
ChildTicketCreated
TicketSolved
TicketReopened
ExternalReferenceLinked
CustomerVerified
SlaTargetStarted
SlaTargetAchieved
SlaTargetBreached
TriggerExecuted
WebhookDeliveryRequested
```

도메인 event와 canonical audit row는 동일 개념이 아니다.

- Audit: 인간 조사와 변경 이력.
- Domain event: 모듈 반응과 projection.
- Integration event: 외부 호환 계약.

## 9. Transaction boundaries

### Ticket mutation

```text
load aggregate
→ authorize
→ validate version/fields
→ apply command
→ save ticket/comment/relation
→ save ticket audit/events
→ publish in-process fact
→ commit
```

### Sensitive read

```text
authorize
→ query projection
→ append access audit
→ return success
```

### Webhook

```text
business commit + outbox event
→ asynchronous dispatcher
→ delivery attempt
→ retry/dead-letter
```

## 10. Trigger execution

```text
original command commit
→ evaluate ordered active trigger versions
→ actions invoke normal application commands
→ new audit source=TRIGGER
→ next trigger sees latest state
```

Safety:

- max action depth.
- max rule executions per root correlation.
- state fingerprint repetition detection.
- webhook does not synchronously call arbitrary URL inside ticket transaction.

## 11. SLA time events

- state interval rows are opened/closed from ticket status events.
- policy version snapshot on target creation.
- business calendar calculates dueAt.
- clock progression tests use deterministic Clock.

## 12. Event versioning

External event names include schema version in envelope, not topic name alone.

- compatible fields may be added.
- required field removal/type change creates new version.
- consumer contract tests run before publish.
