# Frontend Capability Recomposition Matrix

Status: **Normative current-surface contract**

## 1. Purpose

The current frontend ships Agent Queue, a read-only Ticket Workspace, minimum staff login, and common denied/not-found states. This matrix preserves the contracts needed to rebuild deferred screens without retaining their previous React pages, CSS, fixtures, or screenshots.

Backend implementation status is independent from UI delivery status. `DEFERRED_UI` below means “not routed or rendered in this frontend,” not “the server capability is absent.” Core and Platform OpenAPI remain unchanged by ADR 0039.

## 2. Current surface

| Surface | Operations | Actor/capability | Projection and audit | Current evidence |
|---|---|---|---|---|
| Minimum staff login | `getStaffCsrfToken`, `createStaffSession`, `deleteStaffSession`, `getCurrentStaff` | anonymous staff → authenticated AGENT/ADMIN | Session principal is authoritative; expected-actor guard may only confirm it | `StaffSessionContext.test.tsx`, `access-surface.spec.ts` |
| Agent Queue | `listAgentViews`, `listTicketsInView` | AGENT or ADMIN + `AGENT_WORKSPACE` | Staff-visible queue projection; list/prefetch never emits semantic `TICKET_VIEWED` | `AgentViewsPage.test.tsx`, Queue Storybook interactions, `agent-views-workspace.spec.ts` |
| Read-only Ticket Workspace | `getAgentTicket`, `listAgentTicketExternalReferences`, `listTicketAudits` | AGENT or ADMIN + `AGENT_WORKSPACE`; SECURITY_AUDITOR denied | PUBLIC and INTERNAL remain distinct; `NAVIGATION` records one semantic view and `BACKGROUND` records none | `AgentTicketWorkspacePage.test.tsx`, Workspace Storybook states, `agent-views-workspace.spec.ts` |

## 3. Deferred UI capabilities

| Capability / UI state | Endpoint operation IDs | Actor, scope, projection, audit | Idempotency, concurrency, failures | Preserved headless seam and tests | Delivery |
|---|---|---|---|---|---|
| Customer request and account portal | `createCustomerRequest`, `getAnonymousRequest`, `addCustomerRequestComment`, `listCustomerRequests`, `getCurrentCustomer`, `getCustomerRequest`, `addAuthenticatedCustomerComment` | Anonymous/customer; PUBLIC-only projection; INTERNAL, child, staff, and audit fields absent | Request grants and customer CSRF fail closed; comment command ID prevents duplicate logical submit | `features/customer-requests/requestForm.ts`, `features/customer-auth/api/customerAuthClient*`, `features/customer-portal/api/customerPortalClient*`, backend customer integration tests | `DEFERRED_UI` |
| Customer access-mode and organization administration | `getCustomerAccessModeSetting`, `updateCustomerAccessModeSetting`, `listStaffAccounts`, `createStaffAccount`, `disableStaffAccount`, `listGroups`, `createGroup`, `updateGroup`, `disableGroup`, membership operations | ADMIN only; resource and active-assignment constraints; mutation and admin/security audit commit together | CSRF and expected version required where specified; 409 retains server truth and no partial mutation | `api/client.ts`, `api/types.ts`, Core OpenAPI, `AdminOrganizationIntegrationTest`, `CustomerAccessModeIntegrationTest` | `DEFERRED_UI` |
| Audit Explorer and protected reveal/export | `listAuditActivities`, `getAuditActivity`, `revealAuditSearchQuery`, `createAuditExport`, `getAuditExport`, `rebuildAuditActivityProjection` | SECURITY_AUDITOR plus explicit authority; protected reveal needs reason and self-audit; failed required audit persistence fails closed | Cursor/filter binding and projection rebuild locking; 403/409/503 remain distinct | `features/audit/model/auditInteraction*`, Core OpenAPI, `SecurityAuditorAuthorizationIntegrationTest`, `AuditExplorerIntegrationTest` | `DEFERRED_UI` |
| Agent search | `searchAgentWorkspace` | AGENT/ADMIN staff projection; routine audit is content-free; exact query only through protected ciphertext reveal | Stable search session and result-open linkage; background/detail intent must not invent semantic views | `features/audit/model/auditInteraction*`, `api/client.ts`, `AgentTicketSearchIntegrationTest`, `SearchQueryProtectionTest` | `DEFERRED_UI` |
| Integration clients and external systems | integration-client and external-system operation groups; Platform `platformCreateTicket`, `platformGetTicket`, `platformUpdateTicket`, `platformAddInternalComment` | ADMIN for configuration; `INTEGRATION_CLIENT` plus scope/resource constraints for Platform API; machine actor cannot impersonate staff | Platform writes require `Idempotency-Key`; updates require `If-Match`; secret is shown once; external URLs retain SSRF boundaries | Core/Platform OpenAPI, `api/types.ts`, `PlatformTicketIntegrationTest`, integration-client and external-reference tests | `DEFERRED_UI` |
| Business schedules and First Reply SLA | schedule and SLA policy operation groups, `getFirstReplySlaAnalytics` | ADMIN policy mutation; staff-visible computed projection; policy version and audit retained | Versioned policy activation and preview; schedule/time-zone validation; rebuild is idempotent | Core OpenAPI, `api/types.ts`, `BusinessScheduleAdminIntegrationTest`, `FirstReplySlaIntegrationTest` | `DEFERRED_UI` |
| Ticket create/update/transfer/child/external-reference UI | `createAgentTicket`, `searchAgentCustomers`, `listTicketAssignmentOptions`, `updateAgentTicket`, `transferAgentTicket`, `createChildTicket`, external-reference create/delete | AGENT/ADMIN subject to group membership and resource authorization; one ticket command produces one ordered TicketAudit; customer search is a routine audited search (`CUSTOMER_SEARCH_EXECUTED`) with protected ciphertext query, no reveal without separate grant | `clientCommandId` is stable for ambiguous retry; expected version controls 409; PUBLIC/INTERNAL drafts survive conflict | `features/ticket-workspace/model/ticketEditorModel*`, `useTicketEditor.ts`, `api/client*`, `AgentCustomerSearchIntegrationTest`, `AgentTicketReadIntegrationTest` (assignment-options), backend ticket command/transfer tests | `FIXTURE_ONLY` |

## 4. Recomposition gate

A deferred capability may return only in its own vertical slice. That slice must:

1. freeze or confirm the listed OpenAPI operation IDs without inventing endpoints;
2. cite its actor, capability/scope, projection, and audit events;
3. reuse or extend the headless seam before composing React UI;
4. implement loading, empty, error, denied, not-found, stale/conflict states as applicable;
5. add current-design Storybook, keyboard/axe, and browser evidence without restoring legacy tokens, shells, screenshots, or compatibility aliases;
6. cite at least one `REQ-*` and an applicable gate from `docs/21-minimum-verification-gates.md`.
