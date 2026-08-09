# Intentional Non-goals

이 문서는 “영원히 하지 않는다”가 아니라, 현재 단계에서 왜 넣지 않는지와 어떤 증거가 있어야 도입하는지를 기록한다.

## 1. Not in Core MVP

| Deferred | Why now | Revisit when |
|---|---|---|
| Kafka | local transaction/event semantics가 먼저 | 독립 소비자, 처리량, 장애 격리가 측정됨 |
| Redis | 현재 cache/lock/rate-limit coordination 근거 없음 | DB 방식이 병목임을 측정 |
| Elasticsearch/OpenSearch | Postgres search를 아직 검증하지 않음 | relevance, body scale, latency 한계 확인 |
| WebFlux/R2DBC | blocking DB 중심 workload | 많은 장기 연결/I/O concurrency 근거 |
| Kubernetes | self-hosted 단일 인스턴스 복잡도 증가 | 운영 규모와 HA 요구가 명확 |
| Microservices | 경계·독립 배포 필요 미검증 | team/data/scale boundary가 실제로 분리 |
| Event Sourcing | current state + audit로 요구 충족 | time-travel/rebuild가 audit로 해결 불가 |
| Multi-tenancy | 한 설치 = 한 조직 | SaaS 제품 전략이 확정 |
| Arbitrary workflow DSL | 잘못된 자동화 위험 | allowlisted trigger actions가 안정화 |
| Arbitrary plugin code in backend | RCE와 운영 격리 문제 | 별도 sandbox/runtime 전략 승인 |
| OAuth authorization server | API key로 machine integration v1 충족 | third-party delegated apps 필요 |
| Full SDK marketplace | 계약과 배포 운영이 먼저 | public API/SDK 사용자가 실제 존재 |
| Full embedded ticket editor | auth/permissions 복잡성 | deep link와 read panel 한계가 확인 |
| Long-term external WORM archive | core audit semantics가 먼저 | 규정/감사 요구와 저장소 결정 |
| Legal hold | 제품 정책·법률 검토 필요 | 실제 regulated deployment 요구 |

## 2. Explicitly not allowed

다음은 “나중에”가 아니라 현재 아키텍처에서 금지한다.

- 외부 시스템의 Deskseed PostgreSQL 직접 읽기·쓰기
- 브라우저에 long-lived IntegrationClient secret 저장
- `X-Actor-Id` 같은 임의 header로 상담사 사칭
- staff controller를 그대로 public platform API로 노출
- internal comment를 scope 없이 외부 API에 반환
- 외부 URL을 validation 없이 서버가 fetch
- request/response body 전체를 일반 application log에 남김
- password, access token, API secret, authorization header를 audit에 저장
- audit canonical row update/delete
- audit explorer 사용을 audit하지 않음
- background polling을 사람이 티켓을 열어본 기록으로 집계
- idempotency 없는 외부 create/comment API
- webhook retry에서 새 business event ID 발급
- API 계약보다 먼저 SDK를 수작업으로 독립 진화

## 3. Deferred but schema-aware

실제 기능은 나중에 만들지만, 지금 데이터 의미를 망가뜨리지 않도록 seam을 둔다.

- SLA: comment visibility, actor type, status transition time, group ownership interval
- Analytics: current state와 update/access facts 분리
- Trigger/automation: command ID, actor/source, causation, idempotency
- Webhooks: versioned integration event and event ID
- SDK: OpenAPI first and stable problem types
- App SDK: external reference, ticket context projection, explicit scopes
- Audit archive: globally ordered IDs/digests and immutable timestamps
- Delegated OAuth: machine actor와 human actor를 혼합하지 않음

## 4. Scope-change rule

새 기능이나 기술을 추가할 때 반드시 다음을 문서화한다.

1. 해결하려는 사용자/운영/보안 문제
2. 현재 baseline
3. 더 단순한 대안
4. 새 threat and failure modes
5. data migration and rollback
6. audit obligations
7. acceptance tests
