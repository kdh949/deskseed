# ADR 0021 — Separate server, URL, draft, and layout state

## Status
Accepted

## Context
Ticket screens contain cached server data, shareable filters, unsent public/internal drafts, open tabs, and local panel preferences. Mixing them in one global store obscures correctness.

## Decision
Use TanStack Query for server state, URL parameters for shareable navigation/filter state, local form state for active edits, persisted draft storage for composer buffers, and a small dedicated workspace preference store only for tabs/panel widths if needed.

## Alternatives
- Put all state in Redux/global store: rejected initially as unnecessary and error-prone.
- Keep everything in component state: loses drafts/navigation continuity.

## Consequences
Query invalidation and draft/version reconciliation are explicit. Separate PUBLIC and INTERNAL drafts are required.
