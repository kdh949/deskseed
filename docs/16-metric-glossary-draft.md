# Metric Glossary Draft

Status: **Planned**. 이 문서는 구현 전에 통계 의미를 고정하기 위한 초안이다. Explore 같은 임의 리포트 빌더보다 먼저 curated metric을 정확히 만든다.

## Shared conventions

- 기본 report timezone은 관리자 설정값을 사용한다. 초기 후보는 `Asia/Seoul`이다.
- 생성/해결 같은 flow metric은 event 발생 시각으로 기간을 자른다.
- backlog 같은 stock metric은 snapshot 시각으로 기간을 자른다.
- 평균만 제공하지 않고 p50/p90/p95와 sample count를 함께 제공한다.
- `INTERNAL_CHILD`는 기본 customer-support dashboard에서 제외하고 내부 협업 dashboard에서 별도로 본다.
- 삭제/병합/테스트 티켓의 포함 규칙은 metric version에 명시한다.
- 내부 메모는 public reply metric을 만족시키지 않는다.

## Ticket flow

### Customer tickets created

```text
count(distinct ticket_id where TicketCreated.kind = CUSTOMER_REQUEST)
```

- Grain: ticket
- Time: ticket creation event
- Dimensions: channel, priority, initial group, requester type
- Excludes: internal child, test data

### Tickets solved

두 지표를 분리한다.

1. `solve_events`: `TicketSolved` event 수. 재오픈 후 다시 해결하면 다시 센다.
2. `tickets_first_solved`: 기간 중 처음 해결된 distinct ticket 수.

대시보드 카드에는 어느 지표인지 명시한다.

### Reopen rate

```text
reopened tickets / tickets that reached SOLVED
```

- 같은 분석 cohort와 관찰 기간을 사용한다.
- customer comment, agent action, automation 중 reopen source를 dimension으로 둔다.

## Backlog

### Open backlog at T

```text
count(customer tickets created_at <= T
      and not in SOLVED/CLOSED at T)
```

현재 row를 과거 시점에 적용하지 않는다. `TicketStatusInterval` 또는 정기 snapshot을 사용한다.

### Aging buckets

- `< 1h`
- `1h–4h`
- `4h–24h`
- `1d–3d`
- `3d–7d`
- `>= 7d`

Calendar age와 business-hours age를 섞지 않는다.

## Reply metrics

### First reply time

```text
first PUBLIC AGENT comment occurred_at
- first PUBLIC CUSTOMER comment occurred_at
```

- Grain: customer ticket
- Starts: 최초 고객 public comment
- Stops: 최초 상담사 public comment
- Internal/system-only comment는 stop이 아님
- Bot/automation public reply를 agent reply에 포함할지는 별도 dimension과 policy로 결정
- 미응답 ticket은 percentile에서 제외하지 말고 별도 `unreplied_count`로 표시

### Next reply time

고객의 새로운 public comment로 reply cycle이 열리고, 그 이후 첫 agent public reply로 닫힌다.

- Grain: reply cycle
- 연속된 고객 comment는 하나의 열린 cycle로 묶고 가장 오래된 미응답 고객 comment를 start로 사용하는 것을 기본값으로 한다.
- ticket solve가 열린 cycle을 취소하는지, breach로 남기는지 SLA policy에서 결정한다.

## Resolution metrics

### Total resolution time

```text
first solved_at - created_at
```

재오픈이 있는 경우 `first_resolution_time`과 `final_resolution_time`을 분리한다.

### Requester wait time

고객이 상담팀 응답을 기다리는 상태 interval의 합이다. 단순히 `PENDING`을 제외하는 식으로 계산하지 않고 status semantics와 열린 reply cycle을 함께 검증한다.

### Agent work time

상담팀이 처리할 수 있는 상태 interval의 합이다. 실제 노동시간을 의미한다고 과장하지 않으며, presence/activity tracking과 구분한다.

## Assignment and collaboration

### Transfer count

`GROUP_CHANGED` 또는 `ASSIGNEE_CHANGED` 중 ownership transfer command에서 발생한 audit 수다. 단순 field edit와 구분할 source/command type이 필요하다.

### Transfer rate

```text
customer tickets with >=1 transfer / customer tickets created
```

### Child collaboration cycle time

```text
child solved_at - child created_at
```

- Grain: child ticket
- Dimensions: parent group, child group, priority
- Parent customer SLA와 내부 OLA를 섞지 않는다.

## SLA

### SLA achieved rate

```text
ACHIEVED completed targets
/ (ACHIEVED + BREACHED completed targets)
```

- `ACTIVE`, `CANCELLED`는 분모에서 제외하고 별도 count로 표시한다.
- Ticket 비율과 target-instance 비율을 구분한다.
- policy ID/version, metric type, priority, group을 dimension으로 둔다.

### Tickets at risk

현재 `ACTIVE` target 중 `due_at`까지 남은 business seconds가 threshold 이하인 ticket 수다. scheduled evaluator가 아니라 query로 보여줄 수 있으나 business calendar 계산 비용을 측정한다.

## Automation and integration

- trigger evaluated count
- trigger matched rate
- actions per root command
- recursion/depth prevented count
- webhook delivery success rate
- webhook p50/p95 latency
- retry and dead-letter count
- manual replay success rate

자동화 성과는 처리량뿐 아니라 잘못된 변경, rollback/replay, 사람 개입 수를 함께 본다.

## Versioning rule

Metric definition이 바뀌면 기존 이름의 의미를 조용히 바꾸지 않는다. `metric_definition`에 ID/version, SQL/projection version, timezone, exclusions, releasedAt을 기록하고 dashboard/export에 version을 추적할 수 있게 한다.

## Access and security audit

These metrics are intended for security investigation and control monitoring. They must not be presented as employee productivity scores without a separate policy and validity review.

### Semantic ticket views

```text
count(TICKET_VIEWED)
```

- Grain: user interaction
- Background refresh/polling is excluded.
- Dimensions: actor, group/role snapshot, ticket, source, origin search.

### Unique tickets viewed by actor

```text
count(distinct ticket_id by actor within period)
```

This describes access breadth, not work quality or effort.

### Search-to-open rate

```text
SEARCH_RESULT_OPENED events linked to a search
/ SEARCH_EXECUTED events with result_count > 0
```

Also report search count, result-count distribution, and zero-result rate. Do not expose raw queries in general dashboards.

### Protected audit reveal count

Count of `AUDIT_SENSITIVE_CONTENT_REVEALED`, split by actor, reason category, and content type. A high count is a review signal, not proof of misuse.

### Access denied rate

```text
ACCESS_DENIED / protected access attempts
```

Separate expected permission boundary behavior from suspicious repeated attempts.

## Platform API

### API success/error rate

By IntegrationClient and operation ID:

- 2xx success
- 4xx validation/auth/scope/conflict/rate-limit
- 5xx infrastructure

Do not combine all 4xx into one reliability failure.

### Idempotency replay rate

```text
replayed successful responses / idempotent write requests
```

Also track key-reuse mismatch and in-progress contention separately.

### Webhook delivery success

```text
subscriptions with successful final delivery
/ deliveries created
```

Provide first-attempt success, eventual success, retry count, dead-letter, and p50/p95 delivery latency.

### External-reference coverage

```text
customer tickets with >=1 ExternalReference
/ eligible customer tickets
```

This measures integration adoption, not support quality.
