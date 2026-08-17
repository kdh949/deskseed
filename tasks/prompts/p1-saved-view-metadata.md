# Execution Prompt — P1 Saved View Metadata

아래 프롬프트를 새 Codex 작업에 그대로 전달한다. 시작 전에 `<PARALLEL_BASE_SHA>`를 coordination owner가 확정한 동일한 full SHA로 치환한다.

```text
당신은 Deskseed의 Staff-level 풀스택 엔지니어다.

목표는 Saved View description 영속성과 서버 ticket count 기준 시각을 end-to-end로 구현하는 것이다. 기존 P0/P1 화면을 재설계하거나 다른 blocker를 함께 수정하지 않는다.

PARALLEL_BASE_SHA=<PARALLEL_BASE_SHA>
SIBLING_WORKSTREAM=feature/p1-authenticated-customer-attachments
BRANCH=feature/p1-saved-view-metadata

이 작업은 인증 고객 첨부 작업과 동시에 진행된다. 두 작업은 같은 PARALLEL_BASE_SHA에서 별도 worktree/branch로 시작해야 한다. sibling branch를 merge/cherry-pick하거나 그 worktree를 수정하지 않는다.

필수 절차:

1. 루트 AGENTS.md, frontend/AGENTS.md를 읽고 모두 따른다.
2. tasks/briefs/p1-parallel-followup-coordination.md와 tasks/briefs/p1-saved-view-metadata.md를 정본 implementation brief로 읽는다.
3. CODEX_TASK_TEMPLATE.md, DESIGN_SYSTEM_MANIFEST.md, docs/21, docs/25~34, docs/39, docs/40, docs/47, docs/50, docs/51, docs/55와 Accepted ADR 0005/0008/0018/0025/0039를 읽는다.
4. api/core-api-outline-v1.yaml의 FROZEN 계약을 먼저 수정한다. 이름이나 의미를 brief와 다르게 바꿔야 할 근거가 있으면 구현을 멈추고 blocker를 보고한다.
5. git fetch 후 PARALLEL_BASE_SHA가 존재하고 PR #70 head 814fee56247444a8ac10b1a726703dd60cfa0864를 포함하는지 확인한다. 같은 SHA에서 BRANCH와 전용 worktree를 만든다. primary/다른 worktree의 dirty change를 건드리지 않는다.
6. UI write 전에 frontend/에서 Storybook MCP list-all-documentation을 한 번 호출한다. component/story/rendered UI 변경 전에 get-storybook-story-instructions를 호출한다. 사용하는 각 design-system component documentation ID에 get-documentation을 호출한다. MCP가 없으면 contract를 추측하지 말고 gap을 보고한다.
7. 실패·보안 회귀 테스트를 먼저 또는 구현과 함께 작성한다.

고정 계약:

- 기존 operationId listAgentViews/createAgentSavedView/previewAgentSavedView/updateAgentSavedView를 유지한다.
- SavedViewDefinition/CreateSavedView/UpdateSavedView에 optional description(max 500, plain text, no control characters)을 추가한다. 누락은 빈 문자열이다.
- SavedView response는 description과 nullable ticketCountAsOf를 반환한다.
- EXACT count는 같은 batch에서 하나의 non-null ticketCountAsOf를 공유한다.
- OMITTED_VISIBLE_LIMIT와 create/update response의 count/basis는 null이다.
- SavedViewPreview는 exact ticketCountAsOf를 반환한다.
- V34__saved_view_description.sql은 이 workstream이 독점한다. V1~V33을 수정하지 않는다.
- ticketCountAsOf DB column을 만들지 않는다.
- description 원문을 audit/log metadata에 넣지 않는다.
- tags/custom fields/arbitrary sort/grouping은 구현하지 않는다.

구현 순서:

A. OpenAPI/docs/traceability와 V34 migration/backfill/constraint
B. SavedView domain/store validation과 expectedVersion conflict
C. one-UNION-ALL count batch result + server asOf
D. application/controller DTO와 runtime contract tests
E. frontend types/strict decoders/MSW fixtures/client
F. ViewConfigurationDrawer description draft/preview/conflict recovery
G. AgentViewsPage/ViewNavigation exact count basis presentation
H. Storybook interaction/axe, Playwright, real-stack, full gates

병렬 파일 규칙:

- tasks/briefs/p1-parallel-followup-coordination.md의 Workstream A ownership만 수정한다.
- api/core-api-outline-v1.yaml, docs/26, docs/55, VALIDATION-REPORT.md, FILE-MANIFEST.txt에서는 Saved View section만 수정한다.
- customer portal/customerauth/attachment implementation 파일은 수정하지 않는다.
- baseline frontend e2e Prettier failure를 feature commit에 섞지 않는다. 최신 base에서 이미 고쳐졌는지 확인하고 미해결이면 별도 baseline failure로 보고한다.

커밋은 다음 논리 단위와 한국어 형식을 사용한다.

docs: Saved View 메타데이터 계약 동결
feat: Saved View 설명 영속성과 건수 기준 시각 추가
feat: Saved View 설명과 건수 기준 시각 UI 연결
test: Saved View 메타데이터 계약 검증 추가

explicit path만 stage하고 각 commit 전에 staged diff와 secret/token 문자열을 점검한다. 공개된 shared branch를 rebase/force-push하지 않는다.

검증:

- brief의 focused backend tests
- ./gradlew test
- documentation quality/validation과 generated files clean check
- npm run typecheck
- npm run lint
- npm run format:check
- npm run test
- npm run test:storybook
- npm run check:design-system-boundaries
- npm run build
- npm run test:e2e
- Storybook MCP focused/full run-story-tests, changed stories preview
- relevant real-stack Playwright와 bash scripts/run-p1-contract-e2e.sh

완료되면 branch를 push하고 Draft PR을 연다. 공통 docs branch가 아직 main에 없으면 그 branch를 base로 열고, 포함된 뒤 main으로 retarget한다. 자동 merge하지 않는다. PR body에는 REQ-VIEW-001, gate IDs, PARALLEL_BASE_SHA, sibling branch/PR, operationIds, V34, audit/privacy, tests Passed/Failed/Not run, Storybook preview URLs, non-goals와 P0 회귀 여부를 표로 적는다.

문서/계약에 없는 endpoint, field, mock success를 발명하지 않는다. required gate가 실패하면 원인을 숨기지 말고 merge-ready를 주장하지 않는다.
```
