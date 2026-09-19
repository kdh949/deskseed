# ADR 0053: AI PUBLIC 답변 초안의 전송 귀속 경계

- Status: Accepted
- Date: 2026-09-19
- Decision: D-070
- Requirements: REQ-AI-001, REQ-AI-005

## Context

상담사가 AI 답변을 PUBLIC 작성기에 삽입했다는 사실은 고객에게 실제 댓글을 전송했다는 뜻이 아니다. 현재 `inserted`·`edited` feedback은 UI 동작을 나타낼 뿐이며, 댓글 command의 성공 commit과 연결되지 않는다. 따라서 이를 사용 초안 수, 후보별 전송 수 또는 비용 절감의 분모로 쓰면 취소·재시도·INTERNAL 전환을 잘못 집계한다.

최종 전송은 기존 Core `UpdateTicket` command가 소유한다. Comment, TicketAudit, command replay와 고객 알림 intent의 원자성을 유지하면서도 AI 서비스나 Langfuse 장애가 고객 응답을 막아서는 안 된다. 또한 AI 원문이나 diff를 TicketAudit, outbox, 로그, Langfuse에 복제하지 않고 수정량을 계산해야 한다.

S08의 cache/coalescing에서는 여러 job이 같은 생성 결과를 소비할 수 있으므로 `jobId`만으로 후보를 식별하면 하나의 생성 후보가 여러 후보로 과대 집계된다. 반대로 임의의 client candidate ID를 신뢰하면 다른 actor/ticket/job의 결과를 성공 사용으로 가장할 수 있다.

## Decision

Deskseed는 AI 후보 조회 시 만든 body-free use binding과 PUBLIC comment command 안의 optional attribution을 결합한다. 유효한 attribution intent는 comment·TicketAudit과 같은 Backend transaction에 기록하고, AI 통계 반영은 durable post-commit outbox로 전달한다.

### Stable candidate identity

- insert 가능한 `ticket.reply_draft`와 `ticket.reply_rewrite` 결과는 server-owned `candidateId`를 가진다. provider 또는 browser가 이 값을 발급하거나 바꿀 수 없다.
- 새 생성은 새 candidate ID를 갖는다. S08 `NEW_CANDIDATE`는 이미 발급한 candidate ID를 결과 candidate ID로 사용한다.
- exact cache hit와 coalesced consumer는 origin result의 candidate ID를 상속한다. 같은 생성 결과를 여러 job이 읽어도 candidate first-use는 하나다.
- legacy 또는 migration 이전 결과처럼 stable candidate를 증명할 수 없는 결과는 표시·삽입할 수 있어도 sent attribution에서는 `UNATTRIBUTED`다. 과거 값을 추정하거나 job ID를 candidate ID로 재해석하지 않는다.

### Authorized use binding

- Backend가 current requester/ticket/feature/source/expiry를 재검증하고 required `AI_RESULT_READ` audit과 함께 결과 본문을 반환할 때만 candidate use binding을 만든다.
- binding은 workspace, requester staff, ticket, job, candidate, normalized answer SHA-256, code-point length, result expiry와 bounded contract version만 저장한다. 답변, citation, prompt, diff와 customer identity는 저장하지 않는다.
- binding은 결과 만료보다 오래 유효하지 않으며 다른 actor, ticket, job 또는 candidate로 이동할 수 없다. AI stop, stale result, 권한 철회 또는 source invalidation 뒤에는 새 binding을 만들지 않는다.
- browser는 반환받은 원 candidate text와 lineage를 PUBLIC draft의 메모리 상태에만 유지한다. 이를 INTERNAL draft, 다른 ticket/actor session 또는 영속 browser storage로 옮기지 않는다.

### PUBLIC comment command

- Core `UpdateTicket` comment에 versioned optional `aiAttribution`을 추가한다. 새 Staff UI는 PUBLIC 전송마다 `NO_AI_LINEAGE | LINEAGE_PRESENT | LINEAGE_LOST`를 명시하고 legacy client omission은 `UNINSTRUMENTED`다.
- `LINEAGE_PRESENT` source는 bounded ordered list이며 각 항목은 `jobId`, `candidateId`, exact original answer를 포함한다. 원문은 binding digest 검증과 edit-distance 계산 중 memory에서만 사용하고 request descriptor, DB, audit, outbox, log 또는 trace에 저장하지 않는다.
- 모든 source가 current actor/ticket/job/candidate binding과 일치해야 attribution이 성립한다. 하나라도 stale, expired, mismatched, duplicated 또는 unbound이면 comment command는 계속 수행하되 `UNATTRIBUTED_VALIDATION_FAILED`로 기록하고 어떤 candidate도 sent로 집계하지 않는다.
- INTERNAL comment의 attribution은 `IGNORED_INTERNAL`이며 AI PUBLIC usage outbox를 만들지 않는다. `NO_AI_LINEAGE`, `LINEAGE_LOST`, invalid attribution과 legacy omission도 AI sent event를 만들지 않는다.
- 구조적으로 잘못되거나 bound를 넘은 request는 일반 request validation으로 거부한다. 정상 구조의 attribution을 검증할 수 없는 것은 고객 응답 실패 사유가 아니다.

### Edit and sent metrics

- single-source attribution은 `AI_USAGE_TEXT_V1`로 원 candidate와 최종 canonical PUBLIC plain text를 정규화한다. Unicode NFC, CRLF/CR→LF와 양끝 whitespace 제거만 적용하며 대소문자나 내부 whitespace를 접지 않는다.
- Unicode code point 기준 Levenshtein distance로 `editRatio = min(1, distance / max(originalLength, finalLength, 1))`를 계산하고 original/final length, insertion count와 deletion count를 저장한다. 본문·diff·digest는 통계 outbox에 넣지 않는다.
- multi-source 전송은 `MULTI_SOURCE`와 source count로 분리하고 하나의 정확한 edit ratio를 만들지 않는다. candidate별 `sent`는 기록하지만 candidate first-use와 sent comment count를 별도 집계한다.
- Backend row와 AI receiver 모두 `(commentId, candidateId)`를 unique하게 만들어 exact command replay, dispatcher redelivery와 receiver retry가 sent를 중복 증가시키지 않는다.
- `inserted`·`edited` feedback은 UI interaction signal로 유지하며 Backend-confirmed `sent`를 대체하지 않는다.

### Transaction and delivery

- valid attribution row, comment, ordered TicketAudit와 body-free AI sent outbox는 기존 `UpdateTicket` transaction에서 함께 commit/rollback한다. exact `clientCommandId` replay는 원 comment/result를 반환하고 새 attribution/outbox를 만들지 않는다.
- D-049 request descriptor에는 attribution version/state, ordered job/candidate IDs와 transient original text의 digest만 포함한다. 원문, diff와 edit-distance working state는 포함하지 않는다.
- outbox delivery는 transaction 후 실행한다. AI service/Langfuse unavailable, timeout, duplicate delivery 또는 exporter failure는 committed comment, TicketAudit와 고객 알림을 rollback하지 않는다.
- AI receiver는 body-free event를 별도 usage table에 멱등 반영하고 canonical cost/result metadata와 연결한다. Langfuse는 retryable projection이며 canonical sent record가 아니다.

## Authorization and privacy

- attribution은 별도 comment 권한을 부여하지 않는다. 현재 STAFF session, expected actor, CSRF, ticket write policy와 PUBLIC comment 규칙을 그대로 통과해야 한다.
- job/candidate binding 조회는 현재 actor와 ticket에 한정되고 mismatch는 다른 resource 존재를 드러내지 않는 unattributed 결과로 수렴한다.
- customer API, webhook, Platform API, customer notification과 TicketAudit projection에는 job/candidate/edit metric을 추가하지 않는다.
- candidate binding과 sent usage metadata의 기본 보존은 30일이다. 미전달 outbox와 조사 중인 dead delivery는 해결 전 삭제하지 않는다. canonical comment/TicketAudit 보존은 기존 정책을 따른다.

## Consequences

실제 성공한 PUBLIC comment와 AI 후보를 연결하고 삽입·전송·수정량을 구분할 수 있다. cache/coalesced job도 하나의 origin candidate로 집계되며 재시도는 중복 sent를 만들지 않는다. 비용은 Backend schema, result-read binding, comment command transaction, durable outbox와 AI receiver에 걸친 추가 상태다.

attribution 검증 실패를 댓글 실패로 바꾸지 않으므로 sent coverage가 100%라고 보장하지 않는다. 추적할 수 없는 복사·만료·lineage 상실은 비용 0 또는 미사용으로 단정하지 않고 명시적 unattributed cohort로 남긴다.

## Alternatives considered

- `inserted`를 sent로 간주: 취소·삭제·INTERNAL 전환과 실제 command 실패를 구분하지 못해 거부한다.
- browser가 sent와 edit ratio를 직접 feedback으로 전송: command commit과 분리되고 actor/job binding과 exact replay를 증명할 수 없어 거부한다.
- AI service를 ticket transaction 안에서 동기 호출: 외부 장애가 comment transaction과 결합하므로 거부한다.
- AI 원문을 Backend에 영속 저장: 기존 암호화 result의 중복 보존과 privacy surface를 만들므로 거부한다.
- job ID를 candidate identity로 사용: cache/coalesced consumer가 같은 생성 결과를 중복 후보로 집계하므로 거부한다.

## Revisit triggers

- multi-source 편집 기여도를 정확히 계산해야 하는 제품 요구
- 30일보다 긴 비용·품질 cohort 보존에 대한 operator/legal 결정
- candidate binding 생성이 result-read latency 또는 Backend DB 부하 목표를 넘는 측정 근거
- cross-actor handoff나 공유 draft가 필요해 requester-bound lineage 정책을 바꿔야 하는 경우
- browser 밖의 channel adapter가 AI candidate를 전송하는 use case
