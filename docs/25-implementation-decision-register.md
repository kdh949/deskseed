# Implementation Decision Register

This is a concise checklist for the owner and Codex. Accepted ADRs contain the rationale.

| ID | Decision | Status | Revisit trigger |
|---|---|---|---|
| D-001 | Kotlin/Spring Boot backend | accepted | product/team strategy changes |
| D-002 | modular monolith first | accepted | independent deploy/scale boundary measured |
| D-003 | ticket body is first public comment | accepted | no revisit planned |
| D-004 | transfer and child delegation are separate commands | accepted | no revisit planned |
| D-005 | current state + append-only audit, not Event Sourcing | accepted | audit cannot satisfy required reconstruction |
| D-006 | anonymous customer first, account later | accepted | production identity requirements |
| D-007 | field-aware optimistic concurrency | accepted | measured conflict/complexity failure |
| D-008 | PostgreSQL first for data/search/audit | accepted | measured limits |
| D-009 | one installation = one organization | accepted | SaaS decision |
| D-010 | local events before Kafka | accepted | independent durable consumers |
| D-011 | generic signed webhooks before product-specific connectors | accepted | provider UX/auth requires connector |
| D-012 | separate public Platform API adapter | accepted | no revisit planned |
| D-013 | separate audit ledgers, unified read projection | accepted | evidence shows unmanageable complexity |
| D-014 | search query HMAC + protected exact ciphertext | accepted; routine representation superseded by D-048 | privacy/security review changes |
| D-015 | ExternalReference before external data mirroring | accepted | live data use case requires projection |
| D-016 | scoped API key first, OAuth later | accepted | third-party/delegated app requirement |
| D-017 | sandboxed Agent App SDK later, no backend plugin execution | accepted | isolated plugin runtime strategy approved |
| D-018 | strict audit persistence for sensitive reads/writes | accepted | explicit availability/compliance policy change |
| D-019 | Security Auditor is read-only by default | accepted | organization needs dual role with explicit grant |
| D-020 | audit view/reveal/export is audited | accepted | no revisit planned |
| D-021 | integration client cannot impersonate staff | accepted | verified delegated OAuth only |
| D-022 | Platform API writes require idempotency | accepted | no revisit planned |
| D-023 | external update uses ETag/If-Match | accepted | alternative standard chosen before v1 release |
| D-024 | webhook is at-least-once and duplicate-safe | accepted | no revisit planned |
| D-025 | SDK generated from OpenAPI | accepted | no revisit planned |
| D-026 | raw search query retention operational default 30 days | provisional | operator policy/legal review |
| D-027 | access metadata retention proposal 180 days | provisional | operator policy/storage review |
| D-028 | app ticket sidebar is first extension location | provisional | real extension use case |
| D-029 | embed SDK begins read/create, not full editor | provisional | external admin workflow evidence |

| D-030 | Garden components with independent Deskseed branding | accepted | Garden/license or product identity changes |
| D-031 | resizable three-panel Agent Workspace | accepted | measured workflow/usability failure |
| D-032 | separate server/URL/draft/layout frontend state | accepted | state complexity evidence |
| D-033 | views/tags/custom fields/macros/search after core commands | accepted | no revisit planned |
| D-034 | SLA policy snapshots and rebuildable intervals | accepted | metric model evidence changes |
| D-035 | typed ordered automation, no arbitrary code | accepted | isolated script runtime/security model approved |
| D-036 | PostgreSQL read projections before external stores | accepted | measured functional/latency limits |
| D-037 | private object storage attachment pipeline | accepted for P8 | attachment requirements change |
| D-038 | email as Ticket/Comment channel adapter | accepted for P8 | channel model evidence changes |
| D-039 | Docker Compose first supported self-hosted topology | accepted | Kubernetes demand and ops owner exist |

| D-040 | customer authentication starts with DB-backed email magic links | superseded by D-057 for authentication method; token/session/claim boundaries retained | password/SSO requirement activated |
| D-041 | all active agents initially read all staff-visible tickets | accepted | operator requests restrictive mode |
| D-042 | cross-group write remains group-or-assignee until explicitly decided | provisional | product owner decision |
| D-043 | Platform API v1 is private-network scoped-key create/read/update/internal-comment | accepted | public/delegated API requirement |
| D-044 | First Reply SLA uses configurable schedule; seed Mon–Fri 09–18 Asia/Seoul and PENDING pause | accepted | operator policy change |
| D-045 | exact search query is required authenticated ciphertext with 30-day default retention | accepted | legal/operator retention review |
| D-046 | Mailpit is the development outbound-mail adapter; production provider later | accepted | production email rollout |
| D-047 | staff auth uses server sessions, CSRF, PostgreSQL lockout, and password-file bootstrap | accepted | MFA/SSO or horizontally scaled session requirement |
| D-048 | routine search audit stores a content-free marker; exact meaning requires protected reveal | accepted | investigation usability or privacy evidence changes |
| D-049 | Core staff UpdateTicket exact replay reuses immutable ticket audit metadata plus a database advisory lock; V14 adds a non-unique partial lookup index and multiple matches fail closed | accepted | command retention, multi-ticket audit shape, or measured lookup limits change |
| D-050 | Staff browser requests carry a realm-local expected-actor consistency guard that is compared with, but never selects, the authenticated server-session principal | accepted | non-cookie session isolation or browser-context ownership model changes |
| D-051 | Search-origin fingerprints use an encryption-independent fixed-format key; audit projection rebuild copies canonical event-time snapshots under a shared/exclusive PostgreSQL lock | accepted | session isolation or projection storage architecture changes |
| D-052 | Organization mutations and membership-dependent staff ticket commands share one PostgreSQL transaction consistency guard | accepted | measured command contention justifies ordered keyed locks |
| D-053 | The current frontend ships Agent Queue/read-only Workspace only and preserves deferred capabilities as OpenAPI/headless contracts under one canonical design system | accepted | a deferred capability is recomposed as a current-design vertical slice |
| D-054 | Committed OpenAPI remains authoritative; Scalar renders manually owned Korean domain documentation, while springdoc runtime output is drift evidence only and validators never generate inferred prose | accepted | contract ownership or API delivery model changes |
| D-055 | Parallel Wave delivery used delivery-time ownership reservations; the durable contract is owned Core OpenAPI fragments plus a deterministic committed bundle, and typed workflow extensions fail closed outside central switches | accepted | a different contract composition or extension boundary is approved |
| D-056 | Ticket configuration uses typed EAV values, immutable form versions, server-authoritative conditional projection, normalized tags, and custom labels mapped to fixed status categories | accepted | field type set, query evidence, or status/state-machine policy changes |
| D-057 | Customer authentication is password-primary; magic-link login is passwordless-only and registration activation also requires browser-bound continuation proof | accepted | SSO/MFA requirement, supported-hardware password benchmark failure, or credential-policy change |
| D-058 | Customer consent policies use administrator-managed immutable published versions and append-only acceptances bound to the accepted version; P0 publish is immediate and server-owned `effectiveAt` equals `publishedAt` | accepted | legal withdrawal/renewal policy, scheduled activation, or document-format requirement changes |
| D-059 | Customer request submission binds the current server-authorized form/version and normalized typed values; candidate projection is never an authorization token; a stable client command identity makes initial creation single-winner and logically replayable without retaining a raw access capability | accepted | form lifecycle, field type set, deployed-client compatibility, or request-replay capability policy changes |

D-049는 새 receipt row/table이나 comment raw/full-payload hash를 추가하지 않고 canonical ticket-audit retention을 그대로 따른다.
IDEM-002의 409/no-mutation 하위 조건은 구현하지만 rejected reuse attempt 자체의 requestId/security-event durable linkage는 아직 없다.
원 receipt의 actor/command ID는 추적 가능하나 gate 전체 평가는 `LIMITED`이며 별도 audit 요구가 승인될 때 재검토한다.

D-050의 `X-Deskseed-Expected-Staff-Id`는 optional defense-in-depth header다. 일반 staff read/CSRF/write는
한 logical operation 시작 시 confirmed actor와 generation을 한 번 snapshot해 끝까지 사용한다. 로그인 CSRF/POST와
post-login `/me` verification은 새 identity를 아직 확인하지 않았으므로 생략한다. 서버는 session principal을 먼저
검증하고 header가 있으면 canonical UUID·동일 actor인지 activity 갱신과 controller 실행 전에 비교한다. mismatch는
server session이나 다른 탭 owner marker를 폐기하지 않고 stale tab UI만 fail-closed한다. 구현된 `staffSession`
operation은 OpenAPI에 standard header parameter와 `400 /problems/invalid-staff-session-actor`,
`409 /problems/staff-session-actor-mismatch`를 직접 노출한다. blueprint-only operation은 policy extension을 따르되
구현 계약을 동결할 때 같은 operation-level binding을 추가한다.

## How to use this register

Before a Codex task:

1. list the decision IDs it relies on;
2. state whether it changes any decision;
3. if yes, create/update ADR before code;
4. include verification gate IDs;
5. record the final decision and evidence in the PR.
