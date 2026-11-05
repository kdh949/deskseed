# ADR 0043 — Redis 기반 고객 인증 요청 제한

## Status

Accepted — 2026-08-25; amended by product owner — 2026-08-26

## Context

회원가입·로그인·비밀번호 재설정·매직 링크는 계정 존재 여부를 노출하지 않으면서 요청 횟수를 제한해야 합니다. 요청 목적, 수신지와 요청자 네트워크의 keyed fingerprint로 횟수를 계산합니다. ADR 0008은 PostgreSQL 우선을 정했고 ADR 0042도 처음에는 고객 인증 limiter에 PostgreSQL을 사용하도록 했습니다.

구현 전 검토에서는 두 가지 비용이 문제가 됐습니다. 모든 인증 요청을 PostgreSQL에 쓰면 공격 트래픽이 특정 row의 락, WAL과 커넥션 풀에 부담을 줄 수 있습니다. PostgreSQL limiter를 먼저 완성한 뒤 Redis로 교체하면 같은 기능을 두 번 구현해야 합니다.

PostgreSQL이 처리할 수 있는 요청 수는 하드웨어와 요청 분포에 따라 달라집니다. 같은 키의 업데이트는 row lock을 기다리고, 서로 다른 키가 많은 공격은 insert·인덱스·WAL·커넥션 풀 사용량을 늘립니다. Redis는 만료되는 원자적 카운터를 제공하지만 네트워크 장애, secret·TLS, 모니터링, 복제와 복구를 추가로 운영해야 합니다. 두 방식 모두 애플리케이션 앞단의 경로·네트워크별 요청 제한이 필요합니다.

## Decision

- 2026-08-26 변경에서 담당자는 고객 인증 limiter에 Redis 하나를 사용하고 PostgreSQL 비교 벤치마크를 생략하기로 결정했습니다. 업무 DB의 공격 트래픽 부담과 교체 구현 비용을 줄이기 위한 선택입니다. 처리량 목표의 달성 여부는 아래의 미측정 항목으로 남깁니다.
- HTTP/OpenAPI는 요청 목적·수신지·네트워크, 공통 오류와 `Retry-After`를 정의합니다. 저장소 선택은 HTTP 계약에서 분리합니다.
- application service는 `AuthenticationAttemptLimiter` port를 사용합니다. 키 생성, 공통 응답, dummy 비밀번호 계산, 보안 감사와 `Retry-After` 처리는 저장소 adapter 밖에 둡니다.
- 만료 설정과 카운터 변경을 한 번에 처리하는 Redis 서버 스크립트로 adapter 하나를 구현합니다. 고객 인증용 PostgreSQL adapter나 이중 쓰기는 추가하지 않습니다. 직원 인증의 PostgreSQL lockout과 별도 공개 문의 limiter는 유지합니다.
- reverse proxy·ingress에서 경로·네트워크별 요청량을 먼저 제한합니다. 애플리케이션은 계정 유무를 노출하지 않는 기존 limiter 검사를 계속 수행합니다.
- 공통 저장소에서 거부한 fingerprint는 각 인스턴스가 반환된 시간 창의 끝까지 크기를 제한한 deny cache에 보관할 수 있습니다. 이 캐시는 반복 조회만 줄이며 요청을 허용하거나 원래 허용량을 늘리지 않습니다.
- limiter 조회·저장을 끝낸 뒤 비밀번호 비교, 메일 처리와 보안 감사 저장을 실행합니다. 저장소 timeout·장애는 공통 `503`으로 응답하고 요청 제한을 건너뛰지 않습니다.

이 ADR은 ADR 0042의 고객 인증 limiter 저장소 선택을 변경합니다. ADR 0008의 별도 승인된 공통 상태 저장소 사용을 이 기능에 적용하며, 요청 제한 기준, 공통 응답, `429`, `Retry-After`, dummy 계산과 인증 규칙은 유지합니다.

## Declared capacity target and unmeasured release evidence

구현 전에 정한 목표입니다.

| 항목 | 목표 |
| --- | --- |
| 지속 요청 | 20 requests/second |
| 공격성 burst | 100 requests/second, 60초 |
| 동시 요청 | 100 |
| 여유 계수 | 2x |
| limiter 지연 | p95 ≤20ms, p99 ≤50ms |
| 일반 업무 트랜잭션 영향 | p95 악화 ≤10% |
| 오류·timeout·DB 커넥션 풀 고갈 | 목표 부하에서 0건 |

PostgreSQL에 공격 트래픽의 쓰기·락 비용을 추가하는 위험을 피하기 위해 비교 벤치마크는 실행하지 않았습니다. AUTH-006 성능·처리량 검증은 `Not run`입니다. 지원 배포 환경에서 트래픽 분포, p50/p95/p99, 오류·timeout, Redis 메모리·eviction과 일반 업무 부하 영향을 측정한 뒤 `docs/performance/`에 기록해야 목표 통과 여부를 판단할 수 있습니다.

## Redis requirements

- 카운터 변경과 만료를 하나의 원자적 연산이나 검토한 서버 script/function으로 처리합니다. `INCR`와 만료를 별도로 호출해 만료가 누락될 수 있는 방식은 사용하지 않습니다.
- 키에는 목적별 keyed fingerprint만 넣습니다. 원문 이메일·IP·비밀번호·토큰·세션·회사 정보는 제외합니다.
- TTL, 메모리 한도·eviction, TLS·인증, 네트워크, health·metrics와 로컬·CI 구성을 정의합니다.
- failover 때 허용할 데이터 손실과 장애 시 거부 동작을 정합니다. Redis 복제·영속화만으로 강한 일관성이 보장된다고 가정하지 않습니다.
- 외부 응답의 `429`, `Retry-After`, 공통 `503`, 계정 존재 여부 보호, 감사·개인정보 규칙을 유지합니다.
- Redis는 고객 인증 요청 카운터에 사용합니다. 계정·인증 정보·세션·토큰·동의·감사·티켓 원본은 기존 담당 저장소에서 관리합니다.

## Alternatives considered

### Introduce Redis immediately without measurement

2026-08-26 변경에서 선택한 방식입니다. PostgreSQL의 limiter 쓰기·락 부담과 교체 구현을 줄이는 대신 Redis 운영과 아직 측정하지 않은 처리량 목표를 후속 과제로 남깁니다.

### Fully implement PostgreSQL and optimize only after production contention

제외했습니다. 특정 키 집중·서로 다른 키 증가에 따른 비용은 작은 벤치마크로도 조사할 수 있어, 운영에서 경합이 발생할 때까지 전체 구현을 기다릴 필요가 없습니다.

### Keep storage selection inside authentication application services

인증·세션·감사 코드에 저장소 선택이 퍼지고 교체 비용이 커져 제외했습니다.

### Use only reverse-proxy or per-instance memory limits

분산 요청이나 여러 애플리케이션 인스턴스에서 공통 허용량을 관리하지 못해 보조 수단으로만 사용합니다.

## Consequences

- 고객 인증 요청 제한은 Redis를 우선 사용합니다.
- Task 7은 변경 결정을 기록한 뒤 저장소에 의존하지 않는 port와 Redis adapter 하나를 구현합니다.
- 저장소 교체는 adapter에서 처리하며 공개 API 버전은 바꾸지 않습니다. Redis 운영 담당과 절차는 별도로 정합니다.
- AUTH-006 처리량 목표는 지원 환경에서 측정할 때까지 미검증으로 유지합니다.

## References

- D-060
- ADR 0008, 0035, 0042
- REQ-AUTH-003, REQ-AUTH-004
- AUTH-001, AUTH-005, AUTH-006, AUTH-007, AUTH-008
- `docs/32-database-schema-and-index-blueprint.md`
- `docs/56-customer-auth-consent-request-form-p0-implementation-plan.md`
- PostgreSQL locking: <https://www.postgresql.org/docs/current/explicit-locking.html>
- PostgreSQL pgbench: <https://www.postgresql.org/docs/current/pgbench.html>
- Redis rate limiter pattern: <https://redis.io/docs/latest/develop/use-cases/rate-limiter/>
- Spring Data Redis scripting: <https://docs.spring.io/spring-data/redis/reference/redis/scripting.html>
- Official Redis container image: <https://hub.docker.com/_/redis>
- Redis replication: <https://redis.io/docs/latest/operate/oss_and_stack/management/replication/>
