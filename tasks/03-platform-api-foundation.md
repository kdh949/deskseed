# Codex Brief 03 — Platform API and Integration Client Foundation

## Goal

관리자가 발급한 최소 권한 machine credential로 외부 운영 전산이 티켓을 안전하게 읽고 idempotent하게 생성·수정한다.

## Required decisions

D-012, D-016, D-021, D-022, D-023

## In scope

- IntegrationClient and credential lifecycle
- scoped API-key authentication
- resource constraints
- `/api/v1/platform` adapter
- create/read/update ticket
- internal comment write
- Idempotency-Key store/state machine
- ETag/If-Match
- rate limit contract
- OpenAPI and Problem Details
- integration actor audit/access events

## Out of scope

- OAuth
- public comment scope unless explicitly separate follow-up
- webhooks
- generated SDK release
- admin APIs other than integration client management

## Acceptance

- secret once-display/hash/revoke/rotate/expire;
- missing scope/resource denied;
- no staff impersonation;
- same-key retry makes one ticket/comment;
- different payload/key reuse conflicts;
- stale ETag conflicts;
- every read/write attributed and audited;
- 429 behavior documented/tested.

## Gates

INT-AUTH-001 through INT-AUTH-004, IDEM-001 through IDEM-004, CONC-001, ACC-006
