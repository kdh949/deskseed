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

커밋된 OpenAPI 3.1 문서가 계약의 source of truth다. Scalar WebMVC `0.6.62`는 이 파일을 `/docs/api`에서 렌더링하고, springdoc WebMVC API `3.1.0`은 구현된 Controller의 runtime 문서를 생성해 경로·HTTP method 드리프트를 검사한다. runtime annotation이나 생성 결과가 커밋 계약을 자동으로 덮어쓰면 안 된다.

- 개발 기본값: Scalar와 `/v3/api-docs/**` 활성화, Try it 허용, 인증 정보 저장·telemetry·Scalar agent 비활성화
- production 기본값: 문서 전체 비활성화
- production 명시적 활성화: ADMIN 세션만 허용하고 Try it/client 버튼 비활성화
- renderer 입력: `core-api-outline-v1.yaml`, `customer-identity-api-v1.yaml`, `platform-api-outline-v1.yaml`
- 문서 품질: 작업 목적은 한국어로 직접 작성한다. 구현 요청 schema는 사람이 검토한 도메인별 설명과 합성 전체 예시를 유지하며, 검증 도구는 누락·placeholder·자격 증명 노출·이름/타입 기반 boilerplate만 검사한다. 도구가 설명이나 예시를 생성·덮어쓰지 않는다.

Scalar의 기본 원격 문서나 외부 font에 의존하지 않는다. API 문서에 password, token, Authorization header, session cookie, webhook secret 또는 실제 고객 데이터를 입력하지 않는다.

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
