# 공개 문의의 메일 렌더링 설정 사전 검증

## Goal

발송을 비활성화한 서버도 공개 문의와 답변의 보호된 메일 intent를 만들 수 있도록, 렌더링에 필수인 발신 주소와 공개 URL의 잘못된 설정을 시작 시 거부한다.

## Decision and source references

- D-038, D-046, Accepted ADR 0034, docs/49의 outbound foundation.
- REQ-NOTIF-001, REQ-CHAN-003; MAIL-002, CHN-006, CHN-008, DOC-001의 해당 범위.
- 기존 `createCustomerRequest`, `updateAgentTicket` 계약과 오류 응답은 변경하지 않는다.
- UX 감사 C-F01/AG-F01의 코드 진단 후보에 대한 방어다. 배포 SHA `b6be5ae34b54d442f933b660e88abb1d262a8dc0`의 production 컨테이너에서 두 환경변수 누락, profile의 빈 기본값, disabled에서 검증을 건너뛰는 코드를 읽기 전용으로 확인했다. 요청별 예외 stack은 남아 있지 않아 해당 request ID의 직접 예외 연결은 미확인이다.

## Actor and source

공개 문의는 CUSTOMER/CUSTOMER_PORTAL, 공개 답변은 STAFF/AGENT_UI의 기존 command context를 그대로 사용한다. 이번 시작 검증은 업무 actor를 생성하거나 staff 권한을 대행하지 않는다. 공개/내부 projection과 scope/resource constraint는 변경하지 않는다.

## Product and UX contract

UI 변경은 없다. 렌더러가 거부할 설정으로 프로세스가 정상 시작한 뒤 첫 사용자 요청에서 400을 반환하는 경로를 사전에 차단한다. SMTP 발송은 기존대로 명시적 opt-in이며 disabled 상태에는 SMTP host/credential을 요구하지 않는다.

## In scope

production Compose의 명시적 environment 목록에 sender/public URL을 필수 값으로 전달하고 env 예시에 표시한다. 기존 `OutboundMailSafety` 검증을 delivery-disabled 분기보다 먼저 실행한다. disabled 상태의 빈/잘못된 발신 주소·상대 URL을 Spring context 시작 테스트로 검증한다. 정상 렌더링 설정과 SMTP 미설정은 허용한다.

## Out of scope

실제 배포 설정·발송 활성화, 신규 readiness 서비스, 메일 provider, 광범위한 IllegalArgumentException 오류 재분류, UI 변경은 포함하지 않는다. 배포 환경변수 누락을 확인했지만 서버 설정 변경·배포·실제 문의 재검증은 수행하지 않는다. 운영 복구 완료를 주장하지 않는다.

## Invariants and failure semantics

- 첫 PUBLIC comment, TicketAudit, request grant와 protected outbound intent는 기존 업무 transaction에서 함께 commit/rollback한다.
- provider network I/O는 commit 뒤에만 실행한다. INTERNAL 메모는 고객 메일을 만들지 않는다.
- 초기 문의의 clientCommandId와 직원 command replay, version guard, audit failure 처리는 변경하지 않는다.
- 잘못된 설정은 시작을 실패시킨다. 오류에는 주소·URL 값·secret을 포함하지 않는다.

## Data and privacy

새 데이터, 로그, retained metadata, migration, export/webhook 변경이 없다. 기존 token-bearing 본문의 암호화와 보호 키 검증을 유지한다.

## Threats changed

임의 권한·SSRF·replay 경계를 추가하지 않는다. 기존 렌더링 안전성 검사와 시작 검사의 불일치를 줄인다. 이 검사는 외부 URL을 fetch하지 않는다.

## Acceptance scenarios

1. Given 발송 disabled와 정상 protected key, When 발신 주소가 비었거나 mailbox가 아니면, Then Spring context 시작이 실패한다.
2. Given 같은 조건, When 공개 URL이 비었거나 상대 경로면, Then Spring context 시작이 실패한다.
3. Given 정상 발신 주소·공개 URL·키, When 발송 disabled이고 SMTP credentials가 없으면, Then 시작할 수 있다.
4. Given enabled production SMTP, When 필수 SMTP/TLS 설정이 누락되면, Then 기존대로 시작이 실패한다.

## Validation

- `./gradlew fastTest --tests '*MailDeliveryConfigurationValidatorTest' --tests '*OutboundMailPolicyTest'`
- `./gradlew integrationTest --tests '*OutboundMailDeliveryIntegrationTest' --tests '*PublicRequestIntegrationTest' --tests '*AgentTicketCommandIntegrationTest'`
- `./gradlew integrationTest --tests '*PersonalStagingObservabilityRuntimeIntegrationTest'`
- `bash scripts/test-production-compose-contract.sh`, `bash scripts/test-personal-staging-deploy.sh`
- `make docs-check`, `git diff --check`.
- MAIL-001 실제 Mailpit 전달과 전체 UI/E2E는 이번 설정 검사 범위 밖이며 실행 결과와 구분한다.

## Compatibility and migration

HTTP/OpenAPI와 DB schema는 변경하지 않는다. 과거 잘못된 sender/base URL로 시작되던 disabled 서버는 수정 후 시작에 실패하므로 배포 전에 설정을 확인해야 한다. SMTP를 켤 필요는 없다. 롤백은 해당 코드 커밋을 되돌릴 수 있지만 잘못된 설정의 사용자 요청 실패를 다시 허용한다.

## Human explanation

새로운 상태 서비스 대신 기존 렌더러의 입력 검증을 시작 단계에서도 적용한다. 발송 off는 네트워크 전달만 끄며, 업무 transaction의 durable intent 생성까지 끄지 않는다는 점이 핵심이다.

## Completion report

- 발송 disabled의 렌더링 설정 검증과 production Compose 필수 전달을 구현했다. D-038/D-046/ADR 0034를 유지하고 API·actor/audit·transaction·재시도 의미는 변경하지 않았다.
- 수정 전 신규 회귀 1개 실패를 확인했고 수정 후 설정/렌더러 fast tests 9개, 공개 접수/직원 command/outbox integration tests 55개, production profile 기동 integration test 1개가 통과했다.
- production Compose 필수 전달·빈 설정 거부와 personal staging 배포 계약, 문서 검사, diff whitespace 검사가 통과했다.
- Java 21에서 Kotlin compiler 기본 heap 부족으로 최초 컴파일이 실패했다. 작업 명령에 `-Pkotlin.daemon.jvmargs=-Xmx2g`를 적용해 재실행했으며 저장소 JVM 설정은 변경하지 않았다.
- MAIL-001 실제 메일 전달, 전체 backend suite, 브라우저 E2E, 서버 설정 수정/배포, 성능 부하는 실행하지 않았다. 성능 개선을 주장하지 않는다. 서버 접속·배포 SHA·누락 설정은 확인했다. 감사 request ID의 직접 예외 stack과 실제 배포 복구는 미확인이다.
