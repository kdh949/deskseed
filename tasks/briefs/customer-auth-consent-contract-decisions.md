# Customer authentication, consent, and request-form contract decisions

## Goal

고객이 password-primary 계정을 안전하게 만들고, 현재 정책에 동의하며, 서버가 허용한 문의 form/version으로 접수할 수 있도록 구현 전 결정·권한·감사·실패·검증 계약을 동결한다.

## Decision and source references

- Decision IDs from `docs/25-implementation-decision-register.md`: D-040 superseded portion, D-057, D-058, D-059
- Accepted ADRs: ADR 0006, 0013, 0018, 0029 retained boundaries, 0034, 0037, 0041, 0042
- PRD/domain sections: docs/01, docs/02, docs/23, docs/33, docs/34, docs/37, docs/52, docs/56
- API contract operation IDs: `requestCustomerRegistration`, `verifyCustomerRegistration`, `createCustomerPasswordSession`, `requestCustomerMagicLink`, `consumeCustomerMagicLink`, `completePasswordlessCustomerRegistration`, `requestCustomerPasswordReset`, `resetCustomerPassword`, `getCurrentCustomer`, `listCurrentCustomerConsentPolicies`, admin customer-consent lifecycle operations, `projectCustomerTicketForm`, `createCustomerRequest`
- Verification gate IDs: AUTH-001 through AUTH-008, CONSENT-001/002, CFG-001 through CFG-006, ARCH-001/002/004, TKT-001/002, CHG-001/002/003, FILE-001/003/004/006, MAIL-001/002, DOC-001

## Actor and source

- Actor type: `CUSTOMER_ANONYMOUS`, `CUSTOMER_ACCOUNT`, `STAFF_ADMIN`
- Source: `CUSTOMER_PORTAL`, `ADMIN_UI`
- Required role/scopes: consent policy mutation requires active ADMIN plus `customer-consent:manage`; customer commands require purpose-bound proof or the current customer session as contracted
- Resource constraints: current consent context/version, current published customer form/version, customer-visible field/option projection, own customer session or ticket-scoped anonymous proof
- Interaction/request/correlation semantics: every command carries server actor/source/request/correlation; customer credential/consent/ticket mutations retain one transaction-specific correlation without placing secrets or content in metadata

## Product and UX contract

- Requirement IDs: REQ-AUTH-001/002/003/004, REQ-CONSENT-001/002, REQ-CFG-014, REQ-TKT-001/002/004/006/008
- Screen IDs / route IDs: backend contract freeze only; frontend routes are Phase 4 and out of scope
- OpenAPI operationIds: listed in Decision and source references; Task 2/3 own their final schemas
- Zendesk parity pattern from docs/51: workflow/IA inspiration only; no proprietary pixels/assets are part of this backend task
- loading/empty/error/denied/conflict states: OpenAPI must distinguish generic auth failure, rate limit, invalid/expired proof, stale policy/form, denied admin, audit unavailable, and field validation without existence leaks
- keyboard/focus/accessibility requirements: not applicable to this document-only task; later UI consumers must follow frontend gates
- visual regression fixtures and widths: not applicable

## In scope

- ADR 0042 and D-057 through D-059;
- narrow requirement IDs and executable gate definitions;
- password-primary/passwordless-only identity blueprint and settings inventory;
- immutable consent authority, lifecycle, acceptance, audit, privacy, and retention boundaries;
- server-authorized form projection/submission decision and restoration of existing CFG gate definitions;
- authoritative plan reference corrections and this task brief.

## Out of scope

- OpenAPI schemas and generated bundle, owned by Task 2 and Task 3;
- migrations V80–V82, Kotlin/runtime code, tests, UI, Storybook, and production legal text;
- social/OIDC/SSO/MFA, organization membership, consent withdrawal, automatic email-match ticket claim, external order/company fetch;
- changes to staff BCrypt/bootstrap authentication.

## Invariants and failure semantics

- domain invariants: password account cannot use magic login; purpose-bound proofs are not interchangeable; email equality cannot claim tickets; published consent versions and acceptances are append-only; final customer submission revalidates current form/policy versions
- transaction boundary: credential/profile/current registration consent; policy mutation/audit; and ticket/form/value/request consent are each atomic as defined in docs/34 and docs/56
- audit obligation: every credential/policy/consent mutation and defined failure produces bounded metadata-only security/admin audit; ticket creation retains one TicketAudit with ordered metadata-only events
- audit failure behavior: required audit failure returns no credential, policy, acceptance, or ticket success
- concurrency: token/continuation consume is single winner; policy/form uses version preconditions; final submission rechecks current published versions
- idempotency/retry: request endpoints use generic repeatable responses and throttling; consume/replay cannot mutate twice; final ticket command retains its stable command boundary
- external I/O boundary: email network delivery occurs only after durable intent commit; no external fetch occurs in credential, consent, or ticket transaction

## Data and privacy

- data read/written: email/profile/company, adaptive password hash, digest-only proofs, customer session/credential version, immutable policy/version, append-only acceptance, selected form/version and typed values
- PII/secrets: raw password/token/continuation/session cookie are never persisted in plaintext or emitted; company name and form values are high-sensitivity data
- retention category: registration acceptance follows account +365 days after deletion by default; request acceptance follows ticket retention; referenced policy versions outlive references
- redaction/encryption: audit/log/webhook metadata excludes document body, password/hash, token, session, company, and field values; environment-owned keys/secrets remain outside database
- export/webhook exposure: absent until separately contracted and authorized

## Threats changed

- authorization bypass: explicit `customer-consent:manage`, current customer session/proof, server form projection
- impersonation: email token plus browser continuation proof prevents token-only registration pre-hijack
- replay/duplicate: purpose-bound digest tokens, single consume, credential version, `If-Match`, final version validation
- SSRF/XSS: no external fetch; consent document uses safe block allowlist and rejects raw HTML/unsafe URL/attachments
- secret leakage: generic responses, content-free fingerprints, bounded audits, no usable credential examples
- audit bypass/tampering: mutation and required audit atomicity; published policies/acceptances append-only to runtime role
- concurrency/data loss: normalized-email consume serialization, session rotation/revocation, versioned policy/form conflicts

## Acceptance scenarios

1. Given a password account, when magic-link login is requested, then the response is generic and no magic-login intent is created.
2. Given a pending registration, when only the email token or a different browser continuation proof is supplied, then no account, consent acceptance, or authenticated session is created.
3. Given unknown, wrong-password, disabled, passwordless, or incomplete identity input, when password login runs, then the external problem is indistinguishable and real-or-dummy adaptive work plus throttling applies.
4. Given a valid reset token, when concurrent consume occurs, then one credential change succeeds and every previous session is revoked.
5. Given a non-ADMIN or ADMIN without `customer-consent:manage`, when policy lifecycle is called, then it is denied without partial policy/audit state.
6. Given a published consent version with acceptance references, when an edit is requested, then a new draft/version is created and historical content remains resolvable.
7. Given missing, duplicate, wrong-context, archived, or stale policy references, when registration/request final validation runs, then the account/ticket transaction is non-mutating.
8. Given staff-only, hidden, readonly, invalid-option, or stale form input, when customer projection/submission runs, then protected definitions do not leak and final ticket creation is rejected or values are dropped according to ADR 0041.
9. Given matching verified email without ticket proof, when claim/list is attempted, then ownership does not change and AUTH-004 remains enforced.

## Validation

- Gates: AUTH-001 through AUTH-008, CONSENT-001/002, CFG-001 through CFG-006, ARCH-001/002/004, TKT-001/002, CHG-001/002/003, MAIL-001/002, DOC-001
- Task 1 commands: `make docs-check`, `python3 scripts/bundle_core_openapi.py --check`, `git diff --check`
- Task 2/3 add API documentation quality/contract tests; PostgreSQL behavior tests begin only in later checkpoints.

## Compatibility and migration

- OpenAPI change classification: Task 2/3 intentionally change current v1 in place because no production consumer has shipped; no v2 or compatibility adapter
- migration/rollback: no migration in Task 1; V80–V82 are future additive forward-only Flyway migrations
- backfill: future V82 changes only the untouched access-mode seed and preserves operator edits
- existing client/UI impact: later frontend work must update directly for password-primary identity, policy versions, form-aware request, and multipart JSON part; Task 1 changes no runtime

## Human explanation

- why this domain/transaction boundary: identity, consent, configuration, ticket, and mail have different invariants; root APIs coordinate without cross-module internals
- why this permission and audit behavior: legal policy mutation is an explicit ADMIN capability and every accepted version must be attributable without copying bodies into audit
- why this is the simplest sufficient technology: PostgreSQL, Spring server sessions, adaptive hashes, durable mail intent, and existing typed form/condition infrastructure satisfy current evidence without new infrastructure
- what measured evidence would change the design: supported-host Argon2 latency/DoS evidence, deployed compatibility needs, legal retention/withdrawal requirements, SSO/MFA demand, or measured PostgreSQL limits

## Completion report

- Task 1 changes decisions/docs only and begins no production implementation.
- Task 2/3 must report contract operations, exact diff, local/remote validation, and deliberately unimplemented backend/UI work.
- Checkpoint A ends after the stacked contract PRs are review-ready and green; Checkpoint B implementation requires separate continuation.
