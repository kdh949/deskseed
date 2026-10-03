# Deskseed AI V1 서버 실행 계획

Status: Server implementation complete; local verification passed; UI and external validation deferred
Scope: Backend/Python/API/DB/KB/feedback/operations/eval/runbook; frontend is `DEFERRED_UI`
Baseline: `main@2c417b249dfefd7743804c11e4608675fb049ddc`

## Fixed boundaries

- summary, triage, reply draft는 server-side PUBLIC comment projection만 사용한다.
- UI, Storybook, frontend API client/type generation과 composer/form 연결은 구현하지 않는다.
- 실제 provider 호출, Langfuse Cloud 전송, 실제 KB 색인, 서버 배포는 opt-in external validation이다.
- 별도 승인 없이 commit, push, PR 게시, merge, deploy를 수행하지 않는다.
- fake-provider 통과를 live model/retrieval 품질 근거로 사용하지 않는다.

## Dependency order and progress

| Stage | Server deliverable | Requirement / gate | Status |
|---|---|---|---|
| A | Core contract, ADR, Backend request binding/outbox, PUBLIC source auth/audit | REQ-AI-001/002; AI-API-001, AI-SRC-001 | Implemented; local gates passed |
| B | FastAPI, AI DB, Streams, dispatcher/worker lifecycle, generation/lease/fencing/cancel/recovery | REQ-AI-002; AI-LIFE-001 | Implemented; real PostgreSQL/Redis tests passed |
| C | Summary, budget ledger, fake + LiteLLM fast adapter, body-free OTel/Langfuse adapter | REQ-AI-003; AI-COST-001, AI-OBS-001 | Implemented; fake/local gates passed, live external pending |
| D | Triage schema/taxonomy/current allowed-value validation; no automatic mutation | REQ-AI-003; AI-TRIAGE-001 | Implemented; unvalidated tag IDs fail closed |
| E | Public KB snapshot/cursor manifest, daily reconciliation, refresh/chunk/embedding/hybrid retrieval/generation publish | REQ-AI-004; AI-KB-001 | Implemented; partial-scan deletion safety and synthetic revision/retrieval gates passed, corpus quality pending |
| F | Bounded LangGraph reply, citation membership, evidence and freshness/capability | REQ-AI-003/004; AI-REPLY-001 | Implemented; no-evidence result is body-free NEEDS_REVIEW |
| G | Settings/stop/status/reindex, feedback/export, retention, eval/load/recovery, runbooks/handoff | REQ-AI-005; AI-OPS-001, AI-RET-001 | Implemented; local operational gates passed, live drills pending |
| UI | React/Storybook/browser/composer/form integration | REQ-UI-* | DEFERRED_UI / Not run |
| Live | paid provider, Langfuse Cloud receipt, real data indexing, human quality, deployment | external gates | Pending external validation |

## Shared versioned contracts

- Feature: `ticket.summary | ticket.triage | ticket.reply_draft | kb.reconcile`.
- Backend state before AI receipt: `ACCEPTED`; AI lifecycle: `QUEUED | RUNNING | RETRY_WAIT | SUCCEEDED | NEEDS_REVIEW | FAILED | CANCELLED | SUPERSEDED | EXPIRED`.
- Result bodies are returned only for a current owner-authorized, uncancelled, fresh request whose required access audit persisted.
- Error codes distinguish disabled, denied/not-found, conflict, rate limit, budget, input too long, no public context, insufficient evidence, invalid output, stale/superseded, cancelled, expired and dependency unavailable.
- All money uses integer micro-USD. All time is UTC from injected clocks.

## Transaction map

```text
Staff create job transaction
  current session/ticket authorization
  -> PUBLIC-only projection + context revision
  -> required access audit
  -> idempotency/rate-limit decision
  -> ai_request + body-free ai_integration_outbox commit

Backend relay (outside original transaction)
  lease body-free outbox -> authenticated AI command -> delivered/retry

AI accept transaction
  inbox/dedupe -> ai.job + ai.dispatch_outbox

Dispatcher
  DB lease -> XADD minimal envelope -> delivery mark

Worker
  DB lease/fencing -> current Backend policy/source -> budget reserve
  -> provider I/O without DB lock -> settle -> freshness/source recheck
  -> encrypted terminal result commit -> XACK
```

## Rollback and compatibility

All Core operations are additive. The rollout default is `ai.enabled=false`. Rollback is flag off, stop new model calls, drain/cancel work, retain reconciliation/deletion/cost settlement, then stop Python roles. Backend Flyway and checksummed AI SQL migrations are forward-only; rollback uses backup/restore or forward fix. Existing ticket/customer/Platform/frontends remain functional without AI services.

## Local verification evidence — 2026-09-16

- Backend: all 603 tests passed with 129 fast, 25 contract, 367 integration, 35 migration, and 47 slow categories. The run required a temporary 2 GB Kotlin compiler heap; repository JVM settings were not changed. OTLP connection warnings were non-failing because no collector was started.
- AI service: 20 tests passed against real PostgreSQL/pgvector and Redis, including authenticated API plus fake provider, exact idempotency, cancellation tombstone, lease/generation fencing, pending-entry recovery, Redis stream-loss republish, indexing budget, stable manifest reconciliation with no deletion on partial scan, explicit zero hidden LiteLLM retries, feedback, and retention. Ruff passed, and strict mypy passed for the Pydantic contract/config/pricing/encryption boundary.
- Synthetic evaluation: immutable `cases-v1` corpus의 100 functional(요약 25, 분류 25, 검색/답변 50) + 30 security/failure cases가 130/130 통과했다. Tune/holdout은 각각 70/30과 20/10으로 고정했으며 model/retrieval/human quality는 여전히 `NOT_ESTABLISHED`다.
- Contract/docs: deterministic Core bundle check, Core bundle regression, and 36 API documentation-quality tests passed. The repository-wide documentation validator reports 78 known pre-existing failures and 0 unexpected failures: absolute line-number Markdown links and unapproved historical evidence images in `docs/frontend-audit-2026-09-11.md` and its evidence folder.
- Delivery: effective Compose rendered, sensitive environment key names were inspected per role without values, and `deskseed-ai:local` built successfully.
- Frontend: `git status --short frontend` and `git diff -- frontend` were empty. Storybook/browser/accessibility verification was not run by scope.
