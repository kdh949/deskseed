# Deskseed

Deskseed는 한 조직이 직접 설치하는 고객지원 티켓 시스템을 구현한 포트폴리오 프로젝트다. 익명 문의부터 상담사 워크스페이스, 공개 답변과 내부 메모, 이관·자식 티켓, 변경·접근 감사와 Audit Explorer까지를 Kotlin/Spring, React, PostgreSQL로 연결한다. 일반적인 헬프데스크 업무 흐름을 참고하되 제품명, 화면, 코드와 자산은 독립적인 Deskseed 구현이다.

현재 상태는 **Core MVP + Security/Audit 포트폴리오 릴리스 후보**다. 지원 배포 경계는 단일 조직용 로컬 또는 사설망 Docker Compose이며, 공용 인터넷 production 배포를 승인하는 문서가 아니다. 실제 통과·미실행·제한 상태는 [릴리스 검증 요약](docs/evidence/release/verification-summary.md)이 유일한 기준이다.

## 현재 구현 범위

| 영역 | 구현된 동작 |
| --- | --- |
| 고객 포털 | 익명 문의 접수, 첫 `PUBLIC` comment 저장, 한 번만 반환되는 opaque 조회 토큰, 토큰으로 `PUBLIC` 대화만 조회 |
| 직원 인증·관리 | BCrypt 비밀번호, 서버 세션, CSRF, DB 기반 로그인 제한, realm-local expected-actor 일관성 guard, 비밀번호 파일을 이용한 최초 ADMIN bootstrap, ADMIN 전용 직원·그룹·활성 멤버십 관리 |
| 상담사 업무 | Views, PostgreSQL 검색, 3-panel 티켓 워크스페이스, `PUBLIC`/`INTERNAL` 별도 draft, 상태·우선순위·그룹·담당자 통합 저장 |
| 정합성 | 담당자/그룹 invariant, field-aware optimistic concurrency, same-field `409`과 draft 복구, 응답 유실 시 exact UpdateTicket command replay, transfer와 child-ticket 명령 분리, 열린 child 경고 후 parent solve 허용 |
| 감사 | 한 command/한 `TicketAudit`과 ordered events, semantic `TICKET_VIEWED`, 검색→티켓 열람 연결, strict audit failure, 분리된 change/access/admin 원장과 재생성 가능한 Audit Explorer projection |
| 감사자 화면 | activity 목록·상세, 권한·이유·최근 인증을 확인하는 단일 검색어 원문 reveal, export **요청** 기록, projection rebuild와 self-audit |
| 배포·검증 | Docker Compose, Flyway, PostgreSQL Testcontainers, 실제 브라우저 E2E, visual/axe/keyboard suite, 설치·upgrade·backup·restore rehearsal, 성능 fixture/query-plan harness |

핵심 데이터 규칙은 다음과 같다.

- `Ticket`에 `description`을 두지 않는다. 최초 문의 본문은 첫 `PUBLIC` comment다.
- `PUBLIC`/`INTERNAL`, child relation과 staff/audit 필드는 서버 authorization/projection에서 분리한다.
- Transfer는 기존 티켓의 소유권을 이동한다. Child creation은 parent 소유권을 유지한다.
- 현재 `Ticket` row가 현재 상태의 source of truth다. Audit은 Event Sourcing store가 아니다.
- 티켓 mutation과 canonical change audit은 함께 commit/rollback한다. 필수 접근 감사가 실패하면 보호된 응답을 반환하지 않는다.
- Kafka, Redis, Elasticsearch/OpenSearch, Kubernetes, microservices는 현재 runtime에 없다.

구현 상태의 상세 근거는 [현재 구현 경계](docs/15-seed-status.md), 요구사항별 근거는 [traceability matrix](docs/26-requirement-traceability.md), 현재 코드 기준 구조는 [architecture/context/module/data-flow diagrams](docs/portfolio/architecture.md)에서 확인할 수 있다.

## 명시적으로 구현하지 않은 범위

문서나 Accepted ADR이 존재해도 아래 기능은 현재 릴리스 완료 기능이 아니다.

| 범위 | 현재 상태 |
| --- | --- |
| 고객 계정, email ownership, magic link, My Requests, 익명 티켓 claim | 설계만 존재; 미구현 |
| 고객 profile 상세 접근 감사 (`ACC-005`) | 미구현 |
| Platform API, IntegrationClient, idempotency/ETag, ExternalReference, webhook, generated SDK (`ACC-006` 포함) | 계약·blueprint만 존재; runtime 미구현 |
| Audit export artifact 생성·download·expiry·deletion (verification gate `AUD-004` 전체) | allowlisted request와 self-audit만 구현; artifact state는 `NOT_CREATED` |
| 보호된 comment 본문 reveal | 미구현 |
| SECURITY_AUDITOR의 고위험 reveal/export/rebuild 권한을 별도 영속 grant로 부여하는 모델 | 미구현; [security finding](docs/evidence/release/security/security-scan.md)에 공개된 설계 위험 |
| SLA/OLA, trigger/automation, analytics, attachment/rich text, email channel, app/embed SDK | 후속 상세 명세만 존재; 미구현 |
| 외부 signed audit checkpoint, production KMS/secret provider, managed deployment | 미구현 |

`IMPLEMENTATION_READY`는 계약이 구현을 시작할 만큼 구체적이라는 뜻이지 완료 표시가 아니다. 현재/계획 구분은 [ADR index](docs/portfolio/adr-index.md)에도 함께 기록한다.

## 가장 짧은 재현 경로

Docker Engine, Docker Compose v2, Node.js 22.12 이상, npm과 Chromium용 Playwright dependency가 필요하다. 다음 순서는 synthetic data만 사용해 실제 frontend, HTTP API, PostgreSQL과 감사 원장을 검증하고 disposable stack/volume을 종료 시 정리한다.

```bash
npm --prefix frontend ci
npm --prefix frontend exec playwright install chromium
bash scripts/run-release-e2e.sh
```

정상 결과는 customer/staff 5개 시나리오와 Audit Explorer 1개 시나리오 통과다. 이 실행은 익명 접수·조회, staff login/Views/search/workspace, `PUBLIC`/`INTERNAL` 비노출, 실제 두 세션 conflict, transfer/child와 Audit Explorer 흐름을 포함한다. Strict audit failure injection은 별도 backend integration gate에서 검증한다. 상세 발표 순서는 [포트폴리오 demo scenario](docs/portfolio/demo-scenario.md)에 있다.

## 대화형 로컬 데모

Compose에 포함된 기본 DB password와 암호화/signing key는 **로컬 개발 전용 공개값**이다. 직원 화면까지 사용할 때는 저장소 밖의 mode `0600` 파일에 12~128자 임시 ADMIN 비밀번호를 넣고 최초 한 번만 bootstrap한다.

```bash
install -m 600 /dev/null /tmp/deskseed-first-admin.secret
# /tmp/deskseed-first-admin.secret에 고유한 12~128자 비밀번호를 입력한다.

DESKSEED_BOOTSTRAP_ADMIN_ENABLED=true \
DESKSEED_BOOTSTRAP_ADMIN_EMAIL=admin@example.test \
DESKSEED_BOOTSTRAP_ADMIN_DISPLAY_NAME='Deskseed Admin' \
DESKSEED_BOOTSTRAP_ADMIN_PASSWORD_FILE=/tmp/deskseed-first-admin.secret \
DESKSEED_RUNTIME_USER="$(id -u):$(id -g)" \
  docker compose up --build --detach

curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health
curl --fail --silent --show-error http://127.0.0.1:5173/ >/dev/null
```

`DESKSEED_RUNTIME_USER`는 Linux의 file-backed Compose secret가 host의 `0600` 권한을 그대로 유지하기 때문에 필요하다. 이 bootstrap 명령은 root가 아닌 계정에서 실행해야 한다.

- 고객: `http://127.0.0.1:5173/requests/new`
- 직원 로그인: `http://127.0.0.1:5173/agent/login`
- ADMIN: `http://127.0.0.1:5173/admin/staff`, `/admin/groups`
- Agent: `http://127.0.0.1:5173/agent/views/my-open`, `/agent/search`
- Audit Explorer: `http://127.0.0.1:5173/audit/activity`
- Backend health: `http://127.0.0.1:8080/actuator/health`

첫 로그인 후 bootstrap 환경 변수를 다시 주입하지 말고 backend를 재생성한 다음 임시 비밀번호 파일을 안전하게 삭제한다. 직원이 이미 있는 DB에서 bootstrap은 기존 계정을 변경하지 않는다.

```bash
docker compose up --detach --force-recreate backend
```

데모 데이터까지 삭제하려면 아래 명령을 사용한다. `--volumes`는 로컬 PostgreSQL named volume도 제거한다.

```bash
docker compose down --volumes --remove-orphans
```

기본 `compose.yaml`은 로컬 개발 topology이며 TLS나 migration/runtime split-role 배포 wiring을 제공하지 않는다. 저장소의 실행 가능한 split-role 증거는 격리된 operations rehearsal overlay이고, 운영자별 production manifest는 이 릴리스에 포함되지 않는다. 장기 실행 경계와 정확한 한계는 [self-hosted operations runbook](docs/36-self-hosted-operations-runbook.md)을 먼저 확인한다.

## 개발과 검증 명령

PostgreSQL만 띄운 뒤 backend/frontend를 직접 실행할 수 있다.

```bash
docker compose up --detach db

cd backend
./gradlew bootRun
```

별도 terminal:

```bash
cd frontend
npm ci
npm run dev
```

주요 검증은 저장소 root에서 실행한다.

| 목적 | 명령 |
| --- | --- |
| 문서 + backend + frontend 기본 gate | `make check` |
| Docker Compose health smoke | `make compose-smoke` |
| Core/Audit 실제 stack E2E | `bash scripts/run-release-e2e.sh` |
| 1280/1440/1920 visual, axe, keyboard | `cd frontend && PLAYWRIGHT_BROWSER=chromium npm run test:e2e:dev` |
| Firefox/WebKit 기능·axe·keyboard smoke | `cd frontend && npx playwright install firefox webkit && PLAYWRIGHT_BROWSER=firefox npm run test:e2e:dev && PLAYWRIGHT_BROWSER=webkit npm run test:e2e:dev` |
| fresh install, V11→latest, role, backup/restore | `./scripts/run-operations-rehearsal.sh --smoke` |
| 무캐시 운영 rehearsal과 외부 evidence 파일 | `./scripts/run-operations-rehearsal.sh --evidence-file /tmp/deskseed-operations-evidence.md` |
| 작은 성능 harness | `bash scripts/run-release-performance.sh --scale smoke` |
| 100k Customer / 1M Ticket release fixture | `bash scripts/run-release-performance.sh --scale release` |

Release performance run의 host repository-filesystem guard는 최소 16 GiB 여유 공간을 요구한다. 별도 Docker data-root filesystem이나 Docker Desktop VM quota는 이 guard가 측정하지 않으므로 같은 headroom을 별도로 확인해야 한다. fixture는 100,000 Customers, 1,000,000 Tickets, 2,000,000 Comments와 별도 change/access/admin audit/projection rows를 생성한다. 실행 여부와 수치를 README 문구로 추정하지 말고 [성능 evidence](docs/evidence/release/performance/README.md)에서 확인한다.
축소된 smoke profile은 harness 검증일 뿐 release-scale `PERF-001`/`PERF-002` 통과 근거가 아니다.

## 기술과 구조

- Backend: Java 21, Kotlin 2.4.10, Spring Boot 4.1.0, Spring MVC/Security/JPA/Modulith
- Data: PostgreSQL 17, Flyway 12.4.0, Hibernate `ddl-auto=validate`
- Frontend: React 18.3.1, TypeScript 5.9.3, Vite 8.2.1, TanStack Query, React Router
- UI: Garden 9.15.7 primitives를 Deskseed-owned wrapper와 독립 branding 뒤에서 사용
- Runtime: Java 21 JRE backend + Nginx frontend + PostgreSQL의 Docker Compose 단일 인스턴스

Backend는 Spring Modulith 기반 모듈러 모놀리스다. HTTP adapter, application transaction, domain invariant, persistence I/O의 책임을 분리하고 `ApplicationModules.verify()`로 경계를 검사한다. Flyway가 schema를 소유하며 application production profile은 migration/runtime credential을 분리해 받을 수 있다. 이를 배포하는 일반 production Compose manifest는 아직 제공하지 않는다.

주요 설계의 이유와 구현 상태는 [ADR index](docs/portfolio/adr-index.md), AI 제안과 인간 결정의 경계는 [AI assistance and human decisions](docs/portfolio/ai-and-human-decisions.md)에 있다.

## API와 계약의 경계

- [`api/openapi-v1.yaml`](api/openapi-v1.yaml): 현재 구현된 고객 문의 접수/조회 API 계약
- [`api/core-api-outline-v1.yaml`](api/core-api-outline-v1.yaml): Customer/Agent/Admin/Audit v0.6 계약 outline; runtime 완료 여부는 코드와 release evidence로 판단
- [`api/platform-api-outline-v1.yaml`](api/platform-api-outline-v1.yaml): 후속 Platform API blueprint; 현재 endpoint가 아님
- [`api/api-surface-catalog-v0.6.yaml`](api/api-surface-catalog-v0.6.yaml), [`api/ui-route-catalog-v0.6.yaml`](api/ui-route-catalog-v0.6.yaml): 전체 계획 surface와 상태 catalog

고객 조회는 생성 응답에서 한 번 반환된 token을 URL이 아닌 header로 보낸다.

```http
X-Request-Access-Token: <opaque-token>
```

원문 token은 DB에 저장하지 않고 hash만 보존하며 기본 TTL은 30일이다. 이메일 소유권 검증, 재발급·폐기 UI와 고객 계정은 아직 제공하지 않는다.

## 보안·운영 경계

- 직원 인증은 server-side password session, CSRF, idle/absolute expiry와 DB 기반 login throttle을 사용한다. password reset, MFA, SSO/OIDC는 없다.
- 검색 원문은 authenticated ciphertext와 key version으로 보존하고 기본 30일 후 bounded retention job이 ciphertext만 삭제한다. routine audit UI에는 내용 비보존 표식과 keyed fingerprint를 사용하며, result-open session 소유권은 암호화 rotation과 분리된 고정 형식 key로 검증한다.
- runtime DB role은 canonical ledger의 `UPDATE`/`DELETE`와 schema DDL을 거부하도록 구성할 수 있다. DB owner/superuser까지 막거나 외부 변조를 증명하는 signed checkpoint는 없다.
- Compose는 TLS reverse proxy, production secret manager, email ownership, CAPTCHA, 계층형 abuse control, 중앙 log/alert를 제공하지 않는다.
- 공용 인터넷 배포는 위 통제와 [남은 security findings](docs/evidence/release/security/security-scan.md)를 운영 책임자가 해결·수용하기 전까지 지원하지 않는다.
- application log/evidence에는 password, token, Authorization header, session cookie, comment body와 raw search query를 남기지 않는다.

운영 rehearsal의 정확한 upgrade/rollback 한계, RPO/RTO 해석과 복구 순서는 [operations runbook](docs/36-self-hosted-operations-runbook.md)에 있다. 자동화 결과만으로 인간 screen-reader/visual 승인이나 production readiness를 주장하지 않는다.
Flyway migration은 forward-only이며 자동 down migration이 없다. 이번 릴리스는 이전 tagged binary와 최신 schema의 호환성을 검증하지 않았으므로 image만 되돌리는 rollback도 지원한다고 주장하지 않는다.

## 릴리스 근거

- [전체 gate 요약](docs/evidence/release/verification-summary.md)
- [security scan과 finding disposition](docs/evidence/release/security/security-scan.md)
- [dependency, advisory, license와 repository-security baseline](docs/evidence/release/supply-chain/baseline.md)
- [performance fixture, raw plans와 측정 경계](docs/evidence/release/performance/README.md)
- [install/upgrade/backup/restore evidence](docs/evidence/release/operations/README.md)
- [visual/axe/keyboard 검토 기록](docs/evidence/release/ui/automated-and-visual-review.md)
- [release evidence 생성·보존 규칙](docs/evidence/release/README.md)

Evidence에는 명령, 환경, 결과와 한계를 함께 남긴다. `NOT RUN`이나 `LIMITED`는 `PASS`가 아니며, flaky test를 재실행 횟수로 숨기지 않는다.

## 문서 읽는 순서

1. [현재 구현 경계](docs/15-seed-status.md)
2. [요구사항 추적](docs/26-requirement-traceability.md)
3. [현재 릴리스 architecture](docs/portfolio/architecture.md)와 [ADR index](docs/portfolio/adr-index.md)
4. [minimum verification gates](docs/21-minimum-verification-gates.md)
5. [release evidence](docs/evidence/release/README.md)
6. [제품 charter](docs/00-product-charter.md), [MVP PRD](docs/01-prd-mvp.md), [domain model](docs/02-domain-model.md), [장기 architecture](docs/03-architecture.md)

문서·계약 구조는 `make docs-check`로 검증한다. 이 validator는 runtime 동작을 대신하지 않는다.

## 라이선스

Deskseed 자체 코드는 root [`LICENSE`](LICENSE)의 MIT License로 배포한다.

직접 frontend runtime 의존성과 Garden 고지는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), 전체 dependency/license inventory와 알려진 한계는 [supply-chain baseline](docs/evidence/release/supply-chain/baseline.md)에 기록한다. 제3자 구성요소에는 각 구성요소의 라이선스가 적용되며, 이 안내는 법률 자문이 아니다.
