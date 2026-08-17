# P1 frontend operations UI

## Goal

상담사와 감사 담당자가 서버 저장형 보기, 전체 검색, 일괄 작업, SLA, 외부 참조, 첨부, 감사 내보내기를 하나의 계약 일치형 Deskseed UI에서 안전하게 사용한다.

## Decision and source references

- Decision IDs: D-015, D-018, D-019, D-020, D-021, D-022, D-023, D-026, D-032, D-033, D-036, D-037, D-039, D-053
- Accepted ADRs: ADR-0014, ADR-0015, ADR-0018, ADR-0019, ADR-0020, ADR-0021, ADR-0022, ADR-0023, ADR-0026, ADR-0032, ADR-0033, ADR-0036, ADR-0037, ADR-0039
- PRD/domain: `docs/01-prd-mvp.md`, `docs/02-domain-model.md`, `docs/46-saved-views-queue-search-bulk.md`, `docs/47-saved-views-search-bulk-sla-detailed-spec.md`, `docs/48-attachments-audit-export-detailed-spec.md`
- API contract operation IDs: `listAgentViews`, `createAgentSavedView`, `previewAgentSavedView`, `reorderAgentSavedViews`, `updateAgentSavedView`, `deleteAgentSavedView`, `listTicketsInView`, `searchAgentWorkspace`, `listTicketAssignmentOptions`, `executeAgentTicketBatch`, `getAgentTicket`, `listAgentTicketExternalReferences`, `createAgentTicketExternalReference`, `deleteAgentTicketExternalReference`, `createAgentAttachmentUpload`, `downloadAgentAttachment`, `createCustomerAttachmentUpload`, `downloadCustomerAttachment`, `createAuditExport`, `getAuditExport`, `downloadAuditExport`
- Verification gates: UI-001 through UI-005, FILE-001, FILE-003, FILE-004, FILE-006, ACC-003, ACC-004, AUD-003, AUD-004, EXT-001 through EXT-004, CHG-001, CHG-002, IDEM-001, CONC-001

## Actor and source

- Actor type: STAFF and CUSTOMER
- Source: AGENT_WORKSPACE, AUDIT_EXPLORER, CUSTOMER_PORTAL
- Required role/scopes: server session and resource authorization remain authoritative; shared view mutation and audit export require the corresponding server capability.
- Resource constraints: queue/search/bulk/detail/external reference operations only expose tickets authorized by the server; attachment download rechecks comment visibility and ownership.
- Interaction/request/correlation semantics: search keeps one interaction ID across opaque cursor pages; opening a result forwards `X-Origin-Search-Event-Id`; external-reference and attachment reads mint deliberate interaction IDs; background refresh never claims `TICKET_VIEWED`.

## Product and UX contract

- Requirement IDs: REQ-VIEW-001, REQ-SRCH-001, REQ-BULK-001, REQ-SLA-001, REQ-INT-005, REQ-FILE-001, REQ-AUDX-001, REQ-UI-001 through REQ-UI-006
- Routes: `/agent/views/:viewKey`, `/agent/search`, `/agent/tickets/:ticketNumber`, `/agent/audit`, `/agent/audit/exports/:jobId`, `/requests/new`, `/requests/:ticketNumber`, `/account/requests/:ticketNumber`
- Zendesk parity: documented properties/conversation/context information architecture only; no proprietary pixels or assets.
- States: loading, empty, error, denied, stale/conflict, partial success, expired, upload/scanning/clean/rejected.
- Accessibility: named controls and regions, text plus icon for SLA state, keyboard-operable tables/drawers/tabs, deterministic focus on errors/conflicts and restored focus on drawer close.
- Visual fixtures: Storybook interaction and axe coverage at documented desktop/mobile widths; Playwright covers contract-backed routes.

## In scope

- Replace browser-local saved views with PERSONAL/SHARED/SYSTEM server definitions and exact counts.
- Queue assignment options, SLA filtering/display, opaque cursor history, explicit local-page search semantics, and explicit-selection bulk commands.
- Server-side search with protected request body, exact count, cursor history, and origin search audit hand-off.
- Workspace SLA detail, lazy external reference CRUD, safe backend-returned deep links, agent/customer attachment upload and authorized download.
- Audit export terminal-state polling/download/expired regeneration path.
- OpenAPI operation manifest, shared MSW fixtures, decoder tests, Storybook stories, and Playwright contract checks.
- Requirement traceability and current-surface documentation updates after the gates pass.

## Out of scope

- Saved-view tags, custom fields, arbitrary scripts, or bulk comments.
- Selecting all results across pages.
- Client-side calculation of canonical business-time remaining.
- Frontend construction of external URLs or backend fetch of external systems.
- P0 screen redesign.
- Saved-view description persistence: the FROZEN `SavedViewDefinition`, `CreateSavedView`, and `UpdateSavedView` schemas have no description property. Adding it requires a separately reviewed contract/backend slice; this UI must not send an undocumented field.

## Invariants and failure semantics

- PUBLIC/INTERNAL boundaries remain server projections; customer UI never renders INTERNAL attachments.
- Bulk contains 1-100 explicitly selected unique ticket numbers, one expected version and stable command ID per item; transfer always requires a reason.
- CLEAN attachment handles alone can be submitted; pending/rejected uploads block comment/request submission.
- External links use only `safeUrl` returned by the backend and open with `noopener,noreferrer`.
- Saved-view update/delete uses the current definition version; conflicts preserve the local draft and offer reload/retry.
- Polling stops at READY, FAILED, EXPIRED, or unmount.
- Ordinary URL, analytics, and logs never receive a raw ticket search query.
- Frontend retries never broaden authorization or synthesize success after an audit persistence failure.

## Data and privacy

- Raw search query lives only in component memory and the POST body.
- Attachment bytes use multipart uploads and authorized binary downloads; object keys/checksums/scan details are never shown.
- Audit export downloads are short-lived, no-store artifacts and are available only in READY.
- No secret, token, raw query, customer comment body, or external credential is logged or placed in route parameters.

## Threats changed

- Authorization bypass: keep every protected read/write on the existing server client and surface denied/not-found safely.
- Replay/duplicate: retain stable per-item bulk command IDs and expected versions for failed-item retry.
- SSRF/XSS: never resolve external identifiers or fetch/open unvalidated URLs.
- Secret leakage: raw search and customer capability tokens stay out of URL and ordinary state reporting.
- Audit bypass: search-origin and deliberate read interaction headers are mandatory.
- Concurrency/data loss: preserve drafts and present version conflicts rather than overwriting.

## Acceptance scenarios

- Given a PERSONAL view, when the user refreshes or signs in again, then the server definition and count reappear without local fixture state.
- Given a saved view draft, when preview succeeds, then exact count and sample rows are announced; when update returns conflict, then focus moves to a conflict message and the draft remains.
- Given queue or search results, when filters, view, or sort change, then cursor history resets; previous/next uses opaque cursors only.
- Given a raw search query, when searching and opening a result, then the query is absent from the URL and the detail read receives the origin search event ID.
- Given up to 100 explicit selections, when a bulk command completes, then each result is shown as SUCCEEDED, CONFLICT, DENIED, NOT_FOUND, or VALIDATION_FAILED and only failed items can be retried.
- Given SLA data, when rendered in queue/search/workspace, then text and icon are present; policy/schedule versions appear in workspace and no browser-derived business time is shown.
- Given the external-reference tab, when opened, then it lazy-loads stored data; create/delete honors expected version and safeUrl alone can open a new tab.
- Given an upload, when it is pending or rejected, then submit is blocked and navigation warns; when CLEAN, its handle is linked in the command.
- Given an audit export, when status is non-terminal, then polling continues; READY alone enables download; EXPIRED offers a new export path.

## Validation

- `npm run contract:check`
- `npm run typecheck`
- `npm run lint`
- `npm run format:check`
- `npm run test`
- focused and full Storybook MCP `run-story-tests`, then `npm run test:storybook`
- `npm run check:design-system-boundaries`
- `npm run build`
- `npm run test:e2e`
- related Playwright real-stack E2E documented by `docs/50-codex-implementation-runbook.md`

## Compatibility and migration

- OpenAPI classification: no contract change; the existing FROZEN P1 operations are consumed.
- Migration/backfill: none in this frontend slice.
- Existing UI: P0 routes and information architecture remain; P1 controls are additive or replace only explicit browser-local placeholders.

## Human explanation

- Server-held definitions, versions, audit IDs, SLA timestamps, and safe URLs are authoritative because they cross sessions and authorization boundaries.
- Component-local state is limited to drafts, current-page selection, upload progress, and opaque cursor history.
- The simplest sufficient technology is the existing React Router, TanStack Query, Deskseed design system, MSW, Vitest, Storybook, and Playwright stack.
- A contract change, rather than UI inference, is required before saved-view descriptions can persist.

## Completion report

The PR report must include changed routes/components, consumed operationIds, Storybook stories and preview URLs, exact gate results, unrun validation, contract blockers/non-goals, migration/rollback notes, performance evidence, and P0 regression status.
