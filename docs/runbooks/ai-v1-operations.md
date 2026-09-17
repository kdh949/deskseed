# AI V1 operations runbook

## Scope and authority

Deskseed Backend owns authorization, PUBLIC projections, settings, and canonical audit. The isolated AI database is not an event-sourcing authority. Operator actions use the authenticated Admin API; direct database repair is an incident-only procedure requiring a recorded owner and backup.

The default rollout is OFF. Enabling requires healthy Backend outboxes, AI PostgreSQL, Redis, API readiness, current pricing configuration, role-specific credentials, and an agreed budget. Live provider and Langfuse remain separate opt-ins.

## Normal checks

1. Read `/api/v1/admin/ai/status`; record `dataAsOf` and `aiServiceDataAsOf` separately.
2. Check Backend request/knowledge outbox pending and dead counts.
3. Check AI job counts, reserved/settled/UNKNOWN micro-USD, indexed PUBLIC revisions, last successful reconciliation time, and dead-letter count.
4. Treat unavailable AI dependency data as unknown, never as zero.
5. Verify effective Compose configuration by service and sensitive key name without printing values.

## Stop procedure

1. Set the global AI flag OFF through versioned Admin settings.
2. Keep API, dispatcher, recovery, indexer, and settlement/reconciliation paths running while stopping new worker model calls.
3. Cancel or allow bounded in-flight jobs to reach a terminal state; do not delete UNKNOWN cost rows.
4. Drain Backend and AI outboxes, then stop worker roles. Preserve databases and Redis AOF for investigation.

## Redis loss or pending entries

- PostgreSQL `ai_dispatch_outbox` is authoritative delivery intent. Start `run-recovery` to republish stranded delivered rows after the safety interval.
- A normal worker uses `XAUTOCLAIM` to take idle pending entries and executes them under the same source/provider credential boundary as new work. The recovery role only republishes stranded durable dispatch intents and never runs model work. Generation and lease epoch fence stale workers.
- Do not reconstruct Stream messages with ticket or KB bodies. The only allowed fields are schema version, job ID, generation, and trace context.

## Stuck lease or retry

- Confirm deadline, generation, lease owner/expiry, cancellation tombstone, and Backend source authorization.
- Use an idempotent operator action with expected generation. A stale generation must not call the provider or commit a result.
- Repeated failure after bounded attempts goes terminal/dead-letter; do not loop indefinitely.

## UNKNOWN provider charge

- UNKNOWN means request delivery/outcome could not be proven. It continues consuming budget.
- Reconcile using provider request evidence and operation key. Settle exactly once or retain UNKNOWN; never release optimistically.
- Midnight rollover does not erase the reservation. Escalate when the configured budget headroom is exhausted.

## PUBLIC knowledge withdrawal and reindex

- Unpublish/archive/redact/parent-audience changes commit a body-free Backend outbox intent with the knowledge mutation.
- The indexer fetches the exact current PUBLIC revision with its own credential. A missing or changed revision fails closed and cannot resurrect an older body.
- Daily reconciliation reads a 24-hour Backend snapshot with a stable UUID cursor in bounded pages. It marks missing revisions deleted only after the final page commits; an expired token or partial scan fails the run without interpreting unseen IDs as deletions.
- Trigger Admin reindex with a stable operation ID. Reusing it is a replay, not a second operation. One request is capped at 10,000 manifest items and rolls back atomically if the cap is exceeded.
- Backend reauthorizes every citation before result return; withdrawn citations make the result stale and body-free even before asynchronous deletion completes.

## Provider or Langfuse outage

- Provider outage: leave the feature OFF or allow bounded retries only. Do not bypass reservation, source reauthorization, or output schema validation.
- Langfuse outage: job execution may continue because traces are non-authoritative; feedback remains leased/retryable. Never put prompts, results, ticket text, customer fields, auth headers, or secrets into trace metadata.
- Rotate worker source, indexer source, Backend-to-AI, provider, Langfuse, and result-encryption credentials independently. Never reuse direction-specific keys.

## Retention and restore

- Purge encrypted result ciphertext after seven days and job metadata after 30 days while preserving dedupe tombstones and settled/UNKNOWN cost evidence required for reconciliation.
- After backup restore, rerun retention and PUBLIC source reconciliation before enabling. A restore can reintroduce data already deleted after the backup.
- Forward-only schema migrations use a forward fix or full restore; do not manually downgrade selected tables.

## Incident evidence

Record timestamps, versions, operation/job IDs, status counts, generation/lease epoch, outbox state, normalized error code, and cost state. Do not copy PUBLIC bodies, prompts/results, raw search queries, customer fields, tokens, cookies, or credentials into tickets or logs.
