# Public API and SDK Contract Governance

## 1. Objective

외부 전산이 Deskseed와 연결될 때 내부 코드 구조나 데이터베이스를 알아야 하지 않도록 한다. Public Platform API와 generated SDK는 장기간 유지해야 하는 제품 계약이다.

## 2. Source of truth

`api/platform-api-outline-v1.yaml`은 범위와 naming을 보여주는 시작점이다. 실제 구현 전에는 이를 완전한 OpenAPI 3.1 문서로 확장한다.

Contract-first sequence:

```text
user scenario
  → API use-case and threat review
  → OpenAPI change
  → example and negative cases
  → contract review
  → server implementation
  → generated SDK
  → conformance tests
```

Runtime annotation output은 drift detection에 사용할 수 있지만 source of truth를 자동으로 대체하지 않는다.

## 3. Compatibility policy

### Compatible by default

- optional response field added
- new endpoint
- new enum value only when clients are documented to tolerate unknown values
- new optional request field with old behavior preserved
- new Problem Details extension clients may ignore

### Potentially breaking

- required field added
- field removed/renamed/type changed
- response visibility expanded or reduced unexpectedly
- enum closed-set expansion for generated clients that cannot tolerate it
- pagination ordering changed
- error status/problem type changed
- default comment visibility changed
- scope meaning broadened or narrowed
- idempotency/concurrency behavior changed

Breaking changes require major SDK/API version or a migration period with dual behavior.

## 4. Resource and action naming

- nouns for resources
- explicit subresource for commands that create records
- avoid RPC endpoint explosion but prefer semantic command when PATCH cannot express atomic behavior
- ticket number in human-facing path
- stable operationId for SDK generation

Examples:

```text
createTicket
getTicket
updateTicket
createTicketComment
createExternalReference
listTicketEvents
```

Operation IDs are public identifiers and cannot be casually renamed.

## 5. Request metadata

### Required for external writes

```text
Authorization
Idempotency-Key
Content-Type
```

### Required for updates

```text
If-Match
```

### Request tracing

```text
X-Request-Id
```

Client may send it; server validates allowed format/length or creates a new ID. Server always returns its accepted request ID.

### SDK user agent

Generated SDKs send a bounded identifier:

```text
Deskseed-Python/1.2.0
Deskseed-TypeScript/1.2.0
Deskseed-JVM/1.2.0
```

Do not include customer data or secrets.

## 6. Error contract

Use RFC 9457 Problem Details.

Common fields:

```json
{
  "type": "/problems/insufficient-scope",
  "title": "Insufficient scope",
  "status": 403,
  "detail": "The client is not allowed to perform this operation.",
  "instance": "/api/v1/platform/tickets/1042",
  "requestId": "req-...",
  "errors": []
}
```

Security rules:

- `detail` is useful but does not reveal protected resource existence or internals
- stable `type` identifies machine behavior
- SDK maps known types to typed exceptions but preserves unknown extensions
- 429 includes `Retry-After`
- conflict includes current ETag/version when disclosure is safe

## 7. Pagination

### Cursor requirements

- opaque to client
- signed or tamper-resistant
- stable ordering documented
- filter/sort binding prevents using cursor with different query
- page size bounded
- no total count requirement for large collections
- cursor expiry documented

SDK provides iterator helpers but exposes raw cursor for durable jobs.

## 8. Filtering and sparse fields

Start with allowlisted query parameters rather than arbitrary SQL-like filter syntax.

```text
status
groupId
assigneeId
updatedAfter
externalSystemKey
externalObjectType
externalId
```

Sparse field selection is deferred until PII/authorization semantics are clear. Public API must not accept arbitrary column names.

## 9. Idempotency SDK behavior

SDK helper can generate a UUID key, but business integrations should provide stable keys based on their operation.

Good:

```text
order-123:create-support-ticket:v1
payment-987:add-investigation-note:attempt-1
```

Poor:

```text
random UUID generated again on every retry
```

SDK behavior:

- preserve caller key across automatic retry
- never automatically retry unsafe write without a key
- expose original/replayed response metadata
- surface key-reuse conflict distinctly

## 10. Retry policy

SDK may automatically retry:

- GET/HEAD on network error or selected 5xx
- idempotent writes with caller-provided key on network ambiguity, 429, selected 5xx

SDK must:

- honor `Retry-After`
- use bounded exponential backoff with jitter
- expose max attempts/timeout
- not retry validation/auth/scope failures
- preserve request ID/idempotency key appropriately
- avoid retry storms

Server remains correct even when SDK retry is disabled.

## 11. SDK packaging

### TypeScript

- ESM first, documented runtime support
- fetch-compatible transport abstraction
- browser use limited to delegated/short-lived credentials; long-lived API keys are server-side only

### Python

- sync client first, optional async only after actual need
- context manager for transport lifecycle
- typed models and exceptions

### JVM/Kotlin

- Java-friendly API with Kotlin examples
- blocking client first to match server/portfolio scope
- avoid forcing Spring dependencies on consumers

Exact package names are decided before first public release and then treated as stable.

## 12. Examples repository

Minimum examples:

1. create an internal ticket linked to an order
2. repeat create with same idempotency key
3. read ticket and update with ETag
4. handle conflict and reload
5. add internal comment
6. verify webhook signature
7. deduplicate webhook event
8. consume incremental export cursor
9. rotate API credential
10. n8n/Workato generic webhook workflow

Examples must not contain real secrets or customer data.

## 13. Contract test matrix

For every public operation:

- happy path
- missing/invalid credential
- expired/revoked credential
- missing scope
- resource constraint denied
- validation failure
- idempotency replay and mismatch when write
- stale ETag when update
- rate limit
- audit attribution
- secret/PII log scan

Generated SDK smoke tests run the same behavior against a real PostgreSQL-backed test environment.

## 14. API deprecation

When deprecating:

- mark OpenAPI operation/field deprecated
- document replacement and deadline
- return deprecation/sunset headers if appropriate
- instrument usage by client ID
- notify operators in admin UI
- keep audit of clients still using deprecated behavior
- remove only in a breaking release after migration evidence

## 15. Agent App SDK contract governance

Agent App SDK has a separate version from Platform API because it exposes UI context/events/actions.

Rules:

- manifest schema versioned
- locations and scopes allowlisted
- host bridge method names stable
- app cannot access arbitrary browser DOM or backend bean
- server proxy uses named connection, not raw secret access
- app installation/update/permission grant is audited
- app action is attributed to both staff actor and app installation

## 16. Embed SDK contract governance

Embed SDK is a thin loader for a Deskseed-hosted UI, not a full API credential wrapper.

- short-lived token
- strict audience/origin
- context-bound external system/object
- allowed action set
- no wildcard postMessage target
- CSP and frame-ancestor policy
- user and integration attribution captured
- token issuance/validation failures audited
