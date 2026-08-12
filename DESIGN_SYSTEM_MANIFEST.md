# Deskseed Design System Manifest

## Status

`CURRENT / CANONICAL`

Deskseed has one production design system. Its canonical root is:

```text
frontend/src/design-system/
```

Development routes, production routes, fixtures, visual regression checks, features, pages, and shells consume the same components and stylesheet entry point. The application imports only `frontend/src/design-system/index.css` as the canonical global style entry.

## Allowed consumers

```text
features/*
pages/*
shells/*
fixtures/*
```

Feature code may compose domain data and route state, but reusable presentation belongs to `frontend/src/design-system/**`.

## Forbidden UI roots and concepts

```text
frontend/src/shared/ui/
frontend/src/styles/tokens.css
legacy AgentShell
DesignPreviewAgentShell
VITE_DESIGN_PREVIEW UI selection
```

Compatibility exports, token aliases, fallback shells, duplicate wrappers, and feature-local clones are not allowed.

## Canonical elements

- Tokens: `foundations/tokens.css`
- Provider: `providers/DeskseedThemeProvider.tsx`
- Brand and icons: `DeskseedBrandMark`, `DeskseedIcon`
- Controls: buttons, icon buttons, selects, tabs, tags, split buttons
- Feedback: screen states and notifications
- Status: `DsStatusIndicator`, `StatusBadge`
- Patterns: `QueueTicketTable`, `ViewNavigation`, `DsDrawer`, conversation and ticket workspace styles
- Shell: `AgentShell`
- Agent workspace patterns: properties, conversation, composer, customer context

## Extension rule

Do not create a reusable feature-local UI clone. When a required element is missing:

1. Review the current design system.
2. Design a reusable contract.
3. Add it under `frontend/src/design-system/`.
4. Consume the canonical export from the feature.

Direct Garden imports are restricted to the design-system root. The boundary check is `npm run check:design-system-boundaries` and runs in CI.

## Visual contract

Agent Queue, read-only Ticket Workspace, minimum login, and common states use the same Deskseed grammar: 64px white navigation rail with a dark-teal brand cell, dark-teal top chrome, Deskseed brand and icons, compact neutral workspaces, semantic tokens, 1px dividers, and shared focus/hover/disabled behavior. Deferred capabilities must recompose from this current system without creating another shell or token root.
