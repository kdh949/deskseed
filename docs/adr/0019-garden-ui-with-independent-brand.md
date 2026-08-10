# ADR 0019 — Garden UI with independent Deskseed branding

## Status
Accepted

## Context
The product should feel familiar to Zendesk agents, but copying Zendesk marks or proprietary visual assets creates legal and product-identity risk. Zendesk Garden provides reusable open-source UI components.

## Decision
Use Garden React components/icons behind Deskseed wrapper components where suitable. Keep license notices. Use an independent product name, logo, primary hue, illustrations, and marketing language. Reproduce workflow/information architecture, not proprietary screenshots or pixel-traced assets.

The M0 dependency baseline uses React 18.3.1 because the selected Garden 9.15.7 release declares a peer range through React 18. React Router 7.9.6 is used because it supports React 18, unlike the previously selected Router 8 release. TypeScript 5.9.3 is retained because the selected lint toolchain declares support through TypeScript 5. These are compatibility constraints, not product-direction changes. React, Router, and TypeScript may be upgraded only after the selected dependencies support their peer ranges and the required visual/accessibility verification is rerun.

## Alternatives
- Build all primitives from scratch: slower and higher accessibility risk.
- Pixel clone Zendesk: rejected for brand/trade-dress risk and maintainability.
- Unrelated generic admin template: rejected because it loses help-desk interaction familiarity.

## Consequences
Visual regression and accessibility tests become release gates. Garden major upgrades require review. The product can be Zendesk-inspired without suggesting endorsement.
