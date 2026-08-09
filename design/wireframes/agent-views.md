# Agent Views wireframe

```text
┌──52──┬────248────┬──────────────────────────────────────────────────────────┐
│ DS   │ Views      │ All unsolved tickets                 Search     + Ticket│
│      │            ├──────────────────────────────────────────────────────────┤
│ Home │ Your work  │ Filters: status:any group:any priority:any              │
│ View │  My open 12├──────┬────────┬──────────────┬─────────┬────────┬───────┤
│ User │  Pending 4 │Status│ #      │ Subject      │Requester│Group   │Updated│
│ Anal │ Shared     ├──────┼────────┼──────────────┼─────────┼────────┼───────┤
│ Aud* │  Unassigned│ Open │ 1042   │ 결제 오류     │김민수    │결제팀   │2m     │
│ Adm* │  All open  │ New  │ 1041   │ 환불 문의     │이수진    │미배정   │5m     │
│      │ Recent     │ ...                                                      │
└──────┴────────────┴──────────────────────────────────────────────────────────┘
```

Acceptance notes:

- left view tree collapsible/categorized.
- sticky table header.
- status includes text.
- row keyboard focus and open.
- count is eventually consistent and refreshable.
