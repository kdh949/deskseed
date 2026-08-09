# AI-driven Development Playbook

## What the human owner should learn

AI에게 타이핑을 맡겨도 다음 질문에는 직접 답할 수 있어야 한다.

- 왜 description이 아니라 first comment인가?
- 왜 transfer와 child ticket이 다른가?
- transaction 경계는 어디인가?
- 어떤 invariant가 DB constraint이고 어떤 것이 domain policy인가?
- internal comment 누출을 어떤 계층에서 막는가?
- audit과 event sourcing이 왜 다른가?
- 왜 Kafka/Elasticsearch/WebFlux를 아직 쓰지 않았는가?
- concurrent update가 같은 필드와 다른 필드에서 어떻게 다른가?
- SLA timer는 어떤 event에서 시작·중지·일시정지되는가?
- 자동화 실패와 중복 실행을 어떻게 설명하고 복구하는가?

## Per-feature workflow

1. **Problem**: 사용자의 실제 업무 시나리오 한 문장
2. **Rules**: invariant, warning, blocker, permissions
3. **Contract**: command/result/error/OpenAPI
4. **Model**: aggregate, transaction, data changes
5. **Alternatives**: 최소 두 가지와 선택 이유
6. **Tests first**: 중요한 rule의 실패 예시
7. **Implementation**: AI draft 후 인간 review
8. **Verification**: unit/module/Postgres/API/UI
9. **Decision record**: ADR 또는 PRD update
10. **Learning note**: 면접에서 설명할 5문장

## Useful prompts

### Domain review

```text
이 PRD와 invariant를 시니어 Kotlin/Spring 백엔드 엔지니어처럼 공격적으로 리뷰해.
특히 aggregate boundary, transaction, 동시성, authorization, audit 누락을 찾아줘.
코드는 작성하지 말고 반례와 선택지를 먼저 제시해.
```

### Implementation request

```text
AGENTS.md, 현재 milestone, 관련 ADR을 읽고 이 acceptance criterion만 구현해.
다른 기능은 추가하지 마. 변경 파일, migration, tests, OpenAPI를 함께 제안하고
각 변경이 어떤 rule을 만족하는지 매핑해.
```

### AI output review

```text
이 diff에서 그럴듯하지만 잘못된 가정, 데이터 유실, 내부 코멘트 누출,
module internal import, transaction 중 외부 I/O, retry 비멱등성을 찾아줘.
```

### Performance investigation

```text
추측으로 index를 제안하지 마. query, cardinality, EXPLAIN ANALYZE BUFFERS,
현 인덱스를 바탕으로 병목 가설과 검증 순서를 작성해.
```

## AI change log

큰 PR에는 다음을 기록한다.

```text
AI tools used:
Prompts/roles:
Human decisions:
AI suggestions rejected:
Verification performed:
What I can explain without AI:
```

이 기록은 “AI가 대신 만들었다”가 아니라 AI 결과를 분석하고 개선한 능력을 보여주는 포트폴리오 자료다.

## Review gate

다음 중 하나라도 답하지 못하면 merge하지 않는다.

- 변경된 데이터는 무엇인가?
- 실패 시 transaction은 어떻게 되는가?
- 재시도하면 중복되는가?
- 권한이 없는 사용자가 호출하면 무엇이 노출되는가?
- 동시에 실행되면 무엇이 덮어써지는가?
- 어떤 테스트가 이 규칙을 고정하는가?
- 나중에 Kafka/worker로 옮겨도 계약이 유지되는가?
