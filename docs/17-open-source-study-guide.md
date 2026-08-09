# Open-source Study Guide

기준일: 2026-08-10. 목적은 코드를 번역해 붙이는 것이 아니라, 실제 제품이 어떤 문제를 어떤 데이터와 경계로 풀었는지 비교해 Deskseed의 결정을 검증하는 것이다. branch와 파일 구조는 바뀔 수 있으므로 연구 노트에 commit SHA를 함께 남긴다.

## Study method

한 기능을 구현하기 전에 다음 순서로 본다.

1. Zendesk 공식 문서에서 사용자에게 보이는 behavior와 용어를 확인한다.
2. Chatwoot/Zammad/FreeScout에서 같은 문제의 모델·쿼리·권한·비동기 처리를 찾는다.
3. 각 구현의 제약과 라이선스를 기록한다.
4. Deskseed의 Kotlin/Spring 모델로 다시 설계한다.
5. 선택과 거절 이유를 ADR 또는 PR에 남긴다.

복사한 코드가 있다면 출처, 원 라이선스, 수정 내용을 명시한다. AGPL 코드를 permissive 프로젝트에 무심코 섞지 않는다.

## Chatwoot

Repository: `chatwoot/chatwoot`

### Start here

- `app/models/conversation.rb`
- `app/models/message.rb`
- assignment/automation concerns included by Conversation
- API serializers and reporting events used by the agent UI

### Questions to study

- Conversation이 inbox/contact/assignee/team/messages를 어떻게 연결하는가?
- private message가 customer-visible chat query와 notification에서 어떻게 제외되는가?
- first reply와 waiting state를 어떤 timestamp/event로 계산하는가?
- automation이 message loop를 어떻게 제한하는가?
- operational report를 위해 current row와 reporting event를 어디까지 분리했는가?

### Deskseed comparison

Chatwoot은 channel/inbox 중심의 conversation 모델이 강하다. Deskseed는 초기에는 전통적인 ticket/group/assignee와 audit semantics를 우선한다. `private` boolean을 그대로 모방하기보다 `CommentVisibility`로 명시하고 customer projection에서 차단한다.

## Zammad

Repository: `zammad/zammad`

### Start here

- `app/models/ticket.rb`
- `app/models/ticket/article.rb`
- `HasHistory`, `HasLinks`, `HasTags` concerns
- Ticket API와 Article API 문서

### Questions to study

- Ticket과 Article이 어떻게 분리되어 있는가?
- history, links, tags, search indexing이 core ticket model에 어떤 결합을 만드는가?
- object/custom attribute 기능이 schema와 validation을 어떻게 확장하는가?
- email article과 내부 article의 차이를 어디에서 강제하는가?

### Deskseed comparison

Zammad는 성숙한 ticket domain의 breadth를 연구하기 좋다. Deskseed MVP는 custom fields, email, tags, arbitrary workflow를 넣지 않고 Ticket/Comment/Audit/Relation의 의미를 먼저 고정한다.

## FreeScout

Repository: `freescout-help-desk/freescout`

### Start here

- `app/Conversation.php`
- `app/Thread.php`
- mailbox/user permission policies
- email fetch/send jobs

### Questions to study

- Conversation과 Thread를 어떻게 분리하는가?
- customer message, user reply, note, line item을 어떤 type으로 표현하는가?
- mailbox 이동과 assignment 변경을 대화 timeline에 어떻게 보여주는가?
- shared mailbox 권한과 content sanitization을 어디에서 검사하는가?

### Deskseed comparison

FreeScout의 note/line-item 분리는 공개 답변·내부 메모·상태 변경을 한 timeline에 보여주는 UX 연구에 유용하다. 다만 audit은 comment/thread와 별도로 grouped update semantics를 유지한다.

## Security study, not only feature study

오픈소스의 과거/현재 보안 advisory도 반드시 읽는다. 특히 다음 유형을 Deskseed 회귀 테스트로 바꾼다.

- 다른 group/mailbox의 ticket 또는 comment에 접근하는 broken access control
- rich HTML/signature/attachment의 stored XSS
- webhook secret와 access token 로그 노출
- customer projection에서 internal content가 새는 IDOR
- automation/webhook 재시도로 중복 command가 실행되는 문제

연구 결과는 “해당 프로젝트가 나쁘다”가 아니라, helpdesk가 권한·가시성·HTML·email 때문에 공격 표면이 넓다는 증거로 사용한다.

## Feature-by-feature source map

| Deskseed feature | Zendesk behavior | Open-source code study |
|---|---|---|
| first comment | Ticket comments / Requests | Zammad Article, Chatwoot Message, FreeScout Thread |
| public/internal | public/private comments | Chatwoot private message, FreeScout note, Zammad article visibility |
| assignment | group + assignee | Chatwoot team/assignee, Zammad group/owner, FreeScout mailbox/user |
| audit timeline | Ticket Audits | Zammad history, FreeScout line item, Chatwoot activity/reporting event |
| child collaboration | side-conversation child ticket | ticket links/forward patterns; redesign rather than copy |
| SLA | policies and metric targets | Chatwoot first-reply/waiting/report models |
| triggers | ordered create/update rules | Chatwoot automation rules and loop prevention |
| webhooks | signed/retried delivery | Chatwoot webhook payloads; build a smaller versioned contract |
| search | ticket/comment search | Zammad/Chatwoot Elasticsearch integration after PostgreSQL baseline |

## Research record template

```text
Feature:
User behavior to reproduce:
Zendesk source:
Open-source repository + commit + files:
Observed model/query/event:
Useful idea:
Risk or coupling to avoid:
Deskseed decision:
Test that proves the decision:
License notes:
```

## v0.3 study tracks: integration and audit

### Integration Platform study

| Product/source | Inspect | Do not copy blindly |
|---|---|---|
| Zendesk REST APIs | surface separation, pagination, auth, rate limits | plan-specific endpoint breadth |
| Zendesk Apps Framework | ticket sidebar location, context/events, proxy secrets | exact proprietary manifest/API |
| Zendesk webhooks | delivery attempts, retry, monitoring | vendor-specific event catalog |
| Chatwoot Application/Platform/Client APIs | API audience separation | Rails-specific structure |
| Chatwoot signed webhooks | timestamp/raw-body signature pattern | exact headers as a compatibility claim |

Questions to answer in research notes:

- Which actor does an external call represent?
- How is least privilege represented?
- What is the idempotency contract?
- What data is stable public contract versus internal projection?
- How are credentials rotated/revoked?
- What failure/retry behavior is observable?

### Security/Audit study

| Source | Inspect | Deskseed concern |
|---|---|---|
| Zendesk Ticket Audits | ordered field/comment events | ticket business change ledger |
| Zendesk Access Logs | ticket/profile/search access | semantic access/search ledger |
| Zendesk account Audit Logs | settings and actor/IP/source | admin/security ledger |
| OWASP Logging guidance | sensitive data, integrity, log access | privacy and tamper controls |

Questions:

- Is this operational telemetry or canonical audit?
- Must it be atomic with a business change/read?
- What is the retention category?
- Who can view/reveal/export it?
- Does viewing it create another audit event?
- Can an application role modify/delete it?
