# Analytics, Explore-like Reporting, and Data Export

## 1. 목표

Zendesk Explore와 유사하게 상담 운영자가 티켓 흐름, backlog, 응답 시간, SLA, 이관, 자식 협업, 자동화와 연동 상태를 이해하도록 한다. 첫 버전은 자유로운 BI builder가 아니라 **정의가 명확한 curated dashboard**다.

## 2. Source datasets

### Ticket current/final fact

한 티켓당 한 row 또는 projection.

```text
created/solved/reopened/current status
requester safe dimensions
channel/form/group/assignee/priority
parent/child flags
latest timestamps
```

### Update fact

TicketAudit/AuditEvent 기반, 변경 한 건 또는 audit 단위.

```text
actor/source/event/field/before/after/time
comment visibility/channel metadata
transfer and assignment changes
```

### Interval facts

- status intervals
- assignment/group intervals
- requester wait/agent work intervals

### Backlog snapshot

특정 시점의 미해결 티켓을 매일/시간별 snapshot한다. 현재 tickets row로 과거 backlog를 추측하지 않는다.

### SLA/OLA fact

Target instance and result.

### Access/security fact

보안 운영용 별도 dataset. 일반 상담 성과 dashboard에 개인 감시 지표를 무분별하게 섞지 않는다.

### Automation/integration fact

Trigger execution, webhook delivery, Platform API operation, export job.

## 3. Metric governance

모든 metric은 다음을 가진다.

```text
metric key
human name
definition
numerator
denominator
inclusions/exclusions
timezone/business time
source dataset
calculation version
owner
```

`docs/16-metric-glossary-draft.md`를 source of truth로 승격하고 변경을 versioning한다.

## 4. Initial dashboards

### Operations overview

- created/solved tickets by day
- current backlog
- aging buckets
- reopen rate
- unassigned count

### Response and resolution

- first reply p50/p90/p95
- next reply
- total resolution
- requester wait/agent work

### Team flow

- tickets by group
- transfer count/rate
- assignment changes
- child ticket volume and cycle time
- open child tasks

### SLA

- achieved/breached/no-policy
- at-risk due soon
- policy/group/priority breakdown

### Automation/integration

- trigger executions/failures/loops blocked
- webhook success/retry/dead letter
- Platform API errors/rate limits/idempotency replay

## 5. Query architecture stages

### Stage A — PostgreSQL operational reporting

Curated SQL views/materialized views for small self-hosted instances. Queries must not lock or overload ticket command path.

### Stage B — Analytics schema/projections

Asynchronous projection tables and scheduled snapshots. Rebuild/checkpoint support.

### Stage C — External warehouse/export

Incremental export to customer-controlled warehouse. Kafka only when durable independent consumers justify it.

## 6. Dimensions and privacy

Default dashboards use operational dimensions, not comment body or raw search query.

- customer PII masked or excluded
- staff performance metrics require explicit policy and context
- access/security analytics permission-separated
- small cohort suppression may be needed in production
- export field allowlist and audit

## 7. Time semantics

- store UTC
- query/report timezone explicit
- business minutes use schedule version
- “created on day” uses selected reporting timezone
- reopened and final solve semantics defined
- late-arriving projection update policy documented

## 8. Snapshot jobs

Daily backlog snapshot:

```text
snapshot_at
status/group/priority/age bucket dimensions
count
calculation_version
```

Job:

- idempotent for same snapshot instant/version
- backfillable
- checkpointed
- alerts on delay/failure

## 9. Drill-down

Dashboard tile can link to a permission-filtered ticket view. Metric aggregate count must explain scope and may differ for users with different permissions.

Drill-down is not allowed to expose inaccessible tickets or protected audit data.

## 10. Exports

### Snapshot export

User-selected filter and fields; async job; short-lived artifact.

### Incremental export

Stable cursor over canonical changes/projections. At-least-once, duplicate-safe consumer examples.

### Ticket content export

Separate privileged field set for subject/public/internal comments, attachments metadata, audit. Requires explicit permission, reason, retention and audit.

## 11. Dashboard UI

- date range/timezone
- scope/group filters
- metric definition tooltip/link
- loading/stale/data updated at
- no-data vs no-permission
- accessible chart alternative table
- p50/p90/p95 rather than average alone

## 12. Performance

- no unbounded scan on request path
- explain analyze baseline for each primary dashboard query
- materialized view refresh strategy
- partitions only after measured need
- query timeout and result limits

## 13. Gates

- `ANA-001`: ticket/update/interval facts reconcile with canonical sample
- `ANA-002`: historical backlog independent of current ticket state
- `ANA-003`: timezone and reopened semantics match glossary
- `ANA-004`: SLA numerator/denominator reconciles target instances
- `ANA-005`: permission-filtered drill-down cannot leak ticket
- `ANA-006`: accessible table equivalent for charts
- `ANA-007`: export field authorization/expiry/audit
- `ANA-008`: projection rebuild/checkpoint and query-plan evidence
