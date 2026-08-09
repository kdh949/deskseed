# Backend

This Spring Boot backend uses Kotlin, Java 21, PostgreSQL, Flyway, and Spring Modulith. The committed Gradle Wrapper pins Gradle 9.7.0 and verifies the distribution checksum before use.

## Prerequisites

- Java 21
- Docker Engine for the PostgreSQL Testcontainers integration tests

## Commands

```bash
./gradlew test
./gradlew bootRun
```

For local application development, start PostgreSQL from the repository root with `docker compose up -d db`. The backend reads database credentials and CORS origins from environment variables; use `.env.example` as the local-development template and do not commit real secrets.

The `production` profile requires separate runtime (`DATABASE_RUNTIME_*`) and migration (`DATABASE_MIGRATION_*`) database credentials. Set `DATABASE_MIGRATION_URL` only when migrations use a different JDBC endpoint.
