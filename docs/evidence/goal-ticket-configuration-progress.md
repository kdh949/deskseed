# Goal Ticket Configuration progress

Frozen base: `feature/goal/foundation-extension-kernel` at `4c06aca6560f7a8458992af6379c6954b1bc4dc1`.

| Requirement | Checkpoint | Evidence | Status |
|---|---|---|---|
| REQ-CFG-010 | A — typed field configuration slice | ADR 0041, owned Core fragment, V40 additive schema, ADMIN field/option HTTP boundary, PostgreSQL integration test | Implemented: field definition/option lifecycle, ETag, atomic Admin/Security audit; runtime projection/value command pending |
| REQ-CFG-011 | C — versioned form/conditional slice | ADR 0041, V40 immutable `ticket_form_versions`, Foundation `WorkflowCatalog` ConditionHandler contribution, PostgreSQL integration test | Implemented: draft/update/publish/archive lifecycle, server preview, AST allowlist validation, cycle/contradiction rejection, immutable published snapshot; customer/agent runtime projection pending |
| REQ-CFG-012 | D — normalized tag catalog slice | ADR 0041, V40 tag catalog/assignment schema, ADMIN API, PostgreSQL integration test | Implemented: lowercase canonical catalog, lifecycle deactivation, immutable value identity, ETag, Admin/Security audit; ticket command/search/View contributor pending |
| REQ-CFG-013 | E — category-compatible custom status catalog slice | ADR 0041, V40 status schema/default unique index, ADMIN API, PostgreSQL integration test | Implemented: stable category label catalog, one active default/category, order, allowed-form validation, CLOSED catalog rejection; ticket command/old-client compatibility pending |

## Verification log

- Passed before this commit: Core bundle unit tests, ownership validation, API documentation-quality tests, deterministic documentation validation, `ArchitectureTest`, and `AdminTicketConfigurationIntegrationTest` against PostgreSQL Testcontainers. The integration test proves field/option lifecycle, stable option identity, whole-collection reorder, optimistic version rejection, ADMIN authorization, atomic admin-audit persistence, audit-write rollback, form conditional preview, cycle rejection, publish, immutable snapshot enforcement, tag normalization/deactivation, custom-status default uniqueness, allowed-form reference, order, and CLOSED catalog rejection.
- Not run: runtime ticket command, customer/agent projection, Search/View contributor, Platform API, browser E2E, Storybook MCP verification, and full backend suite.
- Route contract staging: fields/forms and tag/status ADMIN operations are `FROZEN` in this PR because their runtime mappings are implemented here. Customer/agent runtime operations remain reviewer-visible `BLUEPRINT_READY` and are promoted only by the final runtime stacked PR.
- Not run: Storybook MCP documentation/instructions/tests/previews. The project-local MCP endpoint is configured but its tools are not registered in this task session; no frontend component contract is inferred by this checkpoint.

## Reserved non-goals

- Existing broad `REQ-CFG-001` and `REQ-CFG-002` remain `BLUEPRINT_READY`; they are not marked complete by this narrower field-administration slice.
- No Platform API operation is added until field/tag/status resource constraints and idempotency/ETag behavior are implemented together.
