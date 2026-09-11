# 승인된 시각 기준선

2026-09-11 사용자가 #176의 CI 실패 원인과 기준선 갱신 대기 사유를 확인한 뒤 “허용할게. 원래 하려던거 계속 진행하고 원격에 반영까지 해줘.”라고 승인했다. 승인 대상인 macOS/Linux Queue·Workspace 1280/1440/1920px 기준 이미지 12장을 반영했다. 앞서 준비한 후보와 적용 이미지의 SHA-256이 모두 일치한다.

변경은 상태·메타데이터·표 제목의 14px 확대, workspace 제목의 18px 확대와 현재 앱의 내 매크로 메뉴 등을 반영한다. 기존 Queue 비교에서 약 2% 차이가 발생했으며 목록의 행·상태·소유자와 PUBLIC/INTERNAL 구분은 유지된다. 실제 API는 합성 fixture로 대체했다. 각 플랫폼에서 Playwright 1.62.1 Chromium으로 별도 생성했으며 플랫폼 간 이미지를 복사하지 않았다. 허용치 `maxDiffPixelRatio: 0.01`, `threshold: 0.2`와 테스트 assertion은 변경하지 않았다.

[12장의 플랫폼·크기·기존/변경 후 SHA-256 manifest](visual-candidates.json). `baselineSha256`은 갱신 전, `candidateSha256`은 승인 후 적용한 이미지의 해시다.

## 대표 변경 전후

| 화면 | 변경 전 | 승인 후 |
| --- | --- | --- |
| Linux Queue 1280px | [기존 이미지](https://github.com/kdh949/deskseed/blob/b7d9a120d5ae80dc0f5db64651a3545eae9509ef/frontend/e2e/__screenshots__/linux/frontend-system-view-queue-1280.png) | [기준 이미지](../../../frontend/e2e/__screenshots__/linux/frontend-system-view-queue-1280.png) |
| Linux Workspace 1920px | [기존 이미지](https://github.com/kdh949/deskseed/blob/b7d9a120d5ae80dc0f5db64651a3545eae9509ef/frontend/e2e/__screenshots__/linux/frontend-system-workspace-1920.png) | [기준 이미지](../../../frontend/e2e/__screenshots__/linux/frontend-system-workspace-1920.png) |

전체 [macOS 기준선](../../../frontend/e2e/__screenshots__/darwin/), [Linux 기준선](../../../frontend/e2e/__screenshots__/linux/). 기존 로컬 비교 HTML `/private/tmp/deskseed-frontend-visual-review.html`도 보존했다.

## 승인 후 검증

제품 코드 `b7d9a12`에 승인된 기준선만 적용해 다음 명령을 스크린샷 생략·갱신 옵션 없이 실행했다.

```bash
cd frontend
npx playwright test customer-auth-continuation.spec.ts access-surface.spec.ts admin-operations.spec.ts agent-views-workspace.spec.ts agent-ticket-write.spec.ts frontend-system.spec.ts ticket-workspace.spec.ts
```

- macOS: 21 tests Passed, Queue/Workspace 6개 pixel 비교 및 페이지 이동·read intent·axe 포함.
- Linux: Playwright `v1.62.1-noble` 컨테이너의 동일 소스로 21 tests Passed, Queue/Workspace 6개 pixel 비교 포함.
- 직원 Storybook MCP: `get-changed-stories`는 변경 story 없음. Queue Shell과 Ticket Workspace Anatomy의 focused `run-story-tests(a11y=true)` 2개 Passed.
- 새 component/story/API/DB 변경 없음. 전체 MCP 352개 검증은 직전 리뷰 반영 때 완료했고 이번에는 해당 2개만 재실행했다.
- 원격 CI는 커밋별 PR checks에서 별도 확인한다. 실제 SMTP/DB 인증 E2E, 부하 측정, 전체 수동 screen reader는 실행하지 않았다.

미리보기: [Queue Shell](http://localhost:6216/?path=/story/04-patterns-seed-workspace--queue-shell), [Ticket Workspace Anatomy](http://localhost:6216/?path=/story/04-patterns-seed-workspace--ticket-workspace-anatomy).

초기 자동 승인 검토는 [docs/40](../../40-frontend-visual-regression-and-accessibility.md)의 사람 검토 조건을 이유로 갱신을 거절했었다. 이후 사용자의 명시적 갱신·원격 반영 승인에 따라 적용했으며 승인 대기는 해소했다. 롤백은 이 기준선 갱신 커밋 revert로 가능하고, 이전 UI로의 롤백은 해당 제품 커밋을 함께 검토해야 한다.
