# Integration Platform Specification

Status: proposed baseline for Integration v1
Owner: integration bounded context

## 1. Purpose

Deskseed must integrate with shopping-mall admin, order/payment systems, operations tools, internal admin consoles, n8n, Workato, and future third-party apps without direct database access or brittle browser automation.

The integration platform has four distinct surfaces:

1. **Platform REST API** — synchronous reads and commands
2. **Outbound events and webhooks** — push-based changes
3. **Snapshot and incremental exports** — bulk or continuous data extraction
4. **SDK and extension surfaces** — generated API clients first, sandboxed Agent App SDK and Embed SDK later

These surfaces share actor, authorization, idempotency, audit, and versioning foundations but are not one generic endpoint.

## 2. Primary use cases

### Order/admin system creates a support ticket

```text
Order admin
  → POST Platform API with Idempotency-Key
  → Ticket command
  → Ticket + first comment + audit
  → ExternalReference to order
  → response with ticket number and ETag
```

### Agent sees external business context

```text
Ticket sidebar
  → ExternalReference list
  → order/payment labels and safe deep links
  → later app loads allowlisted live data through server-side proxy
```

### External system receives ticket changes

```text
Ticket changed
  → canonical integration event
  → WebhookDelivery intent
  → signed HTTP attempt
  → retry/dead-letter/replay
```

### Data warehouse consumes changes

```text
Warehouse job
  → incremental export cursor
  → deduplicate by event ID
  → advance cursor after durable write
```

### Internal admin embeds a ticket panel

Later:

```text
External backend
  → issues short-lived signed embed token
Browser admin
  → loads Deskseed embed UI
  → token scopes user/system/ticket context
```

## 3. Explicit non-use cases for v1

- arbitrary SQL access
- arbitrary code plugins inside the Spring process
- public marketplace
- full bidirectional synchronization of every external object field
- API key staff impersonation
- generic workflow DSL
- synchronous external API calls inside ticket transactions
- SDK-specific business behavior not represented in OpenAPI

## 4. Integration identity and authorization

### 4.1 IntegrationClient

An IntegrationClient is a machine principal, not a StaffAccount.

Required attributes:

```text
id
name
description
status
scopes
resourceConstraints
credentialMetadata
expiresAt
createdAt/createdBy
lastUsedAt/lastUsedIp
```

### 4.2 Phase 1 credential

Use a scoped API key:

```text
dsk_live_<public-key-id>.<secret>
```

- public key ID locates the credential row
- secret is displayed once
- only a slow/secure hash or appropriate verifier is stored
- revoke is immediate
- rotation creates a new secret with bounded overlap
- expiration is required for production clients
- optional IP/CIDR allowlist

### 4.3 Phase 2 OAuth

Add only when needed:

- client credentials for managed machine-to-machine integration
- authorization code + PKCE for a human-authorized third-party app
- refresh token rotation and revocation
- explicit delegated staff/customer subject

The platform must not accept arbitrary actor IDs in headers or request bodies.

### 4.4 Scopes and resource constraints

The I1 management/authentication slice freezes the v1 credential vocabulary to exactly:

```text
tickets:create
tickets:read
tickets:update
tickets:comment:internal
```

No Platform Ticket API is exposed by this slice. The broader candidate vocabulary below is reserved for a later contract-freeze decision and must not be accepted by the I1 client-management API:

```text
tickets:read
tickets:create
tickets:update
tickets:comment:internal
tickets:comment:public
customers:read
customers:write
external-references:read
external-references:write
webhooks:manage
exports:read
```

Resource constraints describe where:

```text
allowedGroupIds
allowedTicketKinds
allowedExternalSystemKeys
allowedFields
allowedCommentVisibilities
customerPiiFieldSet
```

Authorization is the intersection of scope and constraint. A broad scope with a narrow constraint remains narrow.
When a configured resource dimension is missing from an authorization request, authorization fails closed; callers cannot omit group or ticket kind to bypass a configured constraint.

## 5. Platform API design

### 5.1 Separate adapter

`/api/v1/platform/**` is a separate adapter from agent/admin/customer APIs.

It may invoke the same application commands but must have:

- stable external DTOs
- public-safe fields
- machine authentication
- scopes and constraints
- idempotency
- explicit concurrency
- rate limits
- external actor attribution
- compatibility policy

### 5.2 Minimum v1 operations

```text
POST   /api/v1/platform/tickets
GET    /api/v1/platform/tickets/{ticketNumber}
PATCH  /api/v1/platform/tickets/{ticketNumber}
POST   /api/v1/platform/tickets/{ticketNumber}/internal-comments
```

PUBLIC follow-up comment, ExternalReference CRUD, event feed, export, webhook, SDK, OAuth, and admin/settings routes are not part of v1.

Integration-client management belongs under `/admin/integrations/**`, not the Platform API itself.

### 5.3 Ticket creation kinds

The client must explicitly choose the origin/visibility intent.

```text
CUSTOMER_REQUEST
  requester required
  first comment PUBLIC

INTERNAL_WORK_ITEM
  requester optional; omit it instead of fabricating a customer
  first comment INTERNAL
  never exposed as a customer request by accident
```

Do not infer visibility from missing fields.

`CUSTOMER_REQUEST` creation writes one TicketAudit and then publishes one transaction-local
`TicketSubmitted` fact with `channel=API` and `source=PLATFORM_API`. A matching idempotency
replay returns the stored response without a second audit, state interval, or First Reply target/fact.
`INTERNAL_WORK_ITEM` also receives the creation-state projection but never starts First Reply SLA.
The fact is a projection trigger, not an external integration event or an event-sourcing source of truth.

### 5.4 Stable identifiers

- internal UUID is opaque
- ticket number is stable and human-readable
- integration event ID is globally unique and stable across delivery retries
- external IDs remain strings to preserve leading zeroes and provider formats
- ExternalSystem key is stable and operator-managed

### 5.5 Concurrency

Read responses include:

```text
ETag: "ticket-v12"
```

Updates require:

```text
If-Match: "ticket-v12"
```

A stale update maps to the same domain conflict semantics as agent updates. The Platform API returns RFC 9457 Problem Details and current ETag when safe.

## 6. Idempotency

### 6.1 Why mandatory

External clients retry after timeout, connection reset, 429, or ambiguous response. A ticket/comment create without idempotency can duplicate customer-facing work.

### 6.2 Identity

```text
clientId + operationId + Idempotency-Key
```

Do not use key alone globally; different clients may choose the same string.

### 6.3 Canonical request hash

Hash a canonical representation of the operation-relevant request:

- HTTP operation ID
- normalized path identifiers
- normalized JSON body
- selected semantic headers

Exclude volatile headers such as request ID.

### 6.4 Behavior

| Existing record | New request | Result |
|---|---|---|
| none | any | create IN_PROGRESS then execute |
| SUCCEEDED, same hash | retry | replay original status/body/resource |
| any, different hash | misuse | 409 |
| IN_PROGRESS, same hash | concurrent retry | 409 + Retry-After by initial decision |
| retryable failure before business commit | same hash | permit retry |
| final business validation failure | same hash | replay same final problem |

The idempotency record and business mutation must not leave an ambiguous committed state. Use a documented transaction/state machine and test crash points.

The implementation stores only SHA-256 representations of the idempotency key and canonical request. Successful responses and deterministic
final 4xx validation responses are retained for replay; retryable persistence/audit failures roll back the reservation. The launch default
retention is 7 days. A scheduled bounded-batch cleanup deletes expired final receipts through the expiry index. Expired `IN_PROGRESS`
reservations receive a separate configurable grace period before global cleanup so an in-flight command is not mistaken for abandoned work;
same-identity retries remain `IN_PROGRESS` conflicts during that grace. Cleanup exports deleted-row, oldest-backlog-age, and failure metrics.

## 7. External object links

### 7.1 Link before mirror

An order/payment/member system remains the source of truth. Deskseed stores an `ExternalReference`:

```text
systemKey: commerce-admin
objectType: ORDER
externalId: ORDER-123
displayLabel: 주문 ORDER-123
deepLinkUrl: https://admin.example.com/orders/ORDER-123
metadataSnapshot: { storeName, orderStatus }
```

### 7.2 Security rules

- `https` only in production
- host must match ExternalSystem allowlist
- reject userinfo, unsupported schemes, oversized URLs, control characters
- server does not fetch the URL by default
- frontend opens with safe `noopener/noreferrer` behavior
- metadata is allowlisted scalar JSON and size-limited
- secrets, card data, auth tokens, full external payloads are forbidden

### 7.3 Consistency

- same external object may link to several tickets
- same exact link on one ticket is unique
- removing a link does not delete the external object
- optional live metadata refresh is a separate asynchronous integration feature
- stale snapshot is labelled with observed time/source

## 8. Outbound integration events

### 8.1 Event envelope

The public envelope is versioned and CloudEvents-inspired without claiming conformance until tested.

```json
{
  "id": "event-uuid",
  "type": "ticket.updated",
  "version": 1,
  "occurredAt": "2026-08-10T03:00:00Z",
  "subject": "ticket:ticket-uuid",
  "sequence": 42,
  "correlationId": "corr-uuid",
  "causationId": "audit-uuid",
  "data": {}
}
```

### 8.2 Public event safety

Integration event data is an allowlisted projection. It must not automatically include:

- internal comment body
- customer PII
- access audit data
- staff private metadata
- secrets

Subscription configuration and client scope determine optional fields.

### 8.3 Initial event types

```text
ticket.created
ticket.updated
ticket.solved
ticket.reopened
ticket.comment.created
ticket.transferred
ticket.child.created
external_reference.created
```

Do not publish every internal domain event. Public events are stable product contracts.

## 9. Webhook delivery

### 9.1 Delivery contract

Headers:

```text
X-Deskseed-Event-Id
X-Deskseed-Delivery-Id
X-Deskseed-Timestamp
X-Deskseed-Signature
```

Signature:

```text
HMAC(secret, timestamp + "." + rawBody)
```

Receiver checks:

- timestamp tolerance
- signature with constant-time comparison
- delivery/event ID deduplication
- body size/content type

### 9.2 Retry policy

Initial proposal:

- short connection/read timeout
- exponential backoff with jitter
- retry network errors, timeout, 408, 429, selected 5xx
- do not retry most 4xx
- bounded attempts then dead letter
- circuit breaker per subscription

Exact schedule is configuration, not public semantic contract.

### 9.3 Operations

Admin can view:

- event and subscription
- attempt number and time
- response status
- failure class
- next retry
- response snippet after secret/PII sanitization

Manual replay:

- requires permission and reason
- keeps original event ID
- creates a new delivery attempt
- records who replayed it

## 10. Incremental export

### 10.1 Use case

Bulk consumers should not poll every ticket or depend on webhook-only delivery.

### 10.2 Cursor semantics

- opaque cursor represents committed sequence position
- stable ordering by sequence then event ID
- consumers may see duplicates and must deduplicate by event ID
- cursor is advanced only after consumer durably stores the page
- deleted/redacted objects appear as tombstones when allowed
- backfill and cursor-expiry behavior is documented

### 10.3 Snapshot export

Ad-hoc CSV/JSON export is a background job:

- filter/field/auth snapshot
- manifest with schema version and time range
- encrypted object storage
- short-lived download URL
- expiry/deletion
- request/completion/download audit

## 11. SDK strategy

### 11.1 Generated first

OpenAPI 3.1 is the source of truth. Generate:

- TypeScript
- Python
- JVM/Kotlin-friendly client

SDK responsibilities:

- authentication setup
- request ID propagation
- idempotency helper
- cursor iteration
- ETag/If-Match helper
- typed Problem Details exceptions
- retry helper for safe operations

SDK must not invent hidden server behavior.

### 11.2 Versioning

- API and SDK use semantic versioning
- additive compatible contract → minor
- bug/doc/example → patch
- breaking contract → major or `/api/v2`
- generated code is reproducible from committed OpenAPI and generator config
- generated source is not manually patched without changing templates/contract

### 11.3 Release verification

Each SDK release must:

- compile/package
- authenticate against a test server
- create a ticket idempotently
- repeat the same request without duplicate
- read and update using ETag
- handle 403, 409, 429, 5xx
- iterate a cursor page
- verify webhook signature example where language package includes helper

## 12. Agent App SDK — later

### 12.1 Why separate from generated SDK

Generated SDK calls REST APIs. Agent App SDK runs inside Deskseed UI and needs context/events/actions without exposing broad credentials.

### 12.2 Execution model

- iframe sandbox, not backend plugin execution
- manifest-declared locations/scopes/origins
- short-lived app session bound to installation, staff, ticket context
- strict postMessage origin checks and nonce
- Content Security Policy
- per-app rate/resource limits

### 12.3 Proposed bridge

```text
client.context.get(["ticket.number", "ticket.status", "customer.id"])
client.events.subscribe("ticket.updated", handler)
client.actions.invoke("ticket.addInternalComment", payload)
client.http.request(namedConnection, request)
```

`http.request` is a server-mediated proxy using an admin-configured named connection. Long-lived provider secrets never enter browser JavaScript.

## 13. Embed SDK — later

### 13.1 Purpose

Allow an order/ops admin page to show a Deskseed ticket panel.

### 13.2 Initial scope

- create ticket with external reference
- list tickets linked to current external object
- read selected ticket summary/conversation according to scope
- open full Deskseed workspace via deep link

Full edit and public reply come later.

### 13.3 Authentication

- external backend signs a short-lived embed token or exchanges identity server-to-server
- token includes issuer, subject, audience, expiry, external object context, allowed actions
- token does not contain a long-lived IntegrationClient secret
- user mapping and delegated attribution are explicit

## 14. Audit obligations

Every integration operation records at least:

```text
integrationClientId
actorType
operationId
requestId
idempotencyKeyHash (not raw when avoidable)
resource IDs
scope decision
outcome/status
latency
IP/client metadata
```

Ticket mutations additionally create TicketAudit. Reads create AccessAuditEvent. Credential and webhook configuration changes create AdminSecurityAuditEvent. Webhook attempts stay in IntegrationDeliveryLog and may project summary events into the Audit Explorer.

## 15. Threat model checklist

- stolen API key
- overly broad scope
- staff impersonation
- replayed create/comment request
- idempotency key collision/misuse
- external deep-link XSS/SSRF/open redirect
- webhook secret theft
- forged webhook
- duplicate/out-of-order events
- endpoint exfiltration of PII
- malicious app iframe
- browser secret leakage
- export link leakage
- SDK supply-chain/reproducibility
- audit bypass by alternate endpoint

Each implementation task must state which threats it changes.

## 16. Definition of Integration v1 done

Integration v1 is complete only when:

1. an admin can create, rotate, expire, and revoke a scoped client;
2. the client can idempotently create/read/update a constrained ticket;
3. it can add an internal comment with correct actor attribution;
4. it can link a safe external order reference;
5. unauthorized scope/resource access fails without leaking data;
6. every read/write is visible in the appropriate audit ledger;
7. webhook delivery is signed, retryable, observable, duplicate-safe, and replayable;
8. incremental export resumes from cursor and tolerates duplicates;
9. TypeScript, Python, and JVM SDK smoke tests pass against the same OpenAPI contract;
10. no external credential, raw authorization header, or forbidden PII appears in logs or audit payloads.
