# Personal Staging Image Delivery

Status: **IMPLEMENTATION_READY**

## Goal

검증을 통과한 Deskseed backend/frontend 이미지를 GitHub-hosted Actions에서 하나의 커밋 SHA로 GHCR에 게시하고, 개인 서버는 같은 SHA의 repository checkout과 이미지만 사용해 on-box build 없이 production Compose를 배포한다.

## Decision and source references

- Decision IDs: D-039
- Accepted ADRs: ADR-0028
- Requirements: REQ-PROD-001
- API operations: 변경 없음
- Verification gates: OPS-001, OPS-003

## Actor and source

- Actor type: SYSTEM
- Source: GitHub Actions image publisher와 operator-owned Docker Compose deployment
- HTTP actor/source/request/correlation 계약은 변경하지 않는다.

## In scope

- main CI gate 뒤의 GitHub-hosted GHCR image publication
- backend/frontend가 같은 40자리 commit SHA tag를 사용하는 Compose override
- server checkout SHA, production env file, image availability를 검증하는 deployment script
- migration/permission one-shot job 재실행과 frontend/backend health 확인
- workflow, Compose merge, deployment refusal/ordering regression tests
- self-hosted operations runbook과 requirement evidence

## Out of scope

- GitHub Actions에서 개인 서버로 SSH하는 자동 배포
- self-hosted GitHub Actions runner
- production secret의 GitHub 저장 또는 rotation 자동화
- Sophos WAF 장비 설정, firewall rule 자동화
- PostgreSQL과 VersityGW 세 volume의 일관 backup/restore 도구
- schema downgrade 또는 자동 application rollback

## Invariants and failure semantics

- main push image publication은 기존 CI gate 성공 뒤에만 실행한다.
- backend/frontend는 mutable `latest`가 아니라 같은 exact commit SHA tag를 사용한다.
- deployment SHA는 40자리 소문자 hex이고 server `HEAD`와 같아야 한다.
- untracked file을 포함한 checkout이 dirty하면 migration/script와 image source 불일치를 막기 위해 배포를 거부한다.
- production env file은 repository 밖의 regular mode-0600 file이어야 한다.
- application image가 모두 pull되고 revision label이 요청 SHA와 일치하기 전에는 service replacement를 시작하지 않는다.
- server에서는 application image를 build하지 않고 `--no-build --pull never`로 기동한다.
- `db-migrate`와 `db-permissions`를 release마다 강제 재생성하며 실패하면 backend/frontend 성공을 반환하지 않는다.
- registry pull, migration, permission, container state 또는 health 확인 실패는 non-zero로 끝난다.
- production secret과 GHCR token은 stdout, workflow source, repository에 기록하지 않는다.

## Data and privacy

- ticket/audit/attachment schema와 데이터는 변경하지 않는다.
- image에는 source와 revision label만 기록하고 runtime secret을 build argument로 전달하지 않는다.
- production env와 Redis ACL은 기존 `/etc/deskseed` operator-owned 경계를 유지한다.

## Threats changed

- Supply-chain mismatch: image tag와 checked-out migration/script SHA 불일치를 fail closed한다.
- Secret leakage: GitHub publisher는 repository-scoped `GITHUB_TOKEN`만 사용하고 server secret을 받지 않는다.
- Availability: 동시 배포 lock, bounded pull retry, one-shot exit와 health 검증을 추가한다.
- Rollback misuse: 이전 binary/schema 호환성을 검증하지 않았으므로 자동 image rollback을 추가하지 않는다.

## Acceptance scenarios

1. Given successful main CI, When publisher가 실행되면, Then backend/frontend를 같은 `${github.sha}` tag로 GHCR에 게시한다.
2. Given personal-staging overlay, When merged Compose를 render하면, Then 두 app service에 exact SHA image가 있고 `build`가 없다.
3. Given invalid/mismatched SHA or dirty checkout, When deploy를 요청하면, Then image pull과 container replacement 전에 실패한다.
4. Given one required image가 없거나 revision label이 요청 SHA와 다르면, When deploy를 요청하면, Then 기존 application container를 교체하지 않는다.
5. Given valid checkout/env/images, When deploy를 실행하면, Then migration/permission job을 재실행하고 app을 `--no-build --pull never`로 기동한 뒤 health를 확인한다.

## Validation

```bash
bash scripts/test-personal-staging-deploy.sh
bash scripts/test-production-compose-contract.sh
make docs-check
git diff --check
```

GitHub-hosted image push와 실제 개인 서버/Sophos/backup-restore는 repository-local validation 범위 밖이며 별도 실행 증거가 필요하다.

## Compatibility and migration

- OpenAPI/Flyway migration: 없음
- 기본 local Compose와 source-build production 명령: 유지
- prebuilt path는 세 번째 `compose.personal-staging.yaml`을 명시할 때만 활성화된다.
- 기본 project name은 `deskseed`다. 기존 배포가 다른 Compose project name을 사용했다면 `DESKSEED_PROJECT_NAME`으로 동일 값을 전달해야 기존 volume을 이어 쓴다.
- rollback은 docs/36의 forward-fix/fresh-restore 계약을 유지하며, image-only 자동 rollback은 제공하지 않는다.

## Human explanation

빌드와 배포를 분리해 작은 개인 서버에서는 image pull과 migration만 수행한다. 서버 자동 접속까지 CI 권한을 넓히지 않고, source checkout·migration·image를 한 SHA로 묶는 최소 경계만 추가한다.
