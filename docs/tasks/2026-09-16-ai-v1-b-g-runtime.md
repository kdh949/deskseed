# AI V1 B–G — isolated execution, capabilities, and operations

## Goal

Complete the server-only AI V1 vertical slices after Backend contract/source binding: durable execution and recovery, summary/triage/reply, PUBLIC KB indexing, budgets, feedback, status, retention, evaluation scaffolding, and operator documentation.

## Decisions and gates

- Decision: D-066; Accepted ADR 0049, plus ADR 0002/0008/0009/0025.
- Requirements: REQ-AI-003 through REQ-AI-005.
- Gates: AI-LIFE-001, AI-COST-001, AI-KB-001, AI-TRIAGE-001, AI-REPLY-001, AI-OBS-001, AI-OPS-001, AI-RET-001.
- Contracts: Core agent/admin AI operations, AI internal command/status API, Backend AI source API.

## Actors and boundaries

- Staff AGENT/ADMIN uses session, expected-actor, current ticket permission, CSRF for mutations, and owner-scoped jobs.
- Backend-to-AI, worker-to-Backend, and indexer-to-Backend use distinct fixed `INTEGRATION_CLIENT` credentials.
- PUBLIC ticket comments and current PUBLIC KB revisions are the only content sources. INTERNAL comments, customer profile, attachments, audit content, and private knowledge are excluded.
- Results require current authorization and a fail-closed access audit. Provider/Langfuse calls never occur inside Backend ticket transactions.

## Failure semantics

- Durable outboxes and exact inbox fingerprints make duplicate delivery normal.
- Generation plus lease epoch fences stale workers; cancellation tombstones survive event reordering.
- All calls reserve integer micro-USD before I/O. Unknown outcomes remain charged as UNKNOWN until reconciliation.
- Retry is bounded by attempt and deadline. Invalid output, missing evidence, disabled policy, stale source, and budget denial are terminal/body-free.
- PUBLIC KB replacement is exact-revision and transactionally switches current chunks; Backend citation reauthorization blocks withdrawn sources.

## Verification scope

- Automated: fake-provider authenticated API flow, real PostgreSQL/pgvector and Redis lifecycle/recovery, Backend integration/migration, OpenAPI/documentation, Compose render/secret distribution, image build, synthetic evaluation.
- Deferred: frontend/Storybook/browser, live provider, Langfuse receipt, production corpus/quality, production load and deployment.

## Compatibility and rollback

All HTTP additions are additive and default OFF. Disable the flag first, preserve reconciliation/deletion/cost settlement, drain durable intents, and then stop model workers. Migrations are forward-only; restore or forward-fix rather than partial downgrade.

## Human trade-off

Two databases and Redis add operational cost, but they keep model/runtime dependencies and short-lived result data outside the business database while the Backend retains authorization and audit truth. The design accepts at-least-once delivery and resolves it with idempotency, fencing, and reauthorization instead of a distributed transaction.
