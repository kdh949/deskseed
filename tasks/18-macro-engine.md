# Codex Brief 18 — Macro Engine

## User scenario and actors

- 상담사는 자신이 소유한 PERSONAL macro를 versioning하고 활성화한다.
- `macro:shared:manage` capability를 가진 ADMIN은 SHARED macro를 관리한다.
- 상담사는 접근 가능한 활성 macro를 티켓에서 preview한 뒤 명시적으로 apply한다.
- 적용 actor는 macro가 아니라 현재 STAFF이며 source는 `AGENT_UI`이다.

## Traceability

- Requirement: `REQ-CFG-003`
- Decisions: D-033, D-035, D-055, D-056
- ADR: 0022, 0024, 0040, 0041
- Contract owner: `api/core-api-fragments/50-views-macros.yaml`
- Gates: CFG-003, SEC-003, SEC-005, API contract/docs gate, backend integration/migration gate

## Data boundaries

- Macro action은 allowlisted typed configuration만 저장하고 script/expression을 허용하지 않는다.
- COMMENT template은 PUBLIC 또는 INTERNAL 한 가시성을 가지며 allowlisted placeholder만 사용한다.
- Preview는 staff-only ticket projection을 사용하고 required sensitive-read audit 실패 시 성공하지 않는다.
- 관리 변경은 Admin/Security audit과 같은 transaction으로 commit/rollback한다.
- Apply는 일반 Ticket command의 한 audit과 ordered events를 만들며 raw comment 본문을 ordinary log에 남기지 않는다.

## Authorization and failure semantics

- PERSONAL lifecycle은 owner-only이며 다른 owner의 ID 추측은 not-found로 처리한다.
- SHARED lifecycle은 ADMIN role과 `macro:shared:manage` capability의 교집합을 요구한다.
- 모든 mutation은 CSRF, expected staff actor guard, strong `If-Match`를 요구한다.
- Preview 시점과 apply 시점에 ticket read/write, group membership, active configuration을 각각 다시 검사한다.
- version/action/activation history는 immutable이며 실패한 감사 또는 ticket command는 macro apply 성공으로 응답하지 않는다.

## Incremental slices

1. V71 immutable version/action/activation schema와 PERSONAL/SHARED lifecycle.
2. Side-effect-free preview, template rendering, required access audit.
3. One combined ticket command apply, idempotency, one TicketAudit and provenance.

## Not in scope

- 임의 script 또는 동적 expression 실행
- background macro execution
- frontend macro picker/editor
- external network call
