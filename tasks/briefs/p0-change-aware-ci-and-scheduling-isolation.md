# P0 변경 범위 기반 CI와 scheduling 테스트 격리

## Goal

PR의 누적 변경 범위에 필요한 검증만 실행하되, 미분류·판별 실패·`main` push는 전체 release gate로 fail closed하고 일반 backend 테스트에서는 background scheduler를 실행하지 않는다.

## Decision and source references

- Decision IDs: D-001, D-002, D-008, D-039, D-055. 변경 없음.
- Accepted ADRs: ADR 0002, 0008, 0028, 0040.
- Requirements: REQ-FND-001, REQ-FND-003, REQ-CHAN-003.
- API contract operation IDs: 없음. HTTP 계약을 변경하지 않는다.
- Verification gates: ARCH-001, ARCH-002, ARCH-003, AUT-009, CHN-006.

## Actor and source

- CI 판별·검증 actor: `SYSTEM`.
- scheduled worker actor/source 의미: 기존 `SYSTEM`/`SYSTEM_JOB` 의미를 유지한다.
- role, scope, resource constraint, request/correlation 계약을 변경하지 않는다.

## In scope

- 저장소 소유 변경 범위 판별기와 단위 테스트.
- 항상 시작되는 선택형 GitHub Actions job과 최종 `CI gate`.
- npm/Gradle dependency cache와 PR backend fail-fast.
- `deskseed.scheduling.enabled` master switch 및 test 기본 비활성화.
- 운영 문서와 검증 근거.

## Out of scope

- 테스트 삭제·assertion 완화·병렬 fork 증가.
- 제품 worker의 cron, fixed delay, transaction, audit, retry 의미 변경.
- API, migration, frontend UI/Storybook 파일 변경.
- branch protection/ruleset 직접 변경과 자동 merge.

## Invariants and failure semantics

- PR은 base SHA부터 head SHA까지 누적 diff를 사용한다.
- unknown path, 빈 diff, invalid SHA, git diff 오류는 모든 gate를 실행한다.
- E2E 선택은 frontend quality를 반드시 포함한다.
- 선택된 job의 failure/cancelled와 changes job 실패는 `CI gate` 실패다.
- property가 없거나 `true`이면 제품 scheduling이 활성화되고 `false`이면 infrastructure가 등록되지 않는다.
- module-specific worker property는 그대로 worker bean 활성화를 제어한다.

## Data and privacy

- business/PII 데이터를 읽거나 쓰지 않는다.
- CI summary에는 변경 파일 경로만 제어문자 이스케이프 후 기록하고 파일 내용·secret은 기록하지 않는다.
- migration, retention, export, webhook 노출 변화 없음.

## Acceptance scenarios

- Given 순수 Markdown diff, when 분류하면, then Documentation contracts와 CI gate만 실행 대상이다.
- Given API diff, then documentation과 backend가 실행 대상이다.
- Given migration, then backend와 Compose가 실행 대상이다.
- Given E2E diff, then frontend quality와 browser E2E가 함께 실행 대상이다.
- Given unknown path 또는 diff 실패, then 모든 출력이 true다.
- Given main push, then 모든 release gate가 실행 대상이다.
- Given 일반 backend integration test, then scheduled worker는 background에서 실행되지 않는다.
- Given property 미지정/true/false, then scheduling infrastructure는 각각 활성/활성/비활성이다.

## Validation

```bash
python -m unittest scripts.ci.test_classify_changes
cd backend && ./gradlew --no-daemon test --tests '*Scheduling*' --tests '*Configuration*'
cd backend && ./gradlew --no-daemon test
make docs-check
git diff --check
```

GitHub-hosted runner 자체는 로컬에서 완전히 재현하지 못하므로 workflow YAML 정적 파싱과 판별기 테스트를 별도 근거로 남긴다.

## Compatibility and rollback

- OpenAPI/migration/backfill 없음.
- 기존 job 표시 이름과 `./gradlew test` 의미를 유지한다.
- rollback은 classifier, workflow 조건, 중앙 scheduling configuration과 test property를 함께 되돌린다.

## Human explanation

- 변경 분류를 저장소 코드와 단위 테스트로 소유해 third-party action 의존성을 추가하지 않는다.
- unknown/error는 비용보다 검증 누락 위험을 우선해 전체 실행한다.
- test scheduling은 긴 initial delay로 증상을 늦추지 않고 infrastructure 자체를 비활성화한다.
