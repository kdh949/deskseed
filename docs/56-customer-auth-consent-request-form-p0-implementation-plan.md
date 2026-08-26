# Customer Password Authentication, Consent, and Request Form P0 Implementation Plan

Status: **implementation plan — contract approval required before code**
Date: 2026-08-25
Scope: customer identity, administrator-managed consent policies, and customer request-form submission

## 1. Outcome

This plan delivers three connected customer outcomes without introducing a second API version:

1. A customer can register with email, password, display name, company name, and required policy consent; verify the email; then use email/password as the primary sign-in method.
2. A customer identity that has no password, usually originating from an anonymous request, can still use the existing magic-link flow. After that sign-in, the server explicitly reports that registration completion is required and lets the customer set a password and finish the profile.
3. Anonymous and signed-in customers can submit the fields selected by an administrator's published customer ticket form, including customer-visible custom fields. The backend remains authoritative for visibility, editability, requiredness, type validation, and the selected form version.

The implementation reuses the existing customer session, CSRF, one-time-token, outbound-mail, customer access-mode, ticket form, typed EAV, ticket command, attachment, and audit foundations.

## 2. Owner decisions incorporated

- Registration collects **email, password, display name, company name, Terms of Service consent, and Privacy Policy consent**.
- Email/password is the primary customer authentication method.
- Magic-link login is available only for a customer identity without a password, including identities originating from anonymous requests.
- A magic-link session must expose a registration-completion requirement so the UI can guide the customer to set a password and finish registration.
- Customer access mode is `REGISTRATION_OPTIONAL`: anonymous and registered request submission are both allowed.
- Consent policies use server-owned keys and immutable versions with append-only acceptance history.
- An administrator can edit consent policy content through a draft/publish lifecycle.
- An administrator can choose which built-in or custom fields appear on customer request forms.
- This product has not been deployed. Existing v1 request and identity contracts may be changed directly; no v2 endpoint, legacy adapter, or deprecation window is required.

Forward-only Flyway migrations are still required. “Not deployed” permits contract cleanup, but it does not permit editing already committed migration history.

## 3. In scope and non-goals

### In scope

- password registration and email verification;
- password login, logout, and server-side customer session reuse;
- password-reset request and completion;
- passwordless magic-link login for identities without a password;
- authenticated registration completion after passwordless login;
- customer company-name profile field;
- administrator-managed Terms, Privacy, and request-submission consent policies;
- immutable consent-policy versions and append-only acceptance records;
- customer-facing current consent-policy projection;
- dynamic customer ticket-form projection using candidate field values;
- form/version/value persistence during request creation;
- removal of the currently ignored `privacyConsent` field;
- direct cleanup of the current request JSON and multipart contracts;
- OpenAPI, requirement, ADR, audit catalog, verification gate, migration, and test updates.

### Non-goals

- social login, OIDC, SSO, or MFA;
- organization membership or multi-user company accounts;
- automatic claiming of historical anonymous tickets by email match;
- administrator viewing or recovering customer passwords;
- password history or breached-password provider integration;
- marketing consent or arbitrary consent withdrawal workflow;
- customer profile image, notification preferences, inbound email, or chat;
- mapping customer-entered urgency directly to internal `TicketPriority`;
- fetching an order or company record from an external system during submission;
- a production legal-policy text bundled by the repository.

## 4. Decisions and document changes required before implementation

### 4.1 Replace the current customer-auth decision

`D-040` and ADR 0029 currently say customer authentication begins with email magic links. The new requirement activates their password-authentication revisit trigger.

The new accepted ADR is:

```text
ADR 0042 — Password-primary customer authentication with passwordless magic-link onboarding
```

The accepted decision-register entries are:

```text
D-057: customer authentication is password-primary; magic-link login is passwordless-only
D-058: consent policies are administrator-managed immutable versions with append-only acceptance
D-059: customer request submission binds a server-authorized form/version and typed values
D-060: customer auth throttling remains storage-neutral at the application boundary and uses one Redis adapter
```

Task 1 registers these final ASCII decision IDs in the canonical `docs/25-implementation-decision-register.md`.

ADR 0042 supersedes the authentication-method portion of ADR 0029 but preserves its single-use token, enumeration safety, session security, explicit claim, and outbound-mail boundaries.
ADR 0043 supersedes only ADR 0042's customer-authentication PostgreSQL-throttling sentence. Its 2026-08-26 owner amendment selects Redis without a PostgreSQL comparison benchmark while preserving the externally visible rate-limit, enumeration-safety, and fail-closed behavior.

### 4.2 Requirement IDs

The five narrow requirements are:

```text
REQ-AUTH-003: password registration, email verification, password login, and reset
REQ-AUTH-004: passwordless magic-link sign-in and explicit registration completion
REQ-CONSENT-001: administrator-managed immutable consent-policy versions
REQ-CONSENT-002: server-validated append-only customer consent acceptance
REQ-CFG-014: customer form projection, submission validation, and form snapshot binding
```

Task 1 registers these IDs in `docs/26-requirement-traceability.md` as `BLUEPRINT_READY`; the matching contract-freeze PR promotes each row independently.

`REQ-CFG-002` remains broad. Completing the customer submission slice does not prove every custom-field query, search, and analytics capability.

### 4.3 Verification gate additions

Extend `docs/21-minimum-verification-gates.md` with these seven gates:

```text
AUTH-005: password registration and email verification
AUTH-006: password login enumeration safety, throttling, and session rotation
AUTH-007: password reset single-use, expiry, and session revocation
AUTH-008: passwordless magic-link eligibility and registration completion
CONSENT-001: immutable policy lifecycle and administrator authorization
CONSENT-002: current-version enforcement and atomic acceptance persistence
CFG-006: customer form candidate projection and submission binding
```

Task 1 also restores the already-referenced `CFG-001` through `CFG-005` definitions from the delivered ticket-configuration evidence so every active traceability reference resolves.

Existing `AUTH-001` through `AUTH-004`, `ARCH-001/002/004`, `TKT-001/002`, `CHG-001/002/003`, `FILE-001/003/004/006`, and `DOC-001` remain applicable.

## 5. Target architecture

### 5.1 Module ownership

```text
customerauth
  password credentials, registration intents, purpose-bound one-time tokens,
  password login/reset, customer sessions, authentication security audits

customer
  customer profile, display name, company name, verified email state

customerconsent (new bounded module)
  policy draft/publish/archive lifecycle, immutable policy versions,
  current policy projection, acceptance validation and persistence

settings
  REGISTRATION_OPTIONAL customer access mode

ticketconfiguration
  field/option/form administration, customer projection,
  conditional evaluation, typed EAV persistence, selected form/version

portal
  customer HTTP translation and request-submission orchestration

ticketing
  ticket, first PUBLIC comment, one TicketAudit, ordered ticket events

outboundmail
  verification, password-reset, and passwordless-login delivery intents
```

Modules import only root APIs or named interfaces. No new `common`, `utils`, or cross-module `internal` import is introduced.

### 5.2 Authentication state

```text
NO_ACCOUNT
  └─ standard registration request
       └─ PENDING_REGISTRATION intent
            └─ verified email token + browser continuation proof
                 └─ ACTIVE_PASSWORD account

ANONYMOUS_REQUESTER or ACTIVE_PASSWORDLESS account
  └─ eligible magic-link request
       └─ single-use magic-link consume
            └─ ACTIVE_PASSWORDLESS session
                 └─ authenticated registration completion
                      └─ ACTIVE_PASSWORD account

ACTIVE_PASSWORD account
  ├─ password login
  └─ password-reset request/completion
```

Email equality never transitions ticket ownership. An anonymous request is linked only through the existing ticket-specific claim proof.

### 5.3 One-time token purposes

Generalize the current token store with an allowlisted purpose:

```text
PASSWORDLESS_LOGIN
EMAIL_VERIFICATION
PASSWORD_RESET
```

All purposes retain digest-only storage, single consume, expiry, request/correlation context, bounded cleanup, and raw-token-free logs/audits. A token issued for one purpose is rejected by every other consumer.

`EMAIL_VERIFICATION` verifies a registration and does not serve as a normal login method. `PASSWORD_RESET` changes a credential and does not authenticate a session. `PASSWORDLESS_LOGIN` is issued only when the target identity has no password.

### 5.4 Password storage

Use a customer-specific `PasswordEncoder`; do not change the existing staff BCrypt bean or rewrite staff password hashes.

Recommended initial customer encoder:

```text
Argon2id
memory: 19 MiB
iterations: 2
parallelism: 1
salt: 16 bytes
hash: 32 bytes
stored format includes an algorithm/version prefix
```

Tune the work factor on the supported deployment hardware and record the verification duration. Spring recommends adaptive one-way password hashing and exchanging the long-term password for a short-lived session; OWASP recommends Argon2id for new password storage. See:

- <https://docs.spring.io/spring-security/reference/7.0/features/authentication/password-storage.html>
- <https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html>

Password input policy:

- 12–128 characters;
- spaces and Unicode are allowed;
- control characters are rejected;
- no forced composition rule;
- plaintext exists only at the HTTP/application boundary and is never logged, audited, returned, cached, or stored.

### 5.5 Authentication throttling storage decision

The product owner considered introducing Redis immediately. The concern was that writing every failed or attempted authentication to PostgreSQL could turn malicious traffic into hot-row, WAL, connection-pool, and lock pressure, while implementing a full PostgreSQL limiter and later replacing it with Redis would duplicate migration, repository, test, and operational work.

The accepted 2026-08-26 owner amendment selects Redis directly:

- keep the HTTP/OpenAPI behavior storage-neutral behind an `AuthenticationAttemptLimiter` application port;
- use one atomic TTL-backed Redis adapter and do not implement a customer-authentication PostgreSQL limiter adapter;
- the owner-declared target is 20 req/s sustained, 100 req/s for a 60-second burst, concurrency 100, and a 2x safety factor;
- the owner-declared SLO is limiter p95 at most 20 ms, p99 at most 50 ms, ordinary-transaction p95 degradation at most 10%, and zero errors/timeouts/database-pool starvation;
- the comparison benchmark is waived, so these are unmeasured targets and AUTH-006 capacity remains `Not run` until a supported-deployment test records evidence;
- never dual-write PostgreSQL and Redis for the same decision;
- keep a coarse ingress/proxy limit in front of the application regardless of the selected store;
- a bounded local deny cache may suppress repeated store calls, but it can only deny and must never grant an allowance;
- shared-store timeout or unavailable state fails closed with the generic authentication-unavailable `503` contract.

The limiter operation completes before adaptive password hashing, mail work, or authentication audit persistence. ADR 0043 owns the Redis acceptance requirements, declared but unmeasured capacity target, and revisit conditions.

## 6. Consent architecture

### 6.1 Policy model

```text
CustomerConsentPolicy
  id
  policyKey                 immutable, e.g. terms-of-service
  context                   REGISTRATION | REQUEST_SUBMISSION
  lifecycle                 DRAFT | PUBLISHED | ARCHIVED
  draftDefinition
  currentVersion
  publishedVersion?
  aggregateVersion

CustomerConsentPolicyVersion
  policyId
  version
  title
  document                  canonical safe block document
  plainText
  checksumSha256
  required
  displayOrder
  effectiveAt                server-owned; equals publishedAt in P0
  publishedByStaffId/display
  publishedAt
```

Published versions are immutable. Editing a published policy updates a draft and publishing creates a new version; it never rewrites what a customer previously accepted.
P0 does not schedule future policy activation. The draft and publish request do not accept `effectiveAt`; a successful publish sets
`effectiveAt = publishedAt` from the same server `Clock` value and atomically replaces the one current pointer. Introducing a separate
scheduled version, transition worker, or future activation time requires a later decision amendment.

Reuse the public, storage-neutral canonical block document codec/validator already used by Knowledge Base. Consent applies an additional allowlist and rejects raw HTML and attachments. This avoids a second rich-text format and preserves deterministic plain text and checksum behavior without importing Knowledge Base internals.
Create/update rejects an HTTP request body larger than 262,144 bytes. Publish validates the canonicalized result at 50,000 plain-text
characters and 200,000 UTF-8 bytes so a schema-valid block collection cannot exceed the immutable version storage contract.

The current-policy projection is intentionally bounded to 20 policies per context. Publish enforces the same limit while serializing
the context inside its transaction; concurrent attempts to move from 20 to 21 current policies can have at most one winner. Pagination
is not introduced for this P0 command prerequisite set.

### 6.2 Acceptance model

```text
CustomerConsentAcceptance
  id
  customerId
  accountId?
  ticketId?
  policyId
  policyVersion
  context
  acceptedAt              server Clock
  source
  requestId
  correlationId
```

The acceptance row stores only the referenced immutable version, context, actor/resource linkage, and server time. It does not duplicate the policy body.

Rules:

- every required active policy for the operation context must be present exactly once;
- the submitted version must be the current published version at final transaction time;
- unknown, archived, optional-for-a-different-context, or duplicate policy references are rejected;
- the client cannot supply canonical acceptance time;
- acceptance rows are append-only;
- policy and acceptance data are absent from ordinary application logs, webhooks, and ticket change metadata;
- request submission stores the acceptance with the ticket transaction;
- registration activation stores registration acceptances with account creation;
- policy-version rows remain retained while referenced by an acceptance.

### 6.3 Administrator permission and audit

Add the explicit authority:

```text
customer-consent:manage
```

The initial ADMIN role receives it. AGENT and SECURITY_AUDITOR are denied unless a later explicit role/grant decision changes this.

Admin/security events:

```text
CUSTOMER_CONSENT_POLICY_CREATED
CUSTOMER_CONSENT_POLICY_DRAFT_UPDATED
CUSTOMER_CONSENT_POLICY_PUBLISHED
CUSTOMER_CONSENT_POLICY_ARCHIVED
CUSTOMER_CONSENT_ACCEPTED
```

Policy mutation and its admin/security audit commit or roll back together. Audit metadata contains policy ID/key/version/context/checksum and never contains the document body.

## 7. API contract plan

### 7.1 Customer identity operations

Update `api/customer-identity-api-v1.yaml` directly. No v2 surface is created.

| Method and path | Operation ID | Contract |
|---|---|---|
| `POST /api/v1/customer/registrations` | `requestCustomerRegistration` | Accept email, password, display name, company name, registration policy versions; return enumeration-safe `202` and a browser-bound continuation cookie |
| `POST /api/v1/customer/registration-verifications` | `verifyCustomerRegistration` | Consume email token plus continuation cookie; atomically create verified profile/account/consents; return `204` |
| `POST /api/v1/customer/auth/password-sessions` | `createCustomerPasswordSession` | Generic email/password authentication; rotate any current customer session and return `CurrentCustomer` |
| `POST /api/v1/customer/auth/magic-link-requests` | existing | Keep generic `202`; send only for an identity without a password |
| `POST /api/v1/customer/auth/magic-link-sessions` | existing | Consume only `PASSWORDLESS_LOGIN`; create/rotate a session whose projection requires registration completion |
| `PUT /api/v1/customer/me/registration` | `completePasswordlessCustomerRegistration` | Session+CSRF; set password, display name, company name, registration consents; rotate session |
| `POST /api/v1/customer/auth/password-reset-requests` | `requestCustomerPasswordReset` | Enumeration-safe `202`; send only for active password accounts |
| `POST /api/v1/customer/auth/password-resets` | `resetCustomerPassword` | Consume single-use reset token, set new password, revoke all customer sessions, return `204` |
| `GET /api/v1/customer/me` | existing | Add `companyName`, `credentialState`, `registrationState`, and `availableAuthenticationMethods` |
| `GET /api/v1/customer/csrf` | existing | Reuse unchanged |
| `DELETE /api/v1/customer/session` | existing | Reuse unchanged |

Registration, magic-link request, and password-reset request use generic response shapes and comparable timing classes. Login uses one generic invalid-credential problem for unknown email, wrong password, disabled account, passwordless account, and incomplete registration. Throttling returns `429` with `Retry-After` without identifying which condition occurred.

To prevent account pre-hijacking, a pending registration is activated only when both the email token and the browser-bound continuation secret match the same intent. If the email link is opened without the continuation cookie, the customer restarts registration; the server does not activate a password selected by another browser.

### 7.2 Consent operations

Add the owned Core fragment `api/core-api-fragments/05-customer-consent.yaml`.

Customer operation:

```text
GET /api/v1/customer/consent-policies?context=REGISTRATION|REQUEST_SUBMISSION
operationId: listCurrentCustomerConsentPolicies
```

The response returns current published `policyId`, `policyKey`, `version`, `title`, safe canonical document, checksum, required flag, display order, and server-owned effective time equal to `publishedAt`. Public responses are cacheable only according to an explicit version/ETag policy; registration and submission responses remain `no-store`.

Administrator operations:

```text
GET  /api/v1/admin/customer-consent-policies
POST /api/v1/admin/customer-consent-policies
GET  /api/v1/admin/customer-consent-policies/{policyId}
PUT  /api/v1/admin/customer-consent-policies/{policyId}
POST /api/v1/admin/customer-consent-policies/{policyId}/publish
POST /api/v1/admin/customer-consent-policies/{policyId}/archive
```

Admin writes require staff session, ADMIN role, `customer-consent:manage`, expected-actor guard, CSRF, and `If-Match`. Stale drafts return `412`; lifecycle/key conflicts return `409`; unavailable resources return existence-safe `404`; required audit failure returns `503` with no mutation.

### 7.3 Customer form projection

Reuse the existing initial projection:

```text
GET /api/v1/customer/ticket-forms?formId=...
```

Add candidate-value projection:

```text
POST /api/v1/customer/ticket-form-projections
operationId: projectCustomerTicketForm
```

Request:

```json
{
  "ticketKind": "CUSTOMER_REQUEST",
  "formId": "00000000-0000-4000-8000-000000000001",
  "formVersion": 3,
  "fieldValues": {
    "request.type": {"optionId": "00000000-0000-4000-8000-000000000101"}
  }
}
```

The public adapter always evaluates `ticketKind=CUSTOMER_REQUEST`; it does not accept an internal ticket-kind input. The server evaluates
allowlisted `field.<machineKey>` facts and returns only the customer-visible projection. The final create command always repeats validation;
a projection response is not an authorization token.

Each published `formVersion` freezes placement/order, condition rules, customer visibility/editability/requiredness, field and option IDs
and machine keys, field type/validation bounds, and the eligible option set/order. Customer label and description are current display copy,
not part of the snapshot; the contract does not promise exact historical UI wording. A field or option semantic change, including type,
validation meaning, or option meaning, creates a new ID. A copy-only label/description change does not create a new form version.

### 7.4 Request creation contract cleanup

The implementation blueprint replaces `CreateAnonymousRequest` with a form-aware `CreateCustomerRequest`. It omits the runtime `FROZEN`
marker until the controller, persistence, error, and springdoc parity land. `privacyConsent` is removed rather than deprecated because the application has not shipped.

Planned JSON shape:

```json
{
  "clientCommandId": "08a85f1c-9939-43f7-a90a-657ac5acb935",
  "requester": {
    "name": "김민아",
    "email": "mina.kim@example.test"
  },
  "subject": "주문 상태 확인 요청",
  "message": "주문 상태를 확인해 주세요.",
  "formId": "00000000-0000-4000-8000-000000000001",
  "formVersion": 3,
  "fieldValues": {
    "request.type": {"optionId": "00000000-0000-4000-8000-000000000101"},
    "customer.urgency": {"optionId": "00000000-0000-4000-8000-000000000201"},
    "order.reference": {"shortTextValue": "ORD-2026-1042"}
  },
  "acceptedPolicies": [
    {
      "policyKey": "inquiry-data-processing",
      "version": 2
    }
  ]
}
```

Rules:

- `clientCommandId` is a required high-entropy UUID generated by the first-party browser and retained across transport retries;
- `requester` is required for an anonymous request and omitted for a signed-in request;
- a signed-in request uses the server session identity and rejects a conflicting requester object;
- `formId` and `formVersion` are either both present or both absent;
- absence means the core subject/message form only when no published default customer form is available;
- when a published default form exists, new UI clients obtain and submit its ID/version;
- the submitted version must still be the current published customer form at final validation;
- unknown or staff-only field keys return a generic field-validation problem without exposing the protected definition;
- hidden/readonly values are dropped as required by ADR 0041;
- active visible required fields must have valid values;
- customer urgency remains a custom field and does not mutate internal priority;
- order reference is a bounded string and triggers no external fetch.

Initial request idempotency is scoped by the authenticated customer ID or anonymous requester destination fingerprint plus
`clientCommandId`. The final command stores only a keyed command-identity digest, a canonical request/attachment-manifest hash,
the committed ticket identity/result metadata, and expiry. The canonical hash covers identity mode, requester/session identity,
subject, message, form/version, normalized typed values, accepted policies, and the ordered server-computed attachment
SHA-256/size/media-type manifest. The raw command ID, requester content, and raw request access token are not stored in the receipt.

- same identity and command ID with the same canonical payload returns the same logical ticket result without duplicating Customer,
  Ticket, comment, form/value, acceptance, audit, or mail intent;
- replay issues a fresh ticket-scoped request access grant while previously issued grants retain their normal expiry/claim lifecycle,
  so no raw capability must be retained for response replay;
- the same identity and command ID with a different canonical payload returns a non-mutating `409`, except that the separately
  committed public abuse budget remains consumed;
- a transaction-scoped lock makes concurrent finalization single-winner;
- the receipt expires after seven days and bounded cleanup removes expired/abandoned rows; a retry after expiry is a new command.

Replace the flat multipart schema with:

```text
request       application/json CreateCustomerRequest
attachments  zero to five files
```

The existing attachment quarantine/upload/link implementation is reused. For an anonymous multipart request the server generates a
`plannedCustomerId` UUID, but does not insert a Customer before file work. Quarantine/upload/scan records only that opaque server-generated
UUID as the CUSTOMER actor; the client cannot supply it and attachment metadata/audit contains no requester name or email. The final
transaction creates `Customer(id = plannedCustomerId)` and links only CLEAN attachments owned by the same UUID. Initial validation occurs
before upload and is repeated in the final ticket transaction. A file failure or policy/form version change leaves no Customer or Ticket;
already uploaded objects remain unlinked and use the existing TTL cleanup policy.

The stable customer problem catalog is `/problems/customer-ticket-form-validation-failed`,
`/problems/customer-ticket-form-unavailable`, `/problems/customer-ticket-form-version-conflict`,
`/problems/customer-request-validation-failed`, `/problems/customer-request-not-allowed`,
`/problems/customer-request-configuration-conflict`, `/problems/customer-request-command-conflict`,
`/problems/customer-request-rate-limited`, and
`/problems/customer-request-configuration-unavailable`. All responses use `Cache-Control: no-store`;
validation and unavailable responses do not disclose whether an unknown input names a staff-only definition.

## 8. Database migration plan

Wave 1's V40–V79 reservation is complete. Begin the next additive range at V80.

### V80 — customer consent policies and acceptances

- create `customer_consent_policies`;
- create immutable `customer_consent_policy_versions` plus update/delete rejection trigger;
- create `customer_consent_acceptances` with customer/account/ticket references;
- add lifecycle, current-version, context/key, display-order, and current-published indexes;
- add append-only protection for acceptance rows;
- add `customer-consent:manage` to the authority vocabulary/default ADMIN grants;
- do not seed production legal text.

### V81 — password-primary customer authentication

- add nullable `customers.company_name`;
- add nullable `customer_accounts.password_hash`, `password_changed_at`, and `credential_version`;
- add `customer_registration_intents` with email, password hash, profile values, continuation-secret digest, expiry, consume/cancel state;
- add `customer_registration_intent_consents` referencing immutable policy versions;
- generalize/rename `customer_magic_link_tokens` to purpose-bound customer one-time tokens;
- require every new token writer to provide `purpose`, and bind EMAIL_VERIFICATION/PASSWORD_RESET token email to its intent/account identity with composite foreign keys;
- introduce the storage-neutral `AuthenticationAttemptLimiter` port with content-free fingerprints and the single ADR 0043 Redis adapter; do not add, alter, or dual-write a PostgreSQL customer-auth limiter;
- add session authentication method and credential-version snapshot if required for invalidation;
- keep raw passwords, raw tokens, and raw continuation secrets out of all tables.

### V82 — customer request form binding

- create `ticket_form_selections(ticket_id PK, form_id, form_version, selected_at)`;
- treat `ticket_form_selections` as the sole ticket-level selected form/version source of truth, including tickets with zero custom values;
- backfill one selection for each historical ticket whose non-null `ticket_custom_field_values.form_id/form_version` rows contain exactly one distinct tuple;
- fail migration when a ticket contains more than one distinct tuple; do not guess or add an operational backfill system;
- do not infer a selection for historical tickets with zero values because the old schema did not preserve one;
- after deterministic validation/backfill, drop the redundant value-row `form_id/form_version` columns while preserving typed value rows;
- add the exact FK/indexes needed by selected-form reads and immutable-version resolution;
- update every FK-connected PostgreSQL `TRUNCATE` cleaner;
- change the initial effective access mode to `REGISTRATION_OPTIONAL` only for the untouched seed setting, without overwriting an operator-edited row.

All migrations require empty-database migration, upgrade-path migration, Hibernate validation, constraint tests, and forward-fix rollback notes.

## 9. Transactions, failures, concurrency, and audit

### Registration request

```text
consume allowance through AuthenticationAttemptLimiter
→ validate current registration policy versions
→ hash password
→ replace/create bounded pending registration intent
→ store token digest and continuation-secret digest
→ enqueue EMAIL_VERIFICATION mail intent
→ append CUSTOMER_REGISTRATION_REQUESTED
→ commit
```

Mail network delivery remains post-commit. Failure to persist the intent, token, mail intent, or required audit returns no success and leaves no partial registration.

### Registration verification

```text
consume email token + continuation proof under normalized-email lock
→ confirm current intent and policy versions
→ create verified Customer with name/company
→ create ACTIVE_PASSWORD CustomerAccount with password hash
→ append consent acceptances
→ append CUSTOMER_REGISTRATION_VERIFIED and CUSTOMER_CONSENT_ACCEPTED
→ consume intent/token
→ commit
```

Concurrent consume produces one account. A race with an existing verified account fails closed without replacing its credential or profile.

### Password login

```text
consume allowance through AuthenticationAttemptLimiter using purpose-bound normalized-email/network fingerprints
→ always execute a real-or-dummy password hash comparison
→ verify ACTIVE + PASSWORD credential
→ rotate session
→ append success/failure security event
→ return CurrentCustomer or generic problem
```

Use a generic response and comparable work for unknown, wrong-password, disabled, pending, and passwordless identities. Follow the existing same-origin HttpOnly/Secure/SameSite session and CSRF design. OWASP's authentication guidance is the reference for generic errors and login throttling: <https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html>.

### Password reset

Reset request is enumeration-safe and mail-backed. Successful reset consumes one token, increments `credentialVersion`, replaces the hash, revokes every existing customer session, and writes the security audit in one transaction. Replay and concurrent consume cannot create a second password change.

### Passwordless registration completion

The current session must have `credentialState=PASSWORDLESS`; CSRF is required. Completion writes password/profile/registration consents, increments credential version, rotates the current session, revokes older sessions, and records registration completion atomically. A password account cannot use the endpoint to bypass the password-reset flow.

### Request creation

```text
pre-committed abuse budget
→ for anonymous input, generate a server-only plannedCustomerId
→ quarantine/upload/scan files outside the ticket transaction under that opaque owner UUID
→ begin final database transaction
   → lock scoped clientCommandId and compare the canonical request/attachment-manifest hash
   → return the prior logical result plus a fresh access grant on an exact replay
   → validate current request consent policies
   → resolve current customer form/version and normalize candidate values
   → create anonymous Customer(plannedCustomerId), or resolve the current session Customer
   → create Ticket + first PUBLIC Comment
   → persist selected form/version + field values and consent acceptances
   → write one TicketAudit and required consent security event
   → link CLEAN PUBLIC attachments owned by the same customer UUID
   → persist request access token, idempotency receipt, and mail intent
→ commit
```

Any form, consent, Customer, TicketAudit, security-audit, attachment-link, access-token, idempotency-receipt, or mail-intent persistence
failure rolls back the final transaction. File work remains outside it: successful earlier uploads are CLEAN but unlinked and cleanup-eligible.
The previously consumed public abuse budget remains consumed by its existing separate transaction.

## 10. Security and privacy acceptance

- Password hashes use the customer-specific adaptive encoder; plaintext is never persisted.
- Login and recovery responses do not reveal account, credential, passwordless, disabled, or pending state.
- All authentication request endpoints use the storage-neutral purpose/destination/network throttling contract and return `Retry-After` on `429`; ADR 0043 selects exactly one Redis adapter while capacity evidence remains a separate release gate.
- Registration intent, verification, reset, and magic tokens are purpose-bound, single-use, expiring, digest-only, and control-character bounded.
- Session cookies remain HttpOnly, Secure in production, SameSite=Lax, and server-revocable.
- Password reset and passwordless completion revoke/rotate sessions.
- Existing anonymous tickets are never auto-claimed from email equality.
- Company name is customer PII and is excluded from logs, routine auth audit metadata, webhooks, and customer-list projections unless explicitly contracted.
- Consent documents reject raw HTML, scripts, attachment blocks, unsafe URLs, controls, and unbounded text.
- Staff-only fields/options never appear in customer form projection, errors, counts, or audit metadata.
- Customer-submitted custom values do not enter ordinary logs or unprotected admin/security audit.
- Required audit failure is fail-closed for credential, policy, consent, and ticket mutations.

## 11. Vertical implementation sequence

Each numbered task uses `CODEX_TASK_TEMPLATE.md`, owns one branch/worktree, and records Requirement IDs, ADRs, operation IDs, migration files, audit events, gates, commands run, and explicit non-goals.

### Phase 0 — Contract freeze

#### Task 1: Supersede the customer authentication decision

**Deliverable:** ADR 0042 plus decision, blueprint, requirement, privacy, settings-catalog, command/event, and gate updates.

**Acceptance:** password-primary and passwordless-only magic semantics are unambiguous; consent publish is immediate with server-owned equal effective/published timestamps; initial request idempotency and capability replay are explicit; claim behavior remains unchanged; new requirements and gates are traceable.

**Likely files:** new ADR, `docs/25`, `docs/26`, `docs/34`, `docs/37`, `docs/52`.

**Scope:** M. **Dependencies:** none.

#### Task 2: Freeze customer identity and consent contracts

**Deliverable:** all identity, public consent, and admin consent operations with request/response/problem/audit/rate-limit/PII examples.

**Acceptance:** contract-quality validation passes; every mutation schema is marked `MANUAL`; no example contains a usable credential.

**Likely files:** `api/customer-identity-api-v1.yaml`, new Core fragment, bundled Core artifact, API documentation tests.

**Scope:** M. **Dependencies:** Task 1.

#### Task 3: Freeze form projection and request-creation contracts

**Deliverable:** candidate projection, form-aware JSON/multipart create body, direct removal of `privacyConsent`, and stable problem catalog.

**Acceptance:** no v2/legacy schema remains; JSON and multipart represent the same stable client command; same-payload replay and mismatched-payload conflict are contracted without retaining a raw access capability; customer/staff projection boundaries are explicit.

**Likely files:** `api/core-api-base-v1.yaml`, `api/core-api-fragments/10-ticket-configuration.yaml`, bundled Core artifact, contract tests.

**Scope:** M. **Dependencies:** Task 1.

### Checkpoint A — contract approval

- ADR/decision/requirement/gate review complete;
- `make docs-check` passes;
- deterministic Core bundle parity passes;
- no backend production code begins before this checkpoint is approved.

### Phase 1 — Consent policy vertical slices

#### Task 4: Persist immutable consent policy versions

**Deliverable:** V80 schema, root policy/acceptance types, canonical document validation adapter, and PostgreSQL migration/invariant tests.

**Acceptance:** published rows and acceptance rows reject update/delete; one active published version per key/context is deterministic; unsafe documents fail before persistence.

**Scope:** M. **Dependencies:** Task 2.

#### Task 5: Deliver administrator consent-policy lifecycle

**Deliverable:** list/create/read/update/publish/archive application service and Admin controllers with authority, expected actor, CSRF, If-Match, and audit.

**Acceptance:** wrong role/capability is denied; stale writes are non-mutating; audit failure rolls back; published history remains resolvable.

**Scope:** M. **Dependencies:** Task 4.

#### Task 6: Deliver current customer consent projection

**Deliverable:** context-filtered current-policy endpoint and strict response decoder tests.

**Acceptance:** only active published versions appear in display order; archived/draft versions do not affect existence/count; safe document/checksum/version are stable.

**Scope:** S. **Dependencies:** Task 4.

### Checkpoint B — consent foundation

- consent migration, administration integration, authorization, audit rollback, and public projection tests pass;
- ADMIN can publish synthetic test policies;
- registration remains disabled when required policies are unavailable.

### Phase 2 — Password-primary authentication

#### Task 7: Generalize customer credential and token storage

**Deliverable:** ADR 0043 Redis owner-decision amendment and declared target/SLO, the storage-neutral limiter port with exactly one Redis adapter, V81, customer-specific Argon2id encoder, token purpose model, registration-intent persistence, and cleaner updates.

**Acceptance:** the Redis adapter uses atomic expiring counters, keyed purpose/destination/network fingerprints, bounded TTL, and generic fail-closed `503`; declared performance targets are reported `Not run`, not passed. No dual write exists, no shared-store operation is held during adaptive hashing, DB has no plaintext credential/token columns, purpose mismatch fails, and clean migration plus existing magic-link regression tests pass.

**Scope:** M. **Dependencies:** Tasks 1, 4.

#### Task 8: Deliver standard registration and email verification

**Deliverable:** registration request, protected verification mail, continuation cookie, verification consume, profile/account creation, consents, and security audit.

**Acceptance:** generic response for new/existing emails; browser-proof mismatch does not activate; concurrent verification creates one account; audit/outbox failure rolls back.

**Scope:** M. **Dependencies:** Tasks 6, 7.

#### Task 9: Deliver password login

**Deliverable:** password-session endpoint, dummy hash path, rate limiting, session rotation, current-customer credential projection, and tests.

**Acceptance:** unknown/wrong/disabled/passwordless are externally indistinguishable; throttling includes `Retry-After`, shared-store failure returns generic `503`, and the target burst does not starve unrelated business transactions; password success creates one audited session and no password appears in output/logs.

**Scope:** M. **Dependencies:** Task 8.

#### Task 10: Deliver password reset

**Deliverable:** reset request/consume endpoints, reset mail template, credential-version update, all-session revocation, audit, and concurrency tests.

**Acceptance:** request is enumeration-safe; token is single-use; reset replay cannot rotate twice; all old sessions fail immediately.

**Scope:** M. **Dependencies:** Task 9.

#### Task 11: Restrict magic links and complete passwordless registration

**Deliverable:** existing magic request issues mail only for passwordless/anonymous identities, consume returns onboarding state, and authenticated completion sets password/profile/consents.

**Acceptance:** password account receives no magic-login mail; passwordless login works; completion requires session+CSRF; completion never claims a ticket by email; explicit claim proof still works.

**Scope:** M. **Dependencies:** Tasks 6, 9.

### Checkpoint C — identity flow

- registration → verification → password login → logout works through real HTTP and Mailpit;
- anonymous identity → magic login → registration completion → password login works;
- reset revokes old sessions;
- existing customer-authentication gates plus all newly registered password and passwordless-onboarding gates, relevant mail gates, `ARCH-004`, and secret/log scans pass.

### Phase 3 — Customer form submission

#### Task 12: Project forms from candidate customer values

**Deliverable:** candidate projection input/output types, customer condition facts, server projection endpoint, and visibility/type/stale-version tests.

**Acceptance:** custom fields created by ADMIN can appear; staff-only fields never appear; conditional dependencies are deterministic; final validation does not trust the preview.

**Scope:** M. **Dependencies:** Task 3.

#### Task 13: Bind selected form and typed values to ticket creation

**Deliverable:** V82, selected-form persistence, customer create mutation handler, `SubmitPublicRequestCommand` extension, typed value normalization, and ordered metadata-only audit events.

**Acceptance:** form/version is retained even with zero values; invalid type/option/requiredness is non-mutating; hidden/readonly values are dropped; one TicketAudit covers ticket creation.

**Scope:** M. **Dependencies:** Task 12.

#### Task 14: Bind request consent and clean the JSON contract

**Deliverable:** form-aware JSON controller/application orchestration, request-policy validation/acceptance, direct `privacyConsent` removal, and atomic failure tests.

**Acceptance:** anonymous and session identities follow their distinct input rules; current consent version is required; consent/audit failure returns no ticket; INTERNAL/staff-only data is absent.

**Scope:** M. **Dependencies:** Tasks 6, 13.

#### Task 15: Replace multipart submission with JSON-part plus attachments

**Deliverable:** new multipart adapter reusing existing upload/quarantine/link services and two-phase validation.

**Acceptance:** JSON and multipart produce equivalent tickets/form values/consents; an anonymous upload uses a server-only planned Customer UUID and creates no Customer before file validation; third-file failure leaves no Customer/Ticket/acceptance/mail intent, earlier CLEAN files are unlinked and cleanup-eligible; stale policy/form or audit/mail-intent failure rolls back Customer/Ticket/link state; cross-owner attachment linking is denied; authenticated submission creates no new Customer.

**Scope:** M. **Dependencies:** Task 14.

### Checkpoint D — backend P0 complete

- anonymous and password-authenticated request creation work;
- administrator-selected customer fields and custom fields render and persist;
- Terms/Privacy and request consent versions are provable;
- attachments, first PUBLIC comment, customer isolation, and explicit claim regressions pass;
- clean PostgreSQL migration, Modulith verification, OpenAPI drift, and `make docs-check` pass.

### Phase 4 — UI consumers after backend freeze

#### Task 16: Customer registration/login/onboarding UI

Implement registration, verification, password login, password reset, passwordless onboarding, and all loading/validation/rate-limit/expired/denied/error states against the frozen identity contract.

#### Task 17: Admin consent-policy UI

Implement draft editor, preview, publish, archive, stale conflict, permission denial, and immutable version history using the canonical document editor contract.

#### Task 18: Dynamic customer request-form UI

Render the server projection, request candidate projections when dependencies change, preserve values/drafts, submit typed values and policy versions, and cover anonymous/authenticated/multipart flows.

Frontend tasks must separately follow `frontend/AGENTS.md`, the Storybook MCP documentation contract, focused/full story tests as applicable, interaction tests, accessibility gates, and real browser E2E.

## 12. Validation commands and evidence

Minimum backend/document validation per affected slice:

```text
make docs-check
python3 scripts/bundle_core_openapi.py --check
cd backend && ./gradlew test --tests '*CustomerConsent*'
cd backend && ./gradlew test --tests '*Customer*Auth*'
cd backend && ./gradlew test --tests '*PublicRequest*'
cd backend && ./gradlew test --tests '*TicketConfiguration*'
cd backend && ./gradlew test
```

Required evidence classification:

- `Passed`: command executed and completed successfully;
- `Failed`: product/test failure with exact failing scenario;
- `Blocked`: environment/infrastructure prevented execution;
- `Not run`: validation was intentionally not executed.

Local success is not remote CI success. Mailpit E2E, clean Flyway/PostgreSQL, audit failure injection, concurrent token consume, concurrent registration, login throttling, session revocation, attachment regression, and module verification require explicit evidence.

## 13. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Existing ADR and code assume magic-link-only auth | High | Superseding ADR and contract-only checkpoint before code |
| Account pre-hijacking through victim email registration | High | Email token plus browser-bound continuation proof; no token-only activation |
| Password hash cost creates auth DoS | High | Argon2id benchmark, strict login limits, dummy comparison, bounded concurrency evidence |
| Per-attempt PostgreSQL writes turn an authentication attack into hot-row, WAL, lock, or connection-pool pressure | High | ADR 0043 owner-selected Redis adapter, coarse ingress limit, storage-neutral port, and no customer-auth limiter write in PostgreSQL |
| Redis creates availability, failover, memory, and operational dependencies before capacity evidence | Medium | Atomic TTL script, noeviction/reserved capacity, TLS/auth/network isolation, health/metrics, fail-closed `503`, one adapter without dual writes, and explicit `Not run` capacity status |
| Admin edits rewrite accepted legal text | High | Immutable published versions; acceptance references version/checksum |
| Missing legal policy is silently accepted | High | Registration/request fail closed until required active policy exists |
| Staff-only custom field leaks through projection/error | High | Server-side allowlist, existence-safe error, direct-ID negative tests |
| New required form breaks current code paths | Medium | Directly update all current v1 clients/tests because there is no deployed consumer |
| Form/policy changes during multipart upload | Medium | Validate before upload and again at final transaction; stable conflict and cleanup |
| New FK tables break test isolation | Medium | Inventory and update every affected TRUNCATE cleaner in the migration slice |
| Password reset becomes an alternate login bypass | High | Purpose-bound token, no session creation, all-session revocation, single use |
| Company/consent data leaks to audit or integrations | High | Metadata allowlists and negative log/export/webhook tests |

## 14. Recommended decisions and recorded owner decision

The following are recommendations made by this plan and can be changed before Checkpoint A:

1. Standard registration requires email verification; the verification link is a distinct `EMAIL_VERIFICATION` token and is not treated as magic-link login.
2. Registration activation requires both the email token and an HttpOnly browser-bound continuation secret to prevent account pre-hijacking.
3. Customer passwords use a dedicated Argon2id encoder with 19 MiB memory, 2 iterations, and parallelism 1; staff BCrypt behavior remains unchanged.
4. Passwords allow 12–128 characters, spaces, and Unicode, reject controls, and have no composition rule.
5. Password-reset token TTL is 30 minutes; magic-login TTL remains 15 minutes; registration-verification TTL is 24 hours.
6. Password reset is included in P0 because password-primary login without credential recovery creates an avoidable account-lockout path.
7. Consent policy content uses the existing canonical safe block-document contract with a stricter consent subset, rather than adding HTML/Markdown storage.
8. No real Terms, Privacy, or request-processing legal text is seeded. Synthetic fixtures are test-only, and registration/request consent fails closed until ADMIN publishes required policies.
9. Registration acceptance retention follows the account record plus 365 days after account deletion by default; request-submission acceptance follows the ticket/support-record retention. Referenced policy versions are retained at least as long as any acceptance.
10. Production migrations do not seed tenant-specific request type/product area/urgency/order options. Development fixtures may demonstrate them; ADMIN creates the real fields/options/forms.
11. Customer urgency remains a custom field and never automatically changes internal ticket priority.
12. The current `/api/v1/requests` and identity contracts are changed in place; `privacyConsent`, the flat multipart body, compatibility adapters, and v2 endpoints are not retained.
13. A new `customerconsent` module owns policy and acceptance semantics instead of storing legal content in generic system settings.
14. The first implementation uses `REGISTRATION_OPTIONAL` as the effective customer access mode so anonymous and registered submission coexist.
15. The product owner raised the risk of database write/lock pressure and duplicated PostgreSQL-to-Redis implementation work, then amended ADR 0043 on 2026-08-26 to select one Redis adapter directly. The PostgreSQL comparison benchmark is waived; the declared capacity/SLO remains unmeasured release evidence.

The authentication implementation can proceed after Task 7 records the Redis owner decision and target load/SLO. It must report capacity evidence as `Not run` until measured. Actual production legal text and jurisdiction-specific retention remain operator/legal-owner inputs, not values generated by the implementation.
