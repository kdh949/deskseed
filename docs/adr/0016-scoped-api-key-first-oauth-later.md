# ADR 0016: Use scoped API keys for Integration v1 and add OAuth when delegated apps require it

- Status: Accepted
- Date: 2026-08-10

## Context

The first integrations are operator-managed internal systems. A full OAuth authorization server adds significant security and operations scope. Nevertheless, unrestricted permanent account tokens are unacceptable.

## Decision

Integration v1 uses expiring, revocable, rotatable scoped API keys bound to `IntegrationClient` and resource constraints. Secrets are shown once and stored as verifiers/hashes. Machine calls cannot impersonate staff. Add OAuth client credentials or authorization code + PKCE only when concrete third-party/delegated authorization use cases exist.

## Alternatives considered

- Full OAuth immediately: rejected as premature complexity.
- One global admin token: rejected for blast radius and attribution.
- Staff username/password from integrations: rejected.

## Consequences

- Internal integrations can ship earlier with least privilege.
- Credential lifecycle UI/audit is mandatory.
- Delegated human attribution waits for verified OAuth grants.
