# 프론트엔드 제품 구조와 정보 아키텍처

## 1. 목표

Deskseed 프론트엔드는 일반 웹사이트가 아니라 상담원이 장시간 사용하는 고밀도 업무 도구다. Zendesk Agent Workspace의 정보 구조를 참고하되, Deskseed 고유 브랜드와 용어를 사용한다.

핵심 UX 원칙:

- 상담원이 티켓을 처리하는 동안 화면 전환을 최소화한다.
- 대화, 티켓 속성, 고객·외부 문맥을 동시에 확인한다.
- 읽기와 쓰기 권한이 UI에서 분명히 드러난다.
- 저장 결과와 충돌을 조용히 숨기지 않는다.
- 고객 화면과 상담사 화면을 별도 projection으로 유지한다.

## 2. 현재 제공 표면

```text
/                       → /agent/views/my-open
/agent/login            최소 직원 로그인
/agent/views/:viewKey   Agent Queue
/agent/tickets/:number  읽기 전용 Ticket Workspace
그 외 경로               canonical not-found
```

Customer, Admin, Audit, Search, Integration, SLA 화면은 ADR 0039에 따라 `DEFERRED_UI`다. 서버 기능과 OpenAPI는 유지되며 재조합 계약은 `docs/55-frontend-capability-recomposition-matrix.md`가 소유한다. 이 문서 아래의 해당 정보 구조는 후속 재조합 시의 제품 의도이며 현재 라우트 제공을 뜻하지 않는다.

## 3. 역할별 홈

| 역할 | 기본 진입 | 주요 목적 |
|---|---|---|
| Agent | `/agent/views/my-open` | 처리할 티켓 큐 |
| Admin + `AGENT_WORKSPACE` | `/agent/views/my-open` | Agent Queue와 읽기 전용 Workspace |
| Security Auditor | denied | 이번 프론트 surface에서 접근 불가 |

## 4. Agent Workspace global shell

```text
┌─ Global rail ─┬─ Work navigation ───────────────────────────────┐
│ Home          │ Current view / ticket tabs / search / profile   │
│ Views         ├──────────────────────────────────────────────────┤
│ Queue         │ Page content                                     │
│ Tickets       │                                                  │
└───────────────┴──────────────────────────────────────────────────┘
```

- Global rail: 48~56px, 아이콘 + tooltip.
- Work navigation: Views일 때 카테고리·뷰 목록, ticket일 때 열린 티켓 탭.
- Top chrome: 열린 티켓 탭과 사용자 상태.
- 권한 없는 메뉴는 숨기되 직접 URL 접근은 서버가 거부한다.

## 5. Agent route catalog

```text
/agent/views/:viewKey
/agent/tickets/:ticketNumber
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
- 1500px 이하에서는 context panel을 헤더의 접근 가능한 토글로 연다.
- 신규 comment는 아래쪽에 쌓이며 composer는 대화 하단에 고정한다.

## 6. Deferred Customer portal intent

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

## 7. Deferred Admin information architecture

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

## 8. Deferred Audit Center IA

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

## 9. Deferred Analytics IA

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
- 1500px 이하: context panel을 overlay로 전환하고 헤더 아이콘 버튼으로 연다.
- 960px 미만: Agent Workspace는 제한 모드 안내; 고객 포털은 정상 responsive.
- 고객 포털 responsive 기준은 UI가 재조합될 때 다시 동결한다.
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
