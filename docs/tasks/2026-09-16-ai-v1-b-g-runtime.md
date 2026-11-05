# AI V1 B–G — 비동기 실행과 복구 구현 기록

## Goal

백엔드의 AI 계약과 데이터 제공 API를 바탕으로 서버 기능을 구현한 기록입니다. 요약·분류·답변 초안, 작업 복구, 공개 도움말 인덱싱, 비용 제한, 피드백, 상태 조회, 보존 기간과 평가 도구를 다룹니다.

## Decisions and gates

- Decision: D-066; Accepted ADR 0049, ADR 0002/0008/0009/0025.
- Requirements: REQ-AI-003 through REQ-AI-005.
- Gates: AI-LIFE-001, AI-COST-001, AI-KB-001, AI-TRIAGE-001, AI-REPLY-001, AI-OBS-001, AI-OPS-001, AI-RET-001.
- Contracts: Core agent/admin AI operations, AI internal command/status API, Backend AI source API.

## Actors and boundaries

- 상담사·관리자는 세션, 현재 티켓 권한, 요청한 직원의 일치 여부를 확인합니다. 변경 요청에는 CSRF 검사를 적용하고 작업은 요청자별로 조회합니다.
- Backend→AI, worker→Backend, indexer→Backend는 각각 별도의 고정 `INTEGRATION_CLIENT` 인증 정보를 사용합니다.
- 입력은 PUBLIC 댓글과 현재 공개된 도움말 버전으로 제한합니다. INTERNAL 댓글, 고객 프로필, 첨부파일, 감사 내용, 비공개 도움말은 제외합니다.
- 결과를 반환할 때 현재 권한을 확인하고 필수 접근 감사를 저장합니다. 저장에 실패하면 결과를 반환하지 않습니다. Provider와 Langfuse 호출은 티켓 트랜잭션 밖에서 실행합니다.

## Failure semantics

- 각 DB의 outbox에 발송 요청을 저장합니다. 수신 측은 요청 내용을 비교해 같은 이벤트의 재전달을 중복 처리하지 않습니다.
- `generation`과 `lease epoch`를 검사해 이전 워커의 상태 변경을 막습니다. 취소 기록은 이벤트가 순서대로 도착하지 않아도 유지합니다.
- 외부 호출 전에 비용을 정수 micro-USD로 예약합니다. 처리 결과가 불확실하면 `UNKNOWN`으로 남겨 확인을 마칠 때까지 예산에서 차감합니다.
- 재시도 횟수와 기한을 제한합니다. 출력 형식 오류, 근거 부족, 정책 비활성화, 오래된 입력, 예산 초과는 작업을 종료하고 본문을 제공하지 않습니다.
- 공개 도움말은 지정한 버전을 확인하고 같은 트랜잭션에서 검색용 조각을 교체합니다. 백엔드는 인용 권한을 다시 검사해 철회된 문서의 사용을 막습니다.

## Verification scope

- 자동 검증: 합성 모델을 사용하는 인증 API 흐름, 실제 PostgreSQL/pgvector·Redis의 실행·복구, Backend 통합·마이그레이션, OpenAPI·문서, Compose 렌더링·서비스별 secret 전달, 이미지 빌드, 합성 평가.
- 당시 후속 작업: 프런트엔드·Storybook·브라우저, 실제 모델 호출, Langfuse 수신 확인, 운영 문서의 답변 품질, 운영 부하·배포.

## Compatibility and rollback

HTTP 계약은 기존 기능에 추가하며 AI의 기본값은 OFF입니다. 중단할 때는 먼저 기능을 끄고, 데이터 동기화·삭제·비용 정산을 유지하면서 저장된 발송 요청을 처리한 뒤 모델 워커를 종료합니다. 마이그레이션은 앞으로 적용하는 방식이며, 복구는 전체 백업 복원 또는 후속 수정 마이그레이션으로 진행합니다.

## Human trade-off

별도 DB와 Redis를 운영해야 하지만 모델 실행과 단기 결과 데이터를 업무 DB에서 분리할 수 있습니다. 권한과 원본 감사 기록은 Backend가 관리합니다. 두 DB를 하나의 분산 트랜잭션으로 묶는 대신 이벤트 재전달, 중복 확인, 워커 소유권 검사와 현재 권한 재검사로 처리합니다.
