# Production Runtime Separation

Status: **IMPLEMENTATION_READY**

## Goal

Production Compose에서 Flyway migration과 application runtime DB role을 분리하고, Redis를 host에 publish하지 않은 채 ACL 인증으로 보호한다. Redis TLS를 일시적으로 생략하는 선택은 exact `redis:6379` endpoint와 전용 internal network에 한정한다.

## Decision and source references

- Decision IDs: D-018, D-039, D-065
- Accepted ADRs: ADR-0018, ADR-0028, ADR-0048
- Requirements: REQ-PROD-001, REQ-AUD-007
- API operations: 변경 없음
- Verification gates: ARCH-001, ARCH-004, OPS-001, OPS-003

## Actor and source

- Actor type: SYSTEM
- Source: operator-owned migration/runtime startup sequence
- HTTP actor/source/request/correlation 계약은 변경하지 않는다.

## In scope

- 빈 DB에서 init 전용 bootstrap과 제한된 migration/runtime role 생성 및 default privilege 설정
- Flyway one-shot service와 migration 후 runtime privilege 적용·검증 service
- Backend의 runtime credential 사용 및 embedded Flyway 비활성화
- Redis external ACL file, named authenticated application user, unauthenticated default user 차단
- Redis TLS 미사용 production exact endpoint fail-fast 검증
- production env 예시와 self-hosted runbook

## Out of scope

- Redis TLS, replication, failover, persistence, metrics, alerting
- secret manager와 자동 rotation
- 외부 SMTP delivery
- VersityGW와 attachment policy
- production 규모 backup/restore 및 schema downgrade

## Invariants and failure semantics

- Flyway는 migration role만 사용하고 Backend datasource는 runtime role만 사용한다.
- bootstrap credential은 DB init에만 전달되고 migration role은 `NOSUPERUSER/NOCREATEDB/NOCREATEROLE`여야 한다.
- migration 또는 privilege 검증 job이 실패하면 Backend가 시작하지 않는다.
- runtime role은 schema CREATE와 Flyway history 접근 권한이 없고 canonical audit table은 SELECT/INSERT만 갖는다.
- Redis default user는 비활성화하고 application user는 limiter key와 health `INFO`를 포함한 필요한 command만 사용한다.
- Redis TLS가 false이면 endpoint가 Compose service `redis:6379`와 정확히 일치해야 한다.

## Data and privacy

- schema migration 자체는 기존 forward-only SQL을 재사용하며 새 migration은 없다.
- credential 값은 Git에 저장하지 않고 operator env/ACL file에서 주입한다.
- Redis는 짧은 TTL limiter counter만 저장하며 ticket/customer/audit source of truth가 아니다.

## Threats changed

- Elevation of privilege: runtime DB credential의 DDL/Flyway/canonical-ledger mutation 권한을 제거한다.
- Spoofing: Redis unauthenticated default user를 끄고 named ACL user를 요구한다.
- Information disclosure: DB/Redis port는 host에 publish하지 않지만 동일 Docker host root/daemon 권한자는 plaintext Redis traffic과 process environment를 관찰할 수 있다.

## Acceptance scenarios

1. Given production Compose, When service graph를 검사하면, Then migration과 privilege 검증이 성공해야 Backend가 시작한다.
2. Given runtime role, When privilege SQL을 실행하면, Then DDL/Flyway/canonical-ledger mutation이 거부된다.
3. Given Redis ACL, When unauthenticated command를 보내면, Then default user가 off라서 거부된다.
4. Given production profile with Redis TLS false, When host/port가 `redis:6379`와 다르면, Then application context가 실패한다.

## Validation

```bash
bash scripts/test-production-compose-contract.sh
cd backend && ./gradlew --no-daemon test --tests dev.deskseed.customerauth.internal.CustomerAuthPropertiesTest
make docs-check
git diff --check
```

## Compatibility and migration

- OpenAPI/Flyway migration: 없음
- 기본 local Compose: 변경 없음
- 새 production volume은 init script가 두 role을 만든다. 기존 volume은 운영자가 role 생성/default privilege를 먼저 수행해야 한다.
- rollback은 이전 Compose로 전환하되 생성된 role/ACL은 자동 삭제하지 않는다.

## Human explanation

Migration credential은 schema 변경 작업에만 쓰고, 실제 요청 처리 Backend에는 제한된 runtime credential만 준다. Redis TLS 생략은 안전하다는 뜻이 아니며, 지원 topology는 exact service endpoint와 한 host의 internal Docker network로 위험을 제한한다.
