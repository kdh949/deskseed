# Test, Quality, and Release Strategy

## 1. 목표

테스트는 코드 줄 수가 아니라 다음 위험을 줄이기 위해 존재한다.

- internal data leakage.
- lost update.
- assignment/child ownership violation.
- missing/tampered audit.
- duplicate integration writes.
- webhook duplicate/replay failures.
- metric semantic drift.
- UI regression in dense workspace.

## 2. Test layers

### Pure domain tests

- state transitions.
- assignment invariant.
- transfer vs child.
- solve warning.
- trigger condition/action semantics.
- SLA calendar calculations.

### Module integration tests

- application command transaction.
- JPA mapping and constraints.
- canonical audit atomicity.
- permission queries.
- Spring Modulith module boundaries/events.
- outbound mail business/outbox atomicity, worker claim lease, retry/manual retry and delivery-event persistence.

### API contract tests

- OpenAPI request/response.
- RFC Problem Details.
- projection field allowlists.
- idempotency/ETag.
- pagination cursor.

### Browser E2E

- customer submit/view.
- agent login/views/ticket.
- public/internal composer.
- conflict banner.
- transfer/child.
- audit explorer.
- integration key/webhook UI.

### Security tests

- IDOR/BOLA.
- role/group bypass.
- CSRF/session.
- XSS in comment/subject/metadata.
- log injection.
- mail recipient/header injection and body/link/recipient log non-exposure.
- secret/token leak.
- SSRF in webhook/deep links.
- rate limit.

### Performance tests

- ticket queue.
- audit explorer.
- access audit write overhead.
- idempotency contention.
- webhook retry queue.
- SLA at-risk query.

## 3. Required fixtures

Small deterministic fixture:

```text
3 groups
6 agents + admin + auditor
10 customers
40 tickets
public/internal comments
parent with 3 children
external references
search and access audit events
```

Large performance fixture targets:

```text
100,000 customers
1,000,000 tickets
10,000,000 comments
20,000,000 audit events
```

규모는 로컬 자원에 따라 축소하되 분포를 기록한다.

## 4. Golden business scenarios

1. 익명 문의 → 고객 조회 → agent public reply.
2. 내부 note가 고객에게 보이지 않음.
3. group/assignee invariant.
4. status와 comment 원자 저장.
5. 서로 다른 field 동시 병합.
6. 같은 field 충돌과 UI 회복.
7. transfer ownership 이동.
8. child 생성 후 parent ownership 유지.
9. open child warning 후 parent solved.
10. global audit에서 before/after 확인.
11. search → result open 추적.
12. API retry에도 ticket 1개.
13. webhook duplicate receiver idempotency.
14. request/public reply outbox → Mailpit recipient/subject/link inspection; INTERNAL note has no delivery.

## 5. Frontend quality gates

- TypeScript strict.
- ESLint/format.
- no unhandled query error state.
- Storybook/isolated stories for all critical components.
- Playwright on Chromium; release 전 Firefox/WebKit smoke.
- axe WCAG checks.
- visual regression at 1280x800, 1440x900, 1920x1080.
- keyboard-only critical path.

## 6. Visual snapshots

필수 snapshots:

- Views loading/ready/empty.
- Ticket public/internal comments.
- Composer PUBLIC/INTERNAL.
- Conflict banner.
- Open child warning.
- Context panel collapsed/wide.
- Audit explorer drawer.
- Customer portal mobile/desktop.

snapshot 변경은 의도와 before/after를 PR에 첨부한다.

## 7. Accessibility acceptance

- 모든 interaction keyboard reachable.
- focus visible/not obscured.
- dialog focus trap/restore.
- icon buttons accessible name.
- status not color-only.
- table header associations.
- form errors linked by aria-describedby.
- screen reader smoke for ticket composer and conflict.

## 8. Database migration tests

CI에서:

1. empty DB → latest.
2. previous tagged release DB → latest.
3. app startup `validate`.
4. destructive DDL scan.
5. audit append-only permission check.

## 9. Release types

- `dev`: feature branch.
- `preview`: demo environment, synthetic data only.
- `rc`: upgrade/restore/security rehearsal.
- `stable`: signed tag and changelog.

## 10. Release gate

Stable 전에:

- requirement matrix status update.
- all minimum verification gates green.
- OpenAPI diff classified.
- database backup/restore rehearsal.
- dependency/license report.
- threat model delta.
- performance baseline comparison.
- no real customer data in demo.
- known issues documented.
