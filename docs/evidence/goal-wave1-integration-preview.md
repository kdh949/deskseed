# Wave 1 integration preview evidence

## Merge inputs

| Lane | Final head | Preview merge |
|---|---|---|
| Foundation F3 | `4c06aca` | preview base |
| Knowledge Base | `dd0987b` | `1883522` |
| Ticket Configuration | `7d1faaf` | `ef89cfc` |
| Integrations/Webhooks | `cc3611f` | `a556f47` |
| Drafts/Presence | `46232c2` | `51492ad` |

## Contract and migration safety

- Core source fragments are bundled deterministically. The duplicate `TicketNumber` component from independent Knowledge and Drafts contracts is resolved by the fragment-local `TicketDraftTicketNumber` component; the HTTP path parameter remains `ticketNumber`.
- Applied migrations remain additive and ordered: V40, V50–V52, V60–V63, V70. No applied migration was edited, deleted, or renumbered.
- ADR 0042 keeps presence single-instance and advisory. Multi-instance delivery requires a new decision and real-stack evidence.

## Verification record

- Passed: integration-preview Core bundle, ownership validator, API documentation quality, deterministic documentation/manifest validation.
- Pending: Kotlin compile, focused cross-lane tests, Compose real-stack attempt, remote preview CI.
- External gaps remain explicit: Storybook MCP, approved non-loopback webhook receiver/load evidence, two-agent browser verification, and multi-instance deployment.
