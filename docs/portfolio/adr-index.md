# 설계 결정 목록

각 ADR은 당시의 문제, 선택한 방식, 대안과 운영 비용을 기록합니다. 아래에는 포트폴리오 릴리스의 구현 상태와 이후 추가한 결정을 정리했습니다.

| ADR | 선택한 방식 | 구현 상태 |
| --- | --- | --- |
| [0001](../adr/0001-kotlin-spring.md) | Kotlin·Spring Boot 백엔드 | 구현 완료 |
| [0002](../adr/0002-modular-monolith.md) | Spring Modulith 모듈러 모놀리스 | 구현 및 모듈 의존성 테스트 완료 |
| [0003](../adr/0003-first-comment-no-description.md) | 문의 본문을 첫 PUBLIC 댓글로 저장 | 구현 완료 |
| [0004](../adr/0004-transfer-vs-child-ticket.md) | 이관과 자식 티켓 생성 분리 | 구현 완료 |
| [0005](../adr/0005-audit-not-event-sourcing.md) | 현재 티켓 상태와 변경 감사 기록 분리 | 구현 완료 |
| [0006](../adr/0006-anonymous-first-customer-identity.md) | 익명 문의와 고객 계정 | 가입·세션 인증·익명 문의 연결 구현 |
| [0007](../adr/0007-field-aware-optimistic-concurrency.md) | 수정한 필드를 비교하는 동시성 처리 | 구현 완료 |
| [0008](../adr/0008-postgresql-first.md) | 업무 데이터는 PostgreSQL 우선 사용 | 고객 인증 요청 제한은 ADR 0043에 따라 Redis 사용 |
| [0009](../adr/0009-single-instance-no-tenancy.md) | 한 설치 인스턴스에서 한 조직 지원 | 설치 단위 제약 적용 |
| [0010](../adr/0010-local-events-before-kafka.md) | 프로세스 내부 도메인 이벤트 | 설계 규칙 적용, Kafka 미도입 |
| [0011](../adr/0011-generic-webhooks-before-connectors.md) | 개별 연동 도구보다 범용 서명 webhook 우선 | 구독·전달·재시도·재전송 구현 |
| [0012](../adr/0012-separate-platform-api-surface.md) | 외부 연동용 Platform API 분리 | 별도 controller와 인증 구현 |
| [0013](../adr/0013-separate-audit-ledgers-unified-explorer.md) | 감사 종류별 저장과 통합 조회 | 구현 완료 |
| [0014](../adr/0014-search-query-audit-privacy.md) | 검색어 마스킹·fingerprint·암호화 | 일반 조회의 검색어 표시는 ADR 0036으로 변경 |
| [0015](../adr/0015-external-reference-before-data-mirroring.md) | 외부 객체 복제보다 참조 연결 우선 | ExternalReference 구현, 외부 데이터 전체 복제는 제외 |
| [0016](../adr/0016-scoped-api-key-first-oauth-later.md) | scope가 있는 API key 인증 | 키 발급·client 관리·리소스 제한 구현 |
| [0017](../adr/0017-sandboxed-app-sdk-later.md) | 격리된 상담사 앱 SDK | 설계 단계 |
| [0018](../adr/0018-strict-audit-write-semantics.md) | 필수 감사 저장 실패 시 요청 실패 처리 | 구현 완료 |
| [0019](../adr/0019-garden-ui-with-independent-brand.md) | Garden 컴포넌트와 Deskseed 브랜딩 | 구현 완료 |
| [0020](../adr/0020-three-panel-agent-workspace.md) | 속성·대화·고객 맥락의 3패널 화면 | 구현 완료 |
| [0021](../adr/0021-frontend-state-separation.md) | 서버·URL·초안·레이아웃 상태 분리 | 구현 완료 |
| [0022](../adr/0022-ticketing-depth-after-core.md) | 핵심 티켓 처리 후 검색·사용자 설정 확장 | Views·태그·필드·매크로·검색 구현 |
| [0023](../adr/0023-sla-snapshots-and-intervals.md) | SLA 정책 버전과 시간 구간 기록 | 업무 일정·첫 답변 SLA 구현, 다음 답변·해결 SLA는 계획 단계 |
| [0024](../adr/0024-ordered-automation-without-arbitrary-code.md) | 실행 순서가 있는 조건·액션 자동화 | trigger와 시간 기반 자동화 구현 |
| [0025](../adr/0025-postgresql-projections-before-external-stores.md) | PostgreSQL의 조회용 데이터 우선 사용 | 현재 감사·검색 범위에 적용 |
| [0026](../adr/0026-object-storage-attachment-pipeline.md) | 비공개 object storage 첨부파일 | 업로드·검사·댓글 연결·권한 확인 다운로드 구현 |
| [0027](../adr/0027-email-as-channel-adapter.md) | 티켓·댓글에 연결하는 이메일 채널 | 발신 구현, 수신 이메일 티켓은 계획 단계 |
| [0028](../adr/0028-docker-compose-first-self-hosted.md) | Docker Compose 설치·배포 | 구현 완료 |
| [0029](../adr/0029-email-magic-link-customer-authentication.md) | 고객 이메일 매직 링크 | 비밀번호 없는 계정 로그인 구현, 비밀번호 계정은 ADR 0042 적용 |
| [0030](../adr/0030-all-agents-read-all-tickets-initially.md) | 활성 상담사의 티켓 읽기 범위 | 직원용 티켓 전체 읽기 구현 |
| [0031](../adr/0031-private-platform-api-v1.md) | 내부 네트워크용 Platform API v1 | 티켓 API 구현 |
| [0032](../adr/0032-configurable-business-schedule-first-reply-sla.md) | 첫 답변 SLA의 업무 일정 설정 | 버전별 업무 일정·첫 답변 SLA 구현 |
| [0033](../adr/0033-required-encrypted-raw-search-query-audit.md) | 검색 원문 암호화 저장 | 구현 완료 |
| [0034](../adr/0034-mailpit-development-outbound-mail-adapter.md) | 개발용 Mailpit 메일 adapter | 개발 SMTP·발송 처리 구현 |
| [0035](../adr/0035-staff-password-session-bootstrap.md) | 직원 세션 로그인과 첫 관리자 생성 | 구현 완료 |
| [0036](../adr/0036-content-free-routine-search-audit.md) | 일반 감사 조회에서 검색 내용 제외 | V13 마이그레이션·회귀 테스트 적용 |
| [0037](../adr/0037-stable-audit-origin-and-projection-snapshots.md) | 검색 출처 키 분리와 감사 시점 정보 보존 | V15 마이그레이션·회귀 테스트 적용 |
| [0038](../adr/0038-shared-organization-ticket-consistency-guard.md) | 조직 변경과 티켓 저장의 공통 락 | 동시성 회귀 테스트 적용 |

## 후속 설계 결정

| ADR | 선택한 방식 |
| --- | --- |
| [0039](../adr/0039-agent-workspace-surface-and-headless-capability-preservation.md) | 상담사 화면 정리와 Backend 계약 보존 |
| [0040](../adr/0040-additive-extension-foundation.md) | 기존 기능에 병렬로 확장하는 개발 기반 |
| [0041](../adr/0041-ticket-configuration-typed-eav-and-versioned-forms.md) | 타입별 사용자 필드와 폼 버전 관리 |
| [0042](../adr/0042-password-primary-customer-authentication.md) | 비밀번호 로그인과 기존 매직 링크 지원 |
| [0043](../adr/0043-benchmark-gated-customer-authentication-throttling.md) | Redis 기반 고객 인증 요청 제한 |
| [0044](../adr/0044-customer-portal-frontend-surface-isolation.md) | 고객 포털과 직원 콘솔 분리 |
| [0045](../adr/0045-structured-rich-text-comment-content.md) | 구조화된 리치 텍스트 댓글 |
| [0046](../adr/0046-ticket-collaboration-notes-and-mentions.md) | 협업 메모와 멘션 알림 |
| [0047](../adr/0047-private-load-observability-pipeline.md) | 비공개 부하 테스트 모니터링 |
| [0048](../adr/0048-personal-staging-private-observability.md) | 개인 스테이징 모니터링 |
| [0049](../adr/0049-ai-v1-isolated-execution-boundary.md) | AI V1 실행 환경 분리 |
| [0054](../adr/0054-latest-first-staff-search-order-index.md) | 최신순 상담사 검색 인덱스 |

요약한 결정 ID는 [구현 결정 기록](../25-implementation-decision-register.md)에 있습니다. 이후 결정이 달라지면 후속 ADR로 이유와 변경 범위를 남깁니다. 이전 ADR의 결정 이력은 보존합니다.
