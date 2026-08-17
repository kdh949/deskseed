# Saved View Count Invariant Remediation

Status: **IMPLEMENTATION_READY**

## Goal

Agent frontend가 frozen Saved View count metadata 조합만 수용하고 drifted/malformed success response를 fail closed한다.

## Decision and source references

- Decision IDs: D-003, D-047
- Requirements: REQ-VIEW-001
- Existing API operations: Saved View list/create/update/preview operations
- Verification gates: ARCH-001, ACC-007, PERF-001
- Contract source: `tasks/briefs/p1-saved-view-metadata.md`

## Actor and source

- Actor: authenticated STAFF
- Source: AGENT_WORKSPACE
- 기존 `ALL_TICKETS` read scope와 Saved View permission/audit semantics를 변경하지 않는다.

## In scope

- response decoder가 `ticketCountState`, `ticketCount`, `ticketCountAsOf` cross-field invariant를 검증한다.
- 네 가지 invalid 조합을 negative unit test로 고정한다.
- 기존 테스트 fixture를 frozen contract와 일치하도록 교정한다.

## Out of scope

- backend count query, visibility limit, Saved View AST 또는 OpenAPI schema 변경
- rendered UI/component/Storybook public API 변경
- migration 또는 performance query 변경

## Invariants and failure semantics

- `EXACT`는 non-null integer `ticketCount`와 non-null server timestamp `ticketCountAsOf`를 함께 가진다.
- `OMITTED_VISIBLE_LIMIT`는 두 필드가 모두 `null`이다.
- invalid 2xx response는 partial/coerced UI state로 사용하지 않고 controlled `ApiError(status=200)`로 실패한다.
- create/update의 count 미실행 응답은 기존 `OMITTED_VISIBLE_LIMIT/null/null` 계약을 유지한다.

## Acceptance scenarios

1. Given valid EXACT metadata, When response를 decode하면, Then count와 basis timestamp를 반환한다.
2. Given valid OMITTED metadata, When response를 decode하면, Then count와 timestamp가 모두 null이다.
3. Given EXACT의 count 또는 timestamp가 null, Then response decode는 실패한다.
4. Given OMITTED의 count 또는 timestamp가 non-null, Then response decode는 실패한다.

## Validation

```bash
cd frontend && npm run typecheck
cd frontend && npm run lint
cd frontend && npm run format:check
cd frontend && npm run contract:check
cd frontend && npm run check:design-system-boundaries
cd frontend && npm run test
cd frontend && npm run build
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_api_documentation_quality.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_documentation.py --write
```

## Compatibility and migration

- OpenAPI/migration: 변경 없음
- valid server response: 동작 변경 없음
- frozen contract를 위반한 2xx response만 새롭게 fail closed한다.

## Human explanation

세 필드는 독립 nullable field가 아니라 하나의 discriminated state다. decoder에서 조합을 검증해야 server drift가 UI에서 조용히 정상 상태로 보이는 것을 막을 수 있다.
