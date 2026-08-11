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

현재 baseline은 Playwright의 platform 분리를 그대로 보존한다.

```text
frontend/e2e/__screenshots__/
├── darwin/
└── linux/
```

`frontend-system.spec.ts`는 다음을 고정한다.

- Agent Home, View Queue, Workspace, Admin, Public form/detail: 1280x800, 1440x900, 1920x1080.
- Workspace INTERNAL draft, same-field conflict, 공통 상태 gallery: 1440x900.
- fixture route는 `import.meta.env.DEV`에서만 등록하고 production bundle에는 포함하지 않는다.
- 데이터와 시각 시간 표현은 고정 fixture를 사용하고 animation은 비활성화한다.

허용 임계값은 Playwright `threshold: 0.2`, `maxDiffPixelRatio: 0.01`이다. 즉 픽셀별 색상 차이 판정 임계값을 0.2로 두고, 판정된 차이 픽셀이 전체의 1%를 넘으면 실패한다. 넓은 레이아웃 이동을 숨기는 화면별 예외는 두지 않는다.

baseline 변경 통제:

1. 일반 검증과 CI는 `npm run test:e2e`만 실행하며 snapshot을 쓰지 않는다.
2. 의도한 UI 변경은 실패 diff와 실제 화면을 먼저 확인한 뒤에만 `npx playwright test e2e/frontend-system.spec.ts --update-snapshots=all`로 갱신한다.
3. Darwin과 Linux 이미지는 각 플랫폼의 동일 Playwright 버전에서 생성한다. 한 플랫폼 이미지를 다른 플랫폼 폴더로 복사하지 않는다.
4. PR은 변경 이유와 대표 before/after를 설명하고, 무관한 baseline 변경을 포함하지 않는다.
5. 사람의 화면 검토 없이 대량 snapshot 갱신을 승인하지 않는다.

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

`frontend-system.spec.ts`는 1440px의 모든 핵심 fixture와 INTERNAL/conflict/state fixture에서 axe violation 0을 요구한다. 별도 keyboard 시나리오는 최초 Tab의 skip link, context tab arrow 이동, PUBLIC/INTERNAL draft 보존, 텍스트·아이콘 기반 visibility, 양쪽 resize separator의 keyboard semantics를 검증한다.

자동 도구 통과만으로 접근성 완료를 주장하지 않는다.
