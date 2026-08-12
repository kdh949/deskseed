# Business Schedule Administration Evidence

Date: 2026-08-12
Requirement: `REQ-SLA-002`
Decisions: `D-034`, `D-044`
ADRs: 0023, 0032
Gates: `SCHED-001`, `SCHED-002`, `SLA-001`, `AUD-003`, `ARCH-001`,
`ARCH-002`, `UI-002`, `UI-004`, `UI-005`, `OPS-005`

## Calculation contract

- Schedule intervals use local half-open ranges `[start, end)` and cannot cross
  midnight. Adjacent boundaries are allowed; overlaps and `start >= end` are
  rejected.
- A disabled weekday has no intervals. An enabled weekday may have zero or up
  to 12 intervals.
- A date exception replaces its weekly rule. `CLOSED` has no intervals and
  `OPEN` has at least one interval.
- Adding zero business minutes returns the input instant. Positive additions
  consume the current effective range first and then resume at the next open.
- Elapsed time is the intersection with effective ranges and truncates only a
  final partial minute. `nextOpen` returns the input when already open.
- The schedule's exact IANA zone is always used; the server default zone has no
  effect.

## DST policy

Policy identifier: `GAP_SHIFT_FORWARD_OVERLAP_INCLUDE_BOTH`.

- A nonexistent boundary in a spring-forward gap moves forward by the zone
  transition duration.
- An ambiguous start boundary uses the earlier offset; an ambiguous end uses
  the later offset. The repeated wall-clock hour is therefore counted twice.
- An interval that collapses after boundary resolution contributes zero time.

## Deterministic fixture table

| Fixture | Input | Expected result |
|---|---|---|
| Seoul Friday to Monday | Fri 2026-08-14 17:00 KST + 120 business minutes | Mon 2026-08-17 10:00 KST |
| Closed weekend | Fri 2026-08-14 18:00 KST `nextOpen` | Mon 2026-08-17 09:00 KST |
| Weekend split hours | Sat 09:00–12:00, 13:00–16:00 | 360 elapsed minutes; 11:30 + 120 = 14:30 |
| Closed holiday | Sat 2026-08-15 `CLOSED` | Weekly Saturday ranges are fully replaced |
| Exceptional Sunday | Sun 2026-08-16 `OPEN` 10:00–12:00 | 120 elapsed minutes and close at 12:00 |
| New York DST gap | Sun 2026-03-08 02:30–04:00 | 30 effective minutes |
| New York DST overlap | Sun 2026-11-01 01:00–02:00 | 120 effective minutes |
| Server-zone independence | Server defaults Honolulu and London | Same Seoul due instant in both runs |

These fixtures are executable in `BusinessTimeCalculatorTest`; PostgreSQL seed
and immutability are covered by `BusinessScheduleMigrationTest`.

## Transaction, authorization, and audit evidence

- New schedule versions require the root aggregate `If-Match`; a stale value
  returns `412` before a version or audit row is written.
- Creating a version never activates it. Explicit activation advances the root
  pointer and appends `business_schedule_activations` plus
  `BUSINESS_SCHEDULE_ACTIVATED` in one transaction.
- `BUSINESS_SCHEDULE_CREATED` and `BUSINESS_SCHEDULE_VERSION_CREATED` use the
  same canonical AdminSecurityAudit ledger. Injected audit persistence failure
  returns `503` and rolls the schedule insert back.
- Server method authorization and the frontend `AdminRoute` restrict the
  surface to active administrators. Direct Agent access returns `403` and is
  audited as `ACCESS_DENIED`.

## Screenshots

- Default seeded editor:
  `frontend/e2e/__screenshots__/darwin/business-schedule-admin-default-1440.png`
- Weekend, holiday, and preview result:
  `frontend/e2e/__screenshots__/darwin/business-schedule-admin-preview-1440.png`

The browser scenario also checks CSRF and `If-Match` headers, preview payload,
version save, activation, a clean console, and axe with no violations.

## Verification results

| Gate/evidence | Result |
|---|---|
| Full backend Gradle suite | 152 passed, 0 failed (`./gradlew test`) |
| Frontend component/unit suite | 155 passed, 0 failed (`npm test`) |
| Frontend type/lint/production build | passed |
| Chromium Playwright suite | 42 passed, 6 environment-gated full-stack cases skipped |
| Schedule Chromium scenario | passed with two reviewed 1440 px visual baselines, clean console, zero axe violations |
| Documentation/OpenAPI and seed validators | passed |
| `docker compose config` and `git diff --check` | passed |

The six skipped Playwright cases require the separate real-stack runner and are
not schedule-specific. PostgreSQL-backed schedule API, authorization, audit
rollback, and migration coverage ran in the backend suite. Firefox/WebKit,
composed-stack browser E2E, and dedicated load/latency benchmarking were not
run. Inputs are bounded to 366 exceptions and 12 intervals per day; next-open
search avoids iteration toward a remote exception date, but no production-scale
performance claim is made by this slice.

## PR draft

Title: `feat: 업무 시간 일정 버전 관리와 미리보기 추가`

Body:

```markdown
## 요약

- REQ-SLA-002 관리자 업무 시간 일정 API와 설정 화면 추가
- Asia/Seoul 평일 09:00–18:00 활성 seed와 불변 버전/활성화 이력 추가
- DST 정책을 고정한 결정론적 add/elapsed/next-open 계산기 추가
- 관리자 권한, CSRF, If-Match 충돌, 감사 원자성, 브라우저 접근성 검증 추가

## 결정과 경계

- D-034, D-044 및 ADR 0023/0032 적용
- 예외 날짜는 주간 규칙을 대체하고 시간 구간은 [start, end)로 계산
- SLA target instance, ticket schedule assignment, trigger/automation은 제외

## 검증

- SCHED-001, SCHED-002, SLA-001, AUD-003
- ARCH-001, ARCH-002, UI-002, UI-004, UI-005, OPS-005
- PostgreSQL 통합 테스트, React 테스트, Chromium axe/visual test

## 배포와 호환성

- V18 additive Flyway migration
- rollback은 애플리케이션 롤백 후 forward-fix migration으로 수행
- 기존 Customer/Agent/Admin/Audit API에는 breaking change 없음
```

This is Stack H PR 1/2 and must remain a draft; automatic merge is disabled.
