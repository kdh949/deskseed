# AI 비용 절감 S01 — 최신 발화·역할·옵션·업무 prompt

## Goal

권한 있는 상담사의 AI 요청이 ordered PUBLIC 대화의 최신 고객 요구를 누락하지 않고, 저장된 발화자 역할과 정규화된 옵션을 기능별 prompt에 전달한다.

## Decision and source references

- Decision IDs: D-003, D-007, D-008, D-009, D-013, D-018, D-021, D-049, D-050, D-054, D-066.
- Accepted ADRs: 0002, 0003, 0005, 0008, 0013, 0018, 0025, 0049.
- PRD/domain: `docs/01`, `docs/02`, `docs/03`, `docs/19`, `docs/23`, `docs/ai-v1/COST_REDUCTION_PLAN.md` S01.
- API contract operation IDs: `createAgentAiJob`, `getInternalAiRequestContext`, `getInternalAiRequestContextRevision`.
- Verification gate IDs: AI-API-001, AI-SRC-001, AI-TRIAGE-001, AI-REPLY-001, AI-OBS-001.
- This slice does not change an Accepted decision. `public-comments-v2` is a compatible source-contract generation; S05 separately owns feature-specific `aiInputRevision`.

## Actor and source

- Staff request actor: active `STAFF`, source `AGENT_UI`, existing session/CSRF/expected-actor checks.
- Source read actor: fixed `INTEGRATION_CLIENT`, source `AI_SERVICE`, registered job binding only.
- Resource constraints: server-owned workspace, requester, ticket UUID/number, feature and request revision.
- Polling and source metadata reads do not emit semantic `TICKET_VIEWED`.

## Product and API contract

- Requirements: REQ-AI-001, REQ-AI-002, REQ-AI-003.
- UI/routes: no rendered UI change; current AGT-004 client remains compatible.
- `public-comments-v2` adds required `sequence` and `authorRole` to each PUBLIC source comment.
- `ticket_comments.sequence_number` is the persisted canonical per-ticket order; ordering never relies on timestamp inference after migration. The source `sequence` is the contiguous ordinal of the PUBLIC-only projection ordered by that canonical value, so an INTERNAL comment cannot be inferred from a sequence gap. The current schema lacks the canonical column, so S01 adds it with a deterministic `(created_at, id)` backfill and serialized assignment for later inserts.
- Source author mapping is `CUSTOMER -> CUSTOMER`, `AGENT -> STAFF`, `INTEGRATION_CLIENT -> INTEGRATION_CLIENT`, `SYSTEM/AUTOMATION -> SYSTEM`; an unknown future stored value maps to `UNKNOWN` rather than being inferred from body or display name.
- New jobs use `public-comments-v2`. The AI reader accepts v1 and v2 so rollout can deploy reader compatibility before the Backend v2 writer. A v1 binding keeps its v1 revision semantics and source shape.
- Supported normalized options are deliberately narrow: language `ko` for all features and tone `calm` for reply draft. Omitted options receive these defaults; unknown values fail before outbox creation.
- The live provider receives request-local `C1..Cn` references, role, sequence, timestamp and body. UUID, author ID, name and email are not sent in the prompt. Canonical UUIDs remain only in server-side provenance.
- Each feature owns a versioned prompt resource and content digest. The stored `promptVersion` identifies the feature prompt set, while the provider request records no raw prompt in telemetry.

## In scope

- AI source OpenAPI v1/v2 compatibility contract and deterministic Core bundle update for option enums/defaults.
- V94 additive migration for per-ticket comment sequence, deterministic existing-row backfill, uniqueness and concurrent insert serialization.
- Backend PUBLIC projection, author mapping, stable sequence, policy-aware revision, option normalization and source response.
- Python source schemas, bounded context selection, option propagation, feature prompt loading/digest and provider payload.
- Backend and Python regression tests for ordering, INTERNAL exclusion, latest-customer protection, same timestamp, long input and option behavior.

## Out of scope

- S02 call receipts, tokenizer-backed cost reservation and exclusive usage normalization.
- S04 no-evidence generation bypass, S05 feature-specific input revision, S06+ cache/coalescing.
- Live provider calls, current-server data extraction, Langfuse Cloud, deployment and UI changes.

## Invariants and failure semantics

- Only `visibility='PUBLIC'` rows enter source, selection, prompt, provenance or tests; INTERNAL sentinels remain absent.
- The newest CUSTOMER comment and all following PUBLIC comments are a protected suffix. If that suffix cannot fit the current conservative input guard, execution stops with `INPUT_TOO_LONG` before a provider call.
- If no CUSTOMER comment exists, the latest PUBLIC comment is protected and the provider is told only its stored non-customer role.
- First PUBLIC comment and latest STAFF response are anchors added when the remaining bound allows; duplicate anchors are removed by sequence.
- Short conversations preserve exact bodies and order. Earlier comments are selected newest-first for capacity, then emitted in canonical sequence order.
- Every ticket has one monotonic stored comment sequence. Concurrent inserts serialize on the ticket row and the database rejects duplicate `(ticket_id, sequence_number)` values. The AI source renumbers only the authorized PUBLIC projection contiguously and does not expose INTERNAL gaps.
- v1 in-flight jobs are not reinterpreted as v2 and cannot be revived by a policy mismatch.
- Required source access audit still persists before a body response; audit failure remains fail closed.
- No external network call occurs in a Backend transaction. Provider retry remains disabled.

## Data and privacy

- Read: PUBLIC comment ID, sequence, stored author type, created time and body.
- Provider payload: request-local comment reference, normalized author role, sequence, timestamp and body; approved PUBLIC KB remains reply-only.
- Excluded: INTERNAL/collaboration/child/customer profile/attachment/audit content, author identity, secret and raw idempotency key.
- Logs/metrics/Langfuse remain body- and prompt-free. Prompt digests are bounded metadata.
- Existing result, execution and audit retention remain unchanged.

## Threats changed

- Role spoofing: role comes only from persisted `author_type` and a closed mapping.
- Ordering ambiguity: persisted sequence is canonical even when timestamps match.
- Prompt injection: PUBLIC body remains untrusted data behind a feature-owned system instruction.
- Context omission: protected latest-customer suffix is included or the request fails explicitly.
- Compatibility: v1 reader/writer coexistence is covered by contract fixtures.

## Acceptance scenarios

1. Given PUBLIC and INTERNAL sentinel comments, when source v2 is read, then only PUBLIC rows appear with contiguous persisted sequence and mapped author roles.
2. Given two PUBLIC comments with the same timestamp, when source is read, then persisted sequence determines order and revision.
3. Given a changed customer request after an earlier staff answer, when reply context is bounded, then the latest CUSTOMER comment and following PUBLIC comments are present.
4. Given a protected latest comment over the bound, when the worker prepares context, then `INPUT_TOO_LONG` is terminal and provider call count is zero.
5. Given a staff-created PUBLIC conversation with no customer author, when prompt data is built, then no customer role or request is invented.
6. Given omitted options, when a job is accepted, then summary/triage receive `language=ko` and reply receives `language=ko,tone=calm` through the provider call.
7. Given an unsupported language, tone or option key, when create is called, then the request fails without request/outbox/audit mutation.
8. Given a v1 in-flight binding, when the upgraded services read it, then v1 revision/source semantics remain accepted; new jobs use v2.

## Validation

- `python3 scripts/bundle_core_openapi.py --check`
- `cd backend && GRADLE_USER_HOME=./.gradle-user-home ./gradlew --no-daemon integrationTest --tests '*AgentAiRequestIntegrationTest*'`
- `cd backend && GRADLE_USER_HOME=./.gradle-user-home ./gradlew --no-daemon fastTest contractTest migrationTest`
- `cd ai && uv sync --frozen`
- `cd ai && .venv/bin/ruff check src tests scripts`
- `cd ai && .venv/bin/mypy`
- `cd ai && .venv/bin/pytest -q`
- `cd ai && .venv/bin/python scripts/evaluate_fake.py`
- `make docs-check`
- `git diff --check`

Live Korean tone/language quality remains Pending and the fake-provider evaluator is not quality or cost evidence.

## Compatibility and migration

- OpenAPI: source v2 is additive beside legacy v1; Core option strings become closed enums matching existing `ko/calm` clients.
- Migration: V94 adds and backfills canonical `sequence_number`, makes it non-null, adds `(ticket_id, sequence_number)` uniqueness, and assigns the next value under the ticket-row lock for new inserts. API projection preserves its separate PUBLIC-only ordinal. Rollback uses forward-fix or backup restore rather than dropping ordering facts.
- Deployment: AI reader compatibility first, then Backend v2 writer, then workers using v2 prompts. Existing v1 jobs drain under v1 semantics.
- Rollback: stop new AI admission, return writer to v1 while the dual reader remains, drain jobs, then roll back provider/prompt code. Canonical source/audit remains available.

## Human explanation

The Backend owns role and order because it owns the immutable comment facts. The AI service owns bounded selection and feature prompts because it owns provider input construction. The slice intentionally fails instead of silently omitting the newest customer requirement; tokenizer-accurate reservation and broader long-context support remain explicit later work.
