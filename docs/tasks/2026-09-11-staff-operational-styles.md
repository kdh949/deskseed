# 관리자·감사 화면 공통 스타일 복구

## 시나리오와 근거

ADMIN이 운영 설정을 편집하거나 SECURITY_AUDITOR가 감사 목록을 조회할 때 기존 앱 내부 컴포넌트의 버튼, 폼, 테이블, 레이아웃 스타일이 적용된다. REQ-UI-001/005/007, REQ-PERM-002, REQ-AUD-002, D-030/032, ADR 0044와 docs/28~31, 40, 51에 따른다. UI-002/004/005/006을 검증한다. API operation과 계약 변경은 없다.

누락된 스토리를 다시 등록한 결과 DsButton의 실제 배경색이 브라우저 기본값으로 확인됐다. production main과 Storybook preview는 Agent 전용 canonical-index.css만 읽어 운영 화면의 토큰·primitive·admin/audit 스타일을 누락했다. DESIGN_SYSTEM_MANIFEST에 명시된 앱 전체 index.css entry로 두 진입점을 일치시키고 현재 사용 중인 운영 스타일 뒤에 canonical-index.css를 불러온다. index.css에서 퇴역 AgentShell 등이 들어 있는 designSystem.css를 제거하고 현재 사용하는 tokens.css와 primitives.css만 직접 연결한다. canonical 표면 경계 검사는 그대로 유지한다.

1280px 브라우저에서 날짜 입력의 실제 최소 폭 218~228px이 필터 열 198px을 넘어 옆 필드를 가리는 현상도 확인했다. 필터 열은 최소 240px을 확보하면서 좁은 폭에서는 한 열로 줄어들게 한다.

운영 표 caption과 live status에 쓰는 기존 sr-only 규칙은 primitive stylesheet로 옮겨 생산 entry에 포함한다. MCP의 전체 interaction 278개는 통과했지만 별도 accessibility 결과에서 대기 상태 3개 story의 텍스트 대비가 4.44:1로 실패했다. 사용자가 요청한 프론트엔드 문제 수정 범위에서 기존 amber 토큰을 조금 어둡게 조정해 밝은 canvas 위에서도 4.5:1 이상이 되게 한다. 의미·라벨·색상 계열은 유지한다.

Reuse: MCP에 문서화된 DsButton, AdminShell, AuditExplorer 및 해당 앱의 기존 스타일을 그대로 사용한다. Compose/Extend/Add: 없음. 새 디자인 시스템이나 다른 앱 의존성을 만들지 않는다.

## 경계와 실패 의미

STAFF / ADMIN_UI / AUDIT_EXPLORER의 기존 역할, resource constraint, loading/empty/error/denied/stale 동작과 키보드·포커스 계약을 유지한다. 고객 공개 데이터와 내부·감사 데이터의 분리는 서버 권한 및 projection을 따른다. actor/source/request/correlation, 감사 이벤트, transaction/concurrency/idempotency/retry, 외부 I/O, 개인정보와 retention은 바뀌지 않는다. DB migration, backfill, OpenAPI 변경이 없으며 CSS import revert로 복구 가능하다.

## 수용 기준과 검증

- Given 실제 앱과 같은 CSS entry, When 기존 DsButton을 렌더링, Then 기존 CssCheck가 문서화된 primary 색상을 확인한다.
- Given ADMIN/AUDITOR 화면, When 합성 fixture를 표시, Then 기존 레이아웃·폼·테이블 스타일과 접근성 상태를 유지한다.
- MCP 전체 interaction/a11y, changed-stories, 관련 preview와 브라우저 시각 검토를 수행한다. 소스 파일과 Storybook index를 대조한다.
- typecheck/lint/build 및 앱 간 design-system boundary gate를 실행한다. 실서비스 데이터/DB·메일 E2E와 성능 측정은 수행하지 않는다.

이 변경은 현재 생산 코드에서 사용하는 앱 내부 계약을 다시 연결한다. 새 컴포넌트로 전환하는 대규모 재설계는 범위에 포함하지 않는다.
