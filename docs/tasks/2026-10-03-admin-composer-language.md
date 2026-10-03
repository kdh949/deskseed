# ADM-F06 / AG-F11 — 관리자와 작성기 운영 용어 후속 정리

## Goal and sources

관리자는 고객 접근과 최초 답변 목표의 설정·버전·복구 동작을 이해하고 상담사는 작성기 서식을 한국어로 고른다. P18/P20 및 AG-F11 후속이며 main 기반의 작은 표시 문구 변경이다.

- REQ-TKT-004, REQ-SLA-001/003, REQ-UI-005; D-030/034/044/057/062, Accepted ADR-0023/0032/0042/0044/0045.
- 기존 `getCustomerAccessModeSetting`, `updateCustomerAccessModeSetting`, SLA list/version/activation/preview/analytics와 rich-content 계약을 사용한다. 신규 operation이나 계약 상태 변경은 없다.
- 관련 기준: docs/00~03/07, 21/25~34/39/40/44/50~52와 기존 작업의 필수 문서. UI-002/004/005/006 및 기존 SLA UI 실패·동시성 회귀를 적용한다.

## Actor, scope and data boundaries

STAFF/ADMIN_UI의 active ADMIN 및 STAFF/AGENT_WORKSPACE 작성기가 대상이다. 관리자 두 화면·SLA 메뉴의 version/access link/snapshot 용어와 작성기 서식 선택의 네 label만 고친다. 식별자, DTO/fixture 데이터, option value, route, request, 권한, expected actor/CSRF/If-Match, 감사 및 PUBLIC/INTERNAL 경계는 그대로다. 새 추상화·컴포넌트·CSS·레이아웃·저장 기능·서버·migration은 없다.

이벤트/transaction/idempotency/retry/retention/redaction/external I/O도 변하지 않는다. 현재 정책의 변경 번호와 불변 정책 버전을 구분해 설명하며, 새 버전 활성화가 기존 티켓 목표를 다시 계산하는 것처럼 표현하지 않는다. 기존 P16/P18의 그룹·시간표·자동화 용어 교정은 중복 구현하지 않는다.

## Reuse plan and acceptance

실제 staff Storybook 6126 MCP의 list-all-documentation, get-storybook-story-instructions, get-documentation을 확인했다. 기존 DsButton/Notification/ScreenState/AdminShell/StatefulEditor를 그대로 재사용한다. Extend/Add는 없다.

- 고객 접근 저장·충돌 후 선택 보존·저장된 설정으로 되돌리기에서 문구와 실제 동작이 일치한다.
- 최초 답변 목표의 목록·버전 검토·저장·충돌/불확정 결과는 기존 동작과 자료형을 유지한다.
- 작성기의 본문/제목 1~3은 기존 paragraph/1/2/3 값을 유지하고 서식 키보드 동작이 유지된다.
- 변경 이야기와 관련 기존 unit/MCP를 실행하고 preview를 검토한다. 통합 전체 suite/시각 기준은 상위 결합 검증에서 별도로 수행한다.

## Validation and handoff

통과: typecheck, 직원 unit 43 files/255 tests, staff build, DS boundary 4 tests+scanner, 변경 파일 ESLint/Prettier, docs-check, diff-check. 기존 500KB bundle 경고는 남는다. 실제 HTTP MCP의 focused 13 stories/a11y PASS JSON을 확인했고, get-changed-stories/consumer discovery/preview-stories를 호출했다. unit 파일 2개의 story coverage 경고는 해당 unit 실행으로 구분해 검증했다.

새 E2E나 full MCP는 중복 실행하지 않았다. 4개 작성기 option label과 관리자 메뉴 이름의 공유 소비자 영향은 상위 최종 통합 full suite/시각 검증에 포함한다. 본 PR에서 pixel baseline을 변경하지 않았다. 서버 테스트·운영 배포·메일 발송·실제 개인정보/권한 변경은 범위 밖이다. 저장 DTO/DB가 그대로라 backfill과 migration은 없고 UI 커밋 revert로 되돌릴 수 있다. 성능 측정은 하지 않으며 문구 변경을 성능 개선으로 주장하지 않는다.

- http://localhost:6126/?statuses=affected;modified;new
- http://localhost:6126/?path=/story/06-admin-admin-customer-access-mode-page--conflict-preserves-selection
- http://localhost:6126/?path=/story/06-admin-admin-first-reply-sla-page--version-review-and-edit
- http://localhost:6126/?path=/story/05-shells-layouts-adminshell--mail-operations
- http://localhost:6126/?path=/story/03-components-seed-rich-text--full-formatting
