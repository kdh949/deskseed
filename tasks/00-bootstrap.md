# Codex Brief 00 — Backend Bootstrap

## Goal

개발자와 CI가 고정된 Kotlin/Spring Boot 도구체인으로 PostgreSQL/Flyway 기반 백엔드를 실행하고, 현재 고객 문의 명령이 신뢰할 수 있는 actor·request·correlation context를 남길 수 있게 한다.

## Decision and source references

- Decision IDs: D-001, D-002, D-008, D-018
- Accepted ADRs: 0001, 0002, 0008, 0018
- PRD/domain sections: PRD 6, 9, 13; Domain Model 2, 4; Architecture 3, 5
- API operations: `submitRequest`, `getRequest` (customer surface only)
- Verification gates: ARCH-001, ARCH-002, ARCH-004 (implemented customer path)

## Actor and source

- Primary actor: `CUSTOMER`; source: `CUSTOMER_PORTAL`
- Bootstrap and CI configuration are not a runtime user command; future system paths use explicit `SYSTEM` actors.
- Request ID and correlation ID may be supplied only when they pass the bounded identifier policy. Actor identity is derived by the application, never from request headers.

## In scope

- pinned Gradle Wrapper and dependency management
- Spring Boot, PostgreSQL/Flyway, Modulith, and Testcontainers baseline
- UTC, safe logging, actuator and CORS baseline
- request/correlation/command context primitives for the customer request path
- separate runtime and migration credentials in the production profile

## Out of scope

- staff authentication and authorization
- Platform API, external writes, idempotency records, webhooks, and SDKs
- empty future module scaffolding

## Invariants and failure semantics

- ticket mutation and TicketAudit remain in one transaction
- the customer request body remains the first `PUBLIC` comment; no ticket description is introduced
- a client cannot choose a different actor through request metadata
- a failed audit write rolls back the ticket command
- no external I/O is performed inside the ticket transaction

## Data and privacy

- request/correlation/command identifiers are bounded metadata, not request bodies or secrets
- database passwords and CORS origins are environment-configured; real secrets are not committed
- this bootstrap does not add export or webhook exposure

## Threats changed

- actor impersonation through untrusted headers
- missing correlation during ticket audit investigation
- secret leakage through committed build/configuration files
- schema drift from Hibernate DDL

## Acceptance scenarios

1. Given a clean checkout with Java 21 and Docker, when `./gradlew test` runs, then the PostgreSQL-backed Flyway tests and Modulith verification pass.
2. Given a customer request, when the ticket and creation audit are persisted, then the audit contains server-accepted request, correlation, and command identifiers with a `CUSTOMER` actor and `CUSTOMER_PORTAL` source.
3. Given malformed request/correlation headers, when a request is handled, then bounded server-generated identifiers replace them and no actor is inferred from headers.

## Validation

- `cd backend && ./gradlew --no-daemon test`
- inspect the generated migration and Hibernate validation through the PostgreSQL-backed integration suite

## Compatibility and migration

- additive Flyway migration only; existing `V1` is not edited
- no public API response shape change
- existing audit rows receive non-sensitive legacy context values before new columns become non-null
