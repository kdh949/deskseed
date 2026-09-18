# AI 비용 절감 S03a — 본문 없는 관측과 실제 데이터 평가 기반

## Goal

AI job과 provider call에 저장된 결정적 trace/observation identity를 부여하고, Langfuse 장애를 제품 실행·정산과 격리하며, 현재 서버의 승인된 PUBLIC 대화·공개 KB로 암호화된 30일 이내 평가 snapshot을 안전하게 준비한다.

## Decision and source references

- Decision IDs: D-003, D-008, D-009, D-013, D-018, D-021, D-049, D-050, D-054, D-066.
- Accepted ADRs: 0002, 0003, 0005, 0008, 0013, 0018, 0025, 0047, 0048, 0049.
- Requirements: REQ-AI-003, REQ-AI-005.
- Plan: `docs/ai-v1/COST_REDUCTION_PLAN.md` S03a. S03b 전송 귀속은 후속 UI 단계다.
- Verification gates: AI-API-001, AI-COST-001, AI-OBS-001, AI-RET-001.
- 이 slice는 Accepted decision을 바꾸지 않는다. D-066의 body-free observability와 평가 데이터 경계를 실행 가능한 계약으로 고정한다.

## Actor, source and data boundary

- AI 실행 actor/source/request/correlation binding은 기존 job 계약을 유지한다. telemetry는 권한 또는 감사 근거가 아니다.
- job trace ID는 저장된 job UUID의 32자리 hex다. provider call observation ID는 call UUID의 32자리 hex이며, job call은 같은 trace ID를 공유한다. SYSTEM indexing call은 reservation UUID에서 독립 trace ID를 만든다.
- upstream `traceparent`는 Deskseed trace ID를 대체하지 않는다. 유효한 upstream trace/span ID만 bounded link metadata로 보존하고 원문 header와 `tracestate`를 export하지 않는다.
- Langfuse allowlist에는 실행 모드, 환경, feature/stage, 실제 모델, prompt/schema/policy/retrieval/index/config version, exclusive token buckets, micro-USD 비용, reuse/escalation/failure stage와 bounded outcome만 허용한다.
- prompt, comment/KB/result body, title/subject, customer/staff identity, ticket/request/job identifier의 metric label, provider request ID, Authorization/cookie/secret, raw exception/error text를 export하지 않는다.
- current-server evaluation source는 PUBLIC comment와 `PUBLISHED + PUBLIC` KB current revision만 허용한다. INTERNAL, customer profile, collaboration/child relation, protected audit와 비공개 KB는 제외한다.

## Trace and exporter contract

- AI migration 005는 기존/new job과 provider call에 stable trace/observation identity를 저장하고 shape/uniqueness를 검증한다.
- job start/update/end/flush, provider observation, feedback score export는 adapter 경계에서 예외를 흡수한다. canonical job/result, cost receipt/settlement, feedback outbox 상태를 telemetry 성공으로 대체하지 않는다.
- exporter 상태는 bounded cumulative drop/retry counters로 internal status에 노출한다. high-cardinality identity는 trace context에만 있고 metric label/counter key에는 없다.
- provider observation은 receipt가 canonical AI DB에 저장·정산된 뒤 best-effort로 export한다. known usage와 cost만 숫자로 보내며 UNKNOWN/INCONSISTENT를 0으로 변환하지 않는다.
- feedback는 job과 같은 trace ID에 score를 연결한다. export 실패는 existing outbox retry를 사용하고 성공으로 mark하지 않는다.
- automatic model integration은 사용하지 않는다. adapter가 `input`, `output`, prompt/body를 Langfuse SDK에 전달하지 않는 sentinel test를 유지한다.

## Evaluation snapshot contract

- 1차 inventory는 원문을 출력하지 않는 집계만 수행한다. 가용 건수는 품질 표본 완료나 모델 승격 근거가 아니다.
- snapshot 입력은 승인된 source에서 읽은 immutable source revision, PUBLIC 원문, feature와 problem-family key를 포함한다. 같은 ticket/문제군은 tune 70%/holdout 30%를 넘나들지 않는다.
- snapshot은 authenticated encryption으로만 저장하고 plaintext 임시 파일을 만들지 않는다. encryption key는 snapshot과 분리하며 Git, log, Langfuse, PR artifact에 넣지 않는다.
- 기본 TTL은 30일이며 더 짧게 설정할 수 있다. source 삭제·비공개 전환·철회가 확인되면 만료 전에도 해당 case를 즉시 invalid 처리하고 provider 전송 대상에서 제외한다.
- summary 60, triage 40, search/reply 200은 최초 목표다. 실제 데이터 분포, long conversation, 요구 변경, 실패 조치, 상충 KB, 근거 없음, 한국어/오탈자/오류 코드의 부족분은 그대로 보고한다.
- 사람 label/gold evidence/금지 주장은 원문과 분리한 encrypted payload에 둔다. 합성 보안 fixture와 fake 130건은 actual PUBLIC 품질 점수와 섞지 않는다.
- live evaluator는 snapshot/version/model/prompt/pricing/retrieval 설정과 사례 수·USD cap이 명시된 경우에만 provider를 호출한다. 현재 slice는 유료 호출을 수행하지 않는다.

## Concurrency, idempotency and failure semantics

- 동일 job/call은 재시도·recovery에서도 같은 trace/observation ID를 사용한다. 다른 immutable call만 새 observation ID를 가진다.
- telemetry 실패가 provider receipt 기록 전후의 transaction 또는 result fencing을 변경하지 않는다.
- feedback export lease/retry는 기존 monotonic revision 계약을 유지한다. telemetry adapter false/exception은 retryable export failure이며 feedback 자체를 잃지 않는다.
- snapshot case ID와 family split은 source identity/version에서 결정적으로 생성한다. exact replay는 같은 encrypted logical dataset으로 수렴하고 source revision 충돌은 덮어쓰지 않는다.
- snapshot expiry/invalidation은 model call 전에 fail closed한다. 만료된 원문을 평가 편의를 위해 다시 사용하지 않는다.

## In scope

- migration 005 trace/observation identity와 repository mapping.
- metadata-only job/provider/feedback Langfuse adapter, explicit IDs, upstream link parsing, failure isolation and counters.
- `/internal/v1/status`의 body-free telemetry exporter 상태.
- encrypted evaluation snapshot codec/manifest, deterministic family split, TTL/invalidation validation과 재사용 가능한 tests.
- current-server body-free inventory와, 별도 비커밋 평가 저장소에서의 snapshot 준비 절차 검증.

## Out of scope

- S03b PUBLIC comment sent attribution, edit distance, candidate lineage와 UI.
- 유료 live provider/LLM judge 호출, 사람 품질 판정, 모델 승격 또는 비용 절감 효과 주장. 이는 model·sample count·USD cap 승인이 필요하다.
- Langfuse Cloud 실제 수신 증거와 AI runtime 배포. 현재 서버에는 AI service/AI DB가 없다.
- S04 이후 generation bypass, cache, routing, batching, prompt-cache와 UI 고도화.
- 원문을 포함한 audit/evidence 문서, screenshot, one-off 추출 script, snapshot, log의 Git 커밋.

## Acceptance scenarios

1. 같은 job의 실행·feedback은 job UUID hex trace ID를 공유하고 각 provider call은 call UUID hex observation ID를 쓴다.
2. indexing call은 job 없이도 reservation 기반 stable trace ID와 call observation ID를 가진다.
3. 유효한 upstream traceparent는 bounded link metadata로 남고 invalid/header 원문과 tracestate는 export되지 않는다.
4. job start/update/end/flush 각각의 Langfuse 예외가 job transition, result commit과 cost settlement를 실패시키지 않고 해당 drop counter를 증가시킨다.
5. feedback score 실패는 exported revision을 올리지 않고 retry 상태와 bounded counter만 남긴다.
6. provider receipt를 먼저 저장한 뒤 KNOWN exclusive usage/actual model/cost를 export한다. UNKNOWN/INCONSISTENT usage를 zero-cost로 보고하지 않는다.
7. sentinel prompt/comment/KB/result/identity/secret 값이 SDK call arguments, logs와 status response에 없다.
8. snapshot plaintext는 파일로 생성되지 않고 ciphertext 변조·wrong key·expired TTL에서 fail closed한다.
9. 같은 problem family의 모든 case가 tune 또는 holdout 한쪽에만 들어간다.
10. source withdrawal/invalidation은 남은 TTL과 관계없이 case를 model-call 대상에서 제외한다.
11. fake 130건은 contract evidence로만 표시되고 actual quality/cost result로 집계되지 않는다.
12. current-server inventory가 300 목표에 못 미치거나 strata가 부족하면 해당 route를 NOT_ESTABLISHED로 유지한다.

## Validation

- `cd ai && uv sync --frozen`
- `cd ai && .venv/bin/ruff check src tests scripts`
- `cd ai && .venv/bin/mypy`
- `cd ai && .venv/bin/pytest -q`
- Focused tests for explicit trace/call identity, allowlist, every exporter failure point, counters, receipt-before-export ordering, encrypted snapshot TTL/tamper/family split/invalidation.
- `cd ai && .venv/bin/python scripts/evaluate_fake.py` — contract evidence only.
- Current-server body-free inventory query. Raw source and evidence outputs are not committed.
- `make docs-check`
- `git diff --check`
- Latest PR HEAD CI.

## Migration, rollback and compatibility

- Migration 005 is additive and deterministically backfills trace/observation IDs. Existing job/feedback/call identity and S02 settlement facts remain unchanged.
- Langfuse disabled or unavailable remains a supported state. Rollback may stop exporter code while retaining stored IDs, receipts and feedback retry rows.
- Evaluation snapshot format is versioned. Removing the evaluator deletes encrypted snapshots and keys through the operator retention procedure; it does not mutate primary ticket/KB rows.

## Human explanation

관측은 실행을 설명하지만 실행을 결정하지 않는다. S03a는 같은 job·call을 일관된 ID로 연결하되, 먼저 AI DB에 정산 사실을 남긴 뒤 본문 없는 사본만 best-effort로 보낸다. 실제 PUBLIC 원문은 품질 평가에 필요하지만 일반 telemetry가 아니므로 별도 암호화 snapshot, 짧은 TTL, 철회 재검증과 유료 호출 예산 승인을 요구한다.
