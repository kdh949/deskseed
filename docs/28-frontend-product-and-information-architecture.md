# 프론트엔드 제품 구조와 정보 아키텍처

## 1. 목표

Deskseed 프론트엔드는 일반 웹사이트가 아니라 상담원이 장시간 사용하는 고밀도 업무 도구다. Zendesk Agent Workspace의 정보 구조를 참고하되, Deskseed 고유 브랜드와 용어를 사용한다.

핵심 UX 원칙:

- 상담원이 티켓을 처리하는 동안 화면 전환을 최소화한다.
- 대화, 티켓 속성, 고객·외부 문맥을 동시에 확인한다.
- 읽기와 쓰기 권한이 UI에서 분명히 드러난다.
- 저장 결과와 충돌을 조용히 숨기지 않는다.
- 고객 화면과 상담사 화면을 별도 projection으로 유지한다.

## 2. 애플리케이션 표면

```text
/customer     고객 문의·조회·계정 포털
/agent        상담사 Workspace
/admin        관리자 설정
/audit        보안 감사 센터
/integrations 외부 연동 관리
/analytics    통계·SLA 대시보드
```

## 3. 역할별 홈

| 역할 | 기본 진입 | 주요 목적 |
|---|---|---|
| Customer | `/requests` | 요청 제출·상태·공개 대화 |
| Agent | `/agent/views/my-open` | 처리할 티켓 큐 |
| Admin | `/admin/people` | 계정·그룹·설정 |
| Security Auditor | `/audit/activity` | 변경·열람·검색 조사 |
| Integration Admin | `/integrations/clients` | API key·webhook·외부 시스템 |

## 4. Agent Workspace global shell

```text
┌─ Global rail ─┬─ Work navigation ───────────────────────────────┐
│ Home          │ Current view / ticket tabs / search / profile   │
│ Views         ├──────────────────────────────────────────────────┤
│ Customers     │ Page content                                     │
│ Analytics     │                                                  │
│ Audit*        │                                                  │
│ Admin*        │                                                  │
└───────────────┴──────────────────────────────────────────────────┘
```

- Global rail: 48~56px, 아이콘 + tooltip.
- Work navigation: Views일 때 카테고리·뷰 목록, ticket일 때 열린 티켓 탭.
- Top chrome: 글로벌 검색, 새 티켓, 알림, 사용자 메뉴.
- 권한 없는 메뉴는 숨기되 직접 URL 접근은 서버가 거부한다.

## 5. Agent route catalog

```text
/agent/home
/agent/views/:viewKey
/agent/tickets/new
/agent/tickets/:ticketNumber
/agent/customers/:customerId
/agent/search?q=...
```

### Views

- 좌측: personal/shared/recent 카테고리.
- 중앙: 티켓 table.
- 상단: view 이름, count, refresh, 임시 filter, column 설정.
- 행 클릭: 같은 workspace 안에서 ticket tab을 연다.

### Ticket

```text
┌─ Properties ─────┬─ Conversation ───────────────┬─ Context ──────┐
│ requester         │ subject / ticket number      │ Customer       │
│ status            │ chronological comments       │ History        │
│ priority          │ internal/public distinction  │ Child tickets  │
│ group             │ composer                     │ External refs  │
│ assignee          │ submit action                │ Apps / Audit   │
│ tags              │ warning/error banners        │                │
└───────────────────┴──────────────────────────────┴────────────────┘
```

- Properties panel: 기본 300px, 240~420px resize.
- Conversation: flexible, 최소 480px.
- Context panel: 기본 320px, 240~520px resize, 접기 가능.
- 패널 폭은 사용자별 local preference로 저장한다.
- 신규 comment는 아래쪽에 쌓이며 composer는 대화 하단에 고정한다.

## 6. Customer portal route catalog

```text
/requests/new
/requests/lookup
/requests/:ticketNumber?access=...
/account/sign-in             (later)
/account/requests            (later)
/account/requests/:number    (later)
```

익명 MVP:

- 문의 제출
- 접수 완료 번호와 조회 키 표시
- 조회 키로 public conversation 조회
- 내부 field, child, audit, assignee 상세 비노출

계정 버전:

- Open/Solved 필터
- 제목·번호 검색
- 추가 public comment
- follow-up/reopen 정책

## 7. Admin information architecture

```text
/admin
├── people
│   ├── staff
│   ├── customers
│   └── roles
├── groups
├── tickets
│   ├── fields       (later)
│   ├── statuses     (later)
│   └── views        (later)
├── access
│   ├── customer-mode
│   └── permissions
├── business-rules
│   ├── sla
│   ├── triggers
│   └── automations
├── integrations
└── system
    ├── branding
    ├── retention
    └── audit-policy
```

## 8. Audit Center IA

```text
/audit/activity
/audit/ticket-changes
/audit/access-search
/audit/admin-security
/audit/integrations
/audit/exports
```

- 상단 query builder/필터.
- 결과 table.
- 우측 detail drawer.
- 민감 원문 공개는 별도 action과 사유 입력.

## 9. Analytics IA

```text
/analytics/overview
/analytics/tickets
/analytics/sla
/analytics/groups
/analytics/automation
/analytics/integrations
/analytics/explore    (later custom query builder)
```

MVP 이후에도 지표 정의는 `16-metric-glossary-draft.md`를 source of truth로 사용한다.

## 10. 화면 크기 정책

- Agent Workspace: desktop-first, 지원 최소 폭 1180px.
- 1180px 미만: context panel을 drawer로 전환.
- 960px 미만: Agent Workspace는 제한 모드 안내; 고객 포털은 정상 responsive.
- 고객 포털: 360px부터 지원.
- 테이블은 중요 column을 고정하고 나머지는 horizontal scroll을 허용한다.

## 11. URL·탭·새로고침

- 모든 열린 티켓은 직접 URL로 복원 가능해야 한다.
- 탭 UI가 있더라도 browser history와 충돌하지 않아야 한다.
- 새로고침 후 unsaved draft는 local storage에서 복원하되 서버에 자동 전송하지 않는다.
- 권한이 사라진 ticket tab은 즉시 닫고 접근 거부 화면을 표시한다.

## 12. 이벤트·감사 연계

- 사용자가 명시적으로 티켓을 연 interaction에만 `TICKET_VIEWED`를 기록한다.
- background refetch는 동일 interaction ID를 재사용한다.
- 검색 실행과 결과 행 클릭은 search session ID로 연결한다.
- 외부 deep link 클릭은 audit 정책에 따라 `EXTERNAL_REFERENCE_OPENED`를 기록한다.
