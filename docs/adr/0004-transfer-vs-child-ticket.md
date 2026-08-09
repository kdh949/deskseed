# ADR 0004: Transfer and child-ticket delegation are different commands

- Status: Accepted
- Date: 2026-08-10

## Context

A support team sometimes relinquishes ownership to another team, and sometimes retains customer ownership while asking specialists to investigate. Treating both as reassignment loses accountability and makes customer communication ambiguous.

## Decision

- `TransferTicket` changes the existing ticket's group/assignee ownership.
- `CreateChildTicket` creates an internal ticket linked by `PARENT_CHILD`; the parent owner remains unchanged.
- Child tickets are never exposed through customer APIs.
- Solving a parent with open children produces a warning and is allowed.
- Solving a child never solves the parent automatically.

## Consequences

- Separate authorization, audit events, UI affordances, and tests are required.
- Parent/child linkage becomes a first-class domain relation rather than a comment convention.
