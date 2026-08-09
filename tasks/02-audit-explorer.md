# Codex Brief 02 — Unified Audit Explorer

## Goal

Security Auditor가 티켓을 하나씩 열지 않고 ticket changes, access/search, admin/security 활동을 한 화면에서 검색하고 조사한다.

## Required decisions

D-013, D-018, D-019, D-020

## In scope

- `SECURITY_AUDITOR` role/authorities
- AuditActivityProjection or normalized union query
- cursor pagination and filters
- structured ticket field diffs
- search-to-view navigation
- protected comment/search-query reveal endpoints
- export request artifact skeleton or documented follow-up
- self-audit for view/detail/reveal/export

## Out of scope

- arbitrary report builder
- SIEM export
- WORM/checkpoint implementation
- bulk raw-query decryption

## Acceptance

- actor/ticket/action/field/date/source/outcome filters work;
- field before/after is visible without opening ticket;
- default results exclude protected content;
- reveal requires permission/reason and is self-audited;
- Security Auditor cannot mutate tickets/admin settings;
- projection can be rebuilt;
- one-million-row query plan evidence exists.

## Gates

CHG-005, AUD-001 through AUD-006, PERF-002
