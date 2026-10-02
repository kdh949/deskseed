# 티켓별 AI 생성 가능 상태 계약

상태: CONTRACT_DEFINED. 이 계약 PR에는 서버/화면 구현이 없다. 현행 `getAgentTicket` READ/UPDATE 계약의 FROZEN은 유지하고, 새 의미는 `x-deskseed-planned-capabilities`의 BLUEPRINT로 구분한다. 후속 runtime parity 회귀 후 이 blueprint를 실제 capabilities 설명·예시로 승격한다.

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

계약·합성 응답 예시와 후속 구현/검증 조건. 후속 서버는 기존 AI feature gate를 재사용하며 기존 audited ticket detail 안에서 최소 정책 projection만 추가한다. endpoint·DB migration·새 infrastructure는 필요하지 않다.

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
