# 개인 및 공유 매크로 관리

## 사용자 시나리오와 actor
상담사는 자신의 답변/상태/우선순위 매크로를 저장하고 활성화한다. 관리자는 공유 매크로를 같은 흐름으로 관리한다. 저장과 활성화를 분리하고 최신 버전/활성 버전을 명시한다. 상담 중 실제 적용은 기존 ticket-bound preview/apply를 사용한다.

## 계약과 결정
- REQ-CFG-003; docs/47 §4; ADR 0024, 0040
- 기존 list/create/version/activation API를 재사용하고 personal/shared history GET을 추가한다.
- 기존 version/activation 테이블의 최근 이력 각 100건을 제공하며 별도 저장 구조를 추가하지 않는다.
- UI에서 문구/공개 범위/상태/우선순위를 편집한다. 기존 다른 typed action은 손실 없이 유지하고 실제 적용 preview에서 검토한다.

## 경계와 실패
- PERSONAL은 소유자, SHARED는 관리자와 macro:shared:manage를 요구한다. 이력 경로의 scope와 실제 저장 scope도 일치해야 한다.
- 고객 API, 내부 댓글 및 티켓 조회 projection 변경 없음. 이력은 저장 당시 직원 표시 이름만 제공하고 request/correlation/security metadata는 제외한다.
- AGENT_UI/ADMIN_UI actor context와 기존 원자적 MACRO_CREATED/VERSION_CREATED/ACTIVATED/DEACTIVATED 감사를 유지한다.
- If-Match aggregate concurrency를 사용하며 409/412와 불확실한 결과는 입력을 보존하고 재조회 후 명시적으로 저장한다. 새 버전 저장은 기존 활성 버전을 바꾸지 않는다.
- 외부 I/O, outbox, migration, retention 정책 변경 없음. API는 additive하며 이전 클라이언트와 호환된다.

## 검증
TKT-006/CHG-001 및 해당 docs/21 gate: MacroDefinitionIntegrationTest(소유권, 다른 scope, 버전 이력, 감사 rollback 포함), API/architecture contract tests, frontend typecheck/unit/Storybook MCP/a11y/build/boundaries/docs validation. 실제 실행 결과는 완료 시 추가한다.

## 범위 밖
사용량 분석, 적용 후 수정 비율, 과거 버전 복원 전용 화면과 새 고급 typed action 작성기는 후속 범위다. 기존 고급 action은 유지된다. 배포 및 성능 측정은 수행하지 않는다.

## 실행 결과
- Passed: MacroDefinitionIntegrationTest 6, ApiDocumentationIntegrationTest 5, ArchitectureTest 1; 실패/누락 0.
- Passed: staff unit 211, typecheck, build, ESLint, design-system boundaries, documentation validation.
- Passed: fresh Storybook MCP 전체 87/87 및 a11y. Chromium 1280/390/320px에서 가로 넘침 없음; 390px 화면 육안 확인.
- Not run: 실제 서버를 통한 브라우저 E2E, 운영 부하/배포. 테스트 종료 시 OTLP 미연결 로그는 telemetry 수집 증거가 아니다.
