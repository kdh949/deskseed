# Wave 1 integrations and webhooks progress

## Frozen preflight

| Item | Evidence |
|---|---|
| Branch/base | `feature/goal/webhook-delivery` from Foundation F3 `4c06aca6560f7a8458992af6379c6954b1bc4dc1` |
| Lane ownership | `tasks/goal-wave-ownership.yaml`: V60–V69, `api/core-api-fragments/30-integrations.yaml`, `frontend/src/features/integrations` |
| Existing Platform boundary | `api/platform-api-outline-v1.yaml`, V21/V23/V31, `IntegrationClient`, idempotency, PostgreSQL limiter |
| Event source | Foundation V36 `domain_event_outbox` and `eventpublication` root API |
| External source contract | `/Users/donghyunkim/Downloads/codex-goals/04-integrations-webhooks-api.md`; not copied into this repository |
| Storybook MCP | unavailable in this session. No integration admin UI component contract or Storybook verification is claimed. |

## Checkpoints

| Checkpoint | Contract/security evidence | Verification | Remaining work |
|---|---|---|---|
| A — admin webhook contract | `30-integrations.yaml` owns FROZEN lifecycle, secret rotation, test, delivery history and replay operations; delivery detail exposes only redacted attempt status/latency/error metadata; deterministic bundle refreshed | Passed: bundle/ownership/documentation check, `ApiDocumentationIntegrationTest` runtime route inventory, and `AdminWebhookIntegrationTest` non-leak assertion | pagination is deferred; raw request/response headers and bodies remain intentionally unavailable |
| C — HMAC and SSRF boundary | `WebhookSignatureSigner` signs timestamp + raw body; `WebhookTargetValidator` validates every DNS answer and endpoint-scoped private allowlist; the RestClient Apache request factory pins its resolver to those validated addresses and disables redirects; secret is encrypted with versioned AES-GCM material | Passed: `WebhookSecurityContractTest`, `AdminWebhookIntegrationTest` ciphertext assertion, worker compile/integration coverage | A real external receiver cannot be verified locally without an approved non-loopback target; it remains Not run rather than inferred |
| B — endpoint/delivery durable model | V60 adds endpoint, encrypted versioned secret, subscription, delivery and attempt tables; V61 adds endpoint version and bounded delivery/circuit lease state; `(endpoint_id,event_id)` is unique | Passed: `AdminWebhookIntegrationTest`, Flyway-backed worker integration tests | Custom non-secret headers and endpoint-specific timeout/retry administration are deferred; global bounded settings are active |
| D — worker | PUBLIC event fan-out is durable; a bounded Java virtual-thread batch claims with PostgreSQL `FOR UPDATE SKIP LOCKED`, signs outside transactions, records safe attempt metadata, retries retryable HTTP/network failures, dead-letters at max attempts, recovers expired leases, and persists endpoint circuit state | Passed: `WebhookOutboxMaterializerIntegrationTest`, `WebhookDeliveryWorkerIntegrationTest` success/retry/dead-letter/circuit/lease coverage, `ArchitectureTest` | Real receiver transport/redirect/DNS-pinning E2E and load evidence are Not run; no success is claimed for them |
| E — admin UI | Not implemented | Not run: Storybook MCP is not registered in this session | Feature contribution, API client, states, Storybook/axe/Playwright after documented contracts are available |
| F — Platform API hardening | V62 adds persisted `rate_limit_per_minute` and `usage_count`; an ADMIN-only, CSRF and `If-Match` protected no-store rate-policy resource writes `INTEGRATION_CLIENT_RATE_LIMIT_UPDATED` in the same transaction. The active Platform auth metadata is `OPAQUE_API_KEY`; `ExternalOAuthTokenAuthenticator` is a future, unexposed compatibility boundary. The authenticated principal carries the persisted per-client limit into the PostgreSQL DB-clock fixed-window limiter. | Passed: `AdminIntegrationClientIntegrationTest` covers capability/CSRF, no-store/ETag, stale 412, required audit rollback, usage count and secret absence. `PlatformRateLimitIntegrationTest` proves a configured limit changes Platform 429/header behavior. `PlatformRateLimiterTest` retains multi-instance shared-bucket coverage. | No OAuth issuer/token endpoint is implemented. Ticket configuration has not published a custom resource root API on this stack, so custom field/tag/status operations are intentionally not invented. |

## Current verification status

| Gate | Status | Evidence |
|---|---|---|
| BE compile | Passed | `./gradlew --no-daemon compileKotlin` after V62 |
| BE focused PostgreSQL | Passed | One final `./gradlew --no-daemon test` invocation ran 13 focused classes / 48 tests: Core runtime contract, webhook endpoint/security/outbox/worker, admin client policy, Platform ticket/idempotency/rate/network/OpenAPI, migration and architecture. Every result XML reports `failures=0, errors=0`. |
| BE delivery-detail projection | Passed | `AdminWebhookIntegrationTest`, `ApiDocumentationIntegrationTest`, and `ArchitectureTest` passed after V63. The test persists raw response header/body fixtures and verifies they are absent from the authorized detail response. |
| DOC-001 | Passed | `make docs-check` after the owned fragment was bundled and deterministic Core/Platform/manifest artifacts were staged. |
| UI/Storybook/axe | Not run | `deskseed-design-proj` MCP is unavailable; no integration UI code was created or verified. |
| Real receiver E2E/load | Not run | A non-loopback external mock receiver and target approval are unavailable locally. Local assertions do not claim transport E2E or load evidence. |
