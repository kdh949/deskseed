# Post-MVP Capability Blueprints

## 1. 적용 규칙

아래 기능은 장기 구조를 정한 `BLUEPRINT_READY` 상태다. 코딩 직전 별도 task에서 policy, API, migration, UI acceptance를 동결한다.

## 2. Fine-grained permissions

### 목표

그룹·역할별 ticket `NONE/READ/READ_WRITE`와 action/field permission.

### 순서

1. capability registry.
2. group policy tables.
3. ticket access decision service.
4. query filtering.
5. UI reason/disabled state.
6. policy simulator와 audit.

### 금지

JPA repository를 직접 호출해 권한 filter를 우회하는 controller.

## 3. SLA

### Domain

```text
BusinessCalendar
SlaPolicyVersion
SlaTargetInstance
TicketStateInterval
```

### MVP metric

1. First Reply Time.
2. Resolution Time.
3. Next Reply Time.
4. Requester Wait Time.
5. Group OLA for child tickets.

### 구현 순서

- event semantics 확정.
- deterministic business clock.
- target snapshot.
- at-risk/breached job.
- ticket badge/view columns.
- policy admin.
- analytics facts.

## 4. Explore-like analytics

### Datasets

```text
Tickets current/final
Updates/audit facts
Backlog snapshots
SLA facts
Automation facts
Access/security facts
Integration facts
```

### 첫 dashboard

- created/solved/backlog.
- first reply p50/p90/p95.
- SLA achievement/at risk.
- transfer count/rate.
- child cycle time.
- reopen rate.
- automation success/failure.
- webhook health.

### 원칙

metric glossary를 코드·SQL보다 먼저 승인한다. 현재 ticket row만으로 과거 backlog를 계산하지 않는다.

## 5. Trigger engine

### Model

```text
TriggerVersion
ordered position
allConditions
anyConditions
actions
active/draft
```

### Initial conditions

- status/priority/group/assignee/channel.
- tags.
- comment visibility/source.
- requester properties.
- child/open child count.

### Initial actions

- set status/priority.
- assign group/agent.
- add/remove tags.
- add internal note.
- request webhook.

### Safety

- no arbitrary code.
- max depth.
- fingerprint loop detection.
- dry-run.
- ordered evaluation.
- execution audit.

## 6. Time-based automation

Trigger와 분리한다.

- conditions include elapsed time/current state.
- scheduler selects candidates in bounded batches.
- same ticket/rule/time window idempotency key.
- action uses normal commands.

## 7. Search

### Stage 1 PostgreSQL

- subject/comment/customer/ticket number.
- normalized query.
- permission-aware query.
- search audit.
- query plan baseline.

### Stage 2 projection

- PostgreSQL materialized/search document table.
- async update and lag indicator.

### Stage 3 Elasticsearch/OpenSearch

도입 기준:

- 기능/latency/scale 한계 측정.
- rebuild path.
- source of truth remains PostgreSQL.
- authorization filter strategy.

## 8. Ticket detail extraction and export

### Snapshot export

현재 filter 결과의 CSV/JSON.

### Incremental export

cursor 이후 ticket/audit changes.

### Requirements

- async job.
- row/field permission.
- expiring artifact.
- encryption at rest.
- request/download audit.
- large export limit.
- tombstone semantics.

## 9. Attachments

- S3-compatible storage.
- presigned upload/download.
- MIME/extension/size allowlist.
- malware scan state.
- content disposition.
- access audit.
- lifecycle retention.
- no public bucket.

## 10. Email channel

- inbound message provider/webhook.
- message ID deduplication.
- sender identity matching.
- public/internal classification.
- threading references.
- outbound delivery status.
- bounce handling.
- raw email retention policy.

## 11. Chat/messaging later

- conversation session vs ticket lifecycle 분리.
- realtime transport.
- presence/routing.
- transcript as comments/events.
- agent workspace remains unified.

## 12. Custom fields/statuses/views/macros

구현 순서:

1. typed field definition.
2. value storage and validation.
3. permission/projection.
4. view conditions/columns.
5. trigger condition support.
6. analytics dimension.

Macros는 여러 field/comment action을 하나의 상담사 command draft로 적용하고 제출 전 preview한다.

## 13. Agent App SDK

- sandboxed iframe.
- manifest locations/scopes/origins.
- host bridge.
- named server-side connections.
- no long-lived secret in browser.
- resize and context events.
- app action audit.

## 14. Embed SDK

- short-lived signed embed token.
- external object context constraint.
- ticket list/create/summary/internal note.
- CSP/frame-ancestors/origin allowlist.
- parent postMessage protocol versioning.

## 15. Kafka/CQRS scale path

도입 전:

- durable consumers가 둘 이상인지.
- local event publication 한계 측정.
- outbox/retry/rebuild semantics 확정.

도입 순서:

```text
local event
→ durable publication
→ outbox
→ Kafka externalization
→ separate projection consumers
```

MSA 분리는 독립 배포·소유권·데이터 경계가 검증된 모듈만 고려한다.
