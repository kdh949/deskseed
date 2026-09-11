# 시각 기준선 검토 후보

현재 기준선은 수정하지 않았다. 후보는 글자 크기와 실제 현재 메뉴를 반영한 화면이며 실제 API는 합성 fixture로 대체했다. 승인 전이므로 이 자료를 시각 회귀 통과로 해석하면 안 된다. 각 플랫폼에서 Playwright 1.62.1 Chromium으로 별도 생성했으며 플랫폼 간 이미지를 복사하지 않았다.

macOS 기존 Queue 비교는 1280/1440/1920에서 약 2% 차이로 실패했다. 차이는 상태·메타데이터·표 제목의 14px 확대, workspace 제목의 18px 확대와 현재 앱의 내 매크로 메뉴 등이다. 목록의 행·상태·소유자와 PUBLIC/INTERNAL 구분은 유지된다. 후보 생성 과정에서 세 viewport의 페이지 이동·read intent·axe 검증은 두 플랫폼 모두 통과했다.

[후보 12장의 플랫폼·크기·기존/변경 후 SHA-256 manifest](visual-candidates.json).

사용자의 로컬 작업 환경에서 다음 자료를 열어 검토한다. 저장소의 시각 asset gate가 승인 전 이미지를 저장소에 넣는 것을 금지하므로 이미지와 HTML은 저장소 밖에 보존했다.

- 변경 전후 비교: `/private/tmp/deskseed-frontend-visual-review.html`
- macOS 후보 6장: `/private/tmp/deskseed-visual-candidates-20260911/darwin/`
- Linux 후보 6장: `/private/tmp/deskseed-linux-visual-20260911/e2e/__screenshots__/linux/`
- 변경 전: [macOS 기준선](../../../frontend/e2e/__screenshots__/darwin/), [Linux 기준선](../../../frontend/e2e/__screenshots__/linux/)

자동 승인 검토는 “12개 시각 기준선을 저장소에 덮어쓰는 대량 snapshot 갱신은 사람의 화면 검토 전 금지된 prerequisite를 충족하지 않았고, 기존 회귀 기준을 영구적으로 바꿀 수 있다”는 이유로 갱신을 거절했다. [docs/40](../../40-frontend-visual-regression-and-accessibility.md)의 baseline 변경 통제를 따른다. 승인 후 기준선 반영과 전체 E2E/CI 재실행이 남아 있다.
