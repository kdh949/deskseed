# PR 159 폼 편집 리뷰 수정

## Goal and sources

ADMIN이 조건을 삭제·추가하고 폼의 이름을 수정해도 유효한 우선순위와 기존 설명을 보존한다.
REQ-CFG-010/011, D-055/059, ADR 0041, CFG-002, UI-002/004를 따른다.
기존 admin ticket-forms GET/PUT/publish 계약과 `CODEX_TASK_TEMPLATE.md`의 경계를 유지한다.

## Scope and boundaries

- 새 조건 priority는 현재 최댓값 + 1이다. 기존 조건의 순서는 바꾸지 않는다.
- 기존 description을 draft에서 보존해 PUT에 포함한다. 별도 설명 편집 UI나 PATCH 계약은 추가하지 않는다.
- ADMIN/session/CSRF/If-Match 및 서버의 발행 검증·Admin audit 원자성은 기존 경로를 따른다.
- PII/보존/공개 projection/외부 I/O/DB migration 변경 없음. 기존 트랜잭션과 실패 시 초안 보존을 유지한다.
- Reuse: SeedButton/SeedSelectField/SeedTextField. 새 디자인 시스템 API 없음.

## Acceptance and validation

- Given priorities 0,1,2, when 중간 조건 삭제 후 반대 효과 조건 추가·저장·발행, then 0,2,3 유지.
- Given API에서 설정한 설명, when 폼 이름만 변경, then 저장 요청에 원래 설명 유지.
- 수정 전 Storybook 회귀는 0,2,2로 실패했고 API 단위 회귀는 description 누락으로 실패했다.
- 수정 후 focused Storybook MCP 3개 및 API unit 4개 통과. 타입·전체 직원 단위·문서·경계 검증은 스택 통합 시 최종 기록한다.
- 실제 서버 브라우저 E2E, 부하 측정, 배포는 실행하지 않는다. 서버 계약·migration 변경 없음.
