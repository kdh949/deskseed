# Knowledge Base goal progress

Branch ownership: `feature/goal/knowledge-base-content` → `feature/goal/knowledge-base`; base: Foundation F3 `4c06aca`.

| Checkpoint | Evidence | State |
| --- | --- | --- |
| A — contract and permissions | Owned fragment `api/core-api-fragments/20-knowledge-base.yaml` freezes public, agent and admin operation families; no manual edits to the generated artifact. | In progress |
| B — hierarchy/revisions | V50 creates the fixed category→section→article schema, FK/order/slug constraints, immutable revision trigger and a migration compatibility test from V36. | In progress |
| C — canonical block/XSS | Pure domain validator now allowlists canonical blocks, rejects HTML/unsafe URLs/unknown schema versions, and rejects PUBLIC attachment references; HTTP adapter and rendered UI remain pending. | In progress |
| D — PostgreSQL search/audience | Permission-safe FTS/trigram projection, corpus and latency evidence. | Not started |
| E — cache consistency | ETag, Last-Modified, Cache-Control and audience-change invalidation. | Not started |
| F — UI contribution | Customer, agent/context and admin editor routes through Foundation host; Storybook MCP evidence required. | Not started |

## Explicit non-goals

OpenSearch/Elasticsearch, Redis cache, ltree, community, generative answers, arbitrary hierarchy, automatic agent reply submission, and direct central App/shell/workspace modifications are excluded.
