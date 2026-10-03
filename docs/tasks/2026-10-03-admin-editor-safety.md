# P18 규칙·영업시간·자동화 편집 보호

## Goal / actor / sources

ADMIN(STAFF/ADMIN_UI)이 트리거·시간 자동화·영업 시간표를 편집할 때 미저장 변경을 지키고, 시간 단위를 직접 계산하지 않아도 설정 의미를 이해한다. 감사 ADM-F04/05/06 대상.

- REQ-AUT-001/002, REQ-SLA-002, REQ-UI-002/004/005; D-007/032/034/035/044/059/061/062, Accepted ADR-0024/0032/0041. 결정 변경 없음.
- docs/44/45/52, docs/28~31/40/51, 기존 시간표/트리거/자동화 구현 task 참조.
- UI-001/002/004/005/006, SCHED-001/002, AUT-007/008/009, TKT-006/CHG-001 유지.
- 기존 list/create/version/preview/activation/history/reposition API만 사용. API/권한/DTO 의미 변경 없음.

## Reuse plan / scope

- Reuse: SeedDrawer/DsDrawer, SeedButton/DsButton, SeedTextField/SelectField 및 기존 form/notice.
- Compose: 각 페이지의 confirmation drawer와 작은 useAdminDraftExit route/state hook. 실제 3개 사용처의 같은 dirty/busy/route/beforeunload만 묶으며 UI/저장/검증 API는 소유하지 않는다. 새로운 디자인 시스템 API/설정 프레임워크 없음.
- 닫기·Escape·메뉴·뒤로가기·새로고침·시간표 선택/새 작성에서 실제 변경만 확인한다. 취소는 입력/초점을 유지하고 명시적 버리기로만 종료한다. 요청 중 폐기 금지.
- 자동화의 일/시간/분 전환은 같은 정수 분을 유지한다. 1일=24시간의 실제 경과이며 영업 시간표가 아님을 명시. 기존 서버 1~525600분/CLOSE_TICKET 범위 유지. 목록/이력도 자연스러운 단위로 표시.
- 시간표의 timezone/version/aggregate/snapshot 용어를 시간대/버전/설정 변경 번호/기존 응답 목표로 설명한다. immutable version 저장과 별도 활성화 의미 유지.

## Invariants and failure semantics

- 기존 ADMIN/typed manage 권한, CSRF/expected actor, If-Match, immutable version/audit 원자성 그대로. PUBLIC/INTERNAL/customer projection 및 active membership 변경 없음.
- save/preview/activation은 기존 transaction와 audit 경계. 미리보기는 티켓을 바꾸지 않는다. 실행 worker/outbox/lease/retry/idempotency/retention 불변.
- 충돌/불확실한 응답에서 기존 입력 보존·최신 상태 확인 후 명시적 재시도. 중복 자동 retry 없음. guard는 성공한 mutation을 실패로 바꾸지 않는다.
- 편집값은 기존 component memory. 추가 브라우저 영구저장, 로그, PII, export/webhook, 외부 I/O 없음. 새 권한 우회/SSRF/XSS/임의 실행 표면 없음.

## Acceptance / validation

- 초기 변경 없는 새 규칙/기존 규칙은 바로 닫힘. 변경 후 Escape/route/back/reload에 취소·버리기, 저장 중 버리기 disabled.
- 자동화 1440분↔1일↔24시간, 61분↔시간/일의 round-trip 동일. 1.5시간=90분, 정수 분으로 환산 불가/빈값/범위 초과는 저장 불가.
- 새 시간표/새 버전/다른 시간표 선택·닫기에서 입력 보존. 성공 후 dirty 해제, 실패 후 보존. 기존 미리보기·활성화 및 version/If-Match 회귀.
- staff unit/type/lint/build/format/boundary, focused/full Storybook MCP/a11y, 1280/1440/1920 Chromium mock E2E, docs-check. 서버 코드·계약을 바꾸지 않아 backend gate 재실행은 제외; 운영·실제 DB E2E/배포/부하 측정 제외.

## Compatibility and trade-off

migration/backfill/API 변경 없음. UI commit revert로 rollback. 요구사항 구현 상태는 기존 그대로. 공통 hook은 이탈 상태만 담당하고 각 도메인 editor가 저장 및 confirmation을 소유해 범용 폼 엔진을 피한다. 실제 성능 측정은 없으며 새 network call도 없다.

## Completion

- staff unit 44 files / 257 tests PASS. 최종 MCP full 61 files / 295 stories 및 a11y PASS, 문구 최종 검수 후 관련 focused story도 PASS. MCP 소비자 조회로 세 화면과 공통 hook의 연결 확인.
- 실제 React Router 기반 Chromium mock E2E 9개 PASS(세 화면 × 1280/1440/1920). 닫기/Escape/뒤로가기/새로고침 취소 시 입력 유지, 명시적 폐기 시 이동, Axe 위반 0 및 가로 overflow 없음. 저장 중 입력/닫기 차단과 성공 후 dirty 해제는 deferred-response story로 확인.
- typecheck, lint, staff build, design-system boundary, format, docs-check PASS. 검수 중 규칙 화면의 main landmark 누락과 전체 너비 저장 버튼을 보완하고 재검증.
- API/server/migration 변경 없음. 운영 배포, 실제 DB·SMTP/browser E2E, Firefox/WebKit, 성능 측정은 실행하지 않음. 이탈 hook은 P16과 동일 파일을 공유하며 도메인 저장 코드를 추상화하지 않음.
- 로컬 실화면 증거는 감사 output의 implementation-evidence/p18에 보관하며 소스/시안과 구분. requirement status와 기존 권한/감사/transaction 의미 불변.

Storybook preview:

- http://localhost:6120/?statuses=affected;modified;new
- http://localhost:6120/?path=/story/07-screens-admin-triggers--unsaved-escape-guard
- http://localhost:6120/?path=/story/07-screens-admin-automations--duration-unit-preserves-minutes
- http://localhost:6120/?path=/story/06-admin-admin-business-schedules-page--discard-changed-schedule
