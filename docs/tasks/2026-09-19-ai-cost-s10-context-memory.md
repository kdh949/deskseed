# AI 비용 절감 S10 — 출처 보존 누적 context memory

## Goal

반복되는 긴 PUBLIC 대화에서만 과거 대화를 출처가 있는 암호화 context memory로 압축하고 최신 고객 질문은 원문으로 유지하여, stale 또는 손익이 불명확한 요약을 답변 근거로 쓰지 않으면서 reply 입력 비용을 줄인다.

## Decision and source references

- Decision IDs: D-009, D-066.
- Accepted ADRs: 0049의 `Source-backed context memory` 변경.
- Requirements: REQ-AI-003, REQ-AI-004.
- Plan: `docs/ai-v1/COST_REDUCTION_PLAN.md` S10 and sections 2, 3, 5, 6, 10, 13, 14.
- External API operation: 없음. 기존 `readAiPublicContext`, AI job create/get 계약과 typed reply result는 바꾸지 않는다.
- Verification gates: AI-SRC-001, AI-LIFE-001, AI-COST-001, AI-REPLY-001, AI-OBS-001, AI-RET-001.
- 선행 구현: S09b immutable PUBLIC KB artifact PR #204.

## Actor and source boundary

- 상담사 요청은 기존 STAFF session/CSRF/expected-actor guard를 사용한다. source read는 고정 `INTEGRATION_CLIENT`의 registered job binding으로만 수행한다.
- memory scope는 server-owned workspace key, requester staff ID, ticket UUID다. caller가 memory ID, source ref, coverage 또는 digest를 제출하지 않는다.
- memory 입력은 current Backend source API가 반환한 ordered PUBLIC comments뿐이다. INTERNAL, collaboration note, child relation, customer profile, audit content와 비공개 KB는 memory build request, stored payload, reply prompt, log/trace에 들어가지 않는다.
- memory 사용도 새 민감 read를 대체하지 않는다. worker는 매 job에서 current source authorization과 required access audit를 다시 수행한다.

## Memory contract

- 화면에 반환되는 `ticket.summary` 결과는 재사용하지 않는다. `context-memory-v1`은 reply 전용 내부 파생물이다.
- 암호화 payload는 `confirmedFacts`, `attemptsAndOutcomes`, `openQuestions`를 가지며 모든 항목은 하나 이상의 request-local PUBLIC `C<n>` ref를 가진다. provider output은 별도 `conflictSourceRefs`를 반환하고, server는 conflict가 비어 있을 때만 memory를 저장한다. server는 unknown, duplicate, coverage 밖 ref를 거부한다.
- 저장 metadata는 memory ID, owner scope, `coveredThroughSequence`, covered PUBLIC comment ID 집합의 digest, source-prefix digest, schema/policy/prompt/model version, update count, 생성·만료·무효화 시각과 bounded reason이다. body 파생 payload와 source ref 목록은 AES-GCM ciphertext 안에 둔다.
- AAD는 memory ID와 owner scope, schema/policy version에 결합한다. memory TTL은 24시간이며 AI result 7일 상한을 넘지 않고 hit/update로 연장하지 않는다.
- current PUBLIC prefix의 ID, sequence, role, timestamp, body를 canonicalize한 digest가 stored digest와 일치해야 decrypt/use할 수 있다. source edit/delete/visibility withdrawal/reorder, unknown ref, decryption/schema failure는 fail closed invalidation이다.
- 한 owner/ticket에는 활성 memory 하나만 둔다. 갱신은 row lock과 expected memory version CAS로 직렬화하며, provider/network I/O는 DB transaction 밖에서 수행한다.

## Selection, update and break-even

1. current 전체 PUBLIC projection에서 최신 CUSTOMER comment와 그 뒤 suffix를 raw protected range로 정한다. CUSTOMER가 없으면 최신 PUBLIC comment 하나를 보호한다.
2. 보호 suffix가 reply raw 상한에 들지 않으면 provider call 0회로 `INPUT_TOO_LONG`이다. memory로 최신 요청을 자르거나 요약하지 않는다.
3. 전체 대화가 기존 raw 상한 안이면 memory call을 추가하지 않는다. 기존 deterministic raw path를 사용한다.
4. 유효 memory가 있으면 covered prefix를 exact digest로 재검증하고 이후 PUBLIC delta와 최신 raw suffix를 구분한다. invalid memory는 사용하지 않고 bounded reason으로 무효화한다.
5. 새 build 또는 update는 승인된 KB가 존재하여 final reply generation이 가능한 현재 request 안에서만 수행한다. no-evidence, policy disabled, cancelled request와 선제 batch에는 memory call이 없다.
6. 새 build는 최신 raw suffix 전 prefix를 다룬다. update는 stored coverage 뒤부터 새 protected suffix 전까지의 delta만 기존 memory와 함께 보낸다. 세 번째 update마다 full current prefix에서 재구성하여 누적 변형을 제한한다.
7. server는 reviewed tokenizer와 current pricing catalog로 `예상 반복 횟수 × (raw reply input upper bound - memory reply input upper bound)`와 `memory build/update input+output upper bound + full rebuild share`를 micro-USD로 계산한다. 전자가 엄격히 클 때만 call을 예약한다. 값이 없거나 0 이하면 raw path다.
8. 예상 반복 횟수와 activation은 typed server setting으로 두고 기본 `off`다. test mode는 합성 손익 fixture에만 쓰며 production enable은 live baseline과 품질 gate 없이는 허용하지 않는다.
9. context-memory call과 final reply generation은 합쳐서 generation 최대 2회다. 각 call은 기존 budget reservation, receipt-first settlement, UNKNOWN no-retry, cancellation/lease fencing을 사용한다.

## Reply input contract

- provider request는 `publicConversationMemory`와 `recentPublicConversation`을 별도 JSON field로 보낸다. memory 항목의 source ref는 current request의 same PUBLIC comment map에서만 해석된다.
- 최신 CUSTOMER 발화와 이후 PUBLIC comments는 전부 raw다. memory가 cover한 comment를 raw recent list에 중복하지 않는다.
- retrieval query는 최신 raw CUSTOMER 문제에서 결정적으로 구성하며 memory 내용을 query rewrite로 사용하지 않는다.
- memory가 없거나 사용할 수 없으면 기존 reply-v2 raw selection과 typed failure semantics를 유지한다.
- final reply의 KB citation membership/freshness 검증은 memory와 무관하게 기존 S04/S09 경로를 그대로 수행한다. conversation source ref는 KB citation이 아니다.

## In scope

- ADR 0049 변경과 이 task brief.
- AI additive migration의 encrypted context-memory row, owner scope, TTL/invalidation/CAS constraints.
- strict context-memory schema/prompt/provider adapter, source-ref validator와 request builder.
- reply workflow의 post-KB-authorization on-demand memory build/update and break-even gate.
- source drift, deletion, visibility/reorder simulation, unknown refs, expired/corrupt ciphertext, concurrent update, no-evidence/no-benefit/short-context regression tests.
- fake provider cost fixture and body-free observability metadata. 실제 provider 품질·비용 절감 claim은 제외한다.

## Out of scope

- `ticket.summary` UI/result를 memory로 변환하거나 memory를 staff/customer API에 노출.
- latest protected suffix 자체의 요약, INTERNAL/custom profile input, speculative batch precompute.
- provider의 `conflictSourceRefs`를 완전한 semantic contradiction detector나 사실 보증으로 간주하는 것. 합성 conflict fixture는 검토 경계를 확인할 뿐 live 사람 평가를 대체하지 않는다.
- live provider, 실제 PUBLIC 원문 export, paid canary, production activation, merge·deploy.
- S11 model routing, S12 rewrite, S13 provider prompt cache, S14 embedding reuse/batch.
- 감사 증빙 문서, screenshot, one-off scripts/logs의 commit.

## Invariants and failure semantics

- canonical source와 authorization은 Backend가 소유한다. AI DB memory의 존재나 digest 일치는 권한 증거가 아니다.
- provider/network I/O는 memory row transaction 밖이다. validated payload와 source revision 재검증 뒤에만 CAS commit한다.
- source mismatch, unknown ref, expired/corrupt memory, stale CAS는 stale memory 사용 없이 invalidation 또는 raw fallback이다. provider가 conflict source를 반환하면 memory를 저장하지 않고 `NEEDS_REVIEW`로 종료하여 상충 내용을 압축하거나 답변하지 않는다.
- memory provider receipt가 저장된 뒤 schema/source validation이 실패해도 실제 비용은 보존한다. dispatch outcome UNKNOWN은 자동 재호출하지 않고 memory와 reply result를 저장하지 않는다.
- memory 생성 실패를 숨기려고 과거 원문을 추가 누락하지 않는다. 기존 bounded raw path가 안전하면 fallback하고, 보호 suffix가 너무 길면 `INPUT_TOO_LONG`이다.
- cache/shared execution leader만 memory build/update와 비용을 소유한다. follower job에 비용이나 memory update를 복제하지 않는다.
- memory text/source content는 ordinary log, metric label, operation reason, publication history 또는 Langfuse payload에 기록하지 않는다.

## Acceptance scenarios

1. 8KB 이하 대화와 손익 0 이하 대화는 memory provider call 0회이고 기존 raw reply 경로를 사용한다.
2. 긴 반복 PUBLIC 대화에서 계산된 절감 upper bound가 build/update 비용보다 클 때만 context-memory call 1회와 reply generation 1회가 발생하며 최신 CUSTOMER suffix는 raw input에 있다.
3. memory의 모든 항목이 coverage 안의 known `C<n>` ref를 가지며 unknown/빈/중복 ref 또는 non-empty conflict output은 receipt 비용을 보존한 채 memory 저장과 reply generation을 중단한다.
4. 기존 memory 뒤 새 PUBLIC comments가 추가되면 delta만 update input에 들어가고 coverage/digest/version이 CAS로 전진한다. 세 번째 update는 full prefix rebuild다.
5. covered comment의 본문/역할/시간/순서 변경, 삭제, PUBLIC 철회 또는 source ref 불일치는 memory를 무효화하고 ciphertext를 reply prompt에 넣지 않는다.
6. latest protected suffix가 raw 상한을 넘으면 memory 유무와 무관하게 generation 0회 `INPUT_TOO_LONG`이다.
7. no approved KB는 query embedding 비용만 정산하고 memory/reply generation 0회다.
8. concurrent same-input jobs는 shared execution leader 하나만 memory call과 CAS update를 수행하고 follower는 독립 ciphertext result를 받는다.
9. expired/corrupt ciphertext, stale memory version, source change after memory response와 cancellation/lease loss는 result commit을 막는다.
10. logs/traces/metrics에는 body, memory text, source ref 원문 대응값이 없고 stage/version/cost/reuse outcome만 bounded metadata로 남는다.

## Validation

- `cd ai && .venv/bin/ruff check src tests scripts`
- `cd ai && .venv/bin/mypy`
- `cd ai && .venv/bin/pytest -q`
- PostgreSQL-backed migration, encryption/AAD, TTL, invalidation, CAS/concurrency, source-drift and cost-ledger tests.
- `cd ai && .venv/bin/python scripts/evaluate_fake.py` — contract regression only; quality/cost saving evidence is `NOT_ESTABLISHED`.
- `cd ai && .venv/bin/python scripts/export_openapi.py` and generated internal OpenAPI parity. No source/Core operation changes are expected.
- `make docs-check`, `git diff --check`, latest PR HEAD CI.

## Compatibility and migration

- external Core/Staff and Backend-to-AI OpenAPI are unchanged. Strict existing clients receive no new fields.
- additive migration creates context-memory storage only. Default mode `off` preserves current reply path and deployment behavior.
- deploy order is migration → AI code with mode off → synthetic/fake and live baseline review → explicit production enable in a later authorized configuration change.
- rollback is mode off/old application while preserving encrypted additive rows until normal retention. No automatic down/drop or production data deletion is performed.

## Human explanation

긴 대화를 매번 Terra 입력으로 보내는 대신 오래된 PUBLIC 구간을 Luna로 한 번 압축하면 반복 요청에서 비용을 줄일 수 있다. 그러나 요약은 원문이 아니므로 화면용 summary를 그대로 신뢰하지 않고, 각 항목의 PUBLIC source ref와 exact prefix digest를 저장해 원문이 조금이라도 달라지면 폐기한다. 최신 고객 질문은 항상 raw로 남기며, 첫 호출 비용까지 포함한 보수적 손익이 양수일 때만 memory를 만든다. live 품질·실비 기준선이 없으므로 기능은 기본 off이고 fake 통과를 절감 증거로 보고하지 않는다.
