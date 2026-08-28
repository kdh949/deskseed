# Customer Portal Surface Isolation Task Brief

## Goal

고객이 Deskseed Help Center에서 지식 문서를 찾고, 현재 계약이 허용하는 문의·인증 흐름을 사용할 수 있도록 하면서 고객 화면의 디자인과 번들을 상담사 화면에서 완전히 격리한다.

## Decision and source references

- Decision IDs: D-003, D-006, D-030, D-032, D-053, D-057, D-058, D-059, D-061
- Accepted ADRs: 0003, 0006, 0019, 0021, 0029, 0039, 0041, 0042, 0043, 0044
- PRD/domain: docs 01, 02, 28~31, 37, 39, 40, 51, 55, 56
- API operations: `createCustomerRequest`, `getAnonymousRequest`, `addCustomerRequestComment`, `listCustomerRequests`, `getCustomerRequest`, `requestCustomerRegistration`, `verifyCustomerRegistration`, `createCustomerPasswordSession`, `requestCustomerMagicLink`, `consumeCustomerMagicLink`, `getCurrentCustomer`, `deleteCustomerSession`, `listHelpCategories`, `getHelpCategory`, `getHelpSection`, `getHelpArticle`, `searchHelpArticles`, `recordHelpArticleFeedback`
- Verification gates: UI-002, UI-004, UI-005, UI-006, AUTH-001/002/003/004/005/006/008, TKT-001/002, FILE-001/003/004/006, DOC-001

## Actor and source

- Actor type: anonymous CUSTOMER or authenticated CUSTOMER
- Source: CUSTOMER_PORTAL
- Required role/scopes: public Knowledge Base and anonymous request proof, or current customer session
- Resource constraints: ticket-specific request access proof or session-owned customer/ticket
- Interaction/request/correlation semantics: secrets stay out of URL/history/log/audit; customer POST session mutations use CSRF; command retry uses existing client command identity

## Product and UX contract

- Requirement IDs: REQ-KB-001/003/004, REQ-TKT-001/002/003/005/008, REQ-AUTH-001/002/003/004/005/006/008, REQ-CONSENT-002, REQ-FILE-001/003/004/006, REQ-UI-005/006/007
- Screen/route IDs: `/`, `/help/**`, `/requests/**`, `/account/**`, `/customer/**`
- OpenAPI operation IDs: listed above; no new endpoint is invented
- loading/empty/error/denied/conflict states: all reachable states defined by the existing operation contracts
- keyboard/focus/accessibility: WCAG 2.2 AA, keyboard reachability, visible focus, semantic status, reduced motion
- visual regression fixtures and widths: 390, 768, 1024, and 1448 CSS pixels; reference comparison at 1448×1086

## In scope

- separate customer/staff applications, builds, style roots, Storybooks, MCP configuration, and production routing;
- customer-only design tokens, components, shells, illustrations, and route composition;
- contract-backed customer Help Center, requests, password login, registration verification, passwordless magic-link, and logout;
- `announcements` 공개 Knowledge Base section의 published article을 고객 홈 공지로 표시하며 기존 관리자 knowledge draft/revision/publish API로 내용을 관리;
- unit, Storybook, E2E, accessibility, boundary, build, and visual evidence.

## Out of scope

- profile/avatar/preferences/notification settings, organization/team/role screens, SSO/MFA;
- live chat, phone, inbound email, 별도 announcement/system-status 도메인과 전용 관리자 편집 화면;
- customer-visible SLA, internal priority/assignee/source, request aggregate counts, automatic related-article suggestions;
- password-reset screens, because no selected reference in this customer-screen batch requires them;
- backend schema, migration, authorization, audit, or API wire-shape changes.

## Invariants and failure semantics

- Ticket body remains the first PUBLIC comment and INTERNAL data never enters a customer projection or DOM.
- Ticket number is not authorization. Matching email never claims historical tickets.
- Request proof is removed from URL fragments before network use and stored only in ticket-scoped session storage.
- Required audit persistence failure never becomes customer-visible success.
- Existing idempotency, replay, concurrency, attachment, CSRF, token single-use, and session-rotation semantics are preserved.
- No external network call is introduced inside a ticket transaction.

## Data and privacy

- Data read/written: customer email/profile, PUBLIC comments, consent references, private attachment metadata, and opaque proofs.
- PII/secrets: passwords and proofs are write-only and never logged, audited, persisted in browser storage, or reflected in errors.
- Retention/redaction: existing customer, consent, request, attachment, and security policies are unchanged.
- Export/webhook exposure: none added.

## Threats changed

- Cross-surface style/import drift is reduced by build-time boundaries.
- Existing authorization, replay, XSS-safe KB block rendering, secret leakage, and customer/staff projection isolation remain regression targets.

## Acceptance scenarios

- Given either application, when it is built, then its manifest contains no source, CSS, token, provider, or story from the other application.
- Given a customer route hard refresh, when Nginx resolves it, then only the customer HTML and `/_customer/` assets load.
- Given a staff route hard refresh, when Nginx resolves it, then only the staff HTML and `/_staff/` assets load with unchanged visual baselines.
- Given public KB data, when a customer searches or opens an article, then only audience-safe fields and canonical blocks render.
- Given an anonymous request proof or authenticated session, when a customer views or replies, then only PUBLIC conversation and attachment metadata render.
- Given invalid, expired, replayed, throttled, or unavailable customer authentication, when the UI handles the response, then it shows a generic recoverable state without revealing account or proof status.

## Validation

- `npm run check:design-system-boundaries`
- per-app lint, typecheck, unit test, Storybook build/test, and Vite build
- Playwright route, security, keyboard, accessibility, and responsive scenarios
- visual comparison against selected customer references at 1448×1086
- `git diff --check`

## Compatibility and migration

- OpenAPI change classification: no wire change; UI route catalog and delivery ownership change only.
- migration/rollback/backfill: none.
- existing client/UI impact: one image and origin, two static application roots; existing cookies and links remain compatible.

## Human explanation

Two deployable frontend roots are the smallest boundary that prevents accidental design-system cross-reference by both people and Codex. Shared runtime UI would weaken that guarantee; shared immutable brand sources do not.
