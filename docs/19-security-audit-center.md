# Security & Audit Center Specification

Status: required for Portfolio Release Gate
Primary role: `SECURITY_AUDITOR`

## 1. Goal

운영자 또는 보안 감사 담당자가 티켓을 하나씩 열지 않고도 다음 질문에 답할 수 있어야 한다.

- 어떤 상담원이 어떤 티켓을 열어봤는가?
- 특정 고객의 프로필을 누가 열람했는가?
- 상담원이 어떤 검색어와 필터를 사용했는가?
- 그 검색 결과에서 어떤 티켓을 열었는가?
- 특정 기간에 어떤 티켓 필드가 누구에 의해 어떻게 바뀌었는가?
- 누가 공개 답변 또는 내부 메모를 추가했는가?
- 누가 계정·권한·그룹·SLA·Trigger·API client·Webhook 설정을 바꿨는가?
- 누가 감사 로그를 조회·원문 공개·export했는가?
- 외부 시스템 또는 자동화가 어떤 티켓을 읽거나 바꿨는가?

## 2. What this is not

- 일반 application log viewer가 아니다.
- APM/trace UI가 아니다.
- Event Sourcing store가 아니다.
- 모든 request/response body를 저장하는 packet recorder가 아니다.
- 관리자가 마음대로 수정하는 업무 테이블이 아니다.

Operational telemetry는 장애와 성능을 설명하고, audit은 actor의 업무 데이터 접근·변경을 설명한다.

## 3. Separate canonical ledgers

### 3.1 Ticket Change Audit

Purpose: 업무 상태 변경의 설명 가능성

Canonical records:

```text
TicketAudit
TicketAuditEvent
```

Examples:

- TICKET_CREATED
- COMMENT_CREATED
- STATUS_CHANGED
- PRIORITY_CHANGED
- GROUP_CHANGED
- ASSIGNEE_CHANGED
- TICKET_SOLVED
- TICKET_REOPENED
- CHILD_TICKET_CREATED
- EXTERNAL_REFERENCE_CREATED

Properties:

- same transaction as ticket mutation
- one command per ticket → one audit
- ordered events
- structured before/after
- append-only

### 3.2 Access & Search Audit

Purpose: 민감 데이터 열람과 탐색 경로 조사

Canonical record:

```text
AccessAuditEvent
```

Examples:

- TICKET_VIEWED
- CUSTOMER_PROFILE_VIEWED
- SEARCH_EXECUTED
- SEARCH_RESULT_OPENED
- ATTACHMENT_VIEWED
- ATTACHMENT_DOWNLOADED
- EXPORT_REQUESTED
- EXPORT_DOWNLOADED
- API_RESOURCE_READ

### 3.3 Admin & Security Audit

Purpose: 권한·보안·설정·자격증명 변화와 보안 결과 조사

Canonical record:

```text
AdminSecurityAuditEvent
```

Examples:

- LOGIN_SUCCEEDED
- LOGIN_FAILED
- ACCESS_DENIED
- STAFF_CREATED/DISABLED
- ROLE_CHANGED
- GROUP_MEMBERSHIP_CHANGED
- CUSTOMER_ACCESS_MODE_CHANGED
- INTEGRATION_CLIENT_CREATED/ROTATED/REVOKED
- WEBHOOK_CREATED/SECRET_ROTATED/DISABLED
- AUDIT_LOG_VIEWED
- AUDIT_SENSITIVE_CONTENT_REVEALED
- AUDIT_EXPORT_REQUESTED/DOWNLOADED
- RETENTION_POLICY_CHANGED
- RETENTION_JOB_EXECUTED

### 3.4 Integration Delivery Log

Purpose: webhook/export delivery 운영과 재처리

Canonical records:

```text
WebhookDelivery
WebhookAttempt
ExportJob
ExportArtifact
```

This is operationally append-oriented but not identical to security audit. Important summary actions are projected into the Audit Explorer.

## 4. Unified Audit Explorer

The UI queries a rebuildable `AuditActivityProjection` or a query service that normalizes canonical ledgers.

### 4.1 Default views

- **Ticket changes**
- **Access and searches**
- **Admin and security**
- **Integrations and exports**

A single advanced view can combine them.

### 4.2 Filters

Minimum filters:

```text
from/to
ledger type
action/event type
actor type
actor ID/name snapshot
ticket number
customer ID
support group
changed field
source
integration client
IP address
auth type
outcome/status
request ID
correlation ID
search fingerprint or privileged query search
```

### 4.3 Result row

```text
occurredAt
actor
source
action
resource/ticket
summary
outcome
IP/client
request ID
expand link to canonical detail
```

### 4.4 Detail drawer

Ticket change detail shows:

- audit ID and command ID
- actor/source/correlation
- expected/result version
- ordered events
- structured old/new values
- immutable comment metadata
- optional inline comment body reveal if authorized

Access detail shows:

- actor/session/interaction
- ticket/customer/search resource
- IP, user agent, auth type
- query redacted/fingerprint
- raw query reveal control if available
- result count and filters
- origin search → opened ticket relationship

## 5. Semantic ticket-view events

### 5.1 Why HTTP request logging is insufficient

A React page can fetch the same ticket repeatedly for polling, focus refresh, prefetch, and related panels. Recording every GET as “상담원이 티켓을 열었다” creates misleading noise.

### 5.2 Interaction model

The staff UI generates a UUID `interactionId` when the user intentionally opens a ticket.

```text
user click / direct navigation / new tab
  → new interactionId
  → ticket detail request
  → successful authorization and data read
  → one TICKET_VIEWED per actor+ticket+interactionId
```

Background refresh reuses the same interaction ID and does not create a new semantic view. New browser refresh or navigation may create a new interaction.

### 5.3 Trust boundary

The server does not trust the client for authorization, but may use client interaction metadata to classify an already authorized read. It enforces deduplication and can flag invalid/reused IDs.

External Platform API reads do not use semantic UI view deduplication. Each request creates `API_RESOURCE_READ` because the machine call itself is the access event.

## 6. Search audit and privacy

### 6.1 Why special handling is required

A search box can contain customer email, phone, card-like numbers, health data, passwords accidentally pasted, or investigative keywords. The audit requirement to know “what was searched” conflicts with data minimization.

### 6.2 Stored representations

Each `SEARCH_EXECUTED` event stores:

```text
queryRedacted
queryFingerprint
queryCiphertext        // required authenticated ciphertext
queryNonceOrEnvelope
queryKeyVersion
filters
sort
resultCount
searchMode
interactionId
```

#### Redacted query

Human-readable but masks configured patterns:

- credential-like tokens
- authorization headers
- card/account patterns
- national identifiers if configured
- excessive free text after a maximum length

#### Fingerprint

Use keyed HMAC over normalized query, not a plain hash. This permits equality/correlation without making short common queries easy to reverse from a dictionary.

#### Encrypted raw query

Required for every `SEARCH_EXECUTED` event.

- authenticated encryption
- key stored outside DB
- key version recorded
- associated data binds event ID and purpose
- shorter retention than metadata; default 30 days
- reveal requires privileged permission and reason
- reveal is audited
- bulk reveal is disabled initially

When access audit is enabled, a missing/invalid raw-query encryption key is a startup/configuration failure. Never fall back to plaintext or silently omit the original query.

### 6.3 Search-result linkage

```text
SEARCH_EXECUTED event S1
  resultCount=25

TICKET_VIEWED event V1
  ticket=1042
  originSearchEventId=S1
```

This enables investigations such as “검색어 X를 사용한 뒤 어떤 고객 티켓을 열었는가?”

### 6.4 Querying raw search terms

Normal Audit Explorer search uses redacted values or fingerprint. Searching decrypted raw terms requires a privileged endpoint; mass decryption is not the default list query.

## 7. Ticket modification visibility

### 7.1 Field changes

Use structured audit events:

```json
{
  "eventType": "STATUS_CHANGED",
  "field": "status",
  "before": "OPEN",
  "after": "PENDING"
}
```

Allowed value types:

- string enum
- number/boolean
- timestamp
- stable reference `{id, displaySnapshot}`
- bounded string list
- redacted JSON for explicitly approved custom fields

Never serialize an entire entity graph.

### 7.2 Comments

Initial comments are append-only. Audit stores:

```text
commentId
author
visibility
contentLength
contentHash
```

The explorer can fetch immutable comment content inline only with `audit:content:reveal`. This avoids copying every potentially sensitive body into multiple ledgers.

If comment editing/redaction is later introduced:

- create a new immutable comment version or redaction record
- preserve who/when/why
- define whether prior content is encrypted, legally held, or destroyed
- never silently overwrite original text

### 7.3 Bulk modifications

A bulk action has:

- root operation/correlation ID
- per-ticket command/audit result
- summary event with requested/succeeded/failed counts

One giant audit must not obscure per-ticket outcomes.

## 8. Authorization model

### 8.1 Roles and scopes

Minimum role:

```text
SECURITY_AUDITOR
```

Granular authorities:

```text
audit:changes:read
audit:access:read
audit:security:read
audit:integration:read
audit:content:reveal
audit:search-query:reveal
audit:export
```

`SECURITY_AUDITOR` is read-only by default and does not implicitly get ticket mutation or admin configuration permissions.

### 8.2 Self-auditing

The following actions create AdminSecurityAuditEvent:

- opening Audit Explorer
- applying sensitive filters when policy requires
- opening a canonical detail
- revealing comment/search protected content
- requesting/completing/downloading export
- changing audit/retention policy

### 8.3 Avoiding information leakage

Unauthorized users receive a consistent 403/404 policy. Security events can record attempted resource IDs, but ordinary responses must not confirm protected ticket/customer existence.

## 9. Persistence and integrity

### 9.1 Append-only enforcement

- migration owner owns schema
- runtime writer can INSERT required ledgers
- runtime reader can SELECT authorized views
- application roles cannot UPDATE/DELETE canonical audit
- DB trigger or privilege test proves rejection
- retention is executed by a dedicated privileged job, never general application CRUD

### 9.2 Tamper evidence

Database append-only controls do not defeat a database superuser. Later hardening:

1. canonical serialization and per-event digest
2. daily ordered Merkle/hash-chain checkpoint
3. checkpoint signed with key outside DB
4. checkpoint/export stored in independent object storage or SIEM
5. verification job detects missing/changed records

This provides tamper evidence, not magical prevention against every infrastructure administrator.

### 9.3 Clock and ordering

- canonical `occurredAt` is server UTC
- stable UUID event ID
- per-ledger monotonically increasing sequence where useful
- interaction/request/correlation IDs provide causal navigation
- client timestamp is optional metadata only

## 10. Failure semantics

### Ticket and admin writes

Change and audit are atomic. Audit failure rolls back the change.

### Sensitive reads/searches

Initial behavior is strict:

```text
authorize → read data → insert access event → commit → return response
```

If access event persistence fails, return a service/audit-unavailable problem instead of silently returning data.

### Audit projection failure

Canonical ledger success is enough for the original command/read. A failed unified projection can be rebuilt. Projection lag is shown in the Audit Explorer and monitored.

### Export failure

Export state and failure reason are recorded. Partial artifacts are not exposed. Download URL is created only after complete artifact and manifest are durable.

## 11. Retention

Retention is category-specific. Proposed launch defaults are in `docs/23-data-retention-and-privacy.md`.

Rules:

- raw search query shorter than access metadata
- export artifacts much shorter than export audit metadata
- ticket change audit follows support record policy
- security/admin audit retains enough history for credential/permission investigation
- deletion job records policy version, range, count, result
- legal hold, when implemented, overrides deletion
- backups and replicas must be included in retention documentation

## 12. Performance and partitioning

Start with PostgreSQL and indexed queries.

Suggested access patterns:

```text
occurredAt DESC + id
actorId + occurredAt
ticketId/ticketNumber + occurredAt
action + occurredAt
integrationClientId + occurredAt
originSearchEventId
queryFingerprint + occurredAt
requestId/correlationId
```

Do not add every possible index. Record query and plan evidence.

When volume grows:

- time-based partition access/security ledgers
- archive older partitions
- build normalized AuditActivityProjection
- separate analytics/warehouse only after measured operational impact

## 13. Audit Explorer UX acceptance scenarios

### Scenario A — suspicious ticket browsing

Auditor filters one agent and a date range, sees all `TICKET_VIEWED` events, opens a row, and follows the ticket number to the change history. No ticket-by-ticket manual search is required.

### Scenario B — suspicious search

Auditor filters `SEARCH_EXECUTED`, sees redacted query and result count, requests raw reveal with reason, then sees tickets opened from that search. All reveal actions are audited.

### Scenario C — unauthorized change investigation

Auditor filters ticket 1042, sees status/assignee/comment events with structured before/after and actor/source/request IDs. Immutable comment content can be revealed inline if authorized.

### Scenario D — integration behavior

Auditor filters IntegrationClient X, sees ticket reads/writes, idempotency outcome, external references, webhook configuration changes, and delivery/replay summary.

### Scenario E — audit misuse

A privileged user exports access logs. A second auditor can see who requested and downloaded the export, filter scope, record count, and protected fields included.

## 14. Definition of Portfolio Audit Gate done

The gate is complete only when:

1. successful staff ticket navigation creates exactly one semantic view event per interaction;
2. polling/prefetch does not inflate view counts;
3. search execution records protected query representations, filters, and result count;
4. ticket opened from search links to the search event;
5. ticket changes are globally searchable with before/after without opening each ticket;
6. `SECURITY_AUDITOR` can read required ledgers but cannot mutate tickets;
7. protected content reveal and audit export require separate permission and reason where specified;
8. audit view/reveal/export actions are themselves audited;
9. canonical ledgers reject update/delete from runtime application role;
10. ticket/admin change and audit are atomic;
11. sensitive read fails rather than silently losing audit when audit persistence is unavailable;
12. secrets and forbidden PII do not appear in application logs or audit payloads;
13. query plans and performance baseline exist for at least one million synthetic activity rows;
14. a documented retention job deletes only eligible categories and records its own execution.
