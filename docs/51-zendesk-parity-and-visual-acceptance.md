# Zendesk Parity and Visual Acceptance Register

Status: **Normative UX comparison v0.6**

ADR 0044 overlay: Staff visual acceptance와 Customer Portal visual acceptance는 별도 앱·디자인 시스템·baseline으로 운영한다. Customer Portal은 현재 frozen Knowledge Base, request, identity operation으로 구성 가능한 화면만 shipped로 간주한다. 고객 홈 공지는 `announcements` 공개 Knowledge Base section의 published article로 제한하며 기존 관리자 knowledge lifecycle을 사용한다. profile/preferences/SSO/channel/SLA와 별도 announcement/system-status 도메인은 미래 intent로 남긴다.

Deskseed의 목표는 Zendesk로 오인되는 복제품이 아니라, Zendesk 경험자가 즉시 사용할 수 있는 유사한 업무 정보 구조와 상호작용이다.

## 1. 비교 기준

세 수준을 구분한다.

- `BEHAVIOR_PARITY`: 같은 업무 목적과 결과.
- `INTERACTION_SIMILARITY`: 화면 배치와 조작 흐름이 익숙함.
- `VISUAL_IDENTITY`: 제품 고유 브랜드. Zendesk와 달라야 함.

따라서 “최대한 유사하게”는 첫 두 수준을 높이고, 세 번째는 독립적으로 유지한다는 의미다.

## 2. 공식 UI에서 참고할 핵심 패턴

### Agent shell

- 왼쪽 global navigation rail.
- Views/업무 navigation.
- 상단 ticket tabs.
- 티켓을 여러 개 열고 전환.
- 활성/미저장/새 메시지 상태 표시.

### Ticket workspace

```text
Ticket properties | Conversation + fixed composer | Context panel
```

- properties와 context panel resize.
- 최신 대화가 아래로 흐름.
- composer는 하단 고정.
- PUBLIC reply와 INTERNAL note를 전환하되 별도 draft 유지.
- customer context와 앱/지식/관련 업무는 우측 context.

### Views

- saved condition-based ticket list.
- category/folder navigation.
- dense sortable table.
- counts, filters, stable columns.

### Agent Home

- 나의 업무, 공유 업무, 최근 완료.
- status/channel filter.
- 우선 처리할 티켓을 빠르게 식별.

## 3. Deskseed parity matrix

| Capability | Zendesk-inspired behavior | Deskseed phase | Deliberate difference | Acceptance evidence |
|---|---|---|---|---|
| Global nav | compact dark navigation rail | M0 | Deskseed logo/colors | 1280/1440/1920 visual snapshots |
| Views | saved lists and categorized navigation | M2/P2 | simpler first category model | queue E2E, count reconciliation |
| Ticket tabs | open tickets, dirty/unread indicator, overflow | M2 | no live-channel status initially | keyboard/overflow E2E |
| Properties panel | requester, assignee, group, status, priority | M2 | system fields only first | resizable snapshot and field tests |
| Conversation | chronological public/internal timeline | M2 | web form only first | visibility E2E |
| Composer | fixed bottom, public/internal, submit+status | M3 | plain text first | draft/conflict/network E2E |
| Context panel | customer, related work, apps | M2→P7 | children/external refs first | resize/tab/access tests |
| Side work | internal child ticket | M5 | explicit TicketRelation model | ownership/customer-invisibility E2E |
| Audit/events | ticket-local event view | M3 | security audit is separate ledger | audit reconciliation |
| Customer portal | Help Center, request submission/tracking/comment, password login/registration, passwordless magic link | Customer Portal | anonymous token remains ticket-scoped; no staff UI reuse | customer unit/Storybook/E2E, UI-006, responsive reference comparison |
| Admin Center | staff/group/settings/integrations | deferred UI | staged recomposition | API permission/audit tests |
| SLA | badges, views, policy admin | deferred UI | first reply first | clock/policy contract tests |
| Explore | curated dashboards, drill-down | P5 | no arbitrary report builder first | metric reconciliation |
| Triggers | ordered conditions/actions | P4 | no arbitrary scripts | version/loop/dry-run gates |
| Apps | sidebar extension surface | P7 | sandboxed iframe later | origin/scope/secret tests |

## 4. Desktop layout contract

### 1440px reference

```text
52px global nav
220–260px work navigation when open
280–340px properties
min 480px conversation
300–420px context
```

Panel widths are user preferences, not hard assumptions. Persist them per staff account or local preference after the core UI is stable.

### 1500px and below

- context panel closes and is opened by an accessible header icon.
- properties remains usable, minimum 248px.
- composer action labels may compact but keep accessible names.

### Below 1024px

Agent Workspace is not mobile-first. Use one main column plus drawers for properties/context. Core customer portal remains responsive.

## 5. Visual tokens

Use Garden semantic tokens and Deskseed brand tokens.

```text
base spacing: 4px
body: 14/20
metadata: 12/16
interaction icon: 16px
panel borders: neutral subtle
corner radius: compact, not decorative
shadow: only overlay/floating affordance
```

Do not sample colors from screenshots. Define semantic roles:

```text
surface/default
surface/subtle
surface/internal-note
text/default
text/subtle
border/default
action/primary
status/danger
status/warning
status/success
focus/ring
```

## 6. Component acceptance

### TicketTab

Must support:

- active state.
- subject/number.
- unsaved state without color alone.
- close button.
- keyboard traversal.
- overflow menu.
- draft close confirmation.

### TicketTable

Must support:

- sticky accessible headers.
- server sorting/filtering.
- row keyboard open.
- selected/hover/focus states.
- skeleton, empty, denied, error.
- no semantic `TICKET_VIEWED` on prefetch.

### ConversationTimeline

Must support:

- public/internal/system distinction.
- oldest-to-newest default.
- “new messages” marker later.
- author/source/time.
- safe rendering and redaction state.
- virtualisation only after measurement.

### ReplyComposer

Must support:

- explicit PUBLIC/INTERNAL mode.
- visually strong internal-note state.
- separate drafts per mode.
- plain text first; rich text later.
- submit-and-status action.
- disabled reason, sending, retry.
- draft retained after network/conflict failure.

### ContextPanel

Must support:

- customer.
- previous tickets.
- parent/children.
- external references.
- audit summary.
- apps later.
- resize, collapse, keyboard and focus restoration.

## 7. Screen-by-screen visual regression set

Every release stores screenshots at light mode and required widths.

```text
AGT-002 Agent Home: 1280, 1440, 1920
AGT-003 View queue: 1280, 1440, 1920
AGT-004 Ticket normal: 1280, 1440, 1920
AGT-004 Ticket internal draft
AGT-004 Same-field conflict
AGT-004 Open-child warning
AGT-004 Permission-limited
ADM staff/groups/settings
AUD explorer/list/detail/reveal
PUB request form/detail mobile+desktop
```

Snapshots use deterministic fixtures, fixed Clock, stable fonts, and disabled animation.
Deskseed의 frontend system baseline은 `frontend-system.spec.ts`가 관리하며, 상세 임계값과 갱신 승인 절차는 `docs/40-frontend-visual-regression-and-accessibility.md`를 따른다.

## 8. Interaction parity scenarios

1. Agent opens a saved view and a ticket in no more than two primary actions.
2. Agent reads context while keeping conversation and composer visible.
3. Agent changes group/assignee and adds an internal note in one save.
4. Agent switches PUBLIC/INTERNAL without losing either draft.
5. Agent sees a same-field conflict where ticket properties are edited.
6. Agent creates a child ticket without transferring parent ownership.
7. Agent returns to another open ticket via tab without losing draft.
8. Auditor finds all assignee changes without opening each ticket.

## 9. Brand and intellectual-property boundary

Allowed:

- Garden components/icons under their license.
- conventional help-desk interaction patterns.
- independently implemented three-panel workspace.
- factual documentation saying “Zendesk-inspired” during development.

Not allowed in shipped product:

- Zendesk logo, wordmark, Z symbol, screenshots, illustrations.
- product name/domain that suggests affiliation.
- pixel tracing of proprietary assets.
- text that claims Zendesk endorsement or compatibility without proof.
- copying private CSS/source or reverse engineering non-public APIs.

Use a distinct product name, logo, primary hue, onboarding, illustrations, and marketing site. Trademark/trade-dress conclusions require legal review before commercial release.

## 10. Final UX acceptance workshop

Before portfolio release, compare Deskseed and official Zendesk references by task, not by pixel.

Participants execute:

```text
find unassigned urgent ticket
open ticket and inspect customer history
write internal note and transfer
write public reply and set pending
create child task
review ticket events
```

Record:

- time and errors.
- confusing labels.
- keyboard path.
- missing context.
- visual density.
- accidental public/internal risk.

A screenshot that looks similar but produces a worse or unsafe workflow fails acceptance.

## 2026-09-05 대화 중심 기본 배치

사용자가 선택한 3번 Deskseed 합성 시안을 현재 상담 화면의 비교 기준으로 사용한다. 전역 rail 64px, 속성 320px, 모든 데스크톱 폭에서 기본 접힌 context drawer와 고정 composer를 사용한다. 현재 제공되는 menu, 대화 필터, 초안 저장, 공개/내부 구분 및 실제 SLA 상태는 유지한다. 다중 티켓 탭과 독립 context tab은 이번 시각 개편에서 새로 구현하지 않는다. 선택 시안과 비교할 때 이 기능 차이는 의도된 제품 경계로 기록한다.

구현/검증 기록: `docs/tasks/2026-09-05-agent-conversation-focused-redesign.md`, `design-qa.md`. 기존 Darwin/Linux 픽셀 기준선은 이번 작업에서 자동 갱신하지 않으며 최종 화면 검토 후 별도로 반영한다.
