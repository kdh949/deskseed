# ADR 0048: Machine-verifiable internal plaintext service boundaries

## Status

Accepted — 2026-09-05

## Context

The supported production Compose topology connects the Backend to Redis and VersityGW over plaintext protocols on dedicated same-host internal Docker networks. The existing configuration required separate boolean acknowledgement variables for both paths. Those variables recorded operator consent but could not prove network isolation, service identity, port exposure, or encryption.

Sophos WAF is different: its upload antivirus and origin restriction live outside the application and repository, while `UPSTREAM_WAF` mode treats accepted uploads as clean without rescanning their bytes.

## Decision

- Remove the Redis and S3 plaintext acknowledgement environment variables.
- When Redis TLS is disabled, the production application accepts only the exact `redis:6379` Compose service endpoint. Other plaintext Redis endpoints fail startup.
- Plaintext S3 is accepted only at the exact `http://versitygw:7070` Compose service endpoint. Other HTTP S3 endpoints fail startup; external S3 remains HTTPS-only.
- The production Compose contract continues to require dedicated internal networks and no published Redis or VersityGW host ports.
- Keep `DESKSEED_ATTACHMENT_UPSTREAM_WAF_ACKNOWLEDGED`. Repository code cannot verify the external Sophos policy, and removing this gate could silently turn `UPSTREAM_WAF` into a no-scan mode.

This supersedes only the internal Redis and S3 acknowledgement clauses in the earlier production task briefs and ADR 0026 consequences. It does not supersede the WAF acknowledgement or the Docker host/root threat boundary.

## Alternatives

- Keep all three acknowledgements: rejected because the Redis/S3 flags duplicate machine-verifiable endpoint and Compose topology constraints without adding protection.
- Remove all acknowledgements: rejected because the application cannot verify the external WAF scanner and origin policy.
- Add TLS to the bundled Redis and VersityGW connections now: deferred until certificate lifecycle and operational demand justify the additional self-hosted complexity.

## Consequences

- Production secret configuration has two fewer ceremonial values.
- Custom non-Compose plaintext Redis or S3 endpoints are not supported. They must use TLS/HTTPS instead of a generic opt-in bypass.
- Same-host root, Docker daemon access, container escape, or incorrect Docker network attachment can still expose plaintext traffic. Static Compose contract tests verify the supported topology, while live host enforcement remains an operations gate.
- Existing deployments may delete the removed variables; leaving them in an external secret manager has no runtime effect after upgrade.

## References

- D-037, D-039, D-060, D-065
- REQ-PROD-001, REQ-AUTH-003, REQ-FILE-001
- ADR 0026, ADR 0028
- ARCH-004, OPS-003, FILE-003
