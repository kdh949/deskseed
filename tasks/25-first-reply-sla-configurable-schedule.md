# Codex Brief 25 — First Reply SLA with Configurable Schedule

## Goal

Apply a versioned First Reply SLA to customer tickets and show/report achieved, at-risk, and breached outcomes.

## Requirements

REQ-SLA-001, REQ-SLA-003.

## In scope

- ordered SLA policy and priority targets entered by admin.
- applied schedule/policy version snapshot.
- start on first customer-visible creation/comment.
- stop on first qualifying PUBLIC staff reply.
- INTERNAL notes never satisfy the target.
- PENDING pauses by default and is admin-editable.
- ticket badge/view column, deterministic scanner, analytics fact.
- policy preview, activation, and admin audit.

## Out of scope

Next reply, requester wait, resolution, OLA, custom-field conditions.

## Acceptance

SLA-001/002/004/005/006/008; no-policy distinct from achieved and historical results preserved after edits.

## Required verification IDs

`SLA-001`, `SLA-002`, `SLA-004`, `SLA-005`, `SLA-006`, `SLA-008`, `SLA-009`, `ANA-004`.
