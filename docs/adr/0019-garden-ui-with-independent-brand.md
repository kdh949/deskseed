# ADR 0019 — Garden UI with independent Deskseed branding

## Status
Accepted

## Context
The product should feel familiar to Zendesk agents, but copying Zendesk marks or proprietary visual assets creates legal and product-identity risk. Zendesk Garden provides reusable open-source UI components.

## Decision
Use Garden React components/icons behind Deskseed wrapper components where suitable. Keep license notices. Use an independent product name, logo, primary hue, illustrations, and marketing language. Reproduce workflow/information architecture, not proprietary screenshots or pixel-traced assets.

## Alternatives
- Build all primitives from scratch: slower and higher accessibility risk.
- Pixel clone Zendesk: rejected for brand/trade-dress risk and maintainability.
- Unrelated generic admin template: rejected because it loses help-desk interaction familiarity.

## Consequences
Visual regression and accessibility tests become release gates. Garden major upgrades require review. The product can be Zendesk-inspired without suggesting endorsement.
