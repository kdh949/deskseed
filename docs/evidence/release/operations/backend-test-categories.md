# 백엔드 테스트 분류와 통합 테스트 인프라 evidence

Status: P1 implementation baseline

이 문서는 `REQ-TECH-001`, `REQ-FND-001`, `REQ-FND-003`, `REQ-CHAN-003`과
`ARCH-001`, `ARCH-002`, `ARCH-003`, `CHN-006` 검증을 더 작게 실행하는 방법을 기록한다.
제품 계약, actor/audit 의미, migration 또는 API는 바꾸지 않는다. `./gradlew test`와 `check -> test`는
계속 전체 backend suite를 실행한다.

## 주 분류와 Gradle task

모든 leaf test는 `fast`, `contract`, `integration`, `migration`, `slow` 중 정확히 하나의 JUnit tag를
meta-annotation으로 가진다. 느린 integration test는 `slow`만 가진다. `verifyTestCategories`는 JUnit Platform
discovery로 누락, 복수 분류, 빈 category와 전체 고유 test 합계 불일치를 fail closed한다.

| 주 분류 | 파일 수 | 테스트 수 | 현재 보장 | 같은 환경 실행 시간 |
| --- | ---: | ---: | --- | ---: |
| `fastTest` | 30 | 95 | 순수 domain/policy/state/codec/property 검증 | 9.12s |
| `contractTest` | 7 | 19 | Modulith/architecture, OpenAPI/runtime route, 문서·network/security 계약 | 20.14s |
| `integrationTest` | 29 | 230 | Spring, PostgreSQL, transaction, security filter, audit atomicity, HTTP-to-DB | 70.60s |
| `migrationTest` | 15 | 18 | Flyway, schema constraint, legacy upgrade, fresh database | 26.86s |
| `slowTest` | 8 | 43 | Mailpit, worker/retry, concurrency/lock, WebSocket | 29.02s |
| 합계 | 89 | 405 | 전체 고유 test | category task는 각각 별도 JVM이므로 합산하지 않음 |

시간은 같은 macOS arm64, Java 21, warm dependency/image cache, serial Gradle test worker에서 측정했다.
모든 category와 전체 suite 시간은 최종 context grouping을 반영한 코드에서 측정했다. category task는
각각 독립된 Gradle 실행이므로 전체 suite 시간과 단순 합산하지 않는다.

CI에서 여러 category가 필요할 때는 `ciSelectedTest -PdeskseedTestTags=...`가 한 `Test` process에서 tag를
OR로 실행한다. 빈 값이나 허용되지 않은 값은 `verifySelectedTestCategories`가 실패시킨다. 로컬 category
task는 독립적으로 유지하고 `check` 의존성에는 추가하지 않는다. 모든 `Test` task는 `maxParallelForks=1`을
공유하며 Testcontainers reuse를 사용하지 않는다.

## 변경 경로와 backend category

| PR 변경 | 선택 범위 |
| --- | --- |
| 순수 문서 | backend 없음 |
| `api/**`, OpenAPI/문서 계약 script | `contract` |
| `backend/src/main/**/domain/**` | `fast` |
| 일반 controller/application/repository/security/audit 코드 | `fast,contract,integration` |
| worker/scheduler/outbox materializer | `integration,slow` |
| Flyway migration/schema | `integration,migration` + Compose |
| 분류된 backend test 파일 | 그 파일의 주 category |
| test resource 또는 `testsupport/**` | backend 5개 category 전체 |
| application boot, scheduling 전역 설정 | backend 5개 category 전체 + Compose |
| Gradle/wrapper, workflow, 전역/미분류 | `run_all=true`; 모든 release gate |
| `main` push | `run_all=true`; `./gradlew test` 전체 suite |

PR의 `Backend tests` job은 선택 category를 한 번의 `ciSelectedTest --fail-fast`로 실행한다. `main`은 실패
목록을 모두 남기기 위해 기존 `test`를 `--fail-fast` 없이 실행한다. `backend` aggregate output과 기존 job
표시 이름은 호환을 위해 유지한다.

## Spring context와 PostgreSQL lifecycle

일반 PostgreSQL-backed Spring test는 `DeskseedSpringIntegrationTest`와
`DeskseedPostgresTestConfiguration`을 사용한다. PostgreSQL 17 container는 Spring bean과
`@ServiceConnection`으로 context lifecycle에 귀속되므로 cached context가 살아 있는 동안 먼저 종료되지
않는다. PostgreSQL 18 호환성을 검증하는 두 context, migration/legacy upgrade, 독립 JDBC integration은
기존 fresh container를 유지한다.

같은 의미의 context는 공통 test resource의 scheduling/background-worker 비활성 설정을 공유한다. 반대로
bootstrap seed, 검색 append-only audit/failure trigger, webhook/external-system state처럼 cleanup 의미가 다른
테스트는 `deskseed.test.context-group`으로 명시적으로 분리한다. cache hit를 위해 의미가 다른 설정을 같게
위장하지 않는다.

`StaffTicketTestDatabaseCleaner`는 정확히 동일하던 다섯 staff/ticket 테스트의 mutable actor, customer,
ticket, canonical audit 연결 상태만 정리한다. saved view, ticket configuration, knowledge, webhook 같은 다른
소유 상태는 다루지 않는 목적별 cleaner이며 모든 table을 지우는 generic cleaner가 아니다.

## 전후 측정

| 지표 | 변경 전 | 최종 | 비고 |
| --- | ---: | ---: | --- |
| test 파일 / 테스트 | 88 / 403 | 89 / 405 | 분류 규칙 회귀 test 2개 추가; 제품 test 삭제 없음 |
| Spring test class | 37 | 37 | 일반 class-scoped container를 meta-annotation으로 통합 |
| `@Container` 선언 | 54 | 19 | migration/fresh DB와 Mailpit 소유 container는 유지 |
| PostgreSQL 시작 | 54 | 45 | 일반 순서 전체 suite XML의 `Container is started (JDBC URL:)` |
| context cache hit / miss | 3354 / 37 | 3372 / 28 | max size 8, failure 0 |
| 최소 eviction | 29 | 20 | `miss - maxSize`; 실제 eviction logger count가 아닌 보수적 하한 |
| 전체 suite | 176.35s | 122.78s | 동일 환경 opt-in cache logging, 약 30.4% 감소 |

cache size는 측정 결과가 있어도 8로 유지했다. 의미가 다른 context를 합치거나 heap을 키우는 대신 lifecycle
소유권과 상태군을 정리했다. 최종 일반 순서 405개와 seed `20260818` 무작위 class 순서 405개가 모두
통과했다. 무작위 순서 실행은 `-PdeskseedTestClassOrderSeed=20260818`로 재현할 수 있다. 최종 XML/log에는
종료 후 `Connection refused`, `Connection is not available`, Ryuk 연결 오류가 없다.

## slice 전환과 유지한 보장

이번 inventory에서는 full security filter/session/CSRF, PostgreSQL constraint, transaction/audit atomicity,
PUBLIC/INTERNAL projection, concurrency/lock을 제거하지 않고도 더 작은 slice로 바꿀 후보를 확정하지 못했다.
따라서 quota를 맞추기 위한 `@WebMvcTest`, H2 또는 assertion 축소는 하지 않았다. 기존 순수 Kotlin과
`ApplicationContextRunner` test를 `fast`로 분류했고, PostgreSQL/Flyway/security/audit vertical test는 원래
보장을 유지한 채 `integration`, `migration`, `slow`에 남겼다.

context cache 통계는 평소 로그에 출력하지 않는다. 필요할 때만 다음 명령을 사용한다.

```bash
./gradlew test -PdeskseedTestContextCacheStats=true
```
