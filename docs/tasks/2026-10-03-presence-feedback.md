# 실시간 협업 연결 상태와 복구 안내

## Goal

상담사는 실시간 상태의 신선도와 연결 실패 원인을 구분하고 작성 내용을 유지한 채 다시 연결할 수 있다. P14 / AG-F14.

## Decision and source references

- REQ-COL-002, REQ-UI-003/004/005/007. D-030/031/033/061, Accepted ADR-0040/0044.
- docs31 상태 소유권, docs33 티켓 읽기 권한, docs34 command/event, docs39 확정 계약, docs51 대화 중심 workspace, docs55 기능 조합.
- 기존 authenticated `/ws/agent/collaboration` v1만 사용한다. HTTP operation/OpenAPI 변경 없음.
- UI-002/003/004/005/006, DOC-001. 서버 origin·session·ticket authorization gate는 별도 PR #242.

## Actor and source

STAFF / AGENT_WORKSPACE. 기존 staff session과 server-side ticket READ 권한을 사용한다. UI 재연결은 인가 우회나 새 access audit를 만들지 않는다. 본문을 요청·전송·로그하지 않으며 semantic TICKET_VIEWED도 만들지 않는다.

## Product and UX contract

- 접속 중, snapshot 대기, 연결됨/빈 목록, 일시적 연결 실패, 세션·권한 거부, 브라우저 미지원 상태를 구분한다. 실패 시 이전 상담사 목록은 현재 목록으로 노출하지 않는다.
- 마지막 유효 snapshot/delta 수신 시각과 다음 자동 재연결 시각을 표시한다. 빈 목록은 실제 snapshot 후에만 확정한다.
- 기본 3초 재연결과 서버의 rate-limit retryAfterMs(3초~60초 범위)를 따르고 수동 재시도는 기존 타이머·socket을 정리한 뒤 한 번만 시작한다. 권한 거부는 자동 재시도하지 않고 명시적 확인만 허용한다.
- P10 workspace에서 빠진 기존 ticket-composer.status 슬롯을 다시 연결해 실제 작성 모드를 공유한다. 연결 전에 선택한 공개/내부 작성 모드를 재연결 후 다시 공유한다. 오래된 socket의 뒤늦은 메시지와 새 snapshot 전 delta는 무시한다.
- 작업 상태 공유가 편집 lock이나 티켓/초안 저장 성공을 의미하지 않는다고 안내한다. 연결 실패로 작성 내용을 지우거나 화면을 새로고침하지 않는다.
- 기존 SeedContextCard/SeedStatusBadge/SeedButton/SeedAvatar 구성과 키보드 동작을 재사용한다. 새 디자인 시스템 API, 라이브러리, 전역 상태 계층을 만들지 않는다.

## In scope / Out of scope

기존 협업 client·hook·context/composer 안내, 단위/스토리/3폭 브라우저 회귀만 포함한다. 서버 비활성화를 추측하는 새 상태, 별도 상태 API, polling, 편집 잠금, broker, 다중 인스턴스, 초안 저장 구조, 운영 배포는 제외한다.

## Invariants and failure semantics

PUBLIC/INTERNAL draft 분리, ticket row source of truth, ownership/child semantics, optimistic version/idempotency, transaction/outbox/audit 의무는 변경하지 않는다. WebSocket은 기존 body-free advisory projection만 사용한다. 연결 오류로 티켓 command를 자동 재시도하지 않는다. 권한은 서버가 매 연결/구독에서 검사하고 UI는 거부 후 수동 요청만 허용한다.

## Data and privacy

메모리에서 마지막 확인 시각·재시도 예정 시각·snapshot 수신 여부·현재 composer channel만 추가한다. 새 영구 저장/PII/secret/log/export/retention 변경 없음. 기존 상담사 displayName과 상태만 렌더링한다.

## Acceptance scenarios

1. 연결 후 snapshot 전에는 상담사 없음으로 단정하지 않는다. 유효 snapshot 뒤 목록과 확인 시각을 표시한다.
2. 연결이 끊기면 오래된 목록을 지우고 예정 재연결/수동 버튼을 제공한다. 수동 재시도는 자동 타이머를 취소하고 현재 작성 모드를 다시 보낸다.
3. 권한 거부 후 자동 접속을 멈추고 수동으로 재인가한다. 이전 socket 메시지가 새 연결을 거부하거나 목록을 되살리지 않는다.
4. WebSocket 미지원은 네트워크 실패와 구분한다. unmount 후 타이머/공유 연결을 정리한다.
5. 1280/1440/1920에서 Enter 재연결·초안 유지·Axe·본문 없는 메시지를 확인한다.

## Validation

완료: 직원 unit 45파일/280개, focused MCP 7개와 full MCP 311개, Chromium 새 여정 3폭/Axe 0, 기존 직원 mock E2E 26개, typecheck/lint/format/build/boundary/contract/docs-check PASS. 전체 mock spec 탐색은 35 PASS/1 FAIL이며 기존 고객 로그인 링크 fixture가 방식 선택을 생략해 실패했다. 해당 fixture 수정은 P03 #241에 이미 있으며 최종 결합 브랜치에서 다시 검증한다. 고객 파일을 이 PR에 중복 수정하지 않는다. 실서버 2인 세션·운영 배포·실제 장애·수동 스크린리더·부하 측정은 실행하지 않는다.

## Compatibility and migration

HTTP/DB/WS wire contract 변경 없음. frontend revert로 rollback한다. 이 PR은 P11 #268 기반이며 production origin 설정 #242는 독립 적용한다. 프런트엔드 검증은 운영 연결 복구 증거가 아니다.

## Human explanation

기존 client의 재연결 수명 주기를 보완하고 UI에서 확인한 사실만 표시한다. 새 시스템이나 추정 상태 대신 실제 snapshot와 기존 인증 오류를 사용한다.


## Completion report

기존 작성 상태 슬롯 복구, 연결/인가/미지원/신선도 안내, 수동·자동 재연결, rate-limit 지연 적용과 오래된 메시지/목록 정리를 구현했다. 새 API·migration·audit·권한·저장 구조는 없고 알려지지 않은 멤버 속성은 projection에서 제거한다. 동시 편집 lock이나 초안 저장 성공을 추정하지 않는다. 실제 production 연결 복구와 성능 수치는 미검증이며 서버 설정 #242 적용이 별도로 필요하다.

미리보기(변경 목록 다음 개별 화면):

- http://localhost:6125/?statuses=affected;modified;new
- http://localhost:6125/?path=/story/07-screens-ticket-presence--reconnect
- http://localhost:6125/?path=/story/07-screens-ticket-presence--working-together
- http://localhost:6125/?path=/story/06-domain-workspace-agentticketeditorworkspace--context-preserves-work
