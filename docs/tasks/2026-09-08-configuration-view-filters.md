# 티켓 설정을 Saved View 조건에 연결

ADMIN이 발행한 폼으로 접수한 문의에 STAFF가 태그/업무 상태를 붙인 뒤, 저장 보기에서 태그·최초 접수 폼·사용자 상태·조회 가능한 필드 값으로 목록/미리보기/건수를 확인한다.

REQ-CFG-001/010/012/013/014, REQ-VIEW-001; CFG-003/005, ACC-001, CHG-001, DOC-001, UI-002/004, PERF-001; ADR 0041과 기존 saved view 소유/공유/버전 계약 유지.

조건 AST v1에 TAG/FORM/CUSTOM_STATUS/CUSTOM_FIELD와 optional fieldKey만 추가한다. CUSTOM_FIELD는 활성·상담사 공개·searchable·비민감 CHECKBOX/NUMBER/SINGLE_SELECT/SHORT_TEXT만 신규 설정 가능하다. EQUALS/NOT_EQUALS/IN/NOT_IN을 제공한다. NOT 계열은 해당 값이 없는 티켓도 포함한다. runtime에서 더 이상 조회할 수 없는 필드 조건은 양/음 비교 모두 false이며 편집기는 사용 불가 필드로 표시한다. 등록된 다른 조건은 기존 의미를 유지한다.

FORM은 고객의 최초 ticket_customer_form_bindings를 사용하며 현재 상담사 기본 폼을 뜻하지 않는다. TAG/상태는 현재 티켓 값이다. 조건을 SQL/코드로 받지 않고 parameter binding과 고정 SQL 분기만 사용한다. 기존 read authorization/required access audit, 관리 변경 감사와 낙관적 동시성을 유지하며 DB migration/신규 infra/외부 I/O는 없다.

검증: 필터별 positive/negative/NULL·교차권한·형식/민감 거부·기존 AST 호환, 동일 preview/list/count compiler, OpenAPI parity/architecture, Storybook 필드·선택지·stale와 모바일, unit/typecheck/build. 실제 부하·배포는 별도다.

검증 결과 (2026-09-08): PostgreSQL AgentTicketReadIntegrationTest 14, ApiDocumentationIntegrationTest 5, ArchitectureTest 1 통과. 태그·접수 폼·업무 상태·NUMBER 필드의 positive/negative/미설정 및 4조건 결합, preview/list/count 일치, 민감 전환 후 차단과 익명 카탈로그 거부 확인. Staff unit 218, typecheck/build/lint/design-system boundaries, make docs-check 통과. Storybook MCP 전체 124개 및 최종 변경된 View 7개 통과, get-changed-stories/preview-stories 실행. 1280/390/320px에서 가로 넘침 0.

미실행: 실제 서버와 브라우저를 함께 연결한 이 슬라이스의 E2E, 부하 테스트, EXPLAIN ANALYZE, 배포. 별도 DB migration 없음. 기존 필터의 fingerprint는 fieldKey가 없을 때 보존되며 추가 조건은 구버전 클라이언트와 동시 배포하지 않는다. 외부 I/O·보관 정책 변경 없음.

Preview: http://localhost:6006/?path=/story/06-domain-workspace-viewconfigurationdrawer--configuration-filters
Preview: http://localhost:6006/?path=/story/06-domain-workspace-viewconfigurationdrawer--unavailable-configuration-field
