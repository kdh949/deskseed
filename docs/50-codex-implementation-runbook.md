# Codex Implementation Runbook

Status: **Normative delivery process v0.6**

이 문서는 문서 seed를 실제 Kotlin/Spring + React 코드베이스로 옮기는 절차를 정의한다. Codex에게 제품 전체를 한 번에 구현시키지 않고, 검증 가능한 vertical slice를 작은 PR 단위로 반복한다.

## 1. 기본 원칙

```text
문서와 계약 고정
→ 실패/보안 시나리오 정의
→ migration + domain + API + UI 한 조각 구현
→ 자동 검증
→ 사람의 설계 설명과 수동 시연
→ 다음 조각
```

한 작업은 다음 중 하나여야 한다.

- 사용자에게 보이는 하나의 end-to-end 결과.
- 운영자/감사자에게 보이는 하나의 end-to-end 결과.
- 다음 vertical slice를 막는 기반 작업.

“전체 백엔드”, “전체 관리자”, “Zendesk 클론”은 작업 단위가 아니다.

## 2. 저장소 bootstrap

### Backend

Spring Initializr 값은 `docs/22-spring-initializr-and-dependencies.md`를 따른다.

최소 구조:

```text
backend/
  build.gradle.kts
  src/main/kotlin/dev/deskseed/
    DeskseedApplication.kt
    customer/
    ticket/
    staff/
    organization/
    audit/
    integration/
    configuration/
  src/main/resources/
    application.yml
    db/migration/
  src/test/kotlin/
```

초기 module package는 실제 bounded context가 생길 때만 추가한다. `common`, `utils`, `helpers`를 선제적으로 만들지 않는다.

### Frontend

```text
frontend/
  src/
    app/
    routes/
    features/
    entities/
    shared/ui/
    shared/api/
    shared/lib/
    styles/
  tests/
```

- React + TypeScript strict.
- Vite.
- React Router.
- TanStack Query.
- Garden components는 `shared/ui/garden` wrapper 뒤에서 사용한다.
- API type은 committed OpenAPI에서 생성하거나 계약과 1:1로 관리한다.

### Local operations

```text
docker-compose.yml
.env.example
docs/
api/
db/
tasks/
```

최초에는 PostgreSQL과 애플리케이션만 필수다. object storage, mail catcher 등은 해당 slice에서 추가한다.

## 3. 브랜치와 PR 규칙

권장 브랜치:

```text
feat/req-tkt-001-anonymous-request
feat/req-aud-003-ticket-view-audit
fix/req-tkt-014-same-field-conflict
```

PR 설명에 반드시 포함한다.

```text
Requirement IDs
Decision/ADR IDs
Screen IDs or OpenAPI operationIds
Migration files
Audit events
Verification gates
Known non-goals
Human explanation
```

한 PR에서 migration, domain, endpoint, UI, E2E가 함께 필요한 vertical slice라면 함께 포함한다. 단, review 가능한 범위를 넘으면 “contract → backend → frontend” 3개 PR로 나누고 feature flag로 미완성 동작을 숨긴다.

## 4. Definition of Ready

Codex에게 작업을 주기 전 다음이 있어야 한다.

1. 사용자/actor와 목표가 한 문장으로 정의됨.
2. 관련 `REQ-*`가 있음.
3. 상태, 권한, 실패 의미가 정해짐.
4. 저장되는 데이터와 audit event가 정해짐.
5. API operation 또는 UI contract 초안이 있음.
6. 최소 Given/When/Then이 있음.
7. 이번 작업에서 하지 않을 것이 있음.
8. production secret/PII가 무엇인지 적혀 있음.

하나라도 빠지면 Codex에게 코드를 요청하기 전에 문서를 보완한다.

## 5. Definition of Done

기능은 다음이 모두 충족되어야 완료다.

- 정상 시나리오가 browser/API에서 동작.
- 권한 거부와 데이터 비노출 회귀 테스트.
- PostgreSQL migration과 upgrade path.
- 필요한 Ticket/Access/Admin audit 기록.
- OpenAPI와 UI route catalog 갱신.
- loading/empty/error/denied/conflict UI 상태.
- module verification.
- unit/integration/browser tests.
- 실행한 검증 명령과 결과 기록.
- 사람이 설계 선택과 trade-off를 설명할 수 있음.

“코드가 생성됨”, “컴파일됨”만으로 완료하지 않는다.

## 6. 권장 구현 release train

### Train A — Foundation

1. `tasks/00-bootstrap-documentation-and-repository.md`
2. request/correlation/actor context.
3. Flyway + PostgreSQL Testcontainers.
4. Spring Modulith verification.
5. RFC 9457 error envelope.
6. React/Garden shell과 시각 회귀 harness.

Exit:

- backend test, frontend build/typecheck, Compose smoke test.
- empty Agent shell 1280/1440/1920 snapshot.

### Train B — Anonymous request vertical slice

1. Customer profile.
2. Ticket and first PUBLIC Comment.
3. creation TicketAudit.
4. opaque request grant hash.
5. request form.
6. public-only detail.

Exit scenario:

```text
익명 고객이 문의를 제출
→ 번호와 조회 방법을 받음
→ 공개 대화만 조회
→ DB와 audit에서 원자적 생성 확인
```

### Train C — Staff and group

1. bootstrap admin.
2. staff login/session.
3. roles.
4. Group and membership.
5. admin staff/group pages.

### Train D — Agent views and workspace read

1. default saved views.
2. cursor ticket queue.
3. ticket workspace three-panel shell.
4. semantic `TICKET_VIEWED` interaction.
5. customer context and ticket-local history.

### Train E — Ticket command pipeline

1. combined UpdateTicket command.
2. public reply/internal note.
3. status/priority/group/assignee.
4. one command/one audit.
5. field-aware optimistic concurrency.
6. conflict banner and draft recovery.

### Train F — Transfer and child collaboration

1. assignment invariant.
2. transfer command.
3. `PARENT_CHILD` relation.
4. child creation dialog.
5. relationship parent read.
6. open-child solve warning.

### Train G — Admin/customer completion

1. customer access mode.
2. verified customer account evolution.
3. admin settings and audit.
4. portfolio UX polish.

### Train H — Security audit release gate

1. access/search audit.
2. Audit Explorer.
3. protected reveal/export.
4. retention baseline.
5. forensic scenario demo.

### Train I — Integration v1

1. IntegrationClient.
2. Platform API.
3. idempotency and ETag.
4. external references.
5. signed webhook/outbox.
6. generated SDK and examples.

### Train J — Product depth

Implement in dependency order, not parallel speculation.

```text
views/tags/custom fields/macros/search
→ SLA/OLA
→ trigger/automation
→ analytics/export
→ attachments/email
→ app/embed SDK
→ measured Kafka/search-store evolution
```

## 7. 한 vertical slice 구현 절차

### Step 1 — Contract freeze

- Requirement row status를 확인한다.
- `BLUEPRINT_READY`라면 `IMPLEMENTATION_READY` 승격 조건을 채운다.
- OpenAPI operationId와 request/response/problem을 확정한다.
- UI Screen ID 및 상태표를 확정한다.

### Step 2 — Threat and failure review

최소 질문:

```text
권한을 우회할 수 있는가?
INTERNAL 데이터가 customer projection으로 새는가?
같은 요청이 재시도되면 중복되는가?
동시 저장 시 어떤 값이 승리하는가?
audit 저장 실패 시 무엇을 반환하는가?
외부 I/O 실패가 DB transaction을 어떻게 건드리는가?
PII/secret가 log에 남는가?
```

### Step 3 — Migration first

- Flyway migration을 만든다.
- FK/unique/check constraint를 먼저 표현한다.
- index는 실제 query와 함께 추가한다.
- downgrade SQL을 무리하게 제공하기보다 forward-fix와 backup/restore 정책을 적는다.

### Step 4 — Domain/application

- command input.
- invariant.
- transaction boundary.
- audit event.
- domain/integration event intent.
- repository query.

### Step 5 — HTTP/UI

- controller는 번역만 한다.
- generated/contract type을 사용한다.
- UI는 server state와 draft state를 분리한다.
- 모든 주요 상태를 구현한다.

### Step 6 — Verification

- unit truth table.
- PostgreSQL integration.
- authorization/non-leak regression.
- browser E2E.
- accessibility/visual snapshot.
- query plan where relevant.

### Step 7 — Human review

Codex의 completion report를 읽고 다음을 직접 답한다.

```text
왜 이 aggregate/transaction인가?
어떤 invariant를 DB와 코드 중 어디서 지키는가?
어떤 실패를 허용하고 어떤 실패는 fail-closed인가?
왜 지금 더 복잡한 기술이 필요하지 않은가?
어떤 지표가 생기면 다음 기술을 도입하는가?
```

## 8. Codex 프롬프트 템플릿

```text
Repository instructions:
- Read AGENTS.md.
- Read docs/26, 27, 32, 33, 34, 35, 39 and the relevant ADR/task.

Task:
- Implement REQ-TKT-001, REQ-TKT-002, REQ-TKT-006, REQ-TKT-008.
- Follow tasks/06-core-mvp-customer-request.md.

Required outputs:
1. Flyway migrations.
2. Kotlin domain/application/adapter/API implementation.
3. React request form and public request detail.
4. OpenAPI update.
5. Ticket creation audit.
6. Unit, PostgreSQL integration, and browser E2E tests.
7. Completion report using CODEX_TASK_TEMPLATE.md.

Non-goals:
- Customer login.
- Attachments.
- Email.
- Kafka/Redis/Elasticsearch/WebFlux.

Do not invent missing product decisions. Stop and report a blocking ambiguity with the exact document section.
```

## 9. Codex가 제안한 코드를 검토하는 체크리스트

- entity가 controller response로 직접 노출되지 않는가?
- service가 다른 module repository를 직접 만지지 않는가?
- transaction 안에서 HTTP/mail/webhook을 호출하지 않는가?
- visibility/permission이 UI만이 아니라 query/API에서 보장되는가?
- audit actor/source/correlation이 빠지지 않았는가?
- time이 `Instant`/injected Clock인가?
- pagination이 안정적인가?
- API error가 Problem Details인가?
- retry가 중복 command를 만들지 않는가?
- test가 H2가 아니라 PostgreSQL 의미를 검증하는가?
- 화면이 keyboard/focus/empty/error/conflict 상태를 갖는가?

## 10. 학습 기록

각 release마다 `docs/portfolio/`에 다음을 남긴다.

```text
problem.md
architecture.md
threat-model.md
query-plan-before-after.md
failure-injection.md
ai-decisions.md
release-demo.md
```

`ai-decisions.md`에는 다음을 기록한다.

- AI가 제안한 선택.
- 채택/거절한 이유.
- 검증한 근거.
- 결과와 다음 개선.

이 기록이 코드 생성량보다 포트폴리오 설명력에 더 직접적으로 기여한다.
