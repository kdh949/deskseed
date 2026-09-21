# ADR 0053 — Exact-number and latest-first staff search

Status: Accepted
Date: 2026-09-21

## Context

The one-million-ticket personal-staging corpus still produced a five-second database cancellation on
the score-ordered path after exact count removal and active/terminal projection splitting. A fixed
20-query, one-VU diagnostic run on deployed SHA `0459830` measured p95 836.989 ms and max
5,078.433 ms for score order. Repeating the same corpus, seed, account, limit, and application SHA
with the existing updated-time order measured p95 393.961 ms and max 713.098 ms. This is diagnostic
evidence, not a capacity or relevance claim.

The current SQL still calculates every weighted `strpos` expression even when the caller requests
updated-time order. Numeric input is also ORed with the full substring predicate, so a direct human
ticket-number lookup can wait for unrelated textual references to the same digits.

## Decision

- A decimal-integer query that fits the canonical `bigint` ticket-number type is an exact ticket-number
  lookup. It does not search textual references containing those digits.
- Exact-number lookup still applies active-staff authorization, snapshot, status/priority/group/
  assignee/SLA filters, active-before-terminal partition semantics, current ticket hydration, and
  required protected search audit before returning a result.
- The Staff Console and load diagnostic default becomes
  `updatedAt:desc,ticketNumber:desc`. The existing score order remains an explicit opt-in for
  non-numeric literal substring search.
- The updated-time SQL path performs literal candidate matching but emits a constant internal score;
  it does not evaluate the weighted `strpos` score expression. Cursor v3 and response shape remain
  unchanged.
- No table, index, database, replica, cache, pool setting, global PostgreSQL setting, or external
  search store is added by this slice.

## Consequences

- Ticket-number lookup becomes deterministic and bounded by at most one matching ticket across the
  active/terminal projections. Searching for a numeric token inside subject or comments now requires
  a non-numeric surrounding term; this is an intentional result-membership contract change.
- The default page is latest-first within each partition, not relevance-ranked. The UI continues to
  label the two choices explicitly, and selected score order preserves the previous ranking formula.
- The updated-time path avoids score calculation, but PostgreSQL can still inspect a broad trigram
  candidate set and sort matching rows. This change therefore does not prove every query is fast.
- Rollback is the previous exact application SHA. No schema cleanup is necessary.

## Verification

- SQL-plan tests prove exact-number predicates omit substring matching and updated-time plans omit
  `strpos` scoring.
- PostgreSQL integration tests prove numeric references are excluded, literal wildcard and INTERNAL
  matching remain, filters and active/terminal behavior remain, and required audit still fails closed.
- Staff Console unit coverage proves the initial request uses latest-first and explicit score selection
  resets pagination.
- Personal staging repeats the fixed corpus after exact-SHA deployment and preserves k6, Grafana,
  trace/log/statistics, health, and recovery evidence with absolute UTC ranges.
