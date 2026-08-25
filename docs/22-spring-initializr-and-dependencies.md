# Spring Initializr and Dependency Baseline

Status: recommended bootstrap values, not generated code
Checked: 2026-08-18

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

커밋된 OpenAPI 3.1 문서가 계약의 source of truth다. Scalar WebMVC `0.6.63`는 이 파일을 `/docs/api`에서 렌더링하고, springdoc WebMVC API `3.1.0`은 구현된 Controller의 runtime 문서를 생성해 경로·HTTP method 드리프트를 검사한다. runtime annotation이나 생성 결과가 커밋 계약을 자동으로 덮어쓰면 안 된다.

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

## 9. Frontend Node.js support policy

`frontend/package.json`의 `engines.node`가 로컬 개발과 frontend build tooling의 canonical compatibility range다. 현재 지원 범위는 다음과 같다.

```text
^22.22.2 || ^24.15.0 || >=26.0.0
```

이 범위는 임의의 최신 patch 목록이 아니라 설치된 의존성의 `engines` 교집합과 Node.js release lifecycle을 반영한다.

- `jsdom 30.0.1`이 `^22.22.2 || ^24.15.0 || >=26.0.0`을 요구하며, committed 근거는 `frontend/package-lock.json`의 해당 package metadata다.
- `>=22.22.2`처럼 하나의 열린 범위로 합치지 않는다. 그렇게 하면 jsdom이 지원하지 않는 Node 23, Node 24.0.0–24.14.x, Node 25까지 프로젝트가 지원한다고 잘못 선언하게 된다.
- EOL release line은 새 지원 대상으로 추가하지 않는다. Node.js 공식 release policy와 status를 확인하고, frontend 의존성이 허용하더라도 운영·CI 필요성이 없는 EOL major는 제외한다.
- package `engines`는 호환성 범위다. CI의 exact Node version과 container image는 재현성과 보안 patch 기준이므로 별도로 더 최신 버전에 고정할 수 있다. CI pin을 올렸다는 이유만으로 package의 호환성 하한을 함께 올리지 않는다.

범위를 변경할 때는 direct/transitive dependency의 published `engines`, Node.js 공식 release status, CI와 container baseline을 함께 확인한다. 새 최소 지원 버전에서는 `npm_config_engine_strict=true npm ci`와 frontend quality gates를 실행한다.

Sources:

- Node.js release status and production guidance: https://nodejs.org/en/about/previous-releases
- jsdom package metadata: https://www.npmjs.com/package/jsdom
