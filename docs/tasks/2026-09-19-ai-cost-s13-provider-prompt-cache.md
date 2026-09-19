# AI 비용 절감 S13 — provider prompt cache

## Goal

반복되는 repository-owned AI 지침 prefix만 provider prompt cache 대상으로 표시하고, 최소 길이·쓰기/read/일반 입력/출력 비용을 모두 계측해 효과가 없는 요청은 기존 transport로 유지한다.

## Decision and source references

- Decision IDs: D-054, D-066, D-068.
- Accepted ADR: 0049, 0051.
- Requirements: REQ-AI-003, REQ-AI-005, REQ-AI-007.
- Plan: `docs/ai-v1/COST_REDUCTION_PLAN.md` S13 and sections 2, 3, 5, 6, 10, 11, 13, 14.
- Provider contract: OpenAI GPT-5.6 prompt caching; pinned LiteLLM 1.101.0 `completion()` Chat Completions transport.
- Verification gates: AI-COST-001, AI-PROMPT-CACHE-001, AI-OBS-001, AI-RET-001.
- 선행 구현: S12 code PR #210.

## Actor and source

- 별도 customer/staff command나 Backend HTTP API를 추가하지 않는다. 기존 authorized AI worker call의 transport optimization이다.
- cacheable prefix source는 repository의 versioned feature prompt뿐이다. ticket/requester/workspace/job, PUBLIC conversation, query, KB, answer/citation, memory, rewrite source/candidate는 prefix에 들어가지 않는다.
- provider call actor/budget owner, cancellation, deadline, feature policy와 source freshness는 기존 job 계약을 그대로 사용한다. prompt cache는 authorization이나 source audit를 생략하지 않는다.

## Product and runtime contract

- mode는 `off | test | intent`, 기본은 `off`다. production에서 `test`는 startup failure다.
- GPT-5.6 Chat Completions request는 eligible일 때만 system content block 끝에 `prompt_cache_breakpoint={mode: explicit}`를 붙이고 `prompt_cache_options={mode: explicit, ttl: 30m}`를 보낸다.
- `prompt_cache_options`는 pinned LiteLLM에서 `allowed_openai_params`로 명시적으로 허용한다. 다른 API surface의 예제나 Anthropic `cache_control`을 복사하지 않는다.
- marked prefix를 reviewed tokenizer로 세어 1,024 token 미만이면 breakpoint/options/key를 모두 생략한다. padding과 synthetic examples는 금지한다.
- provider cache accounting key는 model/feature/prompt/schema/reasoning/service-tier/static-prefix digest만 반영한 bounded `ds-pc-v1-*` 값이다. 실제 key는 저장하거나 export하지 않는다.
- exact result cache/shared execution과 독립이다. result cache hit는 provider call이 없으므로 prompt cache 통계 분모에 포함하지 않는다.

## In scope

- AI settings validation과 separate prompt-cache mode.
- deterministic static-prefix cache plan, minimum-token eligibility, explicit OpenAI request fields, disabled/ineligible passthrough.
- pinned LiteLLM transport interception fixture와 provider usage normalization.
- additive AI migration과 call receipt의 bounded plan status/prefix-token/key-version metadata.
- metadata-only Langfuse projection and cost-report query/test using provider-reported usage buckets.
- current prompt eligibility regression so prompt growth or accidental customer-content movement is visible.

## Out of scope

- live/paid provider call, production activation, provider/project retention setting change, deployment.
- prompt padding, new examples written only for cache length, quality prompt rewrite.
- local LiteLLM exact-response cache, Redis response cache, cross-job result reuse 변경.
- ticket/query/KB/result content caching, implicit suffix caching, `24h` retention.
- Backend/OpenAPI/frontend changes; this slice exposes no product HTTP contract.

## Invariants and failure semantics

- cache plan construction is deterministic for the same static contract. Variable payload mutation cannot change the prefix or accounting key.
- any cacheable prefix block containing a non-static field is a programming error and the request must bypass or fail before provider dispatch; tests keep the builder input limited to a `FeaturePrompt` and strict schema type.
- disabled/ineligible calls preserve the exact pre-S13 message and provider parameter shape except bounded receipt metadata after a successful response.
- eligible call uses one breakpoint, explicit 30-minute TTL, content-free key and hidden retry 0. Unknown option forwarding is not silently dropped.
- reservation uses the reviewed maximum input rate, including cache-write rate. cache miss/write never causes reservation overrun to be forgiven.
- provider usage missing/duplicate/inconsistent or delivery ambiguity preserves existing `UNKNOWN` semantics. Prompt cache does not add a retry or release budget optimistically.
- receipt stores status, prefix token count and key version, never raw key/prompt/digest. Langfuse gets the same bounded metadata plus usage/cost only.

## Data and privacy

- provider cache may contain only repository-owned static instruction tensors. The explicit suffix contains all customer/ticket/KB/generated content.
- cache TTL is at least 30 minutes after write/reuse and may physically persist longer; no immediate deletion claim is made.
- Deskseed persists no provider cache state. Receipt metadata follows existing execution retention; raw cache key and prompt are absent from DB/log/audit/trace/Langfuse.
- no workspace/job/ticket/requester identifier is used as a metric label or cache key component.

## Acceptance scenarios

1. `off` sends the original request without breakpoint/options/key and records `OFF` on a successful receipt.
2. `test` in production fails settings validation; `intent` may start but does not imply savings.
3. a static prefix below 1,024 reviewed tokens sends the original request and records `INELIGIBLE` plus the bounded token count.
4. an artificial repository-owned prefix at or above 1,024 tokens produces one explicit breakpoint, explicit `30m` options, a ≤64-character content-free key and key version.
5. pinned LiteLLM transport interception observes the breakpoint in `messages`, `prompt_cache_key`, and `prompt_cache_options` in the final OpenAI SDK request body without a network call.
6. variable user payload changes do not change the static prefix or cache key; model/prompt/schema/reasoning/service-tier change does.
7. provider `cached_tokens` and `cache_write_tokens` normalize to exclusive read/write buckets and total cost uses the reviewed rates.
8. duplicate or contradictory usage remains `INCONSISTENT`; missing usage remains unavailable/UNKNOWN and is not reported as a miss or saving.
9. migration preserves existing calls, accepts only valid status/shape, and upgrades from AI 015 to 016.
10. logs/metrics/traces/Langfuse/DB contain no raw cache key, prompt, ticket/customer/KB/result body or identifier label.

## Validation

- `cd ai && .venv/bin/ruff check src tests scripts`
- `cd ai && .venv/bin/mypy`
- `cd ai && .venv/bin/pytest -q`
- focused transport, cache-plan, receipt, usage normalization, migration and observability tests.
- intercepted final LiteLLM/OpenAI SDK request; no paid network call.
- `cd ai && .venv/bin/python scripts/export_openapi.py` to prove the unchanged internal API artifact remains reproducible.
- `cd ai && .venv/bin/python scripts/evaluate_fake.py` — regression only, not prompt-cache savings or quality evidence.
- `make docs-check`, `git diff --check`, latest PR HEAD CI.

## Compatibility and migration

- no Core, source, or AI HTTP schema changes.
- AI 016 adds nullable/bounded provider-call receipt metadata; existing rows remain null and are interpreted as pre-S13 unknown plan state, not a cache miss.
- deploy reader → AI 016 migration → writer with mode `off` → optional `test` fixture/canary environment → measured `intent` activation.
- rollback is mode `off` followed by application rollback. Additive columns remain; settled costs, UNKNOWN calls, and retention continue.

## Performance and evidence boundary

- current static system prompts are approximately 101–233 reviewed tokens and therefore bypass. No present production savings are claimed.
- fake/intercepted transport proves parameter forwarding and accounting shape only.
- live hit/write/read tokens, latency, total cost, quality neutrality, provider/project retention and result-cache interaction remain NOT_ESTABLISHED until an approved paid canary and representative workload exist.

## Human explanation

Prompt cache는 같은 답을 재사용하는 S06 cache가 아니라 provider가 동일한 입력 앞부분의 계산을 잠시 재사용하는 기능이다. 쓰기 비용이 일반 입력보다 비싸므로 짧은 prompt를 억지로 늘리면 오히려 손해다. 따라서 고객 데이터가 없는 정적 지침만 명시적으로 표시하고, 현재처럼 1,024 token보다 짧으면 아무 옵션도 보내지 않는다. 실제 절감은 provider receipt의 쓰기·read·일반 입력·출력 전체 비용으로만 판단한다.
