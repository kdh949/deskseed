# ADR 0044 — Customer Portal frontend surface isolation

## Status

Accepted

## Context

ADR 0039 temporarily used a single frontend root while customer-facing pages were being rebuilt. Customer authentication, requests, attachments and public Knowledge Base contracts are ready for implementation, and customer routes are active again. Sharing the Agent Workspace theme, global CSS, Storybook and build graph makes it easier to pull staff layouts or tokens into customer pages accidentally.

Customers need a responsive help-center UI; staff use a denser console. Separate application bundles keep their styles and components independent.

## Decision

- `frontend/apps/customer-portal` and `frontend/apps/staff-console` are separate React/Vite applications with independent entrypoints, bundles, global CSS, design-system public exports, Storybook configurations, and project-local MCP names.
- Production keeps one origin. Nginx routes `/agent`, `/admin`, and `/audit` to the staff application, and `/`, `/help`, `/requests`, `/account`, and `/customer` to the customer application. `/api` and `/actuator` remain backend proxy routes with higher precedence.
- The two applications do not share React components, theme providers, CSS, design tokens, icon registries, layout shells, stories, fixtures, or runtime TypeScript modules.
- The only shared frontend inputs are immutable Deskseed source brand assets and license notices. Each application owns its own component wrapper around those assets.
- Staff keeps the existing `--ds-*` token namespace. Customer tokens use `--customer-*` and are defined only in the customer design-system root.
- Customer feature code imports only the customer design-system public entrypoint. Staff feature code imports only the staff design-system public entrypoint. Direct Garden imports remain restricted to the owning design-system root.
- A repository boundary check, ESLint restrictions, TypeScript roots, CSS token/import checks, and production manifest checks fail when one surface reaches into the other.
- Storybook is split into `deskseed-staff-design-proj` on port 6006 and `deskseed-customer-design-proj` on port 6007. Each nested `AGENTS.md` directs Codex to the matching documentation source and forbids using the other surface as a design reference except for an explicit boundary audit or migration.
- Customer reference images provide layout hierarchy and visual tone only. Frozen API, authorization, privacy, accessibility, and domain contracts override fields or actions visible in those images. Screenshots or crops are never shipped as UI.

This ADR supersedes ADR 0039 only where that ADR requires one frontend visual root and keeps customer routes deferred. ADR 0039 continues to govern the Agent Workspace surface, preserved headless contracts, and staff visual coherence.

## Consequences

- A customer-only change cannot alter the staff bundle or global styles without crossing a machine-enforced boundary.
- A staff-only change cannot silently reuse or restyle customer components.
- Dependency and build configuration are duplicated where necessary; that duplication is an intentional cost of deployable isolation.
- Same-origin cookies, CSRF, CORS, customer magic links, and request access proofs retain their current server semantics.
- Customer profile/preferences, SSO, live chat, phone, announcements, customer SLA display, and automatic ticket-to-KB suggestions remain absent until matching contracts are frozen.
