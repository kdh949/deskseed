# Third-party notices

Deskseed does not currently have a project `LICENSE`. Public source availability does not
grant permission to copy, modify or redistribute Deskseed itself. The third-party terms
below apply independently and do not select a license for this repository.

Versions in this file match `frontend/package-lock.json` for the portfolio release. The
complete installed-package counts, production graph size, backend resolution inventory,
advisory results and known scan limitations are recorded in the
[release supply-chain baseline](docs/evidence/release/supply-chain/baseline.md). That
inventory, this notice and package metadata must be reviewed together for distribution;
they are not legal advice.

## Direct frontend runtime dependencies

| Package | Version | Declared license |
| --- | ---: | --- |
| `@tanstack/react-query` | 5.101.4 | MIT |
| `@zendeskgarden/react-buttons` | 9.15.7 | Apache-2.0 |
| `@zendeskgarden/react-theming` | 9.15.7 | Apache-2.0 |
| `react` | 18.3.1 | MIT |
| `react-dom` | 18.3.1 | MIT |
| `react-router` | 7.18.2 | MIT |
| `styled-components` | 6.5.2 | MIT |

## Garden notice and trademark boundary

The frontend uses `@zendeskgarden/react-theming` and
`@zendeskgarden/react-buttons` through Deskseed-owned wrappers. Copyright Zendesk, Inc.
The packages declare the Apache License, Version 2.0; the license text is available at
<https://www.apache.org/licenses/LICENSE-2.0>.

Use of these packages does not grant permission to use any Zendesk trademark, logo,
wordmark, screenshot, illustration or other proprietary visual asset, and it does not
imply sponsorship, endorsement or compatibility. Deskseed ships an independent brand and
implementation.

## Direct backend API documentation dependencies

| Package | Version | Declared license |
| --- | ---: | --- |
| `com.scalar.maven:scalar-webmvc` | 0.6.61 | MIT |
| `org.springdoc:springdoc-openapi-starter-webmvc-api` | 3.1.0 | Apache-2.0 |

Scalar renders the committed OpenAPI contracts in the backend API Reference. springdoc generates runtime-only implementation documents for drift verification. Their Maven metadata and upstream license texts apply independently of Deskseed.

## Transitive, development, backend and container dependencies

The lockfile inventory contains 265 installed frontend package locations and no missing
license metadata at the time of the recorded scan. Production transitive packages and
development tooling remain subject to their own license files and package metadata;
development-only status does not remove those terms from source distribution.

Backend versions are resolved through the committed Gradle build, Spring dependency
management and lockable upstream metadata rather than this frontend table. Container base
images also carry independent terms. The reproducible inventory commands, resolved
backend versions and the status of container/advisory scanning are maintained in the
[supply-chain evidence](docs/evidence/release/supply-chain/baseline.md). This file does not
claim that a package-lock or dependency tree alone is a complete attribution bundle for a
commercial or open-source distribution.
