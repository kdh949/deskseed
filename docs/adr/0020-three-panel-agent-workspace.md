# ADR 0020 — Three-panel agent ticket workspace

## Status
Accepted

## Context
Agents need ticket properties, conversation/composer, and customer/related context visible together during high-frequency work.

## Decision
Desktop ticket detail uses resizable properties, conversation/composer, and context panels, with global/work navigation and ticket tabs. The composer stays visible at the bottom of the conversation. Narrow layouts collapse work navigation/context before hiding critical ticket fields.

## Alternatives
- Single-column form: insufficient context switching efficiency.
- Modal-only properties: hides ownership/status while replying.
- Fully customizable layout builder in MVP: too much complexity.

## Consequences
Panel state, keyboard focus, resize persistence, and 1280/1440/1920 visual tests are required.
