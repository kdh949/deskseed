# Knowledge Base goal progress

Branch ownership: `feature/goal/knowledge-base-content` → `feature/goal/knowledge-base`; base: Foundation F3 `4c06aca`.

| Checkpoint | Evidence | State |
| --- | --- | --- |
| A — contract and permissions | Owned fragment `api/core-api-fragments/20-knowledge-base.yaml` freezes public, agent and admin operation families; no manual edits to the generated artifact. | In progress |
| B — hierarchy/revisions | V50 creates the fixed category→section→article schema, FK/order/slug constraints and immutable revision trigger; V51 forward-corrects canonical multi-block plain-text separators without editing V50. Compatibility is tested from V36. | In progress |
| C — canonical block/XSS | Pure domain validator and strict JSON adapter allowlist canonical blocks, reject HTML/unsafe URLs/unknown schema versions, reject PUBLIC attachment references, and persist only canonical JSON; rendered UI remains pending. | In progress |
| D — PostgreSQL search/audience | Permission-safe FTS/trigram projection, corpus and latency evidence. | Not started |
| E — cache consistency | ETag, Last-Modified, Cache-Control and audience-change invalidation. | Not started |
| F — UI contribution | Customer, agent/context and admin editor routes through Foundation host; Storybook MCP evidence required. | Not started |

## Explicit non-goals

OpenSearch/Elasticsearch, Redis cache, ltree, community, generative answers, arbitrary hierarchy, automatic agent reply submission, and direct central App/shell/workspace modifications are excluded.

## Implemented vertical slice evidence

- Admin-only category/section update, article-draft and lifecycle commands require the staff session actor header and CSRF boundary, validate the fixed hierarchy/state transition, write a required `AdminSecurityAudit`, and append an INTERNAL durable event intent in one transaction.
- `AdminKnowledgeIntegrationTest` proves category optimistic update/stale rejection, category→section→draft→review→publish, immutable published revision pointer, and an injected audit-table failure that rolls both the mutation and outbox intent back.
- This evidence does not claim public/agent read/search, lifecycle transitions, cache validators, UI, Storybook, or remote CI as complete.
