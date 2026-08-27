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

## Storybook and verification

- Run package commands from `frontend/`; use this directory for customer Storybook MCP discovery through `.mcp.json`.
- Customer Storybook uses port `6007` and must not load staff stories or styles.
- Required gates include `npm run test:customer`, `npm run build:customer`, and `npm run check:design-system-boundaries` plus the parent Storybook MCP workflow when available.
