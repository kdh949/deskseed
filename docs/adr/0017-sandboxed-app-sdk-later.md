# ADR 0017: Add a sandboxed Agent App SDK after the REST integration foundation

- Status: Accepted
- Date: 2026-08-10

## Context

Zendesk-like integrations may need a ticket sidebar app with live external context. Running arbitrary plugins in the Spring process would create remote-code and dependency-isolation risks. Building an app framework before stable ticket context and permissions would derail the MVP.

## Decision

First ship ExternalReference, Platform API, signed webhooks, and generated SDKs. Later provide a manifest-driven sandboxed iframe App SDK with explicit locations, scopes, origins, short-lived app sessions, a restricted host bridge, and server-side named connections for secrets.

## Alternatives considered

- Backend plugin JARs/scripts: rejected for execution and upgrade risk.
- Full iframe with broad API key: rejected for credential exposure.
- No extension platform ever: rejected because rich domain-specific sidebars are a valid long-term need.

## Consequences

- Deep links are the initial integration UX.
- App framework has a separate security/versioning program.
- Browser apps never receive long-lived provider secrets.
