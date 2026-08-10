# API Contract

## 1. Contract principles

- Base media type: JSON
- Error format: RFC 9457 `application/problem+json`
- Public contract source: OpenAPI 3.1 under `api/`
- Version prefix: `/api/v1`
- Internal UUIDs are not required for human workflows; ticket number is stable and human-facing.
- JPA entities are never serialized.
- Every response carries `X-Request-Id` and `X-Correlation-Id`.
- Clients may supply either identifier when it matches the bounded identifier policy; the server generates a replacement when it is absent or invalid.
- Every write response includes current ticket version or ETag where applicable.
- External write APIs require `Idempotency-Key`.
- External update APIs require `If-Match` or an explicit expected version.
- Cursor pagination is preferred over page-number pagination.

## 2. API surfaces

```text
/api/v1/requests/**           customer-facing
/api/v1/agent/**              agent workspace
/api/v1/admin/**              admin management
/api/v1/audit/**              security auditor
/api/v1/platform/**           external systems
/api/v1/integration-hooks/**  inbound provider webhook adapters, later
```

Same use case may be called by more than one surface, but DTO, projection, authentication, error detail, rate limit, and stability guarantees remain separate.

## 3. Customer request surface

### Create request

```http
POST /api/v1/requests
Content-Type: application/json
X-Request-Id: request-123
X-Correlation-Id: support-session-456
```

```json
{
  "name": "김철수",
  "email": "hello@example.com",
  "subject": "결제가 되지 않아요",
  "message": "카드 결제 버튼을 누르면 오류가 발생합니다."
}
```

Atomic result:

- Customer created or safely resolved
- Ticket created
- first `PUBLIC` comment created from `message`
- one TicketAudit with ordered creation/comment events
- opaque request access token issued and only its hash stored

```json
{
  "ticketNumber": 1042,
  "status": "NEW",
  "accessToken": "returned-only-once",
  "createdAt": "2026-08-10T03:00:00Z"
}
```

### View request

```http
GET /api/v1/requests/1042
X-Request-Access-Token: returned-only-once
```

Only public comments and customer-safe fields are returned.

## 4. Agent surface

### Combined ticket update

```http
POST /api/v1/agent/tickets/1042/updates
Content-Type: application/json
```

```json
{
  "expectedVersion": 12,
  "changedFields": ["status", "priority"],
  "status": "PENDING",
  "priority": "HIGH",
  "comment": {
    "visibility": "PUBLIC",
    "body": "현재 결제 내역을 확인하고 있습니다."
  }
}
```

One user save creates one TicketAudit. No-op fields do not create audit events.

### Conflict

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json
```

```json
{
  "type": "/problems/ticket-field-conflict",
  "title": "Ticket fields changed concurrently",
  "status": 409,
  "detail": "Some fields were changed by another actor.",
  "currentVersion": 14,
  "conflictingFields": ["assignee"],
  "requestId": "req-..."
}
```

The agent UI shows a red banner and does not silently overwrite same-field changes.

### Create child ticket

```http
POST /api/v1/agent/tickets/1042/children
```

```json
{
  "targetGroupId": "group-uuid",
  "assigneeId": "staff-uuid-or-null",
  "subject": "PG 승인 로그 확인",
  "message": "주문번호 123의 승인 로그와 취소 여부를 확인해 주세요."
}
```

`message` is the first `INTERNAL` comment of the child. Parent ownership remains unchanged.

## 5. Audit surface

### Search unified activity

```http
GET /api/v1/audit/activities?from=...&to=...&actorId=...&ticketNumber=1042&action=TICKET_VIEWED
```

Supported filters grow over time but v1 includes:

- time range
- actor type and actor ID
- ticket number
- customer ID
- action/event type
- changed field
- source
- IP address
- integration client ID
- outcome
- search fingerprint or privileged raw-query search

Response items are normalized projections with links to canonical records.

```json
{
  "items": [
    {
      "activityId": "act-uuid",
      "occurredAt": "2026-08-10T03:00:00Z",
      "ledgerType": "TICKET_CHANGE",
      "action": "STATUS_CHANGED",
      "actor": {
        "type": "STAFF",
        "id": "staff-uuid",
        "displayName": "상담사 A"
      },
      "ticketNumber": 1042,
      "summary": "상태를 OPEN에서 PENDING으로 변경",
      "source": "AGENT_WORKSPACE",
      "requestId": "req-...",
      "outcome": "SUCCESS"
    }
  ],
  "nextCursor": "opaque-cursor"
}
```

### Reveal protected search query

```http
POST /api/v1/audit/access-events/{eventId}/reveal-search-query
```

Requires `audit:search-query:reveal`, a reason, and strong reauthentication if configured.

```json
{
  "reason": "2026-08 내부 보안 조사 INC-42"
}
```

The reveal itself creates `AUDIT_SENSITIVE_CONTENT_REVEALED`.

### Export audit activities

```http
POST /api/v1/audit/exports
```

- requires `audit:export`
- stores filter and field selection snapshot
- creates requested/completed/downloaded events
- result URL is short-lived
- raw protected content is excluded unless explicitly authorized

## 6. Platform API authentication

Phase 1:

```http
Authorization: Bearer dsk_live_<key-id>.<secret>
```

The key identifies `IntegrationClient`. The secret is shown once and only a secure hash is stored.

Required headers on write:

```text
Authorization
Idempotency-Key
X-Request-Id        optional from client; generated if absent
If-Match            required for versioned updates
```

The server must ignore any untrusted `X-Staff-Id`, `X-Actor-Id`, or similar impersonation header.

## 7. Platform API v1 minimum resources

### Create ticket

```http
POST /api/v1/platform/tickets
Authorization: Bearer ...
Idempotency-Key: order-123-support-case-1
```

```json
{
  "requester": {
    "name": "김철수",
    "email": "hello@example.com"
  },
  "subject": "주문 취소 확인 필요",
  "initialComment": {
    "visibility": "INTERNAL",
    "body": "운영 전산에서 생성한 조사 요청입니다."
  },
  "groupId": "ops-group-uuid",
  "priority": "NORMAL",
  "externalReferences": [
    {
      "systemKey": "commerce-admin",
      "objectType": "ORDER",
      "externalId": "ORDER-123",
      "displayLabel": "주문 ORDER-123",
      "deepLinkUrl": "https://admin.example.com/orders/ORDER-123"
    }
  ]
}
```

The API explicitly distinguishes a customer-originated public ticket from an internal ticket. The client cannot set arbitrary actor IDs.

### Read ticket

```http
GET /api/v1/platform/tickets/1042
```

Requires `tickets:read` and resource constraints. Response is an integration projection, not the full staff view by default.

### Update ticket

```http
PATCH /api/v1/platform/tickets/1042
If-Match: "ticket-v12"
Idempotency-Key: update-order-123-state-4
```

```json
{
  "status": "OPEN",
  "priority": "HIGH"
}
```

### Add comment

```http
POST /api/v1/platform/tickets/1042/comments
Idempotency-Key: ops-note-555
```

The client needs separate scope for `PUBLIC` and `INTERNAL` comment. Default integration clients can create internal comments only.

### Add external reference

```http
POST /api/v1/platform/tickets/1042/external-references
Idempotency-Key: link-order-123-ticket-1042
```

The server validates system key, object type, URL scheme, host allowlist, metadata size, and uniqueness.

## 8. Scope vocabulary

Initial scopes:

```text
tickets:read
tickets:create
tickets:update
comments:internal:write
comments:public:write
customers:read
customers:write
external-references:read
external-references:write
webhooks:manage
exports:read
audits:changes:read
audits:access:read
```

A scope grants capability, not universal resource access. `resourceConstraints` may additionally limit groups, ticket kinds, comment visibility, external system key, or field set.

## 9. Idempotency contract

Identity:

```text
(integrationClientId, HTTP method, normalized route/operation, Idempotency-Key)
```

Stored record:

```text
key
requestHash
status: IN_PROGRESS | SUCCEEDED | FAILED_RETRYABLE | FAILED_FINAL
resourceId?
responseStatus
responseBodyHash / replayable response
expiresAt
```

Rules:

1. same key + same canonical request → replay original result
2. same key + different canonical request → `409 idempotency-key-reused`
3. in-progress duplicate → `409` or documented `425/Retry-After`; choose one consistently
4. transient infrastructure failure before commit can be retried
5. key retention must exceed the documented client retry window

## 10. Pagination and incremental export

Collection APIs use opaque cursor and stable ordering.

```json
{
  "items": [],
  "nextCursor": "opaque",
  "hasMore": true
}
```

Incremental export:

```http
GET /api/v1/platform/incremental/ticket-events?cursor=...
```

- cursor represents committed event sequence, not page number
- duplicate records may occur around recovery; consumers deduplicate by event ID
- tombstone records represent deletion/redaction where policy permits
- cursor expiry and backfill behavior are documented

## 11. Rate limiting

- limits are per IntegrationClient and optionally per endpoint category
- 429 responses include `Retry-After`
- successful response headers expose remaining quota when practical
- idempotent retry after 429 must not create duplicate writes
- rate-limit denials create security/usage events without logging secrets

## 12. Webhook contract

Headers:

```text
X-Deskseed-Event-Id
X-Deskseed-Timestamp
X-Deskseed-Signature
X-Deskseed-Delivery-Id
```

Signature input:

```text
<timestamp>.<raw-request-body>
```

- HMAC secret supports overlapping rotation
- event ID remains stable across retries
- delivery ID identifies one subscription delivery
- attempt number changes per retry
- 2xx succeeds
- network errors, timeout, 408, 429, and selected 5xx retry
- manual replay creates a new attempt and preserves original event ID

The canonical event schema is under `api/integration-event-envelope-v1.schema.json`.

## 13. Problem types

Minimum stable problem types:

```text
/problems/validation-error
/problems/authentication-required
/problems/insufficient-scope
/problems/resource-not-allowed
/problems/rate-limit-exceeded
/problems/idempotency-key-required
/problems/idempotency-key-reused
/problems/idempotency-request-in-progress
/problems/ticket-field-conflict
/problems/external-reference-conflict
/problems/audit-write-unavailable
/problems/protected-audit-content
```

Problem response never includes stack traces, SQL, credential material, internal class names, or unauthorized resource existence details.
