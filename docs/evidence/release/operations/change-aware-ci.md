# 변경 범위 기반 CI 운영 규칙

Status: P0 implementation baseline

GitHub Actions `CI` workflow는 모든 pull request와 `main` push에서 시작한다. PR에서는
`scripts/ci/classify_changes.py`가 GitHub event의 명시적인 base SHA와 head SHA를 `base...head`로
비교하고, 필요한 검증 job만 선택한다. 마지막 commit의 파일 목록이나 merge commit의 우연한 diff는
사용하지 않는다.

## 경로별 실행표

| 변경 범위 | Documentation contracts | Backend tests | Frontend quality | Storybook 단계 | Browser E2E | Compose smoke |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 순수 `docs/**`, `tasks/**`, `checklists/**`, Markdown | 실행 | 생략 | 생략 | 생략 | 생략 | 생략 |
| `api/**`, 계약 bundle·검증·문서 생성 script | 실행 | contract category | 생략 | 생략 | 생략 | 생략 |
| `backend/src/main/**`, `backend/src/test/**` | 생략 | 변경 영향 category | 생략 | 생략 | 생략 | 생략 |
| Flyway migration, application boot configuration | 생략 | 실행 | 생략 | 생략 | 생략 | 실행 |
| 일반 `frontend/src/**`, Vite/TypeScript 설정 | 생략 | 생략 | 실행 | 생략 | 생략 | 생략 |
| Story, design system, CSS, rendering asset | 생략 | 생략 | 실행 | 실행 | 생략 | 생략 |
| `frontend/e2e/**`, Playwright 설정 | 생략 | 생략 | 실행 | 생략 | 실행 | 생략 |
| frontend package manifest/lockfile | 생략 | 생략 | 실행 | 실행 | 실행 | 생략 |
| Compose YAML, Dockerfile, Compose smoke/ownership script | 생략 | 생략 | 생략 | 생략 | 생략 | 실행 |
| workflow, `AGENTS.md`, `Makefile`, backend 전역 build 설정, unknown | 실행 | 실행 | 실행 | 실행 | 실행 | 실행 |
| `main` push | 실행 | 실행 | 실행 | 실행 | 실행 | 실행 |

`Frontend quality gates` job의 install, format, lint, design-system boundary, typecheck, unit test, build는
frontend quality가 선택됐을 때 실행된다. Chromium 설치와 Storybook test는 Storybook 분류 또는 전체 실행일
때만 실행된다. Browser E2E 분류는 항상 frontend quality도 선택한다.

## Fail-closed 규칙

- unknown path, 비정상·빈 PR diff, SHA 검증 실패, base/head commit 부재, git diff 실패는 `run_all=true`다.
- rename/copy는 이전 경로와 새 경로를 모두 분류한다.
- `main` push는 변경 경로와 관계없이 `run_all=true`다.
- classifier process 자체가 예상하지 못하게 실패하면 workflow의 fail-closed step이 모든 downstream job을
  선택한다. `changes` job의 실패 결론은 그대로 남아 `CI gate`를 실패시킨다.
- 선택된 job에는 `continue-on-error`나 오류 무시를 사용하지 않는다.

새 경로를 추가할 때는 production/test/build 영향이 가장 좁게 증명되는 기존 범주에 규칙과 단위 테스트를
함께 추가한다. 영향이 모호하면 unknown fallback을 유지한다.

## Cache와 backend 실패 반환

- Backend는 `actions/setup-java`와 `gradle/actions/setup-gradle@v6`의 basic cache provider를 사용한다.
  별도의 Gradle User Home cache를 중복 구성하지 않는다.
- Frontend quality와 browser E2E는 `actions/setup-node`의 npm cache와
  `frontend/package-lock.json` dependency path를 사용한다. `node_modules`는 cache하지 않는다.
- PR Backend tests는 선택된 category를 한 JVM의
  `./gradlew --no-daemon ciSelectedTest -PdeskseedTestTags=... --fail-fast`로 실행한다. 빈 category는
  fail closed한다. `main` push는 전체 실패를 수집하는 `./gradlew --no-daemon test`를 실행한다. 로컬
  `test` task 의미는 바꾸지 않는다. 상세 분류와 lifecycle evidence는
  `docs/evidence/release/operations/backend-test-categories.md`에 기록한다.

## Scheduling test 격리

제품 runtime의 `deskseed.scheduling.enabled` 기본값은 `true`이며 property가 없어도 scheduling
infrastructure가 활성화된다. `backend/src/test/resources/application.properties`는 일반 test context에서
이를 `false`로 설정한다. 실제 registration을 검증하는 focused configuration test만 명시적으로 `true`를
사용한다. `deskseed.mail.scheduling-enabled`, `deskseed.webhook.delivery.scheduling-enabled` 같은
module-specific worker 조건은 master switch 아래에서 계속 적용된다.

## Required check 전환 절차

현재 job 이름은 기존 branch protection 호환성을 위해 유지한다. GitHub에서 `CI gate`가 한 번 관찰된 뒤
repository 관리자가 다음 순서로 전환한다.

1. Settings → Rules → Rulesets에서 default branch에 적용되는 active ruleset을 확인한다.
2. required status checks에 `CI gate`를 추가한다. 같은 이름의 check가 여러 app에서 보이면 GitHub Actions의
   Deskseed `CI` workflow가 생성한 check를 선택한다.
3. 일반 code PR과 docs-only PR을 각각 실행해 선택 job failure/cancelled가 gate를 실패시키고 의도한 skipped
   job은 gate를 통과시키는지 확인한다.
4. 기존 개별 job이 required였다면 전환 검증 동안 유지하고, 두 시나리오가 통과한 뒤 `CI gate`만 required로
   남긴다.
5. ruleset 변경은 repository 관리자 승인과 audit 가능한 운영 변경으로 수행한다. workflow가 ruleset을
   자동 변경하지 않는다.
