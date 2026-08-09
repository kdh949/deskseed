# Zendesk Frontend Research Notes

Research date: 2026-08-10

## 1. Agent Workspace information architecture

Official Zendesk documentation describes the current Agent Workspace as a unified ticket interface with a right-side context panel. The context panel can switch among customer context, apps, knowledge, side conversations, and other related records. Zendesk also documents that the context panel and ticket properties panel can be resized.

Deskseed adoption:

- left ticket properties.
- center chronological conversation and composer.
- right resizable context panel.
- context tabs for customer, history, children, external references, apps, audit.

Sources:

- https://support.zendesk.com/hc/en-us/articles/4408821259930-About-the-Zendesk-Agent-Workspace
- https://support.zendesk.com/hc/en-us/articles/4408836526362-Using-the-context-panel
- https://support.zendesk.com/hc/en-us/articles/4882193306394-Quick-reference-Before-and-after-activating-the-Agent-Workspace

## 2. Views

Zendesk Views organize tickets into condition-based lists and can define columns and availability. Current documentation also supports categorizing views in a folder structure.

Deskseed adoption:

- categorized personal/shared/recent views.
- server-side filters and stable cursor pagination.
- configurable columns later.
- no assumption that dozens of views replace assignment/SLA prioritization.

Sources:

- https://support.zendesk.com/hc/en-us/articles/4408888828570-Creating-views-to-build-customized-lists-of-tickets
- https://support.zendesk.com/hc/en-us/articles/4408832792986-Managing-your-views

## 3. Customer portal

Zendesk customer portal lets end users submit requests, track open and solved requests, add comments, and search/filter their requests. Public and private comment projections remain distinct.

Deskseed adoption:

- anonymous request create/detail first.
- verified account list/filter/comment later.
- public projection only.

Sources:

- https://support.zendesk.com/hc/en-us/articles/4408846805530-Submitting-and-tracking-requests-in-the-help-center-Customer-Portal
- https://support.zendesk.com/hc/en-us/articles/4408884098074-Setting-up-a-requests-only-tickets-only-help-center
- https://developer.zendesk.com/api-reference/ticketing/tickets/tickets/

## 4. Garden design system

Zendesk Garden is the public design system used by Zendesk. Garden React components and SVG icons are distributed under Apache License 2.0. The theme exposes semantic colors, typography, spacing, icon sizes, and light/dark variables.

Deskseed adoption:

- use Garden React primitives where suitable.
- maintain license notices.
- create Deskseed brand theme and logo.
- use semantic tokens instead of copying screenshot colors.

Sources:

- https://garden.zendesk.com/
- https://garden.zendesk.com/components/
- https://garden.zendesk.com/components/theme-object/
- https://github.com/zendeskgarden/react-components
- https://github.com/zendeskgarden/svg-icons

## 5. Sidebar and extension behavior

Zendesk app design guidance notes that sidebar/app trays can be resized and apps must remain useful at narrow and wide widths. Zendesk apps use defined locations and a host SDK.

Deskseed adoption:

- responsive context panel.
- later sandboxed iframe Agent App SDK.
- manifest locations/scopes/origins.
- host bridge rather than arbitrary plugin code in the Spring process.

Sources:

- https://developer.zendesk.com/documentation/apps/app-design-guidelines/support/sidebar-apps-support/
- https://developer.zendesk.com/documentation/apps/getting-started/setting-up-new-apps/
- https://developer.zendesk.com/documentation/apps/getting-started/zendesk-app-quick-start/

## 6. Trademark and trade-dress boundary

Zendesk's official trademark guidance states that third-party logo use requires permission and that the Zendesk UI look and feel is claimed as trade dress. Therefore Deskseed should not present a pixel-for-pixel clone or use Zendesk marks, even when using Apache-licensed Garden components.

Deskseed adoption:

- independent brand/name/logo.
- Zendesk-inspired workflow and information architecture.
- no copied proprietary images or branded assets.
- document Apache notices separately from trademark rights.

Sources:

- https://www.zendesk.com/company/brand-guidelines/
- https://www.zendesk.com/company/trademark-property/trademarks/

## 7. Accessibility baseline

WCAG 2.2 AA is the product target. Relevant requirements include minimum text contrast and visible, unobscured keyboard focus.

Sources:

- https://www.w3.org/TR/WCAG22/
- https://www.w3.org/WAI/WCAG22/quickref/
