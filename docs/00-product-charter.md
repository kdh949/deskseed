# Product Charter

Status: Baseline v0.3
Generated: 2026-08-10

## 1. Product statement

고객이 웹에서 문의를 접수하면 티켓이 생성되고, 상담사가 공개 답변과 내부 메모로 상담하며, 다른 상담사·그룹으로 이관하거나 내부 자식 티켓으로 협업한 뒤 해결할 수 있는 설치형 고객지원 시스템을 만든다.

제품은 고객지원 화면에서 끝나지 않는다. 쇼핑몰·주문·결제·운영·어드민 전산이 티켓과 고객 맥락을 안전하게 주고받을 수 있는 **Integration Platform**과, 누가 어떤 데이터를 조회·검색·수정·내보냈는지 조사할 수 있는 **Security & Audit Center**를 같은 제품의 핵심 축으로 둔다.

## 2. Why this product

이 프로젝트의 목적은 동시에 두 가지다.

1. 실제로 한 조직이 설치해 사용할 수 있는 Zendesk 계열 고객지원 도구를 만든다.
2. Kotlin/Spring, DDD, REST/OpenAPI, PostgreSQL, 감사 추적, API 플랫폼, EDA/Kafka를 단계적으로 설명할 수 있는 백엔드 포트폴리오를 만든다.

완성도를 화면 수나 사용 기술 수로 판단하지 않는다. 복잡한 업무 규칙과 보안 요구를 명확한 모델, 계약, 테스트, 운영 근거로 바꿨는지를 본다.

## 3. Primary users and actors

### Customer

- 로그인 없이 이름과 이메일로 문의할 수 있다.
- 발급받은 안전한 접근 수단으로 자신의 문의와 공개 답변을 조회한다.
- 나중에 계정을 만들면 검증 절차를 거쳐 과거 문의를 연결할 수 있다.
- 내부 메모, 내부 자식 티켓, staff-only 필드, 감사 정보는 볼 수 없다.

### Agent

- 접근 가능한 티켓 큐와 검색 결과를 본다.
- 공개 답변과 내부 메모를 작성한다.
- 상태·우선순위·그룹·담당자를 변경한다.
- 기존 티켓을 이관하거나 부모 소유권을 유지한 채 자식 티켓으로 협업을 요청한다.
- 티켓별 변경 이력을 본다.

### Admin

- 직원, 역할, 그룹, 멤버십과 고객 접근 정책을 관리한다.
- API client, webhook, 외부 시스템 정의, 데이터 보존 정책을 관리한다.
- SLA, trigger, automation, 통계 설정을 관리한다.

### Security Auditor

- 티켓을 직접 하나씩 열지 않고도 전체 change audit을 검색한다.
- 누가 어떤 티켓·고객 프로필을 열어봤는지 확인한다.
- 상담사가 어떤 검색어·필터를 사용했고 어떤 결과를 열었는지 확인한다.
- export, 첨부 다운로드, 로그인, 권한 변경, API client 사용, webhook 변경을 조사한다.
- 감사 로그를 보는 자신의 행위 역시 감사 대상이 된다.
- 티켓을 수정할 권한은 기본적으로 없다.

### Integration Developer / External System

- 주문·회원·결제·운영 전산에서 티켓을 생성하거나 갱신한다.
- 티켓과 외부 도메인 객체를 안정적으로 연결한다.
- Deskseed 이벤트를 webhook 또는 incremental export로 수신한다.
- 최소 권한 scope와 resource constraint를 가진 machine identity로 인증한다.
- 재시도해도 중복 티켓·댓글·외부 링크가 생기지 않아야 한다.

### Automation / System actor

- Trigger, scheduled automation, webhook replay, migration, retention job도 익명 시스템 행위가 아니다.
- 모든 자동 변경은 source, definition version, execution ID, correlation, causation을 가진다.

## 4. Product principles

1. **Customer data separation**: 공개 정보와 내부 정보는 모델·API·권한에서 분리한다.
2. **Ownership is explicit**: 이관과 협업을 혼동하지 않는다.
3. **History is trustworthy**: 업무 변경은 구조화된 append-only change audit으로 남긴다.
4. **Every sensitive read is accountable**: 민감한 티켓 상세·검색·export·첨부 다운로드는 access event를 남긴다.
5. **Audit the auditors**: 감사 로그 조회, 원문 공개, export도 다시 감사한다.
6. **API-first integration**: 외부 연동은 DB 직접 접근이나 화면 자동화가 아니라 버전된 API와 event contract를 사용한다.
7. **Least privilege by default**: 외부 client와 감사 담당자는 목적별 scope와 resource restriction을 가진다.
8. **Retry is normal**: 외부 API와 webhook은 재시도와 중복 전달을 전제로 idempotency를 설계한다.
9. **No network call in ticket transaction**: 외부 연동 실패가 티켓 데이터 정합성을 깨뜨리지 않게 한다.
10. **Link before mirror**: 외부 주문·결제·회원 객체는 먼저 참조로 연결하고, 필요성이 확인된 필드만 projection으로 복제한다.
11. **Simple before distributed**: 모듈러 모놀리스와 PostgreSQL로 시작한다.
12. **Automation is observable**: 자동 변경도 조건·행위자·결과·실패를 조사할 수 있어야 한다.
13. **Analytics starts at write-time semantics**: 현재 row만으로 과거를 억지로 재구성하지 않는다.
14. **Privacy-aware logging**: 감사 필요성과 개인정보 최소화를 함께 설계한다.
15. **Generated SDKs follow the contract**: SDK가 API보다 먼저 진화하지 않는다.
16. **AI writes, owner decides**: AI 결과를 검증하고 트레이드오프를 설명할 수 있어야 한다.

## 5. Product boundaries

### Current deployment model

- 하나의 self-hosted 설치 인스턴스가 하나의 지원 조직을 나타낸다.
- 초기 데이터 모델에 `tenant_id`, `workspace_id`, `account_id`를 넣지 않는다.
- 여러 회사가 함께 쓰는 SaaS multi-tenancy는 별도 제품 결정이다.

### Core support domain

- Customer request
- Ticket conversation
- Assignment and transfer
- Parent/child collaboration
- Ticket change audit
- Staff access/search audit
- Admin and security audit
- Admin configuration

### Integration domain

- Integration client and credentials
- Scope and resource constraints
- External system and external object references
- Public Platform REST API
- Outbound webhook subscriptions and delivery attempts
- Snapshot and incremental exports
- Generated SDKs and examples
- Later: sandboxed ticket-sidebar apps and internal-admin embed SDK

## 6. Delivery definitions

### Core MVP

다음 업무 흐름이 브라우저에서 동작한다.

1. 익명 고객이 웹 폼으로 문의한다.
2. 문의 본문은 첫 `PUBLIC` comment가 된다.
3. 고객은 공개 대화를 조회한다.
4. 상담사가 로그인하고 티켓을 배정받는다.
5. 내부 메모는 고객에게 보이지 않는다.
6. 공개 답변은 고객에게 보인다.
7. 상담사가 상담사·그룹 간 이관한다.
8. 상담사가 부모 소유권을 유지하면서 내부 자식 티켓을 만든다.
9. 열린 자식이 있어도 경고 후 부모를 해결할 수 있다.
10. 관리자가 최소 계정·그룹·고객 접근 설정을 관리한다.

### Portfolio Release Gate

Core MVP에 다음 보안·감사 흐름이 추가되어야 포트폴리오 첫 릴리스로 본다.

11. 티켓 변경은 actor, source, before/after, command/correlation ID와 함께 전역 change audit에서 검색된다.
12. staff가 티켓 상세를 사용자 의도로 열면 `TICKET_VIEWED` access event가 생성된다.
13. staff가 검색하면 검색어 정책에 따라 query, filter, result count가 기록된다.
14. 검색 결과에서 티켓을 열면 view event가 원래 search event와 연결된다.
15. `SECURITY_AUDITOR`가 actor, ticket, action, field, 기간, source, outcome으로 감사 기록을 필터링한다.
16. 감사 로그 조회, 민감 원문 공개, CSV export 자체가 security audit에 남는다.

### Integration v1

1. 관리자가 최소 권한 integration client를 만든다.
2. 외부 운영 전산이 idempotent API로 티켓 또는 내부 메모를 생성한다.
3. 주문·회원·결제 객체와 티켓을 `ExternalReference`로 연결한다.
4. 티켓 생성·변경 이벤트가 서명된 webhook으로 전달된다.
5. 중복 webhook을 수신자가 안전하게 처리할 수 있는 event ID가 제공된다.
6. 실패 delivery를 조회하고 replay할 수 있다.
7. cursor 기반 incremental export로 변경 이벤트를 수집한다.
8. OpenAPI에서 TypeScript, Python, JVM/Kotlin SDK와 API examples를 생성한다.

## 7. Explicit trade-off

전체 기능을 MVP에 넣지 않는다. 다만 나중에 붙이면 actor attribution과 데이터 경계를 다시 뜯어고쳐야 하는 요소는 초기에 심는다.

MVP부터 유지할 기반:

- actor/source/session/client/correlation/command metadata
- change audit 구조
- staff detail/search access event seam
- stable public identifiers
- idempotency를 받을 수 있는 application command 구조
- customer/staff/auditor/integration projection 분리
- API와 UI가 같은 domain command를 호출하는 구조

MVP 이후 구현:

- OAuth client management and delegated user grants
- full public integration write APIs
- webhook delivery engine
- generated SDK release pipeline
- embedded app framework
- long-term tamper-evident external audit archive
