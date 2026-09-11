# 직원 Storybook 등록 누락 복구

F22, REQ-UI-005/007, D-030/032, ADR 0044. 관리자·감사·일괄 작업·요청자 선택·첨부 등 제공 중인 화면과 앱 내부 디자인 시스템 스토리를 누락 없이 검증한다. 수동 파일 allowlist를 앱 src 하위 story glob으로 변경한다. 두 앱의 설정과 디자인 시스템 격리는 유지한다.

API operation 변경 없음. 테스트는 합성 MSW fixture를 사용하고 실제 고객/직원 데이터를 전송하지 않는다. actor/권한/감사/transaction/concurrency/idempotency/retention·DB 변경 없음. 실패가 드러나면 원인과 검증을 같은 PR에서 추적한다.

UI-002/004/005/006: 소스 story 파일과 실제 Storybook index 대조, MCP full interaction/a11y, changed-stories와 preview, typecheck/lint/build/boundary. 등록 수와 실행 결과는 완료 보고서에 기록한다. source inventory를 실제 DB E2E나 모든 화면 시각 검증으로 주장하지 않는다. 설정 revert 가능.

## 리뷰 반영 후 검증
계정 전환 실패 스토리에 CSRF fixture를 명시해 의도한 DELETE 503을 검증한다. 최종 앱별 설정으로 MCP 서버를 재시작한 뒤 직원 60개 파일의 280개 스토리와 고객 72개 스토리가 interaction/a11y 검증을 통과했다. 모든 스토리 등록 검증은 실제 DB/SMTP E2E 또는 모든 화면의 시각 비교를 의미하지 않는다.

## 승인된 시각 기준선 반영

REQ-UI-001/005/007, D-030/032, ADR 0044, UI-001/002/005/006. STAFF/AGENT_WORKSPACE가 확대된 글자와 현재 메뉴로 Queue에서 티켓을 열 때, 승인된 실제 화면을 플랫폼별 시각 회귀 기준으로 비교한다. 사용자가 2026-09-11 기준선 갱신과 원격 반영을 승인해 macOS/Linux 12장을 적용했다.

Given 고정된 합성 티켓과 1280/1440/1920px 화면, When Queue에서 Workspace로 이동, Then 각 플랫폼의 승인된 6개 이미지와 일치하고 PUBLIC/INTERNAL 표시·read intent·axe 검증을 통과한다. 동일 명령의 전체 mock 페이지 E2E는 macOS와 Linux 각각 21개 통과했다. [승인·변경 전후·명령 기록](../evidence/frontend-remediation-2026-09-11/visual-review.md).

기준 이미지와 검증 문서만 변경한다. 기존 디자인 시스템 계약을 재사용하며 component/story의 추가·수정은 없다. API/권한/감사/데이터·retention/transaction/concurrency/idempotency 변경 없음. 기존 1% 비교 허용치와 assertion을 유지하고 새로운 화면이나 성능·실서비스 검증으로 범위를 넓히지 않는다.
