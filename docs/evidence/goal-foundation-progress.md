# Wave 0 Foundation progress

## Frozen preflight

| Item | Evidence |
|---|---|
| Repository and remote | `/Users/donghyunkim/Documents/deskseed`, `origin` GitHub remote |
| Frozen base | `origin/main` `e4b7bedba69b8fe69f43fddb273522de3ac6fd1a` (2026-08-18T06:42:50+09:00) |
| Primary worktree preserved | start status: ` M .gitignore`, `?? .mcp.json`; implementation runs only from an isolated worktree |
| Current migration inventory | V1–V35; V35 is `staff_ticket_search_projection` |
| Foundation reservation | V36–V39; V35 is not edited or reused |
| Root/frontend instruction SHA | `AGENTS.md` `ae59a3e911f3d60018c5a9556d10d94d3e31bcb4fb1f485415c7f7c973e4d166`; `frontend/AGENTS.md` `554d16b83620599f6cab2839a20dec4e4ea404694f90b362b70d7580ec04c983` |
| OpenAPI relation | `api/core-api-base-v1.yaml` + `api/core-api-fragments/*` source; `api/core-api-outline-v1.yaml` deterministic committed artifact |
| GitHub access | `gh auth status` confirmed authenticated `kdh949` with repository and workflow scopes |
| Storybook MCP | unavailable in this session; no undocumented design-system prop is inferred and MCP-specific verification stays Not run |

## Checkpoints

| Requirement/checkpoint | Concrete evidence | Contract/migration evidence | Verification | Remaining gap |
|---|---|---|---|---|
| REQ-FND-001 / F1 fragment bundle | `scripts/bundle_core_openapi.py`, reserved owned fragments, `scripts/test_core_openapi_bundle.py` | ADR 0040; generated compatibility artifact | Passed: `python3 scripts/bundle_core_openapi.py --check`; focused Python test | remote CI pending |
| REQ-FND-001 / lane ownership | Delivery-time registry reserved V36–V39 and V40–V79 while lanes were active | ADR 0040 operational lifecycle | Historical reservation completed; registry and validator retired after Wave delivery | durable fragment ownership remains covered by bundle parity and collision tests |
| REQ-FND-002 / workflow kernel | `workflow` root API, `SpringWorkflowCatalog`, `ConditionAstValidator`, `WorkflowRegistryTest` | ADR 0040 | Passed: focused Gradle test and `ArchitectureTest` (`ApplicationModules.verify()`) | protected catalog HTTP adapter is deferred to the first consumer surface |
| REQ-FND-003 / event outbox | `eventpublication` root API, `JdbcEventOutbox`, `TicketIntegrationEventPublisher` | V36 `domain_event_outbox`; envelope schema is no longer planned-only | Passed: envelope/redaction mapping, PostgreSQL rollback + lease recovery, actual public ticket submission, `ArchitectureTest`, and same-subject lower-sequence leased-claim regression | external dispatch/delivery rows are owned by the Wave 1 integrations lane; remote CI pending |
| REQ-FND-004 / frontend host | `frontend/src/extension-host`, static Vite discovery, route gate, agent navigation and production ticket-workspace slots | No API or migration change; host passes only role/capabilities and non-sensitive ticket/composer context | Passed: deterministic registry, duplicate ID/route/order rejection, role/capability filtering, denied route, and one-slot error isolation unit coverage; Storybook MCP verification Not run | Feature-owned `feature-contribution.tsx` modules, their server authorization, and concrete UI/API slices belong to Wave 1 lanes |
