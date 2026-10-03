# P19 관리자 티켓 설정 목록과 저장본 미리보기

## Goal / sources

ADMIN이 태그·업무 상태·필드·폼을 검색하고 같은 목록/편집 규칙으로 관리하며 저장된 폼을 고객·상담사 관점에서 서버 평가로 확인한다. ADM-F02/F05/F06/F10, REQ-CFG-010/011/012/013, REQ-UI-005/007. D-047/050/056/061, ADR-0041/0044, docs26/28~34/39/40/47/51/52/55, 기존 FROZEN list/save/transitionTicketField/Form/Tag/Status와 previewTicketForm. CFG-001/002/003/004 (frontend coverage), UI-001/002/004/005/006.

## Reuse / boundaries

직원 MCP 문서의 SeedButton/Text/Select/Checkbox/Notice/Feedback/Drawer를 재사용한다. extension 전용 configuration.css와 각 페이지에만 목록 toolbar/header/명시적 개수·상태 라벨을 맞추고 공통 admin CSS나 다른 agent 소유 파일은 변경하지 않는다. 기존 목록 응답의 불러온 항목만 필터링하고 전체 서버 검색을 주장하지 않는다. 신규 API·프런트 조건 evaluator·unsaved-preview endpoint·새 인프라/의존성 없음.

편집 영역은 긴 목록 아래 대신 선택 즉시 표시하고 제목 focus/닫기 후 실행 버튼 복귀를 제공한다. 미저장 입력은 기존 `useAdminDraftExit` 패턴을 byte-identical 재사용하여 닫기/route/browser 이탈에서 보호한다. 이 공용 helper 수정은 root와 조율한다.

## Preview contract

ADMIN_UI staff session, expected actor와 CSRF가 있는 기존 POST `/admin/ticket-forms/{id}/preview`만 호출한다. 저장된 aggregate version의 actor/ticket/status/fieldValues를 서버가 평가하고 결과 formVersion을 표시한다. 화면에서 아직 저장하지 않은 편집안·발행된 과거 snapshot을 평가한다고 주장하지 않는다. 고객 보기에는 서버 visible과 active/customer capability 범위 및 customer label만 표시한다. 조건 AST를 브라우저에서 평가하거나 raw JSON/표현식을 입력받지 않는다. 시뮬레이션 값은 메모리에만 유지하며 actor 변경시 초기화한다.

## Failures / privacy / transaction

loading/empty/0검색결과/error/denied/409·412/불확실 저장과 stale preview를 구분한다. 현재 입력은 실패시 유지하며 저장 중 이탈/중복 action을 막는다. 기존 If-Match mutation·immutable identity·required audit와 transaction 원자성을 변경하지 않는다. preview는 기존 read-only 서버 경계이고 command 생성/상태 변화가 없다. PII·secret·조건 값의 logging/storage/retention 변경 없음.

## Acceptance / verification

불러온 항목의 이름·식별이름 검색/상태필터/0건/초기화, 필터 유지·즉시 편집·focus·draft guard, 폼의 발행 여부와 고객/상담사 기본 여부 분리, 저장본 preview 고객/상담사·조건 입력·staff 전용 미표시·권한/오류/대기·값 변경 후 재적용을 검증한다. frontend unit/type/build/boundary/contract/lint/format/docs, 실제 MCP focused/full story/a11y/preview, mock E2E 1280/1440/1920을 실행한다.

## Limits / rollback

main 독립 base. 실제 backend/DB audit rollback/동시성·운영 설정 변경·배포/SMTP·Firefox/WebKit·pixel baseline 비교는 별도다. migration/backfill/seed 없고 UI revert로 복구. 성능 수치는 주장하지 않는다.


## Completion evidence

목록 검색은 로드된 이름/고객 표시 이름/식별 이름과 사용/발행/처리 단계 필터만 사용한다. 입력한 필터를 유지한 채 필드·폼 편집을 즉시 표시하고 종료 시 실행 버튼으로 초점을 돌린다. 태그·상태는 기존 drawer를 유지하며 실제 행 버튼으로 초점을 복원한다. 일반 편집안과 아직 추가하지 않은 조건 입력 모두 닫기/route/새로고침에서 보호하고, 미완성 조건은 추가 또는 지운 뒤 폼을 저장하도록 안내한다. 생성 결과 불확실 상태에서 목록을 확인해도 태그·상태 작성안을 버리지 않는다. 4개 route의 본문 main landmark를 보완했으며 공유 admin shell/CSS는 변경하지 않았다.

기존 `listTicketFieldDefinitions`, `listTicketFieldOptions`, `listTicketForms`, `listTicketTagDefinitions`, `listTicketStatusDefinitions` 응답과 기존 CRUD/activate/publish/archive 및 `previewTicketForm`을 사용한다. preview actor/ticket kind/status/custom status/5개 타입 값을 서버로 전달하며 numeric string 정밀도와 줄바꿈을 보존한다. 결과의 formVersion을 표시하고 목록 이후 버전 변경, 이전 조건 결과, 오류/denied/empty/loading을 구분한다. unknown resource ID·누락 policy decoder 실패, 고객 보기의 전역 capability 교집합/고객 label 사용, 관점 전환 값 초기화를 검증했다.

Passed: `npm run test:staff` 43 files/256 tests; typecheck; staff build; design-system boundaries; contract:check; 변경 파일 ESLint/Prettier; `git diff --check`. 기존 staff bundle >500kB warning은 남으며 번들 최적화 수치를 주장하지 않는다. 실제 HTTP MCP list-all-documentation/get-storybook-story-instructions/get-documentation 후 구현했으며 focused run-story-tests 7+2+2와 각 a11y가 통과했다. 최종 전체 MCP 62 files/304 stories와 a11y가 통과했고 도구 응답도 Passing Stories만 반환했다. 재사용한 Storybook 프로세스 1회 heap 종료가 있어 소유 프로세스만 재시작(NODE_OPTIONS=--max-old-space-size=4096)한 후 전체 결과를 재확보했다. get-changed-stories coverage 경고는 4개 runtime consumer의 get-stories-by-component와 전체 suite 실행으로 보완했다.

Mock Chromium E2E 3개(1280/1440/1920): 목록 필터·0건·saved preview server request+CSRF·시험 값 전달·고객에 staff-only 미표시·관점 변경·menu 이탈 계속편집/버리기·실제 beforeunload 취소·field heading/close focus·tag drawer·status phase·axe·수평 overflow를 확인했다. typed NUMBER/checkbox/long-text/select의 정확한 payload는 Storybook으로 확인했다. 처음 발견한 main landmark 누락은 제품에서 수정했으며, 필수 입력의 label exact mismatch와 취소된 reload 완료 대기는 fixture를 수정한 뒤 세 시나리오를 재검증했다. 최종 대표 캡처는 `frontend/test-results/admin-ticket-configuration-*/configuration-{list,preview}-*.png`에 있고 1280/1920을 직접 열어 확인했다.

UI-001/002/004/005의 변경 과업 범위와 UI-006을 로컬 검증했다. CFG-001~004의 실제 DB/authorization/audit rollback/동시성/immutable snapshot 검증은 backend 변경이 없어 이번 실행에서 하지 않았다. 이전 전체 애플리케이션 보장으로 확대하지 않는다. 고객 실제 화면의 픽셀 재현·저장 전 초안 preview·조건 evaluator·backend/OpenAPI/schema 변경·migration·법률 문구/seed·production DB·배포·메일·Firefox/WebKit·승인된 pixel baseline 비교는 미실행/미구현이다. 새 DS API/의존성 없이 기존 직원 Seed controls/Drawer/Notice/Feedback을 조합했다. 기본 폼 지정은 발행 상태와 별도 표시한다는 trade-off와 saved aggregate preview가 과거 immutable published snapshot은 아니라는 점을 운영자가 이해해야 한다.

MCP preview URLs (local ephemeral):

- http://localhost:6020/?path=/story/07-screens-admin-ticket-forms--filter-and-saved-preview
- http://localhost:6020/?path=/story/07-screens-admin-ticket-form-preview--customer-and-agent-conditions
- http://localhost:6020/?path=/story/07-screens-admin-ticket-form-preview--newer-saved-version
- http://localhost:6020/?path=/story/07-screens-admin-ticket-fields--search-and-draft-exit
- http://localhost:6020/?path=/story/07-screens-admin-ticket-labels--status-phase-filter
