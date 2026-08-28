# AGENTS.md — Customer Portal Boundary

This file applies to `frontend/apps/customer-portal/` and extends both parent instruction files.

## Ownership

- This app exclusively owns public Help Center, customer authentication, account, and request routes.
- Production code must not import from `apps/staff-console`, its package name, its assets, or its design system.
- Customer presentation imports the public API from `src/design-system/`. Direct Garden imports are permitted only inside that directory.
- Customer tokens live only in `src/design-system/tokens.css`. Do not consume staff `--ds-*` variables or duplicate a staff token file.
- Customer feature code may use only frozen Customer and Help Center HTTP contracts. It must not call staff/admin endpoints or expose internal comments, audit metadata, child relations, or staff-only fields.

## Routes and states

- Owned routes are `/`, `/search`, `/articles/*`, `/requests/*`, `/customer/*`, and `/account/*`.
- Preserve ticket access proof in session storage. A ticket number is never authorization and proof must not be placed in a query string.
- New or changed screens cover applicable loading, empty, error, denied, stale/conflict, and success states without inventing endpoints.

## Typography policy

- Do not add ornamental eyebrow, kicker, overline, supertitle, pretitle, pre-heading, or micro-heading copy above customer headings.
- Do not force customer copy to uppercase with CSS. Acronyms such as SSO may remain uppercase only when uppercase is part of the actual product term.
- Start each content region with its semantic heading and place necessary context in normal body copy, breadcrumbs, status, or metadata rather than a small letter-spaced label.
- `npm run check:design-system-boundaries` rejects the prohibited class concepts and uppercase transformation inside the customer source tree.

## Customer copy policy

- Server authorization, projection, and tests guarantee the customer's data boundary. Do not explain that boundary with defensive copy in the rendered customer UI.
- Use terms customers act on: `내 문의`, `문의 대화`, `답변`, `첨부 파일`, `로그인 상태`, and `문의 접수 가능 여부`.
- Production customer TSX must not render implementation terms such as `PUBLIC`, `INTERNAL`, `projection`, `fragment`, `고객 API`, `고객 세션`, `접근 토큰`, `명령 식별자`, `새 명령`, `CLEAN 상태`, `공개 대화/답변/문의`, or `접수 설정`.
- When access or delivery fails, describe the next action without revealing whether an account or request exists. Request IDs and actionable retry guidance may remain.
- Security and projection terminology may remain in tests, Storybook documentation, API adapters, and developer documents. Story canvas copy must still follow the customer language policy.
- `npm run check:design-system-boundaries` enforces these terms in production customer TSX while excluding tests, stories, API adapters, and developer documentation.

## Storybook and verification

- Run package commands from `frontend/`; use this directory for customer Storybook MCP discovery through `.mcp.json`.
- Customer Storybook uses port `6007` and must not load staff stories or styles.
- Required gates include `npm run test:customer`, `npm run build:customer`, and `npm run check:design-system-boundaries` plus the parent Storybook MCP workflow when available.
