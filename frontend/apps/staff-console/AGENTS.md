# AGENTS.md — Staff Console Boundary

This file applies to `frontend/apps/staff-console/` and extends both parent instruction files.

## Ownership

- This app exclusively owns agent and administrator routes under `/agent/*` and `/admin/*`.
- Production code must not import from `apps/customer-portal`, its package name, customer assets, or customer design-system contracts.
- Staff presentation imports the public API from `src/design-system/`. Direct Garden imports remain restricted to that directory.
- Staff tokens remain under `src/design-system/foundations/`. Do not consume customer `--customer-*` variables.
- Do not add public customer routes or customer-facing components to this app even when they share a domain entity.

## Storybook and verification

- Run package commands from `frontend/`; use this directory for staff Storybook MCP discovery through `.mcp.json`.
- Staff Storybook uses port `6006` and must not load customer stories or styles.
- Required gates include `npm run test:staff`, `npm run build:staff`, and `npm run check:design-system-boundaries` plus the parent Storybook MCP workflow when available.

## Visual design

- Staff page and panel titles begin with their semantic Korean heading. Do not add ornamental eyebrow, kicker, overline, or CSS-forced uppercase micro-heading copy above them.
- Staff ticket creation uses one operational workspace with ticket properties beside the subject and first-comment composer. Do not present the required fields as a numbered wizard or card-by-card onboarding flow.
