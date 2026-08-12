# Zendesk-inspired Frontend Design System

## 1. 디자인 결정

Deskseed는 Zendesk의 업무 정보 구조와 상호작용 패턴을 참고한다. 다만 Zendesk 로고·이름·브랜드 자산·픽셀 단위 복제는 사용하지 않는다. 제품은 독립 브랜드로 보여야 하며 Zendesk의 trademark 또는 trade dress를 그대로 복제하지 않는다.

권장 방식:

- Zendesk Garden React components와 SVG icons를 기반 구성 요소로 사용한다.
- Garden의 Apache-2.0 고지와 NOTICE를 보존한다.
- 제품명, 로고, chrome 색상, empty illustration은 Deskseed 고유 자산을 사용한다.
- Zendesk screenshot을 제품 자산으로 포함하지 않는다.
- “Zendesk 공식 제품”으로 오인될 표현을 사용하지 않는다.

## 2. 프론트 의존성 기준

현재 runtime과 개발 도구의 정확한 버전은 `frontend/package-lock.json`이 소유한다. Garden은 `frontend/src/design-system/` 내부 provider, primitive, icon 구현에서만 직접 import할 수 있다. feature/page/shell은 canonical public export를 사용하며 호환 wrapper를 만들지 않는다.

핵심 계층:

```text
react
react-dom
react-router
typescript
vite
@tanstack/react-query
styled-components
@zendeskgarden/react-theming
@zendeskgarden/svg-icons
storybook / vitest / playwright / axe
```

실제 package version은 저장소 초기화 시 lockfile에 고정한다. Garden major upgrade는 별도 ADR과 visual regression을 요구한다.

## 3. 브랜드와 theme

Deskseed theme의 유일한 token source는 `frontend/src/design-system/foundations/tokens.css`다. 문서에 token 값을 복제하거나 과거 이름의 alias를 두지 않는다.

원칙:

```text
semantic role → component/state contract
reference role → current foundations value
brand role → Deskseed-owned asset and color
```

Zendesk screenshot의 색을 추출하지 않고 Garden semantics와 Deskseed brand를 조합한다.

## 4. Typography

- 기본 본문: 14px/20px.
- compact metadata: 12px/16px.
- page title: 22px/28px semibold.
- ticket subject: 18px/24px semibold.
- 숫자·ticket ID·event ID: 필요 시 monospace.
- 중요도가 없는 label을 대문자로 과도하게 표시하지 않는다.

## 5. Ticket status semantics

색만으로 상태를 구분하지 않는다. 항상 text label을 함께 표시한다.

| 상태 | UI label | 의미 |
|---|---|---|
| NEW | 신규 | 아직 상담사가 본격 처리하지 않음 |
| OPEN | 처리 중 | 상담사가 처리해야 함 |
| PENDING | 고객 대기 | 고객 응답 대기 |
| ON_HOLD | 보류 | 다른 팀·외부 조건 대기 |
| SOLVED | 해결 | 고객 관점 해결 |
| CLOSED | 종료 | system-only, 수정 불가; later |

## 6. 핵심 컴포넌트 목록

### Shell

- `GlobalNavRail`
- `WorkNavigation`
- `TopBar`
- `TicketTabs`
- `ResizablePanel`

### Views

- `ViewTree`
- `TicketTable`
- `ColumnChooser`
- `TemporaryFilterBar`
- `BulkActionBar` (later)

### Ticket

- `TicketPropertiesPanel`
- `ConversationTimeline`
- `CommentCard`
- `ReplyComposer`
- `SubmitMenu`
- `ConflictBanner`
- `OpenChildWarning`
- `ContextPanel`
- `CustomerContext`
- `ChildTicketList`
- `ExternalReferenceCard`
- `AuditTimeline`

Admin/Audit/Integration 전용 시각 컴포넌트는 현재 public design-system surface에 포함하지 않는다. 재조합 시 필요한 범용 primitive/pattern만 current design으로 추가한다.

## 7. Conversation 디자인

Comment card는 다음을 구분한다.

```text
Customer public: neutral surface, customer avatar
Agent public: clean white/raised surface, reply indicator
Internal note: warning-tinted surface + INTERNAL badge
System event: compact timeline row
Automation: system avatar + source label
```

표시 정보:

- 작성자 이름과 actor type
- public/internal badge
- 작성 시각(absolute + relative tooltip)
- source(web, agent, platform, trigger)
- attachment indicator
- 수정 불가 정책이면 edit action 없음

## 8. Composer

- PUBLIC/INTERNAL을 탭 또는 segmented control로 명시적으로 선택한다.
- INTERNAL 선택 시 surface 전체가 경고 색으로 변한다.
- 제출 버튼은 `Submit as Open/Pending/Solved`와 같은 split action을 지원할 수 있다.
- 전송 전 visibility를 키보드와 screen reader가 확인할 수 있어야 한다.
- draft는 local storage에 저장하며 ticket version과 연결한다.
- 첨부파일은 post-MVP이며 업로드 진행·실패 상태를 별도로 보여준다.

## 9. 저장·오류·충돌

- 성공: 작은 toast + 변경된 field가 서버 값으로 확정됨.
- validation: 각 field 아래 message + summary.
- permission denied: action 비활성화만 하지 말고 이유 tooltip 또는 inline message.
- concurrency conflict: properties panel 상단 고정 danger banner.
- network failure: draft 보존, 재시도 버튼, 중복 전송 방지.
- partial save는 금지한다. combined command가 실패하면 모두 실패한다.

## 10. Panel behavior

- Properties와 Context는 resize 가능하다.
- Context는 Customer, History, Child, External, Apps, Audit 탭을 제공한다.
- 좁은 폭에서 label을 숨기기보다 content를 세로로 재배치한다.
- collapsed 상태에서도 icon tooltip과 unread/error badge를 제공한다.

## 11. Tables

- row 전체 클릭 가능하되 내부 링크/checkbox와 충돌하지 않는다.
- keyboard로 row 이동 및 open 가능.
- sticky header.
- cursor pagination.
- loading 시 예상 열 구조의 skeleton.
- 0건, 권한 없음, filter 오류를 서로 다른 empty state로 표시.

## 12. Icons

- 기본 interaction icon 16px.
- compact space에서 12px.
- 32px는 empty illustration에만 사용.
- interactive icon에는 tooltip과 accessible name을 제공한다.
- Zendesk 로고 아이콘은 사용하지 않는다.

## 13. 접근성 목표

- WCAG 2.2 AA.
- text contrast 4.5:1 이상.
- keyboard focus가 sticky composer/banner에 가려지지 않음.
- color-only signal 금지.
- dialogs/drawers focus trap과 restore.
- combobox, menu, tabs, grid는 검증된 Garden primitive를 우선 사용.
- reduced motion 지원.

## 14. 시각적 유사성 수용 기준

Deskseed가 “Zendesk와 유사하다”고 판단할 기준:

- 왼쪽 global navigation과 view navigation.
- 고밀도 ticket table.
- ticket properties / conversation / context 3단 구조.
- public reply와 internal note가 명확히 구분되는 composer.
- customer context와 apps/related work가 우측 panel에 위치.
- restrained neutral palette와 작은 type scale.
- resizable panels와 업무 중심 keyboard interaction.

동일성을 판단하는 기준은 로고·색상·픽셀 복제가 아니라 업무 정보 구조와 상호작용 일관성이다.
