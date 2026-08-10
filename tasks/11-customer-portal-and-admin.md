# Codex Brief 11 — Customer Portal and Admin Minimum

## Requirements

REQ-TKT-003~005, REQ-AUD-009.

## In scope

Request list/detail account-ready UI, customer access mode admin, staff/groups/settings audit.

## Staging

Implement anonymous mode completely; keep optional/required account modes behind documented feature flag until identity flow is complete.

## Acceptance

- mode changes audited.
- internal projection regression.
- admin route authorization.
## Accepted v0.6 identity

Implement email magic link, DB-backed single-use tokens, explicit anonymous-ticket claim, and My Requests. Do not auto-claim by email match.
