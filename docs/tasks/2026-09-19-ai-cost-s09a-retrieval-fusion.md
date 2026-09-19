# AI 비용 절감 S09a — 현재 문제 질의와 RRF 검색 계약

## Goal

답변 초안 검색이 대화 끝 문자열이 아니라 최신 CUSTOMER 문제를 사용하게 하고, PostgreSQL의 vector 후보와 keyword 후보를 독립적으로 만든 뒤 deterministic RRF로 결합해 현재 공개 KB 근거 최대 5개를 생성에 제공한다.

## Decision and source references

- Decision IDs: D-009, D-025, D-054, D-066.
- Accepted ADRs: 0025, 0049.
- Requirements: REQ-AI-004.
- Plan: `docs/ai-v1/COST_REDUCTION_PLAN.md` S09a and sections 9, 13, 14.
- API operations: AI internal source context read and knowledge-candidate authorization; external Core API is unchanged.
- Verification gates: AI-SRC-001, AI-KB-001, AI-REPLY-001, AI-COST-001, AI-OBS-001.
- 선행 구현: S01 input correctness #184, S02 call receipts #186, S03 evaluation boundary #188, S04 no-evidence skip #190, S05 input revision #192, S06 reply cache/index generation #196, S07 shared execution #198, S08a generation intent #200.

## Query contract

- schema v2/v3 source에서 마지막 `CUSTOMER` comment를 current problem anchor로 사용한다. anchor 뒤 STAFF/SYSTEM comment와 과거 상담사의 답변은 query에 넣지 않는다.
- role이 없는 legacy schema v1 source는 마지막 PUBLIC comment를 호환 fallback으로 사용한다. current problem anchor가 없거나 공백뿐이면 embedding/provider를 호출하지 않고 `NEEDS_REVIEW/NO_APPROVED_KNOWLEDGE`로 끝낸다.
- S10 전에는 제품·기능·오류·증상을 추정하거나 query rewrite model을 호출하지 않는다. current problem에 명시된 문자열만 NFKC와 공백으로 정규화한다.
- embedding query는 current problem 원문을 유지한다. 현재 embedding model의 reviewed tokenizer로 2,048 token을 초과하면 anchor를 잘라 의미를 추측하지 않고 fail closed한다.
- keyword query는 같은 current problem에서 허용된 Unicode letter/number와 `._/-`만 가진 2~64자 token을 최대 16개 추출한다. 반복·제어문자·query 문법은 제거하고, 영문은 case-insensitive하게 deduplicate한다.
- 문자와 숫자가 함께 있는 명시적 error/code token은 최대 8개를 별도로 분류한다. keyword 본문 검색은 OR 정책이며 exact error-code match는 별도 후보로 합친다. 모든 값은 SQL parameter이고 identifier/query syntax로 연결하지 않는다.
- query 원문·token·error code는 ordinary log, metric label, trace/Langfuse metadata, audit detail, cache key에 저장하지 않는다.

## Retrieval and evidence contract

1. AI DB의 같은 workspace와 `PUBLIC` revision만 대상으로 cosine distance 오름차순 vector 후보 20개를 만든다.
2. 같은 범위에서 `simple` text-search keyword 후보와 exact error-code 후보를 합쳐 keyword rank 내림차순 20개를 만든다. `simple`은 한국어 형태소 분석기로 주장하지 않는다.
3. 각 목록의 1-based rank에 `1 / (60 + rank)`를 더한 RRF score로 chunk를 결합한다. 동점은 best modality rank와 chunk UUID로 고정한다. 임의의 confidence 의미나 threshold를 만들지 않는다.
4. fused top 10은 recall 평가 경계다. runtime은 같은 chunk 중복과 같은 article의 인접 ordinal overlap을 제거하면서 상위 최대 5개를 authorization 후보로 보낸다. 후보가 5개보다 적다는 이유로 관련성 낮은 별도 호출을 추가하지 않는다.
5. Backend는 각 후보의 current PUBLIC revision, audience와 상위 공개성을 현재 canonical projection에서 다시 승인한다. 승인 순서는 바꿀 수 없고 승인된 근거가 0개면 generation reservation/call 없이 `NEEDS_REVIEW/NO_APPROVED_KNOWLEDGE`다.
6. generation 직전, result commit과 GET/use의 기존 source/corpus/index generation 재검증을 유지한다. AI SQL 공개 필터는 canonical authorization을 대체하지 않는다.

## Transaction, cost and failure semantics

- query embedding은 기존과 같이 call upper bound를 먼저 예약하고 provider receipt를 즉시 정산한다. retrieval/source authorization/generation 실패는 이미 발생한 embedding 비용을 되돌리지 않는다.
- vector와 keyword SELECT는 하나의 read-only repeatable snapshot에서 실행한다. 그 뒤 publish/withdraw race는 Backend authorization과 result freshness 검증이 fail closed한다.
- provider/tokenizer failure, query over-cap, SQL failure는 bounded body-free error만 저장한다. raw current problem이나 DB exception text를 저장하지 않는다.
- 검색 정책 변경은 `retrievalVersion`을 올려 S06 completed cache와 S07 shared execution이 구 버전 결과에 hit하지 않게 한다. chunking/index generation은 S09b 전까지 바꾸지 않는다.
- candidate limit 20, RRF constant 60, fused evaluation depth 10, evidence maximum 5는 S09a versioned code constant다. generic setting이나 caller-controlled option으로 노출하지 않는다.

## In scope

- latest CUSTOMER 기반 deterministic query builder와 token/error-code allowlist.
- PostgreSQL vector/keyword candidate SQL, exact code 후보, Python RRF와 overlap suppression.
- retrieval version bump와 completed-cache/shared-execution isolation regression.
- synthetic PostgreSQL/pgvector fixtures의 ranking, authorization, no-evidence, injection, limit, withdrawal 회귀 테스트.
- 같은 fixture/corpus에서 기존 weighted query와 신규 후보 SQL의 `EXPLAIN (ANALYZE, BUFFERS)`, Recall@10과 ANN-vs-exact recall을 커밋하지 않는 검증 증빙으로 측정.

## Out of scope

- title/section embedding, section metadata, chunk boundary/index generation/reindex 전환(S09b).
- query rewrite LLM, reranker, semantic cache, Elasticsearch/OpenSearch, 형태소 분석기.
- Core/AI OpenAPI 변경, Backend migration, AI DB migration. 이번 slice는 internal implementation policy와 retrieval version만 바꾸며 persisted shape를 추가하지 않는다.
- 실제 상담사 UI(S08b), 누적 memory(S10), model routing(S11), rewrite(S12), provider prompt cache(S13), batch embedding(S14).
- live provider·사람 답변 품질 또는 실제 비용 절감 주장, merge·deploy.
- 감사·검증 증빙 문서, screenshot, one-off script, query-plan/부하 로그의 Git 커밋.

## Acceptance scenarios

1. CUSTOMER → STAFF → CUSTOMER → SYSTEM 대화에서 query는 마지막 CUSTOMER body만 anchor로 사용하고 trailing SYSTEM과 과거 STAFF body를 포함하지 않는다.
2. legacy role-less source는 마지막 PUBLIC comment를 사용하며 v2/v3에 CUSTOMER가 없으면 마지막 PUBLIC comment fallback을 사용한다.
3. 2,048-token 초과 current problem은 embedding/generation call 0회로 끝나며 잘린 질문으로 검색하지 않는다.
4. punctuation/query operators/control characters가 포함된 입력도 allowlisted bounded parameter만 SQL에 전달하며 schema/data를 바꾸지 않는다.
5. vector-only relevant chunk와 keyword/error-code-only relevant chunk가 각각 후보가 되고, 두 목록에 함께 든 chunk는 RRF 상위로 결합된다.
6. vector/keyword 목록은 각각 최대 20개, fused evaluation은 최대 10개, Backend authorization과 generation evidence는 최대 5개다.
7. 같은 article의 동일/인접 overlapping chunk는 생성 근거에서 중복되지 않으며 순위와 tie-break는 반복 실행에서 같다.
8. 철회되거나 상위 공개성이 사라진 fused candidate는 Backend에서 제거되고, 승인 후보 0개면 query embedding만 정산한 채 generation 0회다.
9. retrieval version이 다른 completed cache/shared execution은 hit/join하지 않는다.
10. gold Recall@10, ANN-vs-exact recall과 query plan은 fake embedding HTTP 200과 별도로 보고하며, 승인된 실제 corpus 측정 전 production 품질은 `NOT ESTABLISHED`다.

## Validation

- `cd ai && .venv/bin/ruff check src tests scripts`
- `cd ai && .venv/bin/mypy`
- `cd ai && .venv/bin/pytest -q`
- PostgreSQL/pgvector-backed current-problem, token bound, SQL injection, 20+20 candidate, RRF tie, overlap, source withdrawal and cache-version tests.
- 동일 synthetic corpus/load의 legacy/new `EXPLAIN (ANALYZE, BUFFERS)`, Recall@10, ANN-vs-exact recall; 산출물은 Git에 넣지 않는다.
- `cd ai && .venv/bin/python scripts/evaluate_fake.py` — 130-case contract regression only; retrieval/model quality claim 금지.
- Backend regression suite because current PUBLIC citation authorization remains canonical even though its contract is unchanged.
- Core/AI OpenAPI bundle or export parity checks to prove no contract drift.
- `make docs-check`, `git diff --check`, latest PR HEAD CI.

## Compatibility and rollout

- schema와 persisted row shape는 바뀌지 않아 migration이 없다. 기존 PUBLIC KB index를 그대로 읽고 S09b 전까지 재색인하지 않는다.
- 새 retrieval version 배포 뒤 이전 cache/shared key는 자연 miss하며 과거 row를 backfill하거나 삭제하지 않는다.
- rollback은 신규 worker 중지 → in-flight job drain → 이전 retrieval code/version 복원 순서다. 새 DB object나 data rollback은 없다.
- legacy schema v1 source와 schema v2/v3 job decoder는 유지한다. external Core/Staff API와 현재 UI payload/response는 바뀌지 않는다.

## Human explanation

S09a는 검색 점수를 한 식으로 섞는 대신 벡터가 찾은 의미상 후보와 PostgreSQL keyword가 찾은 명시적 단어·오류 코드 후보를 각각 제한해서 만든 뒤 순위만 RRF로 합친다. 최신 고객 발화 외의 내용을 추정하지 않고, 최종 후보도 Backend가 현재 공개 근거인지 다시 승인한다. 이 변경은 검색 결과를 재사용하는 key를 바꾸지만 KB 청크 자체는 바꾸지 않으므로 S09b 재색인과 분리된다.
