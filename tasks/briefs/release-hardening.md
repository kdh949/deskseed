# Release Hardening Task Brief

## Goal

구현된 Core MVP와 Audit Explorer 경로를 새 기능 추가 없이 검증하고, 동일한
결과를 로컬에서 설치·복구·성능 측정까지 재현할 수 있는 포트폴리오 릴리스로
고정한다.

## Decision and source references

- Decisions: D-001~009, D-013~014, D-018~020, D-030~033, D-039,
  D-041, D-045, D-047, D-048, D-049, D-050
- Accepted ADRs: 0001~0009, 0013, 0014, 0018~0022, 0028, 0030,
  0033, 0035, 0036
- Requirements: REQ-PROD-001/002, REQ-PORT-001, REQ-AUTH-005/006,
  REQ-PERM-001/002, REQ-TKT-001~003/006~015, REQ-CHILD-001~004/006/007,
  REQ-AUD-001~008, REQ-SRCH-001, REQ-PERF-001, REQ-UI-001~006
- API source of truth: `api/core-api-outline-v1.yaml`
- Gates: ARCH-001/002/004, TKT-001~006, CHG-001~005, ACC-001~004/007,
  AUD-001~006, PERF-001~003, UI-001~005, OPS-001~004, PERM-001/002

이 작업은 보안 검증 결과에 따라 ADR 0014의 routine human-readable query
representation만 ADR 0036/D-048로 supersede한다. 그 밖의 accepted decision은
바꾸지 않으며, 측정 결과와 운영 리허설은 PostgreSQL·모듈러 모놀리스·Docker
Compose 선택을 재검토할 근거를 추가한다.

같은 보안 검증에서 발견한 shared-cookie 교차 탭 actor 혼동은 D-050으로 고정한다.
이는 인증 source를 header로 옮기는 결정이 아니라 server session principal을 유지한
채 stale browser intent를 fail-closed하는 additive consistency guard다.

## Actor and source

| Actor | Source | 이 릴리스에서 검증하는 경로 |
|---|---|---|
| CUSTOMER | CUSTOMER_PORTAL | 익명 접수, 조회 토큰 기반 조회, PUBLIC 전용 projection |
| STAFF | AGENT_WORKSPACE | 로그인, views/search/detail, command, transfer, child |
| STAFF | ADMIN_UI | staff/group/membership 관리와 권한 거부 |
| STAFF | AUDIT_EXPLORER | list/detail/search-query reveal/export request/projection rebuild |
| SYSTEM | SYSTEM | fixture, migration, retention 및 복구 smoke에 필요한 내부 작업 |

- 활성 Agent의 staff-visible read와 group/assignee 기반 write 제약을 유지한다.
- Security Auditor는 ticket/admin mutation 권한을 얻지 않는다.
- request/interaction/correlation context와 strict access-audit 실패 의미를
  실제 HTTP 및 DB 행으로 검증한다.
- 임의 header로 actor를 승격하거나 고객 조회 범위를 넓힐 수 없다.
- 구현된 `staffSession` operation의 optional expected-actor header는 검증된 session
  principal과의 일치만 확인하며 actor를 선택하지 않는다. 로그인 bootstrap과
  post-login `/me` verification은 확인 전 단계라 header를 생략한다.

## Product and UX contract

- 실제 `AgentShell`, ticket workspace, admin, customer portal, Audit Explorer를
  사용하며 `shared/ui/system`과 Deskseed token을 재사용한다.
- OpenAPI/DTO가 request/response contract의 source of truth다. 이 작업에서 새
  interaction이나 endpoint를 발명하지 않는다.
- PUBLIC/INTERNAL draft, stale/conflict banner, loading/empty/error/denied 상태,
  keyboard focus와 axe 결과를 1280/1440/1920에서 검증한다.
- Zendesk 상표·logo·screenshot·copied CSS/assets를 추가하지 않는다.

## In scope

- 구현된 Core/Audit critical E2E와 authorization/non-disclosure regression
- strict audit failure, canonical ledger append-only, credential/query/log leakage scan
- 100k Customer/1M Ticket 및 충분한 Comment/Audit deterministic fixture
- 주요 queue/audit query의 index 전·후 `EXPLAIN (ANALYZE, BUFFERS)`와 반복 측정
- fresh Compose install, V11-to-latest migration, backup/restore, post-restore smoke
- public README, current architecture/data flow, ADR index, demo, AI/human decision log
- dependency/advisory/license/security baseline 및 알려진 제약

## Out of scope

- Kafka, Redis, Elasticsearch/OpenSearch, Kubernetes, microservice, Event Sourcing
- SLA, trigger, analytics, email/channel, attachment, custom field/macro 신규 기능
- 고객 계정/magic link와 Customer Profile 상세 화면
- Platform API, SDK, webhook 및 integration-client 경로
- protected comment-body reveal
- Audit export artifact 생성·download·expiry·deletion lifecycle. 현재 구현은
  allowlisted export **request와 self-audit만** 지원하며 artifact 상태는
  `NOT_CREATED`다.
- production deployment와 운영 SLA. Compose는 재현 가능한 single-host 기준선이다.

ACC-005/006과 미구현 reveal/export lifecycle은 critical gate의 실패를 숨기지
않고 `N/A (not implemented)` 또는 known limitation으로 릴리스 보고서에 표시한다.

## Invariants and failure semantics

- 문의 본문은 첫 PUBLIC Comment이며 customer response는 INTERNAL, child relation,
  staff-only field와 audit metadata를 포함하지 않는다.
- transfer는 현재 ticket ownership을 이동하고 child 생성은 parent ownership을
  바꾸지 않는다. 열린 child는 parent solve를 경고하지만 막지 않는다.
- assignee는 선택 group의 active member여야 한다.
- 한 ticket command의 current state, comment, ordered audit event는 한 transaction이다.
- sensitive read에서 required access/self-audit 저장이 실패하면 stable 503 problem을
  반환하고 protected payload를 반환하지 않는다.
- same-field optimistic conflict는 409이며 comment와 disjoint field까지 부분 저장하지
  않는다. 충돌 draft는 UI에 남는다.
- staff UpdateTicket의 exact command retry는 original result만 replay하며 ticket,
  operation 또는 payload가 다른 command-ID reuse는 409이고 두 번째 mutation은 없다.
- canonical audit row는 별도 runtime application role로 update/delete할 수 없다.
- 외부 network I/O를 ticket transaction에 추가하지 않는다.

## Data and privacy

- synthetic fixture만 사용하고 실행 로그에 실제 PII나 credential을 넣지 않는다.
- password, token, Authorization, session cookie, comment body와 raw query는 일반
  application log에 나타나면 안 된다.
- raw search query는 ciphertext, redacted value, keyed fingerprint 경계를 유지한다.
- evidence에는 환경, seed, 명령, commit, 행 수, timing, query plan과 sanitization
  방법을 기록한다. secret 값은 기록하지 않는다.
- backup artifact는 임시 격리 경로에서 만들고 smoke 종료 시 제거한다.

## Threats changed

- authorization/resource-scope bypass와 customer projection over-fetch
- forged actor/request context와 staff impersonation
- 교차 탭 staff session 교체 중 stale UI가 새 session principal로 command를 제출하는 actor 혼동
- strict-audit bypass 및 canonical audit update/delete
- conflict의 partial save/data loss
- token/credential/query/comment log leakage와 control-character injection
- restore 과정의 secret 노출, 잘못된 database target, 불완전한 migration
- unbounded queue/audit query와 release dataset에서의 plan regression

## Acceptance scenarios

1. Given an anonymous customer, when a request is created and read with its one-time
   returned token, then only that ticket and PUBLIC comments are visible.
2. Given staff/admin/auditor sessions, when direct URL and command permissions are
   exercised, then the authorization matrix permits only the documented operations.
3. Given PUBLIC and INTERNAL comments plus a child ticket, when the customer reads or
   guesses identifiers, then INTERNAL content, child relation and staff/audit fields are
   absent and unauthorized identifiers return the documented denial/not-found shape.
4. Given simultaneous edits, when the same field conflicts, then the second save returns
   409, persists nothing from that command and preserves both composer drafts.
5. Given an injected required-audit persistence failure, when a sensitive read or command
   occurs, then the response fails closed and state/protected response does not commit.
6. Given the runtime application DB role, when canonical audit rows are updated or
   deleted, then PostgreSQL denies or the append-only trigger rejects the operation.
7. Given the release-scale deterministic dataset, when queue and Audit Explorer queries
   run before and after candidate indexes, then raw plans, buffers, p50/p95 and storage
   trade-offs are saved with the generation command.
8. Given a clean Docker host, when the documented install/upgrade/backup/restore sequence
   runs, then health, login, ticket and audit smoke pass after restore; unsupported schema
   downgrade is explicitly blocked/documented.
9. Given the public README and release report, when a reviewer compares them to routes,
   tests and contracts, then no planned or skeleton feature is claimed complete.
10. Given tab A has confirmed staff A while a shared-cookie session becomes staff B, when
    A calls any implemented staff operation, then a malformed header returns the stable
    400 or an actor mismatch returns the stable 409 before activity renewal, controller,
    success audit or mutation. If the generation changes while A's CSRF request is held,
    the client cancels before the write and never upgrades A's intent to B.
11. Given an UpdateTicket response is lost after commit, when the browser reloads or
    refreshes and retries, then it preserves the exact command ID and payload, returns the
    original result, and writes no duplicate comment/audit. Different reuse returns 409;
    the missing dedicated misuse-attempt security event keeps IDEM-002 `LIMITED`.

## Validation

Exact commands and outputs are recorded under `docs/evidence/release/`; the intended
top-level sequence is:

```bash
make check
bash scripts/run-release-e2e.sh
bash scripts/run-release-performance.sh
bash scripts/run-operations-rehearsal.sh
```

Dependency, license and container commands are listed individually in
`docs/evidence/release/supply-chain/baseline.md`; external availability is reported per
check rather than hidden behind a nonexistent aggregate command.

Commands are added only when they are deterministic and leave their evidence path in the
repository. A missing external credential may make only the external scan `LIMITED`; it
does not make local tests or static checks pass by implication.

## Compatibility and migration

- Core OpenAPI change classification: additive optional header plus explicit 400/409
  error-contract correction on implemented `staffSession` operations; no invented endpoint.
- Flyway migrations remain forward-only. The rehearsal uses a V11 schema as the supported
  upgrade fixture and verifies latest startup separately.
- Database backup/restore is the data rollback path. Application rollback is supported
  only when the older application understands the already-migrated schema.
- V13 backfills canonical and projection `query_redacted` values to `[PROTECTED]` and
  adds content-free constraints. It does not rewrite fingerprint/ciphertext; logical
  rollback is restore/forward-fix only, and old backup/WAL remnants follow operator
  retention.
- V14 adds a non-unique partial staff audit lookup index for exact command replay. It
  changes no rows; multiple legacy matches fail closed, and V11→V14 plus the 1M-audit
  lookup plan must be present in current operations/performance evidence.

## Human explanation

- The release proves the small PostgreSQL modular-monolith design before introducing
  infrastructure or product breadth.
- Strict audit availability is retained because returning sensitive data without its
  required audit record violates D-018.
- Audit export remains a truthful skeleton because completing private artifact delivery
  would be a separate security-sensitive product slice, not release hardening.
- A human owner must decide the repository license and accept or resolve any remaining
  dependency advisory before public source distribution.

## Completion report

The release report must include changed scenarios, relied-on decisions, invariants,
actor/source and audit events, authorization constraints, failure/concurrency behavior,
privacy/retention impact, pass/fail/N/A gates, raw evidence paths and commands, migration
and rollback limits, measured performance, remaining risks, and a normal (non-auto-merge)
PR draft.
