# Agent Workspace search quality corpus

This committed corpus freezes the V35 literal-substring quality boundary for
`searchAgentWorkspace`. `AgentTicketSearchIntegrationTest` executes the API-level
cases, while `StaffTicketQueryEvidenceIntegrationTest` verifies the underlying
PUBLIC/INTERNAL segments, transactional refresh/rebuild, and GIN plan.

| Case | Query/fixture | Accepted result |
|---|---|---|
| Korean phrase | `결제 오류` in Korean subjects | matching OPEN/HIGH tickets retain the frozen sort and exact count |
| English phrase and cursor | `stable cursor` | all four known rows page without duplicates or snapshot drift |
| Exact identifier rank | ticket `8802` plus another subject containing `8802` | exact ticket ranks first; both remain in the exact count |
| Requester email | `customer-8807@example.com` | requester ticket is found through the staff document |
| INTERNAL-only term | `내부전용 corpus-marker` | active staff finds the ticket; term remains absent from `public_comment_text` |
| SQL wildcard literals | `100%_` | only the subject containing literal `%_` matches |
| Misspelling boundary | `paymant` against `payment refund` | zero results; fuzzy correction is explicitly not claimed in V35 |

The corpus does not claim Korean morphological analysis, typo tolerance, quoted
syntax, or production relevance scoring. Those capabilities require a separate
UX/injection review and measured decision. No customer ticket-search endpoint
consumes `ticket_search_documents`; customer-facing projections remain separate.
