# Third-party notices

Deskseed's project license has not yet been selected. The notices below apply independently to
the third-party packages used to build and ship the frontend. Exact direct and transitive package
versions are pinned in `frontend/package-lock.json`.

## Zendesk Garden

The frontend uses `@zendeskgarden/react-theming` and `@zendeskgarden/react-buttons` through
Deskseed-owned wrappers in `frontend/src/design-system`. Copyright Zendesk, Inc. These packages are
licensed under the Apache License, Version 2.0. The license text is available at
<https://www.apache.org/licenses/LICENSE-2.0>. This notice does not grant permission to use any
Zendesk trademark, logo, or proprietary visual asset.

## Other direct runtime dependencies

| Package | License |
| --- | --- |
| `@tanstack/react-query` | MIT |
| `react` / `react-dom` | MIT |
| `react-router` | MIT |
| `styled-components` | MIT |

Development-only tooling is not included in the production frontend bundle. Its exact licenses
and versions remain recorded in `frontend/package-lock.json` and must be included in any future
release license scan.
