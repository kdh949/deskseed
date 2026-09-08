# 트리거 운영 수직 슬라이스

ADMIN이 고객 재답변/티켓 변경, 태그·접수 폼·배정 조건을 구성하고 티켓 샘플로 판단 이유와 변경값을 확인한 뒤 버전을 활성화한다. 실행 결과와 미배정 그룹 알림을 상담 업무에서 확인한다.

REQ-AUT-001, REQ-COL-003; ADR 0024/0041, D-059/061/062; AUT-001~008, CHG-001, CONC-001, ACC-001, ARCH-002/003, UI-002/004, DOC-001.

- 기존 typed ALL/ANY, immutable version, ETag, activation history, ordered job/worker/outbox를 확장한다. 신규 범용 엔진/인프라/임의 코드/시간 DSL은 없다.
- TICKET_CREATED 유지. STAFF/Platform 실제 변경에서 TICKET_UPDATED, 고객 PUBLIC follow-up에서 CUSTOMER_REPLIED를 한 원자적 job으로 기록한다. EVENT=TICKET_UPDATED는 CUSTOMER_REPLIED도 포함한다. trigger/automation 자체 변경과 command replay/no-op는 새 root job을 만들지 않는다. 본문은 job에 복사하지 않는다.
- GROUP/ASSIGNEE는 UUID 비교 또는 presence, TAG/FORM은 UUID 비교. FORM은 최초 고객 접수 binding, 태그는 현재 값이다. 실행 시마다 최신 티켓을 잠그고 같은 evaluator로 dry-run 결과와 일치시킨다.
- SET_PRIORITY, SET_ASSIGNEE(명시적 null이면 배정 해제), 미배정 그룹 알림을 추가한다. 최종 group/assignee 조합의 활성 membership을 한 command 안에서 검증한다. 설정 순서와 무관하게 최종 상태를 한 TicketAudit의 ordered events로 남긴다. CLOSED는 변경하지 않는다.
- 미배정 알림은 현재 활성 그룹의 활성 구성원만 대상으로 하며 고객에게 노출하지 않는다. 기존 staff notification 목록/읽음/realtime 경로를 사용하고, 실행별 수신자 identity로 중복을 막는다. 시스템을 임의 staff actor로 표현하지 않는다.
- 버전·활성화·최근 실행 조회는 관리자 권한으로 body 없는 metadata만 반환한다. 조회는 제한된 페이지 크기로 제공하고 저장 입력과 dry-run 결과를 분리한다. 저장 실패는 초안을 보존하고 ambiguous create를 자동 재시도하지 않는다.
- 시간 자동화는 후속 슬라이스에서 기존 solved-age/close 관리와 dry-run/실행 이력을 연결한다. 임의 시간 조건이나 주기적 알림 체계는 추가하지 않는다.

검증: 업데이트/고객 재답변 transaction rollback·중복 없음, 트리거 자기 재실행 없음, 태그/폼/배정 조건의 실제 worker 및 dry-run 동등성, 최종 membership 실패 원자성, 활성화 후 retired target 거부, 관리자 경계·ETag·이력, notification 수신자 격리, MCP 정상/실패/충돌/키보드/모바일, API/docs/architecture.

## Verification and limits

- Passed: trigger definition 7, execution 7, migration 2; agent ticket commands 21, collaboration 4, Platform commands 12; API documentation 5 and architecture 1 PostgreSQL/JVM tests. CustomerRequestPortalIntegrationTest also passed after the root event change.
- Passed: staff unit 220, full staff Storybook MCP 133/133 with accessibility, typecheck, build, ESLint, Prettier, app boundaries, docs-check, git diff --check. Rendered 1280/390/320px conflict/recovery drawer has no horizontal overflow; 320px screenshot reviewed.
- V88 extends typed constraints and existing notification rows. Apply migration before the new application; old code cannot read the new notification type, so an application-only rollback requires disabling new rules and removing/routing their new notifications through an explicit recovery plan. No retention policy change or new infrastructure.
- Not run: live-backend browser E2E, production deployment and load/EXPLAIN measurement. Local tests are not production throughput evidence.
- Trade-off: captured rule versions evaluate latest locked ticket facts; machine-generated mutations do not recursively enqueue triggers. Unassigned notifications go to active ADMIN/AGENT members, not audit-only staff. Time policies remain a separate bounded solved-age close slice.
