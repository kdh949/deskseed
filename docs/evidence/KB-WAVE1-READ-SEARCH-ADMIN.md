# KB-WAVE1-READ-SEARCH-ADMIN task brief

## Goal

고객은 자신에게 공개된 Help 문서를 탐색하고, 상담사와 관리자는 권한·감사 경계 안에서 같은 canonical 지식 문서를 검색·관리한다.

## Decision and source references

- Requirements: REQ-KB-001, REQ-KB-002, REQ-KB-003, REQ-KB-004
- Accepted ADRs: 0008, 0013, 0018, 0025, 0040
- Contract: `listHelpCategories`, `getHelpCategory`, `getHelpSection`, `getHelpArticle`, `searchHelpArticles`, `recordHelpArticleFeedback`, agent knowledge operations, and admin article/index operations in `20-knowledge-base.yaml`
- Gates: `docs/21-minimum-verification-gates.md`, `ApiDocumentationIntegrationTest`, PostgreSQL-backed `AdminKnowledgeIntegrationTest`, `make docs-check`

## Actor and boundaries

- Anonymous/signed-in customers use `CUSTOMER_PORTAL`; staff use `AGENT_UI`; administrators use `ADMIN_UI`.
- Help queries apply PUBLIC/SIGNED_IN_CUSTOMER audience before a category, result count, excerpt, or cursor is made visible. Staff selected-group membership is read from active database memberships.
- Agent search/detail/suggestions require the expected staff actor header and persist one `knowledge_access_audit_events` row before 200. Persistence or query protection failure becomes 503.

## In scope

- Additive V52 only: published-revision PostgreSQL FTS projection, GIN/trigram indexes, feedback totals, separate immutable knowledge access audit, and search-index status.
- Signed scope-bound cursors, public cache validators, Help/agent/admin route mappings, admin draft revision and rebuild command audit/outbox coupling.

## Failure, privacy, and compatibility

- V50/V51 remain unchanged. V52 is additive and rollback is a forward migration dropping only its derived tables/indexes/triggers after consumers stop using them.
- Raw agent/suggestion queries are not logged or placed in events; the audit stores the existing protected redaction/fingerprint/ciphertext material. PUBLIC feedback persists aggregate counts only and creates no anonymous identity.
- Rebuild recomputes the derived PostgreSQL projection inside the admin command transaction and emits only a public-safe INTERNAL outbox fact; it never changes an article/revision.

## Acceptance and validation

- Given a published PUBLIC article, anonymous Help read/search/feedback returns only the canonical allowed blocks and a public cache policy.
- Given an authenticated staff session, detail/search succeeds only after the access audit writes; an injected audit insert failure returns 503 without a success body.
- Given an admin, draft patch appends a new immutable revision under If-Match, and index status/rebuild use the admin CSRF/actor boundary.
- Local passed: `./gradlew --no-daemon compileKotlin`; `./gradlew --no-daemon test --tests 'dev.deskseed.staffaccess.internal.ApiDocumentationIntegrationTest'`; `./gradlew --no-daemon test --tests 'dev.deskseed.staffaccess.internal.AdminKnowledgeIntegrationTest'`.

## Explicit non-goals

No external search/cache infrastructure, automatic responses, arbitrary hierarchy, browser UI/Storybook work, customer selected-audience matrix completion, search corpus/p95 benchmark, or real-stack verification is included in this slice.
