# ADR 0049: AI V1 격리 실행 경계

- Status: Accepted
- Date: 2026-09-16
- Decision: D-066
- Requirements: REQ-AI-001, REQ-AI-002, REQ-AI-003, REQ-AI-004

## Context

Deskseed의 기본 아키텍처는 Kotlin/Spring Modulith 모듈러 모놀리스와 하나의 PostgreSQL이다. AI V1은 상담사가 요청한 PUBLIC 대화의 요약·분류 제안·공개 KB 기반 답변 초안을 비동기 실행해야 한다. 모델 호출 지연과 장애, Python AI 생태계, 파생 vector index, 실행 재시도와 비용 원장을 기존 ticket transaction과 분리할 필요가 있다.

이 선택은 처리량 병목을 측정해 서비스 분리를 요구한 결과가 아니다. 한 사람이 운영하는 self-hosted 배포에 FastAPI, 별도 PostgreSQL/pgvector, Redis Streams와 네 Python 실행 역할을 추가하므로 운영 부담을 의도적으로 받아들이는 제품 결정이다.

## Decision

AI V1에만 다음의 좁은 예외를 허용한다.

- Kotlin Backend는 staff/admin HTTP 계약, 현재 권한, job/requester/ticket binding, PUBLIC-only source projection, canonical access/admin audit, typed enable/stop policy와 `ai_integration_outbox`를 소유한다.
- 하나의 Python codebase/image를 `ai-api`, `ai-worker`, `ai-indexer`, `ai-dispatcher` 역할로 실행한다. FastAPI는 internal command/query adapter이고 worker는 Redis Streams message를 받아 실행한다.
- AI 실행 상태·암호화 결과·비용 원장·공개 KB 파생 index는 별도 PostgreSQL 17 + pgvector DB가 소유한다. AI runtime에는 Deskseed 업무 DB credential을 주지 않는다.
- Backend와 AI DB 사이에 분산 transaction을 만들지 않는다. Backend request/outbox와 AI job/dispatch-outbox가 각 DB의 commit 경계를 보호하고 at-least-once 전달은 event/job fingerprint로 멱등 수렴한다.
- 인증 제한용 기존 Redis와 AI Streams Redis를 분리한다. Redis message는 job ID, generation과 trace context만 포함하며 본문·credential·권한 근거는 포함하지 않는다.
- LangGraph는 reply draft의 `authorize -> retrieve -> validate sources -> generate -> validate output` 유한 workflow 하나에만 사용한다. 무제한 tool loop, 업무 mutation tool, checkpointer/HITL runtime은 추가하지 않는다.
- LiteLLM Python SDK adapter는 server-owned model alias만 허용하고 hidden retry, provider fallback, cache를 기본으로 끈다. fake provider와 live provider 실행은 명시적으로 분리한다.
- Langfuse Cloud는 body-free 관측·평가 exporter일 뿐 job DB, canonical audit, budget enforcement가 아니다. 장애는 결과 commit을 실패시키지 않는다.

모든 생성 기능은 Backend가 만든 PUBLIC comment projection만 사용한다. INTERNAL comment, collaboration note, child relation, customer profile, protected audit content와 비공개 KB는 source response, prompt, cache, result provenance와 trace에 들어가지 않는다. 화면용 summary/triage 결과는 reply context로 재사용하지 않는다. 다만 S10부터 아래 조건을 모두 만족하는 별도 `PUBLIC_ONLY` context memory를 reply input의 과거 대화 압축으로 사용할 수 있다.

### Source-backed context memory

- context memory는 화면용 summary 결과나 ticket의 canonical state가 아니다. AI DB의 파생 데이터이며 같은 workspace, requester staff, ticket에만 결합한다.
- memory에는 확정 사실, 시도와 결과, 열린 질문, 각 항목의 PUBLIC comment source ref, 포함한 마지막 PUBLIC sequence, source-prefix digest, memory policy/prompt/model version을 둔다. 본문 파생 payload 전체는 authenticated encryption으로 저장하고 결과 보존 상한보다 오래 보존하지 않는다.
- worker는 매 사용과 갱신 전에 Backend의 전체 current PUBLIC projection을 다시 읽고 current staff/ticket 권한과 required access audit를 통과한다. caller가 memory, source ref, coverage sequence 또는 digest를 제출하거나 선택하지 않는다.
- 저장한 source-prefix의 comment ID, sequence, role, time, body digest가 current projection과 정확히 일치할 때만 memory를 사용한다. 수정, 삭제, visibility 철회, 순서 변경, unknown ref, digest 불일치는 memory를 원자적으로 무효화하며 stale body를 reply에 넣지 않는다.
- 최신 CUSTOMER 발화와 그 뒤 PUBLIC suffix는 항상 원문으로 남긴다. 이 보호 구간이 입력 상한에 들지 않으면 memory로 숨기지 않고 기존 `INPUT_TOO_LONG`으로 종료한다.
- memory 생성 또는 갱신은 승인된 공개 근거가 있는 현재 reply 요청 안에서만 on demand로 수행한다. 선제 batch 생성은 하지 않는다. 기존 memory 이후 delta만 갱신 입력으로 사용할 수 있지만, bounded 횟수마다 current 원문 prefix 전체에서 재구성한다. memory output이 동일 항목의 모순 source를 보고하면 저장·reply 사용을 중단하고 검토 필요로 종료한다.
- server가 같은 가격표와 tokenizer로 계산한 예상 반복 reply 입력 절감액이 memory 생성, 갱신, 정기 재검증 upper bound보다 클 때만 provider call을 허용한다. 손익을 계산할 수 없거나 0 이하이면 기존 원문 선택 경로를 사용한다. fake 실행은 품질 또는 실제 비용 절감 증거가 아니다.
- context memory call은 interactive 생성 호출 상한과 기존 workspace/actor/job 예산, receipt, UNKNOWN, cancellation, lease fencing을 그대로 적용한다. memory 생성 응답이 invalid, source-unknown, stale 또는 UNKNOWN이면 해당 memory를 저장하거나 reply에 사용하지 않는다.

## Authorization and audit

- staff HTTP 요청은 기존 staff session, CSRF, expected-actor guard와 ticket read policy를 사용한다.
- source API machine actor는 고정 `INTEGRATION_CLIENT` principal이며 등록된 `jobId -> requesterStaffId/ticketId/feature` binding을 권한 근거로 사용한다. caller가 actor/ticket/workspace를 선택하지 않는다.
- source read와 result read/use capability는 매번 현재 staff 활성 상태, ticket 권한, cancellation, context/source revision을 재검증한다.
- 민감 body를 반환하기 전 required AccessAuditEvent를 commit한다. 실패하면 body를 반환하지 않는다. polling은 semantic `TICKET_VIEWED`를 만들지 않는다.
- AI 생성 자체는 ticket을 바꾸지 않으므로 TicketAudit을 만들지 않는다. 설정 변경은 Admin/Security audit과 함께 commit/rollback한다.

## Failure and cost boundaries

- 외부 network I/O는 ticket/source read transaction 밖에서 수행한다.
- job/dispatch idempotency, monotonic request revision/generation, lease epoch/fencing, heartbeat, terminal commit 후 ACK, stranded recovery와 cancellation tombstone을 유지한다.
- HTTP relay retry와 실제 model retry는 별개다. interactive는 최초 포함 최대 2회, indexing은 최대 3회이며 deadline과 Retry-After를 존중한다.
- 모든 model/embedding call은 workspace -> actor 또는 SYSTEM -> job 순서로 고정 소수점 upper bound를 먼저 예약한다. 도달 여부가 불확실하면 `UNKNOWN`으로 보존하고 자정 rollover로 해제하지 않는다.
- reply의 context memory 생성/갱신과 최종 답변 생성은 합쳐서 interactive generation 최대 2회를 넘지 않는다. query embedding은 별도 call ledger로 정산하되 memory 준비 실패를 자동 재호출하지 않는다.

## Consequences

장점은 ticket transaction과 모델 장애를 격리하고 Python 기반 retrieval/workflow를 사용할 수 있으며, public-source authorization을 Kotlin Backend에 남긴다는 점이다. source-backed context memory는 반복되는 긴 PUBLIC 대화의 입력을 줄일 수 있지만, 요약 변형과 추가 호출 비용을 만들므로 기본 reply 경로의 필수 구성 요소가 아니다. 단점은 별도 DB·Redis·프로세스·migration·backup·health·secret rotation·복구 절차와 암호화 memory의 무효화·재검증 운영이 추가된다는 점이다.

Redis/pgvector/서비스 분리는 AI V1의 승인이지 기존 backend bounded context를 microservice로 분해하거나 다른 기능에 queue/vector store를 도입하는 일반 승인 아니다. 단일 설치가 한 조직이라는 D-009도 유지하며 `workspaceKey`는 AI 배포 단위 구분용 server-owned 값일 뿐 customer organization 또는 SaaS tenant ID가 아니다.

## Alternatives considered

- Spring 내부 동기 호출: 구현은 단순하지만 model latency/timeout이 request lifetime과 결합되고 durable recovery·indexing 격리가 약하다.
- Spring `@Async`/FastAPI `BackgroundTasks`: 프로세스 종료와 재배포에서 실행 보존을 제공하지 않는다.
- 기존 domain outbox를 AI consumer가 직접 claim: webhook materializer와 경쟁 소비하고 서로 다른 전달 완료 의미를 섞으므로 거부한다.
- Kafka/Celery/Temporal/LiteLLM Proxy/별도 reranker: 현재 범위에 필요한 최소 계약보다 운영 부담이 크며 측정 근거가 없다.
- 기존 업무 PostgreSQL에 vector index 저장: 단순하지만 AI runtime에 업무 DB 접근을 주고 파생 index 운영·권한 경계를 결합하므로 이번 확정 결정과 맞지 않는다.

## Revisit triggers

- AI 기능 제거 또는 Python 생태계 필요성 소멸
- 측정된 운영 비용이 기능 가치보다 큼
- multi-instance/self-hosted topology 요구
- queue wait, DB load, corpus 규모 또는 복구 목표가 현재 단일-node Compose 설계를 초과함
- provider/data-residency/retention 정책이 PUBLIC support content 외부 전송을 허용하지 않음
