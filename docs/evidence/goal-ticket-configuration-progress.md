# Goal Ticket Configuration progress

Frozen base: `feature/goal/foundation-extension-kernel` at `4c06aca6560f7a8458992af6379c6954b1bc4dc1`.

| Requirement | Checkpoint | Evidence | Status |
|---|---|---|---|
| REQ-CFG-010 | A — typed field configuration slice | ADR 0041, owned Core fragment, V40 additive schema, ADMIN lifecycle and agent configuration command/read HTTP boundaries, PostgreSQL integration test | Implemented: typed field/option lifecycle, ETag, atomic Admin/Security audit; agent form-projected typed-value mutation/read with replay and redacted TicketAudit |
| REQ-CFG-011 | C — versioned form/conditional slice | ADR 0041, V40 immutable `ticket_form_versions`, Foundation `WorkflowCatalog` ConditionHandler contribution, PostgreSQL integration test | Implemented: draft/update/publish/archive lifecycle, server preview, AST allowlist validation, cycle/contradiction rejection, immutable snapshot, and customer-safe published-form projection |
| REQ-CFG-012 | D — normalized tag catalog slice | ADR 0041, V40 tag catalog/assignment schema, ADMIN API and agent command, PostgreSQL integration test | Implemented: lowercase canonical catalog, lifecycle deactivation, immutable value identity, ETag, Admin/Security audit, and atomic active-tag assignment/removal in the ticket command; Search/View contributor remains pending |
| REQ-CFG-013 | E — category-compatible custom status catalog slice | ADR 0041, V40 status schema/default unique index, ADMIN API and agent command, PostgreSQL integration test | Implemented: stable category label catalog, one active default/category, order, allowed-form validation, CLOSED catalog rejection, and category-compatible ticket command update |

## Verification log

- Passed: `ApiDocumentationIntegrationTest`, `ArchitectureTest`, `AgentTicketCommandIntegrationTest`, and `AdminTicketConfigurationIntegrationTest` against PostgreSQL Testcontainers. The scenarios prove frozen-route alignment; typed value/tag/status command with `If-Match`, CSRF, write scope, replay, one ordered TicketAudit, outbox fact, and audit-failure rollback; configuration read produces `API_RESOURCE_READ` without semantic `TICKET_VIEWED`; customer form projection excludes staff labels; and all prior ADMIN lifecycle/immutable-snapshot catalog behavior.
- Review remediation: V41 defers option-order uniqueness within the reorder transaction, enforces one published customer/agent default each, and adds the reverse tag-assignment index. Customer editable projection now intersects the global capability, tag-only configuration changes emit no status/SLA lifecycle event, and the tag list uses one aggregate projection instead of per-row assignment queries.
- Passed: focused PostgreSQL Testcontainers `AdminTicketConfigurationIntegrationTest` and `AgentTicketCommandIntegrationTest.tag only configuration mutation emits no status or SLA lifecycle event` after the remediation.
- Passed: `make docs-check` constituent bundle, ownership, OpenAPI-quality, and deterministic documentation validation commands. Its final clean-diff assertion is intentionally deferred until this commit stages regenerated Core OpenAPI and manifest outputs.
- Stack reconciliation: merge commit `88215ed` absorbs the original #87 without rewriting shared history; reconciled #87 `7f135d0` is then absorbed by this final stack. The four implemented customer/agent runtime operations remain `FROZEN`, and their temporary staff blueprint registry entries stay removed because frozen operations carry direct actor contract bindings.
- Not run: Search/View SQL contributor, Platform API, browser E2E, Storybook MCP verification, and full backend suite.
- Not run: Storybook MCP documentation/instructions/tests/previews. The project-local MCP endpoint is configured but its tools are not registered in this task session; no frontend component contract is inferred by this checkpoint.

## Reserved non-goals

- Existing broad `REQ-CFG-001` and `REQ-CFG-002` remain `BLUEPRINT_READY`; they are not marked complete by this lane.
- No Platform API operation is added until field/tag/status resource constraints and idempotency/ETag behavior are implemented together.
- Customer ticket submission does not yet persist custom field values; this slice only exposes the customer-safe form projection and keeps agent ticket updates on the existing command boundary.
