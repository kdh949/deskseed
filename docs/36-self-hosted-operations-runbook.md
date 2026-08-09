# Self-hosted Operations Runbook

## 1. 지원 배포 형태

초기 공식 지원:

```text
Docker Compose
PostgreSQL
Backend
Frontend/reverse proxy
optional S3-compatible object storage later
```

Kubernetes는 측정된 운영 요구가 생기기 전 공식 지원 범위가 아니다.

## 2. Environment categories

```text
APP_BASE_URL
DATABASE_URL / DB credentials
SESSION/JWT keys
CUSTOMER_ACCESS_TOKEN_PEPPER
AUDIT_SEARCH_ENCRYPTION_KEY + version
INTEGRATION_SECRET_PEPPER
WEBHOOK_MASTER_ENCRYPTION_KEY
CORS/TRUSTED_ORIGINS
REVERSE_PROXY/TRUSTED_PROXY
RETENTION policies
MAIL settings later
OBJECT_STORAGE settings later
```

`.env.example`에는 형식만 두고 실제 secret을 넣지 않는다.

## 3. First boot

1. PostgreSQL 준비.
2. Flyway migration.
3. bootstrap admin token 생성.
4. browser에서 최초 admin 계정 등록.
5. bootstrap token 폐기.
6. 조직명·branding·time zone 설정.
7. customer access mode 확인.
8. backup schedule 설정.

## 4. Reverse proxy and TLS

- production은 HTTPS 필수.
- secure/httpOnly/sameSite cookie.
- HSTS는 HTTPS 확인 후 활성화.
- request body size/timeouts 제한.
- trusted proxy 명시.
- public customer endpoint와 staff/admin endpoint rate limit 분리.

## 5. Database operations

- 매일 logical/physical backup 정책 선택.
- point-in-time recovery 가능 여부 기록.
- backup 암호화.
- restore rehearsal 정기 수행.
- audit retention과 backup retention 차이를 문서화.

## 6. Upgrade procedure

1. release notes와 migration 확인.
2. DB backup.
3. preview/staging에서 current backup restore.
4. migration + smoke/E2E.
5. maintenance window 또는 rolling-compatible deployment.
6. health/readiness 확인.
7. critical scenario smoke.
8. 이전 image 보관.

DB migration rollback이 불가능하면 app rollback도 제한됨을 명확히 표시한다.

## 7. Observability

Application logs:

- structured JSON.
- request ID/correlation ID.
- actor ID는 정책에 따라 pseudonymous.
- comment body, access token, API key, raw search query 금지.

Metrics:

- HTTP latency/error.
- DB pool/query.
- ticket command latency.
- access audit failure.
- outbox backlog.
- webhook delivery/retry/dead-letter.
- SLA scheduler lag.

Audit log는 observability log의 대체물이 아니고 반대도 아니다.

## 8. Background jobs

각 job은:

- idempotent.
- lease/lock.
- bounded batch.
- retry/dead-letter.
- progress metric.
- manual rerun.
- operator action audit.

대상:

```text
retention
webhook dispatch
export generation
SLA timers
analytics projection
search indexing
closed ticket transition
```

## 9. Incident playbooks

### Audit persistence failure

- 민감 read/write가 fail closed하는지 확인.
- DB storage/permission 확인.
- 성공했다고 기록되지 않은 요청을 조사.

### Webhook backlog

- endpoint별 실패율.
- global circuit breaker.
- secret/URL 변경 audit.
- replay는 reason과 actor 필수.

### Search/index lag

- PostgreSQL source of truth 유지.
- stale indicator.
- projection rebuild.

### Compromised integration key

- credential revoke.
- access audit로 사용 범위 조사.
- replacement key rotate.
- affected webhook/exports 확인.

## 10. Data lifecycle

- retention job은 dry-run report를 제공.
- delete/pseudonymize counts 기록.
- object storage orphan cleanup.
- export artifact 만료.
- search ciphertext shorter retention.
- backups에서 완전 삭제되는 시점을 운영자가 이해하도록 문서화.

## 11. Support matrix

공식 지원 버전은 release마다 기록한다.

```text
Java
PostgreSQL
Docker/Compose
Browser versions
Object storage later
```

## 12. Production readiness checklist

- TLS.
- strong secret generation and rotation.
- backup + restore verified.
- rate limits/CAPTCHA/email verification decision.
- audit retention and encryption key management.
- admin MFA/SSO decision.
- dependency scanning.
- terms/privacy notice.
- monitoring/alerts.
