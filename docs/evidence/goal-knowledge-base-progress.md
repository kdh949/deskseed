# Knowledge Base goal progress

Branch ownership: `feature/goal/knowledge-base-content` → `feature/goal/knowledge-base`; base: Foundation F3 `4c06aca`.

| Checkpoint | Evidence | State |
| --- | --- | --- |
| A — contract and permissions | Owned fragment `api/core-api-fragments/20-knowledge-base.yaml` freezes public, agent and admin operation families; no manual edits to the generated artifact. | In progress |
| B — hierarchy/revisions | V50 creates the fixed category→section→article schema, FK/order/slug constraints and immutable revision trigger; V51 forward-corrects canonical multi-block plain-text separators without editing V50. Compatibility is tested from V36. | In progress |
| C — canonical block/XSS | Pure domain validator and strict JSON adapter allowlist canonical blocks, reject HTML/unsafe URLs/unknown schema versions, reject PUBLIC attachment references, and persist only canonical JSON; rendered UI remains pending. | In progress |
| D — PostgreSQL search/audience | V52 adds the published-revision FTS projection, GIN/trigram indexes, signed scope-bound cursors, SQL audience filtering, feedback totals, and the separate immutable agent-knowledge access audit. Corpus/latency evidence remains. | In progress |
| E — cache consistency | Public anonymous article responses derive ETag from revision/audience version and return Last-Modified plus public cache policy; authenticated/restricted responses are no-store. Explicit 304 and audience-change regression coverage remains. | In progress |
| F — UI contribution | Customer, agent/context and admin editor routes through Foundation host; Storybook MCP evidence required. | Not started |

## Explicit non-goals

OpenSearch/Elasticsearch, Redis cache, ltree, community, generative answers, arbitrary hierarchy, automatic agent reply submission, and direct central App/shell/workspace modifications are excluded.

## Implemented vertical slice evidence

- Admin-only category/section update, article-draft, lifecycle, and audience replacement commands require the staff session actor header and CSRF boundary, validate the fixed hierarchy/state transition, write a required `AdminSecurityAudit`, and append an INTERNAL durable event intent in one transaction.
- `AdminKnowledgeIntegrationTest` proves category optimistic update/stale rejection, category→section→draft revision→review→publish, immutable published revision pointer, audience-version increment, public Help cache/search/feedback, admin article/index routes, agent search/detail, and injected admin/agent audit failures that withhold success and roll the command back.
- `ApiDocumentationIntegrationTest` proves every FROZEN Goal-03 method/path now has a runtime mapping. `compileKotlin` and these PostgreSQL-backed tests pass locally.
- Review remediation: the OpenAPI document is now a closed discriminated block schema; admin list filtering returns one document-free summary projection; audience replacement revalidates the current published/latest revision before side effects; and `AdminKnowledgeIntegrationTest` covers public-attachment rejection and filtered summary behavior.
- This evidence does not claim selected-group/customer audience matrix, ETag 304 regression, corpus/p95 latency, UI/Storybook, real-stack validation, or remote CI as complete.
