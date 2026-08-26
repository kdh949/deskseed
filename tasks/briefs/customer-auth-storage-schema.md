# Customer authentication storage schema

## Goal

비밀번호 우선 고객 인증이 이후 registration, login, reset 흐름에서 원문 secret 없이 사용할 수 있도록
V81 credential, registration-intent, purpose-bound token 저장 구조를 추가하고 기존 magic-link 동작을 보존한다.

## Decision and source references

- Decision IDs: D-057, D-058, D-060
- Accepted ADRs: ADR 0029, 0042, 0043
- PRD/domain sources: docs/56 sections 4, 7, 9, 12 Task 7
- API contract operations: 없음. 이 slice는 이미 확정된 고객 인증 operation의 내부 저장 기반만 변경한다.
- Verification gates: AUTH-001, AUTH-002, AUTH-003, AUTH-005, AUTH-006, AUTH-007, AUTH-008,
  ARCH-004, OPS-001

## Actor and source

- Actor type/source: 이후 `CUSTOMER_ANONYMOUS`/`CUSTOMER_ACCOUNT`, `CUSTOMER_PORTAL` 흐름이 사용한다.
- Required role/scopes: 이 slice에는 새 HTTP surface나 권한이 없다.
- Resource constraints: verification token은 registration intent에, reset token은 account에 목적별로만 연결된다.
- Request/correlation: pending intent와 one-time token에는 생성 command의 request/correlation ID만 저장한다.

## Product and UX contract

- Requirement IDs: REQ-AUTH-003, REQ-AUTH-004
- Screen/route/OpenAPI change: 없음
- UI states/accessibility/visual regression: 해당 없음

## In scope

- V81 clean 및 V80 upgrade migration
- nullable customer company, password hash/change time, credential version
- pending registration intent와 immutable registration-policy version 선택
- `PASSWORDLESS_LOGIN`, `EMAIL_VERIFICATION`, `PASSWORD_RESET` 목적 기반 one-time token
- session authentication method와 credential-version snapshot
- 기존 magic-link JDBC runtime의 새 table/purpose 사용
- 새 FK에 연결된 PostgreSQL test cleaner 전체 갱신

## Out of scope

- Argon2id encoder와 registration-intent repository/application API는 다음 Task 7 child slice에서 구현한다.
- registration, verification, password login/reset, passwordless completion HTTP 흐름과 audit은 Tasks 8–11이다.
- raw credential migration, 계정 backfill, production legal-policy seed는 없다.

## Invariants and failure semantics

- DB에는 raw password, raw token, raw continuation secret를 저장하지 않는다.
- one-time token purpose와 연결 resource 조합이 맞지 않으면 DB constraint가 거부한다.
- 같은 normalized email에는 `PENDING` registration intent가 하나뿐이다.
- registration consent는 immutable policy version과 `REGISTRATION` context를 함께 참조한다.
- 기존 magic-link token은 V81에서 `PASSWORDLESS_LOGIN`으로 보존되고 consume은 같은 purpose만 허용한다.
- 이 slice에는 새 transaction/audit/external-I/O 동작이 없다.

## Data and privacy

- 저장: normalized/display email, Argon2id hash 자리, profile values, digest-only proofs, purpose, timestamps.
- secret: password/token/continuation 원문은 schema와 예시 모두에 없다.
- retention: 인텐트와 proof 정리 정책은 후속 application slice에서 구현하며 expiry/cleanup index를 먼저 제공한다.
- export/webhook/log 노출: 없음.

## Threats changed

- 목적 혼동과 replay는 purpose/resource constraint 및 single-use consume 조건으로 제한한다.
- continuation digest와 token digest uniqueness가 proof 재사용을 거부한다.
- credential version/session snapshot이 후속 reset·rotation에서 오래된 session을 무효화할 기반을 제공한다.
- migration은 기존 magic-link token digest와 expiry/consume 상태를 그대로 보존한다.

## Acceptance scenarios

1. Given V80 magic-link/session/account rows, when V81 migrates, then token purpose와 session credential snapshot이
   안전한 default로 채워지고 PostgreSQL limiter table은 제거된다.
2. Given an empty database, when migrations reach V81, then Hibernate validation and purpose-bound constraints pass.
3. Given a pending email intent, when another pending intent uses the same normalized email, then the unique constraint rejects it.
4. Given a token purpose with the wrong resource link, when inserted, then the database rejects it.
5. Given the existing magic-link HTTP flow, when mail is requested/delivered/consumed, then it remains single-use and
   stores only a digest under `PASSWORDLESS_LOGIN`.

## Validation

- `./gradlew migrationTest --tests dev.deskseed.customerauth.internal.CustomerAuthenticationMigrationTest`
- focused magic-link, limiter-unavailable, portal integration tests
- `./gradlew slowTest --tests dev.deskseed.outboundmail.internal.MailpitApiE2ETest`
- `./gradlew fastTest contractTest`
- `make docs-check`
- `git diff --check`
- legacy table-name and raw-secret column scan

## Compatibility and migration

- OpenAPI change: 없음.
- V81 is forward-only. It renames the internal pre-release magic-link token table and changes the runtime SQL in the
  same child PR; no production consumer or released DB integration exists.
- Existing token/account/session rows are preserved with `PASSWORDLESS_LOGIN`, `MAGIC_LINK`, and credential version 0.
- PostgreSQL limiter rows are intentionally not backfilled because Redis is the only authoritative limiter after Task 7A.
- Rollback is backup restore or a reviewed forward-fix migration. Applied Flyway history is never edited and a binary
  that only knows the V80 token table must not be deployed against V81.

## Human explanation

- Purpose and resource identity live in one constrained token table so verification, reset, and login cannot be confused.
- Pending registration is separate from a customer/account so unverified input never becomes an active identity.
- This is the minimum PostgreSQL structure required by D-057; hashing work and HTTP/audit transactions remain in later
  reviewer-sized slices.

## Completion report

- Report V81 clean/upgrade/Hibernate/constraint and magic-link regression results separately.
- State that application endpoints, Argon2 supported-hardware benchmark, cleanup worker, and Tasks 8–11 are not implemented.
- Report migration as forward-only and capacity evidence as `Not run`.
