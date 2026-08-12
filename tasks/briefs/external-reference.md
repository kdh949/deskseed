# Goal

상담사가 티켓을 쇼핑몰 주문·결제·환불·회원·매장·운영 객체와 안전한 참조로 연결하고 Workspace에서 확인하되, 외부 원본 데이터는 Deskseed로 복제하거나 서버에서 조회하지 않는다.

## Decision and source references

- Decision ID: D-015
- Accepted ADR: 0015
- Requirements: REQ-INT-005, REQ-UI-004
- Product/domain: docs/18 sections 7, 14, 15; docs/30 AGT-004 and INT-002; docs/32 section 5; docs/33 sections 3, 4, 11
- API operationIds: listAdminExternalSystems, createExternalSystem, updateExternalSystem, listAgentTicketExternalReferences, createAgentTicketExternalReference, deleteAgentTicketExternalReference
- Verification gates: ARCH-001/002/004, CHG-001, ACC-007, AUD-001, EXT-001/002/003/004, UI-002/004/005

## Actor and source

- ExternalSystem management: active STAFF ADMIN with `integration:systems:manage`, source ADMIN_UI.
- Reference list: active STAFF AGENT/ADMIN with current staff ticket read authorization, source AGENT_UI.
- Reference create/delete: active STAFF AGENT/ADMIN with current group-or-assignee ticket write authorization, source AGENT_UI.
- Every mutation carries authenticated actor, request ID, correlation ID, command ID, expected ticket/system version, and server Clock.

## Product and UX contract

- Screen IDs: AGT-004 Workspace Context `External` tab, INT-002 `/integrations/systems`.
- External tab has loading, empty, error, denied/read-only, validation, stale/conflict, and success states.
- External links open in a new window with `noopener noreferrer`; disabled/currently disallowed links render as text with an explanation.
- Create/delete refreshes the ticket version and reference projection without losing composer/property drafts.
- Admin registry supports loading, empty, validation, conflict, error, active/disabled state, and keyboard-accessible editing.
- Chromium visual evidence is captured at the documented Workspace widths; Firefox/WebKit run functional/axe/keyboard smoke assertions.

## In scope

- V22 ExternalSystem and ExternalReference tables, constraints, and indexes.
- Typed registry and staff reference management application APIs.
- Core admin/staff OpenAPI and UI/surface route catalog updates.
- URL/hostname and bounded metadata validation with no network fetch path.
- Atomic Ticket Change Audit for create/delete and AdminSecurityAudit for registry changes.
- Admin registry UI and Workspace External context tab.
- Unit, PostgreSQL integration, customer non-leak, component, browser, visual, and accessibility tests.

## Out of scope

- Platform API or IntegrationClient calls.
- External API proxy/fetch, redirects, link preview, DNS resolution, mirroring, synchronization, refresh jobs, and arbitrary JSON payloads.
- OAuth, webhook, generated SDK, Agent App SDK, Embed SDK, iframe, provider credentials, and deep-link templates.
- External-object deletion or mutation.

## Invariants and failure semantics

- `systemKey` is immutable, normalized lowercase, and unique; hostname allowlists contain exact canonical public DNS names only.
- Deep links are at most 2048 characters, HTTPS with default/443 port, have no userinfo/control characters, use one exact registered hostname, and never contain forbidden credential-like query keys.
- Literal IPv4/IPv6, localhost/local/internal names, wildcard hosts, private/link-local candidates, and dangerous schemes fail before persistence or audit serialization.
- Metadata has at most eight approved scalar keys, at most 2048 serialized bytes, and no object/array/null value.
- `(ticket, system, objectType, externalId)` is unique. The same external identity may link to another ticket.
- The registry and each ticket are capped at 100 entries under transaction-scoped capacity locks; the 101st create is a stable conflict and list responses are never silently truncated.
- Create/delete locks and versions the ticket, persists one TicketAudit with one ordered ExternalReference event, and commits/rolls back together.
- Registry mutation and AdminSecurityAudit commit/roll back together.
- Duplicate create is a stable conflict; stale ticket/system version is precondition failure; retry requires an intentional fresh read.
- Deleting a reference only unlinks Deskseed metadata and never calls or mutates the external system.
- Existing reference deep links are suppressed if the system becomes inactive or the current hostname policy no longer allows the stored host.
- There is no external network I/O, durable intent, outbox, or background refresh in this slice.

## Data and privacy

- Stored identity: system key/name, object type, external ID, bounded display label, validated deep link, allowlisted scalar snapshot, observed/created time, and staff actor ID.
- Snapshot keys are fixed to `status`, `storeName`, `amountDisplay`, `currency`, `occurredAt`, `ownerLabel`, and `channel`; external payloads are never accepted.
- Ticket audit copies only stable reference identity, link hostname, and metadata key names—never metadata values, URL path/query, secrets, or provider payload.
- Customer API/OpenAPI/types/DOM remain structurally free of references, IDs, metadata, and links.
- Reference rows follow ticket/support-record retention; canonical change audit follows ticket audit retention. No new log/export/webhook surface is added.

## Threats changed

- SSRF/open redirect/XSS: URL is stored-only and exact-host validated; backend has no fetch client or proxy path.
- Information disclosure: customer projections omit the feature; snapshots and audit are allowlisted and bounded.
- Tampering/repudiation: ticket/system optimistic concurrency and atomic canonical audit.
- Elevation of privilege: server-side ticket read/write and separate integration registry authority.
- Denial of service: bounded hosts, IDs, labels, list sizes, metadata properties, scalar lengths, and serialized bytes.

## Acceptance scenarios

- Given an active registered system and writable ticket, a staff actor links an ORDER and sees its label, snapshot age, and safe new-window link in External context.
- Given the same identity twice on one ticket, the second create returns conflict without another row/audit/version change; another ticket may link it.
- Given inactive system, unregistered host, HTTP/file/gopher URL, userinfo, control character, oversized URL, localhost/IP/private/link-local candidate, wildcard host, or forbidden query credential, create fails without mutation/audit/network request.
- Given forbidden metadata key, nested value, too many properties, oversized value, or serialized payload, create fails before audit.
- Given a non-writer, list remains available under ticket read scope but create/delete is denied; SECURITY_AUDITOR and anonymous/customer actors cannot use staff/admin APIs.
- Given audit persistence failure, the reference/system mutation and ticket/system version change roll back.
- Given a customer detail request and rendered portal, no external reference identifier, label, metadata, or URL is present.
- Given create/list/delete and a controlled local HTTP counter URL, the counter remains zero.

## Validation

- Backend unit/integration/full tests and Spring Modulith verification.
- Flyway clean PostgreSQL migration and V21→V22 upgrade.
- Core OpenAPI/documentation validators.
- Frontend format/lint/typecheck/component tests/build.
- Chromium full browser suite and External Context visual snapshots; focused Firefox/WebKit smoke with Axe.

## Compatibility and migration

- Additive staff/admin API and additive agent detail behavior; customer and Platform contracts are unchanged.
- Forward-only V22 with no backfill. Rollback is application rollback plus backup restore or reviewed forward fix.
- Removing the feature leaves new tables unused; no existing ticket/comment/customer row is rewritten.

## Human explanation

- Stable external identity and a small display snapshot solve agent context without making Deskseed the order/payment source of truth.
- Ticketing owns command/version/audit semantics; Integration owns external registry/reference validation and persistence through its root API.
- Exact hostname policy plus no fetch path is simpler and safer than DNS resolution, proxies, or live adapters at this stage.
- A measured need for live freshness/search/reporting would justify a separately authorized asynchronous projection, never arbitrary mirroring.
