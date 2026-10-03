# 상담사 티켓 저장·이관 후 상태 정합성

## Goal

상담사가 이관 후 현재 담당자와 편집 중인 필드를 구분하고, 실제 저장 대상·실패·확정 성공 및 복구 초안 정리 결과를 이해한다.

## Decision and source references

- Decisions: D-008, D-032, D-033, D-041, D-047, D-049, D-062
- Accepted ADRs: 0020, 0021, 0039, 0040, 0045
- Requirements: REQ-TKT-007/010/012/013/014/015, REQ-COL-001, REQ-UI-003/004/005
- Sources: docs 21/28~31/34/40/48/50/51/55, UX audit AG-F01/02/05/10 and relevant AG-F11 copy
- Operations: getAgentTicket, updateAgentTicket, transferAgentTicket, get/save/clearAgentTicketDraft (existing contracts)
- Gates: UI-003/004/005/006, CONC-001, IDEM-001/003/004, DOC-001

## Actor and source

- Active STAFF / AGENT_UI with existing server-projected READ/UPDATE and assignment options.
- Existing expected-version, client command ID and BACKGROUND read semantics remain authoritative. No semantic TICKET_VIEWED is added for reconciliation.

## Product and UX contract

- New detail versions update untouched fields. Locally changed fields survive and use existing field-aware conflict decisions when the same server field changed.
- An ambiguous pending command retains its exact payload/base/ID; background detail cannot establish its outcome.
- Save labels distinguish field-only, comment-only and combined writes. PUBLIC/INTERNAL drafts remain separate.
- Existing ProblemDetails/fieldErrors drive Korean recovery guidance without treating service configuration as an input fix or inventing new HTTP contracts.
- Confirmed ticket success is not reversed when recovery-draft cleanup fails. Cleanup uses the known optimistic draft version and preserves newer server drafts.
- Reuse documented notice/button/composer/field controls. No new design-system component or presentation API is planned.

## In scope

- Ticket editor reconciliation, save labels and safe failure details.
- Reproducible confirmed-save/draft-cleanup lifecycle regression using existing draft APIs.
- Unit, Storybook and browser regression at documented viewports.

## Out of scope

- Session/authentication redesign or a claim that the audit's single logout observation identifies an authentication defect.
- New API, command receipt endpoint, provider/mail implementation, permissions or server draft retention.
- Workspace redesign/tabs/resize/AI/presence and unrelated extension mutations.

## Invariants and failure semantics

- One combined command remains one transaction/audit; client cannot claim partial ticket success.
- Transfer moves ownership; child creation does not. Current ticket row remains source of truth.
- Assignees remain current-group active members; server enforces all scope/resource constraints.
- Confirmed success clears only its submitted composer channel before detail refresh. An unsent other channel remains.
- Draft cleanup failure/conflict is a separate warning, never a reason to replay the successful ticket command or delete a newer draft by content similarity.
- Network/5xx ambiguity retains original command identity. Validation/conflict preserves user input and follows existing explicit recovery.

## Data and privacy

- Existing staff/ticket/channel draft storage and retention boundaries remain. No comment bodies, search queries, credentials or provider settings are added to logs/URLs/telemetry.
- Customer projection, PUBLIC/INTERNAL authorization, attachment visibility and audit storage are unchanged.

## Threats changed

- Verify stale field overwrite, double-submit guidance, draft cross-channel deletion and deletion of newer server drafts.
- No new external I/O, SSRF, authorization or impersonation boundary.

## Acceptance scenarios

1. A completed transfer refreshes header and untouched assignee field consistently. A later status-only save excludes the previous assignee.
2. A locally edited field survives a newer detail; same-field server changes require the existing conflict choice.
3. PUBLIC validation failure keeps both drafts and renders actionable Korean guidance plus the request ID and known field feedback.
4. Field-only, comment-only and combined save controls describe the actual command.
5. Confirmed comment success cleans only the submitted channel before refresh/unmount; other-channel drafts survive.
6. Draft clear failure or newer draft-version conflict keeps the ticket save successful and preserves the newer remote copy. A lost ticket response still retries the exact original ID/payload.

## Validation

- Focused editor/model/draft-sync unit tests and full staff tests.
- Storybook MCP instructions/documentation, focused stories, changed-story previews; full suite if shared impact warrants it.
- Ticket write/browser keyboard/conflict/draft regressions, canonical 1280/1440/1920 visual inspection and Axe.
- Typecheck, staff build, lint, design-system boundaries, docs/diff checks.
- Backend/PostgreSQL and production deployment are not required for unchanged HTTP/domain contracts and will be reported as not run.

## Compatibility and migration

- No OpenAPI/DB migration. Existing API and optimistic draft versions are retained. Rollback is a frontend commit revert.

## Human explanation

Existing confirmed/local field separation and draft optimistic versions already express the required consistency. The narrow correction is to reconcile new projections and order confirmed cleanup before refresh, without replacing authentication or draft storage.

## Completion report

- Implemented newer-detail field reconciliation using the existing conflict model; background detail never changes a pending ambiguous command's base, payload or ID.
- Added field-only/combined save labels and Korean failure guidance with existing allowlisted field errors and request ID. Generic server validation is not diagnosed as user input or provider configuration.
- Confirmed comment writes immediately clean only their channel, serialized after an in-flight draft save and before detail refresh. Unknown/newer versions and other channels remain; cleanup 409/503 never replays a successful ticket command. Recovery after a failed cleanup can still contain the remote draft, with explicit review guidance rather than a new receipt/retention mechanism.
- Passed: staff unit 43 files/263 tests; final editor/draft-sync regressions 2 files/21 tests; typecheck, lint, staff build (existing large-chunk advisory), design-system boundary 4 tests plus checker; full staff Storybook MCP 61 files/294 stories; Chromium development E2E 23 tests including transfer → field-only keyboard save, unchanged ownership command payload and Axe at 1280/1440/1920. Inspected the three viewport captures: field labels, selection and save button remain visible without overlap. Supporting APIs omitted by the E2E fixture render their existing error notices.
- Storybook MCP: documentation list/instructions and relevant Seed public APIs read before editing; focused stories, changed-story lookup and previews used. The component dependency lookup was supplemented with source consumers because the tool reported a graph gap.
- Preview: http://localhost:6006/?path=/story/06-domain-workspace-agentticketeditorworkspace--save-target-labels
- Preview: http://localhost:6006/?path=/story/06-domain-workspace-agentticketeditorworkspace--validation-feedback
- Preview: http://localhost:6006/?path=/story/06-domain-workspace-agentticketeditorworkspace--background-ownership-update
- Preview: http://localhost:6006/?path=/story/06-domain-workspace-agentticketeditorworkspace--confirmed-save-draft-conflict
- Docs/diff gates and remote CI are recorded with the delivery PR. REQ statuses remain implemented; no capability status changes.
- Not implemented/run: authentication redesign, provider/mail backend changes, automatic suppression of a remote draft whose cleanup failed, PostgreSQL/backend tests, production mutation/deployment, or a new performance benchmark. Existing command/audit, authorization, retention and API semantics are unchanged; no migration or server rollout dependency.
