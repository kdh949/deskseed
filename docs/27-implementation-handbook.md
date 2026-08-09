# 문서를 실제 코드로 옮기는 구현 핸드북

## 1. 구현 원칙

이 저장소는 “한 번에 Zendesk 전체를 만들어 달라”는 프롬프트를 위한 문서가 아니다. 한 번에 하나의 검증 가능한 vertical slice를 구현하기 위한 계약 모음이다.

각 slice는 다음 순서를 따른다.

```text
Requirement ID
  → PRD/업무 규칙
  → command·state·permission
  → OpenAPI/UI contract
  → Flyway migration
  → domain/application code
  → adapter/API/UI
  → automated tests
  → evidence and docs update
```

코드보다 먼저 계약을 고정하되, 문서가 실제 코드와 어긋나면 같은 PR에서 함께 수정한다.

## 2. 최초 저장소 구성

권장 구조:

```text
deskseed/
├── backend/
│   ├── build.gradle.kts
│   └── src/
├── frontend/
│   ├── package.json
│   └── src/
├── docs/
├── api/
├── db/
├── tasks/
├── docker-compose.yml
├── AGENTS.md
└── README.md
```

Backend 모듈 후보:

```text
dev.deskseed
├── ticketing
├── customer
├── organization
├── staffaccess
├── portal
├── audit
├── integration
├── settings
└── foundation
```

프론트엔드 feature slice 후보:

```text
src/
├── app
├── design-system
├── features/
│   ├── customer-requests
│   ├── ticket-views
│   ├── ticket-workspace
│   ├── admin
│   ├── audit-explorer
│   ├── integrations
│   ├── automation
│   └── analytics
└── shared
```

## 3. 문서 읽기 우선순위

기능 구현 전 최소 읽기 순서:

1. `docs/26-requirement-traceability.md`
2. 해당 기능이 있는 PRD/blueprint
3. `docs/33-authorization-permission-matrix.md`
4. `docs/34-state-machines-command-event-catalog.md`
5. `docs/32-database-schema-and-index-blueprint.md`
6. `docs/39-api-contract-freeze-plan.md`
7. 해당 화면이 있으면 `docs/28~31`, `docs/40`
8. `docs/21-minimum-verification-gates.md`
9. 해당 `tasks/*.md`

충돌 시 우선순위:

```text
승인된 ADR
> requirement traceability의 확정 결정
> PRD/도메인·권한 문서
> API contract
> UI screen spec
> task brief
> 예시 코드
```

## 4. 구현 Release Train

### Release 0 — Repository bootstrap

- Spring Initializr 값 적용
- PostgreSQL/Flyway/Testcontainers
- React/Vite/Garden/Router/Query
- Docker Compose
- CI: backend, frontend, architecture, docs validation
- `ApplicationModules.verify()`
- OpenAPI lint 및 migration test

Exit:

```text
빈 애플리케이션이 로컬에서 뜬다.
health check가 통과한다.
브라우저 shell이 보인다.
CI가 녹색이다.
```

### Release 1 — Anonymous request vertical slice

- Customer
- Ticket
- 첫 PUBLIC Comment
- Ticket Change Audit
- 조회 토큰
- 고객 접수/상세 화면

Exit:

```text
고객이 문의를 제출하고 받은 키로 공개 대화를 조회한다.
DB에 description 컬럼이 없다.
내부 필드는 고객 API에 존재하지 않는다.
```

### Release 2 — Staff identity, groups, agent shell

- ADMIN/AGENT 로그인
- 그룹/멤버십
- Views shell
- 상담사 티켓 목록/상세 read model

### Release 3 — Reply, fields, audit, concurrency

- PUBLIC/INTERNAL composer
- 상태·우선순위·그룹·담당자
- combined save command
- field-aware conflict
- 티켓 audit timeline

### Release 4 — Transfer and child ticket

- 소유권 이관
- 내부 child 생성
- 부모 전체 읽기
- 열린 child 경고

### Release 5 — Admin and customer portal

- 사용자/그룹/설정 관리
- 익명/선택 가입/가입 필수 설정
- 고객 요청 목록/상세
- 계정 연결은 필요 시 다음 minor release

### Release 6 — Security & Audit Gate

- semantic ticket view
- search audit
- global change/access explorer
- protected reveal/export

### Release 7 — Integration v1

- IntegrationClient
- Platform API
- idempotency/ETag
- ExternalReference
- signed webhook
- generated SDK

### Release 8+ — SLA, analytics, automation, search, app/embed, scale

`docs/38-post-mvp-capability-blueprints.md` 순서를 따른다.

## 5. 한 기능의 Definition of Ready

Codex에 넘기기 전에 다음이 모두 있어야 한다.

- Requirement ID
- 사용자와 사용 목적
- 포함·제외 범위
- 정상 흐름과 최소 3개 실패 흐름
- 권한 규칙
- 상태 전이 또는 command 의미
- audit 의무
- API/route/UI 초안
- migration 영향
- acceptance test 목록
- 성능·보안 위험

없다면 Codex에게 구현을 시키기보다 문서를 먼저 보완한다.

## 6. Codex 작업 요청 형식

`CODEX_TASK_TEMPLATE.md`를 사용한다. 한 작업은 다음 크기를 넘기지 않는다.

```text
DB migration 1~3개
Application command 1~2개
API endpoint 1~4개
화면 1개 또는 UI workflow 1개
핵심 E2E 1~3개
```

권장 프롬프트:

```text
AGENTS.md와 아래 문서를 먼저 읽어라.
- docs/...
- tasks/...

이번 작업은 REQ-TKT-013, REQ-AUD-001을 구현한다.
허용 범위: ...
금지 범위: ...
먼저 설계 요약과 변경 파일 계획을 제시하고, 내가 승인한 뒤 구현하라.
완료 시 실행한 테스트와 남은 위험을 보고하라.
```

## 7. PR 단위 작업 절차

1. **Plan**: command, transaction, schema, API, UI, tests를 1페이지로 정리한다.
2. **Adversarial review**: Codex에게 동시성·권한·데이터 누출·실패 복구를 공격적으로 검토시킨다.
3. **Contract first**: OpenAPI/UI fixture와 실패 응답을 먼저 작성한다.
4. **Migration first**: forward-only Flyway migration과 롤백 전략을 기록한다.
5. **Domain**: invariant가 entity/application command에서 강제되는지 확인한다.
6. **Adapters**: HTTP/JPA/UI는 domain 의미를 바꾸지 않는다.
7. **Tests**: unit, integration, contract, browser 순으로 작성한다.
8. **Evidence**: 스크린샷, test output, query plan, API example을 남긴다.
9. **Docs sync**: 구현 상태와 결정 레지스터를 갱신한다.

## 8. 테스트 실행 최소 세트

Backend:

```text
unit tests
domain/application integration tests
Spring Modulith verification
Flyway clean migration test
Testcontainers PostgreSQL tests
OpenAPI contract tests
```

Frontend:

```text
typecheck
unit/component tests
Storybook or isolated component render
Playwright critical flows
axe accessibility checks
visual regression at 1280/1440/1920
```

Cross-system:

```text
customer create/view
agent reply/internal note
conflict handling
transfer/child ticket
audit view/search
platform idempotency/webhook signature
```

## 9. Definition of Done

기능은 다음을 모두 만족해야 완료다.

- 요구사항 acceptance 통과
- 권한 우회 테스트 통과
- audit event 확인
- OpenAPI와 구현 일치
- migration from empty DB 및 previous release DB 통과
- UI loading/empty/error/conflict 상태 구현
- 접근성 및 키보드 경로 통과
- 로그에 토큰·comment body·secret 미노출
- 문서와 decision register 업데이트
- 알려진 제한 기록

## 10. 실패했을 때 되돌리는 방법

- 기능 flag로 새 UI/API 진입을 끌 수 있게 한다.
- DB migration은 destructive change를 한 release 이상 분리한다.
- 배포 전 backup과 restore rehearsal를 수행한다.
- 외부 event schema는 삭제·타입 변경보다 새 version을 만든다.
- background job은 idempotent하고 재실행 가능해야 한다.

## 11. 첫 10개 실제 이슈

1. Repository bootstrap and CI
2. Core IDs, Clock, actor context
3. Customer + anonymous request
4. Ticket + first public comment + audit
5. Customer access token and request detail
6. Staff authentication and bootstrap admin
7. Group membership and authorization skeleton
8. Agent Views list read model
9. Ticket workspace read projection
10. Combined update command and audit

각 이슈는 `tasks/00`, `tasks/06~12`의 acceptance를 따른다.
