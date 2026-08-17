# Goal — Webhook delivery and Platform API hardening

## Goal

ADMIN이 서명된 outbound webhook endpoint를 안전하게 운영하고, external client가 기존 scoped Platform API를 retry-safe하게 사용한다.

## Decision and source references

- Decision IDs: D-008, D-010, D-011, D-012, D-016, D-021, D-022, D-023, D-024, D-043, D-055
- Accepted ADRs: 0008, 0010, 0011, 0012, 0016, 0018, 0031, 0040
- Requirements: REQ-INT-001 through REQ-INT-006, REQ-AUD-008, REQ-TECH-004
- OpenAPI operations: `listWebhookEndpoints` through `replayWebhookDelivery`; existing Platform operations retain their operation IDs
- Verification gates: ARCH-001/002/004, AUD-001, INT-AUTH-001 through 004, IDEM-001 through 004, PLAT-001/002, WH-001 through 005

## Actor and source

- Endpoint configuration/test/secret rotation/replay actor: ADMIN, `ADMIN_UI`, `integration:clients:manage` plus the explicit private-target capability when introduced.
- Platform actor: `INTEGRATION_CLIENT`, `PLATFORM_API`; arbitrary staff impersonation headers never select an actor.
- Webhook transport actor: SYSTEM worker after an already committed event-outbox record; it performs no ticket transaction work.
- Sensitive reads and admin mutations record their defined admin/security or access audit inside the same required transaction. Delivery attempts remain in their own canonical delivery log.

## In scope

- V60–V69 only, webhook endpoint/subscription/delivery/attempt durable model, HMAC signing, endpoint-scoped SSRF validation, bounded delivery/retry/dead-letter/replay, and admin API contracts.
- Existing PostgreSQL API-key, idempotency, ETag and fixed-window limiter behavior is retained and expanded only without breaking its four frozen Platform operations.

## Out of scope

- RabbitMQ, Redis, WebFlux/R2DBC, a bespoke OAuth authorization server, generic browser plugin execution, ticket-configuration resource types, or direct external DB access.
- No client-side secret persistence or inactive OAuth success path.

## Invariants and failure semantics

- Domain event publication and webhook HTTP are separated by durable intents; an endpoint failure never rolls back or reruns ticket mutation.
- Each `(endpointId, eventId)` is unique. A stable delivery ID and event ID make at-least-once duplicates receiver-detectable.
- Public endpoints accept HTTPS/443 only and every resolved address must be public. Private endpoints need endpoint-local hostname, port and CIDR approval; loopback, link-local, multicast, unspecified and CGNAT stay blocked.
- HMAC is `v1=base64(HMAC-SHA256(timestamp + "." + rawBody))`; raw secrets, request body and response body never enter audit or ordinary logs.
- Admin mutation or required audit persistence failure returns a typed failure and rolls back the mutation. Delivery failures update their own durable state.

## Threat model and abuse cases

- A malicious ADMIN-configured endpoint must not become an SSRF primitive: HTTPS, normalized hostname, endpoint-scoped policy, all-address validation, DNS-pinned transport and redirect refusal are required on create, update, test and delivery paths.
- A receiver must not be able to forge or replay a delivery without the one-time secret: stable IDs, timestamped HMAC over the exact sent body, constant-time verification support and rotation overlap are retained; raw secret/body never enter audit or ordinary logs.
- A failing receiver must not block a ticket command or starve unrelated endpoints: event materialization commits first, lease/fan-out are separate, global/per-endpoint admission is bounded, and retry/circuit/dead-letter state lives in PostgreSQL.

## Validation

- Focused deterministic HMAC/SSRF unit tests before persistence.
- PostgreSQL migration/integration tests for fan-out uniqueness, lease recovery, retry/backoff, circuit isolation, replay and audit rollback.
- Contract bundle and ownership validation, docs check, architecture test, and real-stack mock receiver E2E after worker implementation.

### Executed evidence

- Passed: `./gradlew --no-daemon test --tests dev.deskseed.webhook.WebhookSecurityContractTest --tests dev.deskseed.webhook.internal.WebhookOutboxMaterializerIntegrationTest --tests dev.deskseed.architecture.ArchitectureTest`
- Passed: `./gradlew --no-daemon test --tests dev.deskseed.webhook.internal.WebhookDeliveryWorkerIntegrationTest --tests dev.deskseed.webhook.internal.WebhookOutboxMaterializerIntegrationTest --tests dev.deskseed.staffaccess.internal.AdminWebhookIntegrationTest --tests dev.deskseed.webhook.WebhookSecurityContractTest --tests dev.deskseed.architecture.ArchitectureTest`
- Not run: real non-loopback receiver, redirect/DNS-pinning end-to-end, load/performance, full backend suite, remote CI for the current head, and frontend Storybook checks. The Storybook MCP is unavailable in this session, so no undocumented UI contract has been inferred.

## Compatibility and migration

- Core API fragment is source; generated `core-api-outline-v1.yaml` is refreshed deterministically. The first runtime endpoint slice freezes its matching admin operations together with controller and PostgreSQL integration evidence.
- V60–V69 are additive only. Rollback is a commit revert before migration deployment; deployed tables are retained until a separately approved forward migration retires them.

## Human explanation

PostgreSQL remains the source of truth for event intent, delivery state and endpoint health so retry/circuit decisions stay consistent across instances. A broker or cache needs measured need and a separate Accepted ADR.
