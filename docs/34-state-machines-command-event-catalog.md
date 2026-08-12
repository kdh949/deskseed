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
UPDATE_COMMAND_RECEIVED
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

`UPDATE_COMMAND_RECEIVED`는 `UpdateTicket`이 유효하지만 current row, comment, version에 실제 변경이 없는 경우에만
남기는 content-free receipt event다. 첫 ordered event는 `commandOperation=UPDATE_TICKET`, canonical request descriptor,
original warnings를 metadata로 가진다. Descriptor는 ticket/version/정렬된 declared field와 요청 field 값, comment visibility,
기존 `COMMENT_CREATED.contentSha256` 값만 사용하며 comment raw body나 별도 full-payload digest를 저장하지 않는다.
같은 authenticated staff actor의 동일 `clientCommandId`와 exact descriptor 재시도는 원래 결과를 반환하고 새 audit/event를
만들지 않는다. 동일 ID의 다른 payload, ticket, operation 또는 둘 이상의 legacy audit match는 409로 fail closed한다.

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
STAFF_AUTHORITY_GRANTED
STAFF_AUTHORITY_REVOKED
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

`GrantStaffAuditAuthority`와 `RevokeStaffAuditAuthority`는 ADMIN actor만 실행하며,
`AUDIT_SEARCH_QUERY_REVEAL`, `AUDIT_EXPORT`, `AUDIT_PROJECTION_REBUILD`만 허용한다.
grant row 변경과 대응하는 admin/security event는 같은 transaction에서 commit/rollback한다.

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

Staff `UpdateTicket` uses the request `expectedVersion` and explicit field set. If the row is stale,
the service intersects that set with structured audit fields committed after the expected version.
An overlap is rejected with `409`; a disjoint change is applied to the latest row. If two transactions
still race at commit, the losing optimistic transaction is fully rolled back and retried from the latest
row with authorization and conflict checks repeated. Group changes that would invalidate the current
assignee require the request to include an explicit compatible `assigneeId` or `null` clear.

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
- business schedule versions calculate dueAt without consulting server default timezone.
- clock progression tests use deterministic Clock.

Business schedule administration emits canonical AdminSecurityAudit events:

```text
BUSINESS_SCHEDULE_CREATED
BUSINESS_SCHEDULE_VERSION_CREATED
BUSINESS_SCHEDULE_ACTIVATED
```

Create/version/activation and its audit commit or roll back together. Repeating
activation of the already-active version is a no-op. Version definitions and
activation facts are never update/delete events.

## 12. Event versioning

External event names include schema version in envelope, not topic name alone.

- compatible fields may be added.
- required field removal/type change creates new version.
- consumer contract tests run before publish.
