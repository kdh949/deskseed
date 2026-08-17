# Goal Ticket Configuration progress

Frozen base: `feature/goal/foundation-extension-kernel` at `4c06aca6560f7a8458992af6379c6954b1bc4dc1`.

| Requirement | Checkpoint | Evidence | Status |
|---|---|---|---|
| REQ-CFG-010 | A — typed field configuration slice | ADR 0041, owned Core fragment, V40 additive schema, ADMIN field/option HTTP boundary, PostgreSQL integration test | Implemented: field definition/option lifecycle, ETag, atomic Admin/Security audit; runtime projection/value command pending |
| REQ-CFG-011 | C — versioned form/conditional slice | ADR 0041, V40 immutable `ticket_form_versions`, Foundation `WorkflowCatalog` ConditionHandler contribution, PostgreSQL integration test | Implemented: draft/update/publish/archive lifecycle, server preview, AST allowlist validation, cycle/contradiction rejection, immutable published snapshot; customer/agent runtime projection pending |
| REQ-CFG-012 | A — normalized tag contract | ADR 0041, owned Core fragment | Contract frozen; implementation pending |
| REQ-CFG-013 | A — category-compatible custom status contract | ADR 0041, owned Core fragment | Contract frozen; implementation pending |

## Verification log

- Passed before this commit: Core bundle unit tests, ownership validation, API documentation-quality tests, deterministic documentation validation, `ArchitectureTest`, and `AdminTicketConfigurationIntegrationTest` against PostgreSQL Testcontainers. The integration test proves field/option lifecycle, stable option identity, whole-collection reorder, optimistic version rejection, ADMIN authorization, atomic admin-audit persistence, audit-write rollback, form conditional preview, cycle rejection, publish, and immutable snapshot enforcement.
- Not run: tag/status/runtime ticket command, customer/agent projection, Search/View contributor, Platform API, browser E2E, Storybook MCP verification, and full backend suite.
- Not run: Storybook MCP documentation/instructions/tests/previews. The project-local MCP endpoint is configured but its tools are not registered in this task session; no frontend component contract is inferred by this checkpoint.

## Reserved non-goals

- Existing broad `REQ-CFG-001` and `REQ-CFG-002` remain `BLUEPRINT_READY`; they are not marked complete by this narrower field-administration slice.
- No Platform API operation is added until field/tag/status resource constraints and idempotency/ETag behavior are implemented together.
