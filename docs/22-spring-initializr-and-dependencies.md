# Spring Initializr and Dependency Baseline

Status: recommended bootstrap values, not generated code
Checked: 2026-08-10

## 1. Spring Initializr values

```text
Project: Gradle - Kotlin
Language: Kotlin
Spring Boot: 4.1.0
Group: dev.deskseed
Artifact: deskseed-backend
Name: deskseed-backend
Description: Self-hosted customer support and ticketing platform
Package name: dev.deskseed
Packaging: Jar
Java: 21
```

Repository layout may keep backend under `backend/`, but the Java package remains independent of folder layout.

Pin versions in the repository. Do not silently follow `latest` during development or CI.

## 2. Select in Initializr

### Required for the first vertical slice

- Spring Web
- Validation
- Spring Data JPA
- PostgreSQL Driver
- Flyway Migration
- Spring Security
- Spring Boot Actuator

### Add through build configuration

- Jackson Module Kotlin
- Kotlin Reflect
- Spring Modulith core
- Spring Modulith test support
- PostgreSQL Testcontainers
- JUnit/Testcontainers integration
- AssertJ, if not already supplied by test starter

Use the Spring Boot/Spring Modulith BOMs or dependency management recommended by their current official documentation. Do not scatter explicit transitive dependency versions.

## 3. Do not select initially

- Spring Reactive Web / WebFlux
- R2DBC
- Spring for Apache Kafka
- Spring Data Redis
- Spring Data Elasticsearch
- MongoDB
- Spring Batch
- Spring Integration
- OAuth2 Authorization Server
- GraphQL
- Kubernetes discovery/config

These may be added only with the relevant ADR and user/operational problem.

## 4. Add later by capability

### Machine API JWT validation, when OAuth exists

- OAuth2 Resource Server

Do not add merely for scoped API keys. API key authentication can be a dedicated Spring Security filter/provider with secure credential verification.

### OAuth authorization server, when delegated third-party apps exist

- Spring Authorization Server or an external identity provider

This is a product/operations decision, not an MVP dependency.

### Durable local events

- Spring Modulith event publication support appropriate to the selected persistence setup

Add when a listener needs retry/recovery rather than assuming all in-process listeners are durable.

### Kafka

- Spring for Apache Kafka

Add only after versioned integration events, outbox/publication recovery, and an independent consumer requirement exist.

### Observability

- Micrometer tracing and an OpenTelemetry/Datadog exporter chosen for the deployment

Actuator health and basic Micrometer metrics come first.

### API documentation tooling

The authoritative Platform API remains a committed OpenAPI 3.1 document. Runtime annotation tooling may validate or render it, but should not silently become the contract source. Add a compatible renderer/generator only after Boot version compatibility is verified.

## 5. Required configuration principles

```text
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
```

Also configure:

- UTC database/session semantics
- server-generated request ID if missing
- safe structured logging without request/response body
- Flyway migration at startup or controlled deployment step
- secrets through environment/secret manager, not committed config
- separate runtime and migration DB roles in production profile
- explicit CORS origins by environment
- actuator exposure allowlist

## 6. Recommended test categories

```text
unit               pure domain rules
module             application workflow and module boundary
integration        PostgreSQL/Testcontainers, security, transactions
contract           OpenAPI examples, Platform API compatibility
security           scopes, audit, idempotency, SSRF/log leakage
performance        defined synthetic dataset and EXPLAIN ANALYZE
```

Do not use H2 as proof of PostgreSQL trigger, locking, JSON, index, privilege, or transaction behavior.

## 7. Suggested initial package/module outline

```text
dev.deskseed
├── foundation
├── portal
├── customer
├── organization
├── ticketing
├── settings
├── staffaccess
├── audit
├── integration
└── platformapi
```

Each feature module exposes a small root API and keeps implementation under `internal/`. Actual package names may use valid lowercase Kotlin/Java conventions while documentation uses hyphenated product names.

## 8. Bootstrap order for Codex

1. create project with Initializr values
2. configure PostgreSQL and Flyway
3. add Modulith verification test
4. add request/actor/correlation context primitives
5. implement M1 request vertical slice
6. verify first comment/no description and creation audit
7. continue through `docs/14-execution-backlog.md`

Do not ask Codex to scaffold every future module with empty interfaces. Create a module when its first vertical feature is implemented, while preserving the documented boundary.
