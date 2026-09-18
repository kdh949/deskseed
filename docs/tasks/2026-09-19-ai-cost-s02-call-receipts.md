# AI 비용 절감 S02 — 실제 사용량·가격·호출 정산

## Goal

AI provider의 실제 응답 usage와 실제 모델을 호출별 immutable receipt로 먼저 저장하고, job lease와 분리된 멱등 정산으로 예약·알려진 비용·미정 비용을 사실대로 보존한다.

## Decision and source references

- Decision IDs: D-003, D-008, D-009, D-013, D-018, D-021, D-049, D-050, D-054, D-066.
- Accepted ADRs: 0002, 0003, 0005, 0008, 0013, 0018, 0025, 0049.
- Requirements: REQ-AI-002, REQ-AI-003, REQ-AI-005.
- Plan: `docs/ai-v1/COST_REDUCTION_PLAN.md` S02.
- Verification gates: AI-API-001, AI-LIFE-001, AI-COST-001, AI-OBS-001, AI-RET-001.
- Current pricing references: [OpenAI ChatGPT rate card](https://help.openai.com/en/articles/20001415-chatgpt-rate-card-enterprise-token-based-pricing), [API usage contract](https://platform.openai.com/docs/api-reference/usage), and the reviewed S02 price table. Live provider billing agreement remains unverified until a capped canary is approved.
- This slice does not change an Accepted decision. It strengthens D-066's cost, retry and recovery evidence inside the isolated AI boundary.

## Actor, source and data boundary

- Interactive budget owner: bound STAFF requester and workspace; execution remains bound to job/generation/lease.
- Indexing budget owner: SYSTEM and workspace; no staff identity is invented.
- Provider receipt fields are allowlisted bounded metadata only. Prompt, PUBLIC conversation, KB content, structured result body, secret, raw HTTP headers and raw response are not stored in the ledger or logs.
- Langfuse is not the source of truth for cost. Export remains body-free and cannot make job settlement fail.

## Product and persistence contract

- AI migration 004 adds a provider-call ledger keyed by `call_id`, linked one-to-one to the existing reservation and unique `operation_key`.
- Call lifecycle is `RESERVED -> DISPATCHING -> RESPONDED`; uncertain calls end `UNKNOWN`. Settlement is separately `PENDING | SETTLED | UNKNOWN | CONFLICT`.
- A call receipt stores provider request ID, requested alias, actual model, usage schema version/status, exclusive input buckets, billed output, pricing version, service tier, context price band, known cost, overrun and response time.
- The existing cost ledger keeps reservation accounting. A known response may settle above its reservation; the actual amount and overrun are stored rather than rejected or truncated.
- Exact duplicate settlement is a no-op. The same call with different immutable receipt facts becomes `CONFLICT` and requires reconciliation; it never starts another provider call.
- Receipt persistence and settlement do not require the old job lease. Result commit still requires job/generation/lease fencing and current Backend authorization.
- Unknown reservations remain chargeable debt across the date boundary until reconciled or explicitly released with evidence. Daily limits count current-day spend plus all unresolved prior-day UNKNOWN reservations.

## Usage normalization and pricing

- Normalized exclusive buckets are `inputUncached`, `inputCacheRead`, `inputCacheWrite`, and `outputBilled`.
- OpenAI/LiteLLM inclusive input uses `inputUncached = inputTotal - cacheRead - cacheWrite`; exclusive provider fields are not subtracted twice.
- Top-level and nested cache fields have a documented precedence. When both are present they must agree; disagreement, negative values, missing totals, or cache buckets exceeding input produce `INCONSISTENT` or `UNAVAILABLE`, never a clamped KNOWN zero.
- `outputBilled` is the provider's billed output total. Nested reasoning tokens are evidence inside that total and are not added again.
- `pricing-v2.json` is immutable and adds Luna cache-write pricing from the approved plan. Version, effective time, source, standard tier and short-context band are explicit. Batch, priority, regional surcharge and long-context combinations are rejected before dispatch.
- Cost uses integer micro-USD with ceiling after the full numerator. Actual model and supported alias mapping select the price; the requested alias cannot overwrite a different actual model.

## Reservation and call flow

1. Build the complete provider request, including system prompt, options, conversation, JSON schema and approved KB.
2. Count the serialized request with the model tokenizer. If the configured live alias lacks a verified tokenizer or supported price combination, fail before reservation/dispatch. UTF-8 byte or character division is not a live budget estimate.
3. Reserve a conservative maximum using measured input, output cap and applicable cache-write maximum.
4. Create the call row, mark `DISPATCHING` immediately before the provider SDK call, and use `num_retries=0`.
5. On a provider response, extract and persist the receipt before parsing JSON, citations or refusal semantics. Settle known usage immediately.
6. Validate the business result. Invalid JSON/citation/refusal becomes `NEEDS_REVIEW` with no usable result body while the known call cost stays settled.
7. On transport/process uncertainty, preserve the reservation and call as `UNKNOWN`; do not automatically call the provider again.
8. Query embedding is reserved and settled before retrieval/generation continues. Index embedding records each actual provider call rather than collapsing multiple requests into one unverifiable token sum.

## Concurrency, idempotency and failure semantics

- Lock order is workspace/day, actor or SYSTEM, then operation/call; one transaction performs limit check and reservation creation.
- Twenty concurrent reservations cannot overspend a configured workspace/actor/job limit.
- Operation keys include job/execution generation, stage and bounded attempt. A pre-existing RESERVED/DISPATCHING/UNKNOWN operation blocks re-dispatch.
- Provider response after cancel, authorization change or lease loss still settles once, but cannot commit a result.
- Receipt DB failure after a response makes accounting UNKNOWN and result unusable; replay is reconciliation, not provider retry.
- Search/generation failure after a successful embedding does not undo the embedding settlement.
- Reservation overrun blocks later admission according to actual spend but does not hide the charged call.

## In scope

- `ProviderResponseEnvelope` and allowlisted call receipt types for generation and embedding adapters.
- LiteLLM 1.101 usage normalization fixtures covering top-level, nested, duplicate, missing and inconsistent values.
- `pricing-v2.json`, strict price/tier/band/model matching, integer cost and complete-request token estimate.
- AI migration 004 provider-call ledger and additive cost-ledger settlement/overrun columns.
- Generation/query/index call lifecycle, immediate receipt persistence, late/idempotent settlement and prior-day UNKNOWN debt.
- Regression tests for invalid output, post-response cancel/stale lease, DB failure, overrun and concurrent reservations.

## Out of scope

- Paid live provider call or invoice reconciliation; these need an explicit model, sample count and USD cap.
- S03 Langfuse trace linkage and current-server evaluation dataset.
- S04 no-evidence generation bypass, S06+ reuse, S11 low-cost routing and S13 prompt cache activation.
- UI, deployment, merge, Batch API, priority/regional/long-context pricing and provider billing webhooks.

## Acceptance scenarios

1. Inclusive input 1,000 with cache read 800 and write 200 normalizes to uncached 0 without double counting.
2. Nested/top-level cache fields that agree produce one KNOWN receipt; disagreement is INCONSISTENT and cost remains unknown.
3. Reasoning tokens already included in output are not added to `outputBilled`.
4. Missing/negative/impossible usage persists response metadata with UNAVAILABLE/INCONSISTENT and does not report zero cost.
5. Invalid JSON, invalid citation and refusal after a known response preserve SETTLED cost and finish without usable result.
6. Query/index embedding cost remains settled when later retrieval, generation or source validation fails.
7. A response arriving after cancel or lease loss settles once but cannot overwrite terminal/current result state.
8. An actual cost above reservation is stored with overrun; later exact settlement is a no-op and conflicting settlement is flagged.
9. A prior-day UNKNOWN reservation is included in today's admission debt.
10. Twenty simultaneous reservations respect workspace, actor and job caps without overspend.
11. Provider receipt/log/trace sentinels contain no prompt, PUBLIC/INTERNAL body, KB text, secret or raw response.
12. Every synthetic fixture has an exact integer micro-USD expectation. No fake fixture is described as invoice or quality evidence.

## Validation

- `cd ai && uv sync --frozen`
- `cd ai && .venv/bin/ruff check src tests scripts`
- `cd ai && .venv/bin/mypy`
- `cd ai && .venv/bin/pytest -q`
- Focused database tests for migration, lifecycle, late/idempotent/conflicting settlement, prior-day UNKNOWN and 20-way reservation concurrency.
- `cd ai && .venv/bin/python scripts/evaluate_fake.py`
- `make docs-check`
- `git diff --check`
- Latest PR HEAD CI.

## Migration, rollback and compatibility

- Migration 004 is additive. Existing ledger rows remain valid with nullable call linkage and zero overrun only where cost is known.
- New code accepts legacy jobs but all new provider calls use the call ledger and pricing v2. Existing pricing v1 history is never overwritten.
- Rollback stops new admission, keeps receipt/settlement reconciliation running, drains known calls, then rolls worker code back. Provider-call and overrun facts are not dropped.

## Human explanation

Reservation is a safety limit, receipt is provider evidence, settlement is accounting, and result commit is product state. They cannot be represented by one status or one transaction across a network call. S02 therefore lets a stale worker record a known charge without letting it publish a stale answer, and treats unknown billing as debt instead of free usage.
