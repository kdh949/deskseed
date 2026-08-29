# Ticket Workspace precision reconstruction — Design QA

## Evidence

- Source visual truth: `/Users/donghyunkim/Downloads/DeskSeed-새로운 화면/상담사/상담사_티켓 워크스페이스.png`
- Source pixels: 1448 × 1086.
- Browser-rendered implementation, responsive reference viewport: `/Users/donghyunkim/.codex/visualizations/2026/08/28/01a04a08-e94b-7490-b877-0d7022de28b8/ticket-workspace-route-1448-final.jpg`
- Browser-rendered implementation, full context rail: `/Users/donghyunkim/.codex/visualizations/2026/08/28/01a04a08-e94b-7490-b877-0d7022de28b8/ticket-workspace-route-1600-final.jpg`
- Implementation pixels/CSS viewport: 1448 × 1086 and 1600 × 1086 at 1:1 capture density.
- Full-view comparison: `/Users/donghyunkim/.codex/visualizations/2026/08/28/01a04a08-e94b-7490-b877-0d7022de28b8/ticket-workspace-comparison-1448.jpg`
- Focused context comparison: `/Users/donghyunkim/.codex/visualizations/2026/08/28/01a04a08-e94b-7490-b877-0d7022de28b8/ticket-context-comparison.jpg`
- State: authenticated writable Deskseed agent ticket with four PUBLIC/INTERNAL comments, SLA, structured editor, macros, collaboration, related ticket, external reference, recent activity, and no pending error.

## Required fidelity surfaces

- Typography: compact 12–14 px control/body scale, 600-weight action labels, dense line height, tab hierarchy, and metadata contrast match the reference's visual rhythm. Deskseed's licensed/system font stack is retained rather than copying proprietary font assets.
- Spacing/layout: dark 168 px navigation, compact top and ticket headers, 288 px property column, flexible conversation column, 320 px context rail above 1500 px, 1 px dividers, low-radius controls, and bottom composer match the source composition. At 1448 px the context rail intentionally becomes the documented accessible drawer.
- Colors/tokens: dark teal frame, off-white workspace, neutral borders, mint positive badges, amber INTERNAL surface, blue active indicators, and compact elevation are all canonical tokens shared by the route and stories.
- Image/icon quality: no screenshot pixels, cropped reference assets, CSS drawings, emoji controls, or inline SVG are shipped. The approved Deskseed brand asset and canonical icon-library assets are used; avatars remain initials because the contract has no avatar URL.
- Copy/content: all labels are actual HTML and Korean product copy. Dynamic content follows the OpenAPI fixture rather than copying unsupported customer details from the reference.
- Responsive/accessibility: 1448 × 1086, 1600 × 1086, and the 200% equivalent 724 × 543 checks have zero page-level horizontal overflow. Drawer Escape restores focus, editor/toolbars are keyboard reachable, states are not color-only, and Storybook accessibility tests pass.

## Comparison history

- [P1 resolved] Properties previously used generic controls with different density. Canonical compact status/group/assignee choices and the calendar read-only field now match the reference's icon/value/clear/caret anatomy.
- [P1 resolved] The composer lacked the reference's editor and action hierarchy. The lazy rich-text editor now supplies block formatting, marks, lists, alignment, link, ticket-local attachment image, emoji, code, quote, macro review, attachment, explicit draft save, and split send action.
- [P1 resolved] Context content previously read as unrelated panels. Customer, related ticket, collaboration, presence, external reference, and recent activity now share one dense `SeedContextCard` grammar with matching count/status badges and header actions.
- [P2 resolved] Conflict actions and regular buttons were oversized. `SeedButton` compact uses the reference-like 28 px control, 12 px/600 label, 4 px radius, visible focus, and the conflict bar sits immediately above the composer.
- [P2 resolved] The rich editor initially inflated the main bundle. It now loads as a 399.07 kB/125.75 kB gzip lazy chunk, reducing the initial main chunk by 399.36 kB/125.63 kB gzip.
- Post-fix comparison found no actionable P0/P1/P2 mismatch within the committed contract. Contract-only omissions and the user-selected 1448 px drawer rule are intentional, documented differences.

## Interaction and runtime evidence

- Verified actual route at 1448 and wide desktop, context drawer open/Escape/focus return, editor lazy-load, and 200% equivalent layout.
- Verified 100-comment Storybook state: 100 all → 20 INTERNAL → 100 all, with zero horizontal overflow. Browser-control round trips were 320 ms and 305 ms and include automation transport, so they are not presented as pure render timings.
- Storybook MCP: all 60 stories passed with accessibility checks.
- Actual route console: zero warning/error entries in the final browser pass.

## Residual boundaries

- The reference-only phone/address/local-time/join-date fields, Cc/Bcc, remote image URLs, arbitrary HTML/style/iframe, and editable collaboration notes remain omitted because their contracts are absent or explicitly disallowed.
- The 805.35 kB main chunk still triggers Vite's existing 500 kB advisory even after rich-editor code splitting; this is a non-blocking follow-up optimization, not a visual fidelity defect.

final result: passed
