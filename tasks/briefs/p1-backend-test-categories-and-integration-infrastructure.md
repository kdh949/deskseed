# P1 backend test categories and integration infrastructure

## Goal

`./gradlew test`의 전체 suite 의미를 유지하면서 목적별 backend test task와 fail-closed category 검증을 추가하고, 일반 Spring integration test의 PostgreSQL lifecycle을 Spring context가 소유하도록 통합한다.

## Decision and source references

- Decision IDs: D-001, D-002, D-008, D-039, D-055. 변경 없음.
- Accepted ADRs: ADR 0002, 0008, 0034, 0040.
- Requirements: REQ-TECH-001, REQ-FND-001, REQ-FND-003, REQ-CHAN-003.
- API contract operation IDs: 없음. HTTP 계약을 변경하지 않는다.
- Verification gates: ARCH-001, ARCH-002, ARCH-003, CHN-006.

## In scope

- `fast`, `contract`, `integration`, `migration`, `slow` primary category와 자동 검증.
- category별 Gradle `Test` task와 단일 JVM `ciSelectedTest`.
- P0 change classifier와 Backend tests workflow의 category 선택.
- 일반 Spring integration context가 소유하는 PostgreSQL container configuration.
- 동일한 staff-access 정리 의미를 가진 fixture의 목적별 공통화.
- context cache/container lifecycle과 동일 환경 실행 시간 evidence.

## Out of scope

- 제품 API, migration, dependency version, worker 동작 변경.
- PostgreSQL을 H2로 대체하거나 Testcontainers reuse 활성화.
- test worker 병렬 실행, `maxParallelForks` 증가.
- assertion 삭제·완화, UI/Storybook 변경, branch protection 변경.

## Invariants and failure semantics

- `test`에는 tag filter를 적용하지 않고 `check -> test` 의미를 유지한다.
- 모든 발견 test는 정확히 하나의 primary category를 가진다.
- category 누락·중복, 빈 category, 빈/잘못된 CI tag 입력은 실패한다.
- migration test의 class-scoped fresh PostgreSQL은 유지한다.
- 일반 integration container는 Spring context보다 먼저 종료되지 않는다.
- scheduling은 일반 test resource의 master switch로 계속 비활성화한다.
- security, audit atomicity, transaction, concurrency assertion을 줄이지 않는다.

## Data, actor, and privacy

- test actor/source와 business fixture 의미는 변경하지 않는다.
- synthetic test data만 사용하며 token, secret, comment body를 새 로그에 출력하지 않는다.
- schema, retention, audit event 계약 변경 없음.

## Validation

```bash
cd backend
./gradlew tasks --all
./gradlew verifyTestCategories
./gradlew fastTest contractTest integrationTest migrationTest slowTest --test-dry-run
./gradlew --no-daemon fastTest
./gradlew --no-daemon contractTest
./gradlew --no-daemon integrationTest
./gradlew --no-daemon migrationTest
./gradlew --no-daemon slowTest
./gradlew --no-daemon test
```

## Compatibility and rollback

- OpenAPI/migration/backfill 없음.
- rollback은 category annotations/tasks, classifier/workflow outputs, Spring-managed container support를 함께 되돌린다.
- 기존 per-class container 선언으로 되돌릴 때 scheduling master switch와 전체 `test` gate는 유지한다.

## Human explanation

- category는 한 test의 주 목적을 나타내며 느린 integration은 중복 tag 대신 `slow` 하나를 사용한다.
- CI에서 여러 category를 선택할 때 한 Test JVM을 사용해 context cache를 공유하되, `main`은 계속 `test` 전체 suite를 실행한다.
- migration의 fresh database와 제품 수준 PostgreSQL/security/audit 보장은 속도보다 우선한다.
