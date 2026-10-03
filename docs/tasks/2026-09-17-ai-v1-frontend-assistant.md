# AI V1 상담사 어시스턴트 UI

## Goal

상담사가 기존 티켓 workspace에서 PUBLIC 대화 요약·분류 제안·공개 KB 기반 답변 초안을 확인하고, 최신성을 재검증한 답변만 명시적으로 PUBLIC 작성기에 사용할 수 있다.

## Decision and source references

- Decision IDs: D-066의 Backend-owned authorization/PUBLIC projection 경계를 그대로 사용한다.
- Accepted ADRs: ADR 0025, ADR 0049.
- PRD/domain sections: `docs/28~31`, `docs/40`, `docs/51`, AI design v1.2와 `docs/ai-v1/FRONTEND_HANDOFF.md`.
- API contract operation IDs: `createAgentAiJob`, `listAgentAiJobs`, `getAgentAiJob`, `cancelAgentAiJob`, `recordAgentAiFeedback`.
- Verification gate IDs: AI-API-001, AI-SRC-001, AI-TRIAGE-001, AI-REPLY-001, UI-002~006.

## Actor and source

- Actor type: STAFF.
- Source: AGENT_WORKSPACE.
- Required role/scopes: 서버가 current active staff, ticket read capability, feature allowlist를 매 호출 재검증한다.
- Resource constraints: numeric `ticketNumber`와 서버 소유 job/requester/ticket/feature binding만 사용한다.
- Interaction/request/correlation semantics: mutation은 staff session/CSRF/expected actor를 유지하고 create/feedback마다 새 `Idempotency-Key`를 보낸다. polling은 semantic `TICKET_VIEWED`를 만들지 않는다.

## Product and UX contract

- Requirement IDs: REQ-AI-001, REQ-AI-003~005, REQ-UI-001/003/005/006.
- Screen IDs / route IDs: 기존 AGT-004 `/agent/tickets/:ticketNumber` context panel.
- OpenAPI operationIds: 위 AI job/feedback 다섯 operation.
- Zendesk parity pattern from docs/51: ticket workspace의 properties/conversation/context IA만 따르며 proprietary pixel/asset은 복제하지 않는다.
- States: recent-job loading/empty, denied, request error/retry, polling/cancel, needs-review, failed/budget, cancelled, superseded, expired, stale result.
- Keyboard/focus/accessibility: 모든 동작은 native button/anchor로 접근하고, PUBLIC-only 범위와 상태를 텍스트로 표시하며, 삽입 결과는 polite live region으로 알린다.
- Visual regression fixtures and widths: 360px isolated panel stories와 existing workspace context drawer/desktop composition.

## In scope

- Figma의 우측 도움 패널 계층을 Deskseed token과 기존 context panel에 재구성한다.
- summary/triage/reply job 생성·최근 작업 복원·metadata polling·terminal result 조회·취소·feedback을 Core API에 연결한다.
- summary/triage는 표시만 하고 ticket field를 자동 변경하지 않는다.
- reply 삽입 직전에 `includeResult=true`로 재검증하고 PUBLIC mode 및 draft 경합을 확인한다.
- 빈 PUBLIC draft는 명시적 사용 동작으로 삽입하고, 기존 draft는 추가/교체/취소를 요구한다.
- component story, interaction/a11y, API decoder/transport unit test와 문서 evidence를 추가한다.

## Out of scope

- AI 채팅, 자율 workflow, 그룹/담당자/priority/tag 자동 적용, INTERNAL/customer profile/related ticket 입력, 독립 지식 검색 제품.
- 자동 발송, 실제 모델/Cloud 호출, 배포, provider 품질 및 사람 평가.
- server/OpenAPI schema 변경과 database migration.

## Invariants and failure semantics

- domain invariants: AI 입력은 서버의 PUBLIC-only projection이며 화면 필터를 보안 경계로 사용하지 않는다.
- transaction boundary: 화면은 기존 API를 소비하며 서버 transaction/audit 경계를 바꾸지 않는다.
- audit obligation: result body 조회는 서버의 required AI_RESULT_READ audit 성공 후에만 표시된다.
- audit failure behavior: API 실패로 처리하고 body나 삽입 기능을 제공하지 않는다.
- concurrency: 생성 시 현재 ticket version을 보내고, 삽입 검증 중 ticket/version/mode/draft가 바뀌면 중단한다.
- idempotency/retry: create/feedback은 UUID key를 사용하며 mutation을 자동 재시도하지 않는다. 동일 job의 삽입은 browser session에서 한 번만 허용한다.
- external I/O boundary: browser는 committed Core API 외 provider/KB URL을 직접 호출하지 않는다. citation은 계약된 상대 help URL만 렌더링한다.

## Data and privacy

- data read/written: metadata polling, typed AI result, feedback enum; PUBLIC 작성기 local/draft state.
- PII/secrets: INTERNAL 메모·고객 profile·edited reply body를 AI/feedback payload로 보내지 않는다.
- retention category: 서버의 result 7일, metadata/feedback 30일 정책을 소비하며 브라우저 별도 영속 저장은 추가하지 않는다.
- redaction/encryption: 화면은 서버가 승인한 result만 렌더링하고 raw prompt/context/provenance를 노출하지 않는다.
- export/webhook exposure: 없음.

## Threats changed

- authorization bypass: 모든 recent/get/create/cancel/feedback은 서버 권한 재검증에 의존한다.
- impersonation: shared expected-staff-actor transport만 사용한다.
- replay/duplicate: create/feedback idempotency와 client-side same-job insert fence를 둔다.
- SSRF/XSS: citation decoder는 `/help/articles/{slug}`만 허용하고 React text rendering을 사용한다.
- secret leakage: prompt/context/provider metadata를 UI·log에 추가하지 않는다.
- audit bypass/tampering: include-result 실패 시 insertion을 fail closed한다.
- concurrency/data loss: rich PUBLIC draft를 append 시 보존하며 검증 중 state 변경은 insertion을 중단한다.

## Acceptance scenarios

- Given 최근 성공 작업이 있을 때, When workspace를 열면, Then 기능별 최근 결과를 한 번 감사된 body 조회로 복원한다.
- Given pending 작업일 때, When pollAfterMs가 지나면, Then body 없는 metadata를 조회하고 terminal에서만 result를 가져온다.
- Given INTERNAL mode일 때, When reply 사용을 선택하면, Then mode를 자동 전환하거나 삽입하지 않고 PUBLIC 선택을 요구한다.
- Given PUBLIC draft가 있을 때, When reply 사용을 선택하면, Then 추가/교체/취소를 요구하고 선택 직전에 다시 서버 freshness를 확인한다.
- Given stale/canInsert=false/NEEDS_REVIEW/expired 결과일 때, Then 삽입 동작을 제공하지 않는다.
- Given summary/triage 결과일 때, Then 제안만 표시하고 ticket mutation을 만들지 않는다.
- Given citation URL이 외부 URL일 때, Then decoder가 성공 응답 전체를 거부한다.

## Validation

- `npm run typecheck`
- `npm run test:staff`
- `npm run build:staff`
- `npm run check:design-system-boundaries`
- Storybook MCP `get-changed-stories`, focused `run-story-tests`, `preview-stories`
- `git diff --check`

## Compatibility and migration

- OpenAPI change classification: 없음. committed Core contract 소비만 추가.
- migration/rollback: 없음. UI component/API module integration을 되돌리면 기존 workspace로 복귀한다.
- backfill: 없음. 최근 job API에서 owner-bound state를 복원한다.
- existing client/UI impact: writable Agent Ticket Workspace context 상단에 패널이 추가되며 read-only/customer surface는 변하지 않는다.

## Human explanation

- 서버가 authorization/PUBLIC-only/audit/freshness를 소유하고 UI는 그 capability를 다시 확인한 뒤 사람의 명시적 동작만 작성기에 반영한다.
- 피그마의 정보 계층은 사용하되 현재 계약에 없는 chat/workflow/INTERNAL 활용은 제품 기능으로 만들지 않는다.
- provider 품질·비용·지연 측정이 나빠지면 기본 노출, polling cadence, 기능별 availability를 서버 설정과 함께 재검토한다.

## Completion report

- 변경: Agent Workspace AI context panel, strict API decoder/transport, recent job restore와 bounded polling/cancel, typed result/feedback, safe reply insertion, stories/tests/docs.
- 권한/감사: Backend의 current staff/ticket/feature binding과 result-read required audit를 그대로 사용한다. UI는 `includeResult=true` 재검증 실패, stale 또는 `canInsert=false`에서 fail closed한다.
- 작성기: INTERNAL mode를 자동 변경하지 않고, 기존 PUBLIC rich draft는 append 시 보존한다. 검증 중 state 변경과 동일 job 중복 삽입을 차단한다.
- 검증 Passed:
  - `npm run typecheck`
  - `npm run lint`
  - `npm run test:staff` — 42 files, 250 tests
  - `npm run build:staff`
  - `npm run check:design-system-boundaries`
  - Storybook MCP focused interaction+a11y — AI state 6개와 integrated writable workspace
  - `git diff --check`
  - Storybook/browser visual QA — isolated 360px panel과 1280px context drawer
- 부분 실패: `make docs-check`의 OpenAPI bundle/documentation quality unit 단계는 통과했으나, final repository validator가 기존 `docs/frontend-audit-2026-09-11.md`의 stale absolute line links와 미승인 기존 PNG evidence 때문에 실패했다. 이 작업에서 해당 legacy evidence는 변경하지 않았다.
- Preview:
  - Ready: `http://localhost:6006/?path=/story/06-domain-ai-assistance-aiassistantpanel--ready-recommendations`
  - Existing draft: `http://localhost:6006/?path=/story/06-domain-ai-assistance-aiassistantpanel--existing-draft-choice`
  - Stale: `http://localhost:6006/?path=/story/06-domain-ai-assistance-aiassistantpanel--stale-reply`
  - INTERNAL guard: `http://localhost:6006/?path=/story/06-domain-ai-assistance-aiassistantpanel--internal-composer-guard`
  - Integrated workspace: `http://localhost:6006/?path=/story/06-domain-workspace-agentticketeditorworkspace--writable`
- 미구현/Not run: chat/workflow/auto-apply/auto-send, live provider/Langfuse, 실데이터, deploy, 사람 품질 평가, production E2E/visual baseline 갱신.
- migration/rollback: schema/OpenAPI 변경과 backfill이 없다. AI panel import와 feature directory를 되돌리면 기존 workspace로 복귀한다.
- 성능: production latency/throughput은 측정하지 않았다. 이번 UI는 server `pollAfterMs`와 최대 10초 bounded backoff를 사용한다.
