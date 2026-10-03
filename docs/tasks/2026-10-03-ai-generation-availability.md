# 티켓별 AI 생성 가능 상태 계약

상태: 서버 구현 및 해당 회귀 검증 완료. 계약 PR #245의 capabilities blueprint를 실제 응답 설명·예시로 승격했다. UI 연결과 live provider 검증은 후속이다.

## Goal

상담사가 AI를 누르기 전에 현재 티켓에서 사용할 수 있는 생성 기능을 확인한다.

## Decision and source references

REQ-AI-001/002, D-054/D-066, Accepted ADR 0049, docs/33·39와 AI V1 frontend handoff. Gates: AI-API-001, AI-SRC-001, ACC-007, ARCH-001/002, DOC-001. API: `getAgentTicket`; 생성·조회·result insertion의 기존 AI operation 의미는 변경하지 않는다.

## Actor and source

STAFF/AGENT_UI의 기존 session·expected-actor·ticket read 권한과 request/correlation/interaction context를 재사용한다. BACKGROUND는 semantic TICKET_VIEWED를 만들지 않는다. 새로운 privilege나 admin read 경로를 제공하지 않는다.

## Product and UX contract

기존 `AgentTicketDetail.capabilities` string array에 `AI_SUMMARY`, `AI_TRIAGE`, `AI_REPLY_DRAFT`만 추가한다. PUBLIC comment 존재, active staff allowlist, 전역/개별 기능 설정을 모두 만족할 때만 해당 값을 넣는다. 기존 READ/UPDATE와 unknown-capability 무시 계약을 유지한다. 권한 상세 이유나 global policy·provider·직원 목록은 반환하지 않는다.

화면은 기존 job 조회와 신규 생성을 구분한다. capability가 없는 기능의 생성은 비활성화하고 현재 사용할 수 없다는 안내를 제공한다. 최신 조회/생성 사이 정책이 바뀌면 기존 서버 거절을 표시하고 BACKGROUND refresh로 다시 확인한다. capability는 authorization token이 아니며 budget·rate·provider 준비를 보장하지 않는다. 자동 생성·자동 적용·자동 전송은 없다.

## In scope

기존 AI request/result feature gate를 aiassistance root API로 공유하고, 기존 audited ticket detail 안에서 최소 정책 projection을 추가한다. endpoint·DB migration·새 infrastructure는 필요하지 않다.

## Out of scope

기존 AI 비용절감 PR #183–222의 cache/reuse/rewrite/provider 동작, AI 설정 활성화, live model 호출, 별도 readiness service, INTERNAL input은 포함하지 않는다.

## Invariants and failure semantics

- 공개/내부 댓글 projection, 첫 PUBLIC comment, current ticket truth와 기존 감사 transaction 유지.
- required access audit 실패 시 detail/capability를 반환하지 않음.
- 새 idempotency·concurrency 의미 없음. createAgentAiJob의 기존 replay/version/admission/feature 재검증 유지.
- ticket transaction 안에서 외부 AI network call을 하지 않음.
- 정책 조회 실패를 허용 상태로 치환하지 않음.

## Data and privacy / threats

새 필드 대신 기능명 allowlist만 공개한다. 다른 직원 ID·allowlist·secret·comment body·내부 child 관계를 추가 노출하지 않는다. retention, logging, webhook, customer API는 변경하지 않는다. 낡은 capability로 실제 생성을 우회할 수 없어야 한다.

## Acceptance scenarios

1. 전역 AI off, 허용 staff 누락, PUBLIC 0개일 때 AI capability가 없음.
2. 허용 active staff와 PUBLIC comment, 일부 기능 on이면 해당 capability만 있음.
3. 내부 메모만 존재하는 child에는 AI 생성 capability가 없음.
4. detail 이후 전역/기능/직원 상태가 바뀌면 생성이 기존 정책으로 거절됨.
5. detail 권한 거절·required audit 실패 시 capability 포함 성공 응답이 없음.
6. BACKGROUND refresh가 semantic TICKET_VIEWED를 추가하지 않음.
7. UI의 unavailable/loading/error/denied 상태, 기존 job 읽기, 생성 비활성·keyboard focus를 검증.

## Validation / delivery

계약 PR: Core bundle, docs-check, diff-check. 후속 구현 PR: PostgreSQL-backed ticket detail/AI feature gate/권한·감사 실패 회귀, architecture gate, target app Storybook MCP와 UI tests. 실제 모델·배포·부하 검증은 별도이며 수행 전 통과를 주장하지 않는다.

## Compatibility / human explanation

문자열 capability의 additive 확장으로 기존 READ/UPDATE client를 유지한다. DB migration/backfill 없음. 기능을 위한 새 API나 전체 설정 노출 대신 이미 감사되는 상세 읽기를 사용한다. 기본 정책 off와 기존 기능 flag를 같은 정책으로 해석하여 UI와 생성의 의미가 달라지지 않도록 한다.


## Implementation and observed verification

- `AiFeatureAvailability`는 전역/개별 기능과 active staff allowlist를 한 번의 PostgreSQL 조회로 평가한다. 기존 생성·결과 재인가와 ticket detail이 같은 평가를 사용한다.
- 권한이 확인된 detail의 PUBLIC comment 존재 여부로 먼저 걸러 INTERNAL-only child에는 정책 조회도 하지 않는다. UPDATE와 AI 생성 가능 상태는 독립적이며 기존 서버 write 권한을 넓히지 않는다.
- 수정 전 새 capability 회귀 실패 확인. 수정 후 `AgentTicketReadIntegrationTest` 15, `AgentAiRequestIntegrationTest` 9, `AdminAiIntegrationTest` 2와 `ArchitectureTest` 1 통과. 전역 off/allowlist 제외/개별 feature 전환/PUBLIC 없음/READ-only/audit 실패/BACKGROUND 무감사와 생성 시 stale/feature off 재검증을 포함한다.
- Core bundle, docs-check, diff-check 통과. 전체 backend suite와 실제 모델·배포·부하 검증은 실행하지 않았다. UI Storybook은 후속 UI slice에서 실행한다.
- 성능상 PUBLIC comment가 있는 detail당 indexed singleton/allowlist 조회 한 번이 추가된다. 측정된 지연 개선·무회귀 주장은 하지 않는다. 외부 호출·캐시·DB migration은 없다.

## P13 UI slice plan

AG-F06을 위해 staff AiAssistantPanel에 현재 상세의 AI_SUMMARY/AI_TRIAGE/AI_REPLY_DRAFT를 연결한다. 알 수 없는 capability는 계속 무시하며 기존 READ/UPDATE 타입·decoder·API는 바꾸지 않는다. Reuse: 기존 AI card/SeedButton/SeedNotice/SeedIcon. Compose: 기능별 현재 사용 불가 설명과 정책 재조회 상태. Extend/Add: 없음. source 문서는 staff MCP list/instructions와 AI panel/canonical 문서를 읽었다.

생성이 403으로 거절되면 기존 상세 refreshLatest만 호출한다. AgentTicketWorkspacePage의 성공 상세 이후 재조회는 BACKGROUND이고 editor.refreshEditor를 호출하지 않아 작성안을 초기화하지 않는다. 재조회 실패는 생성 버튼을 잠근 상태와 명시적 재확인을 제공한다. 기존 job 목록/결과 조회·취소·feedback·명시적 삽입은 기존 서버 재인가를 유지한다. 자동 생성·삽입·전송, 운영 정책 수정, 신규 endpoint/DS API/인프라/의존성은 없다.

REQ-AI-001/002, AI-API-001/AI-SRC-001의 frontend 경계 및 UI-002/003/004/005/006을 panel/workspace unit, 실제 MCP story/a11y, mock full-page Chromium에서 검증한다. live provider/운영 배포/서버 감사·DB rollback 전체 재검증은 이번 UI slice에서 수행하지 않는다. 기존 idempotency/ticket version/권한/transaction/retention 계약과 PUBLIC-only 입력 경계를 바꾸지 않는다. UI revert로 복구한다.
