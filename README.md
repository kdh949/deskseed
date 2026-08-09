# Deskseed

> 작업명입니다. 제품명은 아직 확정하지 않았습니다.

설치형(self-hosted) 고객지원 티켓 시스템을 Kotlin/Spring과 React로 만드는 포트폴리오용 코드베이스 시드입니다. 제품 행동은 Zendesk를 참고하되, 구현은 도메인 규칙부터 다시 설계합니다.

현재 저장소는 **M0 기반 구성 + M1 고객 웹 문의 세로 기능**과 v0.5 문서·계약 시드를 담고 있습니다. v0.5의 `IMPLEMENTATION_READY` 표기는 해당 기능의 계약이 구현을 시작하기에 충분하다는 뜻이며, 코드 구현 완료를 뜻하지는 않습니다.

```text
익명 고객이 이름/이메일/제목/문의 내용을 입력
  → Customer 생성 또는 재사용
  → Ticket 생성
  → 문의 내용은 첫 PUBLIC Comment로 저장
  → 한 번만 노출되는 조회 키 발급
  → 조회 키로 고객이 PUBLIC 대화만 조회
  → Ticket/Audit/AuditEvent가 같은 트랜잭션에서 기록
```

상담사 워크스페이스, 공개 답변, 내부 메모, 이관, 자식 티켓, 관리자 화면은 `docs/05-roadmap.md`의 M2~M6에 명세되어 있으며 아직 구현 완료로 간주하지 않습니다.

## 핵심 결정

- Backend: Kotlin 2.4, Spring Boot 4.1, Spring MVC, JPA/Hibernate, PostgreSQL
- Frontend: React 19, TypeScript, Vite
- Architecture: Spring Modulith 기반 모듈러 모놀리스
- Deployment: Docker Compose 기반 단일 설치 인스턴스
- Ticket에는 `description` 컬럼을 두지 않습니다. 문의 본문은 첫 번째 `PUBLIC` 코멘트입니다.
- 이관은 기존 티켓의 소유권 이동이고, 자식 티켓은 부모 소유권을 유지한 내부 협업입니다.
- Audit은 수정 불가능한 변경 이력이며 Event Sourcing은 아닙니다.
- 고객에게 내부 메모가 노출되지 않는 규칙은 UI가 아닌 API/쿼리 경계에서 강제합니다.
- Kafka, Redis, Elasticsearch, WebFlux, Kubernetes, MSA는 초기 MVP에 넣지 않습니다.

## 문서 읽는 순서

1. [`IMPLEMENTATION-START-HERE.md`](IMPLEMENTATION-START-HERE.md)
2. [`docs/26-requirement-traceability.md`](docs/26-requirement-traceability.md)
3. [`docs/27-implementation-handbook.md`](docs/27-implementation-handbook.md)
4. [`docs/50-codex-implementation-runbook.md`](docs/50-codex-implementation-runbook.md)
5. [`docs/00-product-charter.md`](docs/00-product-charter.md), [`docs/01-prd-mvp.md`](docs/01-prd-mvp.md), [`docs/02-domain-model.md`](docs/02-domain-model.md), [`docs/03-architecture.md`](docs/03-architecture.md)
6. [`docs/14-execution-backlog.md`](docs/14-execution-backlog.md), [`docs/15-seed-status.md`](docs/15-seed-status.md), [`docs/21-minimum-verification-gates.md`](docs/21-minimum-verification-gates.md)
7. [`AGENTS.md`](AGENTS.md)와 관련 task 문서

## v0.5 문서·계약 시드

- Core Customer/Agent/Admin/Audit API outline: [`api/core-api-outline-v1.yaml`](api/core-api-outline-v1.yaml)
- Platform API outline: [`api/platform-api-outline-v1.yaml`](api/platform-api-outline-v1.yaml)
- UI·API surface catalog: [`api/api-surface-catalog-v0.5.yaml`](api/api-surface-catalog-v0.5.yaml), [`api/ui-route-catalog-v0.5.yaml`](api/ui-route-catalog-v0.5.yaml)
- 화면·상태·권한·DB·검증 계약: [`docs/28-frontend-product-and-information-architecture.md`](docs/28-frontend-product-and-information-architecture.md)부터 [`docs/43-coverage-assessment-v03-to-v05.md`](docs/43-coverage-assessment-v03-to-v05.md)
- 후속 기능의 상세 명세: [`docs/44-sla-ola-business-hours-implementation-spec.md`](docs/44-sla-ola-business-hours-implementation-spec.md)부터 [`docs/52-admin-settings-catalog.md`](docs/52-admin-settings-catalog.md)

문서 구조와 machine-readable contract는 `python3 scripts/validate_documentation.py --write`로 검증합니다. 이 검증은 문서·계약 구조만 확인하며 애플리케이션 빌드나 런타임 동작을 보장하지 않습니다.

## 빠른 실행

### Docker Compose

```bash
cp .env.example .env
python3 scripts/verify_seed.py
docker compose up --build
```

- 고객 웹: `http://localhost:5173`
- Backend API: `http://localhost:8080`
- Health: `http://localhost:8080/actuator/health`

기동과 health 응답만 재현하려면 다음을 사용합니다. 이 명령은 검증 후 컨테이너와 named volume을 정리합니다.

```bash
bash scripts/compose-smoke.sh
```

### 로컬 개발

PostgreSQL만 실행:

```bash
docker compose up -d db
```

Backend:

```bash
cd backend
./gradlew bootRun
```

Frontend:

```bash
cd frontend
npm ci
npm run dev
```

## 검증

```bash
make docs-check
make backend-test
make frontend-check
make compose-smoke
```

`make docs-check`는 `python3 scripts/validate_documentation.py --write`로 문서와 계약을 검사하고,
생성된 보고서가 최신 상태인지 확인합니다.

## 구현된 API

```http
POST /api/v1/requests
GET  /api/v1/requests/{ticketNumber}
```

조회 API에는 생성 응답에서 받은 키가 필요합니다.

```http
X-Request-Access-Token: <opaque-token>
```

명세는 [`api/openapi-v1.yaml`](api/openapi-v1.yaml)에 있습니다. 서버가 실행 중이면 `./scripts/demo-request.sh`로 첫 세로 기능을 확인할 수 있습니다.

## 보안상 중요한 현재 한계

M1 조회 키는 학습과 세로 기능 검증을 위한 **opaque bearer token**입니다. 원문은 한 번만 반환되고 DB에는 SHA-256 해시만 저장하지만, 이메일 소유권 검증·만료·재발급·폐기 UI·rate limit·CAPTCHA는 아직 없습니다. 실제 공개 배포 전에는 `docs/05-roadmap.md`의 P1을 먼저 완료해야 합니다.

## 저장소 상태 표기

- `Implemented`: 현재 코드와 테스트가 존재함
- `Specified`: 요구사항·API·도메인 규칙이 문서로 고정됨
- `Planned`: 방향만 정했고 구현 시 ADR/PRD를 갱신해야 함

현재 정확한 구현 경계는 [`docs/15-seed-status.md`](docs/15-seed-status.md)에, 첫 구현 순서는 [`docs/14-execution-backlog.md`](docs/14-execution-backlog.md)에 있습니다.

기능을 README 문구만으로 완료 처리하지 않습니다. 수용 기준, 테스트, API 명세, 마이그레이션이 함께 있어야 합니다.

## 라이선스

아직 라이선스를 의도적으로 선택하지 않았습니다. 공개 저장소로 배포하기 전에 [`docs/13-license-decision.md`](docs/13-license-decision.md)를 검토하고 `LICENSE`를 추가해야 합니다.
프런트에 포함되는 Garden과 직접 의존성의 고지는 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)에 남깁니다.
