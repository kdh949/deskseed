# BE-P0-MAIL-OPERATIONS task brief

## Goal

운영자가 production에서도 명시적으로 활성화한 SMTP delivery를 안전하게 실행·관찰하고, 실패한 동일 메일 intent를 감사 가능한 terminal retry로 재큐잉한다.

## Decision and source references

- Decision IDs: `D-010`, `D-018`, `D-046`
- Accepted ADRs: `0010`, `0018`, `0027`, `0034`
- Requirements: `REQ-NOTIF-001`, `REQ-CHAN-003`
- Contract operations: `getOutboundMailSummary`, `listOutboundMailIntents`, `getOutboundMailIntent`, `retryOutboundMailIntent`
- Verification gates: `CHN-006`, `CHN-007`, `CHN-008`, `CHN-009`, `OPS-004`

## Actor and source

- Actor/source: `STAFF` with `ADMIN` authority / `ADMIN_UI`; worker uses `SYSTEM` / `SYSTEM_JOB`.
- Security: admin surface remains session+CSRF protected. Retry reason is mandatory and bounded.

## In scope

- Remove production-profile exclusion where explicit property conditions control worker, scheduler, transport, and configuration.
- Validate enabled production SMTP delivery (transport, host/port/auth, sender, HTTPS public URL, active content key, TLS policy) while retaining disabled-by-default production startup and Mailpit development behavior.
- Add safe health/diagnostics and frozen admin summary/cursor/detail/retry endpoints.
- Project only masked recipient, status/count/timestamps/template and safe error code; omit raw recipient, body, ciphertext/nonce, credentials, token and provider response.
- Make `FAILED`-only same-intent retry race-safe and append admin/security audit atomically.
- Add additive query/retry indexes and PostgreSQL/mail delivery/retry race tests.

## Out of scope

- Inbound email, bounce webhook, provider-specific idempotency reconciliation, rich HTML, or exposing mail content through admin APIs.

## Invariants and failure semantics

- Provider I/O occurs after durable claim commit and never inside the ticket transaction.
- A retry never creates another ticket command, comment, mail intent, or token grant.
- Missing production delivery requirements prevent enabled worker startup; disabled delivery remains visibly disabled rather than silently falling back.

## Data and privacy

- Operations projections are metadata-only. Safe failures are code-only; detailed provider messages are not retained.
- Token-bearing message bodies use the protected-content encryption path.

## Acceptance scenarios

- Production profile with disabled delivery starts without SMTP configuration; enabled delivery with a missing required value fails startup.
- Mailpit delivery succeeds through the same worker path.
- An ADMIN can see masked queue state and retry one FAILED intent once; concurrent retries produce one new cycle and one audit.

## Validation

- Production configuration context tests, SMTP/Mailpit delivery test, terminal retry/race tests, authorization/CSRF/projection tests, and targeted health checks.

## Compatibility and migration

- New admin operations are additive and no mail content becomes externally readable.
- Forward-only index migration; failure queue can be reprocessed after application rollback.

## Human explanation

The durable intent is the retry identity. Keeping delivery state observable but content redacted allows operations without widening access to customer secrets.

## Implementation record — 2026-08-15

- `V29__outbound_mail_operations_indexes.sql` adds only `(queued_at desc, id desc)` and `(status, queued_at desc, id desc)` indexes for ADMIN keyset reads. The existing primary key remains the retry lock.
- `OutboundMailOperations` now exposes frozen summary/list/detail/retry root operations. The JDBC reader selects only safe operational columns and masks the local mailbox part; its signed cursor is bound to the selected status.
- `AdminOutboundMailController` is ADMIN session/CSRF protected. Its response model excludes raw mailbox, sender, subject, rendered body, protected cipher/nonce/key version, request/correlation IDs, credentials, provider message ID and provider response.
- `FAILED` retry holds the existing intent pessimistically, resets its existing retry cycle, persists the immutable delivery event, and appends `OUTBOUND_MAIL_MANUAL_RETRY_REQUESTED` Admin/Security audit in the same transaction. Retry reason is retained only in the bounded delivery event and never reflected by the ADMIN API or security-audit metadata.
- Production SMTP is disabled by default. Explicitly enabled production validates host/port/username/password/auth, required TLS, sender mailbox, HTTPS public base URL and active protected-content key before worker/SMTP creation; Mailpit uses the unchanged opt-in worker path.
- PASS: focused `MailDeliveryConfigurationValidatorTest`, `OutboundMailPolicyTest`, `OutboundMailDeliveryIntegrationTest`, and `AdminOutboundMailIntegrationTest`; `ApiDocumentationIntegrationTest`, `ArchitectureTest`, and `MailpitApiE2ETest`; `make docs-check`; `git diff --check`.
- Non-goals remain inbound email, bounce webhook, provider-side acknowledgement reconciliation, HTML mail and any endpoint that reveals mail content.
