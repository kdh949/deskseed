# BE-P0-PRODUCTION-CONSISTENCY task brief

## Goal

production network policy, Platform-created ticket facts, First Reply SLA eligibility, and agent ticket read projection use one consistent contract without duplicate facts on idempotent replay.

## Decision and source references

- Decision IDs: `D-012`, `D-018`, `D-034`, `D-043`, `D-044`
- Accepted ADRs: `0012`, `0018`, `0031`, `0032`
- Requirements: `REQ-INT-001`, `REQ-INT-003`, `REQ-SLA-001`, `REQ-TKT-005`
- Contract operations: `platformCreateTicket`, `getAgentTicket`, `listTicketsInView`, `searchAgentWorkspace`
- Verification gates: `PLAT-001`, `PLAT-002`, `SLA-002`, `TKT-002`, `PERM-002`

## Actor and source

- Platform creates as `INTEGRATION_CLIENT` / `PLATFORM_API`; staff reads as `STAFF` / `AGENT_WORKSPACE`.
- Production source/trusted-proxy CIDRs are deployment policy, not user input.

## In scope

- Make the real `production` profile require valid nonempty Platform allowlist and trusted-proxy CIDRs without local fallback.
- Publish exactly one `TicketSubmitted` fact with creation audit ID, `API` channel and `PLATFORM_API` source for Platform creates; replays publish none.
- Keep `INTERNAL_WORK_ITEM` outside First Reply SLA start and validate the existing customer-request condition.
- Add additive `createdAt` to staff ticket reads and verify exact `ON_HOLD`/`CLOSED` status and capabilities reflect server authorization.
- Add targeted PostgreSQL/context tests and finish with the full backend suite.

## Out of scope

- New Platform API capabilities, SLA policy semantics, tenanting, or broad agent authorization policy changes.

## Invariants and failure semantics

- Invalid/missing production CIDR configuration fails startup; an untrusted network request never reaches business data.
- Creation audit and fact share the transaction. Idempotency replay returns canonical state without a second audit, target, or fact.
- First Reply starts only for a qualifying customer request and qualifying public customer-origin event.

## Data and privacy

- CIDRs are deployment configuration; no raw client address is written to ticket/audit payloads beyond existing bounded security policy.
- Staff projection adds timestamp only and does not expose customer-private or audit fields.

## Acceptance scenarios

- Production context rejects absent/malformed CIDRs and accepts a valid private deployment configuration.
- Platform customer request yields one SLA target/fact; internal work item yields no First Reply target; exact replay yields neither duplicate.
- Agent ticket response carries `createdAt`, exact terminal/hold status, and only server-authorized capabilities.

## Validation

- Profile startup tests, Platform PostgreSQL idempotency/SLA tests, agent read integration tests, then `/usr/bin/env -u DEBUG ./gradlew --no-daemon test`.

## Checkpoint — 2026-08-15

- Contract frozen: `platformCreateTicket` documents exact-once First Reply behavior. Core `TicketSummary` adds `createdAt`, and its capability description binds UPDATE to server-side write authorization and non-CLOSED state.
- Migration: not required. This slice changes a projection contract and deployment validation only; V28/V29 remain additive prior slices.
- Implementation: `production` requires explicit allowed-client and trusted-proxy CIDRs, validates malformed entries, and cannot use local development fallback. `JpaPlatformTicketService` persists the creation audit before publishing one in-transaction `TicketSubmitted`; the fact carries `INTEGRATION_CLIENT`/`PLATFORM_API`, the creation audit ID, and `API` channel. Customer requests start First Reply eligibility; internal work items only create their state interval. Staff list/search/detail/relations project `createdAt` and preserve canonical ON_HOLD/CLOSED status with server-derived capabilities.
- Targeted tests: `PlatformNetworkBoundaryTest` (5), `PlatformTicketIntegrationTest` (12), `FirstReplySlaIntegrationTest` (12), `AgentTicketReadIntegrationTest` (10), `PlatformOpenApiContractTest` (1), `ApiDocumentationIntegrationTest` (4), and `ArchitectureTest` (1) passed in the combined targeted run.
- Failed tests: an initial missing Kotlin return type and a test-fixture seed deletion were fixed, then the same Platform test passed. No production behavior was bypassed or assertion weakened.
- Final backend suite: `/usr/bin/env -u DEBUG ./gradlew --no-daemon test` passed with 65 XML reports / 312 tests / 0 failures / 0 errors. The profile, Platform/SLA, and agent-read targeted tests remain independently recorded above.
- Remaining risk: no in-scope risk remains. `make docs-check` passed after the final evidence snapshot was staged; the generated-document diff gate is clean.

## Compatibility and migration

- `createdAt` is an additive response field. Profile correction is an intentional production fail-fast behavior.
- Any V28+ indexes are additive and remain on rollback.

## Human explanation

The current ticket row and its creation audit remain authoritative; the in-process fact is a transaction-local projection trigger, not an event-sourcing store.
