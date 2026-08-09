# SLA, Analytics, and Automation Blueprint

이 기능들은 서로 독립적이지 않다. 정확한 audit/update semantics가 SLA timer를 만들고, SLA/update facts가 analytics를 만들며, automation은 같은 command/audit pipeline을 사용해야 한다.

## 1. Operational event vocabulary

최소한 다음 사실을 안정적으로 기록한다.

- TicketCreated
- PublicCustomerCommentAdded
- PublicAgentCommentAdded
- InternalCommentAdded
- StatusChanged
- PriorityChanged
- GroupChanged
- AssigneeChanged
- TicketSolved
- TicketReopened
- ChildTicketCreated
- TriggerExecuted
- WebhookDeliverySucceeded/Failed

이 event 이름은 analytics label이 아니라 business semantics다. actor, occurredAt, ticket, audit, source, correlation/idempotency ID를 가진다.

## 2. SLA model

### Policy

```text
SlaPolicy
  id, name, active, position, version
  conditions: all[] + any[]
  targets by priority
  scheduleId
```

### Target instance

정책 자체만 저장해서는 과거 계산을 재현할 수 없다. 티켓에 적용될 때 policy version과 target 값을 snapshot한다.

```text
SlaTargetInstance
  ticketId
  policyId + policyVersion
  metricType
  cycleNumber
  targetMinutes
  businessHours
  status: ACTIVE/ACHIEVED/BREACHED/CANCELLED
  startedAt
  dueAt
  achievedAt/breachedAt
  elapsedBusinessSeconds
```

### Metric semantics

- First reply: 고객의 최초 public 요청부터 첫 agent public reply까지
- Next reply: 답변되지 않은 가장 오래된 customer public comment부터 다음 agent public reply까지
- Requester wait: 고객이 답변을 기다리는 상태 시간의 합
- Agent work: 상담사가 처리 가능한 상태 시간의 합
- Total resolution: 생성부터 해결까지 calendar/business elapsed
- Group OLA: 특정 group ownership interval의 목표

내부 메모는 reply metric을 만족시키지 않는다. status transition과 public comment가 같은 audit에 있을 때 event order를 명확히 정의해야 한다.

## 3. Time intervals

현재 row만으로 정확한 과거 시간을 계산하지 않는다.

```text
TicketStatusInterval(ticketId, status, startedAt, endedAt)
TicketAssignmentInterval(ticketId, groupId, assigneeId, startedAt, endedAt)
```

Audit event consumer가 projection을 갱신하며 재처리 가능한 idempotency key를 가진다.

## 4. Analytics datasets

### Tickets

현재/최종 상태 중심: 생성, 해결, 우선순위, 채널, requester, current group.

### Updates

audit event 중심: 누가 무엇을 얼마나 변경했는지, transfer/reopen/댓글 횟수.

### Backlog snapshots

매일/매시간 특정 시점의 unsolved ticket 상태와 aging. 과거 backlog는 현재 row만으로 계산할 수 없다.

### SLA

target instance 중심: metric, target, achieved/breached, duration, policy, group, priority.

### Automation

trigger executions, matched rate, action count, failure, recursion prevention, webhook delivery latency.

## 5. Initial dashboard questions

- 오늘/이번 주 문의가 얼마나 생성·해결됐는가?
- 현재 backlog는 몇 개이며 얼마나 오래됐는가?
- first reply p50/p90/p95는 얼마인가?
- SLA 만족률과 곧 breach될 티켓은 무엇인가?
- 어느 그룹에서 transfer/child 처리 시간이 긴가?
- 재오픈률과 반복 문의가 높은 유형은 무엇인가?
- 어떤 trigger가 가장 자주 실행되고 실패하는가?

Metric glossary에서 numerator, denominator, excluded tickets, timezone, business-hours semantics를 정의한다.

## 6. Trigger engine

### Definition

```text
Trigger
  active, position, version
  allConditions[]
  anyConditions[]
  actions[]
```

### Execution

1. ticket command commits current change and audit
2. trigger engine evaluates active triggers in position order
3. a trigger action can alter the ticket through the same command service
4. changed state may affect later trigger conditions
5. execution context tracks depth and previously seen state fingerprint
6. resulting changes produce audit with source `TRIGGER` and trigger execution ID

### Safety

- dry-run before activation
- max actions and max depth per root operation
- timeout and condition/action allowlist
- no arbitrary code execution
- versioned definitions so past audit is explainable
- idempotency on root audit/event ID + trigger version

## 7. Webhook delivery

Canonical envelope:

```json
{
  "id": "event-uuid",
  "type": "ticket.updated",
  "version": 1,
  "occurredAt": "2026-08-10T00:00:00Z",
  "subject": "ticket:uuid",
  "sequence": 42,
  "correlationId": "root-operation-uuid",
  "causationId": "audit-or-event-uuid",
  "data": {}
}
```

Headers:

```text
X-Deskseed-Event-Id
X-Deskseed-Timestamp
X-Deskseed-Signature
```

Delivery states:

```text
PENDING → DELIVERING → SUCCEEDED
                    ↘ RETRY_SCHEDULED → DEAD_LETTER
```

- HMAC includes timestamp and raw body
- duplicate event ID is expected
- 2xx succeeds; retryable status/network errors back off with jitter
- manual replay creates a new attempt, not a new business event
- destination secret is encrypted and never returned after creation

JSON Schema 초안은 `api/integration-event-envelope-v1.schema.json`에 있다. n8n and Workato connect through this generic HTTP contract. Product-specific connector work is justified only when authentication/discovery/user experience requires it.

## 8. Export architecture

### Snapshot export

A user selects filters/fields. A background job writes CSV/JSON to object storage and returns a short-lived signed URL. Export is authorized using the requesting actor's scope snapshot and recorded in an audit log.

### Incremental export

A stable cursor advances through `(generated_at, sequence/id)` and returns changes, including deletions/tombstones. It is for integrations and warehouse ingestion, not ad-hoc UI downloads.

## 9. Evolution path

```text
Transactional DB + audit
  → local projection listeners
  → Spring Modulith durable publication
  → Postgres analytics schema/materialized views
  → signed webhook/outbox
  → Kafka for independent consumers
  → dedicated search/analytics stores if measured
```

## 10. Audit and integration datasets added in v0.3

### Access/Security dataset

Grain: one canonical access or admin/security event.

Dimensions:

- actor type and actor ID
- staff group/role snapshot
- action
- resource type/ticket/customer
- source/client/auth type
- IP/network category
- outcome
- search interaction and fingerprint

Measures:

- semantic ticket views
- unique tickets viewed by actor
- searches executed
- result-open rate
- access denied count
- audit protected-content reveal count
- audit export count

This dataset is for security/operations. It is not a productivity leaderboard by default. Any employee-monitoring use requires an explicit policy and metric interpretation review.

### Integration dataset

Grain depends on fact:

- API request fact
- idempotency outcome fact
- webhook delivery attempt fact
- incremental export page fact

Measures:

- requests by client/operation/outcome
- 403/409/429 rates
- idempotency replay/misuse
- webhook latency/retry/dead-letter
- external references created
- deprecated API usage

Security audit and integration delivery facts remain separate canonical sources even when a dashboard combines them.
