# P16 — 직원·그룹 편집 동선과 미저장 보호

## Goal

ADMIN이 검색한 직원·그룹의 편집 위치를 바로 찾고, 수정 중 이동할 때 입력을 잃지 않으며, 실행한 양식에서 오류를 해결한다. P15의 전체 검색 위에 ADM-F01/03/04/05/06/09를 직원·그룹 범위로 구현한다.

## Decision and source references

REQ-PERM-002, REQ-UI-005, REQ-AUD-004. D-008/013/014/018/019/020/048/050/061 및 Accepted ADR-0018/0035/0037/0039/0044를 따른다. docs28~31/33/34/39/40/51/52/55, P15 검색 계약과 CODEX_TASK_TEMPLATE의 actor·경계·검증 순서를 적용한다.

기존 list/create/disable staff, grant/revokeStaffAuditAuthority, list/create/rename/disable group, group membership 명령과 P15 searchAdminStaffAccounts/searchAdminGroups만 사용한다. API·permission·schema·감사 event 계약을 추가하지 않는다.

## Actor and product contract

- Active ADMIN / ADMIN_UI. 서버 인가·CSRF·expected actor 및 조직의 최종 불변조건이 authoritative하다. 고객/상담사 API를 변경하지 않는다.
- 생성 양식은 명시적 생성 행동으로 열어 첫 목록/검색을 앞에 둔다. 선택 편집 제목/첫 필드로 초점과 스크롤을 이동하고 닫을 때 실행 버튼으로 돌아온다. 직원 감사 권한은 대상 이름을 담은 기존 Drawer로 제공한다.
- 그룹 생성·이름 변경·구성원 추가 오류를 나누고 해당 필드에 aria-invalid/describedby·초점을 연결한다. 서버 실패와 충돌은 실행한 양식에서 입력을 보존한다.
- 이름/새 직원 입력이 바뀐 경우 닫기·그룹 전환·검색/페이지 변경·메뉴/뒤로가기·unload를 보호한다. 계속 편집은 유지하고 명시적 버리기만 폐기한다. 저장 중 이탈을 막는다.
- 감사 권한 편집은 기존 서버가 허용하는 ACTIVE SECURITY_AUDITOR에만 제공한다. 다른 역할에는 추가 부여 작업을 제공하지 않으며 서버 권한을 확대하지 않는다. 구성원 역할을 한국어로 표시한다.

## Reuse plan and boundaries

DsButton/Notification/ScreenState/RetryButton/DsDrawer와 기존 관리자 스타일을 재사용한다. P18 root의 `useAdminDraftExit`를 동일한 코드로 공유하고 페이지별 확인 Drawer만 조합한다. 새 generic form/search/settings 프레임워크나 고객 앱 API 복사는 하지 않는다. 비밀번호와 미저장 입력은 메모리에만 두며 draft persistence를 만들지 않는다.

Ticket 소유권·assignee/group invariant·mutation+audit atomicity·기존 retry/idempotency를 유지한다. 후보 조회가 변경 명령의 성공을 보장하지 않는다. 외부 I/O·migration·retention 변화가 없다.

## Acceptance and validation

UI-002/004/005와 해당 ADMIN-SEARCH-001 회귀. 생성/선택/키보드 focus, 빈 이름·중복·서버 실패 오류 위치, dirty 닫기/전환/route/unload, clean 즉시 이동, busy 이탈 차단, 보안 감사자 역할 제한, 1280/1440/1920 긴 목록·이름·이메일을 검증한다.

Storybook MCP 문서 조회/변경 story discovery/preview/focused 및 영향 범위 suite, unit, typecheck, build:staff, DS boundaries, Playwright/axe/직접 캡처 검수, docs-check를 실행하고 결과·미실행을 추가한다. Backend wire와 데이터 변경은 없으며 운영 데이터·메일·merge/deploy는 범위 밖이다.

## Verification evidence

- `UI-002/004/005`, `ADMIN-SEARCH-001` frontend 회귀: Storybook MCP에서 documentation index, instructions, DsDrawer/DsButton/Notification 및 Typography/FormActions 계약을 조회했다. 새로운 public component API는 없다.
- 신규 5 stories와 기존 ManageAccount를 보강했다. focused 18 stories 및 전체 61파일/304 stories interaction/axe 통과를 실제 MCP 응답으로 확인했다. full 응답 반환은 테스트 종료보다 지연되었다. `get-changed-stories`의 coverage gap은 실제 두 페이지를 `get-stories-by-component`로 다시 조회하여 확인했다. shared stylesheet의 추가 selector는 directory 페이지에 한정한다.
- `npm run test:staff -- --maxWorkers=2`: 43파일/257테스트 통과. 첫 전체 실행의 App ADMIN fixture는 MemoryRouter를 사용해 useBlocker 오류가 발생하여 해당 fixture만 실제 앱과 같은 data router로 수정했다. 함께 발생한 ticket editor timeout은 코드 변경 없이 제한된 병렬 전체 실행에서 통과했다.
- `npm run typecheck`, `build:staff`, 변경 파일 ESLint/Prettier, `check:design-system-boundaries`의 4테스트+경계검사, `contract:check`, `make docs-check` 통과. 기존 staff bundle 500KB 경고는 유지된다.
- `admin-directory-search.spec.ts` Chromium 4테스트: 닫기·계속 편집·버리기·route·브라우저 back·beforeunload·pending save 이동 차단·비밀번호 폐기와 검색 범위/개인정보 회귀를 검증했다. 1280/1440/1920×900에서 50행 중 마지막 그룹 선택 시 상세 제목 포커스와 viewport 진입, 닫기 시 원래 행 포커스, 전체 구성원 검색/페이지 이동을 확인했다. 1440 생성 Drawer와 그룹 화면 axe 위반 0건이다.
- 캡처는 `frontend/test-results/admin-directory-search-*/admin-directory-{1280,1440,1920}.png`, `admin-group-long-list-{1280,1440,1920}.png`, `admin-staff-create-drawer.png`다. 직접 열어 목록/상세 정렬, 역할·작업 줄바꿈, 생성 Drawer와 상세 포커스 영역을 검수했다. 운영 접속이나 픽셀 baseline 자동 갱신은 하지 않았다.
- Backend는 계약/구현 변경이 없어 이 slice에서 재실행하지 않았다. Firefox/WebKit, 고객 앱 build, 운영 데이터·고부하 측정은 미실행이다. 데이터/DDL/retention/권한 변경이 없어 frontend revert로 되돌릴 수 있다. 기존 API의 비멱등 생성·서버 충돌 검증·감사 원자성을 그대로 사용하며 자동 재시도를 추가하지 않았다.

## Storybook previews

- http://localhost:6010/?statuses=affected;modified;new
- http://localhost:6010/?path=/story/06-admin-admin-groups-page--directory-and-membership-search
- http://localhost:6010/?path=/story/06-admin-admin-groups-page--rename-validation-and-conflict
- http://localhost:6010/?path=/story/06-admin-admin-groups-page--create-group-drawer
- http://localhost:6010/?path=/story/06-admin-admin-staff-page--create-draft-protection
- http://localhost:6010/?path=/story/06-admin-admin-staff-page--manage-account

## Human explanation

목록과 생성 양식을 분리하고 넓은 화면에서 그룹 상세를 목록 옆에 두어 선택 후 편집 위치를 찾는 시간을 줄인다. 1100px 이하에서는 기존 단일 흐름으로 접히며 focus/scroll이 같은 목적지를 가리킨다. 미저장 보호는 작은 기존 hook과 Drawer 조합으로 구현하고 입력을 브라우저 저장소에 남기지 않는다. 권한·인가·명령·검색 감사는 서버의 기존 계약을 사용한다. 운영 개선 수치는 측정하지 않았으며 테스트는 합성 응답 기반이다.

## Remote CI follow-up

첫 #261 CI의 ManageAccount story는 권한 저장 완료 전에 checkbox 상태를 즉시 확인해 실패했다(저장 중 disabled 상태). 테스트에 150ms 합성 응답 지연을 두고 `waitFor`로 checked 및 저장 완료를 확인하도록 수정했다. 제품 상태 코드는 변경하지 않았다. 추가 full MCP 실행은 304 PASS 서버 출력 후 JSON 직렬화 단계에서 Node heap exhaustion으로 서버가 종료되어 응답을 받지 못했다. 앞선 full 304 PASS의 실제 응답은 보존했으며 소유 서버 재시작 후 해당 focused story의 실제 MCP 응답과 typecheck/ESLint 통과를 확인했다.
