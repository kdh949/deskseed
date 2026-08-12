# Email and Messaging Channel Architecture

## 1. 목표

웹 문의 모델을 깨지 않고 이메일을 Ticket/Comment channel adapter로 추가한다. 이메일 수신/발신 실패가 ticket transaction과 뒤엉키지 않게 한다.

## 1.1 Accepted development delivery boundary

- Docker Compose includes Mailpit.
- application SMTP target in development: `mailpit:1025`; developer UI: `http://localhost:8025`.
- use a provider-neutral `OutboundMailPort`; no Mailpit type leaks into ticket/customer modules.
- first outbound templates are customer magic link, request received, and public agent reply.
- Mailpit REST API is permitted in integration tests.
- production provider and inbound ingestion remain separate adapter decisions.

## 2. Channel abstraction

Ticket and Comment have a channel marker, but domain logic does not depend on provider SDK.

```text
InboundChannelAdapter
  → normalized inbound message command
Ticketing application
  → comment/ticket/audit + outbound intent
OutboundChannelAdapter
  → provider delivery
```

## 3. Email channel configuration

```text
EmailChannel
  id
  address
  display_name
  inbound_provider/config reference
  outbound_provider/config reference
  default_group
  status
  threading_domain/secret settings
```

Secrets are encrypted/managed outside ordinary config response.

## 4. Inbound normalization

Inbound provider payload becomes:

```text
provider_message_id
from/to/cc
subject
text/plain
safe html
attachments
received_at
in_reply_to/references
spam/authentication metadata
raw message object reference
```

Validate provider signature or trusted mailbox fetch boundary.

## 5. Threading

Thread resolution precedence:

1. signed Deskseed reply token/message reference
2. known provider message ID relationship
3. configured safe fallback subject/reference strategy
4. new ticket

Never rely only on subject text.

A forged ticket number in subject must not attach to a ticket without cryptographic or provider-verified context.

## 6. Inbound idempotency

Unique provider/channel message ID prevents duplicate comments. Duplicate webhook/fetch replay returns existing result.

## 7. Customer matching

Sender email can identify a candidate customer but is not universally trusted as account authentication. Email comment belongs to the channel thread/ticket; account linking follows identity policy.

## 8. Email body handling

- prefer text/plain canonical representation where possible
- sanitized HTML subset
- strip tracking pixels and dangerous remote content by policy
- quote trimming is presentation assistance, not destructive canonical deletion
- retain raw MIME only in protected object storage if operationally needed
- cap headers/body/attachment sizes

## 9. Outbound reply

Agent public comment command creates:

```text
TicketComment PUBLIC
TicketAudit
OutboundMessageIntent
```

Network send occurs after commit.

Outbound record:

```text
id
comment_id
channel_id
recipient snapshot
template/version
provider_message_id
status QUEUED|SENDING|SENT|FAILED|BOUNCED
aattempts
```

Internal notes never create customer email.

## 10. Failure and retry

- provider timeout/5xx retry with backoff
- duplicate-safe provider idempotency where supported
- permanent recipient failure visible to agent
- ticket comment remains committed but delivery state is explicit
- manual resend is audited and must not create duplicate comment

## 11. Recipient safety

- validate To/CC based on ticket participants and permission
- prevent header injection
- do not expose internal recipients
- BCC policy explicit
- notification templates never include internal comments/fields

## 12. Email UI

Conversation card shows channel and delivery status. Composer public mode can preview recipients. Delivery failure appears near the comment and in operations queue.

## 13. Chat/messaging later

Chat and social messaging require additional concepts:

```text
conversation session
presence/typing
message ordering
reconnect
real-time transport
channel-specific delivery status
agent availability/routing
```

Do not fake these with email/web comment polling. Add after email and core ticketing semantics are stable.

## 14. Omnichannel future

A single ticket may contain multiple channel comments only after participant identity, notification policy, and audit semantics are explicit. The agent can choose a supported reply channel, but cannot silently send to unverified contact.

## 15. Gates

- `CHN-001`: authenticated inbound provider boundary
- `CHN-002`: duplicate inbound creates one comment
- `CHN-003`: signed/thread-safe ticket association
- `CHN-004`: HTML/remote content sanitization
- `CHN-005`: internal note never sends externally
- `CHN-006`: outbound intent commits without network dependency
- `CHN-007`: retry/resend does not duplicate comment/message
- `CHN-008`: recipient/header injection controls
- `CHN-009`: delivery state observable and audited
- `CHN-010`: real-time ordering/reconnect contract before chat
- `CHN-011`: channel permission and identity mapping
- `CHN-012`: load/backpressure evidence before WebSocket scale changes

## 16. Implemented outbound foundation boundary

The first outbound-only slice implements `REQ-NOTIF-001` and `REQ-CHAN-003`:

- `OutboundMailPort` queues provider-neutral versioned intents in the caller's business transaction.
- PostgreSQL stores intent, attempt and append-only delivery-event state; workers claim due rows with a lease and `SKIP LOCKED`.
- development Compose enables the SMTP adapter against `mailpit:1025` and exposes Mailpit UI/API at `localhost:8025`.
- the production profile does not activate this development adapter.
- request received and PUBLIC agent reply are wired; INTERNAL comments have no mail intent path.
- customer magic-link rendering is available for the customer-auth slice, but token issuance/consume is not implemented here.
- plain text only; production provider, inbound, bounce, attachments and rich text remain unimplemented.

Default retry table:

| Attempt | Delay from failure | Exhausted result |
|---:|---:|---|
| 1 | immediate | retry if transient |
| 2 | 1 minute | retry if transient |
| 3 | 5 minutes | retry if transient |
| 4 | 30 minutes | retry if transient |
| 5 | 2 hours | terminal `FAILED` |

SMTP has no atomic exactly-once handshake with the database. A stable intent ID and `Message-ID` prevent application/concurrent retry duplication and support reconciliation, but an SMTP server accepting a message immediately before an acknowledgement loss remains ambiguous. Production provider selection must freeze an idempotency or lookup/reconciliation contract.
