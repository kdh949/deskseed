# 시간 자동화 운영 수직 슬라이스

ADMIN이 해결 후 경과 분을 설정하고 저장된 정책 버전으로 샘플 티켓의 현재 상태·해결 시각·실행 가능 시각을 검토한 뒤 활성화한다. 이전 버전과 활성화, 최근 후보·실행 결과를 확인하고 입력 충돌을 복구한다.

REQ-AUT-002; ADR 0024, D-059/061/062; AUT-007/008/009, ARCH-002/003, UI-002/004, DOC-001.

- 기존 solved-age/CLOSE_TICKET 정책·scanner·leased worker·immutable history를 사용한다. 범용 시간 조건, 주기 알림, 새 스케줄러/인프라는 추가하지 않는다.
- 계약: list/create/version/activate/deactivate/dry-run 유지, getAutomationVersion/getAutomationHistory additive FROZEN. ADMIN + AUTOMATION_MANAGE + ADMIN_UI 경계를 서버에서 검사한다.
- 이전 버전 20개, 활성 이력·실행·후보 각각 최근 50개만 반환한다. 본문·고객 정보·감사 원문은 반환하지 않는다. 버전 GET은 현재 aggregate 메타데이터와 선택한 불변 정책 값을 분리해 해석한다.
- 새 버전은 기존 활성 버전을 바꾸지 않는다. If-Match 충돌·응답 불명에는 초안을 보존하고 최신 상태 확인 전 재전송하지 않는다. 저장하지 않은 입력으로 활성화하지 않으며 미리보기는 실제 티켓을 변경하지 않는다.
- 정책 비활성화는 새로운 후보 발견을 중단한다. 이미 발견된 후보의 immutable version 실행은 기존 계약대로 유지한다. 재개방 티켓은 현재 SOLVED interval 재확인으로 건너뛴다.
- 기존 mutation/audit transaction, interval idempotency, lease/retry/dead-letter/outbox, retention 유지. DB migration 없음.

검증: 관리자 history/version 권한·존재 여부·과거 정책과 현재 aggregate 구분; 기존 definition/scanner/execution/migration 회귀; API parity/architecture/docs; UI 생성·미리보기·활성/비활성·과거 버전·충돌/응답 불명·권한/빈 목록/오류/로딩·키보드·모바일.

## Verification and delivery boundary

- Passed: AutomationDefinition 4, Migration 2, Execution 6, CandidateScanner 1, API documentation 5, Architecture 1 PostgreSQL/JVM tests (19 total).
- Passed: staff unit 221; full Storybook MCP 142/142 with accessibility after final async-state changes; typecheck/build/lint/Prettier/app boundaries/docs-check. Actual 1280/390/320px recovery drawer has zero horizontal overflow; 320px screenshot reviewed.
- Not run: browser-to-live-backend end-to-end, production deployment, load/EXPLAIN measurements. Existing scanner capacity was not recharacterized by this UI slice.
- API changes are additive; no migration, retention, domain invariant, actor/audit mutation, external I/O or retry algorithm changes. Reverting this UI and additive reads leaves saved policies and their existing worker intact.
- Trade-off: elapsed minutes and CLOSE_TICKET are the only time policy; deactivate stops discovery and does not cancel captured candidates. History is a bounded recent operational view, not a reporting or export engine.
