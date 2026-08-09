# Minimum Verification Gates

이 문서는 “테스트가 있다”가 아니라 **무엇이 통과되어야 다음 단계로 간주하는지**를 정의한다. Codex는 각 작업에서 해당 gate ID를 완료 보고에 적는다.

## 0. Evidence rules

A gate is passed only with:

- automated test output, or
- reproducible command and captured result, or
- manual security/UX scenario with explicit steps where automation is not yet practical

AI explanation, code review comment, or “looks correct” is not evidence.

## 1. Repository and architecture gates

### ARCH-001 — Module boundary

- `ApplicationModules.verify()` passes.
- no module imports another module's internal package.
- `portal`, `staff-access`, `platform-api` do not share controllers/HTTP DTOs.

### ARCH-002 — Migration ownership

- clean PostgreSQL starts from zero with Flyway.
- Hibernate validate succeeds.
- application startup cannot create/alter production schema.

### ARCH-003 — Network boundary

- no ticket command transaction performs outbound HTTP.
- integration delivery is represented by durable intent before network call.
- test injects downstream timeout and proves ticket commit is not rolled back or duplicated.

### ARCH-004 — Actor context

For customer, staff, integration, trigger/system paths:

- actor type/ID/source/request ID/correlation are present.
- untrusted actor impersonation headers are ignored/rejected.
- audit records carry the same accepted context.

## 2. Core ticket gates

### TKT-001 — First comment, no description

- schema and domain model have no ticket description column/property.
- request `message` becomes first `PUBLIC` comment.
- ticket, comment, audit commit atomically.

### TKT-002 — Visibility isolation

- customer projection never returns internal comments.
- customer projection never returns internal child tickets or audit metadata.
- regression test attempts direct ID access.

### TKT-003 — Assignment invariant

- assignee without group is rejected.
- assignee outside group is rejected.
- group change clears or rejects incompatible assignee according to command policy.

### TKT-004 — Transfer vs child

- transfer preserves ticket ID/number and changes ownership.
- child creation preserves parent ownership and creates separate internal ticket.
- child is absent from all customer endpoints.

### TKT-005 — Parent solve warning

- open child returns structured warning.
- parent solve still commits.
- child status remains unchanged.

### TKT-006 — Field-aware concurrency

- same-field stale update returns 409 and no partial hidden overwrite.
- disjoint-field merge follows documented policy.
- response includes conflicting fields/current version.
- UI displays red conflict banner.

## 3. Ticket change audit gates

### CHG-001 — Atomic change audit

Inject TicketAudit persistence failure:

- ticket/comment/field change does not commit.
- error is observable.
- retry does not create duplicate side effects.

### CHG-002 — One command, one audit

A save containing comment + group + assignee + status:

- creates one TicketAudit for that ticket.
- creates ordered events for actual changes only.
- preserves command/request/correlation/actor/source.

### CHG-003 — Structured diff

- each supported field records typed before/after.
- no full JPA entity serialization.
- secret/unsupported field values are redacted or rejected.

### CHG-004 — Append-only enforcement

Using runtime DB role:

- UPDATE canonical audit fails.
- DELETE canonical audit fails.
- application has no ordinary update/delete repository for audit.
- migration/retention role is separately documented.

### CHG-005 — Global query

With synthetic history:

- filter by ticket number returns all relevant change events.
- filter by actor/date/action/field works.
- result shows structured diff without opening each ticket.
- cursor pagination has no duplicate/omission under stable dataset.

## 4. Access and search audit gates

### ACC-001 — Semantic ticket view

Given a staff user opens ticket 1042:

- successful response creates one `TICKET_VIEWED` for the interaction ID.
- refresh/poll with same interaction ID does not create another semantic view.
- new intentional navigation creates a new event.
- unauthorized attempt does not create success event.

### ACC-002 — Strict audit failure

Inject AccessAuditEvent insert failure:

- sensitive ticket/customer/search response is not returned successfully.
- response is a stable `audit-write-unavailable` problem.
- no protected body is leaked in error/log.

### ACC-003 — Search execution

- records actor, time, source, interaction ID, filters, sort, result count.
- stores redacted query and keyed fingerprint.
- raw query is never plaintext in DB/log when encrypted mode is configured.
- secrets/patterns are masked according to policy.

### ACC-004 — Search-to-view linkage

- ticket opened from results has `originSearchEventId`.
- direct ticket navigation has no false origin.
- Audit Explorer can traverse search → opened tickets.

### ACC-005 — Customer profile access

- opening customer profile records a separate event.
- ticket list that only shows minimal requester label does not falsely count as full profile view.
- field-level projection and event semantics are documented.

### ACC-006 — External API read

- each successful Platform API ticket/customer read records `API_RESOURCE_READ` with IntegrationClient.
- resource constraint denial records security denial without success access event.

### ACC-007 — Log injection and secret scan

Automated scan verifies audit/application logs do not contain:

- Authorization header
- API key secret
- password/session cookie
- webhook secret
- raw search query outside protected ciphertext policy

Newlines/control characters in actor/query/labels cannot forge additional log rows.

## 5. Audit Explorer authorization gates

### AUD-001 — Read-only role

`SECURITY_AUDITOR`:

- can use allowed audit endpoints.
- cannot update tickets, staff, groups, policies, clients, or webhooks without separately granted permissions.
- ordinary Agent cannot access audit endpoints by default.

### AUD-002 — Separate sensitive reveal

- normal audit read omits protected comment body/raw query.
- reveal requires specific scope, reason, and configured reauthentication.
- reveal result is not cached publicly.
- reveal action creates its own AdminSecurityAuditEvent.

### AUD-003 — Audit the auditor

The following create events with actor/filter/resource/outcome:

- Audit Explorer opened
- canonical event detail opened
- protected content revealed
- export requested
- export downloaded

### AUD-004 — Export authorization

- export fields are allowlisted by permission.
- requester permission snapshot is stored.
- artifact is inaccessible before completion.
- URL expires.
- request/completion/download/deletion are audited.
- one auditor cannot access another unauthorized export by guessing ID.

### AUD-005 — Projection rebuild

- delete/recreate test AuditActivityProjection.
- rebuild from canonical ledgers.
- counts and sampled records match.
- canonical writes remain available while projection recovery policy is exercised.

### AUD-006 — Tamper evidence baseline

Before external checkpoint feature:

- append-only DB privileges/triggers pass.
- canonical event includes stable ID and digestable representation.
- documented limitation states DB superuser can bypass local controls.

After checkpoint feature:

- modified/deleted test row causes verification failure.
- external signed checkpoint cannot be recomputed with DB-only credentials.

## 6. Integration credential gates

### INT-AUTH-001 — Secret lifecycle

- secret displayed once.
- only verifier/hash is stored when possible.
- secret cannot be retrieved later.
- revoked/expired/suspended client fails.
- rotation overlap works and old key expires/revokes.
- lifecycle changes are security-audited.

### INT-AUTH-002 — Scope

For each Platform API operation:

- missing scope returns 403 stable problem.
- broad scope but denied resource constraint returns 403.
- client cannot escalate scope via request body/header.
- customer PII fields require explicit field permission.

### INT-AUTH-003 — No staff impersonation

- `X-Actor-Id`, `createdByStaffId`, similar input cannot change actor.
- machine-created ticket/comment audit actor is IntegrationClient.
- delegated human attribution only works through verified grant when implemented.

### INT-AUTH-004 — Rate limit

- per-client limit enforced.
- 429 includes Retry-After.
- rate-limit event contains client/outcome but no secret.
- retry after window with same idempotency key creates no duplicate.

## 7. Idempotency and concurrency gates

### IDEM-001 — Same request replay

Run same client/operation/key/body twice:

- one ticket/comment/reference exists.
- second response represents original result.
- no duplicate audit/business event.

### IDEM-002 — Key misuse

Same key with different body:

- 409 `idempotency-key-reused`.
- no second mutation.
- security/usage event can identify misuse without storing secret/raw key if policy hashes it.

### IDEM-003 — Concurrent duplicate

Two simultaneous same-key requests:

- exactly one business mutation.
- loser receives documented in-progress/replayed result.
- no deadlock or duplicate.

### IDEM-004 — Crash points

Failure injection at:

1. after idempotency record reservation
2. before business commit
3. after business commit before response persistence
4. after response persistence before network response

For each, retry converges to one committed outcome or a documented final failure.

### CONC-001 — ETag

- GET returns ETag.
- stale If-Match update fails.
- matching update succeeds and returns new ETag.
- SDK surfaces conflict distinctly.

## 8. External reference gates

### EXT-001 — Safe URL

Reject:

- non-HTTPS production URL
- non-allowlisted host
- userinfo URL
- control characters
- oversized URL
- unsupported scheme such as file/gopher

Frontend opening behavior prevents opener access.

### EXT-002 — No server fetch

Creating/viewing a reference does not cause backend network request. Test with controlled URL counter.

### EXT-003 — Uniqueness and link semantics

- same exact reference cannot duplicate on one local object.
- same external object may link to multiple tickets if policy allows.
- unlink does not delete external object.
- metadata size/field allowlist enforced.

### EXT-004 — Audit

Create/remove reference records actor, local object, external system/type/ID, source, outcome. Forbidden secret metadata is rejected before audit payload serialization.

## 9. Webhook gates

### WH-001 — Signature

Reference receiver verifies:

- correct raw-body HMAC succeeds.
- body/timestamp/signature tamper fails.
- expired timestamp fails.
- old/new secret overlap works during rotation.

### WH-002 — Retry and duplicate

- simulated timeout/429/5xx produces bounded retries.
- event ID remains stable.
- attempt/delivery IDs are distinct.
- receiver dedup example processes business event once.

### WH-003 — Dead letter and replay

- exhausted delivery reaches dead-letter state.
- authorized replay creates new attempt.
- original event ID/causation retained.
- replay actor/reason is audited.

### WH-004 — SSRF boundary

Endpoint configuration rejects or protects:

- loopback/link-local/private ranges according to deployment policy
- metadata-service addresses
- DNS rebinding strategy is documented/tested
- redirect to forbidden address
- excessive response body

Self-hosted operators may explicitly allow private endpoints, but policy is opt-in and visible.

### WH-005 — Secret/log safety

Webhook secret and Authorization values never appear in:

- API response after creation
- application logs
- delivery attempt response snippet
- Audit Explorer

## 10. Incremental export and SDK gates

### EXP-001 — Cursor

- stable page ordering.
- resume from cursor without missing committed events.
- documented duplicates are identifiable by event ID.
- wrong filter/cursor combination rejected.
- expired cursor has recovery path.

### EXP-002 — Tombstone

- deletion/redaction produces documented tombstone when allowed.
- consumer example handles it.
- protected data is not leaked in tombstone.

### SDK-001 — Reproducible generation

- clean checkout generates byte-equivalent or functionally reproducible SDK artifacts from committed contract/config.
- no manual generated-file patch is required.

### SDK-002 — Language smoke tests

TypeScript, Python, JVM/Kotlin clients each:

- authenticate
- idempotently create
- read
- update with ETag
- handle 403/409/429
- iterate cursor

### SDK-003 — Breaking change detection

CI compares public OpenAPI changes and blocks unapproved breaking changes.

## 11. Retention and privacy gates

### RET-001 — Category-specific policy

- different categories can have different retention.
- raw query ciphertext expires before or separately from metadata.
- export artifact expires separately from export event.

### RET-002 — Retention job

- selects only eligible rows/partitions.
- records policy version, range, count, outcome.
- retry is idempotent.
- legal-hold-marked data is excluded when hold exists.

### RET-003 — Backup documentation

- backup/replica retention is documented.
- deleting primary rows does not falsely claim immediate deletion from backups.

### RET-004 — Encryption rotation

For encrypted search query/credentials:

- key version recorded.
- old key can be rotated/re-encrypted or retired by policy.
- missing key fails safely.
- plaintext never appears in migration/log.

## 12. Performance evidence gates

No universal millisecond target is claimed without defined hardware. The portfolio must include reproducible baseline and budget.

### PERF-001 — Ticket queue

- dataset generator
- query plan before/after index
- p50/p95 under defined local hardware/container limits
- no N+1 regression

### PERF-002 — Audit Explorer

At least one million synthetic activity rows:

- actor+date query plan captured
- ticket+date query plan captured
- action+date query plan captured
- first cursor page measured
- index/storage trade-off documented

### PERF-003 — Access write overhead

Measure sensitive read with and without access audit on defined environment. Record:

- p50/p95 latency delta
- throughput delta
- DB write amplification
- chosen strict availability trade-off

### PERF-004 — Idempotency contention

Concurrent same-key benchmark proves one mutation and records lock/wait behavior.

## 13. Portfolio release checklist

The first strong portfolio release requires:

```text
[ ] Core MVP M1-M6
[ ] R1 Access Audit Foundation
[ ] R2 Unified Audit Explorer
[ ] architecture/module gates
[ ] core domain gates
[ ] Ticket Change Audit gates
[ ] Access/Search Audit gates
[ ] Audit Explorer authorization gates
[ ] documented performance baseline
[ ] demo scenario and threat model
```

Integration v1 is a following release and requires all `INT-*`, `IDEM-*`, `EXT-*`, `WH-*`, `EXP-*`, and `SDK-*` gates relevant to implemented scope.
