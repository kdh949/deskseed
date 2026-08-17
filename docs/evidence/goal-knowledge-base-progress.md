# Knowledge Base goal progress

Branch ownership: `feature/goal/knowledge-base-content` → `feature/goal/knowledge-base`; base: Foundation F3 `4c06aca`.

| Checkpoint | Evidence | State |
| --- | --- | --- |
| A — contract and permissions | Owned fragment `api/core-api-fragments/20-knowledge-base.yaml` freezes public, agent and admin operation families; no manual edits to the generated artifact. | In progress |
| B — hierarchy/revisions | V50–V59 reservation only; category→section→article and immutable canonical revision tests. | Not started |
| C — canonical block/XSS | Allowlisted block schema, Editor.js adapter and safe renderer. | Not started |
| D — PostgreSQL search/audience | Permission-safe FTS/trigram projection, corpus and latency evidence. | Not started |
| E — cache consistency | ETag, Last-Modified, Cache-Control and audience-change invalidation. | Not started |
| F — UI contribution | Customer, agent/context and admin editor routes through Foundation host; Storybook MCP evidence required. | Not started |

## Explicit non-goals

OpenSearch/Elasticsearch, Redis cache, ltree, community, generative answers, arbitrary hierarchy, automatic agent reply submission, and direct central App/shell/workspace modifications are excluded.
