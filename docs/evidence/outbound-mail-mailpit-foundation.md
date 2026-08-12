# Outbound Mail and Mailpit Foundation Evidence

- Date: 2026-08-12 (Asia/Seoul)
- Branch: `feat/14-outbound-mail-mailpit`
- Base revision: `bc4cabfec905`
- Requirements: `REQ-NOTIF-001`, `REQ-CHAN-003`
- Decisions: `D-002`, `D-005`, `D-010`, `D-038`, `D-039`, `D-040`, `D-046`
- ADR: `0034-mailpit-development-outbound-mail-adapter.md`
- Dataset: synthetic only

## Transaction and delivery boundary

The request or ticket command transaction persists the business rows, canonical ticket audit and a stable `outbound_mail_intents` row together. `OutboundMailPort.enqueue` requires an existing transaction. A worker later claims one due intent and commits an `IN_PROGRESS` attempt and lease before calling SMTP. Success, retry or terminal failure is finalized in another transaction. SMTP failure therefore does not roll back a committed request or PUBLIC reply.

`INTERNAL` comments do not call the outbound port. Delivery event rows are append-only and contain actor/source/request/correlation context plus bounded reason or failure codes. Recipient, subject, message body, magic link, provider exception and SMTP response body are excluded from operational logs.

## Retry schedule

| Cycle attempt | Delay from previous completion | Failure result |
|---:|---:|---|
| 1 | immediate | `RETRY_WAIT` when retryable |
| 2 | 1 minute | `RETRY_WAIT` when retryable |
| 3 | 5 minutes | `RETRY_WAIT` when retryable |
| 4 | 30 minutes | `RETRY_WAIT` when retryable |
| 5 | 2 hours | terminal `FAILED` after failure |

Manual retry is an explicit `STAFF`/`SYSTEM` operation with reason and command context. It reuses the same intent, stable idempotency key and `Message-ID`, records a new retry cycle, and adds one configured attempt budget. It does not recreate the ticket or comment.

## Automated verification

| Gate | Result | Evidence |
|---|---|---|
| Backend full suite | PASS | `make backend-test`; 155 tests, 0 failed, `BUILD SUCCESSFUL in 55s` |
| Outbound policy | PASS | 3 tests: recipient/header injection, template/version, bounded backoff |
| Outbound PostgreSQL integration | PASS | 7 tests: rollback, failure boundary, retry, manual retry, idempotency conflict, concurrent claim, log secrecy, append-only event |
| Mailpit API E2E | PASS | 1 Testcontainers test against `axllent/mailpit:v1.27.4`; 0 failed |
| Portal integration | PASS | 28 tests including outbox rollback and request-received intent |
| Agent command integration | PASS | 13 tests including PUBLIC delivery intent and INTERNAL non-delivery |
| Frontend repository gate | PASS | format, lint, typecheck, 153 tests, production build |
| Seed/document validator | PASS | 20 YAML files, 136 Kotlin files, 38 ADRs; documentation validator `PASS` |
| Compose ownership | PASS | preexisting and replacement container/network/volume/image sentinels preserved |
| Compose smoke | PASS | four services built and started; backend `:18080/actuator/health` and Mailpit `:18025/readyz` returned HTTP 200; owned resources cleaned |

## Mailpit API verification log

The Mailpit E2E deletes the mailbox, commits one `CUSTOMER_MAGIC_LINK` intent, runs the post-commit worker, then checks:

- `GET /api/v1/messages?limit=50`: exactly one message;
- recipient: the exact generated `mailpit-<uuid>@example.com` address;
- subject: contains `로그인`;
- `GET /api/v1/message/{id}` text body: contains the exact generated HTTPS magic link;
- a second worker run processes zero rows;
- Mailpit still contains one message and PostgreSQL contains one delivery attempt.

SMTP does not provide an atomic exactly-once boundary between remote acceptance and local success commit. Stable intent/key/`Message-ID`, lease claiming and attempt history prevent application replay and concurrent-worker duplicates in the verified paths, but an accept-then-ack-loss ambiguity remains for a future production provider to solve with provider idempotency or reconciliation.

## Performance and compatibility

The due queue has partial indexes for due intents and expired leases, and workers use `FOR UPDATE SKIP LOCKED`; a two-worker integration test proves one claim and one delivery for one intent. A backlog gauge exposes queued/retry/sending count. Dedicated throughput or soak testing was not run in this slice.

Flyway `V18` is additive and forward-only. Rollback is an application rollback that leaves the new tables intact; no down migration is provided. There is no OpenAPI change. Production provider, inbound email, bounce webhook, attachment and rich-text delivery remain unimplemented.
