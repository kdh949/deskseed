# 상담사 티켓 저장·감사·동시 수정 처리

## Goal

상담사가 티켓을 만들고 댓글과 필드를 한 번에 저장합니다. 여러 상담사의 수정 항목이 겹치는지 확인하고, 티켓 변경과 감사 기록을 함께 저장합니다.

## Decision and source references

- Decision IDs: D-003, D-005, D-007, D-018, D-019, D-041, D-042, D-049
- Accepted ADRs: 0005, 0007, 0018
- PRD/domain: `docs/01-prd-mvp.md` M3/10/11/12, `docs/02-domain-model.md` 3/4/7
- API operations: `createAgentTicket`, `updateAgentTicket`
- Verification gates: ARCH-002, ARCH-004, TKT-002, TKT-003, TKT-006, CHG-001~004, IDEM-001~003, PERM-001, PERM-002

## Actor and source

- Actor는 인증된 활성 `STAFF`(`AGENT` 또는 `ADMIN`), source는 `AGENT_UI`입니다.
- Agent는 티켓을 생성할 수 있습니다. Admin은 모든 업무 티켓을 수정할 수 있고, Agent는 현재 담당자이거나 현재 티켓 그룹의 활성 멤버여야 수정할 수 있습니다.
- 읽기는 `ALL_TICKETS`, 쓰기는 `GROUP_OR_ASSIGNEE` 정책을 각각 적용합니다.
- 길이 등 형식을 검증한 request/correlation ID와 서버에서 수락한 command ID를 하나의 `TicketAudit`에 기록합니다.

## Product and API contract

- Requirements: REQ-TKT-007, REQ-TKT-009~015, REQ-AUD-001, REQ-AUD-007
- Routes: `POST /api/v1/agent/tickets`, `POST /api/v1/agent/tickets/{ticketNumber}/commands`
- 수정 요청은 `expectedVersion`, `changedFields`, 선택한 필드 값과 PUBLIC/INTERNAL 댓글을 전달합니다.
- 충돌 응답은 RFC 9457 형식의 `409`이며 `currentVersion`과 정렬한 `conflictingFields`를 포함합니다.
- REQ-TKT-015 충돌 배너는 후속 프런트엔드 단계에서 구현합니다. 이 문서는 해당 화면이 사용할 Backend 응답 계약을 다룹니다.

## In scope

- 예상 버전·결과 버전 감사 기록과 동시 수정 조회를 위한 추가 마이그레이션
- 상담사 티켓 생성과 첫 댓글의 공개 여부 선택
- 상태·우선순위·그룹·담당자·댓글을 함께 처리하는 저장 명령
- 교체 가능한 쓰기 권한 정책과 활성 그룹 멤버 배정 검사
- 변경 순서가 있는 감사 이벤트와 DB 수정·삭제 차단 테스트
- 필드별 낙관적 병합·재시도와 RFC 9457 오류 응답
- PostgreSQL 기반 명령·실패·권한·동시성 테스트
- 같은 직원의 UpdateTicket 재시도에는 변경 불가 감사 기록의 원래 결과 반환. 내용·티켓·작업이 다른 command ID 재사용은 거부
- 중복 요청 처리 순서를 보장하는 DB advisory lock, V14 직원 명령 조회 인덱스와 대규모 실행계획 측정 도구
- Core OpenAPI 동기화

## Out of scope

이 단계는 이관, 가져오기, 자식 티켓, trigger·automation 실행과 프런트엔드 composer·충돌 배너를 포함하지 않습니다. Event Sourcing, 티켓의 비관적 락, Kafka와 외부 네트워크 호출도 추가하지 않습니다.

## Invariants and failure semantics

- 현재 상태는 Ticket row에서 읽습니다. 감사 기록은 변경 이력을 설명합니다.
- 한 티켓의 수락된 명령은 하나의 `TicketAudit`을 만듭니다. 실제 변경은 순서가 있는 이벤트로 기록하고, 유효하지만 변경이 없는 요청은 본문 없는 `UPDATE_COMMAND_RECEIVED`로 남깁니다.
- 티켓·댓글·버전 변경과 감사 저장은 같은 트랜잭션에서 처리합니다. 필수 감사 저장 실패 시 전체를 롤백합니다.
- 오래된 요청의 `changedFields`를 `expectedVersion` 이후 결과 버전의 변경 이벤트와 비교합니다. 겹치면 저장 전에 409를 반환하고, 겹치지 않으면 최신 row에 반영합니다.
- 버전 경쟁으로 저장에 실패하면 전체 시도를 롤백하고 최신 row에서 다시 권한·충돌을 검사합니다. 재시도 횟수는 제한합니다. 같은 actor/command ID 요청은 조회·변경 전에 트랜잭션 advisory lock을 잡아 순서대로 처리합니다.
- 댓글만 추가하는 요청은 다른 필드 변경과 충돌하지 않습니다. actor·ID·티켓·수정 내용이 같은 재시도에는 변경 없이 원래 결과를 반환합니다. 내용·티켓·작업이 다르거나 저장된 결과가 여러 개면 409를 반환합니다.
- 값이 바뀌지 않은 요청은 티켓 버전을 유지하고 `UPDATE_COMMAND_RECEIVED`에 재시도 확인 정보를 저장합니다.
- 그룹 변경 후 기존 담당자가 새 그룹의 활성 멤버가 아니면 유효한 `assigneeId`나 명시적 `null`을 함께 보내야 합니다. 그렇지 않으면 전체 명령을 거부합니다.
- CLOSED 티켓과 지원하지 않는 상태 전환은 거부합니다.

## Data, privacy, and threat model

티켓 필드, 수정 불가 댓글, 감사 metadata와 필드별 변경 값을 저장합니다. 댓글 본문은 감사 기록에 복사하지 않고 ID·공개 여부·길이·기존 SHA-256 해시를 사용합니다. 재시도 확인에도 이 정보와 안전한 필드·버전 값을 재사용하며 본문이나 전체 요청의 추가 해시는 저장하지 않습니다.

고객 응답에는 PUBLIC 댓글과 고객용 필드만 제공합니다. 세션으로 인증된 HTTP 요청의 JSON은 검증 대상입니다. 다른 그룹 수정, 필드 목록과 값 불일치, 비활성 담당자 배정, 오래된 값 덮어쓰기, 감사 저장 실패와 내부 댓글 노출을 권한·입력·트랜잭션·DB 감사 보호·조회 테스트로 검사합니다.

## Acceptance scenarios

- 활성 Agent가 PUBLIC/INTERNAL 첫 댓글을 선택해 티켓을 생성하면 티켓·댓글·감사가 함께 저장됩니다.
- 권한 있는 상담사의 댓글·필드·복합·변경 없는 요청은 실제 변경만 순서대로 기록한 하나의 감사를 만듭니다.
- 읽기만 가능한 다른 Agent의 수정 요청은 403이며 데이터가 바뀌지 않습니다.
- 잘못된 그룹·담당자 배정은 전체 명령을 거부합니다.
- 같은 버전에서 다른 필드를 동시에 수정하면 두 요청이 연속 버전으로 반영됩니다. 같은 필드라면 하나가 성공하고 다른 요청은 최신 버전과 충돌 필드가 담긴 409를 받습니다.
- 감사 insert 실패를 주입하면 티켓·필드·댓글·버전 변경이 모두 롤백됩니다.
- runtime DB role로 원본 감사 기록을 UPDATE/DELETE하면 PostgreSQL이 거부합니다.
- 같은 ID의 순차·동시 재시도는 댓글·감사·버전을 한 번만 변경하고 원래 결과를 반환합니다. 다른 본문·티켓·작업에 같은 ID를 쓰면 409를 반환합니다.

## Known release limitation

IDEM-002의 409 응답과 두 번째 변경 방지는 통과했습니다. 원래 요청의 actor/command ID는 변경 불가 처리 기록에서 찾을 수 있습니다. 다만 거부된 ID 재사용 요청의 request ID는 별도 보안·접근 감사로 남기지 않아 원래 요청과의 연결을 기록으로 추적하는 검증은 `LIMITED`입니다. 따라서 IDEM-002 전체도 `LIMITED`로 유지합니다. 이 릴리스 hardening에서는 해당 감사 기능을 추가하지 않습니다.

## Compatibility and migration

M3 OpenAPI를 실제 flat command 형식에 맞춰 확정합니다. 기존 M3 릴리스 구현이 없으므로 릴리스 전 계약 정리입니다.

V7은 예상 버전 저장 구조를 추가합니다. V14는 STAFF 재시도 조회에 부분 인덱스 `(actor_id, command_id, created_at, id)`를 추가합니다. CreateChildTicket이 같은 command ID로 부모·자식 감사를 만들 수 있어 unique 인덱스는 사용하지 않습니다. 조회 결과가 여러 개면 안전하게 거부합니다.

V14는 데이터 변경 없는 추가 마이그레이션입니다. 복구는 애플리케이션 롤백과 백업 복원·후속 수정으로 진행하며, 인덱스 삭제를 자동 롤백으로 제공하지 않습니다. V11→V14 운영 테스트와 100만 감사 기록 측정은 다시 실행해야 합니다.

## Human explanation

필드별 감사 이력으로 오래된 요청의 충돌을 판단합니다. 별도 Event Sourcing 저장소 없이 필요한 변경 정보를 얻을 수 있습니다. 낙관적 저장 실패 후에만 다시 시도해 현재 담당자·그룹 기준으로 권한과 충돌을 재검사합니다. 전체 티켓 읽기와 담당 범위 쓰기는 별도 정책이며 쓰기 정책은 교체할 수 있게 둡니다.
