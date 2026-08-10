# Codex Brief 07 — Staff Authentication and Groups

## Goal

An operator can bootstrap the first ADMIN without committing a credential, staff can use a bounded cookie session, and only ADMIN can manage staff, groups, and memberships through API and UI.

## Decision and source references

- Requirements: REQ-AUTH-005/006, REQ-PERM-002, REQ-TKT-010/011, REQ-UI-001/005.
- Decisions: D-001/002/008/009/013/018/030/039/047.
- ADRs: 0001, 0002, 0008, 0009, 0013, 0018, 0019, 0021, 0028, 0035.
- Screens: AGT-001, ADM-002, ADM-003.
- Gates: ARCH-001/002/004, ACC-007, AUD-001, UI-002/004/005.

## Actor and data boundaries

- Login actor is unauthenticated until credentials pass; accepted sessions create a STAFF actor sourced from AGENT_UI.
- Organization mutations require active STAFF/ADMIN and source ADMIN_UI at both HTTP and method boundaries.
- Admin/security audit is canonical and separate from TicketAudit. Passwords, CSRF tokens, session cookies, and bootstrap secret values never enter responses, audit, or ordinary logs.
- Customer APIs and customer identity are unchanged.

## In scope

- StaffAccount with one initial role ADMIN or AGENT and ACTIVE/DISABLED status.
- SupportGroup and active/inactive GroupMembership.
- Password-file first-admin bootstrap, email/password login, logout, session expiry, CSRF, generic errors, and PostgreSQL-backed throttling.
- Admin staff create/disable, group create/rename/disable, membership add/remove.
- API/method authorization and frontend route guard.
- Migration, core OpenAPI, admin routes, integration/component/browser tests, and audit events.

## Out of scope

- Customer account auth, SSO/OAuth/MFA, password reset email, multi-role grants, group NONE/READ/READ_WRITE, and ticket queue/workspace implementation.

## Invariants and failure semantics

- Normalized staff email is unique; active membership pair is unique.
- Disabled staff cannot log in or reuse an existing session.
- Self-disable, last-active-ADMIN disable, assigned staff disable, assigned group disable, and assigned membership removal are rejected.
- Organization mutation and AdminSecurityAudit commit or roll back together.
- Duplicate membership and uniqueness conflicts return 409 with no partial change.
- Login errors do not distinguish unknown, wrong-password, or disabled accounts; throttling returns 429 with Retry-After.

## Acceptance scenarios

- Correct ADMIN/AGENT login succeeds; wrong and disabled login return the same generic credential problem.
- Logout and idle/absolute expiry invalidate protected access.
- AGENT receives 403 from admin APIs and sees a denied page for a direct `/admin/...` URL without admin data fetch.
- ADMIN creates/disables staff, creates/renames/disables groups, and adds/removes memberships.
- Duplicate active membership returns 409 and exactly one failed outcome is visible through the HTTP contract without a membership change.
- CSRF is required for login/logout/admin writes; session cookie and security headers match development and production profiles.

## Verification

- PostgreSQL migration from empty and previous M1 schema.
- Backend Spring/Testcontainers integration tests, architecture verification, and full Gradle test/build.
- Frontend component tests, Playwright route-guard/admin workflow, axe/keyboard checks, typecheck/lint/build.
- Full-stack login/admin/direct URL scenario through the same-origin reverse proxy.
