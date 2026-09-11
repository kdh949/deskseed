# 직원 Storybook 등록 누락 복구

F22, REQ-UI-005/007, D-030/032, ADR 0044. 관리자·감사·일괄 작업·요청자 선택·첨부 등 제공 중인 화면과 앱 내부 디자인 시스템 스토리를 누락 없이 검증한다. 수동 파일 allowlist를 앱 src 하위 story glob으로 변경한다. 두 앱의 설정과 디자인 시스템 격리는 유지한다.

API operation 변경 없음. 테스트는 합성 MSW fixture를 사용하고 실제 고객/직원 데이터를 전송하지 않는다. actor/권한/감사/transaction/concurrency/idempotency/retention·DB 변경 없음. 실패가 드러나면 원인과 검증을 같은 PR에서 추적한다.

UI-002/004/005/006: 소스 story 파일과 실제 Storybook index 대조, MCP full interaction/a11y, changed-stories와 preview, typecheck/lint/build/boundary. 등록 수와 실행 결과는 완료 보고서에 기록한다. source inventory를 실제 DB E2E나 모든 화면 시각 검증으로 주장하지 않는다. 설정 revert 가능.

## 리뷰 반영 후 검증
계정 전환 실패 스토리에 CSRF fixture를 명시해 의도한 DELETE 503을 검증한다. 최종 앱별 설정으로 MCP 서버를 재시작한 뒤 직원 60개 파일의 280개 스토리와 고객 72개 스토리가 interaction/a11y 검증을 통과했다. 모든 스토리 등록 검증은 실제 DB/SMTP E2E 또는 모든 화면의 시각 비교를 의미하지 않는다.
