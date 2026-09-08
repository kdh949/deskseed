# 지식 문서 운영과 상담 연결

## 사용자 시나리오
ADMIN이 카테고리/섹션을 구성하고 canonical block 문서를 초안 작성→검토→발행한다. AGENT가 티켓 맥락에서 지식을 검색·열람하고 허용된 문서 링크를 현재 답변 초안에 삽입한다.

## 결정/계약
REQ-KB-001/002/004, ADR 0013/0018/0040; ACC-001, CHG-001, TKT-002, DOC-001. 기존 API와 vendor-neutral block document를 소비한다. 관리자 section list와 return-to-draft만 additive하게 보완한다.

## 경계
- PUBLIC/SIGNED_IN_CUSTOMER 문서만 공개 답변 링크 후보. STAFF/SELECTED_STAFF_GROUPS는 내부 메모에서만 직원 경로로 삽입하며 기존 답변 초안을 보존한다.
- ADMIN_UI mutation/관리 조회 감사와 AGENT_UI search/detail fail-closed access audit을 재사용한다. 검색 원문은 URL/일반 로그에 기록하지 않는다.
- If-Match 충돌 시 초안 유지 및 명시적 재조회. 미확인 생성 결과는 목록 확인 후 재시도한다.
- 발행 상태와 초안 상태를 병렬로 관리하는 새 모델을 추가하지 않는다. 발행 문서는 공개 중지 후 초안으로 돌아가 수정·재검토·재발행하며, 화면에서 공개 중지를 알린다.
- 기존 transactional outbox와 감사의 원자성을 유지하며 migration, 외부 네트워크, 별도 검색 인프라 추가 없음.

## 검증 계획
관리자 section list/return-to-draft 및 기존 lifecycle/audit integration, API/runtime/architecture contracts, canonical block/link privacy tests, Storybook MCP/a11y/320·390px 화면, staff unit/typecheck/build/boundaries/docs.

## 후속 범위
다국어 번역, 검색 실패/도움됨 평가 집계 대시보드, 자동 검토 주기, 공개 상태를 유지하는 병렬 초안은 별도 수요 확인 후 진행한다. 운영 성능/배포는 범위 밖이다.

## 실행 결과
Passed: AdminKnowledgeIntegrationTest 5, ApiDocumentationIntegrationTest 5, ArchitectureTest 1; staff unit 214; Storybook MCP 전체 99/99 및 a11y; typecheck/build/ESLint/boundaries/docs; Chromium 1280/390/320px 넘침 없음 및 390px 육안 확인. 공개 삽입 전 audience 변경 차단, nested Drawer에서 기존 답변 보존·링크 삽입을 검증했다. 실제 backend browser E2E, 운영 성능/배포는 Not run.

직원 문서 링크는 `/agent/knowledge/articles/:slug`, 고객 문서는 실제 고객 앱의 `/articles/:slug`를 사용한다. 기존 동일 origin 경로 배포를 따른다. extension-host에 sensitive context를 추가하지 않고 소유 workspace가 일반 React prop으로 초안 변경 callback을 전달한다.

## PR #162 검토 후 보완

REQ-KB-001/004, ADR 0013/0018/0040과 UI-002/004, DOC-001을 유지한다. 발행 충돌 후 명시적 재조회에서는 최신 revision 본문을 표시한다. 충돌 전후 모두 DRAFT이고 실제 미저장 편집이 있을 때만 사용자 초안을 보존한다. IN_REVIEW 상태에서 다른 관리자가 수정·재검토한 경우와 DRAFT로 되돌린 경우를 각각 검증했다. 기존 If-Match, ADMIN_UI 권한/감사와 서버 lifecycle 의미는 바뀌지 않는다.

검증: 원 구현의 구본문 잔류 Storybook 회귀 실패 확인 후 수정, 지식 관리 Storybook MCP 8개 및 a11y 통과(기존 미저장 초안 보존 포함). Staff unit 220개, typecheck, 변경 파일 ESLint/Prettier, staff build와 디자인 시스템 경계 검사 통과. 전체 Storybook 최초 실행의 새 상태 전환 테스트 1개는 비동기 화면 대기 선택자를 보완한 뒤 focused MCP 재실행으로 통과했다.

별도 API/DB migration, 보관 정책, 외부 I/O, 성능 변경 없음. 실제 서버 연동 browser E2E, 부하 측정, 배포는 이번 보완에서 실행하지 않았다.
