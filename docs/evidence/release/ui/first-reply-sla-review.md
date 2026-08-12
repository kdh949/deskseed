# First Reply SLA UI review

- Date: 2026-08-12 (Asia/Seoul)
- Browser: Codex in-app Chromium against an isolated real Docker Compose stack
- Result: PASS for the scoped direct review; screen-reader sign-off was not run

## Scenario

1. Logged in as a synthetic ADMIN through the real staff session flow.
2. Created and activated `고객지원 First Reply` v1 against `Default Support Hours` v1.
3. Previewed a NORMAL ticket submitted Friday 18:30 KST; the 240-business-minute target
   resolved to Monday 13:00 KST with the recorded schedule and DST policy.
4. Submitted ticket #1000, assigned it to `SLA 지원팀`, and opened its workspace.
5. Filtered `내 open` by `First Reply SLA = ACTIVE` and confirmed the same ticket.

## Observations

- Admin policy page exposes the reconciled fact summary, immutable version editor,
  allowlisted group/channel conditions, priority targets, pause statuses, preview, and
  activation controls.
- Ticket workspace shows `First Reply · ACTIVE`, the due instant, policy v1, and schedule
  v1 without exposing the data to the customer surface.
- Views exposes a labelled SLA state filter and the matching ticket projection.
- DOM snapshot contained the expected headings, landmarks, field labels, selected ACTIVE
  option, and non-color state text. Browser console log list was empty after all flows.
- Authorization/audit denial and rollback behavior are covered by
  `FirstReplySlaAdminIntegrationTest`; this direct browser session exercised the allowed
  ADMIN path.

## Screenshots

- `frontend/e2e/__screenshots__/darwin/first-reply-sla-admin-1440.png`
- `frontend/e2e/__screenshots__/darwin/first-reply-sla-ticket-1440.png`
- `frontend/e2e/__screenshots__/darwin/first-reply-sla-views-1440.png`

The captures use synthetic names and comments only. No password, cookie, authorization
header, customer contact value, or raw audit payload is present.

## Open human gate

- VoiceOver/NVDA announcement and reading-order smoke: NOT RUN.
