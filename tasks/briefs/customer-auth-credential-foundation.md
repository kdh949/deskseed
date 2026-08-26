# Customer authentication credential foundation

## Goal

고객 비밀번호를 staff BCrypt와 분리된 Argon2id 정책으로 해시하고, registration intent와 purpose-bound
one-time token을 raw secret 없이 원자적으로 저장·소비할 수 있는 내부 기반을 제공한다.

## Decision and source references

- Decision IDs: D-057, D-058, D-060
- Accepted ADRs: ADR 0029, 0042, 0043
- Requirements: REQ-AUTH-003, REQ-AUTH-004
- Plan: docs/56 sections 5.3, 5.4, 9, 12 Task 7
- Verification gates: AUTH-001, AUTH-002, AUTH-005, AUTH-006, AUTH-007, AUTH-008, ARCH-004
- API operations: 없음. 이 slice는 Tasks 8–11 내부 application service가 사용할 persistence/crypto 기반이다.

## Actor and source

- Future actor/source: `CUSTOMER_ANONYMOUS` or authenticated `CUSTOMER_ACCOUNT`, `CUSTOMER_PORTAL`
- Required roles/scopes: 새 HTTP surface가 없어 해당 없음.
- Request/correlation: intent/token에는 command context의 bounded request/correlation ID만 저장한다.
- Resource constraint: verification token은 registration intent, reset token은 account에만 연결된다.

## In scope

- 12–128 Unicode code point, control-character rejection customer password policy
- Argon2id: 19 MiB, 2 iterations, parallelism 1, 16-byte salt, 32-byte hash
- protected customer password-hash value type; existing staff `PasswordEncoder` remains BCrypt
- typed PASSWORDLESS_LOGIN / EMAIL_VERIFICATION / PASSWORD_RESET target and expected-purpose consume
- advisory-lock serialized pending registration replacement, immutable policy selections, continuation digest
- transaction-mandatory proof lock and expected-version single consume
- rollback, concurrency, purpose mismatch, raw-secret absence tests

## Out of scope

- registration, verification, password login/reset, passwordless completion HTTP/application flows
- current-policy validation, mail/audit writes, customer/account creation and session invalidation
- supported-hardware Argon2 benchmark, cleanup scheduler, production legal text

## Invariants and failure semantics

- persistence accepts `CustomerPasswordHash`, never raw password.
- generated token and continuation values override string rendering and DB stores SHA-256 digests only.
- one normalized email has one PENDING intent; replacement cancels the prior intent in the same transaction.
- failed consent FK insertion rolls the cancellation and new intent back together.
- proof lock requires an existing outer transaction; consume uses expected version and succeeds once.
- wrong-purpose token consume mutates nothing and does not reveal the actual purpose.
- adaptive hashing finishes before any registration store transaction starts.

## Data and privacy

- Stored: normalized/display email, Argon2id hash, profile, digest proofs, immutable policy IDs/versions, context IDs.
- Not stored/logged/audited: raw password, raw token, raw continuation secret.
- `toString()` for hash/token/intent values is protected so incidental structured logging does not expose secret/PII fields.
- Export/webhook exposure: none.

## Acceptance scenarios

1. A valid Unicode password produces the declared Argon2id parameters and matches only the original input.
2. Too-short, too-long, or control-containing password input fails before hashing; staff BCrypt remains the default bean.
3. Concurrent same-email replacements both complete but leave exactly one PENDING intent.
4. Invalid policy selection rolls back the prior-intent cancellation and new insert.
5. Wrong-purpose consume leaves a token usable by its intended consumer; intended consume succeeds once.
6. Correct continuation proof locks a live intent and expected version consumes it once; wrong, oversized, expired, or replayed proof fails.

## Validation

- focused `CustomerPasswordHasherTest`
- focused `CustomerCredentialPersistenceIntegrationTest`
- existing magic-link integration and Mailpit regression tests
- `./gradlew fastTest contractTest`
- `make docs-check`
- `git diff --check`
- raw secret and public-package boundary scans

## Compatibility and migration

- OpenAPI and UI: unchanged.
- Uses V81 from the parent PR; no additional migration or backfill.
- Existing passwordless magic tokens still use Spring Security OTT through the PASSWORDLESS_LOGIN target.
- Application rollback removes only unused internal APIs/dependency; V81 forward-fix/restore notes remain owned by Task 7B.

## Human explanation

- A separate component keeps customer Argon2 work from silently replacing staff BCrypt behavior.
- Typed token targets make invalid purpose/resource combinations unrepresentable before the DB constraint.
- PostgreSQL advisory locking bounds pending registration to one row without introducing a second coordination store.
- Argon2 and Redis supported-deployment performance evidence remains `Not run`; no capacity claim is made.
