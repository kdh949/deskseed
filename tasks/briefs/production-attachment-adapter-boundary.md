# Production Attachment Adapter Boundary

Status: **IMPLEMENTATION_READY**

## Goal

운영 프로필은 로컬 임시 파일 저장소나 결정론적 테스트 스캐너를 private attachment production adapter로 사용하지 않는다.

## Decision and source references

- Decision IDs: D-018, D-037
- Accepted ADRs: ADR-0026
- Requirements: REQ-FILE-001
- API operations: 기존 attachment upload/download operations 변경 없음
- Verification gates: ARCH-001, ARCH-004, FILE-001, FILE-004, FILE-006

## Actor and source

- Actor/source/권한 모델은 기존 CUSTOMER_PORTAL, AGENT_WORKSPACE attachment 계약을 변경하지 않는다.
- 이 slice는 application composition boundary만 변경하며 HTTP authorization이나 audit event shape를 변경하지 않는다.

## In scope

- `LocalPrivateAttachmentStore`와 `DeterministicMalwareScanner`를 non-production profile로 제한한다.
- production profile에서 두 개발용 adapter가 존재하지 않는 회귀 테스트를 추가한다.
- default development/test profile의 기존 pipeline은 유지한다.

## Out of scope

- S3-compatible provider 또는 malware scanner 제품/프로토콜 선택과 외부 서비스 통합
- attachment HTTP/OpenAPI 계약 변경
- migration, object backfill, retention 정책 변경

외부 provider 선택은 deployment architecture, credential, endpoint/TLS, health, retry와 운영 책임자를 함께 확정해야 하므로 이 fail-closed slice에서 임의 결정하지 않는다.

## Invariants and failure semantics

- production은 개발용 filesystem/scanner로 조용히 fallback하지 않는다.
- production provider가 application에 포함되지 않으면 required attachment ports가 충족되지 않아 startup이 실패한다.
- default profile의 bounded quarantine, CLEAN-only link, PUBLIC/INTERNAL isolation과 audit semantics는 변하지 않는다.
- object key, file bytes, checksum, credentials는 log나 오류 메시지에 추가하지 않는다.

## Threats changed

- Tampering/unsafe content: 단순 문자열 탐지로 악성 파일을 CLEAN 처리하는 운영 오구성을 차단한다.
- Data loss: container-local `/tmp`를 durable production storage로 오인하는 구성을 차단한다.
- Security misconfiguration: production이 insecure adapter로 성공 부팅하는 대신 fail closed한다.

## Acceptance scenarios

1. Given production profile, When attachment configuration을 로드하면, Then local storage와 deterministic scanner bean이 존재하지 않는다.
2. Given default development/test profile, When attachment configuration을 로드하면, Then 기존 두 adapter가 각각 하나씩 존재한다.
3. Given 기존 attachment integration suite, When upload/link/download/cleanup을 실행하면, Then 기존 동작이 회귀하지 않는다.

## Validation

```bash
cd backend && ./gradlew test --tests '*AttachmentProductionBoundaryTest' --tests '*AttachmentPipelineIntegrationTest' --console=plain
cd backend && ./gradlew test --rerun-tasks --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_api_documentation_quality.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_documentation.py --write
```

## Compatibility and migration

- OpenAPI: 변경 없음
- Database migration/backfill: 없음
- Development/test: 기존 local adapter 유지
- Production: insecure implicit fallback 제거. production provider가 아직 application에 포함되지 않은 배포는 의도적으로 시작하지 않는다.

## Human explanation

이 PR은 새 storage/scanner 제품을 선택하는 기능 PR이 아니라 Accepted ADR의 trust boundary를 강제하는 fail-closed 보안 수정이다. 실제 provider 통합은 별도 vertical slice에서 credential/TLS/retry/health/retention 검증과 함께 진행한다.
