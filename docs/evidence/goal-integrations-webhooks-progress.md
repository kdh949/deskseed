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
| A — admin webhook contract | `30-integrations.yaml` owns FROZEN lifecycle, secret rotation, test, delivery history and replay operations; deterministic bundle refreshed | Passed: bundle/ownership/documentation check and `ApiDocumentationIntegrationTest` runtime route inventory | pagination and delivery-attempt detail projection are deferred to the worker slice |
| C — HMAC and SSRF boundary | `WebhookSignatureSigner` signs timestamp + raw body; `WebhookTargetValidator` validates every DNS answer and endpoint-scoped private allowlist | Passed: `./gradlew --no-daemon test --tests dev.deskseed.webhook.WebhookSecurityContractTest` | Resolver result must be bound to the transport adapter; secret encryption/persistence pending |
| B — endpoint/delivery durable model | V60 adds endpoint, encrypted versioned secret, subscription, delivery and attempt tables; `(endpoint_id,event_id)` is unique | Passed: `AdminWebhookIntegrationTest` create/rotation/test/replay | domain-event fan-out and HTTP worker remain next slice |
| D — worker | Not implemented | Not run | event-outbox materialization, bounded virtual-thread transport, retry/circuit/lease recovery/dead-letter transition |
| E — admin UI | Not implemented | Not run: Storybook MCP is not registered in this session | Feature contribution, API client, states, Storybook/axe/Playwright after documented contracts are available |
| F — Platform API hardening | Existing implementation inspected | Existing scope only; no new lane claim yet | usage summary, auth-strategy metadata and final compatibility coverage |
