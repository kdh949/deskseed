# ADR 0049: AI V1 실행 환경 분리

- Status: Accepted
- Date: 2026-09-16
- Decision: D-066
- Requirements: REQ-AI-001, REQ-AI-002, REQ-AI-003, REQ-AI-004

## Context

Deskseed의 업무 기능은 Kotlin/Spring Modulith와 PostgreSQL로 구성합니다. AI V1은 PUBLIC 대화 요약, 분류 제안, 공개 도움말 기반 답변 초안을 비동기로 생성합니다.

모델 호출과 복구 작업을 티켓 처리에서 분리하고 Python 라이브러리를 활용하기 위해 AI 실행 환경을 따로 구성했습니다. 처리량 측정에 따른 서비스 분리 결정은 아닙니다. 한 사람이 운영하는 설치형 제품에 FastAPI, 별도 PostgreSQL/pgvector, Redis Streams와 네 Python 실행 역할을 추가하는 비용을 감수합니다.

## Decision

- Kotlin Backend는 직원·관리자 HTTP API, 현재 권한, 작업·요청자·티켓의 연결, PUBLIC 데이터 제공, 원본 접근·관리 감사 기록, 활성화·중단 설정과 `ai_integration_outbox`를 관리합니다.
- 하나의 Python 코드와 이미지를 `ai-api`, `ai-worker`, `ai-indexer`, `ai-dispatcher` 역할로 실행합니다. FastAPI는 내부 명령·조회 API를 제공하고 워커는 Redis Streams 메시지를 받아 실행합니다.
- AI 실행 상태, 암호화한 단기 결과, 비용 예약·정산 내역과 공개 도움말 검색 인덱스는 별도 PostgreSQL 17 + pgvector DB에 저장합니다. AI 서비스에는 업무 DB 인증 정보를 전달하지 않습니다.
- Backend와 AI 서비스는 각 DB에 작업과 outbox 발송 요청을 함께 커밋합니다. 이벤트는 한 번 이상 전달될 수 있으며, 수신 측은 이벤트·작업의 요청 내용을 비교해 중복 처리를 막습니다. 두 DB를 하나의 분산 트랜잭션으로 묶지 않습니다.
- 고객 인증용 Redis와 AI 메시지용 Redis를 분리합니다. 메시지에는 job ID, generation, trace context만 담습니다. 본문·인증 정보·권한 판단에 쓸 데이터는 보내지 않습니다.
- LangGraph는 답변 초안의 `authorize -> retrieve -> validate sources -> generate -> validate output` 흐름 하나에 사용합니다. 반복 횟수가 정해지지 않은 도구 호출, 업무 데이터를 바꾸는 도구, checkpointer/HITL 실행 환경은 추가하지 않습니다.
- LiteLLM Python SDK adapter는 서버가 정한 모델 alias만 사용합니다. SDK 내부 재시도, provider fallback과 캐시는 기본으로 끕니다. 합성 모델과 실제 모델 실행은 별도로 설정합니다.
- Langfuse Cloud에는 본문을 제외한 모니터링·평가 데이터를 전송합니다. 작업 상태, 감사 기록과 비용 제한은 각 담당 DB와 Backend에서 관리하므로 Langfuse 장애가 결과 커밋을 실패시키지 않습니다.

모든 생성 기능은 Backend가 제공하는 PUBLIC 댓글만 사용합니다. INTERNAL 댓글, 협업 메모, 자식 관계, 고객 프로필, 보호된 감사 내용과 비공개 도움말은 입력 API·프롬프트·캐시·결과 출처·trace에서 제외합니다. 답변 초안은 요약·분류 결과를 입력으로 재사용하지 않고 PUBLIC 댓글을 별도로 읽습니다.

## Authorization and audit

- 직원 HTTP 요청은 기존 세션, CSRF, 요청한 직원의 일치 여부와 티켓 읽기 권한을 확인합니다.
- 데이터 제공 API의 호출자는 고정 `INTEGRATION_CLIENT`입니다. 등록된 `jobId -> requesterStaffId/ticketId/feature`를 사용해 권한을 판단하고, 호출자가 actor·ticket·workspace를 임의로 선택하지 못하게 합니다.
- 원문이나 결과를 읽고 사용할 때마다 직원 활성 상태, 티켓 권한, 취소 상태와 입력 버전을 다시 확인합니다.
- 민감한 본문은 필수 `AccessAuditEvent`를 커밋한 뒤 반환합니다. 감사 저장 실패 시 본문을 반환하지 않습니다. polling은 사용자가 의도적으로 티켓을 연 `TICKET_VIEWED`로 기록하지 않습니다.
- AI 생성은 티켓을 변경하지 않으므로 `TicketAudit`을 만들지 않습니다. 설정 변경은 Admin/Security 감사와 함께 커밋하거나 롤백합니다.

## Failure and cost boundaries

- 외부 네트워크 호출은 티켓·원문 조회 트랜잭션 밖에서 실행합니다.
- 작업과 발송 요청의 중복 확인, 증가하는 request revision·generation, 워커 소유권을 확인하는 lease epoch와 heartbeat를 사용합니다. 완료 상태를 커밋한 뒤 메시지를 ACK하고, 중단된 작업을 복구하며 취소 기록을 유지합니다.
- HTTP 전달 재시도와 모델 호출 재시도는 각각 관리합니다. 대화형 작업은 최초 호출을 포함해 최대 2회, 인덱싱은 최대 3회로 제한하고 deadline과 `Retry-After`를 따릅니다.
- 모델·임베딩 호출 전에 workspace → actor 또는 SYSTEM → job 순서로 비용 상한을 예약합니다. 금액은 고정 소수점으로 계산합니다. 요청 도달 여부가 불확실하면 비용을 `UNKNOWN`으로 보존하고, 날짜가 바뀌어도 예약을 해제하지 않습니다.

## Consequences

모델 장애와 지연을 티켓 트랜잭션에서 분리하고 Python의 검색·워크플로 라이브러리를 활용할 수 있습니다. 공개 데이터 제공과 권한 판단도 Kotlin Backend에 남습니다. 대신 별도 DB·Redis·프로세스의 마이그레이션, 백업, 상태 확인, secret 교체와 복구 절차가 필요합니다.

이 구성은 AI V1에 적용합니다. 기존 업무 모듈의 서비스 분리나 다른 기능의 queue·vector store 도입은 별도 결정이 필요합니다. 단일 설치가 한 조직이라는 D-009는 유지합니다. 서버가 정하는 `workspaceKey`는 AI 배포와 예산을 구분하는 값이며 고객 조직이나 SaaS tenant를 뜻하지 않습니다.

## Alternatives considered

- Spring 내부 동기 호출: 구현은 간단하지만 모델 응답을 기다리는 시간과 timeout이 HTTP 요청에 연결되고, 작업 복구와 인덱싱을 분리하기 어렵습니다.
- Spring `@Async` / FastAPI `BackgroundTasks`: 프로세스 종료나 재배포 후 작업을 보존하지 못합니다.
- 기존 domain outbox를 AI 서비스가 직접 소비: webhook 발송 작업과 소비가 경쟁하고, 전달 완료의 의미도 달라 제외했습니다.
- Kafka / Celery / Temporal / LiteLLM Proxy / 별도 reranker: 현재 범위에 비해 운영 부담이 크고 도입을 뒷받침할 측정 결과가 없습니다.
- 업무 PostgreSQL에 vector index 저장: 구성은 간단하지만 AI 서비스에 업무 DB 접근 권한을 주고 검색 인덱스 운영을 업무 DB와 함께 관리해야 합니다.

## Revisit triggers

다음 상황에서는 구성을 다시 검토합니다.

- AI 기능을 제거하거나 Python 라이브러리를 사용할 필요가 줄어드는 경우
- 측정한 운영 비용이 기능의 가치보다 커지는 경우
- 다중 인스턴스·설치형 배포 요구가 생기는 경우
- queue 대기, DB 부하, 문서 규모 또는 복구 목표가 단일 노드 Compose 구성을 넘어서는 경우
- provider·데이터 보관 정책이 PUBLIC 상담 내용의 외부 전송을 허용하지 않는 경우
