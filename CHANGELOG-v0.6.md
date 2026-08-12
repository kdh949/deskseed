# Deskseed v0.6 Changelog

## Portfolio release candidate — 2026-08-12 (unreleased)

This release candidate turns the implemented Core MVP and Audit Explorer slices into a
reproducible portfolio build. It does not claim the later blueprint-only capabilities
listed below as shipped product behavior.

### Implemented release scope

- Anonymous request creation and access-token lookup with a customer-safe projection.
- Staff session authentication, ticket views/search/workspace, PUBLIC and INTERNAL
  comments, optimistic-concurrency conflicts, transfer, child tickets, and group/admin
  administration.
- Strict sensitive-read auditing, append-only canonical audit tables, Audit Explorer
  list/detail/search-query reveal, self-audit, and projection parity checks.
- V13 search-audit migration: ordinary audit rows now use the content-free
  `[PROTECTED]` marker while reason-gated encrypted reveal and keyed fingerprints remain.
- V14 staff-command replay lookup index plus exact UpdateTicket replay: an ambiguous
  browser retry keeps the same command ID and payload, exact replay returns the original
  result, and ticket/payload/operation reuse fails 409 without a second mutation.
- Staff draft ownership and 12-hour expiry, with fail-closed session invalidation on
  logout/global 401/account change; draft purge is best effort when browser storage is
  available, including cross-tab invalidation.
- Reproducible cross-browser, accessibility, real-stack E2E, release-scale PostgreSQL
  performance, supply-chain, and clean install/upgrade/backup/restore harnesses. Their
  executed status is recorded separately in `docs/evidence/release/verification-summary.md`.

### Security and reliability changes

- Unified strict-audit persistence failures on `/problems/audit-write-unavailable` and
  verified that protected ticket, comment, context, and search-result data are absent
  from failure responses.
- Sanitized historical plaintext-equivalent search labels in canonical and projection
  rows; migration keeps fingerprints/ciphertext intact and restores immutable triggers.
- Removed Spring Boot's unused generated user credential and its startup-log disclosure.
- Kept canonical audit inserts available to the runtime role while denying
  update/delete/truncate/references/trigger/maintain privileges; the runtime role has no
  privilege on Flyway schema history, with source and restored-database probes.
- Added atomic stale-command regression coverage so mixed field/comment conflicts leave
  ticket, comment, and audit state unchanged.
- Added a realm-local expected-staff-actor guard across implemented staff HTTP operations.
  The server compares it with the authenticated principal before activity extension,
  controller entry, mutation, or success audit; it never uses the header to choose an
  actor. One snapshot spans CSRF acquisition and its unsafe request.
- Disabled Playwright retries in CI so intermittent failures cannot pass by retry.

### Known limitations

- This repository is a local/private-network portfolio candidate, not an Internet-ready
  production distribution. Base Compose does not provide TLS, a production manifest, or
  migration/runtime role wiring.
- Customer profile access (`ACC-005`), Platform API reads (`ACC-006`), protected comment
  reveal, and the complete audit-export artifact lifecycle are not implemented.
- Email ownership verification, durable/global abuse controls, trusted-proxy policy, and
  explicit high-risk auditor grant administration remain required before public exposure.
- V13 cannot immediately erase plaintext remnants from historical backups, WAL, or
  physical pages; those remain bounded by operator retention, rotation, and media policy.
- Rejected command-ID reuse has no dedicated durable request-ID/security event. Duplicate
  mutation prevention passes, but full IDEM-002 misuse-attempt observability is `LIMITED`.
- A same-account cross-tab session replacement that never changes/removes the stored actor
  ID cannot be distinguished by the browser guard; normal logout/expiry transitions are
  covered.
- Database migrations are forward-only. Reverting requires a previously retained binary
  compatible with the migrated schema; no prior tagged binary is shipped here.
- WAL archiving/PITR, attachment backup/restore, Kubernetes, SLA, automation, analytics,
  and email delivery are outside this release scope.
- No project-wide `LICENSE` has been selected. `THIRD_PARTY_NOTICES.md` records the
  dependency license baseline but does not grant a license to this repository's code.

## Documentation Seed v0.6 (historical)

## Added

- Canonical Core API OpenAPI outline with 25 customer/staff/admin/audit operations.
- Detailed SLA/OLA, automation, analytics, ticketing-depth, attachment/content, and email/channel specifications.
- Codex implementation runbook with Definition of Ready/Done and release trains.
- Zendesk parity and visual acceptance register.
- Typed Admin Settings catalog.
- ADRs 0019–0028 for frontend, SLA, automation, projections, files, email, and self-hosted operations.
- Additional requirement rows for views/tags/custom fields/macros, files, channels, notifications, and deferred AI assistance.

## Changed

- Removed duplicated competing v0.4 documents and established one canonical numbered document set.
- Renamed API/UI catalogs to v0.6.
- Updated requirement references to detailed implementation specs.
- Expanded frontend guidance from visual resemblance to task-level interaction parity and legal/brand boundaries.

## Clarified

- v0.3 was not sufficient for the entire implementation by itself.
- v0.6 is implementation-ready for Core MVP and blueprint-ready for later capabilities; it is not product code.
- Garden licensing does not grant rights to Zendesk trademarks or proprietary assets.
## Decision update — 2026-08-10

- Accepted email magic-link customer authentication and explicit anonymous-request claim.
- Changed initial agent read scope to all staff-visible tickets; cross-group write remains a separate decision.
- Froze private-network Platform API v1 operations.
- Froze configurable business schedules and First Reply SLA defaults.
- Required encrypted preservation of original search queries.
- Selected Mailpit as the development outbound-email adapter.
- Added docs 53–54, ADRs 0029–0034, and tasks 20–25.


## Contract hardening

- Added the Customer Identity API v1 contract for magic-link request/consume, logout, and explicit request claim.
- Reduced Platform API v1 to the accepted private-network operations only.
- Added explicit verification gates for authentication, global agent read, encrypted search-query preservation, Mailpit, private Platform API, schedule administration, and PENDING pause.
- Set the encrypted raw-search retention product default to 30 days, administrator-configurable; this is not a legal retention conclusion.
