# Agent Ticket Workspace wireframe

```text
┌──52──┬────────────────────────────────────────────────────────────────────────┐
│ rail │ Ticket tabs: #1042 결제 오류 | #1045 환불                 search/profile│
├──────┼───────────────┬──────────────────────────────────┬─────────────────────┤
│      │ PROPERTIES    │ #1042 결제 오류                  │ CONTEXT             │
│      │ Requester     │ 고객 · 10:02                     │ [Customer][Child]   │
│      │ 김민수        │ 결제가 되지 않아요...             │ 김민수              │
│      │               │                                  │ previous requests   │
│      │ Status OPEN   │ 상담사 · INTERNAL · 10:05         │                     │
│      │ Priority NORM │ PG 로그 확인 필요                 │ child #1043 OPEN    │
│      │ Group 결제팀   │                                  │ order #A-102        │
│      │ Assignee A    │ 상담사 · PUBLIC · 10:08           │ [외부 전산 열기]     │
│      │               │ 확인 후 안내드리겠습니다.          │                     │
│      │ [Conflict!]   ├──────────────────────────────────┤                     │
│      │               │ [Public reply | Internal note]   │                     │
│      │               │ draft...                         │                     │
│      │               │ [Apply macro] [Submit as Open ▼] │                     │
└──────┴───────────────┴──────────────────────────────────┴─────────────────────┘
```

- properties/context are resizable.
- internal composer changes surface and label.
- warning/conflict stays visible but does not cover focused controls.
