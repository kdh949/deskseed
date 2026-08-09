# Admin, Audit, Integration wireframes

## Audit Explorer

```text
┌─ Filters ─────────────────────────────────────────────────────────────┐
│ Period [7d] Actor [김민수] Activity [Ticket viewed] Ticket [ ] Search │
├──────────────────────────────┬────────────────────────────────────────┤
│ 14:02 김민수 viewed #1042    │ DETAIL                                 │
│ 14:03 김민수 searched [PHONE]│ actor / IP / request / correlation     │
│ 14:04 김민수 changed assignee│ before A → after B                     │
│                              │ search session → opened tickets         │
│                              │ [Reveal protected data] [Export]        │
└──────────────────────────────┴────────────────────────────────────────┘
```

## Integration client

```text
SHOPPING_MALL_ADMIN       Active
Scopes: tickets:read, tickets:create, external-references:write
Constraints: group=Customer Support, system=SHOPPING_MALL
Last used: 2 minutes ago / 10.0.x.x
[Rotate key] [Revoke] [View activity]
```
