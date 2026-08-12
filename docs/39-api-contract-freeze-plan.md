# API Contract Freeze Plan

## 1. Source of truth

각 API surface는 독립 OpenAPI 문서로 관리한다.

```text
api/core-api-outline-v1.yaml       customer/agent/admin/audit outline
api/platform-api-outline-v1.yaml   machine integration outline
api/integration-event-envelope-v1.schema.json
api/audit-activity-event-v1.schema.json

# split after public-contract pressure justifies it
api/customer-api-v1.yaml
api/agent-api-v1.yaml
api/admin-api-v1.yaml
api/audit-api-v1.yaml
api/platform-api-v1.yaml
api/automation-api-v1.yaml   (later)
api/analytics-api-v1.yaml    (later)
```

현재 outline은 계약 동결 전 단계다. 구현 PR은 해당 endpoint를 full schema/examples/errors/security까지 승격해야 한다.

## 2. Surface ownership

| Surface | Actor | Stability |
|---|---|---|
| Customer | anonymous/customer | public stable |
| Agent | staff UI | internal but contract-tested |
| Admin | admin UI | internal but audited |
| Audit | auditor/admin | highly sensitive |
| Platform | machine clients/SDK | public stable |
| Webhook events | external consumers | versioned public |

## 3. Endpoint inventory

### Customer v1

```text
POST /api/v1/requests
GET  /api/v1/requests/{ticketNumber}
POST /api/v1/requests/{ticketNumber}/comments       later
GET  /api/v1/customer/requests                       account later
GET  /api/v1/customer/requests/{ticketNumber}        account later
```

### Agent v1

```text
POST /api/v1/agent/session
DELETE /api/v1/agent/session
GET  /api/v1/agent/me
GET  /api/v1/agent/views
GET  /api/v1/agent/views/{viewKey}/tickets
POST /api/v1/agent/tickets
GET  /api/v1/agent/tickets/{ticketNumber}
POST /api/v1/agent/tickets/{ticketNumber}/commands
GET  /api/v1/agent/tickets/{ticketNumber}/audits
POST /api/v1/agent/tickets/{ticketNumber}/children
POST /api/v1/agent/tickets/{ticketNumber}/transfer
GET  /api/v1/agent/search
```

### Admin v1

```text
GET/POST/PATCH /api/v1/admin/staff...
PUT/DELETE /api/v1/admin/staff/{staffId}/audit-authorities/{authority}
GET/POST/PATCH /api/v1/admin/groups...
PUT /api/v1/admin/settings/customer-access-mode
GET/PUT /api/v1/admin/permissions...
GET/POST /api/v1/admin/integration-clients
GET      /api/v1/admin/integration-clients/{clientId}
POST     /api/v1/admin/integration-clients/{clientId}/disable
POST     /api/v1/admin/integration-clients/{clientId}/revoke
POST     /api/v1/admin/integration-clients/{clientId}/rotate
```

Integration client create/rotate responses are `no-store` one-time secret envelopes. The I1 freeze adds management endpoints only; `/api/v1/platform/**` remains unexposed until the Platform Ticket API slice.

### Audit v1

```text
GET  /api/v1/audit/activities
GET  /api/v1/audit/activities/{id}
POST /api/v1/audit/activities/{id}/reveal
POST /api/v1/audit/exports
GET  /api/v1/audit/exports/{jobId}
GET  /api/v1/audit/exports/{jobId}/download
```

### Platform v1

`api/platform-api-outline-v1.yaml`과 `docs/18`, `docs/20`을 따른다. Customer/Agent/Admin/Audit 현재 outline은 `api/core-api-outline-v1.yaml`이다.

## 4. Contract-first flow

1. user story and authorization.
2. path/operationId.
3. request/response schemas.
4. examples.
5. problem types.
6. pagination/idempotency/ETag.
7. OpenAPI lint.
8. generated mock/client.
9. controller contract test.
10. SDK diff.

## 5. Common headers

```text
X-Request-Id (server may replace invalid input)
X-Correlation-Id
Idempotency-Key (external/selected create commands)
If-Match / ETag
Retry-After
```

## 6. Error contract

RFC Problem Details + extensions:

```json
{
  "type": "/problems/validation",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more fields are invalid.",
  "requestId": "...",
  "errors": [{"field": "subject", "code": "required"}]
}
```

민감한 existence/authorization 정보는 detail에 넣지 않는다.

## 7. Pagination

- ticket views/audit/export: stable cursor.
- cursor opaque and signed/versioned.
- response `nextCursor`.
- sort tuple documented.
- small admin lists는 optional zero-based `page`와 bounded `size`를 사용한다.
- 기존 array body 호환성을 유지하면서 `X-Page-Number`, `X-Page-Size`, `X-Total-Count`, `X-Total-Pages`로 navigation metadata를 반환한다.

## 8. Compatibility

Compatible:

- optional response field addition.
- new enum only if consumer policy handles unknown; otherwise version.
- new optional query parameter.

Breaking:

- field removal/rename/type change.
- required input addition.
- meaning/authorization change.
- pagination order change.

## 9. SDK generation

OpenAPI에서 TypeScript/Python/JVM clients를 생성하고 thin handwritten layer가 auth, retry, errors, pagination을 제공한다.

SDK release마다:

- reproducible generation.
- three language smoke tests.
- API compatibility diff.
- example integration run.

## 10. Contract freeze gate

기능 코딩 전 다음을 승인한다.

- path and actor.
- auth and permission.
- request/response.
- errors.
- audit obligations.
- idempotency/concurrency.
- rate limit.
- PII classification.
