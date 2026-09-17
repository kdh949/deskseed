# Deskseed AI V1 service

This package is the isolated execution boundary accepted by ADR 0049. Deskseed Backend remains the owner of staff identity, current ticket authorization, PUBLIC-only projections, settings, and canonical audit. This service owns asynchronous execution metadata, encrypted short-lived results, budget ledgers, Redis delivery, and the PUBLIC knowledge index.

## Local verification

```bash
uv sync --frozen
.venv/bin/ruff check src tests scripts
.venv/bin/mypy
.venv/bin/pytest -q
.venv/bin/python scripts/export_openapi.py
.venv/bin/python scripts/evaluate_fake.py
```

The integration tests start real PostgreSQL with pgvector and Redis containers. The default provider is deterministic and synthetic. Live provider and Langfuse calls require explicit opt-in and credentials; tests do not make either call.

## Process roles

- `api`: authenticated Backend ingress and metadata/result reads.
- `dispatcher`: durable PostgreSQL dispatch outbox to a content-free Redis Stream.
- `worker`: current Backend source/policy checks, budget reservation, model execution, fencing, and terminal commit.
- `recovery`: stranded dispatch republish and Redis `XAUTOCLAIM` recovery.
- `indexer`: exact PUBLIC knowledge revision fetch/replacement plus bounded daily snapshot reconciliation.
- `feedback`: stable score export to Langfuse when enabled.
- `retention`: hourly encrypted-result and expired-metadata purge while preserving dedupe and cost evidence.
- `migration`: forward-only AI schema migration.

Production validates only the credentials required by the selected role. The Compose overlay deliberately does not give provider or Langfuse secrets to the API, dispatcher, recovery, or Backend.

## Safety properties

- Redis messages and Backend/AI outboxes contain identifiers and revisions, never ticket or knowledge bodies.
- Results are returned only with `includeResult=true`; Backend repeats current authorization, freshness, expiry, citation, and required access-audit checks.
- A reply without approved PUBLIC knowledge ends as `NEEDS_REVIEW` without a stored usable answer.
- Triage tag IDs fail closed until Backend supplies a current allowed-tag contract.
- Provider/Langfuse/data/deployment are disabled by default.

See `TEST_PLAN.md`, `docs/ai-v1/FRONTEND_HANDOFF.md`, and `docs/runbooks/ai-v1-operations.md`.
