# Codex Brief 01 — Access Audit Foundation

## Goal

직원이 티켓 상세을 의도적으로 열거나 검색했을 때, 감사 담당자가 actor·검색·조회 경로를 신뢰할 수 있게 기록한다.

## Required decisions

D-013, D-014, D-018, D-019, D-020

## In scope

- AccessAuditEvent model/migration/repository
- actor/session/request/interaction context
- `TICKET_VIEWED`, `SEARCH_EXECUTED`, `SEARCH_RESULT_OPENED`
- semantic interaction deduplication
- strict audit persistence failure behavior
- redacted query + HMAC fingerprint
- encrypted raw query port and disabled/no-key behavior
- PostgreSQL-backed tests

## Out of scope

- Audit Explorer UI
- long-term checkpoint archive
- customer profile/attachment/export events except stable enum seam
- full search engine

## Acceptance

1. one navigation creates one view event;
2. polling with same interaction does not create another;
3. search records filters/result count/protected query forms;
4. opened result links to search;
5. audit insert failure prevents protected success response;
6. Agent cannot query access logs;
7. secrets/raw query do not appear in application logs.

## Gates

ARCH-004, ACC-001, ACC-002, ACC-003, ACC-004, ACC-007
