# ADR 0010: 프로세스 내부 도메인 이벤트부터 사용

- Status: Accepted
- Date: 2026-08-10

## Context

이 프로젝트에서는 이벤트 기반 구조와 Kafka를 단계적으로 학습합니다. 다만 MVP에는 독립 배포된 여러 consumer가 필요하지 않습니다.

## Decision

도메인 상태가 바뀐 뒤 변경 불가 프로세스 내부 이벤트를 발행합니다. 외부 시스템 호출은 DB 트랜잭션 밖에서 수행합니다. 독립 consumer나 전달 보장 요구가 생기면 transactional outbox를 추가하고 버전이 있는 외부 연동 이벤트를 Kafka로 전달합니다.

## Consequences

- 초기에는 프로세스 내부에서 이벤트로 모듈을 연결합니다.
- 프로세스 내부 listener만으로는 종료 후 이벤트를 다시 전달할 수 없습니다. 전달 보장이 필요한 작업은 DB에 발송 요청을 저장합니다.
- outbox·Kafka 단계에서는 중복 처리 방지, 재전달, 지연 확인, dead letter와 모니터링을 함께 구현합니다.
