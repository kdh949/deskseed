# Ticket Workspace Rich Content and Collaboration Task Brief

## Goal

상담사가 첨부된 워크스페이스 시각 기준과 같은 밀도의 화면에서 장식형 속성 선택, 안전한 리치 답변, 매크로, 명시적 초안 저장, 별도 협업 메모와 멘션 알림을 실제 계약으로 사용할 수 있다.

## Decision and source references

- Decision IDs: D-003, D-005, D-007, D-018, D-030, D-031, D-032, D-037, D-041, D-042, D-062, D-063
- Accepted ADRs: 0003, 0007, 0026, 0030, 0039, 0040, 0045, 0046
- Requirements: REQ-TKT-007/013/014, REQ-FILE-001/002, REQ-CFG-003, REQ-COL-001/002/003, REQ-UI-001/003/004/005/006
- API operations: `getAgentTicket`, `updateAgentTicket`, `getAgentTicketDraft`, `saveAgentTicketDraft`, `listAccessibleMacros`, `previewTicketMacro`, `applyTicketMacro`, `listTicketCollaborationNotes`, `createTicketCollaborationNote`, `listAgentNotifications`, `markAgentNotificationRead`
- Verification gates: FILE-002/003/004, TKT-001/006, CHG-001/002/003, PERM-001/002, AUD-001/002, IDEM-001, UI-001/002/003/004/005/006

## Actor and source

- Actor: authenticated STAFF AGENT or ADMIN
- Source: AGENT_WORKSPACE / AGENT_UI
- Resource constraints: global staff read policy; ticket write remains current assignee or active current-group member; mention target must be active and ticket-readable
- Sensitive reads: explicit ticket navigation and collaboration-thread view are audited; background revalidation is not a semantic view

## Product and UX contract

- Route: `/agent/tickets/:ticketNumber`
- Visual reference: `상담사_티켓 워크스페이스.png`; hierarchy, density, spacing, and component anatomy only
- 1448px keeps the accessible context drawer; above 1500px displays the full rail
- Required states: loading, empty, error, denied, validation, conflict, read-only, stale macro preview, notification reconnect, responsive drawer
- Keyboard: decorated listboxes, editor toolbar, split buttons, mention combobox, drawers, and focus restoration are release gates

## Invariants and failure semantics

- Ticket body remains the first ordered comment; rich content does not add `Ticket.description`.
- PUBLIC and INTERNAL content/drafts/attachments remain server-authorized and isolated.
- Ticket command, comment, attachment links, mail intent, and TicketAudit remain atomic.
- Rich validation, note authorization, mention authorization, audit failure, or notification persistence failure returns no partial success.
- Ambiguous ticket and collaboration-note retries reuse stable command identities.
- No raw comment/note body, document JSON, search query, token, or secret enters ordinary logs or audit metadata.

## Compatibility

- Existing `body` input/output remains supported; new responses add a closed `content` envelope.
- Existing comment/draft rows become `PLAIN_TEXT` without rewriting body values.
- Collaboration notes and notifications are additive staff-only resources.
- Rich PUBLIC comments render in the customer portal; INTERNAL comments and collaboration notes remain absent from customer API and DOM.
