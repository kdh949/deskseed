# First Reply SLA query-plan evidence

- Date: 2026-08-12 (Asia/Seoul)
- PostgreSQL: 17-alpine, disposable Docker Compose database with Flyway V1–V19
- Fixture: one synthetic active First Reply target and its reconciled analytics fact
- Scope: plan shape and index selection only; this is not release-scale latency evidence

The production scanner and projection paths were checked with `EXPLAIN (COSTS OFF)` and
`enable_seqscan=off` so an accidental loss of the intended index is visible on the small
functional fixture. `FirstReplySlaIntegrationTest` asserts the same index names and the
existing `StaffTicketQueryEvidenceIntegrationTest` keeps the staff list/detail SQL
statement counts bounded after the SLA join.

## Breach scanner

```text
Limit
  -> LockRows
       -> Index Scan using sla_target_instances_breach_scan_idx on sla_target_instances
            Index Cond: (due_at <= '2026-08-14 00:00:00+00'::timestamptz)
            Filter: (state = 'ACTIVE')
```

The partial `(due_at, id) where state = 'ACTIVE'` index feeds the ordered, bounded
`FOR UPDATE SKIP LOCKED` claim.

## Policy/outcome analytics

```text
GroupAggregate
  Group Key: outcome
  -> Sort
       Sort Key: outcome
       -> Index Only Scan using analytics_first_reply_facts_policy_idx
            on analytics_first_reply_facts
            Index Cond: (policy_id = '403cf5f5-15be-493c-8b45-fea2ef48329e'::uuid)
```

The UUID is synthetic evidence data. The production API parameterizes it.

## Ticket projection join

```text
Nested Loop Left Join
  -> Index Scan using tickets_pkey on tickets
       Index Cond: (id = <ticket id>)
  -> Index Scan using analytics_first_reply_facts_pkey on analytics_first_reply_facts
       Index Cond: (ticket_id = <ticket id>)
```

The SLA fact is a one-row ticket projection keyed by `ticket_id`; it does not add a
row-by-row policy or schedule lookup to the ticket workspace.

## Limitation

The million-ticket release performance fixture predates V19 and was not regenerated in
this vertical slice. Before production-scale approval, add SLA fact cardinality to that
fixture and record warm-cache p95 plus `EXPLAIN (ANALYZE, BUFFERS)` for the exact SLA
filtered Views and analytics queries.
