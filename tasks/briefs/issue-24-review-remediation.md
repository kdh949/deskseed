# Issue 24 code review remediation

## Goal

조직 변경과 티켓 명령, 감사자 권한, 익명 고객 식별, 관리자 목록 및 검증 자동화가 문서화된 보안·동시성·성능 계약을 일관되게 지키도록 한다.

## Decision and source references

- Decision IDs: D-002, D-005~009, D-013, D-018~020, D-047, D-052
- Accepted ADRs: 0002, 0005~0009, 0013, 0018, 0035, 0038
- PRD/domain: docs/01 sections 5/6/8/10, docs/02 customer/ticket/audit model, docs/03 module and transaction boundaries
- API operations: existing staff ticket commands and the admin staff/group surfaces; any authority-management operation is frozen in `api/core-api-outline-v1.yaml` before implementation
- Verification gates: ARCH-001/002, TKT-003/006, CHG-001/002, ACC-007, AUD-001~005, AUTH-004, PERF-001

## Actor and source

- Actor types: CUSTOMER, STAFF
- Sources: CUSTOMER_PORTAL, AGENT_UI, ADMIN_UI, AUDIT_EXPLORER
- Required role/scopes: active Agent or Admin under the existing ticket write policy; Admin for organization and explicit audit-authority grants; Security Auditor plus an explicit capability for high-risk audit operations
- Resource constraints: current group membership/assignment, target staff role/status, own anonymous request-access grant, bounded admin page
- Interaction/request/correlation semantics: existing request/correlation/command IDs remain authoritative; admin authority changes carry the authenticated actor and are audited atomically

## Product and UX contract

- Requirement IDs: REQ-TKT-002/005/010~014, REQ-AUD-002/005/006/008, REQ-PERF-001, REQ-UI-005
- Routes: existing Agent Workspace, Admin staff/group pages and Audit Explorer
- OpenAPI: core staff/admin contract is updated before or with endpoint implementation
- UI states: authority controls and bounded lists retain loading, empty, error, denied and stale states as applicable
- Accessibility: native controls keep labels, keyboard behavior and visible focus; no color-only permission state

## In scope

- one consumer-owned PostgreSQL transaction-lock port for organization mutations and membership-dependent ticket commands
- current seed verifier and required local/CI gate wiring
- persisted explicit grants for audit reveal, export and projection rebuild with atomic admin audit
- isolated unverified customer records and an additive verified-email uniqueness migration
- batched, bounded admin staff/group reads and query-count regressions
- OpenAPI, migrations, traceability, ADR, tests and completion evidence

## Out of scope

- automatic anonymous-request claim or profile merge
- new customer password/SSO flow, multitenancy, external cache/search, queue or microservice
- production performance SLOs without a defined hardware and dataset baseline

## Invariants and failure semantics

- a committed ticket never references a disabled group, disabled staff member or inactive assignee membership
- ticket commands and organization mutations acquire the organization consistency guard before organization-dependent reads
- current ticket rows remain source of truth and ticket change audit commits atomically
- Security Auditor is routine-read-only by default; high-risk authority changes are explicit, persisted and admin-audited
- matching unverified email alone never links identities or changes an earlier requester's profile
- required sensitive-read or authority-change audit failure fails closed
- retries retain existing command-id/version semantics; no external I/O is introduced inside a transaction

## Data and privacy

- unverified customer name/email rows are request-local until an explicit claim flow is implemented
- audit authority grants contain staff/authority/grant actor/timestamps, not secrets
- passwords, sessions, raw search queries and comment bodies remain absent from ordinary logs and new audit metadata
- existing ledger/customer retention applies; no export or webhook field expansion is implicit

## Threats changed

- closes membership-check TOCTOU and stale authorization windows
- removes implicit elevation of Security Auditor high-risk capabilities
- prevents anonymous same-email profile tampering and accidental identity linkage
- bounds administrator list query amplification
- prevents release verification from silently omitting a stale public gate

## Acceptance scenarios

- Given an active assignment, when ticket creation and group disable race, then exactly one commits first and the later command revalidates against committed state.
- Given a default Security Auditor, when reveal/export/rebuild is requested, then access is denied until an Admin explicitly grants that capability and the change is audited.
- Given two anonymous requests with the same email, when names differ, then each ticket retains its own requester row and neither request mutates the other.
- Given multiple staff and groups, when admin lists load, then query count is bounded independently of row count and the response is paginated or compatibility-bounded.
- Given the repository verification target, when `make check` and CI run, then the current core contract and current requirement labels are validated.

## Validation

- focused PostgreSQL/Testcontainers RED/GREEN regressions per slice
- V15 fixture를 V17로 올리는 explicit forward-migration regression
- `cd backend && ./gradlew clean test`
- `cd frontend && npm run format:check && npm run lint && npm run typecheck && npm test && npm run build`
- `make check`, OpenAPI/document validator, security dependency audit and relevant browser E2E

## Compatibility and migration

- migrations are forward-only and additive except removal of the legacy global unverified-email unique constraint, replaced by verified-only uniqueness
- high-risk Security Auditor capabilities become deny-by-default; operators must explicitly grant them after migration
- admin API evolution preserves or explicitly deprecates existing clients before changing response shape
- rollback is application rollback plus forward corrective migration; destructive Flyway down migrations are not used

## Human explanation

- A shared PostgreSQL transaction guard is the smallest sufficient boundary for cross-module organization invariants at current scale; measured contention would justify finer-grained ordered locks later.
- Persisted capabilities separate routine investigation from exceptional reveal/export/rebuild without creating a new role hierarchy.
- Anonymous email is contact input, not verified identity, so equality cannot authorize merging.
- Batch projections and bounded pages remove data-size-dependent query amplification without a new datastore.
