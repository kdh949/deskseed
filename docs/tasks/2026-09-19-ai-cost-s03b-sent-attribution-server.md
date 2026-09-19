# AI 비용 절감 S03b — PUBLIC 답변 전송 귀속 서버

## Goal

상담사가 AI 답변 초안을 검토·수정해 PUBLIC 댓글로 성공 저장했을 때만 stable candidate에 body-free `sent`를 한 번 귀속하고, AI 전달 장애와 무관하게 댓글 전송을 유지한다.

## Decision and source references

- Decision IDs: D-003, D-005, D-007, D-010, D-018, D-032, D-049, D-066, D-070.
- Accepted ADRs: 0003, 0005, 0007, 0010, 0018, 0021, 0036, 0038, 0049, 0053.
- Requirements: REQ-AI-001, REQ-AI-005, REQ-AUD-001, REQ-AUD-007.
- Plan: `docs/ai-v1/COST_REDUCTION_PLAN.md` S03b and sections 6.2, 10~13.
- API operations: `getAgentAiJob`, `updateAgentTicket`, AI internal result read and sent-usage ingest.
- Verification gates: CHG-001, IDEM-001, AI-API-001, AI-REPLY-001, AI-OBS-001, AI-USE-001.

## Actor and source

- Actor type: active `STAFF`.
- Source: `AGENT_UI` with existing staff session, expected-actor guard and CSRF for the comment command.
- Required permission: current ticket write authorization and PUBLIC comment capability. Attribution grants no additional permission.
- Resource constraints: use binding and final comment must share requester staff, ticket, reply job, stable candidate and unexpired result.
- Interaction semantics: result GET creates a body-free use binding only after current authorization and required `AI_RESULT_READ`; the later `UpdateTicket` command keeps its existing stable `clientCommandId`.

## Product and API contract

- Insertable reply/rewrite result receipts expose server-owned `candidateId`. Generated candidates receive one identity; cache/coalesced consumers inherit the origin identity.
- `UpdateTicketCommand.comment.aiAttribution` is optional and versioned. Instrumented clients distinguish `NO_AI_LINEAGE`, `LINEAGE_PRESENT` and `LINEAGE_LOST`; omission remains legacy `UNINSTRUMENTED`.
- `LINEAGE_PRESENT` contains 1~4 ordered sources with job ID, candidate ID and the exact original answer. Each answer is at most the existing 6,000-character AI result bound and combined request size remains under the Core request limit.
- Candidate source text is transient validation input. OpenAPI manually documents that it is not stored as attribution metadata and must never be logged.
- Customer/comment responses remain unchanged. They do not expose candidate, job, edit metric or attribution state.

## In scope

- Backend and AI DB migrations for stable result candidate, result-read use binding, comment sent attribution, durable delivery and receiver dedupe.
- Core and AI internal OpenAPI changes with strict schemas, Korean domain descriptions and synthetic examples.
- result-read binding creation and current actor/ticket/job/candidate/expiry validation.
- `UpdateTicket` PUBLIC command integration, exact replay descriptor, single/multi-source classification and bounded edit metric.
- post-commit AI outbox dispatch, AI receiver, canonical body-free usage rows and Langfuse retryable projection.
- PostgreSQL-backed authorization, rollback, replay/redelivery and privacy tests.

## Out of scope

- Staff UI lineage and final-send payload; this follows in the S03b UI PR.
- recent result/new candidate controls; S08b follows after S03b.
- reconstructing attribution for legacy results, clipboard content or already-sent comments.
- production analytics dashboard, paid provider call, human quality judgment, savings claim, merge and deployment.

## Invariants and failure semantics

- comment, TicketAudit, local attribution and outbox commit or roll back together.
- AI service, dispatcher or Langfuse failure after commit never rolls back the comment. Dead/pending delivery remains operational backlog.
- structurally valid but stale/mismatched/unbound attribution becomes unattributed without leaking another job/candidate or rejecting the comment.
- any invalid source in a multi-source claim invalidates the entire attribution; partial sent credit is not emitted.
- INTERNAL comment produces zero AI PUBLIC sent events even if a buggy client supplies attribution.
- exact command replay returns the original result and emits zero new attribution/outbox rows. `(commentId, candidateId)` is unique in Backend and AI DB.
- request descriptor stores transient original-answer digest, never the answer or diff.

## Data and privacy

- persisted use binding: actor/ticket/job/candidate, normalized answer digest and code-point length, expiry and contract version.
- persisted sent metadata: comment/candidate/job references, source count, bounded classification, length/edit counts and ratio, timestamps and delivery state.
- excluded everywhere outside transient command memory: answer text, final comment duplicate, diff, prompt, citation body, customer profile, INTERNAL content.
- default metadata retention: 30 days; unresolved delivery backlog remains until reconciled. Comment and TicketAudit retain their existing policies.
- no customer API, Platform API, webhook, notification, ordinary log, metric label or Langfuse body exposure.

## Threats changed

- authorization bypass: requester/ticket/job/candidate binding is server-owned and current ticket write policy still applies.
- replay/duplicate: exact command replay and dual unique keys prevent duplicate sent increments.
- audit bypass: comment/TicketAudit atomicity remains; result read still fails closed if required access audit cannot persist.
- secret/content leakage: transient original answer is excluded from descriptors, exception text, logs, traces and outbox.
- concurrency/data loss: candidate identity follows the origin result; outbox is at-least-once and receiver is idempotent.

## Acceptance scenarios

1. Given an authorized reply candidate binding, when the same text is sent as PUBLIC, then one comment, TicketAudit, attribution and outbox commit and edit ratio is 0.
2. Given the candidate was modified, when PUBLIC send succeeds, then sent is 1 and exact bounded edit/length metrics are stored without body or diff.
3. Given insert only, draft deletion or command rollback, then sent is 0.
4. Given timeout after commit and exact `clientCommandId` retry, then the original comment is returned and sent remains 1.
5. Given duplicate dispatcher/receiver delivery, then `(commentId, candidateId)` remains one row and one metric increment.
6. Given another actor/ticket/job, expired binding, altered original candidate text or one invalid source among several, then comment succeeds unattributed and no sent event is emitted.
7. Given INTERNAL comment with attribution, then comment follows the existing INTERNAL command and AI PUBLIC sent remains 0.
8. Given AI service/Langfuse unavailable, then PUBLIC comment, TicketAudit and local outbox commit; delivery is retried without comment rollback.
9. Given cache/coalesced jobs sharing one origin candidate, then both expose the same candidate ID and candidate first-use is not multiplied.
10. Given DB failure before local attribution/outbox commit, then comment and TicketAudit roll back with the existing transaction.

## Validation

- `make docs-check`, Core/AI OpenAPI export and reproducibility checks.
- Backend full tests plus focused PostgreSQL migration, result-read binding, authorization, transaction rollback, D-049 replay and outbox lease/redelivery tests.
- AI Ruff, strict mypy, full pytest and PostgreSQL receiver dedupe/retention tests.
- secret/body log capture and schema inspection proving no answer/diff column in attribution/outbox/usage tables.
- latest PR HEAD CI. Fake provider is contract evidence only; live sent rate, quality and savings remain `NOT_ESTABLISHED`.

## Compatibility and migration

- new request/receipt fields are additive and `aiAttribution` remains optional. Existing UI and legacy clients continue without sent attribution.
- nullable candidate/binding columns do not backfill or reinterpret old jobs. Only newly proven candidate results are attributable.
- deploy order is migrations/readers → AI candidate producer/receiver → Backend result binding and command producer → Staff UI.
- rollback disables new UI payload and sent dispatch, drains/reconciles existing outbox, then rolls back applications; canonical comments and audits remain valid.

## Human explanation

비용과 품질은 초안을 화면에 넣은 시점이 아니라 고객에게 성공적으로 보낸 시점에 연결해야 한다. 본문을 통계 저장소에 복제하지 않으면서도 Backend가 이미 허가한 후보의 digest와 실제 PUBLIC command를 같은 transaction에서 결합하면, AI 장애로 고객 응답을 막지 않고 재시도·cache·coalescing까지 중복 없이 셀 수 있다.
