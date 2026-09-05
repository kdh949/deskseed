# Deskseed Design System Manifest

## Status

`CURRENT / CANONICAL`

ADR 0044 defines two isolated production design systems. Their canonical roots are:

```text
frontend/apps/staff-console/src/design-system/
frontend/apps/customer-portal/src/design-system/
```

Each application imports only its own `src/design-system/index.css` entry. Development routes, production routes, fixtures, visual checks, features, pages, and shells may not cross those application roots. There is no shared runtime UI package. `frontend/brand-assets/` may contain immutable Deskseed source brand assets and license notices only.

## Allowed consumers

```text
features/*
pages/*
shells/*
fixtures/*
```

Feature code may compose domain data and route state, but reusable presentation belongs to the owning application's `src/design-system/**`.

## Forbidden UI roots and concepts

```text
frontend/apps/customer-portal/src/shared/ui/
frontend/apps/staff-console/src/shared/ui/
frontend/apps/*/src/styles/tokens.css
legacy AgentShell
DesignPreviewAgentShell
VITE_DESIGN_PREVIEW UI selection
```

Cross-application imports, compatibility exports, cross-surface token aliases, fallback shells, duplicate wrappers, and feature-local clones are not allowed.

## Canonical elements

- Staff tokens use `--ds-*`; the existing controls, status, Queue, Workspace, Admin, and Audit contracts belong only to `staff-console`.
- Customer tokens use `--customer-*`; Help Center, customer authentication, request, article, and customer shell contracts belong only to `customer-portal`.
- Each application owns a local brand component and icon registry. The underlying immutable Deskseed mark may come from `frontend/brand-assets/`.

## Extension rule

Do not create a reusable feature-local UI clone. When a required element is missing:

1. Review the current design system.
2. Design a reusable contract.
3. Add it under the owning application's `src/design-system/`.
4. Consume the canonical export from the feature.

Direct Garden imports are restricted to each app's design-system root. `npm run check:design-system-boundaries` resolves relative and aliased import specifiers before verifying import, token, story, asset, and build-manifest isolation in CI.

## Visual contract

Agent Queue, Ticket Workspace, staff ticket creation, staff login, Admin, Audit, and their common states retain the Staff Console Deskseed grammar: compact neutral workspaces, dark-teal operational chrome, semantic `--ds-*` tokens, and existing focus/hover/disabled behavior. Staff content regions begin with their semantic Korean heading and do not use ornamental eyebrow, kicker, overline, or CSS-forced uppercase micro-heading copy. Staff ticket creation is a single operational workspace with properties beside the subject and first-comment composer, not a numbered wizard or card-by-card onboarding flow.

Customer Portal uses an independent responsive Help Center grammar: navy typography, Deskseed-blue actions, white surfaces, light blue-gray borders, and bounded pastel semantic accents expressed only through `--customer-*` tokens. Customer content regions begin with their semantic heading and never use ornamental small eyebrow, kicker, overline, or CSS-forced uppercase micro-heading copy. Customer feature code owns route/session/proof orchestration; reusable customer presentation remains inside the customer design-system root.
