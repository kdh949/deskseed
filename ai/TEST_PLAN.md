# AI V1 test plan

## Automated gates

| Boundary | Evidence |
|---|---|
| Contract/config | strict Pydantic envelopes, role-scoped production credential validation, machine-auth digest checks, integer pricing, Ruff, focused strict mypy |
| API flow | authenticated FastAPI ingress, deterministic fake provider, real PostgreSQL/pgvector and Redis, metadata-only default read and opt-in result read |
| Privacy | PUBLIC source tests in Backend; body-free outbox/Stream assertions; encrypted result bytes; no result for `NEEDS_REVIEW` |
| Lifecycle | exact event idempotency, cancel-before-create tombstone, generation/lease fencing, retry generation, XAUTOCLAIM, Redis stream-loss republish |
| Budget | call-scoped reservation/settlement, SYSTEM indexing bucket, UNKNOWN state, settled-ledger retention |
| Provider transport | explicit `num_retries=0` and bounded timeouts for generation and embedding adapters; orchestration owns retry policy |
| Knowledge | exact PUBLIC revision fetch, replacement, hybrid retrieval, stable paged manifest, no deletion on partial scan, full-scan withdrawal, stale citation reauthorization in Backend |
| Retention | seven-day result purge, 30-day metadata purge, durable dedupe and settled cost preservation |
| Backend | agent/admin/knowledge integration tests, migrations, authorization/audit rollback, source credential scope |
| Delivery | deterministic OpenAPI bundle, documentation-quality checks, Compose render, secret-key distribution inspection, container image build |
| Evaluation | immutable v1 corpus with 100 functional and 30 security/failure cases, fixed 70/30 and 20/10 tune/holdout splits, deterministic fake-provider evaluator |

## Failure injection

The integration suite deletes the Redis stream after delivery and proves PostgreSQL outbox republish, leaves a pending consumer entry and proves `XAUTOCLAIM`, delivers cancellation before creation, attempts a stale lease commit, and interrupts a paged KB manifest before completing it to prove absent revisions remain active until a complete scan. These are real PostgreSQL/Redis state transitions, not mocks.

## Explicitly unverified

- Live provider transport, retry headers, latency, refusal, and billing reconciliation.
- Langfuse Cloud receipt and data-residency controls.
- Production-sized PUBLIC knowledge recall, ANN-versus-exact recall, and human answer quality.
- Production load/SLO, backup restore, deployment, alert delivery, and operator drill.
- Any frontend, Storybook, browser, accessibility, or insertion flow.

Those require opt-in external credentials/data or the deferred UI slice and cannot be inferred from fake-provider success.
