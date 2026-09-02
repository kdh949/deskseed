# Production Ingress Topology

Status: **IMPLEMENTATION_READY**

## Goal

Sophos WAF가 접근하는 명시적 DMZ origin만 host에 publish하고, backend·PostgreSQL·Redis는 Docker 내부 network에만 배치하며 Mailpit은 production 실행에서 제외한다.

## Decision and source references

- Decision IDs: D-039
- Accepted ADRs: ADR-0028
- Requirements: REQ-PROD-001
- API operations: 변경 없음
- Verification gates: ARCH-001, OPS-001, OPS-003

## Actor and source

- Actor type: SYSTEM
- Source: operator-owned Docker Compose deployment
- Product actor, HTTP authorization, request/correlation semantics는 변경하지 않는다.

## In scope

- `compose.production.yaml` production overlay
- Frontend origin의 명시적 host IP/port publish
- Backend host port 제거
- PostgreSQL 전용 internal network와 Redis limiter internal network
- production 실행에서 Mailpit 제외 및 mail delivery 비활성화
- merged Compose contract regression
- self-hosted runbook과 requirement evidence

## Out of scope

- production profile과 secret wiring
- DB migration/runtime role 생성
- Redis 인증/TLS
- object storage와 attachment scan policy
- Sophos 장비 자체 설정, 중앙 로그·알림, production 규모 복구 검증

## Invariants and failure semantics

- Frontend bind address와 origin port가 없으면 Compose interpolation이 실패한다.
- Backend, PostgreSQL, Redis, Mailpit 관리 UI는 host port를 갖지 않는다.
- Frontend는 backend application network만, backend는 application/database/limiter network만 사용한다.
- Mailpit profile은 production 명령에서 활성화하지 않으며 outbound delivery는 disabled다.
- WAF 우회 차단은 Sophos/host firewall 운영 책임이며 이 overlay가 WAN source filtering을 대신하지 않는다.

## Data and privacy

- schema, ticket, audit, attachment data는 변경하지 않는다.
- 실제 credential은 저장소에 추가하지 않는다.

## Threats changed

- Information disclosure: backend와 Mailpit 직접 노출을 제거한다.
- Elevation of privilege: DB/Redis에 frontend가 직접 접속할 network path를 제거한다.
- Security misconfiguration: wildcard bind 대신 운영자 지정 DMZ IP를 요구한다.

## Acceptance scenarios

1. Given production overlay, When merged Compose를 생성하면, Then frontend만 지정 DMZ IP/origin port를 publish한다.
2. Given production overlay, When service model을 검사하면, Then backend/db/redis는 published port가 없고 Mailpit은 active service가 아니다.
3. Given network model, When service network membership을 검사하면, Then frontend에서 DB/Redis로 직접 연결되는 shared network가 없다.
4. Given missing frontend bind configuration, When Compose를 render하면, Then startup 전에 실패한다.

## Validation

```bash
bash scripts/test-production-compose-contract.sh
make docs-check
git diff --check
```

## Compatibility and migration

- OpenAPI/database migration: 없음
- 기본 `docker compose up` 개발 경로: 변경 없음
- production은 `-f compose.yaml -f compose.production.yaml`을 명시해야 한다.
- rollback은 production overlay 제거다.

## Human explanation

WAF가 보호하는 한 origin만 host에 열고 나머지는 network membership과 port publish 양쪽에서 차단한다. Kubernetes나 별도 ingress를 추가하지 않고 Accepted Docker Compose topology 안에서 가장 작은 격리 단위를 사용한다.
