# Self-hosted Operations Runbook

이 문서는 현재 저장소로 재현 가능한 로컬 운영 리허설과 아직 구현되지 않은 production 운영 능력을 구분한다. 현재 제공되는 `compose.yaml`은 단일 조직용 모듈러 모놀리스의 **로컬 데모 구성**이며 production 배포 manifest/profile이 아니다. TLS, production secret wiring, split-role production Compose는 제공하지 않는다. Kubernetes, Redis, Kafka, Elasticsearch/OpenSearch도 지원 범위가 아니다.

## 1. 현재 지원 표면

| 구성 요소 | 현재 릴리스 | 역할 |
|---|---|---|
| PostgreSQL | `postgres:17-alpine` | current state, canonical audit ledger, audit projection |
| Backend | Java 21, Spring Boot 4.1 | Flyway migration, HTTP API, authorization, audit |
| Frontend | Node 26 build, Nginx 1.31 runtime | customer portal, staff/admin workspace, Audit Explorer |
| Object storage | 미구현 | attachment 자체가 릴리스 범위 밖이므로 backup 대상도 없음 |

지원 버전의 하한 범위를 아직 호환성 매트릭스로 검증하지 않았다. 저장소에 고정된 이미지와 toolchain이 검증 기준이며, 실제 실행 환경 버전은 증거 파일에 남긴다.

## 2. 가장 짧은 재현 경로

Docker Engine, Docker Compose v2, `curl`, Git, Python 3가 필요하다. release rehearsal은 실제 자격 증명을 요구하지 않고 실행마다 임시 자격 증명을 생성한다.

Docker layer cache 재사용을 우회하는 fresh-volume 설치부터 V11→latest 전진 migration, 역할 분리, API smoke, backup, restore, post-restore smoke까지 한 명령으로 실행한다. `--no-cache`는 이 실행의 build step에서 cache를 재사용하지 않을 뿐 기존 Docker build cache를 삭제하지 않는다.

```bash
./scripts/run-operations-rehearsal.sh \
  --evidence-file /tmp/deskseed-operations-evidence.md
```

반복 개발 중에는 Docker build cache만 허용하는 smoke mode를 쓸 수 있다. 데이터·권한·복구 검사는 full mode와 같다.

```bash
./scripts/run-operations-rehearsal.sh --smoke
```

스크립트는 다음을 보장한다.

1. cryptographic run marker가 붙은 고유한 source/restore Compose project와 새 named volume을 만들고, 생성 전 충돌을 거부한다.
2. credential-bearing file, cookie, customer access token 응답, dump는 mode `0700` 임시 디렉터리 안에 두고 evidence/stdout에 값을 기록하지 않는다. 다만 generated secret은 Compose 환경 전달과 API 호출 중 process environment/argument 경계를 통과하므로, 같은 host의 privileged process observation까지 차단하는 secret-isolation 증명은 아니다.
3. first-admin password file은 mode `0600`으로 mount한다.
4. rehearsal 전용 private overlay에서 migration role과 runtime role을 분리한다.
5. source와 restore가 같은 run-scoped backend/frontend image ID를 사용하게 고정한다. 병렬 worktree 변경을 restore 단계에서 다시 build하지 않는다.
6. user Docker config와 분리된 mode `0600` anonymous client config, 검증된 local Unix socket/daemon, exact Docker CLI/plugin만 사용하고 inherited registry-auth, remote builder, telemetry override를 거부한다.
7. pull/build 명령을 process-group 단위로 제한 시간 안에 종료시키고, 정상 종료와 실패 모두 `EXIT` trap으로 exact owned container/network/image, fingerprinted volume, 임시 파일 제거를 시도한 뒤 container/network/volume/image/secret-directory가 없는지 검사한다.
8. cleanup 또는 zero-artifact 검사가 실패하면 실행과 evidence를 `FAIL`로 만들고, cleanup 결과가 정해진 뒤에만 evidence를 쓴다.
9. evidence에는 secret이나 access token을 쓰지 않는다.

2026-08-12의 current full evidence는 위 경계에서 139초 만에 V11→V14, backup/restore와 post-restore smoke를 완료했다. `pg_dump`은 93,929바이트/403ms, fresh `pg_restore` parity는 341ms, post-restore application smoke는 8초였다. 이는 로컬 synthetic fixture 관찰값이며 production SLA가 아니다.

고정 포트 `28080`, `25173`, `28081`, `25174`가 사용 중이면 아래 변수로 바꾼다.

```bash
DESKSEED_OPERATIONS_BACKEND_PORT=38080 \
DESKSEED_OPERATIONS_FRONTEND_PORT=35173 \
DESKSEED_OPERATIONS_RESTORE_BACKEND_PORT=38081 \
DESKSEED_OPERATIONS_RESTORE_FRONTEND_PORT=35174 \
  ./scripts/run-operations-rehearsal.sh --smoke
```

## 3. 일반 로컬 부팅과 production 경계

일반 개발 데모는 다음 순서로 시작한다.

```bash
install -m 600 /dev/null /tmp/deskseed-first-admin-password
printf '%s' 'replace-with-a-unique-12-plus-character-password' \
  > /tmp/deskseed-first-admin-password

DESKSEED_BOOTSTRAP_ADMIN_ENABLED=true \
DESKSEED_BOOTSTRAP_ADMIN_EMAIL=admin@example.test \
DESKSEED_BOOTSTRAP_ADMIN_DISPLAY_NAME='Deskseed Admin' \
DESKSEED_BOOTSTRAP_ADMIN_PASSWORD_FILE=/tmp/deskseed-first-admin-password \
DESKSEED_RUNTIME_USER="$(id -u):$(id -g)" \
  docker compose up --build --detach

curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health
curl --fail --silent --show-error http://127.0.0.1:5173/ >/dev/null
```

`DESKSEED_RUNTIME_USER`는 Linux에서 file-backed Compose secret의 host `0600` 소유자와 backend의 non-root 실행 uid/gid를 맞춘다. 이 bootstrap 명령을 root 계정으로 실행하지 않는다.

기본 `compose.yaml`의 DB password와 audit/cursor key는 공개된 **로컬 개발 기본값**이다. 인터넷에 노출하거나 production에 재사용하면 안 된다. 아래 항목은 production 배포자가 별도 manifest와 secret 관리 체계를 만들 때 필요한 요구사항이지, 이 저장소가 제공하는 실행 가능한 production profile이 아니다.

- DB migration/runtime credential
- first-admin password file
- 32-byte base64 access-audit key와 key version
- agent queue cursor signing key
- Audit Explorer cursor signing key
- 허용된 CORS origin
- TLS reverse proxy 설정

현재 Compose는 TLS reverse proxy, rate limit, CAPTCHA/email ownership verification, centralized secret rotation을 제공하지 않는다. `DATABASE_MIGRATION_*`/`DATABASE_RUNTIME_*`도 base Compose에 연결하거나 별도 role을 생성하지 않는다. 따라서 base Compose를 공용 인터넷에 노출하는 형태와 production self-hosting은 모두 unsupported다.

## 4. DB ownership과 least privilege

이번 릴리스에서 실행 가능한 split-role 증명은 `run-operations-rehearsal.sh`이 base Compose, `compose.e2e.yaml`, private `scripts/operations/compose.rehearsal.yaml` overlay를 함께 사용하는 경로뿐이다. 이 overlay는 실행마다 migration/runtime role과 임시 password를 만들고 backend data source와 Flyway credential을 분리한다. base Compose와 `.env` 예시는 `DATABASE_MIGRATION_*`/`DATABASE_RUNTIME_*`를 생성·전달하지 않으므로 같은 경계를 재현하지 않는다. 이를 production profile로 복사하거나 production 지원으로 해석하면 안 된다.

`configure-runtime-role.sql`은 rehearsal의 migration credential로 실행된다. 일반 application table의 DML을 허용한 다음 다음 권한을 명시적으로 회수한다.

- `flyway_schema_history`의 `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`, `REFERENCES`, `TRIGGER`, `MAINTAIN`을 포함한 모든 table privilege
- canonical ticket/access/search/admin audit table의 `UPDATE`, `DELETE`, `TRUNCATE`
- runtime role의 schema `CREATE`

Protected search ciphertext는 구현된 retention delete를 위해 `DELETE`만 유지하고 `UPDATE`를 회수한다. DB trigger는 소유자 또는 잘못 과다 부여된 role에 대한 두 번째 방어선이다. `verify-runtime-role.sql`은 effective privilege를 검사하며, rehearsal은 source와 restored DB 모두에서 실제 runtime credential의 Flyway history `UPDATE`가 `permission denied`로 실패하는 것까지 확인한다.

`configure-default-runtime-privileges.sql`의 새 table `SELECT`/`INSERT` default privilege는 V11 target으로 current application을 부팅하기 위한 rehearsal 전용 bootstrap이다. 각 migration 후에는 `configure-runtime-role.sql`을 다시 실행해 ordinary mutable table 권한을 완성하고 Flyway/canonical ledger 권한을 다시 회수한다. Production에서 같은 모델을 사용하려면 운영자가 TLS, secret lifecycle, role creation/rotation, migration sequencing을 포함한 별도 manifest를 만들고 검증해야 하며, 그 manifest는 이번 릴리스에 포함되지 않는다.

## 5. First boot와 bootstrap admin

First-admin bootstrap은 다음 조건에서만 한 번 실행된다.

- `DESKSEED_BOOTSTRAP_ADMIN_ENABLED=true`
- email과 password-file이 모두 제공됨
- `staff_accounts`가 비어 있음

생성 결과는 `STAFF_CREATED`, `SYSTEM`, `PASSWORD_FILE` metadata를 가진 admin security audit으로 남는다. password 원문은 audit/log/DB 평문 열에 저장하지 않는다. staff가 이미 존재하는 restored DB에서는 bootstrap이 새 admin을 만들지 않는다.

부팅 후 다음을 확인한다.

1. backend health HTTP 200.
2. frontend HTTP 200.
3. anonymous request 생성과 one-time access token lookup.
4. bootstrap admin login과 `/api/v1/agent/me`.
5. `NAVIGATION` staff ticket read와 `TICKET_VIEWED` access audit.

## 6. Upgrade 절차

아래는 production 배포 manifest가 마련된 뒤 적용할 운영 요구사항이다. 이번 저장소만으로 실행 가능한 production upgrade 절차는 아니다.

1. release note와 `backend/src/main/resources/db/migration/`의 새 migration을 확인한다.
2. 현재 DB의 logical backup을 별도 매체에 보관하고 checksum과 시작 시각을 기록한다.
3. backup을 별도 staging DB에 restore한다.
4. migration role로 Flyway를 실행한다.
5. runtime role privilege script를 재실행하고 verification SQL을 통과시킨다.
6. Hibernate `ddl-auto=validate`, backend/frontend health, login, ticket, audit smoke를 실행한다.
7. maintenance window에서 동일 순서를 production에 적용한다.
8. health뿐 아니라 핵심 API와 audit row 생성을 확인한 뒤 traffic을 연다.

### 이 릴리스에서 검증한 upgrade 형태

이 저장소에는 이전 tagged release image가 없다. 그러므로 rehearsal은 빈 DB에서 migration role의 default `SELECT`/`INSERT`를 runtime role에 먼저 부여하고, current image를 `SPRING_FLYWAY_TARGET=11`, `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`로 시작한다. V11 schema에 synthetic ticket/audit을 만든 다음 같은 volume을 repository latest migration으로 전진시킨다. 이 방법은 migration/data 보존과 모든 부팅의 Hibernate validation을 검증하지만 “이전 배포 binary와 최신 schema” 호환성을 인증하지 않는다. 첫 tagged release 이후에는 실제 이전 tag image→현재 image 경로로 교체해야 OPS-001의 supported-previous-release 조건을 완전히 충족한다.

## 7. Backup

아래 예시는 logical custom-format backup이다. 실행 전 destination directory 권한, 여유 공간, encryption-at-rest를 운영자가 확인한다.

```bash
umask 077
backup_path=/tmp/deskseed-pre-upgrade.dump

docker compose exec -T db \
  sh -eu -c 'pg_dump --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --format=custom --no-owner --no-privileges' \
  > "$backup_path"

python3 -c \
  'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' \
  "$backup_path"
```

실제 운영 backup은 DB container와 같은 호스트의 `/tmp`에 장기 보관하지 않는다. 접근 통제·암호화된 외부 저장소로 옮기고 backup 시작 시각, 완료 시각, byte size, checksum, schema version, 보존 만료를 inventory에 기록한다. 현재 release에는 object storage data가 없으므로 DB만 대상이다.

## 8. Fresh restore

아래 명령은 base Compose로 확인할 수 있는 로컬 fresh-restore 패턴이다. Production에서는 기존 DB를 제자리에서 덮어쓰지 말고 operator-owned deployment manifest의 새 database/volume에 적용해야 하며, 그 manifest는 이 저장소에 포함되지 않는다.

```bash
docker compose --project-name deskseed-restore up --detach db

docker compose --project-name deskseed-restore exec -T db \
  sh -eu -c 'pg_restore --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --no-owner --no-privileges' \
  < /tmp/deskseed-pre-upgrade.dump
```

Rehearsal은 그 다음 default startup privilege와 runtime role 권한을 모두 다시 구성하고 `verify-runtime-role.sql`과 실제 Flyway history UPDATE denial을 통과한 뒤 application을 시작한다. `pg_dump --no-privileges`는 source의 ACL/default privilege 보존을 전제로 하지 않으므로 이 단계는 생략할 수 없다. Restore 검증 순서는 다음과 같다.

1. Flyway schema history가 기대 latest version인지 확인.
2. ticket, ticket audit, access audit, admin security audit, audit projection count를 source snapshot과 대조.
3. backend/frontend health 확인.
4. snapshot 전에 만든 customer access token으로 ticket lookup.
5. restored admin password로 login.
6. 같은 ticket을 staff로 읽고 새 access audit이 append되는지 확인.

`run-operations-rehearsal.sh`은 위 과정을 두 번째 고유 Compose project와 fresh volume에서 실행한다. Dump, cookie, token을 포함한 mode-0700 secret directory와 run-scoped Docker resource를 먼저 정리하고 부재를 검증한 뒤 sanitized evidence를 쓴다. 정리 또는 검증 실패는 PASS로 무시하지 않는다.

## 9. RPO와 RTO 해석

`pg_dump`은 일관된 logical snapshot을 만들지만 이 runbook은 WAL archive/PITR를 구성하지 않는다.

- 검증된 recovery point: dump snapshot이 포함한 마지막 committed row.
- 최악 RPO: backup 주기 + snapshot 시작 후 장애까지의 변경분.
- RPO 0 주장: 불가.
- 측정 backup duration: `pg_dump` command wall time.
- 측정 restore duration: fresh DB의 `pg_restore` wall time.
- 측정 recovery-validation duration: restored backend/frontend 기동과 login/ticket/audit smoke 완료까지.

Current full rehearsal의 관찰값은 backup 403ms, fresh restore parity 341ms, post-restore application smoke 8s다. 이 수치는 특정 로컬 fixture와 하드웨어의 관찰값이며 SLA가 아니다. 실제 production data volume으로 정기 restore drill을 반복해 capacity와 목표를 정해야 한다.

## 10. Rollback과 failed migration

Flyway migration은 forward-only다. 저장소에는 자동 down migration이 없고 이미 적용된 migration file을 수정하거나 Flyway history를 수동 편집하면 안 된다.

### Migration 시작 전 실패

1. 새 application을 시작하지 않는다.
2. 기존 application health와 DB 상태를 확인한다.
3. 원인을 수정한 새 forward migration을 staging restore에서 검증한다.

### Migration 중/후 실패

1. traffic을 차단하고 실패한 application을 중지한다.
2. DB와 Flyway history를 보존해 원인을 조사한다.
3. 데이터 손상이 없고 수정이 additive하면 forward-fix migration/application을 우선한다.
4. restore가 필요하면 pre-upgrade backup을 **새 DB**에 복구하고 smoke 후 연결을 전환한다.
5. snapshot 이후 변경은 유실되므로 실제 data-loss window를 incident record에 남긴다.

### Application rollback 제한

이전 binary가 새 schema와 호환된다는 검증 없이는 image만 되돌리지 않는다. 이번 release는 이전 tagged image가 없어 binary rollback을 검증하지 못했다. Current-image V11 fixture→V14 전진, backup/restore와 application smoke는 통과했지만 그 결과를 이전 배포 binary의 backward compatibility 보장으로 확대하지 않는다. DB volume 삭제나 in-place destructive rollback은 허용 절차가 아니다.

## 11. Observability와 incident 확인

현재 구현:

- `/actuator/health` aggregate health.
- request ID/correlation ID request context.
- canonical change/access/admin audit과 별도 application log.
- protected search ciphertext retention job.

현재 미충족/제약:

- process/DB/migration/required dependency를 분리한 liveness/readiness contract 미완성.
- backup age, disk risk, audit-write failure, job lag을 위한 production alert/dashboard 미구성.
- structured JSON logging과 중앙 수집 runbook 미구성.
- outbox/webhook/delivery/SLA/analytics job은 제품 자체가 미구현이므로 운영 대상으로 주장하지 않음.

Audit persistence failure 시 민감 read/write가 fail closed하는지 확인하고 DB capacity/permission을 조사한다. application log에 password, Authorization header, session cookie, customer access token, raw search query, comment body를 첨부하지 않는다.

## 12. Gate 상태와 정기 작업

| Gate | 상태 | 이 rehearsal의 근거 | 남은 조건 |
|---|---|---|---|
| OPS-001 Fresh install/upgrade | LIMITED | empty volume, current image V11→V14, health, data preservation | 실제 previous tagged image 경로 |
| OPS-002 Backup/restore | PASS (local synthetic scope) | login, ticket, canonical audit/projection, checksum, duration, RPO window | attachment/reference는 feature 미구현이라 검증 불가; production-size drill |
| OPS-003 Secrets/bootstrap | LIMITED | rehearsal-generated secrets, 0600 file, anonymous Docker client, one-time audited admin, private-overlay split DB roles | production manifest, 전체 secret rotation procedure와 public deployment controls |
| OPS-004 Health/observability | NOT MET | aggregate health와 request context | 분리 readiness, alert/dashboard, structured central logs |
| OPS-005 Retention/maintenance | LIMITED | protected search ciphertext bounded retention 구현 | 전체 retention dry-run/legal hold, pending migration/backup age operator view |

Release evidence는 `docs/evidence/release/operations/`에 command, host/runtime version, 결과, duration, RPO 해석, known limitation과 함께 저장한다. 실패 실행을 PASS로 덮어쓰지 말고 원인과 재실행을 별도 기록하거나 PR 설명에 연결한다.
