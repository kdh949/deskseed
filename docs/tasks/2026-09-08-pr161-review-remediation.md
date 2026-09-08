# PR 161 매크로 충돌 복구

## Goal and references

STAFF/ADMIN이 매크로 답변만 수정할 때 다른 사용자의 최신 그룹·우선순위·이름·액션 순서를 되돌리지 않는다.
REQ-CFG-003; ADR 0024/0040; AUT-003/004/007/008, UI-002/004; 기존 personal/shared 매크로 GET/versions POST 및 If-Match 계약을 따른다.

## Scope and boundaries

- 최신 actions를 기준으로 이름/답변/공개범위/상태/우선순위 중 사용자가 바꾼 값만 반영한다.
- 양쪽에서 같은 항목을 다르게 바꾸면 기존 편집 폼의 안내에서 최신 값 사용 또는 내 편집값 유지를 선택한 뒤 저장한다. 목록 재조회만으로 이 확인을 생략하지 않는다.
- 기존 advanced action 및 순서는 최신 값을 유지한다. 별도 병합 화면·버전 모델·일반화된 충돌 엔진은 추가하지 않는다.
- 공개/내부 구분과 개인 소유권/공유 ADMIN 권한, session/CSRF, admin audit 및 atomic mutation은 기존 서버 경로를 유지한다.
- DB/OpenAPI/PII/retention/external I/O 변경 없음. Reuse/Compose: 기존 폼과 SeedNotice/Button; 새 공통 UI 없음.

## Acceptance and validation

- 다른 사용자가 GROUP A→B, PRIORITY NORMAL→HIGH, 이름·순서를 바꾼 동안 로컬 답변만 편집: 재조회 후 최신 나머지 값과 로컬 답변만 저장.
- 같은 답변 문구가 양쪽에서 변경: 초안을 보존하고 명시적으로 선택하기 전 저장 금지, 재조회 반복으로 확인을 우회하지 않음.
- 수정 전 실제 충돌 story의 우선순위가 NORMAL로 남아 실패. 수정 후 최신값과 저장 payload 검증 통과.
- API 단위5개/typecheck/ESLint 통과. 기존 충돌·동일 항목 충돌·새 사용자 상태 충돌 Storybook MCP 3개/a11y 통과. 최신 사용자 상태와 로컬 기본 상태는 함께 저장하지 않는다. 전체 UI 회귀는 최종 통합 검증에 기록한다.
- 실제 서버 browser E2E/부하/배포는 실행하지 않는다.
