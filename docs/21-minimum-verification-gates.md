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
- staff display/ticket group mutation after canonical write does not change historical projection snapshots.
- concurrent canonical writer and rebuild share a lock protocol and converge without duplicate or missing rows.
- list status reads stored projection count rather than executing a full exact count per request.
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

## 14. Frontend and Zendesk-inspired workflow gates

### UI-001 — Agent shell and responsive density

- 1280, 1440, and 1920 deterministic visual snapshots.
- global navigation, work navigation, ticket tabs, properties, conversation/composer, context panel follow docs 28~31 and 51.
- at 1280, panels collapse without hiding critical status/ownership controls.
- no Zendesk logo, screenshot, copied illustration, or wordmark is packaged.

### UI-002 — Keyboard and focus

- all primary ticket actions are keyboard reachable.
- tab/menu/dialog/drawer semantics use accessible patterns.
- focus is restored after modal/drawer close.
- sticky composer/banner does not obscure focused content.
- automated accessibility checks have no critical/serious issue, plus manual keyboard scenario.

### UI-003 — Public/internal composer safety

- visibility is visible in text, not color alone.
- PUBLIC and INTERNAL drafts are separate.
- switching ticket tabs/modes preserves drafts.
- internal-note mode has a distinct accessible announcement.
- failed/conflicted submit preserves draft and does not duplicate comment.

### UI-004 — Workspace states

For queue, ticket, customer, admin and audit screens:

- loading, empty, validation, denied, not-found, server-error, stale/conflict states exist where applicable.
- errors include a safe request ID and recovery action.
- skeleton layout approximates final structure without recording access events.

### UI-005 — Visual change control

- screenshot fixtures use fixed Clock/data/fonts/animation.
- intentional changes are reviewed against task-level acceptance, not merely pixel approval.
- Garden major upgrade includes license, accessibility and snapshot review.

## 15. SLA/OLA gates

### SLA-001 — Business time calculator

- weekly interval, exception, holiday, timezone and DST fixtures pass.
- overlapping/invalid schedule intervals are rejected.
- server default timezone cannot change result.

### SLA-002 — First reply semantics

- first PUBLIC customer comment starts target.
- INTERNAL note does not achieve target.
- qualifying PUBLIC staff reply achieves once.
- automated acknowledgement only counts when policy explicitly says so.

### SLA-003 — Next-reply cycles

- consecutive customer PUBLIC comments produce the documented reply cycle.
- INTERNAL comments do not open or achieve a customer reply target.
- repeated/replayed events do not duplicate cycles.
- reopen behavior follows the metric glossary and policy version.

### SLA-004 — Pause/status interval rebuild

- status and assignment intervals rebuild from canonical ticket audits.
- rebuild is idempotent and reconciles the current open interval.
- pause/resume uses business-time boundaries and recorded schedule version.
- out-of-order projection recovery converges to the same result.

### SLA-005 — Policy snapshot and recalculation

- target stores policy, policy version, schedule version, target minutes and calculation version.
- editing policy does not rewrite historical targets.
- explicit recalculation is versioned, idempotent and audited.
- policy matching order is deterministic.

### SLA-006 — Breach scanner recovery

- multiple workers do not double-transition a target.
- outage catch-up marks overdue targets exactly once.
- read logic can identify overdue target before scanner materialization.
- bounded batch/checkpoint/lease state is observable.

### SLA-007 — Parent SLA and child OLA separation

- child creation does not implicitly pause parent customer SLA.
- child OLA is scoped to child/group rules and can be calculated independently.
- solving child cannot achieve or solve a parent SLA target.
- parent solve warning does not delete unfinished child OLA history.

### SLA-008 — Reporting reconciliation

- dashboard achieved/breached/excluded numerator and denominator match `docs/16-metric-glossary-draft.md`.
- no-policy and excluded tickets are not silently counted as achieved.
- selected timezone and business schedule version are visible.
- drill-down reconciles to target instances for the same scope.

## 16. Trigger and automation gates

### AUT-001 — Typed condition truth table

Every allowed operator/field combination has positive, negative, null, changed-from/to tests. Unsupported combinations are rejected before activation.

### AUT-002 — Ordered evolving state

- triggers execute in explicit position order.
- later trigger sees earlier resulting state.
- action evaluation uses one documented working state.
- provenance identifies rule/version/root causation.

### AUT-003 — No-op suppression and provenance

- actions that would not change state are suppressed.
- a trigger-originated mutation uses the normal Ticket command and one structured audit.
- audit identifies rule, immutable version, source, correlation and causation.
- repeated delivery cannot create duplicate comments or field events.

### AUT-004 — Invariant and authorization failure

- rule actions cannot bypass assignment, visibility, field or state invariants.
- unsupported or unauthorized action fails explicitly and does not leave a partial mutation.
- rule failure is observable and auditable without leaking secrets.
- activation rejects references to unavailable fields/actions.

### AUT-005 — Webhook post-commit boundary

- trigger creates durable outbound intent and commits without outbound HTTP.
- delivery failure/retry cannot rollback or re-run the original ticket command.
- correlation survives n8n/Workato callback.
- secret values are never interpolated into ticket text or ordinary logs.

### AUT-006 — Safety and loop prevention

- max depth/action/time budget is enforced.
- repeated rule-version/state fingerprint stops a loop.
- block/failure is visible and audited.
- arbitrary SQL/SpEL/JavaScript/Kotlin/Python is impossible through the definition API.

### AUT-007 — Dry run

- simulation has zero ticket, audit, webhook, notification, analytics or external side effect.
- preview explains matched conditions, proposed actions and rejected actions.
- preview uses a declared ticket snapshot/version.
- dry-run access is authorized and audited as an admin/security action where configured.

### AUT-008 — Version lifecycle

- activation freezes an immutable version.
- reorder, deactivate, activate and rollback are audited.
- rollback reactivates or copies a known version rather than mutating history.
- concurrent admin edits cannot silently overwrite each other.

### AUT-009 — Scheduled automation recovery

- candidate query is bounded and indexed.
- execution key prevents repeat in the same rule/ticket/window.
- crash between claim, action and checkpoint converges safely.
- multiple workers, downtime catch-up and disabled-rule races are covered.

## 17. Analytics and export gates

### ANA-001 — Canonical reconciliation

Ticket, update, interval, SLA, automation and integration facts reconcile to deterministic source fixtures with documented calculation version.

### ANA-002 — Historical backlog

- changing current ticket status does not rewrite prior snapshot.
- snapshot job is idempotent, backfillable and checkpointed.
- delayed/missing snapshot is observable.
- reprocessing the same instant/version does not double count.

### ANA-003 — Time and reopen semantics

- created/solved/reopened grouping follows the selected reporting timezone.
- first reply, resolution and reopen definitions match docs 16 and 46.
- business-minute measures use the recorded schedule version.
- p50/p90/p95 calculation has known fixture output and sample count.

### ANA-004 — SLA reconciliation

- SLA achieved/breached/excluded counts reconcile to `SlaTargetInstance` fixtures.
- target metric and policy version are filterable.
- no-policy tickets are separated from failed tickets.
- ticket drill-down returns the same target population as the tile.

### ANA-005 — Permission-safe drill-down

- aggregate uses only authorized scope or explicitly labels a broader pre-authorized scope.
- drill-down never reveals inaccessible ticket ID, customer, comment or audit data.
- protected access/security datasets use separate permissions.
- permission changes are honored on every drill-down/export request.

### ANA-006 — Accessible dashboard

- every chart has equivalent table or textual summary.
- no-data, loading, stale, error and no-permission states are distinct.
- keyboard and screen-reader labels communicate series, value and filters.
- color is not the only encoding of status or comparison.

### ANA-007 — Export

- field allowlist and actor permission snapshot are enforced.
- artifact is encrypted/private, expiring and audited.
- large export is asynchronous, bounded and cancellable.
- snapshot/incremental duplicate, cursor and tombstone semantics are tested.

### ANA-008 — Query, checkpoint and rebuild evidence

- primary dashboard query plans are captured at fixture scale.
- projection checkpoint/rebuild resumes after failure and reconciles to canonical data.
- rebuild is versioned and can run without corrupting the active projection.
- operational command path remains within documented performance budget during projection work.

## 18. Attachment, content, and channel gates

### FILE-001 — Upload limits and streaming bounds

- size, count, extension and MIME-family limits are enforced server-side.
- upload streams to quarantine/object storage without buffering unbounded bytes in memory.
- decompression/archive policy prevents zip-bomb or nested-archive abuse.
- rejected uploads leave no publicly accessible object.

### FILE-002 — Rich-text XSS sanitization

- the canonical format and renderer pass a maintained XSS payload corpus.
- scripts, event handlers, unsafe protocols and unapproved attributes are removed.
- client rendering is no less strict than server persistence.
- deterministic plain-text/search representation is generated.

### FILE-003 — Public/internal attachment isolation

- customer cannot fetch an INTERNAL attachment by guessed ID, copied URL or API path.
- authorization is rechecked when issuing a short-lived URL or streaming bytes.
- attachment visibility follows its linked comment.
- webhook/export excludes internal attachment content unless explicitly authorized.

### FILE-004 — Unsafe-file quarantine

- unscanned, scan-failed, infected or policy-blocked objects cannot inline or download.
- quarantine and scan states are visible to authorized staff.
- scan retries and object cleanup are idempotent.
- content type/disposition prevent active-content execution.

### FILE-005 — Privileged redaction

- redaction requires dedicated permission and reason.
- redaction is a distinct command, not ordinary comment edit/delete.
- customer, staff, search and export projections honor redaction.
- protected original-content handling follows explicit retention/key policy and is audited.

### FILE-006 — File access, deletion and retention audit

- upload, link, view, download, quarantine, delete and failure transitions are recorded as required.
- ordinary audit metadata never contains file bytes or full sensitive body.
- object lifecycle and DB metadata converge after retry/failure.
- backup/restore and retention tests include object storage.

### CHN-001 — Authenticated inbound provider boundary

- provider ingress is authenticated by signature, mTLS, private network or documented equivalent.
- replay window, body-size and rate limits are enforced.
- provider credentials and raw payloads are not written to ordinary logs.
- unauthenticated input cannot create a ticket/comment.

### CHN-002 — Inbound deduplication

- the same provider/message identifier creates exactly one canonical comment.
- concurrent duplicate deliveries converge to one result.
- deduplication survives process crash and retry.
- duplicate handling is observable without duplicate customer notification.

### CHN-003 — Thread-safe ticket association

- signed reply token/reference maps to the correct ticket without enumeration.
- ambiguous, expired or invalid association follows quarantine/reject policy.
- sender identity and participant authorization are checked separately from threading.
- an inbound reply cannot attach to an inaccessible/internal ticket by guessed subject/reference.

### CHN-004 — Email HTML and remote-content safety

- HTML uses the same or stricter sanitization policy as rich text.
- tracking pixels/remote content follow explicit policy.
- header/body/attachment limits are enforced.
- quote trimming is presentation-only and does not silently destroy canonical evidence.

### CHN-005 — Internal-note delivery isolation

- INTERNAL comment never creates customer email/webhook/channel delivery.
- notification template cannot read internal-only fields without explicit staff-only destination.
- changing composer mode cannot carry internal draft into public send.
- regression tests cover macros, triggers and retries.

### CHN-006 — Outbound post-commit intent

- public comment and TicketAudit commit with an outbound message intent, not a provider HTTP call.
- provider outage cannot rollback or duplicate the comment.
- recipient/template/version snapshots are retained for delivery investigation.
- delivery worker is idempotent.

### CHN-007 — Retry and resend idempotency

- timeout/5xx retry cannot duplicate a provider message where idempotency is available.
- manual resend is audited and does not create a second comment.
- bounce/permanent failure is terminal according to policy.
- attempt history and next retry are visible.

### CHN-008 — Recipient and header injection controls

- To/CC/BCC are derived from authorized ticket participants and explicit policy.
- CR/LF/header injection and malformed addresses are rejected.
- internal recipients are never exposed to customer recipients.
- unverified contact cannot be selected silently.

### CHN-009 — Delivery observability and audit

- queued, sending, sent, failed and bounced states are queryable.
- agent sees failure near the affected public comment and operations queue.
- sensitive payload is not duplicated in operational logs.
- correlation links ticket comment, notification intent and provider attempt.

### CHN-010 — Real-time contract before chat

- message ordering, session identity, reconnect, transcript finalization and delivery acknowledgement are specified before chat implementation.
- polling comments is not presented as equivalent to a real-time channel.
- out-of-order/duplicate message tests exist.
- session closure preserves canonical transcript and audit.

### CHN-011 — Channel permission and identity mapping

- channel-specific identity maps to Customer/participant explicitly.
- supported reply channel is authorized and visible to the agent.
- channel switch cannot send to an unverified or unintended identity.
- permissions are rechecked at send time.

### CHN-012 — Backpressure and scale evidence

- load test establishes connection/message/worker limits before WebSocket or broker architecture changes.
- bounded queues, overload response and retry policy are documented.
- operational metrics expose lag, disconnect and delivery failure.
- WebFlux/Kafka/Redis introduction requires measured evidence and ADR.

## 19. Self-hosted operations gates

### OPS-001 — Fresh install and upgrade

- documented Compose install starts from empty volumes.
- supported previous release upgrades through Flyway without manual data edits.
- failed migration has backup/forward-fix recovery instructions.

### OPS-002 — Backup and restore

- PostgreSQL and enabled object storage are backed up consistently.
- restore drill produces a working login, ticket, audit, attachment/reference sample.
- restore point, duration and data-loss window are recorded.

### OPS-003 — Secrets and bootstrap

- no default production password/secret.
- first admin bootstrap is one-time and auditable.
- session/API/webhook/mail/object-storage secrets can be rotated.
- `.env.example` contains no real secret.

### OPS-004 — Health and observability

- liveness/readiness distinguish process, DB, migration and required dependency states.
- logs carry request/correlation IDs but no protected content.
- metrics/alerts cover error rate, audit-write failure, outbox backlog, job lag and disk/storage risk.

### OPS-005 — Retention and maintenance

- retention jobs are dry-runnable, bounded, idempotent and audited.
- legal hold/exclusion works where enabled.
- operator can see pending migrations, failed jobs, dead letters and backup age.


## 20. Accepted policy extension gates

### AUTH-001 — Enumeration-safe magic-link request

- existing and unknown email addresses receive the same status, shape and comparable timing envelope.
- rate limiting is applied by normalized destination and requester network identity.
- response, audit and logs contain no account-existence signal or raw token.

### AUTH-002 — Single-use and expiry

- a generated magic-link token is accepted once before its deadline.
- replay, expiry, malformed token and concurrent consume attempts do not create a second session.
- only a token verifier/hash is stored; the URL token is absent from application logs and traces.

### AUTH-003 — Customer session isolation

- successful consume creates an HttpOnly, Secure-in-production, SameSite=Lax session cookie.
- one customer cannot list, read, comment on or claim another customer’s request.
- logout invalidates the session and protected endpoints stop working.

### AUTH-004 — Explicit anonymous-request claim

- matching verified email alone never auto-claims or lists an anonymous request.
- claim requires the original request-access token or a signed, ticket-specific claim grant.
- successful and denied claim attempts are security-audited without logging the secret.

### AUTH-005 — Password registration and email verification

- new and existing email input receives the same `202` shape and comparable work class; no response, log, audit, or mail decision exposes account existence.
- registration persists only an adaptive customer password hash and digest-only email/continuation proofs; raw password, token, continuation secret, and usable credential examples are absent.
- activation requires the email token and browser-bound continuation proof for the same unexpired intent; mismatch, replay, expiry, and concurrent consume create no account.
- profile, password account, current registration-policy acceptances, verification security audit, and durable mail intent effects commit or roll back at their documented boundaries.

### AUTH-006 — Password login isolation, throttling, and session rotation

- unknown email, wrong password, disabled account, passwordless account, and incomplete registration return one generic invalid-credential problem after real-or-dummy adaptive hash work.
- normalized purpose, destination, and requester-network limits are enforced through the storage-neutral `AuthenticationAttemptLimiter`; `429` includes `Retry-After` and reveals no account state.
- before a production adapter/migration is accepted, ADR 0043 evidence declares the target sustained rate, burst, concurrency, safety factor, latency budget, and unaffected business-transaction SLO, then exercises hot global/destination/network keys and high-cardinality traffic. `Blocked` evidence is not a pass and selects no adapter.
- the selected PostgreSQL or Redis adapter is atomic, has bounded retention/expiry, and is the only authoritative limiter store; a coarse ingress limit remains enabled and any local deny cache can deny but never grant.
- shared-store timeout or unavailable state returns the generic authentication-unavailable `503`; no limiter transaction/lock is held during adaptive password hashing, mail work, or audit persistence, and the target burst does not starve the normal business connection pool.
- successful login rotates the current customer session, binds it to the credential version, and appends one metadata-only security event; required audit failure returns no authenticated success.
- password, password hash, session cookie, Authorization value, raw email, and company name are absent from ordinary logs and audit metadata.

### AUTH-007 — Password reset single use and session revocation

- reset request is enumeration-safe, throttled, mail-backed, and issues only a purpose-bound digest-stored token for an active password account.
- one valid unexpired token can change the credential once; wrong-purpose, malformed, expired, replayed, and concurrent consume cannot produce a second change.
- successful reset increments credential version, revokes every existing customer session, consumes the token, and appends the required security audit atomically.
- an old session fails on its next protected request and reset never creates a new authenticated session implicitly.

### AUTH-008 — Passwordless eligibility and registration completion

- magic-link login mail is issued only for an eligible identity without a password while every request retains the same generic response.
- consume accepts only a single-use `PASSWORDLESS_LOGIN` token and rotates into a session whose projection requires registration completion.
- completion requires that session plus CSRF and current registration policy versions; password/profile/consents, credential-version update, session rotation/revocation, and security audit are atomic.
- completion never lists or claims an anonymous ticket from email equality; AUTH-004 proof remains required.

### CONSENT-001 — Immutable consent policy lifecycle

- only an active ADMIN with `customer-consent:manage`, current expected-actor guard, CSRF, and matching `If-Match` can create, edit, publish, or archive a policy.
- AGENT, SECURITY_AUDITOR, customer, and Integration Client direct access is denied without leaking protected resource state.
- publishing creates an immutable version; accepted historical versions remain resolvable and cannot be updated or deleted through the runtime application role.
- P0 rejects client-selected/future activation: publish uses one fixed server `Clock` value for both `effectiveAt` and `publishedAt`, atomically replaces the current pointer, and has no scheduled-version state.
- create/update rejects an HTTP body over 262,144 bytes; publish canonicalizes first and accepts at most 50,000 plain-text characters and 200,000 UTF-8 bytes. Boundary coverage includes 49,999/50,000/50,001 characters and multi-byte input at the UTF-8 limit.
- each context has at most 20 current policies. Publish serializes this check inside the mutation transaction so concurrent attempts to cross 20 allow at most one winner and never produce a truncated current-policy projection.
- policy mutation and its metadata-only Admin/Security audit commit or roll back together; document body is absent from audit metadata.

### CONSENT-002 — Current-version validation and atomic acceptance

- registration and request submission require every current required policy for their context exactly once and reject missing, duplicate, unknown, archived, wrong-context, or stale versions.
- final validation occurs in the account/ticket transaction; a prior policy projection is not an authorization token.
- append-only acceptance references the immutable policy/version and server time without duplicating the document body.
- acceptance, account or ticket mutation, and required audit commit or roll back together; acceptance data is absent from ordinary logs, webhooks, and ticket change metadata.

### CFG-001 — Typed field and option integrity

- field machine keys are immutable, each persisted EAV row matches exactly one allowed type column, and single-select values reference an active stable option ID.
- incompatible type change and inactive/foreign option input fail without partial configuration or ticket mutation.
- customer and staff projections apply independent server-side visibility/editability rules; direct ID input cannot reveal a staff-only definition, option, count, or value.

### CFG-002 — Immutable form lifecycle and conditional projection

- draft validation rejects unknown condition types, cycles, contradictory effects, and hidden-required dead ends.
- publish creates an immutable version and enforces at most one current default for each customer/agent audience.
- the server re-evaluates allowlisted facts and drops hidden/readonly values; frontend visibility is never authoritative.

### CFG-003 — Normalized tag lifecycle and assignment

- canonical tag values normalize deterministically and concurrent duplicate creation or assignment cannot create duplicate active identities.
- lifecycle changes require ADMIN authorization and atomic Admin/Security audit; ticket add/remove uses the normal ticket write policy and one TicketAudit.
- View/Search contributors enforce ticket authorization before tag conditions affect results, counts, or cursors.

### CFG-004 — Category-compatible custom status

- every custom status maps to one fixed canonical category and only one active default exists per category.
- a custom label cannot bypass allowed transitions, SLA category semantics, or terminal `CLOSED` behavior.
- older clients can rely on the canonical category while authorized projections resolve the selected label.

### CFG-005 — Runtime configuration command and read boundary

- agent configuration reads are server-projected, required-access-audited, and do not emit semantic `TICKET_VIEWED` during background refresh.
- one ticket configuration command enforces write scope, expected version, stable client command identity, form capability, and normalized field/tag/status inputs.
- exact replay returns the original result; misuse/conflict is non-mutating; one ordered TicketAudit and outbox fact commit with the ticket change.

### CFG-006 — Customer form candidate projection and submission binding

- the public initial projection accepts only optional `formId`; the server fixes `ticketKind=CUSTOMER_REQUEST`. Internal ticket kinds supplied through public inputs are rejected with the same existence-safe validation/unavailable semantics.
- initial and candidate projections expose only the current published customer form and customer-visible fields/options after server evaluation of allowlisted typed values.
- candidate projection is not an authorization token; final create repeats current form/version, visibility, requiredness, type, and option validation.
- a published form version freezes field/option IDs and machine keys, field type/validation, option membership/order, customer visibility/editability/requiredness, placement order, and condition rules. Display label/description resolves from current copy, is not historically reproducible, and a semantic field/option change requires a new ID.
- selected form/version is preserved even with zero custom values; hidden/readonly values are dropped and staff-only/unknown input returns an existence-safe validation problem.
- ticket, first PUBLIC comment, form selection, normalized values, one TicketAudit, required consent acceptances, CLEAN attachment links, access token, and mail intent obey the documented atomicity boundary.
- initial JSON/multipart creation requires a stable high-entropy `clientCommandId`; the scoped canonical request and ordered attachment-manifest hash make same-payload replay return one logical ticket, mismatched reuse return `409`, and concurrent finalization single-winner without persisting a raw command ID or access token.
- anonymous multipart uses a server-only planned Customer UUID for upload ownership before the final transaction. Third-file failure creates no Customer/Ticket/acceptance/mail intent, stale form/consent and audit/mail failure roll back Customer/Ticket/link state, earlier CLEAN objects remain unlinked for TTL cleanup, cross-owner linking is denied, and authenticated submission creates no new Customer.

### PERM-001 — Initial global agent read

- every active Agent can find and read every staff-visible ticket through Views, search, direct URL and parent/child navigation.
- inactive staff and customers cannot use this policy.
- global read does not grant Admin, Audit Explorer, export, protected reveal or integration-secret access.

### PERM-002 — Read does not imply cross-group write

- an Agent who can read an unrelated group ticket cannot mutate it unless the separate write policy permits it.
- the initial write policy accepts the current assignee or an active member of the ticket group.
- denied mutation creates no partial TicketChangeAudit and returns a stable authorization problem.

### PLAT-001 — Private-network Platform API boundary

- production profile requires a non-empty operator allowlist/trusted-proxy configuration for `/api/v1/platform/**`.
- public/untrusted source simulation is rejected before business data is returned.
- network placement never bypasses API-key authentication, scope, constraint, rate-limit or audit checks.

### PLAT-002 — Platform v1 operation allowlist

- v1 permits only ticket create, read, allowed-field update and INTERNAL comment.
- PUBLIC comment, admin/settings operations, arbitrary customer export and staff impersonation are absent or rejected.
- OpenAPI breaking-change check protects the frozen v1 surface.

### SEARCH-AUD-001 — Required encrypted original query

- every successful `SEARCH_EXECUTED` event stores the input-independent `[PROTECTED]` routine marker, keyed fingerprint, authenticated ciphertext and key version.
- migration and DB constraints scrub/reject content-derived routine query representations in both canonical detail and rebuildable projection.
- no plaintext query column, log, trace, metric, cache, ordinary export or webhook payload exists.
- missing/invalid active encryption key fails startup/readiness when access audit is enabled.

### SEARCH-AUD-002 — Protected query reveal

- list/detail projection omits plaintext by default.
- one-event reveal requires dedicated permission, non-empty reason and configured reauthentication policy.
- response uses `Cache-Control: no-store`; reveal creates a self-audit event.
- expired ciphertext is reported as unavailable without changing canonical metadata.

### MAIL-001 — Mailpit development delivery

- Compose starts Mailpit with application SMTP connectivity and developer web/API access.
- integration test sends a magic-link message and verifies recipient, subject and one expected link through the Mailpit API.
- test isolation clears or uniquely tags captured messages.

### MAIL-002 — Provider-neutral outbound boundary

- ticket/customer modules depend on `OutboundMailPort`, not Mailpit classes or endpoints.
- network delivery occurs after durable intent commit and is retry/idempotency safe.
- production provider can be replaced without changing Ticket or Customer aggregate code.

### SCHED-001 — Administrator-editable weekly schedule

- Admin can set IANA timezone, enabled/disabled Monday–Sunday, zero or multiple non-overlapping intervals per day, holidays and exceptional open/closed intervals.
- invalid/overlapping intervals are rejected with field-level problems.
- preview returns deterministic business minutes and next-open/next-close boundaries.

### SCHED-002 — Schedule version history

- editing a schedule creates a new immutable version and an admin-security audit event.
- existing SLA target instances retain their applied schedule version.
- historical calculations do not silently change after an administrator edit.

### SLA-009 — PENDING pause launch policy

- initial First Reply policy pauses while the ticket is PENDING and resumes on the first later active status.
- the pause-status set is policy data editable by Admin and versioned.
- INTERNAL notes neither stop the clock nor count as the first public reply.

### DOC-001 — 탐색 가능한 API 계약 문서

- Scalar는 커밋된 Core, Customer Identity, Platform OpenAPI 계약을 `/docs/api`에서 렌더링한다.
- Compose backend image에도 세 커밋 계약이 포함되며 container smoke에서 Scalar UI와 각 계약 URL의 HTTP 200을 확인한다.
- 모든 작업의 목적·설명은 한국어로 직접 작성한다. 구현 대상으로 표시된 요청 schema는 `x-deskseed-documentation-review: MANUAL`, 도메인별 목적 설명, 필요한 필드를 포함한 합성 예시를 가진다.
- 의미가 확인되지 않은 component/property에는 자동 생성 문구나 `예시 값` placeholder를 넣지 않는다. 문서 검증은 이러한 boilerplate를 실패시키되, 미확인 필드의 설명을 만들어 채우지 않는다.
- `scripts/test_api_documentation_quality.py`는 이름·타입 기반 문구, placeholder 예시, 수동 검토 표식 누락, inline request schema 우회를 회귀 검증한다.
- 예시는 실제 password, token, Authorization 값, session cookie 또는 고객 데이터를 포함하지 않는다.
- springdoc runtime 문서의 구현 경로·HTTP method 집합은 커밋 계약의 구현 대상으로 표시된 작업과 일치한다.
- production profile은 기본적으로 문서를 비활성화하며 명시적 활성화 시 ADMIN 읽기만 허용하고 Try it/client 기능을 숨긴다.
