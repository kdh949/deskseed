# Drawer input focus recovery

- Scenario/actor: AGENT edits a transfer reason in a ticket context drawer; each keystroke changes the parent callback. Nested collaboration panels must close before their parent and restore the originating control.
- REQ-TKT-012, REQ-CHILD-001; UI-004; D-018 and ADR 0004. Existing Core transfer/child operations are unchanged.
- Failure: the Drawer focus effect depended on `onClose`, so an inline callback recreated it after every keystroke and moved focus away from the input.
- Change: retain the current callback separately from the open/close focus lifecycle; exclude disabled fieldset controls from focus cycling; leave nested dialog keyboard handling to the inner panel.
- Verification: Storybook MCP `DrawerPreservesInputFocus`, `NestedDrawerKeyboard`, `Drawer` and the transfer/retry/conflict flows. Typecheck and ESLint passed. No backend, migration, audit, actor/scope, concurrency, privacy or retention change; no performance or deployment claim.
