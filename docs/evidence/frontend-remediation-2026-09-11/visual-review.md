# 시각 기준선 검토 후보

현재 기준선은 수정하지 않았다. 후보는 글자 크기와 실제 현재 메뉴를 반영한 화면이며 실제 API는 합성 fixture로 대체했다. 승인 전이므로 이 자료를 시각 회귀 통과로 해석하면 안 된다. 각 플랫폼에서 Playwright 1.62.1 Chromium으로 별도 생성했으며 플랫폼 간 이미지를 복사하지 않았다.

macOS 기존 Queue 비교는 1280/1440/1920에서 약 2% 차이로 실패했다. 차이는 상태·메타데이터·표 제목의 14px 확대, workspace 제목의 18px 확대와 현재 앱의 내 매크로 메뉴 등이다. 목록의 행·상태·소유자와 PUBLIC/INTERNAL 구분은 유지된다. 후보 생성 과정에서 세 viewport의 페이지 이동·read intent·axe 검증은 두 플랫폼 모두 통과했다.

검토할 변경 후 후보:

| 플랫폼 | 폭 | 티켓 목록 | 티켓 작업 공간 |
| --- | --- | --- | --- |
| macOS | 1280 | [후보](visual-candidates/darwin/frontend-system-view-queue-1280.png) | [후보](visual-candidates/darwin/frontend-system-workspace-1280.png) |
| macOS | 1440 | [후보](visual-candidates/darwin/frontend-system-view-queue-1440.png) | [후보](visual-candidates/darwin/frontend-system-workspace-1440.png) |
| macOS | 1920 | [후보](visual-candidates/darwin/frontend-system-view-queue-1920.png) | [후보](visual-candidates/darwin/frontend-system-workspace-1920.png) |
| Linux | 1280 | [후보](visual-candidates/linux/frontend-system-view-queue-1280.png) | [후보](visual-candidates/linux/frontend-system-workspace-1280.png) |
| Linux | 1440 | [후보](visual-candidates/linux/frontend-system-view-queue-1440.png) | [후보](visual-candidates/linux/frontend-system-workspace-1440.png) |
| Linux | 1920 | [후보](visual-candidates/linux/frontend-system-view-queue-1920.png) | [후보](visual-candidates/linux/frontend-system-workspace-1920.png) |

변경 전 비교 기준은 [기존 macOS 기준선](../../../frontend/e2e/__screenshots__/darwin/)과 [기존 Linux 기준선](../../../frontend/e2e/__screenshots__/linux/)이다. 로컬에서는 `/private/tmp/deskseed-frontend-visual-review.html`이 12개 변경 전후 쌍을 나란히 표시한다.

자동 승인 검토는 “12개 시각 기준선을 저장소에 덮어쓰는 대량 snapshot 갱신은 사람의 화면 검토 전 금지된 prerequisite를 충족하지 않았고, 기존 회귀 기준을 영구적으로 바꿀 수 있다”는 이유로 갱신을 거절했다. [docs/40](../../40-frontend-visual-regression-and-accessibility.md)의 baseline 변경 통제를 따른다. 승인 후 기준선 반영과 전체 E2E/CI 재실행이 남아 있다.
