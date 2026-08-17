# P1 Follow-up Parallel Development Coordination

Status: **IMPLEMENTATION_READY coordination contract**

## Goal

PR #70에서 확인된 두 FROZEN 계약 공백을 Saved View 메타데이터와 인증 고객 첨부라는 독립 vertical slice로 동시에 개발하되, 같은 기준 SHA·계약 명칭·검증 기준을 사용하고 공통 파일 충돌을 통제한다.

## Workstreams

| Workstream | Implementation brief | Execution prompt | Branch |
|---|---|---|---|
| A — Saved View metadata | `tasks/briefs/p1-saved-view-metadata.md` | `tasks/prompts/p1-saved-view-metadata.md` | `feature/p1-saved-view-metadata` |
| B — Authenticated customer attachments | `tasks/briefs/p1-authenticated-customer-attachments.md` | `tasks/prompts/p1-authenticated-customer-attachments.md` | `feature/p1-authenticated-customer-attachments` |

두 workstream은 서로의 구현 branch를 base로 사용하지 않는다. 둘 다 coordinator가 한 번 확정한 동일한 `PARALLEL_BASE_SHA`에서 시작한다.

## Launch baseline

권장 기준은 PR #70과 이 coordination 문서 PR이 모두 `main`에 반영된 commit이다. merge 전 개발을 시작해야 하면 두 작업 모두 `origin/feature/p1-parallel-followup-docs`의 같은 commit을 기준으로 삼을 수 있다.

Coordinator는 시작 전에 다음 값을 한 번만 정하고 두 작업 프롬프트에 같은 값으로 전달한다.

```text
PARALLEL_BASE_SHA=<full 40-character commit SHA>
```

각 작업자는 write 전에 다음을 확인한다.

```bash
git fetch origin
git cat-file -e "${PARALLEL_BASE_SHA}^{commit}"
git merge-base --is-ancestor 814fee56247444a8ac10b1a726703dd60cfa0864 "${PARALLEL_BASE_SHA}"
```

마지막 명령은 P1 프론트 구현 head가 기준 commit에 포함되었는지만 확인한다. `PARALLEL_BASE_SHA`가 없거나 두 작업이 서로 다른 SHA를 사용하면 구현을 시작하지 않는다.

### Known baseline evidence

PR #70 head `814fee56247444a8ac10b1a726703dd60cfa0864`의 GitHub Actions run `32013378207`에는 다음 선행 실패가 있었다.

- Documentation contracts: `docs/26`, `docs/55`, task brief 변경 뒤 `FILE-MANIFEST.txt`와 `VALIDATION-REPORT.md`가 재생성되지 않음.
- Frontend quality gates: `frontend/e2e/agent-views-workspace.spec.ts` Prettier check 실패.
- Backend tests와 Compose health smoke는 성공.

이 문서 PR은 문서 산출물을 재생성한다. 프론트 baseline formatting 문제는 어느 follow-up slice의 기능 범위에도 포함하지 않는다. Coordinator가 PR #70 또는 별도 최소 수정에서 해결하며, 작업자는 자신의 branch에서 우연히 발견한 baseline 실패를 기능 커밋에 섞지 않는다. 최종 merge-ready 주장은 최신 `main` 기준 전체 gate가 통과한 뒤에만 가능하다.

## Frozen cross-workstream decisions

### Saved View metadata

- 기존 operationId를 유지한다: `listAgentViews`, `createAgentSavedView`, `previewAgentSavedView`, `updateAgentSavedView`.
- `description`은 최대 500자의 plain text다. request에서는 호환성을 위해 optional이고 누락 시 빈 문자열이다. backend response는 항상 문자열을 반환하지만 OpenAPI response property는 additive optional로 분류한다.
- `ticketCountAsOf`는 server-generated `date-time | null`이다. `EXACT` count에는 같은 count batch가 사용한 하나의 시각을 반환하고 `OMITTED_VISIBLE_LIMIT`에는 `null`을 반환한다.
- preview의 exact count도 `ticketCountAsOf`를 반환한다.
- `ticketCountAsOf`는 count evaluation metadata이며 DB에 저장하지 않는다.

### Authenticated customer attachments

- 새 operationId는 `createAuthenticatedCustomerAttachmentUpload`, `downloadAuthenticatedCustomerAttachment`로 고정한다.
- path는 `/api/v1/customer/requests/{ticketNumber}/attachments/uploads`와 `/api/v1/customer/requests/{ticketNumber}/attachments/{attachmentId}/download`다.
- POST는 `customerSession`과 customer CSRF header를 요구한다. GET은 `customerSession`을 요구하며 CSRF 대상이 아니다.
- 고객 session download audit의 auth type은 `CUSTOMER_SESSION`이다. request access token 흐름의 `CUSTOMER_CAPABILITY`와 합치지 않는다.
- customer가 접근할 수 있는 attachment visibility는 항상 `PUBLIC` 하나다.
- 기존 `AddAuthenticatedCustomerComment.attachmentIds`, `AttachmentUploadForm`, `AttachmentUpload`, `TicketAttachment` schema와 private attachment pipeline을 재사용한다.

위 이름이나 의미를 바꾸어야 하는 근거가 발견되면 한 작업자가 독단적으로 바꾸지 않는다. 두 workstream을 모두 멈추고 이 문서를 먼저 수정·검토한다.

## File ownership

### Workstream A exclusive ownership

- `backend/src/main/resources/db/migration/V34__saved_view_description.sql`
- `backend/src/main/kotlin/dev/deskseed/ticketing/SavedViews.kt`
- `backend/src/main/kotlin/dev/deskseed/ticketing/internal/JdbcSavedViewStore.kt`
- saved-view 부분의 `StaffTicketReadApi`와 `StaffTicketQueryRepository`
- `SavedViewApplicationService`, saved-view HTTP DTO/translation
- `frontend/src/features/ticket-views/**`
- saved-view 부분의 `frontend/src/api/types.ts`, `frontend/src/api/client.ts`, 관련 tests/fixtures

V34는 Workstream A가 독점한다.

### Workstream B exclusive ownership

- customer session principal/fingerprint의 root API와 `customerauth/internal` 구현
- `CustomerTicketPortal`의 own-ticket locator와 해당 persistence implementation
- `CustomerRequestPortalApplicationService`, `CustomerRequestPortalController`
- attachment download audit의 `CUSTOMER_SESSION` validation
- `frontend/src/features/customer-portal/**`
- 필요한 `frontend/src/features/customer-requests/**` story/test 조정
- customer portal Playwright 및 backend attachment/customer portal integration tests

Workstream B는 migration이 필요하지 않은 것으로 설계되었다. DB 변경이 필요하다는 근거가 생기면 임의로 V34/V35를 만들지 말고 coordinator에게 번호를 할당받는다.

### Shared integration files

다음 파일은 두 workstream이 각자 자기 section만 변경할 수 있다.

- `api/core-api-outline-v1.yaml`
- `docs/26-requirement-traceability.md`
- `docs/55-frontend-capability-recomposition-matrix.md`
- `VALIDATION-REPORT.md`
- `FILE-MANIFEST.txt`

각 workstream PR은 자신의 계약 변경을 포함한다. 먼저 병합되는 PR은 일반 절차로 merge한다. 두 번째 PR 담당자는 최신 `main`을 branch에 merge하고 shared file을 의미 기준으로 통합한 뒤 모든 contract/documentation gate를 다시 실행한다. 이미 push되어 공동 기준으로 쓰인 branch를 rebase/force-push하지 않는다.

## Dependency graph

```text
PARALLEL_BASE_SHA
├── Workstream A: contract → V34/domain/query → HTTP → frontend → E2E
└── Workstream B: contract → session/audit + own-ticket auth → HTTP → frontend → E2E

first merged workstream
└── second workstream merges latest main → resolves shared docs/OpenAPI → full gates
```

두 workstream 사이에는 runtime dependency가 없다. A의 migration이 B의 endpoint에 필요하지 않고, B의 audit auth type이 A의 count/view 모델에 필요하지 않다.

## Commit and PR protocol

- 각 workstream은 구현 brief의 commit sequence를 따른다.
- explicit path만 stage한다. 다른 worktree나 baseline 변경을 포함하지 않는다.
- 두 feature PR은 병렬 리뷰를 위해 처음에는 공통 docs branch를 base로 열 수 있다. 공통 docs와 PR #70이 main에 포함되면 base를 `main`으로 바꾼다.
- PR body에 `PARALLEL_BASE_SHA`, sibling PR URL, shared file 목록, merge order와 gate 결과를 적는다.
- 자동 merge하지 않는다.
- 먼저 병합할 workstream은 review/CI readiness로 정한다. 기능 우선순위 때문에 다른 workstream의 미완성 코드를 cherry-pick하지 않는다.

## Global integration checkpoint

두 PR이 각각 green이어도 두 번째 PR merge 전 최신 main 통합 상태에서 다음을 모두 실행한다.

```bash
cd backend && ./gradlew test
cd frontend && npm run typecheck
cd frontend && npm run lint
cd frontend && npm run format:check
cd frontend && npm run test
cd frontend && npm run test:storybook
cd frontend && npm run check:design-system-boundaries
cd frontend && npm run build
cd frontend && npm run test:e2e
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_api_documentation_quality.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_documentation.py --write
bash scripts/run-p1-contract-e2e.sh
```

Storybook MCP `run-story-tests`와 관련 real-stack Playwright도 각 frontend workstream에서 별도로 통과해야 한다. 실행할 수 없는 검증은 사유와 미검증 범위를 PR에 적는다.

## Non-goals

- tags, custom fields, saved-view arbitrary sort/grouping 확장
- 전체 검색 결과 bulk 선택
- attachment delete/redaction/rich text
- request-token attachment endpoint 변경 또는 통합
- 새로운 object storage/scanner 구현
- P0/PR #70 화면 재설계
- baseline CI 실패를 feature 구현에 섞어 수정
