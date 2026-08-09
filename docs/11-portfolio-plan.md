# Portfolio and Interview Plan

## Target signal

이 프로젝트는 “Zendesk 비슷한 CRUD를 AI로 만들었다”가 아니라 다음을 증명해야 한다.

- 모호한 고객지원 업무를 domain language와 invariant로 바꿨다.
- Kotlin/Spring에서 REST/OpenAPI와 transaction boundary를 설계했다.
- PostgreSQL schema, indexes, query plans를 근거로 개선했다.
- AI 결과를 검증하고 architecture decisions를 남겼다.
- 간단한 모놀리스에서 event-driven/Kafka/CQRS로 이동하는 이유를 설명한다.

첨부한 지원 회사 JD가 강조한 AI-first 개발, DDD, domain events, REST/OpenAPI, query execution plan, Kafka/CQRS/Redis, 관측 가능성과 직접 연결된다.

## Portfolio releases

### Release A — Working product

- anonymous request/customer view
- staff workspace
- public/internal comments
- assignment/transfer
- child tickets
- audits/admin settings
- Docker demo

Interview story:

> 이관과 부서 협업이 같은 것처럼 보였지만 ownership이 이동하는 Transfer와 ownership을 유지하는 Child Ticket으로 분리했습니다.

### Release B — Correctness under concurrency

- optimistic version
- field-level conflict
- red conflict UX
- idempotent commands
- failure/retry tests

Interview story:

> 무조건 last-write-wins는 조용한 데이터 손실을 만들기 때문에 독립 필드는 병합하고 같은 필드는 사용자에게 충돌을 알렸습니다.

### Release C — PostgreSQL performance

- deterministic data generator in Python
- 100k customers, 1m tickets, 10m comments target fixture
- slow queue/search query reproduction
- `EXPLAIN (ANALYZE, BUFFERS)` before/after
- index/selectivity/pagination analysis

Artifact format:

```text
Problem → dataset → query → plan → hypothesis → change → p50/p95 → trade-off
```

### Release D — SLA and analytics

- interval/fact model
- first reply and next reply timers
- SLA dashboard
- backlog snapshots
- metric definition glossary

Interview story:

> 현재 ticket row만으로 과거 backlog나 SLA를 정확히 계산할 수 없어서 update/status interval facts를 분리했습니다.

### Release E — Automation and integrations

- ordered trigger engine
- outbox/publication registry
- signed/retried webhooks
- n8n workflow example
- failure replay dashboard

Interview story:

> 자동화는 단순 if 문이 아니라 순서, 재귀, 중복, 실패 복구, provenance가 있는 도메인으로 설계했습니다.

### Release F — Kafka/CQRS

- measured need document
- versioned integration event
- Kafka externalization
- idempotent search/analytics consumer
- consistency repair/replay

Interview story:

> Kafka를 포트폴리오 장식으로 먼저 넣지 않고, 독립 소비자와 실패 격리가 필요해진 시점에 로컬 event contract를 외부화했습니다.

## Evidence to keep in the repository

- PRD and acceptance criteria
- ADRs including rejected alternatives
- module diagram generated from code
- OpenAPI diff
- schema migrations
- tests and coverage of invariants
- performance fixtures and plans
- incident/troubleshooting notes
- AI usage and human decision logs
- demo script and screenshots

## Interview self-check

각 주요 기능에 대해 3분, 10분, 30분 설명 버전을 준비한다.

- problem and user
- model and invariant
- transaction and data
- concurrency and failure
- security and authorization
- tests
- measured performance
- next evolution and why not yet
