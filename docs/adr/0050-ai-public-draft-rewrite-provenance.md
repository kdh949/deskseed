# ADR 0050: AI PUBLIC 답변 초안 재작성 provenance

- Status: Accepted
- Date: 2026-09-19
- Decision: D-067
- Requirements: REQ-AI-001, REQ-AI-002, REQ-AI-003, REQ-AI-006

## Context

상담사는 공개 답변 초안을 새로 검색·생성하지 않고도 문체와 길이를 조정할 필요가 있다. 그러나 임의의 작성기 본문을 AI 입력으로 받으면 INTERNAL 메모, 고객 개인정보 또는 출처가 불명확한 텍스트가 새 provider 전송 경로로 들어갈 수 있다. 원 답변의 이름·정책·금액·날짜·조건·부정 표현과 공개 KB 인용이 바뀌면 문체 개선보다 더 큰 정확성 위험도 생긴다.

## Decision

`ticket.reply_rewrite`는 독립된 비동기 feature이며 다음 경계를 따른다.

- client는 본문을 제출하지 않고 같은 workspace, ticket, requester staff에 결합된 `ticket.reply_draft` 작업의 `sourceJobId`만 제출한다. source는 chain이나 임의 문자열이 아니라 아직 만료되지 않은 `SUCCEEDED` 원 답변 작업이어야 한다.
- 첫 공개 범위는 `language=ko`, `tone=calm|formal`, `length=concise|standard`의 닫힌 카탈로그다. Backend가 세 값을 모두 정규화하며 AI runtime이나 client가 다른 값을 만들지 않는다.
- v1은 `REUSE_OR_CREATE`만 허용한다. `NEW_CANDIDATE`, 상담사가 편집한 PUBLIC draft, INTERNAL composer, summary/triage 결과, 다른 상담사·ticket의 결과는 입력으로 받지 않는다.
- Backend는 source binding과 현재 staff/ticket/feature 정책을 소유한다. AI worker가 source ciphertext를 복호화하기 전 body-free rewrite-source authorization을 요청하며, Backend는 현재 권한·PUBLIC context revision·source binding을 다시 검사하고 required `AI_RESULT_READ` access audit를 commit한 뒤에만 사용 capability를 반환한다.
- AI worker는 원 답변의 암호화된 answer와 canonical citation map만 사용한다. 전체 ticket PUBLIC 본문, context memory, vector/keyword retrieval, KB 원문을 다시 읽지 않는다. source result는 새 job DB로 복제하지 않고 처리 중 메모리에서만 사용한다.
- 재작성 호출은 원 답변과 citation map을 입력으로 받고 모든 citation source ref를 같은 순서로 반환해야 한다. 숫자·금액·날짜·시간·비율·URL·email·정책/조건/부정 marker의 versioned deterministic guard와 별도 strict preservation verdict가 모두 통과해야 한다. verdict는 이름, 정책, 금액, 날짜, 조건, 부정 의미의 유지 여부만 bounded reason code로 반환하며 본문을 telemetry에 남기지 않는다.
- 검증 실패, invalid/unknown output, provider delivery `UNKNOWN`, 현재 권한·context·citation 변경, source expiry 또는 budget 부족에는 usable rewrite body를 commit하지 않는다. rewrite job은 typed `NEEDS_REVIEW`/failure code와 `sourceJobId` metadata만 반환하고 UI는 이미 승인된 원 `ticket.reply_draft`를 계속 표시한다.
- 성공 결과는 새 answer와 원본과 동일한 canonical citations를 가진 `ReplyRewriteResult`다. Backend GET은 원 답변과 같은 현재 권한·context·citation·expiry 재검증 및 required result-read audit 뒤에만 body를 반환하며 `canInsert=true`를 계산한다.

## Cost and lifecycle

- `GENERATION_REWRITE`와 `GENERATION_REWRITE_VALIDATION`은 서로 다른 durable call/receipt다. 각 호출 직전에 workspace, actor, job budget을 고정 순서로 예약하고 취소·deadline·policy/source freshness를 재검사한다.
- schema/source-ref/deterministic guard 실패는 validation 호출 전에 종료한다. validation은 최대 한 번이며 interactive generation call은 합계 2회를 넘지 않는다. `UNKNOWN`을 재시도하거나 원 답변 생성을 다시 호출하지 않는다.
- 초기 route는 server-owned standard model alias만 사용한다. S11 low-cost cohort 또는 provider fallback에 자동 편입하지 않으며 별도 품질·비용 승인이 있어야 변경한다.
- rewrite result ciphertext는 기존 7일 result 보존, execution/sourceJobId metadata는 기존 30일 상한을 따른다. source result가 만료되면 새 rewrite를 시작할 수 없지만 이미 생성된 rewrite result는 자신의 보존·freshness 규칙을 따른다.

## Authorization and audit

- actor는 staff session이고 source는 `AGENT_WORKSPACE`다. source job과 rewrite job은 동일 requester staff, ticket, workspace여야 한다.
- create는 현재 ticket read, active staff, feature allowlist, CSRF/expected actor, idempotency를 재검증한다. source authorization은 machine actor `INTEGRATION_CLIENT`가 rewrite job binding으로만 요청하며 caller가 source owner/ticket을 선택하지 않는다.
- rewrite source 사용의 required `AI_RESULT_READ` persistence가 실패하면 source ciphertext를 복호화하거나 provider를 호출하지 않는다. metadata polling과 body-free revision 확인은 semantic `TICKET_VIEWED`를 만들지 않는다.
- audit/log/trace/Langfuse에는 원/재작성 answer, KB 원문, prompt, protected span, 임의 provider error를 넣지 않는다. source/rewrite job ID는 canonical binding·audit metadata에는 저장할 수 있지만 metric label에는 사용하지 않는다.

## Consequences

장점은 임의 작성기 텍스트를 새 AI 유출 경로로 만들지 않고 기존 승인 결과를 재사용하며, 실패 시 원 답변을 보존한다는 점이다. 단점은 상담사가 직접 편집한 문장을 재작성할 수 없고, 한 번의 재작성과 한 번의 검증 호출이 필요해 단일 호출보다 비용과 지연이 늘 수 있다는 점이다. fake provider와 deterministic guard 통과는 실제 이름·정책·부정 의미 보존의 품질 증거가 아니므로 live holdout과 사람 검토 전에는 기능을 기본 off로 둔다.

## Alternatives considered

- composer 본문 직접 제출: INTERNAL/PII provenance와 입력 감사 계약이 없어 거부한다.
- 원 ticket과 KB를 다시 검색해 답변 생성: 문체 변경이 아니라 새 답변 생성이며 비용과 source drift를 늘려 거부한다.
- prompt 지시만으로 사실 보존: semantic 변화와 누락을 fail closed로 검출하지 못해 거부한다.
- provider가 반환한 confidence만 검사: 검증 근거가 아니므로 거부한다.
- 로컬 문자열 치환만 사용: 닫힌 문구에는 안전하지만 일반 답변의 문체·길이 조정 요구를 충족하지 못한다.

## Revisit triggers

- staff-edited PUBLIC draft 입력을 요구하는 제품 결정과 별도 provenance/security/audit 계약 승인
- 영어 또는 추가 tone/length 카탈로그의 holdout·사람 평가 승인
- preservation validator의 측정된 false-positive/false-negative가 허용 범위를 벗어남
- 두 호출 비용이 재검색 없는 이점보다 크거나 local deterministic rewrite가 요구 품질을 충족함
