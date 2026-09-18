# AI 비용 절감 S04 — 근거 없음 생성 생략과 canonical 인용 복원

## Goal

reply 검색 후 현재 PUBLIC 승인을 통과한 KB 근거가 없으면 query embedding 비용만 확정하고 생성 예약·호출 없이 typed `NEEDS_REVIEW`로 끝내며, 근거가 있으면 모델에는 요청 내부 `S1…Sn` 참조만 보내고 서버가 canonical citation을 복원한다.

## Decision and source references

- Decision IDs: D-003, D-008, D-018, D-021, D-054, D-066.
- Accepted ADRs: 0003, 0008, 0018, 0025, 0049.
- Requirements: REQ-AI-003, REQ-AI-004.
- Plan: `docs/ai-v1/COST_REDUCTION_PLAN.md` S04.
- API operation: `authorizeAiResultKnowledgeCitations` in `api/ai-source-api-v1.yaml`.
- Verification gates: AI-API-001, AI-COST-001, AI-KB-001, AI-REPLY-001, AI-SRC-001.
- 이 slice는 Accepted decision을 바꾸지 않는다. Backend의 현재 PUBLIC authorization과 required access audit, AI DB의 call receipt·budget ledger를 그대로 source of truth로 사용한다.

## Actor, source and data boundary

- actor는 등록된 job에 고정된 `INTEGRATION_CLIENT`, source는 `AI_SERVICE`다. caller가 requester, ticket, workspace, article visibility를 선택하지 않는다.
- reply는 Backend가 제공한 `PUBLIC_ONLY` conversation과 AI DB의 PUBLIC index 후보만 사용한다. INTERNAL comment, customer profile, collaboration/child relation, 비공개 KB는 검색·prompt·결과·trace에 들어가지 않는다.
- Backend citation authorization은 요청된 candidate 중 **현재도 exact PUBLIC revision인 항목만 순서를 유지해 반환**한다. 철회·비공개·stale 후보는 `200 items=[]` 또는 부분 `items`로 제거한다.
- 인증 실패, job binding/권한 실패, required audit 실패, Backend unavailable은 빈 근거로 변환하지 않는다. 기존 401/404/409/503과 AI typed failure/superseded 상태를 유지한다.
- 반환하는 각 approved candidate에는 기존 `AI_KNOWLEDGE_ACCESS` required audit를 같은 transaction에서 남긴다. audit persistence가 실패하면 승인 목록을 반환하지 않는다. stale/비공개 후보 본문은 읽거나 감사하지 않는다.

## Workflow and citation contract

- 유한 graph는 `authorize → retrieve → validate_sources → (generate | no_evidence) → validate_output`이다. tool loop, model self-review, 무한 재검색을 추가하지 않는다.
- query embedding은 기존 call reservation/receipt/settlement를 끝낸 뒤 source authorization을 수행한다.
- approved source가 0개면 `prepare_generation`을 호출하지 않고 generation reservation/provider call을 만들지 않는다. job은 `NEEDS_REVIEW`, `NO_APPROVED_KNOWLEDGE`, `result=null`, `canInsert=false`로 완료한다.
- Backend transport/authorization/audit 오류는 `SOURCE_AUTHORIZATION_FAILED`, context/source 변화는 `CONTEXT_SUPERSEDED`로 종료하며 `NO_APPROVED_KNOWLEDGE`로 숨기지 않는다.
- approved source가 일부면 승인된 chunk만 generation 입력과 source map에 남긴다. stale content를 provider에 보내지 않는다.
- provider request의 KB 항목은 `sourceRef`, `title`, `content`만 가진다. article/revision/chunk UUID와 URL/slug는 provider에 보내지 않는다.
- provider reply 전용 내부 schema는 `{answer, sourceRefs}`이며 `sourceRefs`는 최대 8개, 요청 map에 존재하는 고유 `S1…Sn`만 허용한다. unknown ref, duplicate ref, 초과 개수, 빈 인용은 usable reply로 저장하지 않는다.
- AI worker는 ordered approved source map으로 외부 `ReplyDraftResult.citations`의 `articleId/revisionId/chunkId/title/url`을 복원한다. canonical URL은 Backend authorization response만 신뢰한다.
- generation 뒤 선택된 citation을 Backend에서 한 번 더 exact 재인가한다. 하나라도 철회되면 결과를 commit하지 않고 superseded 처리한다. membership validation은 사실성/claim-support 평가를 대신하지 않는다.

## Persistence, cost and observability

- AI migration 006은 successful reply 검증 metadata로 ordered source chunk IDs와 canonical source-map SHA-256 digest를 additive 저장한다. summary/triage와 과거 reply row는 null을 허용한다.
- digest는 ordered `(sourceRef, articleId, revisionId, chunkId, title, canonicalUrl)`의 deterministic bounded representation에서 계산한다. KB 본문은 digest 입력·일반 log·Langfuse metadata에 넣지 않는다.
- no-evidence job은 encrypted result/provenance/source-map metadata를 저장하지 않는다. query call receipt와 settled micro-USD만 job 총비용에 포함한다.
- approved-source generation은 query embedding과 generation을 서로 다른 call/receipt로 유지한다. generation validation 실패도 이미 알려진 비용을 보존한다.
- 비용 절약은 같은 빈/철회 fixture의 generation ledger row 0개로 검증한다. fake provider 결과를 실제 모델 품질·청구 절감 증거로 보고하지 않는다.

## In scope

- `ai-source-api-v1.yaml`의 partial/empty current-PUBLIC authorization semantics.
- Backend current-PUBLIC candidate filtering, stable order, approved-item required audit와 회귀 테스트.
- AI Backend client의 partial authorization 수용과 장애 구분.
- LangGraph no-evidence branch, generation reservation/call bypass, canonical source-map conversion.
- provider reply 전용 compact source refs/schema와 unknown/duplicate/empty ref validation.
- migration 006 source-map validation metadata와 repository mapping/retention cleanup.
- 빈 index, 전부 철회, 일부 철회, source authorization 장애, S99/duplicate, generation 후 철회 회귀 테스트.

## Out of scope

- S09 relevance threshold/RRF 개선과 임의 confidence 점수. 현재 retrieval candidate의 의미 품질은 gold evaluation 전까지 NOT_ESTABLISHED다.
- S05 aiInputRevision, S06/S07 cache/coalescing, S08 재사용 UI, S10 이후 memory, S11 routing, S12 rewrite, S13 prompt cache, S14 embedding batch/reuse.
- S03b sent attribution과 UI 정형 안내. 기존 UI가 typed `NEEDS_REVIEW`와 reason code를 처리하는 계약을 유지한다.
- 유료 provider/LLM judge, 사람 blind review, 실제 청구 절감률 주장, merge·배포.
- 감사 증빙 문서, screenshot, one-off script, raw PUBLIC/KB 원문·로그의 Git 커밋.

## Invariants and failure semantics

- query embedding receipt가 KNOWN으로 commit된 뒤의 source filtering/no-evidence는 해당 call을 UNKNOWN이나 RELEASED로 되돌리지 않는다.
- provider call ID는 generation reservation 이후에만 생성한다. no-evidence 경로에는 generation reservation/provider call/observation이 없다.
- partial authorization의 순서는 요청 candidate 순서를 따른다. duplicate article/revision 또는 chunk/ref는 fail closed한다.
- source authorization endpoint의 200 empty와 401/404/409/503은 서로 다른 의미다. transport/contract error를 정상 근거 없음으로 취급하지 않는다.
- final reauthorization은 selected citation 전체가 exact match일 때만 result commit을 허용한다. stale generation/lease fencing과 cancellation/context checks를 우회하지 않는다.
- 외부 I/O는 ticket mutation transaction 밖에서 실행한다. 이 feature는 ticket/comment를 변경하지 않고 TicketAudit을 만들지 않는다.

## Acceptance scenarios

1. 빈 AI index에서 reply job은 query embedding call 1개만 SETTLED이고 generation reservation/call은 0개이며 `NEEDS_REVIEW/NO_APPROVED_KNOWLEDGE`, null result, `canInsert=false`다.
2. 검색 후보가 모두 철회되면 Backend는 200 empty를 반환하고 raw KB를 provider에 보내지 않으며 동일한 no-evidence 결과로 끝난다.
3. 일부 후보만 current PUBLIC이면 승인된 subset만 stable `S1…Sn` map과 provider request에 포함되고 반환 항목마다 required audit가 남는다.
4. authorization API 401/404/503 또는 malformed response는 `NO_APPROVED_KNOWLEDGE`가 아니라 source authorization failure이며 generation은 호출되지 않는다.
5. provider에는 sourceRef/title/content만 전달되고 article/revision/chunk UUID, slug, URL은 없다.
6. provider가 `S1`을 반환하면 worker가 해당 approved source의 canonical citation으로 복원하고 ordered map digest/chunk IDs를 저장한다.
7. `S99`, duplicate ref, 8개 초과, 빈 refs는 result를 저장하지 않고 `MODEL_OUTPUT_INVALID` typed review로 끝나며 known generation cost는 보존한다.
8. generation 뒤 선택 revision이 철회되면 final reauthorization이 result commit을 막고 job을 superseded 처리한다.
9. source-map digest는 같은 ordered map에서 결정적이고 순서 또는 canonical metadata가 바뀌면 달라지며 KB body는 digest 입력이 아니다.
10. source authorization audit DB failure는 503으로 fail closed하고 승인 response/provider generation을 허용하지 않는다.

## Validation

- `./gradlew test --tests '*AiKnowledge*' --tests '*AgentAiRequestIntegrationTest*'`
- `cd ai && uv sync --frozen`
- `cd ai && .venv/bin/ruff check src tests scripts`
- `cd ai && .venv/bin/mypy`
- `cd ai && .venv/bin/pytest -q`
- Focused PostgreSQL/Redis tests for empty/partial authorization, no generation reservation, exact receipt settlement, compact source refs, final withdrawal, digest persistence and migration.
- `cd ai && .venv/bin/python scripts/evaluate_fake.py` — contract evidence only.
- generated AI internal OpenAPI parity and committed `ai-source-api-v1.yaml` contract checks.
- `make docs-check`
- `git diff --check`
- latest PR HEAD CI.

## Compatibility and migration

- AI migration 006 is additive. New validation metadata columns are nullable so existing summary/triage and historical reply rows remain readable. rollback may stop writing them without deleting receipts/results.
- `authorizeAiResultKnowledgeCitations` keeps the request and response shapes but changes 200 semantics from all-or-conflict to ordered current-PUBLIC subset, including empty. Existing AI worker must not be deployed after the new Backend semantics until this stacked code PR is deployed together; the current worker fails closed on a partial response rather than generating from stale data.
- external `ReplyDraftResult` and Core staff API citation shape remain unchanged. Compact `sourceRefs` exists only between AI worker and provider.
- no-evidence continues the existing `NEEDS_REVIEW/NO_APPROVED_KNOWLEDGE` client contract; no new UI enum is introduced.

## Human explanation

검색에 비용이 들었다는 사실과 답변을 만들 근거가 있다는 사실은 다르다. S04는 검색 비용을 정확히 남긴 뒤 current PUBLIC 근거가 없으면 생성 자체를 시작하지 않는다. 근거가 있을 때도 모델은 짧은 요청-local 참조만 선택하고, 실제 UUID와 URL은 권한을 재검증한 서버 map에서 복원한다. 이 방식은 근거 없는 답변 비용과 모델이 citation identity를 만들어 내는 위험을 함께 줄이면서 현재 유한 graph·PostgreSQL 원장·Backend authorization 경계를 유지한다.
