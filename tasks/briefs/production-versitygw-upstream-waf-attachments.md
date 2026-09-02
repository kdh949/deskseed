# Production VersityGW and Upstream WAF Attachments

Status: **IMPLEMENTATION_READY**

## Goal

Production attachment bytes를 private VersityGW S3-compatible storage에 bounded multipart로 저장하고, Sophos WAF가 유일한 upload 경로에서 검사한 요청만 origin에 전달한다는 명시적 신뢰 경계로 application 내부 이중 검사를 제거한다.

## Decision and source references

- Decision IDs: D-018, D-037, D-039
- Accepted ADRs: ADR-0018, ADR-0026, ADR-0028
- Requirements: REQ-FILE-001, REQ-PROD-001
- API operations: 기존 multipart create/upload와 authorized download operation 변경 없음
- Verification gates: ARCH-001, ARCH-004, FILE-001, FILE-003, FILE-004, FILE-006, OPS-003

## Actor and source

- CUSTOMER/STAFF actor, request/correlation context, PUBLIC/INTERNAL authorization은 기존 계약을 유지한다.
- `UPSTREAM_WAF`는 scanner source로 security audit metadata에 기록한다.
- Sophos policy와 origin firewall은 operator-owned deployment source다.

## In scope

- AWS SDK v2 S3-compatible private adapter
- 8 MiB bounded multipart upload, private read, delete, startup bucket validation/create
- VersityGW v1.4.1 POSIX backend와 전용 internal object-storage network/volumes
- production-only `UPSTREAM_WAF` scanner source와 acknowledgement
- app scanner가 object bytes를 다시 읽지 않는 경로
- Sophos upload AV, unscannable block, size, origin bypass 운영 계약
- 20 MiB/file, 105 MiB/request Nginx/Spring/Sophos size 계약
- lease claim transaction, post-commit S3 delete, completion/audit transaction 경계

## Out of scope

- application 내부 malware engine 또는 scanner provider
- VersityGW IAM multi-user/admin WebUI, TLS, replication, external S3 provider
- object-storage backup/restore drill, central logs, metrics, alerts
- WAF 장비 자동 구성 또는 rule 검증

## Invariants and failure semantics

- S3 bucket과 credential validation이 실패하면 production Backend startup이 실패한다.
- unknown-length upload는 전체 byte array로 변환하지 않고 최대 8 MiB part 단위로 전송한다.
- multipart 실패/size limit은 upload를 abort하고 attachment를 CLEAN으로 만들지 않는다.
- stored bytes는 digest/MIME 확인을 위해 읽히지만 변환되지 않는다.
- `UPSTREAM_WAF` mode와 acknowledgement가 없으면 trusted scanner bean이 생기지 않는다.
- WAF upload AV, block-unscannable, request-size coverage, origin source restriction 중 하나라도 충족하지 않으면 acknowledgement를 true로 설정하면 안 된다.
- MIME-family/size/owner/visibility/CLEAN-only link 정책은 유지한다.
- retention S3 delete는 DB transaction/row lock 밖에서 실행하고 실패한 claim은 release 또는 lease expiry 후 재시도한다.

## Data and privacy

- bytes는 PostgreSQL이나 public URL에 저장하지 않고 private VersityGW volume에 저장한다.
- endpoint credential과 object key를 ordinary log/audit metadata에 추가하지 않는다.
- security audit는 `scanSource=UPSTREAM_WAF`, size, digest prefix만 기록한다.
- internal S3 HTTP는 같은 Docker host root/daemon 또는 container escape에 plaintext로 관찰될 수 있다.

## Threats changed

- Data loss: container-local temp filesystem 대신 named persistent VersityGW volumes를 사용한다.
- Denial of service: multipart buffer를 8 MiB로 제한하고 기존 server-side upload bound를 유지한다.
- Malware bypass: WAF가 보호하는 upload route와 origin source 제한을 acknowledgement 조건으로 만든다.
- Security misconfiguration: external HTTP S3 endpoint와 acknowledgement 없는 Versity/WAF mode를 fail closed한다.

## Acceptance scenarios

1. Given VersityGW, When 8 MiB 초과 object를 upload/read/delete하면, Then exact bytes와 lifecycle이 보존된다.
2. Given production Compose, When model을 검사하면, Then VersityGW는 host port 없이 Backend와 전용 internal network만 공유한다.
3. Given `UPSTREAM_WAF` mode without acknowledgement, When context를 시작하면, Then scanner composition이 실패한다.
4. Given acknowledged upstream mode, When upload를 처리하면, Then object를 다시 application scanner로 읽지 않고 CLEAN transition에 `UPSTREAM_WAF` source를 기록한다.
5. Given HTTP multipart, When Servlet 기본 request limit보다 큰 11 MiB 파일을 upload하면, Then Nginx/Spring production 계약 안에서 controller까지 전달된다.
6. Given retention delete/audit failure, When cleanup을 실행하면, Then remote I/O는 DB transaction 밖에서 실행되고 claim은 안전하게 release 또는 lease-expiry retry 상태로 남는다.

## Validation

```bash
cd backend && ./gradlew --no-daemon test --tests 'dev.deskseed.attachments.internal.*'
bash scripts/test-production-compose-contract.sh
make docs-check
git diff --check
```

## Compatibility and migration

- OpenAPI 변경 없음. V86은 attachment cleanup claim/lease/attempt column과 bounded cleanup index를 additive하게 추가한다.
- 기존 local/test filesystem와 deterministic scanner: 유지
- production: VersityGW/S3와 upstream WAF acknowledgement가 새 필수 구성
- rollback: 이전 image/Compose로 전환해도 S3 objects/volumes는 자동 삭제하지 않는다.

## Human explanation

Sophos WAF를 실제 scanner로 신뢰하되 그 사실을 코드·설정·audit에 드러낸다. 파일은 변환하지 않고 그대로 private object storage에 저장하지만 기존 size/MIME/digest와 CLEAN-only comment link 검사는 계속 적용한다.
