# P1 Saved View Description and Count Basis

Status: **IMPLEMENTATION_READY**

Parallel coordination: `tasks/briefs/p1-parallel-followup-coordination.md`

## Goal

상담사가 PERSONAL/SHARED Saved View의 설명을 서버에 저장하고 version conflict로부터 초안을 보호하며, Queue/View 목록과 preview에서 서버가 계산한 티켓 건수의 기준 시각을 확인한다.

## Assumptions fixed by this brief

1. `description`은 검색 조건이 아닌 사람이 읽는 설정 metadata다.
   이는 Saved View의 설명이며 금지된 `Ticket.description` field를 추가하지 않는다.
2. `description`은 plain text 최대 500자이며 HTML/rich text를 지원하지 않는다.
3. request에서 누락된 description은 빈 문자열로 해석하여 기존 client와 SYSTEM seed를 유지한다.
4. `ticketCountAsOf`는 같은 count batch에서 상대시간/SLA 조건을 평가할 때 사용한 server `Instant`다. 브라우저가 생성하거나 보정하지 않는다.
5. strict MVCC historical snapshot API를 새로 제공하는 것은 범위 밖이다. View 실행 row가 계속 authoritative하다.

## Decision and source references

- Decision IDs: D-005, D-008, D-018, D-033, D-036, D-041, D-053, D-054
- Accepted ADRs: 0005, 0008, 0018, 0025, 0039
- Requirements: REQ-VIEW-001; REQ-CFG-001은 tags 제외로 계속 미완료
- Screen/route: AGT-003, `/agent/views/:viewKey`
- OpenAPI operationIds: `listAgentViews`, `createAgentSavedView`, `previewAgentSavedView`, `updateAgentSavedView`
- Gates: ARCH-001/002/004, ACC-002, AUD-003, CONC-001, PERF-001, frontend Storybook/axe/keyboard gates

## Actor and source

- Actor: active `STAFF`
- Source: `AGENT_UI`
- PERSONAL create/update: owner only
- SHARED create/update: `ADMIN` plus `saved-view:shared:manage`
- SYSTEM: read-only; seeded empty description을 반환할 수 있으나 mutation은 계속 거부
- Read authorization: current `ALL_TICKETS` server predicate; count와 row/preview가 같은 predicate와 compiler 사용
- Audit: create/update Admin/Security audit, preview/execute `VIEW_EXECUTED` AccessAudit

## Frozen API contract

기존 endpoint나 operationId를 추가하지 않는다.

### Request schemas

`SavedViewDefinition`, `CreateSavedView`, `UpdateSavedView`에 다음 optional property를 추가한다.

```yaml
description:
  type: string
  maxLength: 500
  description: 상담사가 View의 목적과 사용 범위를 기록하는 plain-text 설명입니다. 검색 조건으로 해석하지 않으며 제어문자를 허용하지 않습니다.
```

요청에서 property가 없으면 `""`로 처리한다. `UpdateSavedView.expectedVersion` 의미는 바뀌지 않는다. description만 바뀌어도 definition mutation이므로 version을 증가시킨다.

### Response schemas

`SavedView`에 additive optional properties를 추가한다. backend는 두 property를 항상 직렬화한다.

```yaml
description:
  type: string
  maxLength: 500
ticketCountAsOf:
  type:
  - string
  - 'null'
  format: date-time
```

계약 invariant:

| `ticketCountState` | `ticketCount` | `ticketCountAsOf` |
|---|---:|---|
| `EXACT` | non-null integer | non-null server timestamp |
| `OMITTED_VISIBLE_LIMIT` | `null` | `null` |

Create/update response는 count를 실행하지 않으므로 기존 `OMITTED_VISIBLE_LIMIT`, `ticketCount=null`과 함께 `ticketCountAsOf=null`을 반환한다.

`SavedViewPreview`에는 additive optional `ticketCountAsOf: date-time`을 추가하고 backend는 항상 반환한다. Preview sample row 시각이 아니라 preview exact count evaluation basis다.

### Compatibility

- optional request/response property 추가이므로 additive다.
- 기존 저장 row와 client는 description 누락을 빈 문자열로 처리한다.
- enum, path, security, cursor, sort 계약은 바뀌지 않는다.
- YAML description/example은 사람이 실제 흐름을 확인해 작성하고 `x-deskseed-documentation-review: MANUAL`을 유지한다.

## Persistence and migration

Workstream A가 V34를 독점한다.

```text
backend/src/main/resources/db/migration/V34__saved_view_description.sql
```

필수 shape:

```sql
alter table saved_ticket_views
    add column description varchar(500) not null default '';

alter table saved_ticket_views
    add constraint saved_ticket_views_description_bounded
    check (length(description) <= 500 and description !~ '[[:cntrl:]]');
```

- 기존 row는 default `''`로 backfill된다.
- migration은 additive이며 V1–V33을 수정하지 않는다.
- application rollback 시 새 column이 남아 있어도 이전 application이 사용하지 않으므로 rolling-compatible하다.
- down migration을 제공하지 않는다. 필요하면 application rollback 후 forward repair를 사용한다.
- `ticketCountAsOf` column은 만들지 않는다.

## Backend design

### Domain model and validation

`SavedViewDefinition`에 `description: String = ""`를 추가한다.

- name과 별도로 trim 정책을 명시한다. 저장 값은 앞뒤 whitespace를 trim한다.
- 길이 0–500, ISO control character 없음.
- `canonicalDefinition`/`conditionFingerprint`에는 description 원문을 넣지 않는다. 설명 변경은 `definitionVersion`으로 감사 가능하며 audit metadata에 사람이 쓴 설명을 복제하지 않는다.
- 조건/column/sort validation은 그대로 유지한다.

### Store

`JdbcSavedViewStore`의 select/insert/update mapping에 description을 추가한다.

- create/update SQL은 description을 명시적으로 bind한다.
- update predicate `definition_version = :expectedVersion`은 그대로 유지한다.
- description-only concurrent update도 하나만 성공하고 다른 요청은 409다.
- seed SYSTEM View는 migration default 빈 문자열을 사용한다.

### Count batch

현재 `countSavedViews(actorId, views): Map<UUID, Long>`을 다음과 같은 named result로 바꾼다.

```kotlin
data class SavedViewCountBatch(
    val counts: Map<UUID, Long>,
    val asOf: Instant,
)
```

- countable view가 있으면 injected `Clock`에서 `Instant`를 한 번 캡처한다.
- 같은 `asOf`를 모든 relative-time/SLA condition parameter에 사용하고 one-round-trip `UNION ALL`을 유지한다.
- empty input은 호출자가 count를 표시하지 않으므로 timestamp를 만들지 않는 nullable/empty result shape를 명확히 정한다.
- service는 정확히 계산된 처음 20개 item에만 같은 `asOf`를 연결한다.
- preview count도 returned batch의 `asOf`를 response에 연결한다.
- browser에게 elapsed/business time 계산을 위임하지 않는다.

### HTTP translation

- Saved View request DTO의 optional description default는 `""`.
- response DTO는 description과 `ticketCountAsOf`를 포함한다.
- validation error는 기존 RFC 9457 400 경로를 사용한다.
- version mismatch는 current definition/description을 response에 반사하지 않는 기존 409를 유지한다.

## Frontend Reuse Plan

UI write 전에 반드시 Storybook MCP preflight를 실행하고 실제 documentation ID를 기록한다.

- Reuse: `ViewConfigurationDrawer`, 기존 Deskseed form controls, notification/conflict patterns, `ViewNavigation` count 표현
- Compose: 설명 form field + 기존 name/condition/column/sort sections; `<time>` + count text
- Extend: documented multiline input API가 부족할 때만 design-system public API를 호환 확장
- Add: 기본적으로 없음

Garden을 feature에서 직접 import하지 않는다.

## Frontend behavior

### Types and decoder

- `SavedViewDefinition.description: string`
- `SavedAgentView.ticketCountAsOf: string | null`
- `SavedViewPreview.ticketCountAsOf: string`
- decoder는 rolling compatibility를 위해 missing description을 `""`로, missing count timestamp를 `null`로 읽을 수 있다.
- `EXACT`인데 timestamp가 명시적으로 malformed이면 success response를 거부한다.
- create/update/preview payload에 description을 포함한다.

### Editor

- label: `설명`
- 최대 500자, plain text
- 이름/설명/조건/column/sort/scope와 함께 local draft로 유지
- 409 시 사용자가 작성한 description을 포함한 전체 draft를 보존
- reload action은 명시적이며 focus를 conflict notification/action으로 이동하고 drawer close 시 origin focus를 복원
- SYSTEM View에서는 description을 표시하되 editor mutation action은 제공하지 않음

### Count presentation

- `EXACT`: `티켓 24개 · <formatted time> 기준`
- `OMITTED_VISIBLE_LIMIT`: count와 basis를 만들지 않고 기존 unavailable/omitted label 사용
- count state를 색상만으로 표현하지 않음
- `<time dateTime={ticketCountAsOf}>` 사용
- 브라우저는 count, SLA, business-time remaining을 계산하지 않음

### States

- loading: 기존 View list skeleton/state 유지
- empty: PERSONAL/SHARED 없음과 SYSTEM visibility를 구분
- denied: shared mutation server 403
- conflict: draft 보존 + reload/retry
- stale: refetch 전 count basis가 기존 서버 시각임을 유지; 새 local 시각을 붙이지 않음
- error: count/list failure를 0으로 합성하지 않음

## Tasks

### Task A1 — Contract and migration

**Acceptance:** OpenAPI field semantics와 V34 constraint/backfill이 위 계약과 일치한다.

**Verify:** documentation quality, OpenAPI parser/contract tests, `P1AdditiveMigrationTest`.

**Likely files:** Core OpenAPI, V34, migration test, docs/26·55.

### Task A2 — Domain/store/count result

**Acceptance:** description create/update/list persistence와 one-batch exact count basis가 PostgreSQL test로 증명된다.

**Verify:** focused `AgentTicketReadIntegrationTest`, module verification.

**Likely files:** `SavedViews.kt`, `JdbcSavedViewStore.kt`, `StaffTicketReadApi.kt`, `StaffTicketQueryRepository.kt`, integration test.

### Task A3 — Application/HTTP contract

**Acceptance:** request default, response fields, preview basis, expectedVersion conflict가 runtime OpenAPI와 일치한다.

**Verify:** controller/integration/runtime operation drift tests.

### Task A4 — Frontend client/editor/count UI

**Acceptance:** description save/preview/conflict recovery와 exact count basis가 existing pages에 연결된다.

**Verify:** API decoder tests, focused component tests, Storybook interaction/axe.

### Task A5 — Browser and full gates

**Acceptance:** refresh/re-login 후 PERSONAL description 유지, conflict draft 보존, Queue count basis 표시가 real stack에서 동작한다.

**Verify:** relevant Playwright real-stack plus repository gates.

## Acceptance scenarios

1. **PERSONAL persistence** — Given owner가 설명을 저장했을 때, When refresh와 재로그인 후 같은 View를 조회하면, Then 동일한 description과 증가한 definitionVersion을 반환한다.
2. **Concurrent conflict** — Given 두 editor가 version 3을 열었을 때, When 첫 editor가 description을 저장하고 둘째가 저장하면, Then 둘째는 409를 받고 local description draft를 잃지 않는다.
3. **Shared permission** — Given capability 없는 AGENT가 SHARED description을 바꾸면, Then 403이고 DB row/version/audit이 변하지 않는다.
4. **SYSTEM immutable** — Given SYSTEM View가 표시될 때, Then 빈 설명을 안전하게 표시할 수 있지만 mutation action은 없고 직접 API mutation도 거부된다.
5. **Count basis** — Given 3개 exact View가 한 list response에서 계산되면, Then 세 item의 `ticketCountAsOf`가 같고 non-null이며 count query는 한 `UNION ALL` round-trip이다.
6. **Omitted count** — Given 21번째 visible View이면, Then count와 count basis 모두 null이고 UI는 0개로 표시하지 않는다.
7. **Preview** — Given description을 포함한 unsaved draft를 preview하면, Then preview는 저장하지 않고 exact count와 server basis를 반환하며 `VIEW_EXECUTED` audit 의무를 유지한다.
8. **Validation** — Given 501자 또는 control character description이면, Then 400이고 definition/audit이 생성되지 않는다.
9. **Audit privacy** — Given description에 고객 식별 문구가 있어도, Then Admin/Security audit metadata에 description 원문이 복제되지 않는다.

## Validation commands

```bash
cd backend && ./gradlew test --tests '*AgentTicketReadIntegrationTest' --tests '*P1AdditiveMigrationTest'
cd backend && ./gradlew test
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_api_documentation_quality.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_documentation.py --write
cd frontend && npm run typecheck
cd frontend && npm run lint
cd frontend && npm run format:check
cd frontend && npm run test
cd frontend && npm run test:storybook
cd frontend && npm run check:design-system-boundaries
cd frontend && npm run build
cd frontend && npm run test:e2e
bash scripts/run-p1-contract-e2e.sh
```

추가로 Storybook MCP focused/full `run-story-tests`, changed story preview, 관련 real-stack E2E를 실행한다.

## Commit sequence

```text
docs: Saved View 메타데이터 계약 동결

feat: Saved View 설명 영속성과 건수 기준 시각 추가

feat: Saved View 설명과 건수 기준 시각 UI 연결

test: Saved View 메타데이터 계약 검증 추가
```

## Completion report requirements

- changed route/component
- existing operationIds와 추가 schema fields
- V34 migration/backfill/rollback compatibility
- audit event와 원문 비저장 근거
- Storybook stories와 preview URLs
- exact commands와 Passed/Failed/Not run
- sibling attachment PR 및 `PARALLEL_BASE_SHA`
- non-goals/blockers와 P0 회귀 여부
