# 상담사 AI 어시스턴트 비용 절감 수정 계획

- 작성일: 2026-09-19
- 상태: **제안 계획 / 구현·운영 검증 미실행**
- 코드 기준: `10e21a478fb6e3ebec2026b49ef8cfc68e858637` — AI V1 및 상담사 어시스턴트 UI가 포함된 현재 체크아웃
- 목표: **최신 고객 요구와 공개 근거를 보존하면서, 실제 전송에 사용된 답변 초안 한 건당 총 API 비용을 줄인다.**
- 1차 범위: **서버·AI만** — 입력 정확성, 비용 정합성·기준선, 생성 생략, 정확 일치 재사용, 검색 개선 및 후속 UI가 사용할 API 계약
- 후속 범위: **상담사 UI·최종 전송 추적**, 누적 요약, 평가를 통과한 저가 모델 라우팅, 문체 수정, 프롬프트 캐시, 임베딩 재사용·배치
- 이번 산출물은 계획 문서다. 구현 코드, OpenAPI, migration, Accepted ADR, 요구사항 상태를 변경하거나 유료 API·실데이터·배포를 실행하지 않는다.

## 1. 계획의 근거와 적용 범위

사용자가 제공한 분석을 출발점으로 현재 소스와 계약을 대조했다. 분석에 적힌 ZIP 기준 커밋과 현재 HEAD의 짧은 SHA가 같다. 별도 첨부로 언급된 `deskseed-ai-code-evidence.md`의 내용과 원래 고립 재현 결과는 이 작업에서 열람하거나 재실행하지 않았다. 아래 “확인”은 소스 경로 확인을 뜻하며 운영 재현·청구 정합성·모델 품질 확인을 뜻하지 않는다.

기존 FastAPI·LangGraph·AI PostgreSQL/pgvector·Redis Streams·LiteLLM·Langfuse를 유지한다. 현재 두 모델 별칭과 PUBLIC-only 경계를 유지하며, 새 VectorDB·GPU 호스팅·파인튜닝·멀티에이전트·고객 간 의미 유사도 캐시는 범위에 넣지 않는다.

### 1.1 확정 방향과 제안 기본값

| 구분 | 내용 |
|---|---|
| 사용자 요청에 따른 방향 | 문맥 손실 수정 → 비용 계측 → 불필요한 생성 제거 → 결과 재사용 → 검색 개선 → 저가 모델 확대 |
| 이번 작업의 범위 | 계획 구체화만 수행. 구현 또는 외부 실행 승인이 아님 |
| 사용자 확정: UI 범위 | 서버·AI만 우선한다. S03b의 실제 전송 귀속과 S08b의 ‘최근 결과 사용/다른 초안 생성’ UI는 후속. 1차 `frontend/` 변경은 0이며 frontend client/type 생성도 하지 않음 |
| 사용자 확정: 평가 데이터 | **현재 서버에 있는 실제 데이터를 그대로 사용하도록 승인함.** PUBLIC 대화·현재 공개 KB 원문을 주 평가 자료로 사용. 비식별 변환이나 합성 대체를 전제로 하지 않음 |
| 조정 가능한 운영값 | 캐시 TTL 24시간, 새 후보 최대 2회/동일 입력/24시간, 벡터·키워드 후보 각 20개, RRF 상수 60, 최종 근거 3~5개 |
| 숫자의 지위 | 위 값과 이후 품질·성능 기준은 검증을 시작하기 위한 제안이다. 기존 계약이나 실측 성능으로 간주하지 않는다 |

위 두 선택은 2026-09-19 사용자 답변으로 확정됐다. 동일 범위의 데이터 사용 승인을 반복 요청하지 않는다. 단, 이번 요청은 계획 작성이며 지금 데이터를 추출·변경하거나 평가·유료 호출을 실행하라는 지시는 아니다. 실제 실행 단계에서는 대상 서버/배포 revision/데이터 snapshot과 호출 건수·USD cap을 명시한다. PUBLIC-only 등 기존 접근 범위는 유지한다.

### 1.2 코드 대조 결과

소스 링크는 이 문서 위치를 기준으로 한다. 변경할 대상 파일과 함수는 S01~S14에서 다시 연결한다.

| 확인한 경로 | 현재 동작 | 계획에 반영할 수정 |
|---|---|---|
| [queue.py](../../ai/src/deskseed_ai/queue.py), `_bounded_context` | summary/triage는 PUBLIC 본문 합계 16,000 UTF-8 바이트 초과 시 거부. reply는 첫 댓글 뒤 최근 댓글을 8,000바이트 안에서 선택하며 초과 댓글을 건너뜀 | 바이트 제한을 토큰 계측과 분리하고 최신 고객 발화를 필수로 취급 |
| [StaffTicketReadApi.kt](../../backend/src/main/kotlin/dev/deskseed/ticketing/StaffTicketReadApi.kt), [StaffTicketQueryRepository.kt](../../backend/src/main/kotlin/dev/deskseed/ticketing/internal/StaffTicketQueryRepository.kt), [schemas.py](../../ai/src/deskseed_ai/schemas.py) | PUBLIC source에 id/body/createdAt만 있음 | 서버가 발화자 역할과 안정적인 순서를 projection |
| [providers.py](../../ai/src/deskseed_ai/providers.py), [queue.py](../../ai/src/deskseed_ai/queue.py) | `ClaimedJob.options`가 provider에 전달되지 않음. 공통 system 지침. JSON 검증 후 usage 생성 | typed 옵션·기능별 prompt와 응답/과금/검증 분리 |
| [pricing.py](../../ai/src/deskseed_ai/pricing.py), [pricing-v1.json](../../ai/config/pricing-v1.json) | 최상위 캐시 필드만 읽고, Luna cacheWrite가 없으면 일반 입력 단가 사용 | 공급자별 exclusive usage 정규화와 가격 버전 추가 |
| [repository.py](../../ai/src/deskseed_ai/repository.py), `reserve_budget`, `settle_budget` | 예약 identity는 job/generation/call type. 실제 비용이 예약보다 크면 정산 거부. 원장에 상세 토큰·provider request ID 없음 | call별 receipt, 알려진 비용 보존, 미전송 해제, overrun 처리 |
| [workflows.py](../../ai/src/deskseed_ai/workflows.py) | 승인 후보가 0개여도 generate 노드 실행. 인용 검증까지 끝나야 query usage가 queue로 돌아옴 | 승인 근거 0개면 생성 생략. embedding 과금은 생성 실패와 독립 정산 |
| [AiContextRevision.kt](../../backend/src/main/kotlin/dev/deskseed/aiassistance/internal/AiContextRevision.kt) | 댓글 해시에 전체 ticketVersion 포함 | 기능 입력 리비전과 ticket command 동시성 분리 |
| [AiResultAuthorizer.kt](../../backend/src/main/kotlin/dev/deskseed/aiassistance/internal/AiResultAuthorizer.kt) | 현재 owner·권한·정책·취소·context·KB·만료 및 required audit 검증 | 캐시에도 이 경로 유지. 리비전 버전 전환을 함께 적용 |
| [retrieval.py](../../ai/src/deskseed_ai/retrieval.py) | weighted hybrid 검색, 마지막 대화 4,000자, 최대 5개 후보. 청크 임베딩 후 해시 저장 | 현재 문제 기반 질의, 후보별 검색 후 RRF, 해시 기반 embedding 재사용 |
| [observability.py](../../ai/src/deskseed_ai/observability.py) | job span에 명시적 trace ID 없음. feedback은 `job_id.hex` 사용 | trace identity 통일, model usage/cost observation, exporter 장애 격리 |
| [api.ts](../../frontend/apps/staff-console/src/features/ai-assistance/api.ts), [AiAssistantPanel.tsx](../../frontend/apps/staff-console/src/features/ai-assistance/AiAssistantPanel.tsx) | 이력 조회와 수동 생성 분리. 생성마다 새 멱등키, inserted 기록. 최종 전송 귀속 연결 없음 | 자동 생성 방지 기능을 중복 구현하지 않고, 재사용·새 후보 의도와 전송 귀속 추가 |
| [evaluate_fake.py](../../ai/scripts/evaluate_fake.py), [uv.lock](../../ai/uv.lock) | fake 평가와 live 품질은 구별됨. lock은 LiteLLM 1.101.0, Langfuse 4.15.3 | 해당 버전의 응답 fixture와 설치 이미지 버전 대조. lock을 운영 버전 증거로 대체하지 않음 |

추가 주의: [기존 서버 구현 계획](IMPLEMENTATION_PLAN.md)의 `DEFERRED_UI`는 당시 서버 범위의 기록이다. 현재 UI 존재는 [후속 UI 작업 기록](../tasks/2026-09-17-ai-v1-frontend-assistant.md)과 소스로 확인했다. 과거 문구를 근거로 UI가 없다고 전제하지 않는다.

## 2. 공통 작업 명세와 유지할 계약

각 구현 작업은 [CODEX_TASK_TEMPLATE.md](../../CODEX_TASK_TEMPLATE.md)를 사용한다. 아래는 모든 작업의 공통 brief이며, S01~S14는 작업별 차이와 acceptance를 지정한다.

### 목표·사용자 시나리오

최종 목표는 활성 상담사가 `/agent/tickets/:ticketNumber`의 AGT-004 AI 패널에서 수동 요청하고 동일 입력에는 유효 결과를 이용하는 흐름이다. 1차는 이를 인증된 API test client로 검증하며 기존 UI의 수동 생성·결과 조회·삽입 동작을 유지한다. 공개 근거가 없으면 추가 생성 없이 기존 NEEDS_REVIEW로 끝낸다. 후속 UI 작업에서 재사용/새 후보를 구분하고, 사람이 PUBLIC 작성기에 삽입·수정·전송한 결과까지 비용을 연결한다.

### Decision·ADR·REQ·gate

- 유지: D-003, D-007, D-008, D-009, D-013, D-018, D-021, D-032, D-049, D-050, D-054, D-061, D-066.
- 근거: [ADR 0025](../adr/0025-postgresql-projections-before-external-stores.md), [ADR 0049](../adr/0049-ai-v1-isolated-execution-boundary.md). 파일명은 저장소 실제 경로를 따른다.
- 요구사항: REQ-AI-001~005. UI 변경은 REQ-UI-001/003/005/006도 연결한다.
- 공통 gate: AI-API-001, AI-SRC-001, AI-LIFE-001, AI-COST-001, AI-KB-001, AI-TRIAGE-001, AI-REPLY-001, AI-OBS-001, AI-OPS-001, AI-RET-001 중 변경에 해당하는 gate.
- **결정 변경이 필요한 부분:** ADR 0049의 현재 비순환 경로에 no-evidence 종료를 명시하고, 내용 기반 캐시·공유 실행·비용 소유권을 추가한다. 누적 요약을 답변 context에 쓰려면 현재 “reply는 summary/triage 결과를 재사용하지 않는다”는 조항을 먼저 변경해야 한다. 문체 수정은 별도 기능 계약이 필요하다. 이 문서에서 새 ADR을 Accepted로 간주하지 않는다.
- [요구사항 추적표](../26-requirement-traceability.md)는 구현·gate 증거가 생길 때 해당 slice가 갱신한다. 이 계획만으로 상태를 올리지 않는다.

### Actor·권한·감사·데이터

| 경계 | 유지할 의미 |
|---|---|
| 상담사 요청 | STAFF + 기존 staff session/CSRF/expected-actor guard. 개념상 AGENT_WORKSPACE이며 현재 Backend RequestSource는 AGENT_UI |
| AI source 호출 | 고정 INTEGRATION_CLIENT. 기존 JOB_SOURCE와 KNOWLEDGE_INDEX 역할 분리. requester는 등록된 job binding에서만 확인 |
| 자원 범위 | 같은 설치의 workspaceKey, 같은 requesterStaffId, 같은 ticket UUID, feature. workspaceKey는 서버 소유 설치 구분자이며 SaaS tenant를 추가하지 않음 |
| AI 입력 | PUBLIC 댓글과 승인된 공개 KB만 사용. 첫 문의는 첫 PUBLIC comment. INTERNAL·협업 메모·child 관계·고객 profile·protected audit·비공개 KB 제외 |
| source/result 접근 | 현재 staff 활성·ticket 권한·정책·취소·리비전·KB·만료 검증 후 required AccessAuditEvent 저장. audit 실패 시 본문·canInsert 반환 금지 |
| 생성/재사용 | ticket mutation 아님. 기존 AI_REQUEST_CREATED, AI_REQUEST_CANCELLED, AI_FEEDBACK_RECORDED 및 AI context/result access 의미 보존. polling은 TICKET_VIEWED 아님 |
| 실제 공개 댓글 전송 | 기존 UpdateTicket 권한·동시성·멱등성·TicketAudit 유지. 전송 귀속은 보조 실행 메타데이터이며 customer 응답·webhook에 AI job 정보를 추가하지 않음 |
| 운영 설정 | ADMIN + expectedVersion + CSRF. typed 설정과 Admin/Security audit 원자성 유지 |
| 외부 I/O | ticket/source DB transaction 밖. Backend outbox와 AI dispatch outbox 별도 유지 |
| 실행 | lease/generation fencing, terminal commit 후 Redis ACK, 취소 tombstone, deadline 유지 |
| 보존 | 결과 암호화 7일, 실행·feedback 30일의 기존 기본 범위 유지. 캐시는 원 결과 만료를 연장하지 않음. 비용 원장·미정산 UNKNOWN·canonical audit은 별도 정책 |

## 3. 순서와 작업 단위

입력 결함을 먼저 고치되 **비용 기준선 없이 절감률을 발표하지 않는다.** S01의 결함 재현 fixture와 기존 입력 스냅샷을 남겨, 수정 전후 비교가 가능하게 한다. 비용 계측 S02~S03 완료 전에는 라우팅·캐시 절감 실험을 확대하지 않는다.

| 순서 | 작업 | 선행 조건 | 완료 산출물 | REQ / 주 gate |
|---|---|---|---|---|
| S01 | 최신 발화 보존·역할·옵션·업무 prompt | 기존 계약 확인 | 입력 계약 v2, prompt v2, 누락·옵션 회귀 | REQ-AI-002/003, AI-SRC-001/AI-REPLY-001/AI-TRIAGE-001 |
| S02a | usage·가격·call receipt | 없음 | 공급자 fixture, 배타적 usage, 가격 카탈로그 v2 | REQ-AI-003, AI-COST-001 |
| S02b | 예약·정산·실패 분리 | S02a | 호출 전/응답 수신/검증 실패/UNKNOWN 원장 | REQ-AI-003, AI-COST-001/AI-LIFE-001 |
| S03a | trace·feedback·평가 기준선 | S01, S02 | trace 연결, 승인된 현재 서버 원문 평가 세트, 비교 보고서 형식 | REQ-AI-005, AI-OBS-001/AI-RET-001 |
| S03b (후속 UI) | 초안 삽입·수정·최종 전송 귀속 | 1차 완료, S03a 및 Core command 계약 검토 | 중복 없는 sent 지표·누락률 | REQ-AI-001/005, AI-API-001/AI-OBS-001, UI-002~006 |
| S04 | 근거 없음 조기 종료·짧은 인용 | S01, S02 | 생성 0회 종료, S1→canonical citation 변환 | REQ-AI-003/004, AI-REPLY-001/AI-COST-001 |
| S05 | 기능별 aiInputRevision | S01 | v1/v2 호환, 메타데이터 변경에도 재사용 가능 | REQ-AI-002/003, AI-SRC-001/AI-API-001 |
| S06 | 완료 결과 정확 일치 캐시 | S02, S04, S05 | 동일 상담사·티켓 결과 재사용·무효화 | REQ-AI-002~004, AI-API-001/AI-KB-001/AI-RET-001 |
| S07 | 진행 중 동일 입력 합치기 | S06 | DB 단일 실행·대기 요청·취소/복구 | REQ-AI-002/003, AI-LIFE-001/AI-COST-001 |
| S08a | 재사용/새 후보 서버 계약·제한 | S06, S07 | API 의도·횟수 제한·legacy client 호환 | REQ-AI-001/005, AI-API-001/AI-OPS-001 |
| S08b (후속 UI) | 최근 결과/새 후보 UI | 1차 완료, S08a | API 의도별 버튼·상태·삽입 연결 | REQ-AI-001/005, UI-002~006 |
| S09a | 현재 문제 질의·후보 분리·RRF | S01~S04, 평가 gold | Recall/검색 실행계획 전후 근거 | REQ-AI-004, AI-KB-001 |
| S09b | 절 중심 청크·제목 임베딩 | S09a | 새 index generation·전환/복원 검증 | REQ-AI-004, AI-KB-001/AI-COST-001 |
| S10 | 누적 요약 + 최근 원문 | 1차 지표, ADR 변경 | 출처가 있는 암호화 memory·손익 검증 | REQ-AI-003/004, AI-SRC-001/AI-REPLY-001 |
| S11 | 저가 모델 라우팅·최대 1회 승격 | 1차 품질·비용 기준선 | 검증된 조건별 route, 두 호출 합계 비용 | REQ-AI-003/005, AI-COST-001/AI-REPLY-001 |
| S12 | PUBLIC 초안 문체 수정 | S11, 기능 계약 동결 | 사실·인용 보존 rewrite | REQ-AI-001/003, AI-SRC-001/AI-REPLY-001 |
| S13 | 공급자 prompt cache | 결과 재사용 측정 후 | transport 전달 증거·쓰기 포함 손익 | REQ-AI-003/005, AI-COST-001/AI-OBS-001 |
| S14 | embedding 재사용·묶음·오프라인 Batch | S02, S09b | 변경 입력만 과금, 별도 SYSTEM 예산 | REQ-AI-004/005, AI-KB-001/AI-COST-001/AI-OPS-001 |

S01~S09 중 **S03b·S08b를 제외한 서버·AI 작업**이 1차 납품 경계다. S09b의 전체 재색인 비용이 크면 S09a까지 납품하고 S09b를 S14와 묶는다. S04를 위해 RRF 전체 구현을 기다리지 않으며, 검색 개선이 캐시 키를 바꾸도록 retrieval/chunk/index 버전을 처음부터 둔다. 한 작업에서 이 표 전체를 구현하지 않는다. UI가 없으면 캐시 API 준비 완료와 실제 상담사 경로 적용 완료를 구별한다.

## 4. S01 — 최신 발화·역할·옵션·프롬프트

### 4.1 입력 계약

변경 파일: `StaffTicketReadApi.kt`, `StaffTicketQueryRepository.kt`, `AiSourceController.kt`, `JdbcAiRequestService.kt`, `ai-source-api-v1.yaml`, `schemas.py`, `queue.py`, `providers.py`. 새 prompt 파일은 `ai/prompts/summary-v2`, `triage-v2`, `reply-v2` 계열로 관리하고 feature별 버전과 내용 digest를 기록한다.

Backend가 PUBLIC comment에 `authorRole`, 안정적인 `sequence`, `createdAt`을 제공한다. 역할은 저장된 author type에 근거한 `CUSTOMER | STAFF | INTEGRATION_CLIENT | SYSTEM | UNKNOWN`의 제한된 값으로 제안한다. CUSTOMER/STAFF를 이름·본문으로 추정하지 않는다. 역할·순서의 정확한 enum은 source 계약에서 먼저 동결한다. provider에는 UUID 대신 요청 내부 `C1`, `C2` 참조를 보내고 서버에서 실제 comment ID와 매핑한다. 이름·이메일·author ID는 추가하지 않는다.

```json
{"commentRef":"C12","authorRole":"CUSTOMER","createdAt":"2026-09-19T01:00:00Z","body":"비밀번호 재설정 후에도 로그인되지 않습니다."}
```

### 4.2 선택 알고리즘

1. ordered PUBLIC projection을 받고 최신 CUSTOMER 발화 및 그 뒤의 후속 PUBLIC 발화를 보호 구간으로 지정한다. 최초 문의·최근 STAFF 답변·관련 실패 조치도 참조와 함께 선택한다.
2. 최신 질문을 먼저 배치하고 과거 댓글을 채운다. 최신 댓글 전체를 용량 초과 이유로 건너뛰지 않는다. 같은 첫/최신 댓글은 중복 삽입하지 않는다.
3. 로그·인용·반복 블록은 경계와 생략 사실을 보존하는 결정적 압축만 먼저 적용한다. 오류 코드, 부정 표현, 날짜·금액·조건, 고객의 요구 변경을 삭제 대상으로 삼지 않는다.
4. 짧은 대화는 원문을 그대로 사용한다. 긴 대화도 보호 구간이 들어가면 남은 예산에 최초 문의·최근 답변을 넣는다. provider에 실제 보낸 comment/range·압축 정책 버전을 provenance로 남긴다.
5. 보호 구간이 상한에 들어가지 않으면 원문 일부를 몰래 누락해 성공 처리하지 않는다. 초기 안전 경로는 `INPUT_TOO_LONG`과 생성 0회이며, UI는 긴 입력 때문에 생성할 수 없음을 안내한다. S10이 승인·검증된 뒤에만 출처 보존 압축으로 처리 범위를 넓힌다.
6. 고객 발화가 없는 staff-created PUBLIC 대화는 최신 PUBLIC 발화와 `UNKNOWN/non-customer` 상태를 사용한다. 고객 요청을 만들어 내지 않는다.

최대 토큰은 messages·system prompt·JSON schema·옵션·KB를 합친 완성 입력을 기준으로 한다. 바이트/글자 수는 HTTP 안전 상한으로만 사용한다. summary/triage의 긴 대화를 저절로 지원한다고 약속하지 않고, S10 전에는 명시적 상한 초과 상태를 유지한다.

### 4.3 옵션과 업무별 출력

- Backend에서 language/tone의 기본값과 허용값을 정규화하고 prompt에 구조화 값으로 전달한다. 옵션은 지침 문자열을 그대로 덧붙이는 통로가 아니다. 현재 `ko/calm`은 반드시 보존한다. 추가 언어·tone 허용 목록은 계약 표와 다국어 fixture로 검증한 범위만 노출한다.
- summary: 확정 사실/미확인 사항, 시도/실패 조치, 남은 문제를 구분한다.
- triage: taxonomy version과 허용 priority를 명시한다. 1차는 `suggestedTagIds=[]`를 명시한다. 태그 추천을 열려면 Backend가 현재 활성 태그 allowlist와 revision을 제공하는 별도 slice가 필요하다.
- reply: 최신 고객 질문에 답하고 KB에 없는 정책·금액·기한·계정 조치를 만들지 않는다. 확인 불가 정보는 확인 질문으로 처리한다. input은 지시가 아닌 데이터다.
- 문체 수정은 현재 feature가 아니므로 S12에서 별도 처리한다.

Acceptance: 20,000바이트 최신 댓글, 첫 문의가 긴 사례, 고객 요구 변경, 실패 조치, 최신 STAFF 안내, 같은 timestamp 댓글, INTERNAL sentinel을 포함한다. 최신 질문은 전송 input에 포함되거나 명시적으로 생성이 중단되어야 한다. `ko/calm`이 provider call까지 전달되고, 옵션 변경은 입력 digest/캐시를 바꿔야 한다. 실제 말투·언어 품질은 live 평가 전까지 Pending이다.

## 5. S02 — 실제 사용량·가격·예약·정산

### 5.1 사용량과 가격

변경 파일: `providers.py`, `pricing.py`, `queue.py`, `retrieval.py`, `indexing.py`, `repository.py`, AI 신규 migration, `pricing-v2.json`, `tests/test_contracts.py`, `tests/test_runtime_integration.py`.

provider 반환을 `ProviderResponseEnvelope(rawStructuredOutput, callReceipt)` 형태로 분리한다. receipt는 JSON 업무 검증보다 먼저 추출·저장한다. allowlist 필드만 보관하며 raw HTTP response나 본문을 원장에 저장하지 않는다.

| 필드 | 의미 |
|---|---|
| callId / operationKey | 실행·stage·attempt 단위 유일 identity |
| providerRequestId / actualModel / requestedAlias | 공급자 추적 및 실제 가격 선택. 실제 모델을 요청 alias로 덮어쓰지 않음 |
| inputUncached / inputCacheRead / inputCacheWrite | 서로 겹치지 않는 입력 과금 버킷 |
| outputBilled | 과금 대상 출력 총량. reasoning이 이미 포함됐으면 다시 더하지 않음 |
| usageSchemaVersion / usageStatus | 어댑터 해석 버전과 KNOWN/UNAVAILABLE/INCONSISTENT 상태 |
| pricingVersion / serviceTier / contextPriceBand | 해당 호출의 고정 가격 근거 |
| reserved / knownCost / settlementStatus | 예약·알려진 실제 비용·회계 상태를 별개로 보존 |

OpenAI 계열 inclusive input과 공급자별 exclusive input을 어댑터가 구분한다. top-level cache 필드와 nested `prompt_tokens_details`를 중복 합산하지 않는다. 지원 필드의 우선순위와 동시 존재 시 일치 검증을 LiteLLM 1.101.0 fixture로 고정한다. 누락·음수·합계 초과는 0으로 보정해 숨기지 않고 계측 이상으로 기록한다. [Langfuse usage 문서](https://langfuse.com/docs/observability/features/token-and-cost-tracking)의 서로 겹치지 않는 버킷 규칙을 적용한다.

2026-09-19에 재확인한 Standard·short-context 가격은 아래와 같다. 공급자 청구 요금제·지역·실제 모델은 호출 시 별도 확인한다. [OpenAI 가격표](https://developers.openai.com/api/docs/pricing)

| 모델 | 일반 입력 / 1M | 캐시 읽기 / 1M | 캐시 쓰기 / 1M | 출력 / 1M |
|---|---:|---:|---:|---:|
| GPT-5.6 Luna | $0.20 | $0.02 | $0.25 | $1.20 |
| GPT-5.6 Terra | $2.00 | $0.20 | $2.50 | $12.00 |

카탈로그 단위 `microusd_per_million_tokens`에서 Luna `cacheWrite=250000`을 넣는다. 과거 `pricing-v1`의 기록을 덮어쓰지 않고 새로운 가격 버전과 적용 시점을 저장한다. long-context·Batch·다른 tier는 이 표를 재사용하지 않으며 지원하지 않는 가격 조합은 호출 전에 거부한다.

```text
costMicroUsd = ceil((uncached × inputRate + cacheRead × readRate
                    + cacheWrite × writeRate + outputBilled × outputRate) / 1,000,000)
```

### 5.2 호출별 상태와 장애 처리

| 상황 | 비용 처리 | job/result 처리 |
|---|---|---|
| 입력·권한·정책 실패, 전송 전 취소 | 생성 예약 없음 또는 안전한 RELEASED | 해당 typed failure, 본문 없음 |
| embedding 응답 성공 후 검색/생성 실패 | embedding은 즉시 실제 비용 정산 | 생성 실패와 별개로 비용 보존 |
| 공급자 응답 수신·usage 확보, JSON/인용/refusal 검증 실패 | 알려진 실제 금액 SETTLED | usable body 없는 NEEDS_REVIEW, 원인 코드 분리 |
| 네트워크 단절·프로세스 종료로 요청 도달/응답 불명 | UNKNOWN, 예약 유지 | 자동 재호출 금지, 정합성 확인 대기 |
| 응답은 있으나 usage 누락/가격 해석 실패 | 응답 수신 사실 유지, billing 미정 상태 | 확인 가능한 범위에서 결과 상태 분리, 비용을 0으로 발표하지 않음 |
| 실제 비용이 예약액 초과 | **실제 비용을 저장**하고 overrun 기록, 추가 호출 차단 | 원장 사실을 예약액으로 잘라내지 않음. 정책상 사용 가능 여부 별도 결정 |
| 취소/권한 철회/lease 상실 후 응답 도착 | callId로 late settlement 1회 | 결과 commit은 fencing·권한 검증으로 거부 |
| 같은 정산 재전달 | 같은 callId+usage는 no-op, 다른 값은 reconciliation 충돌 | 새 공급자 호출 없음 |

예약 상태와 실제 전송 상태를 구분한다. `RESERVED -> DISPATCHING -> RESPONDED` 같은 call lifecycle을 DB에 둔다. 전송 의도를 기록한 뒤 실제 소켓 전송 전 죽은 경우에도 원자적으로 “미전송”을 증명할 수 없으므로 UNKNOWN으로 취급한다. lease 만료만으로 RELEASED로 바꾸지 않는다.

정산은 call identity에 귀속되고 결과 저장은 job lease에 귀속된다. stale worker의 알려진 과금 사실은 정산할 수 있어야 하지만, stale worker가 결과를 덮어쓸 수는 없어야 한다. 저장된 receipt 이후의 정산 실패는 reconciliation으로 복구한다. 응답을 받았어도 receipt 저장 전에 DB/프로세스가 실패해 증거가 남지 않았다면 UNKNOWN이며, 공급자 조회/청구 대조로만 해소한다. 외부 호출과 DB 기록의 원자성을 보장한다고 주장하거나 재호출로 복구하지 않는다. Langfuse에만 비용 근거를 맡기지 않는다.

현재 interactive 최초 포함 2회, indexing 3회와 `num_retries=0`을 유지한다. 알려진 무과금 실패만 정책상 재시도하며, 출력 검증 실패를 일반 네트워크 재시도로 바꾸지 않는다. S11의 승격도 전체 생성 호출 수 상한에 포함한다.

### 5.3 완성 입력으로 예산 예약

1. reply query를 구성·토큰 계측하고 query embedding만 예약 → 호출 → 즉시 정산한다.
2. 검색·공개 권한 재확인 후 근거가 없으면 S04로 끝낸다.
3. 생성 messages/schema/KB를 완성하고 실제 선택 모델 tokenizer로 입력을 추정한다. 출력 상한과 캐시 쓰기를 포함한 보수적 최댓값으로 생성 비용을 예약한다.
4. tokenizer가 해당 alias를 정확히 지원하지 않으면 검증된 보수적 상한 또는 호출 거부를 사용한다. 단순 글자 수/4를 live 예산 근거로 쓰지 않는다. 추정 오차 margin은 실제 receipt로 보정한다.
5. workspace → actor/SYSTEM → execution/job 순서의 lock과 합계 한도를 유지한다. 이전 날짜의 미정산 UNKNOWN은 당일 지출과 별도 부채로 계속 한도 계산에 반영한다. 현재 날짜만 조회하는 SQL로 미정산 부담이 자정에 사라지지 않도록 회귀 검증한다.

Acceptance: top-level/nested/중복 cache usage, cached 800/write 200, reasoning 포함 출력, usage 없음, 잘못된 JSON·인용, 검색 후 실패, 응답 후 cancel, 예약 초과, 중복/late 정산, 자정 경계, DB 실패, 20개 동시 예약을 검사한다. fixture별 micro-USD는 정수 계산으로 정확히 일치해야 한다. 실제 청구서와의 일치는 제한된 live canary 이후에만 주장한다.

## 6. S03 — 관측·전송 귀속·품질 기준선

### 6.1 trace와 feedback

`observability.py`와 `feedback.py`는 하나의 저장된 trace ID를 공유한다. 초기안은 job UUID의 32자리 hex를 job trace ID로 명시하고 call마다 별도 observation ID를 둔다. 공유 실행 S07은 execution trace에 실제 비용을 한 번만 기록하고 consumer job trace에는 cache/coalesced 참조만 둔다. 기존 upstream traceparent와의 관계는 link로 보존한다. [Langfuse 명시적 trace ID](https://langfuse.com/docs/observability/features/trace-ids-and-distributed-tracing)

기록 항목: fake/live, 환경, feature, 실제 모델, prompt/schema/policy/retrieval/index 버전, 일반·cache read/write·출력 토큰, call cost, 검색/생성/검증 시간, reuse 종류, 승격·실패 단계. high-cardinality ID는 제한된 trace metadata에만 두고 metric label로 사용하지 않는다. provider request ID는 원장에 저장하고 외부 export는 allowlist 정책을 따른다.

trace 시작/update/end/flush와 feedback 전송 실패 모두 job·정산을 실패시키지 않도록 어댑터에서 격리한다. exporter drop/retry 수를 노출한다. prompt·댓글·KB·답변 본문·고객 식별 정보를 Langfuse에 보내지 않는다. 자동 LLM integration을 켜면서 본문이 함께 전송되지 않는지 sentinel 검사한다.

### 6.2 실제 전송과 수정량 — 후속 UI 단계 S03b

이 절의 서버 command 연동과 UI 구현 모두 1차에서 제외한다. 1차는 기존 helpful/unhelpful/inserted/edited 계약을 유지하며, inserted를 sent로 간주하지 않는다.

변경 후보: `AiAssistantPanel.tsx`, `AgentTicketEditorWorkspace.tsx`, `features/ticket-workspace/model/useTicketEditor.ts`, 기존 ticket command API/Backend application service, AI feedback outbox. ticketing과 aiassistance는 공개 module API/event로 연결하며 internal cross-import를 하지 않는다.

- 현재 `inserted`는 유지하되 성공 지표로 대체하지 않는다. `displayed`, `inserted`, `edited`, `sent`를 구분하고 UI hint와 Backend-confirmed event를 구별한다.
- 작성기는 PUBLIC draft에 jobId/생성 candidate reference와 삽입 구간의 lineage를 유지한다. INTERNAL draft에는 AI provenance를 옮기지 않는다. 교체·전체 삭제·다른 티켓/상담사 전환·수동 붙여넣기는 확인 가능한 수준만 추적한다.
- 최종 전송은 기존 PUBLIC comment command의 성공 commit과 연결한다. optional AI attribution schema를 Core 계약에 먼저 추가하고 server가 requester/ticket/job binding을 검증한다. `commentId + candidateId` 유일성으로 재전송을 중복 집계하지 않는다.
- ticket/comment/TicketAudit와 최소 body-free 전송 귀속 intent는 같은 transaction에 기록하고, AI 통계 반영은 post-commit outbox로 전달한다. AI 서비스나 Langfuse가 내려가도 정상 댓글 전송은 가능해야 한다.
- 전송은 사람의 command다. AI 정책 중지·result 만료 이후의 수동 작성 댓글을 AI 결과 조회 실패 때문에 막지 않는다. 귀속을 검증할 수 없으면 unattributed로 분류한다. 권한·유효하지 않은 임의 job ID를 성공한 AI 사용으로 집계하지 않는다.
- 수정량은 애플리케이션 내부에서 정규화된 PUBLIC 초안과 실제 전송 텍스트를 비교한다. `editRatio=min(1, editDistance/max(originalLength, finalLength, 1))`, 삭제/추가 비율·길이를 남기되 본문이나 diff를 telemetry에 저장하지 않는다. 표기는 내용 정확성의 평가가 아닌 수정량이다.
- 여러 초안을 섞은 전송은 multi-source로 별도 분리한다. 유일 candidate의 최초 사용 횟수와 전체 sent comment 수를 함께 제공한다. 추적할 수 없는 복사·대규모 수정·만료 사례를 비용 0 또는 미사용으로 단정하지 않는다.

D-049의 exact command replay descriptor에는 AI 본문을 중복 저장하지 않는다. optional attribution이 business command의 재시도 identity와 충돌하지 않도록 canonical metadata 포함/별도 intent 처리 방식을 계약 테스트로 고정한다.

Acceptance: 삽입만 하고 취소=sent 0, 수정 후 전송=1, timeout 후 같은 command 재전송=1, ticket transaction rollback=0, outbox 재전달=1, INTERNAL 전송=AI public-use 0, telemetry 장애에도 댓글 전송 성공, 다른 actor/job 삽입 귀속 거부.

### 6.3 평가 데이터와 판정

기존 fake 130건은 계약·실패 회귀로 유지한다. live 품질용으로 **현재 서버의 실제 원문 사례 300건**을 시작 목표로 제안한다: 요약 60, 분류 40, 검색/답변 200. 이는 현재 그런 분포/수량의 데이터가 있다는 뜻은 아니다. 1차 구현 착수 시 데이터 inventory를 확인하고, 부족한 층과 전체 건수는 있는 그대로 보고한다.

데이터 준비 절차:

1. 현재 서버의 주소·환경·배포 commit·현재 AI/KB 설정·선별 기준을 확인한다. 과거 기록의 IP를 현재 서버로 단정하지 않는다. primary DB/원본 ticket·KB는 변경하지 않는다.
2. Backend가 허용한 PUBLIC source/API 또는 별도로 계약을 정한 통제된 평가 export로 snapshot을 만든다. AI runtime에 업무 DB credential을 주지 않는다. customer profile·INTERNAL·협업/child·protected audit·비공개 KB는 가져오지 않는다.
3. 승인된 PUBLIC 대화·공개 KB **원문을 그대로** 평가 입력으로 보존한다. 본문을 임의로 비식별하거나 문장 변형하여 대표 데이터를 대체하지 않는다. source revision·공개성·취소/철회는 실제 모델 전송 전 재확인한다.
4. 평가 원문은 접근 통제·암호화된 서버의 평가 저장소에 두고 Git, 개발 로그, Langfuse, PR artifact, 공개 문서에 복사하지 않는다. provider에는 해당 평가의 필요한 PUBLIC 입력만 전달한다. 평가용 snapshot TTL은 30일 이내를 제안하고, source 삭제/철회 시 파생 snapshot도 무효화·삭제한다. 통계·본문 없는 평가 결과는 별도로 보존한다.
5. 같은 ticket·유사 문제군은 tune 70% / holdout 30%를 넘나들지 않게 배정한다. 장기 대화를 잘라 만든 사례도 같은 family로 묶는다. 최신 질문/핵심 사실, gold evidence, 금지 주장, 기대 abstention을 원문과 분리된 label로 작성한다.
6. 긴 대화, 요구 변경, 실패 조치, 상충 KB, 근거 없음, 한국어 표현·오탈자·오류 코드의 분포를 보고한다. 실제 데이터에 없는 보안/극단 사례는 합성 회귀 fixture로 보완하되 실제 데이터 품질 점수와 분리한다.

버전·snapshot 시점·model·prompt·가격·retrieval 설정을 고정한다. holdout을 보고 튜닝한 경우 새 holdout을 만든다. 300건 미만이거나 특정 조건 표본이 없으면 그 조건의 모델 route를 확대하지 않는다. 사용자 승인은 현재 서버 자료 사용에 적용되며, 별도 고객 데이터 수집·새 관측 서비스에 원문 업로드로 확장하지 않는다.

| 지표 | 최초 승격 기준 제안 | 측정 방법 |
|---|---|---|
| 개인정보/INTERNAL/권한 위반 | 0건 | 자동 sentinel 및 사람 검토 |
| 최신 필수 발화 선택 | 포함 또는 명시적 상한 종료 100% | context fixture와 provenance |
| 인용 membership | 100% | server 검증 |
| 근거 없음 생성 생략 | generation call 0회 100% | call ledger, embedding 비용 별도 |
| 중대한 근거 없는 정책/금액/기한 주장 | 최종 holdout 0건 | 모델명을 가린 사람 평가 |
| 일반 답변 사용 가능성 | Terra 기준선보다 3%p 이상 악화하지 않음 | 동일 사례 paired 비교·사례군 단위 불확실성 보고 |
| 검색 gold Recall@10 | 전체/한국어/오류 코드별 기준선 이상 | fused 후보 top10, 최종 context와 별개 |
| 전송 귀속 누락 (후속) | 새 계측 cohort에서 5% 미만 | S03b 이후 sent와 attribution coverage; 1차는 NOT_ESTABLISHED |
| 지연 | p95가 기준선 대비 10% 넘게 악화하지 않음 | 같은 환경·부하, queue 포함/제외 둘 다 |

표본이 작아 비열등성을 판단할 수 없으면 “통과”로 단정하지 않고 해당 route를 보류하거나 표본을 늘린다. 사람이 채점한 표본으로 LLM judge를 보정하고, 일반 운영의 모든 답변에 유료 judge를 붙이지 않는다. 평가 비용은 별도 budget bucket으로 분리한다. [Langfuse 평가 지침](https://langfuse.com/docs/evaluation/evaluation-methods/llm-as-a-judge)

## 7. S04 — 생성 생략과 인용 압축

LangGraph를 `authorize → retrieve → validate sources → (generate 또는 no-evidence 종료) → validate output`으로 바꾼다. 새 무한 loop나 모델 자체 검토 노드는 만들지 않는다.

- 승인된 근거 0개: query embedding이 발생했다면 정산하고 generation 예약/호출은 0회. `NEEDS_REVIEW`, `NO_APPROVED_KNOWLEDGE`, `result=null`, `canInsert=false`로 종료한다.
- 1차는 현재 UI가 처리하는 NEEDS_REVIEW와 reason code를 유지한다. 후속 UI의 정형 안내는 “현재 공개 도움말에서 답변 근거를 찾지 못했습니다. 고객에게 추가 정보를 확인하거나 관련 도움말을 검토해 주세요.”로 제안한다. LLM answer로 저장하거나 PUBLIC 작성기에 넣지 않는다.
- 후보 공개 권한을 확인할 수 없는 장애는 no-evidence와 구분한다. backend unavailable/denied를 “검색 결과 없음”으로 숨기지 않는다. stale 후보는 제거 또는 bounded 재검색하되 무한 반복하지 않는다.
- 검색 후보가 존재한다는 이유만으로 관련성이 증명되지는 않는다. S09 평가로 검증하기 전에는 RRF 점수에 임의의 ‘신뢰도 95%’ 의미를 부여하지 않는다. 관련성 불충분 판정 규칙은 별도 reason과 평가 근거를 가진다.
- provider output은 내부적으로 `{answer, sourceRefs:["S1","S3"]}`만 생성한다. 요청별 승인 source map에서 외부 `articleId/revisionId/chunkId/title/url`로 확장한다. unknown ref·중복·초과 개수는 검증한다. prompt/schema 버전은 캐시 키에, source map의 순서/digest는 결과 검증 메타데이터에 포함한다.
- 외부 ReplyDraftResult citation 형식은 유지한다. URL은 서버의 canonical 상대 help URL이며 모델이 UUID·URL을 만들지 않는다. 인용 membership과 주장 뒷받침 평가는 계속 별개다.

Acceptance: 빈 index, 전부 철회된 KB, source 권한 서비스 장애, 승인되지 않은 S99, 생성 후 KB 철회, 내용과 무관한 인용을 나누어 검증한다. 현재 고비용 경로 대비 generation 호출 절약량은 ledger로 확인한다.

## 8. S05~S08 — 입력 리비전·정확 일치 캐시·동시 요청

### 8.1 리비전 역할

| 값 | 소유자와 의미 |
|---|---|
| ticketVersion | Backend의 ticket 변경·최종 command 동시성. 기존 field-aware 정책 유지 |
| aiInputRevision | feature가 읽은 PUBLIC comment ID/순서/역할/시간/본문 digest와 필요한 입력 필드의 canonical digest |
| inputPolicyVersion | 선택·정규화·압축·역할 매핑 정책 버전 |
| generationConfigDigest | language/tone, prompt/schema/model route/policy 버전 |
| corpus/index/retrieval version | 답변 근거의 공개성·검색 구성 변경을 감지하는 버전 |

summary/triage/reply가 현재 실제로 읽는 필드 목록을 각각 고정한다. group/assignee/INTERNAL 추가만으로 AI 입력이 같으면 재사용할 수 있지만, 현재 권한 검사는 항상 다시 한다. feature가 향후 status/priority를 읽게 되면 source 계약·revision 항목도 함께 추가한다. 고객 profile·staff-only 정보가 암묵적으로 섞이지 않게 한다.

`contextRevision`을 즉시 삭제/rename하지 않는다. 내부 v2에서는 aiInputRevision을 분리하고 외부 compatibility 필드는 contextPolicyVersion과 함께 처리한다. 기존 `public-comments-v1`의 const와 엄격한 Pydantic/프런트 decoder가 있어 단순 필드 추가도 혼합 버전에서 실패할 수 있다. §11의 배포 순서로 v1 job을 기존 의미로 처리하고 v2 job만 새 캐시에 넣는다. 과거 job 해시를 재해석하지 않는다. 1차에서 legacy UI의 mode 생략 요청은 외부 v1 receipt를 계속 받으며, v2 mode를 명시한 client만 v2 계약을 사용한다. UI를 수정하지 않은 채 v2 enum/const를 기존 decoder에 보내지 않는다.

### 8.2 캐시 키와 무효화

```text
HMAC(cache-key-version,
  serverWorkspaceKey + requesterStaffId + ticketId + feature
  + aiInputRevision + inputPolicyVersion + normalizedLanguageTone
  + promptDigest + outputSchemaVersion + modelRouteVersion + resolvedModelConfig
  + policyVersion + retrievalVersion + chunkingVersion
  + canonicalPublicCorpusRevision + publishedIndexGeneration
  + contextBuilderVersion)
```

키는 검색 전에 구성 가능한 값만으로 서버가 만든다. S04의 knowledgeSourceMapDigest와 최종 전송 prompt digest는 cache entry의 검증 메타데이터에 별도 저장한다. hit 확인을 위해 먼저 embedding/retrieval을 반복해야 하는 순환 의존을 만들지 않는다. 본문·PII를 Redis key, metric label, ordinary log에 넣지 않는다. caller가 hash/권한 범위를 선택할 수 없다. summary/triage는 KB 항목을 제외한다. reply는 canonical corpus revision이 바뀌면 보수적으로 miss시킨다. 한 선택 문서의 리비전만 검사해서 새로 공개된 더 적합한 문서의 존재를 놓치지 않는다.

Backend의 PUBLIC KB publish/unpublish/redact/상위 공개성 변경 transaction과 canonical corpus revision을 결합하고, AI index의 성공 publish generation은 별도로 관리한다. manifest 불완전·event 지연·corpus 상태 확인 불가에는 hit를 허용하지 않는다. 해당 전역 revision은 제안 계약이며 현재 존재한다고 가정하지 않는다. 초기 비용은 metadata 조회이고, 본문 검색·embedding은 hit에서 생략할 수 있다.

첫 단계는 같은 상담사·같은 ticket의 **완전 일치 SUCCEEDED 결과만** 캐시한다. 실패·UNKNOWN·NEEDS_REVIEW는 성공 캐시로 저장하지 않는다. TTL은 24시간 제안이며 `min(생성시각+24h, 원 결과 만료)`를 넘지 않는다. hit 때마다 TTL을 연장하지 않는다. 취소·권한/정책 철회·입력/KB 변경·만료는 재사용을 차단한다.

AI DB의 캐시 entry는 결과 job/execution 참조와 버전·만료·무효화 상태만 저장한다. 캐시 hit에서도 새 logical request의 job binding을 만든다. 원 결과를 복호화한 뒤 새 job의 AAD로 다시 암호화하며 generatedAt과 원 만료 상한·원 출처를 보존한다. 다른 job의 ciphertext를 AAD 변경 없이 복사하지 않는다. 원본 취소는 캐시 entry를 폐기하며, 이미 생성된 각 소비 job의 사용 여부는 해당 job의 현재 권한·취소 검증에 따른다.

### 8.3 동시 실행 병합

PostgreSQL을 authoritative coordinator로 사용한다. Redis는 기존 전달·깨우기 용도로만 사용하며 `SETNX` TTL만으로 유료 실행의 유일성을 보장하지 않는다.

제안 데이터 책임:

| 구성 | 책임 |
|---|---|
| logical job | 요청자·ticket·취소·deadline·result-read audit·UI polling. 기존 job마다 유지 |
| `ai_shared_executions` 신규 | 정확 일치 key, 상태, 대표 source job, lease/epoch, 생성 세대·attempt, 비용 소유 job |
| `ai_execution_consumers` 신규 | execution에 결합한 logical job, 취소·deadline·완료 상태 |
| `ai_result_cache` 신규 | 유효 completed execution/result 참조, 버전, 만료·무효화 |
| call ledger | execution/stage/attempt unique key. 공급자 호출과 비용은 실행당 한 번 |

1. Backend는 매 logical request의 owner binding·audit·outbox를 정상 생성한다.
2. AI에서 짧은 transaction으로 cache key를 claim한다. 유일 constraint로 한 실행만 winner가 되고 나머지는 durable consumer로 붙는다. worker thread를 장시간 막아 기다리지 않고 재스케줄한다.
3. provider 전송 전 살아 있는 대표 consumer job으로 source·정책·권한을 재검증한다. 대표 취소 시 동일 requester/동일 입력의 살아 있는 consumer로 대표만 교체한다. 실행 identity와 이미 발생한 call identity는 바꾸지 않는다.
4. consumer 하나의 취소는 그 job만 취소한다. 전원 취소면 다음 호출을 막는다. 이미 전송한 비용은 정산하며 취소 job에는 결과를 저장하지 않는다.
5. 완료 결과는 consumer별 현재 권한·입력·KB·정책·deadline을 확인하고 각 job AAD로 저장한다. 하나가 만료/철회돼도 다른 유효 job의 검증을 건너뛰지 않는다.
6. leader가 호출 후 죽으면 UNKNOWN을 유지한다. lock/lease 만료만 보고 follower가 모델을 다시 호출하지 않는다. call receipt가 있으면 비용 정산·결과 검증을 복구하고, 없으면 명시적 불명 실패로 끝낸다.

모든 consumer가 같은 requester이므로 비용은 해당 actor에 한 번 귀속된다. job/execution cap은 실행 전체의 embedding·generation·승격 합계에 적용한다. follower에 원 생성비를 복제해 과금하지 않는다. UI의 cost는 현재 요청 증분 비용과 참조 실행 비용을 구분하고, 운영 집계는 unique callId 기준으로 한다.

### 8.4 사용 의도 서버 계약 S08a와 후속 UI S08b

`createAgentAiJob`에 typed `generationMode=REUSE_OR_CREATE | NEW_CANDIDATE` 추가를 제안한다. 신규 선택 필드와 의미는 Core/OpenAPI/internal envelope에 먼저 반영한다. 현재 endpoint와 staff 인증 경로를 재사용한다. **기존 UI는 ‘새로 생성’ 버튼에서 mode 없이 요청한다. 따라서 mode 생략은 기존 신규 생성 의미를 유지하고, 1차의 재사용 경로는 mode를 명시한 API client로만 검증한다.** 후속 UI가 기본 동작에서 REUSE_OR_CREATE를 보내기 전까지 기존 버튼을 몰래 캐시 hit로 바꾸지 않는다.

- 후속 UI의 기본 “결과 확인/생성”: REUSE_OR_CREATE를 명시한다. 유효 완료 결과 → CACHE_HIT, 진행 중 동일 입력 → COALESCED, 없으면 GENERATED.
- “다른 초안 생성”: 명시적으로 NEW_CANDIDATE. 새 candidate sequence를 발급해 이전 완료 캐시를 우회한다. 같은 logical action의 네트워크 재시도는 같은 Idempotency-Key로 수렴한다. 사용자가 다시 새 후보를 선택한 경우에만 새 key를 만든다.
- 제한 제안: 같은 actor/ticket/feature/inputRevision에 24시간 동안 최초 생성 외 새 후보 최대 2회. 429+Retry-After와 기존 workspace/actor/execution 예산을 모두 적용한다. 한도 소비는 서버에서 원자적으로 결정하고, 전송이 없었던 확정 실패만 환원한다. UNKNOWN은 소비 상태 유지.
- 새 후보 요청도 권한·필수 audit·예산·KB·expiry를 우회하지 않는다. 같은 입력이라고 의도적인 다른 후보를 이전 진행 실행에 합치지 않는다.
- 삽입 전 `includeResult=true`와 `canInsert` 확인, PUBLIC 명시 선택, 추가/교체/취소, draft 변경 경합 검사는 유지한다. cache/coalesced 결과도 동일하다.
- loading/empty/error/denied/stale/conflict/근거 부족/예산·횟수 제한/공유 실행 대기/취소 상태를 text로 구별한다. 모델명·cache key 같은 구현 정보는 상담사 화면에 노출하지 않는다.

Acceptance: 동일 입력의 REUSE_OR_CREATE 20개 동시 요청 → 기본 정상 경로 generation 1회, query embedding도 1회. 각 job의 audit·취소·만료는 독립적이다. 다른 actor/ticket/언어/tone/모델/정책/KB 버전은 hit하지 않는다. INTERNAL 추가·assignee 변경으로만 ticketVersion이 바뀐 사례는 입력이 같고 현재 권한이 유효하면 hit하며, 최종 ticket command는 현재 동시성 정책을 그대로 적용한다. legacy mode 생략은 신규 생성 의미·현재 decoder를 유지한다.

## 9. S09 — 현재 PostgreSQL/pgvector 검색 개선

### 질의 구성

`workflows.py`에서 대화 마지막 4,000자를 쓰는 경로를 최신 CUSTOMER 발화 + 명시적인 제품/기능명 + 오류 코드 + 미해결 증상으로 바꾼다. S10 전에는 원문에서 결정적으로 추출 가능한 내용만 사용하며 주제·제품을 추정해서 추가하지 않는다. 긴 query는 embedding token cap으로 자르되 보호할 오류 코드·핵심 질문이 사라지면 검색/생성을 중단한다. 매 요청 query rewrite LLM은 추가하지 않는다.

keyword 질의는 allowlisted token과 parameterized SQL로 구성한다. 긴 문자열을 `plainto_tsquery`에 통째로 넣지 않고 제한된 개념 그룹과 오류 코드 exact match를 조합한다. AND/OR 정책을 검색 fixture로 고정한다. `simple`을 한국어 형태소 분석기로 간주하지 않는다. [PostgreSQL text search](https://www.postgresql.org/docs/current/textsearch-controls.html)

### 후보 결합과 근거 선택

```text
공개 index 범위의 vector distance ASC LIMIT 20
공개 index 범위의 keyword rank DESC LIMIT 20
→ RRF sum(1 / (60 + rank))
→ chunk 중복 및 같은 문서의 겹치는 구간 제거
→ fused top10을 평가용으로 기록
→ 조건·예외를 함께 담는 근거 3~5개를 token 예산 안에서 선택
→ Backend의 현재 PUBLIC revision/상위 공개성 승인
→ 생성 직전 및 결과 commit/GET에서 재검증
```

SQL의 공개 index 필터만으로 canonical authorization이 완료됐다고 보지 않는다. [pgvector 문서](https://github.com/pgvector/pgvector)가 안내하는 거리 정렬·LIMIT 형태와 RRF를 적용하되 인덱스 사용 여부는 실행계획으로 확인한다. post-filter가 ANN 후보를 줄이는 경우 bounded oversampling/iterative scan 지원 여부를 설치 버전에서 검증한다. 별도 reranker는 recall/품질 개선과 추가 비용을 비교한 후에만 도입한다.

### 청크·검증

문서 제목 + 절 제목 + 본문을 최종 embedding 입력으로 정규화한다. 문서 구조가 source 계약에 없으면 현재 plain text에서 확정 가능한 경계만 사용하거나 public section metadata 계약을 먼저 추가한다. 조건·예외·표의 머리말이 분리되지 않게 하고, 긴 절만 token 단위 subchunk로 나눈다. chunker/model/dimension/normalization 버전을 올려 새 index generation으로 재색인한다.

`EXPLAIN (ANALYZE, BUFFERS)`와 corpus 크기·분포·공개 필터 선택도·pgvector 버전을 `docs/performance/`에 보관한다. 1천/1만 청크의 합성 규모와 실제 목표 corpus에 맞는 추가 규모를 비교한다. cold/warm·p50/p95·rows/buffers·index 사용을 기록한다. gold Recall@10, 최종 context의 gold coverage, ANN-vs-exact recall을 별도로 측정한다. HNSW 존재나 HTTP 200은 성능/검색 품질 증거가 아니다.

## 10. 후속 비용 절감

### S10 — 누적 요약

ADR 변경 후 별도 PUBLIC-only context memory로 설계한다. 화면용 summary 결과를 무검증으로 reply에 넣지 않는다. `confirmedFacts`, `attemptsAndOutcomes`, `openQuestions`, `sourceCommentRefs`, `coveredThroughSequence`, source digest와 summary-policy/model 버전을 저장한다. cache와 동일하게 owner/ticket/권한·암호화·만료를 적용한다.

짧은 대화에는 요약 호출을 추가하지 않는다. 긴 대화에서 이전 memory 이후 delta만 처리하고 최신 고객 질문은 원문으로 남긴다. 원문 수정/삭제·공개 철회·모순·unknown source 참조는 memory를 무효화해 재구성 또는 검토 필요로 처리한다. 반복 요약에 따른 사실 변형을 평가하고 주기적인 원문 재검증 비용까지 계산한다.

진입 조건은 고정 길이만이 아니라 `예상 반복 사용에서 줄어드는 입력 비용 > 요약 생성·갱신·검증 비용`이다. 요약 목적의 선제 배치 생성은 하지 않는다. S01 상한 초과 사례를 자동으로 모두 해결한다고 주장하지 않는다.

### S11 — 난이도별 라우팅

Luna/Terra 별칭 유지. 규칙 기반 router가 검증된 사례군만 Luna에 보낸다. 처음에는 짧고 명확한 PUBLIC KB 하나로 해결되며 정책 충돌·복합 조건이 없는 안내만 후보로 둔다. 복합 문서·조건은 Terra, 근거 없음·계정 조치 확인 불가·정책 충돌은 상담사 검토다. 모델이 출력한 confidence는 라우팅 근거로 쓰지 않는다.

Luna 결과의 schema/인용 실패 또는 평가로 확인된 실패 조건에서만 Terra로 최대 1회 승격한다. Luna 비용은 보존하고 두 호출의 합계 cap을 적용한다. interactive 최초 포함 2회 제한 안에서 재시도와 승격을 함께 계산한다. UNKNOWN을 승격 명분으로 재호출하지 않는다. 각 실제 호출 직전 정책·취소·잔여 예산을 확인한다.

승격률 e의 평균 생성 비용은 `C_luna + e × C_terra`다. 동일 조건에서 `C_luna + e × C_terra < C_terra`이고 품질 gate가 통과해야 확대한다. 기존 Terra 전용 route를 유지한 채 allowlisted actor의 10% → 50% → 100%로 단계 확대하는 안을 제안한다. 각 단계는 최소 100개 완료 요청 또는 7일 관찰 중 더 늦은 시점에 평가하며, 표본이 부족하면 확대하지 않는다.

### S12 — 초안 문체 수정

새 feature로 language/tone/length를 다룬다. server가 승인한 PUBLIC 초안과 citation map만 사용하고 전체 ticket·KB 검색을 반복하지 않는다. 이름·정책·금액·날짜·조건·부정 표현·인용 sourceRef의 변경을 검증한다. 사실을 바꾸는 수정은 원 답변 기능으로 돌린다.

INTERNAL 작성기의 임의 텍스트를 새 AI 입력 통로로 만들지 않는다. 상담사가 수정한 텍스트를 허용할 경우 PUBLIC draft provenance와 입력 보안 계약부터 정의한다. feature enum, source/job envelope, budget call type, typed result, UI, REQ 세분화가 필요한 후속 slice다.

### S13 — prompt cache

안정적인 repository-owned 업무 지침만 explicit breakpoint 앞에 두고 ticket·query·KB·result·citation·memory·rewrite content는 모두 뒤에 배치한다. GPT-5.6은 visible prefix 1,024 token부터 cacheable하고 write 1.25x/read 0.1x이며 explicit `30m` TTL을 사용하므로, reviewed tokenizer 기준 미달이면 option 자체를 보내지 않는다. 현재 prompt의 static system prefix는 약 101~233 token으로 모두 미달이다. 캐시를 채우려고 padding이나 무의미한 예시를 늘리지 않는다. [OpenAI prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching)

`off | test | intent`를 결과 캐시와 별도 toggle로 두고, pinned LiteLLM 1.101.0 Chat Completions fixture가 `prompt_cache_breakpoint`, allowlisted `prompt_cache_options`, content-free bounded key를 실제 SDK body에 전달하는지 확인한다. 비교 지표는 hit rate와 함께 쓰기/read/일반 입력/출력/UNKNOWN 비용과 latency를 합친 실제 비용이다. 현재 구현은 transport capability와 bypass 증거를 제공하되, live canary와 충분히 길고 유용한 prefix가 없으면 production은 `off`다.

### S14 — embedding 재사용·배치

`hash(modelSnapshot + dimension + normalizationVersion + finalEmbeddingInput)`로 기존 벡터를 먼저 조회한다. hash는 제목·절 제목을 포함한 실제 전송 입력에 대해 계산한다. 기존 content_sha256가 같다는 이유만으로 model/입력 의미가 다른 벡터를 재사용하지 않는다. 사용 가능한 vector blob과 현재 article/revision binding을 분리하고 현재 공개 리비전 최종 검증을 저장 직전에도 유지한다.

없는 입력만 제한된 크기의 배열로 embedding한다. 개별 청크 실패·순서 매핑·과금·중복 수신을 검증하고 call별 총 usage와 chunk별 비용 배분을 구분한다. INDEX_EMBEDDING의 중간 실패에도 앞서 성공한 call 비용을 잃지 않는다.

공급자 Batch는 offline 평가·초기 KB 색인·선별된 과거 작업만 대상으로 한다. 일반 embedding 배열 요청과 Batch 할인은 별개다. [OpenAI Batch](https://developers.openai.com/api/docs/guides/batch)는 동기 대비 50% 할인 및 24시간 처리 창을 안내하므로 상담사가 기다리는 reply에는 쓰지 않는다. batchId/customId·부분 완료·cancel·늦은 결과·철회된 source·usage 정산을 durable하게 관리한다. SYSTEM/index/eval budget은 interactive actor budget과 구분하되 workspace 총한도에 포함한다.

S14a와 S14b를 별도 수직 slice로 구현한다. S14a의 재사용 key는 mutable model alias가 아니라 검토된 immutable `modelSnapshot`을 요구하며, provider가 snapshot을 제공하지 않으면 production `intent`는 fail closed다. 배열 response는 provider index가 요청 전체를 unique/complete하게 덮는지 검증하고 한 call의 total usage를 청크별 실제 비용처럼 중복 배분하지 않는다. [S14a task brief](../tasks/2026-09-19-ai-cost-s14a-embedding-reuse-batching.md)

S14b는 manifest, provider batch/file ID, custom ID, request count, partial/cancel/late result와 file delete intent를 별도 durable state로 관리한다. terminal 뒤 input/output/error file 삭제를 재시도하되 provider의 즉시 물리 삭제를 주장하지 않는다. production activation은 reviewed provider data-control 설정과 유료 예산 승인을 필요로 하며 fake/intercepted transport를 할인·품질·절감 증거로 사용하지 않는다. [S14b task brief](../tasks/2026-09-19-ai-cost-s14b-offline-embedding-batch.md)

## 11. 계약·마이그레이션·배포·롤백

### 계약 변경 목록

| 대상 | 변경 | 호환성 주의 |
|---|---|---|
| [AI source](../../api/ai-source-api-v1.yaml) | authorRole/sequence, aiInputRevision/inputPolicyVersion, corpus revision, 필요 시 tag allowlist | required field/const 변경과 strict reader에 대비한 v2 negotiation 또는 제한된 혼합 수용 기간 |
| [AI internal](../../api/ai-internal-api-v1.yaml) | normalized options, generationMode/candidate identity, reuse metadata, trace/call 정보의 필요한 부분 | envelope version과 DB check constraint 함께 갱신 |
| [Core AI fragment](../../api/core-api-fragments/80-ai-assistance.yaml) | create/get의 mode/reuse 의미, reason code, provenance·cost 의미, 필요한 feedback/사용 귀속 | owner·no-store·includeResult 기본값 유지. enum 추가를 무조건 비파괴 변경으로 보지 않음 |
| [Core bundle](../../api/core-api-outline-v1.yaml) | owned fragment로부터 결정적으로 재생성 | 사람이 operation/필드 설명·합성 example 소유. runtime parity 전 FROZEN 상향 금지 |
| 기존 ticket command | optional AI attribution 또는 연결된 application event 계약 | D-049 replay·transaction·clientCommandId 보존. 기존 client attribution 없이도 전송 가능 |
| 운영 정책 | reuse/새 후보 제한/route rollout의 typed 옵션 | `docs/52`에 type/default/range/effective time/audit 명시. arbitrary map 금지 |

Backend는 flyway 신규 migration, AI는 기존 체크섬 SQL을 수정하지 않는 신규 migration을 추가한다. 번호는 구현 직전 마지막 번호를 확인해 배정한다. 기능별 1~3개 migration 범위를 넘으면 slice를 나눈다. 기존 비용 rows는 상세 usage 미상으로 유지하고 금액을 재계산해 덮어쓰지 않는다.

배포 순서는 **reader 호환 확보 → additive schema → Backend/AI writer → UI → flag 활성화**다. 새 필드를 거부하는 구버전 Pydantic/decoder가 남아 있으면 새 writer를 켜지 않는다. v1 in-flight job은 drain하거나 v1 reader로 완료하며, v2 cache에 끼워 넣지 않는다. input revision 또는 cache policy가 바뀌면 새로운 namespace를 사용해 기존 entry를 자연스럽게 miss시킨다.

신규 cache/route/prompt-cache는 기본 off. 입력 누락 수정·정확한 비용 기록은 검증 후 기본 경로로 적용한다. rollback은 신규 최적화 flag off → 새 후보 접수/다음 호출 중지 → 진행 job drain/cancel → canonical source/current permission 경로 유지다. 비용 정산·UNKNOWN 조사·삭제/무효화 worker는 계속한다. 올바른 usage 계측을 과거의 누락 계측으로 되돌리지 않는다.

KB generation 전환은 완전한 새 index 준비 후 atomic publish한다. rollback은 아직 현재 PUBLIC 상태인 이전 generation에만 가능하다. 철회된 KB를 복원하지 않는다. DB는 forward-fix 또는 검증된 backup/restore를 사용하고 destructive downgrade를 기본 전략으로 삼지 않는다.

## 12. 측정 방법과 완료 판단

### 비용 지표

**1차 서버·AI 완료 지표**는 `전체 unique call 비용 / 논리 생성 요청 수`와 `전체 unique call 비용 / 품질 rubric을 통과한 unique draft 수`다. 성공 요청뿐 아니라 실패·버려진 출력·검색·재시도 비용을 분자에 포함한다. 합성/실제 데이터, API 재사용 실험/legacy UI 경로, 기능·대화 길이를 분리한다. 모델이 스스로 판정한 성공이나 schema 통과를 사람의 사용 가능성 평가로 대체하지 않는다.

**최종 제품 지표**는 S03b의 UI/전송 귀속 구현 이후에만 산출한다. 1차 완료 시 아래 값은 NOT_ESTABLISHED로 남긴다.

```text
실제 사용 초안당 API 비용
  = 동일 관찰 cohort의 모든 관련 unique call 비용
    / 최초 실제 PUBLIC 전송에 사용된 unique draft candidate 수
```

분자는 해당 cohort의 버려진/실패한 초안, query embedding, 요약 재사용 준비 비용, 재시도·승격을 포함한다. denominator에 들어간 성공 job 비용만 골라 계산하지 않는다. cache hit/coalesced consumer는 같은 원 생성비를 다시 더하지 않는다. 데이터가 덜 모인 최근 cohort와 7일 후 성숙 cohort를 구분한다. 분모 0은 N/A다.

UNKNOWN은 0이 아니다. `알려진 비용`, `미정산 예약`, `비용 불확실 요청 비율`을 함께 표시하며 완전한 실측 비용 절감을 선언하지 않는다. cache saved cost는 counterfactual 추정이고 실제 청구액과 별도 열에 둔다. index/eval API 비용은 별도 비용표와 총 API 비용에 포함한다. 인프라·운영 시간·세금까지 더한 전체 서비스 비용도 따로 비교한다.

보조 지표: feature/model별 토큰 p50/p95, 생성 요청당 call 수, cache hit/coalescing/no-evidence skip 비율, forced candidate 비율, 승격률, valid draft/inserted/sent 전환, attribution coverage, 수정량, 품질·검색 지표, p95 latency, 예약/정산 차이와 UNKNOWN age.

최소 첫 기준선은 현재 서버 원문으로 구성한 paired 평가와 7일간의 관측 가능 요청을 구분해 수집하며 시간·요청수·티켓 길이·feature 구성·가격 버전을 기록한다. 운영 트래픽이 없으면 오프라인 결과라고 명시한다. 전후 트래픽 구성이 다르면 길이/기능별 층화 비교를 병행한다. 1차의 실험 목표는 동일 품질 gate를 유지한 **유효 초안당 API 비용 20% 이상 감소**, 후속 UI 완료 이후의 최종 목표는 **실제 사용 초안당 API 비용 감소**로 제안한다. baseline이 없으므로 확정 절감 약속은 아니다. 기존 UI를 통한 cache/coalescing 절감률은 S08b 전까지 운영 실적으로 발표하지 않는다.

### 사용자 제공 계산 예시 검산

| 가정: 10,000 reply 요청 | 계산 | 생성비 |
|---|---|---:|
| Terra, 입력 6,000/출력 800 | 10,000 × (6,000×2 + 800×12) / 1M | $216.00 |
| 입력 4,000/출력 500 | 10,000 × (4,000×2 + 500×12) / 1M | $140.00 |
| 결과 재사용 25% | 7,500 × $0.014 | $105.00 |
| Luna 후 20% Terra 승격 | 7,500 × ($0.0014 + 0.2×$0.014) | $31.50 |

산술 감소율은 약 85.4%다. **Deskseed의 예상/측정 절감률이 아니다.** cache read/write·embedding·누적 요약·judge·indexing·인프라·세금과 품질 변화는 제외한 예시이며 출력 가정에는 과금 reasoning을 포함해야 한다.

## 13. 검증 명령과 릴리스 경계

아래는 구현 시 실행할 명령이다. 이번 문서 작업의 실행 결과와 혼동하지 않는다.

| 범위 | 명령/도구 | 필수 추가 증거 |
|---|---|---|
| docs/계약 | 저장소 root `make docs-check` | 신규 문서 link/ID, owned fragment와 bundle 일치 |
| Python | `ai/`에서 `uv sync --frozen`, `.venv/bin/ruff check src tests scripts`, `.venv/bin/mypy`, `.venv/bin/pytest -q` | mypy의 현재 files 범위 명시. 실제 PostgreSQL/pgvector·Redis 테스트 |
| fake 회귀 | `ai/`에서 `.venv/bin/python scripts/evaluate_fake.py` | 기존 130건의 계약 회귀. live 품질 주장 금지 |
| Backend | `backend/`에서 `GRADLE_USER_HOME=./.gradle-user-home ./gradlew --no-daemon test` | source/job/result 권한·감사 rollback, module boundary, empty/upgrade migration |
| Staff UI | `frontend/`에서 `npm run typecheck`, `npm run test:staff`, `npm run build:staff`, `npm run check:design-system-boundaries` | 최종 전송·재시도·PUBLIC/INTERNAL draft 독립·actor 교체 E2E |
| Storybook | staff-console root로 MCP 발견 → list-all-documentation → get-storybook-story-instructions → 대상 get-documentation | focused run-story-tests, get-changed-stories, preview-stories URL. 광범위 영향은 전체 story suite |
| 검색 성능 | 새/기존 SQL의 EXPLAIN (ANALYZE, BUFFERS) | 동일 corpus/부하의 recall·latency·plan 파일 |
| live 검증 | 구현할 별도 opt-in 평가 runner, 명시적 건수·USD cap·승인된 현재 서버 PUBLIC 원문 | 실제 model/usage/청구 대조, 사람 품질, Langfuse에는 본문 없는 receipt만 전송 |

이번 작업과 1차 서버·AI 구현은 rendered UI를 변경하지 않으므로 Storybook 실행 대상이 아니다. 표의 Staff UI/Storybook 검증은 S03b·S08b 등 후속 UI 단계에 적용한다. 기존 UI 호환성은 Core contract fixture·기존 payload의 decoder 수용 여부로 검증하고 frontend 파일은 수정하지 않는다. 후속 구현 시 project-local MCP가 없으면 계약을 추측하거나 package script로 대체해 통과했다고 보고하지 않는다. 운영 모델 평가는 허용할 모델·승인된 데이터 범위·최대 비용을 작업 brief에 명시한 후 실행한다.

릴리스 차단 조건: 공개/내부 정보 누출, 권한 철회 뒤 결과 사용, required audit 우회, 중복 유료 호출, 알려진 비용 유실/이중 집계, 중요 최신 요구 누락, 근거 없는 중대한 정책 약속. 발생 시 해당 최적화 route를 끄고 원장·무효화 처리는 유지한다. latency/비용 목표 미달은 최적화 확대를 보류할 근거이며 기본 상담 업무를 중단하는 이유로 삼지 않는다.

## 14. 구현자가 설명해야 할 선택

- 같은 requester/ticket에서 시작하면 cache 적중률은 제한되지만 권한·취소·출처 검증이 단순해진다. 다른 상담사나 고객 간 공유는 별도 결정이다.
- 최신 질문을 누락한 성공보다 명확한 상한 종료를 선택한다. 길이 지원 확대는 출처 보존과 요약 손익을 확인한 뒤 한다.
- cache hit도 현재 권한·KB·필수 audit 검증 비용을 지불한다. model API 비용 절감이 authorization 생략을 정당화하지 않는다.
- shared execution은 단순 결과 캐시보다 lifecycle이 복잡하므로 별도 S07로 검증한다. 측정된 동시 중복이 거의 없으면 S06을 먼저 출시하고 S07의 구현 시점을 늦출 수 있다.
- RRF·청크 개선은 recall과 query plan으로 판단한다. 무조건 top-k를 늘리거나 reranker를 넣지 않는다.
- 저가 모델 승격은 두 번의 비용을 낸다. 실제 전송·품질·총비용을 함께 보지 않으면 저렴한 호출이 비싼 서비스가 될 수 있다.

## 15. 이번 계획 작성의 완료 보고

- 작성: 현재 코드 근거, 실행 순서, 작업별 파일·계약·실패 의미·acceptance, 권한·감사·보존·예산·migration·rollback·평가 계획.
- 유지: D-066/ADR 0049의 격리 AI 실행과 Backend-owned PUBLIC source/권한 경계. 필요한 ADR 변경은 제안으로 표시했다.
- 사용자 확정 반영: 1차 서버·AI만, UI/전송 추적 후속 분리. 현재 서버의 실제 PUBLIC 대화·공개 KB 원문 사용 승인. 1차 비용 지표와 최종 사용 초안 비용 지표 분리.
- 미구현: S01~S14 전체, migration/OpenAPI/설정/UI 변경, 실제 평가 데이터 snapshot 구성.
- 미실행: 유료 provider 호출, Langfuse Cloud 전송, 실제 상담/KB 처리, DB query plan·부하 측정, 서버/UI 테스트, Storybook, 배포, commit/push/PR.
- 성능·절감 증거: 소스 정적 확인 및 계산 예시뿐이다. 절감률·품질·운영 사용량은 아직 미측정이다.
- 문서 검증 Passed: 신규 문서 local link 28개, 참조 REQ/Decision/gate, 코드 블록·공백 검사, 비용 예시 Decimal 검산. `git diff --check`, Core bundle check와 bundle 회귀 1건, API documentation quality 36건, documentation validator 회귀 2건 통과.
- 저장소 전체 `make docs-check`: 최종 validator에서 실패. 78개 모두 기존 `docs/frontend-audit-2026-09-11.md`의 링크와 해당 evidence 이미지 관련 오류이며 이번 계획의 추가 오류는 0개다. 기존 문서·이미지는 수정하지 않았다.
