# PR 159–168 리뷰 수정 통합 검증

## 범위와 근거

대상은 #159, #160, #161, #162, #163, #164, #165, #167, #168이다. 각 PR의 기존 요구사항·Decision·Accepted ADR과 API를 기준으로 미해결 리뷰를 수정하고, 원래 소유 PR의 커밋을 후속 PR에 merge commit으로 전파했다. 기존 공개 이력은 재작성하지 않았다.

| PR | 사용자 시나리오와 수정 | 요구사항·검증 근거 |
| --- | --- | --- |
| #159 | 관리자 폼 조건 삭제 후 추가 시 priority 중복 방지, 이름 편집 시 기존 description 보존 | REQ-CFG-010/011; ADR 0041; CFG-002, UI-002/004 |
| #160 | 협업 생성 응답 유실 후 409에서도 기존 command ID/payload 유지. 새로 조회한 협업 요청을 사용자가 확인하기 전 새 생성 차단 | 기존 Transfer/Child 소유권 규칙; CONC-001, UI-002/004 |
| #161 | 최신 고급 액션과 순서를 기준으로 사용자가 편집한 항목만 반영. 같은 항목 충돌과 기본/사용자 상태 충돌은 기존 폼에서 명시적으로 선택 | REQ-CFG-003; ADR 0024/0040; AUT-003/004/007/008, UI-002/004 |
| #162 | 검토 문서 재조회 시 최신 revision 본문 표시. 실제 편집 중인 DRAFT만 보존 | REQ-KB-001/004; ADR 0013/0018/0040; UI-002/004 |
| #163 | 접수 결과 미확정 후 429에서도 원래 요청 유지. LONG_TEXT에 LF/CRLF 허용, 다른 제어문자 거부 | REQ-CFG-014; ADR 0041; CFG-001~006, TKT-001/002/006 |
| #164 | 보류 상태의 디코더·선택지를 서버 ON_HOLD와 일치 | REQ-CFG-012; ADR 0041; UI-002/004 |
| #165 | SHORT_TEXT 단일 비교에서 쉼표 보존. 다중 비교는 한 줄에 한 값이며 빈 값은 검증 오류 | REQ-CFG-014, REQ-VIEW-001; ADR 0041; UI-002/004 |
| #167 | 미일치·중복·실행 제외 규칙을 액션 예산에서 제외. 실제 실행 200개 한도 유지 | REQ-AUT-001; ADR 0024/0041; AUT-001~008, CONC-001 |
| #168 | 새 수정 요청 없음. 상위 수정 통합 후 시간 자동화 정의·실행 회귀 확인 | REQ-AUT-002; ADR 0024; AUT-007/008/009 |

#164의 숫자 정밀도 리뷰는 작업 시작 시 별도 커밋 `a567e263780895637e93b1865d85e096bdf81a4a`로 이미 해결되어 있었다. 해당 커밋과 십진수 문자열 전달·정확한 표시/편집·미수정 필드 제외 동작을 보존하고 통합 검증했다. 상세 계약과 호환성은 `2026-09-08-agent-ticket-number-precision.md`를 따른다.

## 도메인·권한·실패 경계

- Transfer는 기존 티켓 소유권을 이동하며 Child 생성은 부모 소유권을 바꾸지 않는다. PUBLIC/INTERNAL, 고객 projection 및 staff-only 필드 경계를 유지한다.
- 기존 STAFF/ADMIN/CUSTOMER actor, session/CSRF, scope/resource 권한, If-Match, audit와 mutation의 트랜잭션 경계를 유지한다. 새 audit 이벤트나 공개 데이터는 추가하지 않았다.
- 결과 미확정 재시도는 같은 논리 command의 ID와 payload를 보존한다. 새 서버 replay 저장소나 일반화된 충돌 병합 엔진을 추가하지 않았다.
- 트리거는 실제 실행 액션에만 예산을 적용한다. 201번째 실행은 기존처럼 실패·재시도하며 티켓 및 실행 기록이 함께 rollback된다. 활성 규칙 전체에 새로운 전역 제한을 두지 않는다.
- 외부 I/O, PII/retention 정책, DB schema와 migration은 이번 리뷰 수정에서 변경하지 않았다. 기존 outbox/post-commit 경계를 유지한다.

## 로컬 검증 결과

- Passed: Node 22에서 양쪽 앱 typecheck, 전체 Prettier/ESLint, 디자인 시스템 경계 4개, frozen frontend operation 계약 21개, 고객·상담사 production build.
- Passed: 고객 unit 63개, 상담사 unit 236개, 합계 299개.
- Passed: 최종 상담사 Storybook MCP 전체 153개와 접근성 검증. 고객 Storybook MCP 전체 43개와 접근성 검증은 #163 수정 사본에서 실행했으며 최종 통합본의 `frontend/apps/customer-portal` 소스·설정이 동일함을 Git diff로 확인했다.
- Passed: PostgreSQL/Testcontainers 기반 관련 백엔드 12개 클래스, 87개 테스트. Transfer/Child 6, Macro 6, Knowledge 5, TicketConfiguration 14, AgentTicketCommand 22, TriggerExecution 9, TriggerDefinition 7, TriggerMigration 2, AutomationExecution 6, AutomationDefinition 4, API documentation 5, Architecture 1.
- Passed: 원격 #160의 고객 첨부파일 실패를 확인한 뒤 CustomerRequestPortalIntegrationTest 14개를 별도로 실행했다. 로컬에서는 ConcurrentModificationException이 재현되지 않았다. 원격 결과는 재실행으로 별도 확인한다.
- Passed: `make docs-check`, OpenAPI source bundle `--check`, `git diff --check`, 각 후속 PR의 상위 수정 ancestry 확인.
- Passed: 변경 화면의 실제 미리보기와 320px/390px 고객 폼, 320px/390px/1280px 매크로 overflow 확인.
- 초기 실패와 보완: 기본 Kotlin compiler heap 부족으로 첫 통합 실행이 중단되어 저장소 설정 변경 없이 in-process compiler, 2 GiB heap, max-workers=1로 재실행했다. 매크로 story는 다른 status 안내를 먼저 읽던 검증을 실제 저장 완료 문구가 나타날 때까지 기다리도록 보완했다.
- CI 후속 보완: #167에 있던 공통 답변 편집 story의 붙여넣기·완료 대기 검증을 #159부터 선반영해 중간 PR의 한글 입력 누락을 방지했다. 최상위 파일 내용은 기존 검증본과 같다. #168 자동화 이력 테스트는 나노초 입력의 PostgreSQL 마이크로초 반올림과 예상값 절삭이 달라 1μs 오차로 실패하는 것을 재현했고, 입력을 저장 정밀도로 맞춘 뒤 정의·실행 테스트 10개를 재검증했다. 자동화 실행 로직과 허용 오차는 변경하지 않았다.
- Not run locally: 전체 backend suite, 실제 백엔드와 연결한 전체 browser E2E, 부하/용량 측정, staging/production 배포. GitHub의 각 최종 head CI 및 merge 결과는 원격 상태로 별도 확인한다.

## 호환성과 운영

리뷰 수정 자체는 신규 DB migration이 없다. 보존한 #164 숫자 정밀도 변경은 관련 `numberValue` API 표현을 십진수 문자열로 맞추므로 backend와 staff console을 함께 배포해야 한다. 이번 작업은 배포를 수행하지 않는다. 수정 전후 사용자 미확정 요청의 자동 초기화나 기존 고급 액션 삭제로 되돌리는 rollback은 중복 생성·덮어쓰기 문제를 다시 발생시킬 수 있다.

## Storybook 미리보기

검증용 로컬 서버가 실행 중일 때 접근할 수 있다.

- [상담사 변경 목록](http://localhost:6196/?statuses=affected;modified;new)
- [폼 삭제·추가·발행](http://localhost:6196/?path=/story/07-screens-admin-ticket-forms--delete-then-add-preserves-form-data)
- [협업 중복 생성 방지](http://localhost:6196/?path=/story/07-screens-ticket-collaboration-actions--retry-child-without-duplicate)
- [매크로 최신 값 보존](http://localhost:6196/?path=/story/07-screens-macro-management--conflict-preserves-draft)
- [고객 변경 목록](http://localhost:6163/?statuses=affected;modified;new)
- [고객 접수 미확정 재시도](http://localhost:6163/?path=/story/06-customer-customer-request-form--ambiguous-retry-keeps-payload)
- [최초 접수 제한](http://localhost:6163/?path=/story/06-customer-customer-request-form--rate-limited)
- [지식·상태·뷰 변경 목록](http://localhost:6016/?statuses=affected;modified;new)
- [검토 문서 최신 본문](http://localhost:6016/?path=/story/07-screens-admin-knowledge--publish-conflict-reloads-review)
- [초안 반환 최신 본문](http://localhost:6016/?path=/story/07-screens-admin-knowledge--publish-conflict-reloads-returned-draft)
- [보류 상태 생성](http://localhost:6016/?path=/story/07-screens-admin-ticket-labels--create-on-hold-status)
- [쉼표 포함 문자열 필터](http://localhost:6016/?path=/story/06-domain-workspace-viewconfigurationdrawer--short-text-values-with-commas)

협업 전용 검증 서버 6167은 종료했다. 그 서버에서 반환한 [기존 검증 URL](http://localhost:6167/?path=/story/07-screens-ticket-collaboration-actions--retry-child-without-duplicate)은 위 6196의 동일 story로 확인할 수 있다.
