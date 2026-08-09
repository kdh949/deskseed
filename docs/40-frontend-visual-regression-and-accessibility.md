# Frontend Visual Regression and Accessibility Standard

## 1. 기준

- WCAG 2.2 AA 목표.
- Garden component의 접근성 semantics를 유지한다.
- custom dense UI는 keyboard/screen-reader 검증을 추가한다.

## 2. Keyboard map

기본 브라우저/컴포넌트 키를 우선한다. 전역 shortcut은 입력 중 오작동하지 않아야 한다.

제안:

```text
g then v  Views
c         new ticket (input focus 아닐 때)
/         global search
[ / ]     previous/next ticket tab
Alt+Shift+R  focus reply composer
Alt+Shift+P  focus properties
Alt+Shift+C  focus context panel
```

shortcut은 설정에서 끌 수 있고 도움말 dialog를 제공한다.

## 3. Focus requirements

- 모든 interactive element focus visible.
- sticky header/composer/banner가 focus를 가리지 않음.
- modal/drawer open 시 focus 이동, close 시 origin 복원.
- panel resize handle keyboard 지원.
- virtualized table 사용 시 logical focus 유지.

## 4. Semantic components

- navigation: `nav` + label.
- ticket table: native table 우선; grid 필요 시 ARIA pattern 엄격 적용.
- comment timeline: ordered list/article.
- status badge: text 포함.
- tabs: tablist/tab/tabpanel.
- alert banner: error severity에 맞는 live region.
- form error: field association.

## 5. Contrast and motion

- body text 4.5:1.
- large text 3:1.
- non-text controls/focus meaningful contrast.
- prefers-reduced-motion에서 panel/notification animation 최소화.
- internal note background만으로 visibility를 구분하지 않음.

## 6. Visual test matrix

Viewports:

```text
1280x800
1440x900
1920x1080
390x844 customer portal
```

Themes:

- light required.
- dark later only after full semantic token coverage.

Data states:

- long Korean/English subject.
- long customer name/email.
- 100+ comments.
- 10 child tickets.
- multiple warnings.
- permission read-only.
- network/loading/empty.

## 7. Screenshot baselines

폴더 제안:

```text
frontend/tests/visual/
├── views/
├── ticket/
├── customer/
├── audit/
├── admin/
└── integrations/
```

baseline update는 자동 승인하지 않는다.

## 8. Component stories

필수 story variants:

- CommentCard customer/agent/internal/system.
- ReplyComposer public/internal/disabled/uploading.
- TicketProperties editable/read-only/conflict.
- ConflictBanner one/multiple fields.
- ChildWarning.
- AuditDiff simple/complex/redacted.
- WebhookDelivery success/retry/dead-letter.

## 9. Accessibility CI

- static lint.
- component axe.
- critical E2E axe.
- keyboard script.
- manual screen-reader checklist per stable release.

자동 도구 통과만으로 접근성 완료를 주장하지 않는다.
