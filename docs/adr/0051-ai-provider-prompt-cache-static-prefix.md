# ADR 0051: AI provider prompt cache static prefix

- Status: Accepted
- Date: 2026-09-19
- Decision: D-068
- Requirements: REQ-AI-003, REQ-AI-005, REQ-AI-007

## Context

OpenAI prompt caching can reduce repeated input processing, but GPT-5.6 cache writes cost more than ordinary input and only an exactly matching visible prefix of at least 1,024 tokens is eligible. A cache miss can therefore increase cost. Deskseed's current feature prompts are intentionally short, and the ticket conversation, retrieval query, KB chunks, original reply, and rewrite candidate all vary per request. Padding a prompt only to cross the provider threshold would increase ordinary input cost without product value.

The pinned LiteLLM 1.101.0 `completion()` transport uses Chat Completions. Its typed signature exposes `prompt_cache_key` but not `prompt_cache_options`; the latter must be explicitly allowlisted and is emitted through the OpenAI SDK `extra_body`. The transport contract must be proven without a live provider call before this optimization can be enabled.

## Decision

Deskseed supports provider prompt caching only through a versioned, explicit static-prefix plan.

- The cacheable prefix contains only repository-owned feature instructions. Ticket/customer text, PUBLIC conversation, query, KB title/body, generated answer, citation title/URL, previous context memory, rewrite source/candidate, actor/workspace identifiers, secrets, and provider credentials remain after the breakpoint and are never deliberately written into the provider cache.
- The prefix ends with an OpenAI `prompt_cache_breakpoint` and the request uses `prompt_cache_options={mode: explicit, ttl: 30m}`. Implicit suffix caching, `24h` retention, and provider-local response caching are not used.
- The cache accounting key is a bounded `ds-pc-v1-<digest>` derived only from model alias, feature/prompt version, response-schema digest, reasoning effort, service tier, and the static prefix digest. It contains no workspace, ticket, requester, job, source, or content value.
- A reviewed tokenizer must estimate the marked visible prefix at 1,024 tokens or more. Shorter prefixes bypass caching and send the pre-S13 request shape. Deskseed does not add padding or synthetic examples to reach the threshold.
- `off | test | intent` is a separate runtime mode from exact result caching. It defaults to `off`; production rejects `test`. `intent` means transport use is permitted, not that savings or quality are established.
- The provider call receipt records only bounded cache-plan metadata: `OFF | INELIGIBLE | REQUESTED`, marked prefix token count, and cache-key version. It never records the cache key, prompt, or content digest. Provider-reported uncached, cache-read, cache-write, and output buckets remain the cost source of truth.
- Reservation continues to use the maximum of ordinary input, cache-read, and cache-write rates. Missing or inconsistent usage is `UNKNOWN`/fail-closed under the existing cost ledger; a cache option never permits optimistic release.

## Current eligibility

Using the reviewed `o200k_base` tokenizer, the current repository-owned system prompts are approximately 101–233 tokens. They are below the provider's 1,024-visible-token minimum, so S13 adds the guarded transport capability and evidence but does not make current production requests cacheable. A later prompt/schema change may become eligible only through the same contract and regression gate; adding useful examples still requires quality and total-cost evaluation.

## Cost and observability

The comparison unit is total known provider cost, not hit rate alone:

`uncached input + cache write + cache read + output`

Receipt-based reporting separates `REQUESTED` calls, provider write tokens, provider read tokens, misses, unknown usage, latency, feature, model alias, prompt version, and pricing version. Job/ticket/requester/workspace IDs and cache keys are not metric labels or Langfuse metadata. A fake or intercepted transport proves forwarding and normalization only; it is not savings evidence.

## Privacy and retention

Only static repository-owned instructions may be retained by the provider cache. GPT-5.6 uses an explicit 30-minute minimum TTL that refreshes on reuse; Deskseed does not claim immediate deletion or exact physical expiry. Provider cache state is not copied into Deskseed databases or backups. Call metadata follows the existing execution/receipt retention, while prompt and cache key remain absent from Git-generated evidence, logs, traces, Langfuse, audits, and persisted receipts.

## Consequences

The design prevents a cost optimization from becoming a second cache of customer content and preserves conservative reservation. It also means current short prompts receive no caching benefit. Enabling the mechanism before a sufficiently long, useful static prefix exists would be a no-op; adding padding is intentionally rejected.

## Alternatives considered

- Implicit caching of the complete request: may write variable customer and KB content and makes retention/cost boundaries harder to explain, so it is rejected.
- A workspace/ticket-derived cache key: unnecessary because the marked prefix contains no tenant data and would export an extra identifier, so it is rejected.
- Padding or duplicated boilerplate to reach 1,024 tokens: raises baseline input and write cost without product value, so it is rejected.
- Treating LiteLLM's local exact-response cache as provider prompt caching: changes response semantics and duplicates S06 result caching, so it is rejected.
- Enabling on hit-rate evidence alone: ignores write, miss, output, and unknown costs, so it is rejected.

## Revisit triggers

- provider minimum length, cache prices, TTL, API fields, or Chat Completions support changes
- a reviewed static prefix becomes at least 1,024 tokens
- provider cache retention conflicts with data-residency or zero-retention policy
- measured total cost or latency regresses, or quality changes after prefix restructuring
- migration from LiteLLM `completion()`/Chat Completions to another transport
