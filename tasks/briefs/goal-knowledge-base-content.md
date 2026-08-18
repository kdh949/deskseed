# Knowledge Base content lifecycle — task brief

## Goal

관리자가 canonical block document 기반의 지식 문서를 작성·검수·게시하고, 고객·상담사가 서버 audience policy에 따라 안전하게 읽고 검색할 수 있는 첫 수직 슬라이스를 만든다.

## Decision and source references

- Decisions: D-008, D-010, D-033, D-035, D-036, D-048, D-054
- Accepted ADRs: 0008, 0010, 0013, 0018, 0025, 0036, 0039, 0040. The canonical-block decision is frozen in this brief because the current ADR registry is intentionally closed at 0040; a new ADR number must be allocated in a dedicated registry change.
- Requirements: REQ-KB-001 through REQ-KB-004
- API: `20-knowledge-base.yaml` operation family
- Gates: ARCH-001, SEC-001, DOC-001, DB-001, API-001, FE-001

## Actor and boundaries

- Customer: published PUBLIC or signed-in-customer article only; unauthorized and missing resources both return 404.
- Agent: audience-filtered PUBLIC/INTERNAL lookup; sensitive search and restricted detail read require access audit before success.
- Admin: category/section/article/audience lifecycle mutations with ADMIN role, CSRF, `If-Match`, atomic admin/security audit, and versioned events.
- Canonical content contains only the vendor-neutral block schema. Raw Editor.js payload, HTML blocks, unbounded draft body in events, and arbitrary attachment bytes are prohibited.

## In scope

- V50–V59 only; fixed Category → Section → Article hierarchy and immutable revisions.
- PostgreSQL FTS/trigram projection behind `KnowledgeSearchPort`, no Redis/OpenSearch/ltree.
- ETag/cache rules, audience policy, safe frontend renderer/editor adapter, and extension contributions without central app/shell changes.

## Out of scope

- Community/forum, generative answers, arbitrary nested trees, OpenSearch/Redis, automatic agent reply submission, raw vendor payload storage, and any migration outside V50–V59.

## Critical failure semantics

- Conflict leaves the existing revision and publication pointer unchanged.
- Required admin/search/restricted-read audit failure returns 503 and commits no mutation or protected response.
- Unknown blocks, unsupported Editor.js tools, HTML payloads, unsafe URLs, and PUBLIC references to non-CLEAN/Internal attachments are rejected before persistence.
- Unpublish/audience changes invalidate shared permission assumptions transactionally; restricted responses are never shared cached.

## Validation plan

- PostgreSQL hierarchy/revision/audience/XSS/search/cache integration tests, OpenAPI bundle/docs checks, focused frontend/Storybook/Playwright checks where MCP is available, and real-stack evidence.
- The current Storybook MCP availability must be recorded rather than inferred from package scripts.
