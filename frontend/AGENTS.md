# AGENTS.md — Deskseed Frontend Rules

This file applies to all work under `frontend/` and extends the repository-root `AGENTS.md`. The root rules remain authoritative for repository-wide domain, security, architecture, and delivery constraints. If applicable instructions or sources conflict, do not silently select a weaker rule; report the conflict instead of guessing.

## Working directory and required context

- Run frontend, Storybook, and package commands from `frontend/`.
- Use `frontend/` as the workspace root when project-local MCP configuration discovery is required; the `deskseed-design-proj` configuration is in `.mcp.json`.
- Before frontend implementation, read the relevant parts of `../DESIGN_SYSTEM_MANIFEST.md`, `../docs/28-frontend-product-and-information-architecture.md`, `../docs/29-zendesk-inspired-design-system.md`, `../docs/30-screen-specifications.md`, `../docs/31-frontend-state-and-interaction-contracts.md`, `../docs/40-frontend-visual-regression-and-accessibility.md`, and `../docs/51-zendesk-parity-and-visual-acceptance.md`.

## Sources of truth

Do not collapse different kinds of authority into one priority list:

- Product behavior, state, authorization, and information architecture come from Accepted ADRs, the PRD, frozen API contracts, applicable frontend specifications, and the current task within those constraints. An ad hoc task does not override a non-negotiable repository rule without an explicit repository decision.
- Documented design-system props, APIs, and intended usage come from the current Storybook MCP documentation.
- The canonical implementation and import boundary are `src/design-system/`, its public exports, `index.css`, and `../DESIGN_SYSTEM_MANIFEST.md`.
- Approved reference images and visual baselines are comparison evidence. They do not override product behavior, accessibility, security, or Deskseed branding.

Do not use `artifacts/`, deleted UI roots, feature-local legacy components, old screenshots, or previous design-system copies as the contract for new production UI. They may be consulted only as explicitly authorized reference material.

## Storybook MCP workflow

For every UI task:

1. Call `list-all-documentation` once before answering UI contract questions or making UI changes.
2. Before creating or editing a component or story, changing rendered UI, running story tests, or fixing a Storybook/a11y failure, call `get-storybook-story-instructions` and follow its current output.
3. Before relying on a documented design-system component, call `get-documentation` with an ID returned by `list-all-documentation`. Call `get-documentation-for-story` when a relevant variant needs more detail.
4. Use only documented props and combinations shown by the MCP documentation or documented stories. Never infer props from component names, source code, type definitions, another library, or memory.
5. Source inspection may establish implementation location, exports, and internal behavior, but it is not a substitute for the documented public contract.
6. If a needed public capability is undocumented, treat it as unavailable. Use a documented composition or, when the task authorizes it, add and document an explicit reusable API. Ask the user only when a product or visual-design decision remains unresolved.

If the MCP tools are unavailable, do not guess component contracts, bypass them with source inspection, or claim Storybook verification passed. Record the unavailable validation and stop if safe implementation depends on it.

## Component ownership and selection

Choose the smallest appropriate change in this order:

1. Reuse an existing documented component as-is.
2. Compose existing documented primitives, components, patterns, and shells.
3. Extend an existing design-system component when the capability is reusable, belongs naturally to its responsibility, and can preserve compatibility.
4. Add a canonical design-system component only when it has a clear reusable UI contract.

Feature and page code may own route state, data loading, permissions, and domain-specific composition. Reusable presentation belongs in `src/design-system/`. Do not create reusable feature-local clones, compatibility wrappers, duplicate token systems, or new generic UI roots. Direct Garden imports are restricted to `src/design-system/`.

Do not broaden or break an existing public API merely to satisfy one screen. Keep one-off domain orchestration in the feature unless a reusable visual contract is demonstrated.

## Story requirements

- Every new UI component needs a Storybook story. When editing a component, update or add stories that demonstrate the changed behavior.
- Cover each distinct reachable behavior or state without duplicating stories that express the same logic.
- Where applicable, cover default, loading, empty, error, denied/read-only, stale/conflict, long-content/overflow, responsive, and meaningful product variants. Do not invent unsupported product states solely for Storybook.
- Important screens and large patterns use deterministic fixtures and must not depend on a live API or unstable time, network, data, fonts, or animation.
- Interactive components need `play` tests for the important user flows, visible outcomes, keyboard behavior, and callback assertions. Prefer role- and label-based queries.
- Story documentation should explain when and why to use the contract and any important misuse to avoid, not merely describe its appearance.
- Follow the imports, story format, mocking, naming, and testing conventions returned by `get-storybook-story-instructions`; do not preserve stale conventions in this file.

## Tokens and styling

- Feature and page code must not introduce raw color values or consume `--ds-ref-*` reference tokens directly. Use documented semantic or component tokens.
- `src/design-system/` may use reference tokens internally to implement semantic tokens and documented component contracts. New raw colors belong only in the token-definition layer and require a reusable semantic purpose.
- Reuse the existing spacing, typography, radius, elevation, and layout systems. Do not create a parallel scale for one feature.
- Add a token only when existing semantic tokens cannot express a new reusable product meaning. Name it for purpose, not a sampled color or one screen.
- Do not sample colors or dimensions from proprietary screenshots. Use references to judge hierarchy and workflow, then express the result through Deskseed tokens and documented layout contracts.

## Icons

- Use the documented Deskseed design-system icon contract and reuse an existing semantic icon when one already represents the action or state.
- Feature and page code must not paste inline SVGs, import Garden SVGs directly, or introduce another icon library.
- When a new icon is genuinely required, add it once to the canonical icon contract and document it in the relevant Storybook story. Do not assume an icon gallery exists unless it is returned by the documentation tools.
- Icon-only controls require an accessible name. Decorative icons must remain hidden from assistive technology.

## Visual and content fidelity

- Implement the documented task flow and information architecture with Deskseed branding. Never ship Zendesk logos, wordmarks, screenshots, illustrations, copied CSS/assets, or a pixel-for-pixel proprietary clone.
- Do not add unsupported headings, subtitles, explanatory copy, cards, badges, icons, illustrations, toolbar actions, decorative surfaces, or section wrappers merely to make a screen appear more complete.
- Every visible element must be grounded in the current requirement, an applicable specification, an approved reference, or an existing documented Deskseed pattern.
- Do not expose engineering explanations, fixture controls, unsupported-action notices, or implementation placeholders in production UI. Omit unavailable actions or implement the specified product state.

## Accessibility and interaction

- Target WCAG 2.2 AA and preserve the accessibility semantics of underlying Garden and Deskseed components.
- Never communicate state by color alone. PUBLIC/INTERNAL, status, warning, error, selection, and conflict states need textual or semantic distinction.
- Verify keyboard reachability, visible focus, labels and accessible names, semantic roles, tab behavior, and modal/drawer focus entry and restoration.
- Sticky chrome, banners, and composers must not obscure focused content. Responsive collapse must preserve critical status and ownership controls.
- Fix non-visual semantic or structural accessibility regressions as part of the task. Before changing color, typography, spacing, or layout to address a visual accessibility finding, present the finding and obtain the user's design choice as required by the current Storybook instructions.

## Verification

Storybook and Playwright serve different purposes and neither replaces the other:

- Storybook verifies components, patterns, isolated states, interactions, and component-level accessibility.
- Playwright verifies complete pages, routing/data integration, canonical viewports, responsive behavior, and page-level visual regression.

After a component, story, or rendered-UI dependency change:

1. Call `get-changed-stories`. If a touched shared file is missing, resolve its consumers and use `get-stories-by-component`.
2. For visual changes, call `preview-stories` for the most relevant affected stories and include every returned preview URL in the handoff.
3. Run focused `run-story-tests` while iterating. Fix failures and rerun until passing.
4. Run the complete story-test suite before handoff when the change is broad, shared, or has unclear impact.
5. Run relevant unit, integration, Playwright, accessibility, visual, and `npm run check:design-system-boundaries` gates according to the change scope.

Do not substitute package scripts, typechecking, or Playwright for `run-story-tests`. Do not update visual snapshots merely to hide a failure; inspect the diff, establish that the change is intentional, and follow the baseline review policy in `../docs/40-frontend-visual-regression-and-accessibility.md`.

## Handoff

For frontend changes, report:

- reused, extended, and newly added design-system contracts, including why an addition was necessary;
- stories added or changed and all preview URLs returned by the MCP tools;
- component, interaction, accessibility, E2E, visual, and boundary checks run or not run;
- unresolved design-system gaps and work deliberately left out of scope;
- the applicable `REQ-*` IDs and verification gates required by the root instructions.
