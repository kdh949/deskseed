# Staff search two-phase PAGE evidence

## Scope

This evidence supports the REQ-SRCH-001 PAGE-query implementation in
`StaffTicketSearchSqlPlanFactory`. It does not change exact COUNT, ranking,
authorization, audit, cursor, API, schema, index, or pool behavior.

The motivating measurement used the personal-staging synthetic search corpus at
deployment `10d3050f0744a17b691e2cb8735800f5f7ca35cc`, seed `20260919`, and corpus
SHA-256 `5d4df63a1912af7a991ed71bc61b7d579d92c7b6e862db96e523d0bef597173d`.
The raw search input is intentionally omitted.

## Before/after component evidence

The baseline PAGE joined ticket/customer/group/assignee/SLA detail for all 22,510
matching candidates and then returned 26 rows. The two-phase alternative first
selected `ticket_id`, `ticket_number`, `updated_at`, and `search_score`, applied the
stable order and limit, and joined detail only for the selected rows.

| Four read-only executions | Baseline PAGE | Two-phase PAGE |
|---|---:|---:|
| Samples (ms) | 1797.398, 1754.404, 1760.562, 2068.953 | 1812.036, 1475.104, 1617.365, 1568.077 |
| Median | 1778.980 ms | 1592.721 ms |
| Mean | 1845.329 ms | 1618.146 ms |
| Root shared hit blocks, median | 318,156 | 134,707 |
| Root shared read blocks, median | 83,311 | 64,988 |
| Detail join rows | 22,510 | 26 |

The structural resource reduction is confirmed for this input: shared hit blocks
fell 57.66% and shared read blocks fell 21.99%. The observed median latency fell
10.47%, but the four-sample ranges overlap. This is a promising latency result, not
a service-level or multi-query-class improvement claim.

COUNT remained about 1.155 seconds. Combining the component medians predicts about
6.35% lower COUNT+PAGE time; this is not an HTTP A/B result.

## Correctness evidence and limits

- Baseline and two-phase returned the same 26 unique ticket IDs, ticket numbers,
  and scores within one repeatable-read snapshot.
- PostgreSQL integration tests cover score and updated ordering, both cursor forms,
  snapshot exclusion, exact count, status/priority/group/assignee/SLA filters,
  INTERNAL search visibility, exact-number ranking, literal wildcard behavior, and
  required search audit failure.
- The PAGE implementation conditionally joins the SLA fact during candidate
  selection only when an SLA filter requires it. Detail projection still joins SLA
  for the bounded selected page.
- The measured A/B covered `common:0`, score sort, no filter, and first page only.
  Filtered/cursor correctness is automated, but its production-scale latency is not
  yet measured.
- The broad substring candidate scan and exact COUNT remain. This change is not a
  complete search-bottleneck fix.

## Re-measurement gate

After an explicitly approved deployment, use the same corpus and seed with a unique
`TEST_RUN_ID`. Compare Grafana HTTP, COUNT, PAGE, audit, error/drop, resource, and
recovery evidence. Do not promote this component result to a production improvement
unless the deployed HTTP path reproduces it without correctness or audit regressions.
