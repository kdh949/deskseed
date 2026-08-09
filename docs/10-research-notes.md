# Research Notes and Source Map

이 문서는 2026-08-10 기준 공식 문서를 중심으로 어떤 제품 행동과 기술 선택을 참고했는지 기록한다. 코드를 복사하기 위한 목록이 아니라 의사결정의 출처다.

## Zendesk product behavior

### Customer request projection

Zendesk Requests API는 request를 end-user 관점의 ticket으로 설명하고, end user에게 public comments와 일부 필드만 노출한다. Deskseed도 `/requests`와 `/agent/tickets`를 분리한다.

- https://developer.zendesk.com/api-reference/ticketing/tickets/ticket-requests/
- https://developer.zendesk.com/api-reference/ticketing/tickets/tickets/

### Anonymous and registered access

Zendesk는 관리자가 익명 제출 허용, 가입 요구, Requests API 인증 요구 등을 설정할 수 있다. Deskseed는 이를 `CustomerAccessMode` 정책으로 단순화해 단계적으로 구현한다.

- https://support.zendesk.com/hc/en-us/articles/4408881989018-Enabling-anyone-to-submit-tickets
- https://support.zendesk.com/hc/en-us/articles/4408883052442-Managing-end-user-settings

### Audits

Zendesk audit은 한 ticket update를 나타내며 여러 event를 포함하는 read-only history다. Deskseed의 `TicketAudit`/`TicketAuditEvent` 모델이 이 행동을 참고한다.

- https://developer.zendesk.com/api-reference/ticketing/tickets/ticket_audits/

### Child tickets and side conversations

Zendesk side conversation은 support group 또는 그 group의 agent에게 child ticket을 만들 수 있다. Deskseed는 외부 email side conversation까지 복제하지 않고, 내부 child-ticket delegation을 핵심 모델로 채택한다.

- https://developer.zendesk.com/api-reference/ticketing/side_conversation/side_conversation/

### Triggers and webhooks

Zendesk trigger는 ticket create/update 시 조건을 검사하고 순서대로 action을 수행한다. Ticket activity 기반 webhook은 trigger/automation과 연결된다. Deskseed의 post-MVP automation blueprint가 이 실행 semantics를 참고한다.

- https://developer.zendesk.com/api-reference/ticketing/business-rules/triggers/
- https://developer.zendesk.com/api-reference/webhooks/webhooks-api/webhooks/
- https://developer.zendesk.com/api-reference/webhooks/event-types/webhook-event-types/

### SLA and Explore

Zendesk SLA는 conditions와 priority별 metric target을 사용한다. first reply/next reply 등은 public comment와 status event 의미에 의존한다. Explore는 Tickets, Updates history, Backlog history, SLAs 같은 서로 다른 dataset을 제공한다. 따라서 Deskseed도 단일 tickets 테이블의 현재 값만으로 reporting을 해결하려 하지 않는다.

- https://developer.zendesk.com/api-reference/ticketing/business-rules/sla_policies/
- https://support.zendesk.com/hc/en-us/articles/4408829459866-Defining-SLA-policies
- https://support.zendesk.com/hc/en-us/articles/4408827693594-Metrics-and-attributes-for-Zendesk-Support

### Incremental exports

Zendesk는 cursor 기반 incremental ticket export와 ticket event export를 제공한다. Deskseed는 일반 CSV snapshot과 별도로 변경 stream용 cursor contract를 계획한다.

- https://developer.zendesk.com/api-reference/ticketing/ticket-management/incremental_exports/

## Spring and Kotlin

### Kotlin support

Spring Boot는 Kotlin, kotlin-spring plugin, Jackson Kotlin module을 공식 지원한다. Deskseed는 Kotlin 2.4.10, Spring Boot 4.1.0, Java 21 toolchain을 seed baseline으로 둔다.

- https://docs.spring.io/spring-boot/reference/features/kotlin.html
- https://kotlinlang.org/docs/releases.html

### Modular monolith

Spring Modulith는 domain-driven modular Spring Boot application의 구조 검증, module integration test, event publication/externalization을 지원한다. Deskseed는 `ApplicationModules.verify()`를 CI architecture gate로 사용한다.

- https://docs.spring.io/spring-modulith/reference/index.html
- https://docs.spring.io/spring-modulith/reference/verification.html
- https://docs.spring.io/spring-modulith/reference/testing.html
- https://docs.spring.io/spring-modulith/reference/events.html

## n8n and Workato

n8n Webhook node와 Workato Webhooks connector 모두 외부 application이 target URL로 실시간 event를 보내 workflow/recipe를 시작하는 모델을 제공한다. 따라서 Deskseed는 각 제품 전용 구현보다 먼저 표준적인 signed outbound webhook을 만든다.

- https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.webhook/
- https://docs.workato.com/connectors/workato-webhooks.html

## Open-source implementation references

코드 구조 연구 대상으로 Chatwoot, Zammad, FreeScout를 볼 수 있다. 라이선스와 enterprise 디렉터리 범위를 확인하고 코드를 번역해 붙이지 않는다.

- https://github.com/chatwoot/chatwoot
- https://github.com/zammad/zammad
- https://github.com/freescout-help-desk/freescout

연구 원칙:

1. Zendesk: 제품 행동과 용어 참고
2. 오픈소스: trade-off와 실제 구현 탐색
3. Deskseed: Kotlin/Spring 도메인으로 재설계
4. 복사한 코드가 있다면 출처와 라이선스를 명시
