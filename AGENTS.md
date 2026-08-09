# AGENTS.md — AI 개발 에이전트 규칙

이 파일은 저장소 전체에 적용되는 normative instruction이다.

## Required reading

1. `docs/00-product-charter.md`
2. `docs/01-prd-mvp.md`
3. `docs/02-domain-model.md`
4. `docs/03-architecture.md`
5. `docs/07-codebase-rules.md`
6. 작업과 관련된 `docs/18-*` through `25-*`
7. 관련 Accepted ADR
8. `docs/21-minimum-verification-gates.md`

## Non-negotiable domain rules

- 문의 본문은 `Ticket.description`이 아니라 첫 번째 `PUBLIC` comment다.
- `PUBLIC`/`INTERNAL` 분리는 server-side authorization/projection에서 보장한다.
- Transfer는 기존 티켓 소유권을 이동한다.
- Child ticket creation은 parent ownership을 바꾸지 않는다.
- 열린 child는 parent solve를 경고하지만 막지 않는다.
- assignee는 현재 group의 active member여야 한다.
- 고객 API는 internal comment, child relation, staff-only field, audit metadata를 반환하지 않는다.
- current Ticket row가 current state source of truth다; audit은 Event Sourcing store가 아니다.

## Actor and audit rules

- 모든 command와 sensitive read에 actor/source/request/correlation context를 둔다.
- machine call의 actor는 `INTEGRATION_CLIENT`이며 임의 header로 staff를 사칭할 수 없다.
- 한 command가 한 ticket을 바꾸면 하나의 TicketAudit과 ordered events를 만든다.
- ticket/admin mutation과 change audit은 함께 commit/rollback한다.
- staff ticket/customer detail, search, attachment/export/audit access는 정의된 AccessAuditEvent를 남긴다.
- sensitive read의 required audit persistence가 실패하면 success를 반환하지 않는다.
- background polling을 semantic `TICKET_VIEWED`로 기록하지 않는다.
- search query는 redacted value, keyed fingerprint, policy-controlled ciphertext로 다룬다.
- audit view, protected reveal, export도 다시 감사한다.
- change, access/search, admin/security, delivery logs를 하나의 generic JSON table로 합치지 않는다.
- canonical audit rows는 runtime application role로 update/delete할 수 없다.

## Integration rules

- 외부 시스템은 Platform API, webhook, export, SDK를 사용하며 DB에 직접 접근하지 않는다.
- Platform API는 staff/customer controller와 별도 surface다.
- 재시도 가능한 external write는 `Idempotency-Key` 필수다.
- external update는 `If-Match`/expected version semantics를 가진다.
- scope와 resource constraint를 모두 검사한다.
- API key secret는 한 번만 표시하고 retrieval endpoint를 만들지 않는다.
- 외부 data는 `ExternalReference`로 먼저 연결하고 arbitrary mirroring을 하지 않는다.
- external deep link는 HTTPS/host allowlist를 검사하며 backend가 기본 fetch하지 않는다.
- webhook은 HMAC, timestamp, stable event ID, retries, dead letter, replay log를 가진다.
- webhook delivery failure는 committed ticket mutation을 rollback하지 않는다.
- SDK는 committed OpenAPI에서 재현 가능하게 생성한다.
- browser/iframe에는 long-lived integration/provider secret를 전달하지 않는다.

## Architecture rules

- measured evidence and Accepted ADR 없이 Kafka, Redis, Elasticsearch/OpenSearch, WebFlux/R2DBC, Kubernetes, microservices, Event Sourcing, multitenancy를 추가하지 않는다.
- module은 다른 module의 root API/named interface만 import한다.
- `internal` package cross-import를 금지한다.
- Controller는 HTTP translation, Application Service는 transaction, Domain은 invariant, Adapter는 persistence/external I/O를 소유한다.
- JPA entity를 HTTP/module API에 노출하지 않는다.
- 외부 network call을 ticket transaction 안에서 실행하지 않는다.
- PostgreSQL/Flyway가 schema를 소유하고 Hibernate는 validate만 한다.
- generic `common`, `utils`, `helpers`, `shared` dumping ground를 만들지 않는다.

## Security and privacy rules

- password, token/secret, Authorization header, session cookie, webhook secret를 log/audit에 저장하지 않는다.
- comment body/search query를 ordinary application log에 남기지 않는다.
- control characters와 unbounded strings를 audit/log에 그대로 넣지 않는다.
- protected audit content reveal은 별도 permission/reason/self-audit를 가진다.
- external URL and webhook endpoint는 SSRF boundary를 검토한다.
- Security Auditor is read-only unless an explicit separate permission is granted.

## Work procedure

1. `CODEX_TASK_TEMPLATE.md`로 사용자 시나리오와 actor를 정의한다.
2. Decision IDs, ADR, PRD, API operation, verification gate를 적는다.
3. 공개/내부/감사 데이터 경계를 적는다.
4. scope/resource constraint, idempotency, concurrency, failure semantics를 적는다.
5. 실패·보안 회귀 테스트를 먼저 또는 함께 작성한다.
6. external I/O가 있으면 durable intent/outbox/post-commit 경계를 둔다.
7. migration, OpenAPI, docs, audit semantics를 함께 갱신한다.
8. `docs/21-minimum-verification-gates.md`의 해당 gate를 실행한다.

## Completion report

- 무엇을 바꿨는가와 사용자/외부 시스템 시나리오
- relied-on/changed Decision IDs and ADRs
- domain invariants
- actor/source and audit/access/security events
- scopes/resource constraints
- transaction, concurrency, idempotency, retry behavior
- privacy/retention effects
- tests and verification gates run/not run
- migration/rollback/compatibility
- performance evidence
- human owner가 설명할 핵심 trade-off
## v0.5 required reading for every product feature

- `docs/26-requirement-traceability.md`
- `docs/27-implementation-handbook.md`
- `docs/32-database-schema-and-index-blueprint.md`
- `docs/33-authorization-permission-matrix.md`
- `docs/34-state-machines-command-event-catalog.md`
- `docs/39-api-contract-freeze-plan.md`

Frontend changes must also read `docs/28~31`, `docs/40`, and `docs/51`.

Post-MVP changes must read the matching detailed specification under `docs/44~49`, `docs/52`, and `docs/50-codex-implementation-runbook.md`.

## Frontend non-negotiable rules

- Use Deskseed branding. Never add Zendesk logo, wordmark, or copied screenshot/assets.
- Garden components/icons may be used under their license; preserve required notices.
- Match the documented workspace information architecture, not a pixel-for-pixel proprietary clone.
- Every screen implements loading, empty, error, denied, and stale/conflict states as applicable.
- No color-only state. Keyboard and focus behavior are release gates.
- Do not invent API endpoints from UI code. Update/freeze the OpenAPI contract first.

## Requirement traceability

Every implementation PR must list at least one `REQ-*` ID and one verification gate. Update `docs/26-requirement-traceability.md` when status changes.

## Contract and delivery rules

- Core Customer/Agent/Admin/Audit HTTP work must update `api/core-api-outline-v1.yaml` before or with implementation.
- Platform API work must update `api/platform-api-outline-v1.yaml`.
- A `BLUEPRINT_READY` requirement cannot be coded until its first vertical slice satisfies `docs/39-api-contract-freeze-plan.md`.
- One Codex task should implement one vertical slice and use a task brief plus `CODEX_TASK_TEMPLATE.md`.
- Every completion report states what was not implemented and which validations were not run.

## Zendesk-inspired UI rules

- Match task flow and information architecture, not proprietary pixels.
- The desktop ticket workspace follows the documented properties/conversation/context structure.
- PUBLIC and INTERNAL composer drafts are separate and preserved across tab switches.
- Ticket prefetch/background revalidation must not emit semantic `TICKET_VIEWED`.
- Never use Zendesk logos, wordmarks, screenshots, illustrations, or copied CSS/assets in the shipped application.
