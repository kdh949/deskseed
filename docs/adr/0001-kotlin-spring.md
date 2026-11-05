# ADR 0001: 백엔드에 Kotlin과 Spring Boot 사용

- Status: Accepted
- Date: 2026-08-10

## Context

실제로 설치해 사용할 고객지원 시스템을 만들면서 백엔드 개발 경험을 쌓는 프로젝트입니다. 결정 당시 가장 익숙한 언어는 Python이었고 TypeScript/React 경험도 있었습니다. Kotlin/Spring을 사용하는 직무를 목표로, 해당 기술로 업무 문제를 해결하는 경험을 쌓기 위해 선택했습니다.

## Decision

Kotlin, Java 21, Spring Boot, Spring MVC, Spring Data JPA, Flyway와 PostgreSQL을 사용합니다.

Python은 테스트 데이터 생성, 부하 테스트와 분석처럼 작업에 잘 맞는 곳에서 사용합니다. 업무 백엔드는 하나의 Kotlin 애플리케이션으로 유지합니다.

## Alternatives

- Python/FastAPI: 익숙해 빠르게 시작할 수 있지만 목표 직무의 Kotlin/Spring 경험을 쌓기에는 부족합니다.
- Java/Spring: 사용할 수 있는 대안입니다. 이번에는 Kotlin 학습과 null safety·값 표현을 활용하기 위해 Kotlin을 선택했습니다.
- WebFlux/R2DBC: 초기 부하에서 필요성이 확인되지 않았고, 업무 규칙과 reactive stack을 동시에 학습해야 합니다.

## Consequences

- Kotlin과 JPA를 함께 사용할 때의 제약을 학습하고 적용합니다.
- 기본은 blocking MVC/JPA이며, 측정 결과에서 다른 방식이 필요해지면 재검토합니다.
- 업무 규칙은 도메인과 application service에서 관리하고 controller는 HTTP 처리를 맡습니다. DB 테이블에서 생성한 entity에 업무 구조를 그대로 맞추지 않습니다.
