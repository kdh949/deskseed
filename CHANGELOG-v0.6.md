# Documentation Seed v0.6 Changelog

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
