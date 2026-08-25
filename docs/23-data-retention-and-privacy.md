# Data Retention and Privacy Policy Baseline

Status: product proposal, not legal advice

## 1. Goal

보안 감사에 충분한 기록을 남기되, “감사니까 모든 내용을 무기한 평문 저장”하는 제품이 되지 않도록 한다. 데이터 종류별 목적, 민감도, 권한, 보존기간, 삭제·암호화 정책을 분리한다.

## 2. Data classes

| Class | Examples | Sensitivity |
|---|---|---|
| Support content | public/internal comment, subject, attachment | high, customer and business data |
| Ticket change metadata | status/group/assignee before-after | medium-high |
| Access metadata | actor, ticket/customer, time, IP, client | high behavioral data |
| Search query | raw text, filters | potentially very high |
| Security events | login, denial, credential lifecycle | high |
| Customer identity and profile | verified email, display name, company name, credential state | high PII; password hash is restricted credential data |
| Customer consent policy | immutable legal document version, plain text, checksum, lifecycle | high integrity and legal evidence |
| Customer consent acceptance | policy/version, account/ticket linkage, context, server time | high behavioral and legal evidence |
| Integration secrets | API/webhook secret | critical |
| Delivery metadata | endpoint, status, latency, failure | medium-high |
| Export artifact | selected ticket/audit data | very high |
| Operational logs | errors, metrics, traces | variable; should be minimized |

## 3. Proposed launch defaults

These are concrete defaults for development and first controlled deployment. Operators must review them.

| Category | Default proposal | Storage behavior |
|---|---:|---|
| Ticket and comments | operator support-record policy | primary records; not audit substitute |
| Ticket change audit | ticket retention + 5 years, or indefinite when configured | append-only |
| Admin/security audit | 365 days | append-only |
| Customer registration consent acceptance | account lifetime + 365 days after account deletion | append-only; operator/legal review required |
| Customer request consent acceptance | ticket/support-record retention | append-only; follows linked support record |
| Referenced consent policy version | at least as long as any acceptance | immutable; document body is not duplicated into acceptance |
| Access audit metadata | 180 days | append-only, partition-ready |
| Raw search query ciphertext | 30 days | encrypted, key versioned |
| Redacted query/fingerprint | 180 days | access event metadata |
| Audit export artifact | 7 days | encrypted object, short-lived URL |
| Audit export metadata | 365 days | security audit |
| Webhook attempts | 90 days | delivery troubleshooting |
| Webhook business event metadata | 365 days | event/delivery correlation |
| Idempotency record | 7 days minimum | longer than retry contract |
| Application logs | 14–30 days | no content/secrets |
| Metrics/traces | deployment-specific | sampled and minimized |

## 4. Search query protection

### 4.1 Representations

```text
queryRedacted      for routine audit UI
queryFingerprint   keyed HMAC for correlation
queryCiphertext    required protected raw value
```

### 4.2 Encryption

Raw query preservation is required by product policy, but only as ciphertext. There is no plaintext database column.

- authenticated encryption
- key outside database
- key version stored with event
- associated data includes event ID and purpose
- decryption only in a narrow service path
- plaintext lifetime in memory minimized
- plaintext never written to logs, exceptions, cache, or analytics projection

### 4.3 Reveal controls

- `audit:search-query:reveal`
- reason required
- recent authentication/MFA when configured
- response `Cache-Control: no-store`
- reveal event self-audited
- bulk reveal disabled initially

## 5. Comment and attachment content

TicketAudit does not duplicate full comment body by default. It stores comment ID, visibility, actor, length/hash. Audit Explorer fetches immutable content through a separate authorized path.

Attachments require later controls:

- object storage encryption
- content type/size validation
- malware scan
- download access event
- short-lived signed URL
- no attachment body in application log or audit event

## 6. Secrets

Never store or expose in audit/logs:

- password
- API key secret
- OAuth access/refresh token
- session cookie
- Authorization header
- webhook signing secret
- encryption key
- provider credential

Credential audit records contain only:

```text
credential ID/public key ID
client/integration name
actor
created/rotated/revoked time
expiry
scope/resource constraint summary
last-used metadata
```

Customer password, hash, raw registration/verification/reset/magic token, continuation secret,
session cookie, and token-bearing mail URL are excluded from ordinary logs, audit metadata,
webhooks, exports, analytics, and API examples. Authentication security events use bounded outcome
codes and content-free destination/network fingerprints. Company name is profile PII and is not
copied into routine authentication audit or customer ticket-list projections.

## 6.1 Customer consent evidence

- a policy version owns the reviewed canonical document, deterministic plain text, and checksum;
- an acceptance stores only policy/version/context, customer/account/ticket linkage, server time,
  source, request ID, and correlation ID;
- policy body and customer-entered form values are absent from ordinary application logs,
  webhooks, TicketAudit metadata, and unprotected Admin/Security audit;
- published policy versions and acceptance rows are append-only to the runtime application role;
- registration acceptance follows the account record plus 365 days after account deletion by
  default; request-submission acceptance follows the ticket/support-record retention;
- every referenced policy version remains retained at least as long as one acceptance references it;
- production legal text, jurisdiction-specific retention, withdrawal, and data-subject handling are
  operator/legal-owner decisions. Repository fixtures must be synthetic and must not be presented as
  production legal policy.

Platform idempotency rows store a SHA-256 key representation and canonical request hash, never the raw `Idempotency-Key` or Authorization
value. Exact replay requires a bounded response copy, which can contain ticket subject or an INTERNAL comment response; it receives the
7-day default expiry and is not exposed through a retrieval/list endpoint. An expiry-indexed cleanup job deletes at most 500 rows per run by
default. Final receipts are eligible at expiry; `IN_PROGRESS` rows are eligible only after a separate one-hour abandonment grace. Deleted
count, oldest expired backlog age, and cleanup failures are metrics without request or response content. Canonical ticket/comment retention
remains separately governed.

## 7. IP addresses and user agents

These help security investigation but are personal/behavioral data.

- store only where purpose is defined
- normalize and validate
- avoid unbounded user-agent text
- provide retention setting
- exports may mask IP for lower-privilege viewers
- proxy trust configuration must define which forwarded IP header is accepted

Public request abuse control is an operational exception to retaining raw IP: V28 stores only purpose-bound HMAC fingerprints for
destination/client/global fixed windows plus count and expiry. It has no raw email, IP, forwarded header, request body, token, ticket,
or audit-reference column. A bounded `FOR UPDATE SKIP LOCKED` cleanup deletes expired buckets; this maintenance state is not exposed
through customer, staff, audit, export, or Platform API projections.

## 8. Deletion and retention execution

Retention job:

1. loads an immutable policy version
2. computes eligible partition/range
3. excludes legal hold if implemented
4. records planned count/range
5. deletes or drops eligible data using dedicated privilege
6. verifies result
7. emits `RETENTION_JOB_EXECUTED`

The normal application role cannot call arbitrary audit delete APIs.

The first access/search slice realizes this policy for `search_audit_query_ciphertexts`: each row receives an immutable `expires_at` when it is written (30 days by default), and a bounded `FOR UPDATE SKIP LOCKED` job deletes only expired ciphertext rows. Canonical `AccessAuditEvent` and redacted/fingerprint metadata remain intact. Each batch appends `RETENTION_JOB_EXECUTED` to the existing admin/security ledger in the same transaction; if that audit insert fails, the deletion rolls back. No public or staff CRUD endpoint exposes ciphertext deletion.

## 9. Backup and replicas

Documentation must state:

- backup schedule and encryption
- backup retention
- replica/archive copies
- restore test cadence
- how primary deletion propagates or expires from backup
- limits on immediate erasure claims

Deleting a primary row does not mean all backups instantly forget it.

## 10. Tamper evidence and external archive

Local DB controls provide append-only behavior against the application role. Stronger assurance requires:

- daily canonical digest/checkpoint
- signature key outside DB
- independent object storage/SIEM copy
- verification and alerting
- documented restore/reconciliation

External archive payload should be minimized; do not export raw search query/comment content by default.

## 11. Privacy and access review checklist

Before adding a field to audit/event/export:

1. What investigation or product purpose requires it?
2. Can a reference, hash, category, or redacted value satisfy the purpose?
3. Who may read it?
4. How long is it needed?
5. Is it copied to logs, backups, analytics, webhook, or SDK?
6. How is it deleted or expired?
7. What happens if an operator asks for export?
8. Does revealing it create another audit event?

## 11.1 Required startup and failure behavior

- `audit.access.enabled=true` requires both a configured search-query encryption key and a separate 32-byte session-fingerprint key.
- key absence, invalid size, or unknown active key version fails startup/readiness.
- session-fingerprint key rotation is independent from ciphertext key rotation; the stored fingerprint is fixed-size and contains neither the session ID nor encryption key version.
- encryption failure fails the protected search request; it does not return results without the canonical audit.
- decryption failure returns no plaintext and creates a security event.

## 12. Production decisions still required

- applicable jurisdiction and internal policy
- exact support/audit retention
- encryption key/KMS implementation
- legal hold
- data subject access/deletion process
- cross-border storage and webhook destinations
- auditor approval model
- incident export handling
