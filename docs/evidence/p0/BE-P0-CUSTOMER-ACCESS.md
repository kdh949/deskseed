# BE-P0-CUSTOMER-ACCESS task brief

## Goal

익명 고객이 티켓 범위 접근 토큰으로 자신의 문의에 PUBLIC 후속 댓글을 안전하게 남기고, 접수·상담사 공개 답변 메일에서 새 토큰이 포함된 작업 링크를 받는다.

## Decision and source references

- Decision IDs: `D-003`, `D-006`, `D-010`, `D-018`, `D-046`
- Accepted ADRs: `0003`, `0006`, `0010`, `0027`, `0029`, `0034`
- Requirements: `REQ-TKT-003`, `REQ-TKT-005`, `REQ-TKT-008`, `REQ-NOTIF-001`, `REQ-CHAN-003`
- Contract operation: `addCustomerRequestComment`
- Verification gates: `TKT-002`, `CHN-005`, `CHN-006`, `CHN-007`, `CHN-008`, `AUTH-004`

## Actor and source

- Actor/source: `CUSTOMER` / `CUSTOMER_PORTAL`; agent reply remains `STAFF` / `AGENT_WORKSPACE`.
- Credential: `X-Request-Access-Token` is a ticket-scoped opaque capability; a token for another ticket, expired token, and revoked token share the not-found result.
- Context: request/correlation values come from `CommandContexts`; `clientCommandId` is the stable retry identity.

## In scope

- Freeze and implement `POST /api/v1/requests/{ticketNumber}/comments` with PUBLIC-only body and `clientCommandId`.
- Reuse the canonical comment for an exact retry; reject the same identity with a different body or ticket.
- Keep `PENDING -> OPEN`; reject `SOLVED` and `CLOSED` without automatic reopen.
- Persist comment, state change, TicketAudit/events, request-grant issuance, and durable mail intent in one transaction.
- Render request-received and public-agent-reply mail links as `{publicBaseUrl}/requests/{ticketNumber}#token={rawAccessToken}` and encrypt every rendered token-bearing body.
- Replace direct Ticketing-to-mail public reply dispatch with a synchronous Ticketing fact handled by Portal integration, avoiding a Ticketing-to-Portal dependency.
- PostgreSQL integration and Mailpit-oriented regressions for access, replay, failure rollback, and token secrecy.

## Out of scope

- Customer account/claim policy redesign, inbound email, production provider selection, rate limiting, and mail-operations administration.
- A new event broker or transactional outbox beyond the existing durable mail intent.

## Invariants and failure semantics

- A capability is checked and locked in the same transaction as the follow-up; mismatched ticket number is not distinguishable from an invalid proof.
- Only `CUSTOMER_REQUEST` and `PUBLIC` comments are reachable; public projection stays INTERNAL-free.
- Required TicketAudit or outbox persistence failure rolls back the comment, token issuance, and intent.
- Exact replay returns the existing comment/audit without a second intent. Reusing the identity for a different canonical payload returns `409`.
- SMTP remains post-commit; sending failure cannot roll back a committed comment.

## Data and privacy

- Raw request tokens exist only in the immediate API response or rendered mail body. Database token rows retain only a digest.
- Token-bearing rendered content is `PROTECTED`, encrypted with the configured versioned content key; ordinary body columns, audit metadata, logs, errors, and provider metadata omit the raw token.
- Mail recipient/body remain outside customer/public HTTP responses.

## Acceptance scenarios

- Given a valid token for a PENDING request, when the customer submits a new command, then one PUBLIC comment, OPEN transition, audit, fresh grant, and one intent commit.
- Given an exact retry, when the same command is sent, then `201` returns the canonical comment and no extra audit or intent is created.
- Given a changed body/ticket or an invalid, expired, revoked, or other-ticket token, then no mutation occurs and the public result is conflict or not-found as appropriate.
- Given an agent PUBLIC reply, when its ticket command commits, then the Portal listener issues a fresh grant and queues one protected reply mail; INTERNAL notes do neither.

## Validation

- Focused PostgreSQL integration: anonymous follow-up/replay/conflict/token mismatch/expiry/revocation/PUBLIC-only/audit failure/outbox failure.
- Mail content checks: fragment token URL, ciphertext-at-rest, and no raw token in normal body/log capture.
- Full backend suite is deferred to the final slice; commands/results are recorded in the progress log.

## Compatibility and migration

- Additive public request body field and frozen operation; existing request reads continue to accept the same header.
- No schema change is expected for the command itself; protected content columns already exist. Any necessary grant index is additive in the next Flyway migration.
- Rollback is an application forward-fix; no historic token or audit rewrite.

## Human explanation

The Portal owns capability issuance and mail composition while Ticketing owns the comment/audit mutation. A synchronous in-process fact keeps all required database writes atomic today without making Ticketing depend on Portal or introducing Kafka before measured need.
