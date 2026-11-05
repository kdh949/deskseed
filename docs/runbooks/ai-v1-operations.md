# AI V1 운영과 복구 절차

## Scope and authority

Deskseed Backend는 권한, PUBLIC 데이터 제공, 설정과 원본 감사 기록을 관리합니다. AI DB는 실행 상태와 검색용 데이터를 저장합니다. 운영 작업은 인증된 Admin API를 사용합니다. DB를 직접 수정하는 복구는 사고 대응 시 담당자와 백업을 기록한 뒤 진행합니다.

AI의 기본값은 OFF입니다. 활성화 전에 Backend outbox, AI PostgreSQL·Redis·API 상태, 요금 설정, 역할별 인증 정보와 예산을 확인합니다. 실제 모델과 Langfuse는 각각 별도로 활성화합니다.

## Normal checks

1. `/api/v1/admin/ai/status`에서 Backend의 `dataAsOf`와 AI 서비스의 `aiServiceDataAsOf`를 각각 기록합니다.
2. Backend의 작업·도움말 outbox에서 대기 건수와 dead 건수를 확인합니다.
3. AI 작업 수, 예약·정산·`UNKNOWN` 비용(micro-USD), 인덱싱한 PUBLIC 버전 수, 마지막 전체 대조 시각과 dead-letter 수를 확인합니다.
4. 의존 서비스의 값을 조회하지 못하면 확인 불가로 기록합니다. 0건으로 처리하지 않습니다.
5. Compose의 서비스별 설정과 secret 이름을 확인하되 값은 출력하지 않습니다.

## Stop procedure

1. 버전을 확인하는 Admin 설정 API로 전역 AI flag를 OFF로 바꿉니다.
2. 새 모델 호출을 중단하면서 API, dispatcher, recovery, indexer와 비용 정산·데이터 대조 작업은 유지합니다.
3. 진행 중인 작업을 취소하거나 정해진 기한 안에 종료되도록 기다립니다. `UNKNOWN` 비용 기록은 보존합니다.
4. Backend와 AI outbox의 발송 요청을 처리한 뒤 모델 워커를 종료합니다. 조사에 필요한 DB와 Redis AOF를 보존합니다.

## Redis loss or pending entries

- PostgreSQL의 `ai_dispatch_outbox`를 원래 발송 요청으로 사용합니다. `run-recovery`는 안전 대기 시간이 지난 뒤 전달 상태만 남은 작업을 다시 발송합니다.
- 일반 워커는 `XAUTOCLAIM`으로 오래 대기한 메시지를 가져와 신규 작업과 같은 원문·provider 인증 검사를 거쳐 실행합니다. recovery는 DB의 발송 요청을 다시 전달하는 역할만 맡습니다. `generation`과 `lease epoch`를 확인해 이전 워커의 상태 변경을 막습니다.
- Stream 메시지에는 schema version, job ID, generation과 trace context만 담습니다. 티켓이나 도움말 본문으로 메시지를 재구성하지 않습니다.

## Stuck lease or retry

- deadline, generation, lease 담당 워커와 만료 시각, 취소 기록, Backend의 원문 접근 권한을 확인합니다.
- 운영 명령에도 중복 처리 방지와 expected generation을 적용합니다. 이전 generation의 워커는 provider를 호출하거나 결과를 커밋하지 못하게 합니다.
- 정해진 재시도 횟수를 넘기면 작업을 종료하거나 dead-letter로 보냅니다.

## UNKNOWN provider charge

- `UNKNOWN`은 provider에 요청이 도달했는지 또는 어떤 결과로 끝났는지 확인하지 못한 상태입니다. 해당 비용은 계속 예산에서 차감합니다.
- provider의 요청 기록과 operation key를 대조해 한 번만 정산합니다. 확인되지 않은 비용은 `UNKNOWN`으로 유지합니다.
- 날짜가 바뀌어도 예약을 해제하지 않습니다. 남은 예산이 소진되면 담당자에게 확인을 요청합니다.

## PUBLIC knowledge withdrawal and reindex

- 문서 공개 취소·보관·본문 마스킹과 상위 문서의 공개 대상 변경은 도움말 변경과 같은 트랜잭션에서 본문 없는 Backend outbox 이벤트를 만듭니다.
- indexer는 전용 인증 정보로 지정된 PUBLIC 버전을 가져옵니다. 문서가 사라졌거나 버전이 바뀌었다면 이전 본문을 다시 등록하지 않습니다.
- 매일 Backend의 24시간 snapshot을 UUID cursor로 나눠 읽어 인덱스와 대조합니다. 마지막 페이지까지 커밋한 뒤 누락 문서를 삭제 처리합니다. 토큰 만료나 일부 페이지 실패로 끝난 경우에는 아직 읽지 않은 문서를 삭제하지 않습니다.
- 관리자 재색인은 고정 operation ID를 사용합니다. 같은 ID의 재요청에는 기존 처리를 반환합니다. 한 요청은 manifest 항목 10,000개로 제한하며 초과 시 전체를 롤백합니다.
- Backend는 결과를 반환하기 전에 모든 인용 문서의 현재 권한을 다시 확인합니다. 문서가 철회됐다면 비동기 삭제가 끝나기 전에도 결과를 오래된 상태로 표시하고 본문을 반환하지 않습니다.

## Provider or Langfuse outage

- Provider 장애: 기능을 OFF로 유지하거나 정해진 횟수만 재시도합니다. 비용 예약, 원문 권한 확인과 출력 schema 검증은 그대로 적용합니다.
- Langfuse 장애: 작업 실행은 계속할 수 있으며 피드백 전송은 lease와 재시도로 처리합니다. trace metadata에는 프롬프트, 결과, 티켓 본문, 고객 정보, 인증 헤더와 secret을 넣지 않습니다.
- 워커 원문 조회, indexer 원문 조회, Backend→AI, provider, Langfuse와 결과 암호화 인증 정보는 각각 교체합니다. 서로 다른 방향의 인증 키를 재사용하지 않습니다.

## Retention and restore

- 암호화한 결과는 7일, 작업 metadata는 30일 후 삭제합니다. 중복 처리를 막는 기록과 정산 완료·`UNKNOWN` 비용 기록은 필요한 대조를 위해 보존합니다.
- 백업 복원 후에는 보존 기간 처리와 PUBLIC 문서 대조를 다시 실행하고 기능을 활성화합니다. 백업 이후에 삭제된 데이터가 복원될 수 있기 때문입니다.
- schema 복구는 후속 마이그레이션이나 전체 백업 복원으로 진행합니다. 일부 테이블만 이전 버전으로 되돌리지 않습니다.

## Incident evidence

시각, 버전, operation/job ID, 상태별 건수, generation·lease epoch, outbox 상태, 정해진 오류 코드와 비용 상태를 기록합니다. PUBLIC 본문, 프롬프트·결과, 검색 원문, 고객 정보, 토큰·쿠키·인증 정보는 티켓이나 로그에 복사하지 않습니다.
