# 상담사 숫자 필드 정확성 수정

## Goal and scope

#164에서 상담사가 큰 정수와 고정밀 소수를 정확히 읽고 편집·저장한다. 태그만 수정하면 숫자를 후보 판정이나 저장 요청에 재전송하지 않는다. 기존 코드의 JSON number 변환과 수정하지 않은 필드의 재전송을 함께 고친다.

## Decision and source references

- REQ-CFG-010/011/012, D-002/005/007/008/010/012/033/036/054/056, ADR 0041 유지.
- PRD 10의 실제 변경 필드 집합, docs/47의 typed field/form, AGT-004 context drawer, docs/51의 입력·충돌 보존 패턴.
- `getAgentTicketConfiguration`, `projectAgentTicketConfiguration`, `updateAgentTicketConfiguration`와 CFG-001/002/003, CHG-001, CONC-001, ACC-001, UI-002/004, DOC-001.

## Actor, data and invariants

STAFF의 AGENT_UI 읽기·수정이며 기존 ticket resource authorization, expected actor, CSRF, If-Match/clientCommandId를 유지한다. 조회와 후보 판정은 BACKGROUND required access audit를 쓰며 semantic TICKET_VIEWED를 만들지 않는다. 후보 판정은 저장하지 않고, 최종 명령은 기존 ticket transaction의 한 TicketAudit와 함께 commit/rollback한다. 모호한 실패는 원래 version/body/command ID로 재시도하고 확정 충돌 시 명시적 최신 설정 조회 후 편집한 필드만 유지한다.

민감 숫자 값의 보호된 감사·로그·retention 의미와 고객 PUBLIC projection 경계는 변하지 않는다. 새 scope, 외부 I/O, outbox, migration, schema backfill, 계산 라이브러리는 없다.

## Contract and reuse plan

상담사용 `AgentTicketFieldValue`를 분리해 조회·후보 판정·저장 `numberValue`를 지수 없는 십진수 문자열로 정의한다. 서버와 프런트는 문자열을 그대로 전달하고, 최종 저장의 BigDecimal 검증은 numeric(30,12)의 정수부 18자리·소수부 12자리 및 기존 필드별 제한을 적용한다. 서버 조회는 불필요한 후행 0을 제거한 정확한 십진수 문자열을 반환한다. DB/domain의 수치 비교는 유지한다.

Reuse: staff Storybook MCP에서 확인한 SeedTextField의 value/error/required와 기존 onChange, SeedDrawer 및 기존 폼 구성을 사용한다. 기본 텍스트 입력에서 숫자 문자열을 보존하고 숫자 형식 오류를 필드에 표시한다. Compose/Extend/Add는 없다. 별도 고객 폼·관리자 preview·macro 액션의 wire 계약은 유지한다. 같은 runtime projection을 소비하는 macro preview는 문자열 값을 직접 사용한다.

## Compatibility and failure semantics

상담사 전용 세 operation의 `numberValue` 응답/요청 타입 변경은 breaking이며 이 PR의 Staff Console과 backend를 함께 배포해야 한다. 기존 JSON number 응답은 새 클라이언트가 거부하므로 일부 배포에서 손상 값을 저장하는 fallback은 없다. 고객·관리자·매크로 값 스키마는 바꾸지 않는다. 롤백은 이 수정 커밋의 서버와 UI를 함께 되돌린다. DB 저장 허용 범위를 벗어난 값은 묵시적으로 반올림하거나 DB 오류로 실패하기 전에 기존 validation problem으로 거절한다.

## Acceptance scenarios

- Given 서버에 `9007199254740993`이 저장됨, When 상담사가 태그만 저장, Then 화면·후보 판정·DB 값이 유지되고 요청 fieldValues는 비어 있다.
- Given 큰 정수 또는 고정밀 소수, When 직접 수정·저장·재조회, Then `123456789012345678.123456789012`와 `-0.000000000001`이 정확히 왕복한다.
- Given 다른 변경으로 충돌, When 최신 설정 조회, Then 미수정 숫자는 최신 값으로 바뀌고 사용자가 수정한 다른 입력은 유지된다.
- Given 비숫자·자릿수 초과·필드 범위 초과 입력, When 저장, Then 명시적으로 실패하며 기존 값과 ticket version/audit는 보존된다.
- Given 저장 결과가 불확실함, When 같은 command 재시도, Then 숫자·감사·version 변경이 중복되지 않는다.

## Validation and completion

Passed: PostgreSQL AgentTicketCommandIntegrationTest 22, MacroDefinitionIntegrationTest 6, ApiDocumentationIntegrationTest 5, ArchitectureTest 1 (총 34). Staff unit 225, typecheck, build:staff, lint, check:design-system-boundaries, 변경 frontend 파일 Prettier 검사, make docs-check. 숫자 응답 decoder의 실패 회귀 4건을 먼저 확인한 뒤 11개 관련 unit을 통과시켰다.

Passed: 격리된 Linux Playwright 환경의 Storybook MCP run-story-tests로 상담사 configuration 14개 스토리와 a11y를 검증했다. 태그만 변경, 정확한 숫자 편집·재조회, 잘못된 숫자 저장 차단, conflict 후 미수정 필드 최신값 반영을 추가했다. Mac 로컬 Chromium은 실행 권한 제한으로 시작하지 못해 이 Linux 검증으로 대체했다. get-changed-stories의 root-relative 경로 탐지에는 격리 복사본의 미포함 파일이 나타났으므로 실제 대상 스토리 파일의 14개 export를 명시해 검증했다.

Not run: 실제 backend 연결 browser E2E, 운영 부하/EXPLAIN, 배포. 성능 개선 주장은 없으며 query/인프라 추가 없이 전달값 정확성을 검증한다. 사람이 설명할 핵심은 브라우저에서 수치 연산을 하지 않는 값의 전달 표현만 문자열로 바꾸고, 최종 수치 검증과 저장 권한은 서버에 유지한다는 점이다.
