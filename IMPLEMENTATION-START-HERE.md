# IMPLEMENTATION START HERE — v0.6

## 1. 현재 문서가 보장하는 범위

지금까지 확정한 다음 요구사항은 `docs/26-requirement-traceability.md`에서 문서·단계·검증 기준까지 연결되어 있다.

- 익명 웹 문의와 고객 공개 조회.
- 첫 문의 본문 = 첫 PUBLIC comment.
- 상담사 Workspace, 공개 답변, 내부 메모.
- 상태/우선순위/그룹/담당자, 이관, 자식 티켓.
- field-aware concurrency와 충돌 배너.
- ticket change audit, access/search audit, Audit Explorer.
- self-hosted 관리자와 설정.
- Platform API, SDK, external references, webhook/export.
- Views/tags/custom fields/macros/search.
- SLA/OLA, trigger/automation, Explore-like analytics.
- attachments/rich text/redaction/email/channel adapters.
- Agent App/Embed SDK와 Kafka/search-store 진화 경로.

Core MVP는 `IMPLEMENTATION_READY`다. 미래 기능은 상세 spec이 있는 `BLUEPRINT_READY`이며, 구현 직전 첫 vertical slice의 OpenAPI, migration, UI acceptance를 동결한다.

## 2. 개발 시작 순서

```text
1. 저장소에 이 문서 seed 복사
2. Spring Initializr/React repository bootstrap
3. `make docs-check`를 CI에 추가
4. tasks/00 실행
5. tasks/06 → 12 순서로 Core MVP
6. tasks/01 → 02로 보안 감사 release gate
7. tasks/03 → 05로 Integration v1
8. tasks/15 → 19로 post-MVP depth
```

자세한 절차는 `docs/50-codex-implementation-runbook.md`를 따른다.

## 3. 첫 Codex 입력

Codex에게 “전체 Zendesk 클론을 구현하라”고 요청하지 않는다.

첫 작업은 `tasks/00-bootstrap-documentation-and-repository.md`다. 그 다음 `tasks/06-core-mvp-customer-request.md`를 한 vertical slice로 구현한다.

각 요청에는 다음을 제공한다.

- `AGENTS.md`
- 해당 task 문서
- 관련 `REQ-*`
- 관련 ADR/Decision ID
- 관련 OpenAPI operation
- verification gates
- 명확한 non-goals

## 4. 첫 포트폴리오 완료 기준

```text
고객이 문의를 제출하고 공개 답변을 조회
→ 상담사가 Views에서 티켓을 열고 처리
→ 공개 답변/내부 메모/필드 변경을 한 번에 저장
→ 이관 또는 내부 child ticket 협업
→ 고객에게 internal/child data가 노출되지 않음
→ 변경·열람·검색 이력을 Audit Explorer에서 조사
→ Docker Compose 설치/backup/restore 시연
```

이 단계가 끝나기 전 Kafka, Elasticsearch, Kubernetes, WebFlux를 넣지 않는다.

## 5. Frontend 기준

`docs/28~31`, `40`, `42`, `51`을 함께 읽는다.

- Garden primitives 사용.
- Deskseed 고유 브랜드.
- Zendesk-like layout/workflow.
- desktop 3-panel ticket workspace.
- public/internal separate draft.
- loading/empty/error/denied/conflict states.
- keyboard, focus, WCAG, visual regression.

## 6. 문서와 코드의 우선순위

충돌 시 우선순위:

```text
Accepted ADR / Decision register
→ Requirement traceability
→ PRD/domain/authorization/command spec
→ committed OpenAPI/Flyway
→ implementation
→ screenshots/mockups
```

코드가 승인된 결정을 바꾸면 먼저 ADR과 계약을 수정한다.
## Accepted extension train after the core portfolio gate

```text
Task 20 Mailpit outbound-email foundation
Task 21 Customer magic-link + My Requests
Task 22 Private Platform API v1
Task 23 Encrypted raw search-query storage/reveal
Task 24 Business schedule admin
Task 25 First Reply SLA
```

Read `docs/53-accepted-decisions-2026-08-10.md` before these tasks.
