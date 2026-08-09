# ADR 0001: Kotlin and Spring Boot for the backend

- Status: Accepted
- Date: 2026-08-10

## Context

The project is both a working self-hosted help desk and a backend portfolio. The author is most familiar with Python, has some TypeScript/React experience, and is targeting roles that use Kotlin/Spring while valuing language-agnostic problem solving.

## Decision

Use Kotlin, Java 21, Spring Boot, Spring MVC, Spring Data JPA, Flyway, and PostgreSQL.

Use Python later for fixture generation, load testing, or analysis where it is the most productive tool; do not split the product backend merely to include Python.

## Alternatives

- Python/FastAPI: fastest initial familiarity, weaker direct practice for the target role.
- Java/Spring: viable, but Kotlin is the target language and gives useful null-safety/value semantics.
- WebFlux/R2DBC: not justified by the initial workload and would mix reactive-stack learning with domain learning.

## Consequences

- The author must learn Kotlin/JPA interoperability deliberately.
- Blocking MVC/JPA is the default until measurements show a different workload.
- Framework choice is not permission to place business rules in controllers or entities generated from tables.
