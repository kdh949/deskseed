# Task 26 — Customer consent persistence foundation

## Goal

관리자와 고객 인증·문의 command가 이후 slice에서 사용할 수 있도록 동의 정책의 editable draft, immutable published version, append-only acceptance를 PostgreSQL과 root API로 제공한다.

## Decision and source references

- Decision IDs: D-005, D-008, D-013, D-018, D-058
- Accepted ADRs: 0005, 0008, 0013, 0018
- PRD/domain sections: docs/02 sections 3–4, docs/56 sections 6, 8, 9–11
- API contract operation IDs: none activated in this foundation PR; Task 5/6 own runtime operations
- Verification gates: ARCH-001, ARCH-002, CONSENT-001, CONSENT-002

## Actor and source

- Actor type: STAFF for future policy publication, CUSTOMER for future acceptance
- Source: ADMIN_UI or CUSTOMER_PORTAL
- Required role/scopes: future policy mutation requires active ADMIN plus `customer-consent:manage`
- Resource constraints: policy key and context are immutable; acceptance binds one Customer and exactly one account or ticket resource according to context
- Interaction/request/correlation semantics: acceptance records retain bounded request/correlation identifiers; this PR exposes no HTTP command

## Product and UX contract

- Requirement IDs: REQ-CONSENT-001, REQ-CONSENT-002
- OpenAPI operationIds: none promoted to `FROZEN`
- UI states: out of scope

## In scope

- V80 forward-only migration
- customer consent root types and safe canonical document adapter
- immutable published-version and append-only acceptance constraints
- ADMIN authority vocabulary/default grant
- PostgreSQL migration/constraint and pure document tests
- all affected test database cleaners

## Out of scope

- administrator HTTP/application lifecycle, owned by Task 5
- customer current-policy HTTP projection, owned by Task 6
- registration/request acceptance orchestration, owned by Tasks 8 and 14
- production legal text, withdrawal, scheduled activation, or retention jobs

## Invariants and failure semantics

- `(context, policyKey)` identifies one policy root and cannot be changed.
- Published versions and acceptance rows reject runtime update/delete.
- A registration acceptance references an account and no ticket; a request acceptance references a ticket and may reference its account.
- Acceptance references an existing immutable `(policyId, version)` and uses server-owned time.
- Schema or document validation failure is non-mutating.
- No external I/O exists in this slice.

## Data and privacy

- Policy versions store canonical JSON, deterministic plain text, checksum, publish actor snapshot, and server time.
- Acceptances store identifiers/context/source/request/correlation only; they do not duplicate document or customer-entered content.
- Production legal text is not seeded.
- Registration acceptance follows account retention plus the documented post-delete period; request acceptance follows its ticket.

## Threats changed

- immutable evidence tampering
- forged policy version/context linkage
- unsafe HTML, attachment/code blocks, unsafe URLs, and control characters
- accidental legal text seeding or audit/log document duplication

## Acceptance scenarios

- Given V76, when Flyway migrates to V80, then the three consent tables and constraints exist without altering prior migrations.
- Given an immutable version or acceptance, when update/delete is attempted, then PostgreSQL rejects it.
- Given duplicate key/context or invalid acceptance linkage, when inserted, then PostgreSQL rejects it.
- Given a safe consent-subset document, when canonicalized, then deterministic plain text/checksum result.
- Given HTML/code/attachment/unsafe URL/control text or an oversized published document, when validated, then validation fails before persistence.

## Validation

- `cd backend && ./gradlew test --tests '*CustomerConsent*'`
- `cd backend && ./gradlew test --tests 'dev.deskseed.architecture.ArchitectureTest'`
- clean PostgreSQL migration and V76→V80 upgrade-path test
- Hibernate validation through a PostgreSQL-backed Spring integration context
- `git diff --check`

## Compatibility and migration

- OpenAPI change classification: none; no operation is promoted to runtime `FROZEN`.
- Migration/rollback: additive V80 only; rollback is backup restore or forward fix, never editing committed migration history.
- Backfill: none; production legal content is intentionally absent.

## Human explanation

- A mutable root owns the draft/current pointer while immutable rows preserve accepted evidence.
- PostgreSQL constraints and triggers protect cross-command invariants and append-only evidence; Kotlin validates safe document semantics.
- PostgreSQL remains sufficient because the slice needs transactions, FK/unique/check constraints, and no measured independent scale boundary.
