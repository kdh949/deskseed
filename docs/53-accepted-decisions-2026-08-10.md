# Accepted Decisions — 2026-08-10

Status: **Normative decision overlay for Documentation Seed v0.6**

이 문서는 2026-08-10에 확정된 여섯 가지 정책값을 기존 정본 문서에 적용한 결과를 한곳에 요약한다. 충돌이 있으면 이 문서와 아래 ADR이 이전의 proposed default보다 우선한다.

## 1. Customer authentication

**Decision:** 고객 계정 인증은 email magic link로 시작한다.

Implementation contract:

- Spring Security One-Time Token 로그인 흐름을 사용하거나 동일한 보안 계약을 만족하는 adapter를 둔다.
- 개발·운영 모두 DB-backed one-time token store를 사용한다. in-memory token store는 테스트 외 금지한다.
- token은 single-use, 기본 TTL 15분, 관리자 설정 가능 범위 5–60분이다.
- 로그인 요청 응답은 이메일 존재 여부와 무관하게 동일하다.
- magic link 발송은 durable outbound-email intent를 거친다.
- 고객 세션은 HttpOnly/Secure/SameSite cookie를 사용한다.
- 같은 이메일이라는 이유만으로 과거 익명 티켓을 자동 claim하지 않는다.
- 기존 익명 티켓 claim은 기존 request access token 또는 별도의 signed claim link 확인 후 수행한다.

Related ADR: `0029-email-magic-link-customer-authentication.md`.

## 2. Initial staff ticket visibility

**Decision:** 초기에는 모든 활성 상담사가 모든 staff-visible 티켓을 읽을 수 있다.

Implementation contract:

- default read scope는 `ALL_TICKETS`다.
- PUBLIC 및 INTERNAL comments가 포함된 staff projection을 읽을 수 있다.
- 고객용 projection과 감사·관리자 전용 projection은 이 결정과 무관하게 계속 분리한다.
- 검색과 Views도 전역 읽기 범위에서 결과를 반환하며, 열람·검색 audit을 남긴다.
- 향후 관리자가 `ALL_TICKETS`, `OWN_GROUPS`, `ASSIGNED_ONLY`, `EXPLICIT_GROUP_MATRIX`를 선택할 수 있도록 policy seam을 유지한다.
- parent-child relationship grant는 초기 전역 읽기에서는 중복처럼 보이지만 제한 모드로 전환할 때 필요하므로 제거하지 않는다.

**Not decided:** 다른 그룹 티켓에 대한 write/comment/transfer 권한. v0.6의 보수적 기본값은 현재 담당자이거나 티켓 그룹의 활성 멤버인 상담사만 변경할 수 있다는 것이다. 이 값은 별도 결정으로 바꿀 수 있다.

Related ADR: `0030-all-agents-read-all-tickets-initially.md`.

## 3. Platform API v1

**Decision:** 사설망에서 scoped API key를 사용하는 Platform API v1을 구현한다.

Initial operations:

```text
create ticket
read ticket
update ticket fields
add INTERNAL comment
```

Create semantics:

- `CUSTOMER_REQUEST`: requester가 필요하고 `message`는 첫 `PUBLIC` comment가 된다.
- `INTERNAL_WORK_ITEM`: `message`는 첫 `INTERNAL` comment가 된다.
- 후속 comment endpoint는 v1에서 `INTERNAL`만 허용한다.

Required controls:

- `/api/v1/platform/**` 별도 API surface
- `IntegrationClient` machine actor
- scoped API key + resource constraints
- no staff impersonation
- `Idempotency-Key` for command endpoints
- `ETag`/`If-Match` for updates
- default 60 requests/minute/client, admin configurable
- trusted proxy/CIDR aware private-network deployment contract
- every read/write access and mutation attributed to the integration actor

Related ADR: `0031-private-platform-api-v1.md`.

## 4. First Reply SLA and business schedule

**Decision:** First Reply SLA부터 시작하며 기본 schedule은 Asia/Seoul, 월–금 09:00–18:00이다. `PENDING` 동안 target clock은 정지한다.

Admin configurability:

- IANA timezone
- each weekday enabled/disabled
- zero or more intervals per weekday
- weekend intervals
- holidays and exceptional open/closed intervals
- First Reply target by priority
- pause statuses
- activation and version history

No SLA policy is active until an admin supplies target times and activates a policy. Schedule edits create a new version and do not rewrite historical target instances.

Related ADR: `0032-configurable-business-schedule-first-reply-sla.md`.

## 5. Raw search-query audit

**Decision:** 원문 검색어를 보존한다.

Security interpretation:

- recoverable original text is stored as authenticated ciphertext, never as a plaintext database column.
- every event also stores a redacted form and keyed HMAC fingerprint.
- raw storage mode is `REQUIRED_ENCRYPTED`; missing encryption key is a startup/configuration failure when access audit is enabled.
- key lives outside the database and has an explicit key version.
- default raw retention is 30 days and admin configurable.
- reveal requires `audit:search-query:reveal`, a reason, `Cache-Control: no-store`, and self-audit.
- no bulk reveal in the initial release.
- plaintext never enters application logs, analytics projections, caches, webhook payloads, or ordinary exports.

Related ADR: `0033-required-encrypted-raw-search-query-audit.md`.

## 6. Email development adapter

**Decision:** development uses Mailpit; production delivery uses the same provider-neutral SMTP adapter only when an operator explicitly enables and provisions it.

Implementation contract:

- Docker Compose includes Mailpit SMTP and web UI.
- default internal development endpoints: SMTP `mailpit:1025`, UI `localhost:8025`.
- the application depends on an `OutboundMailPort`, not Mailpit-specific code.
- first templates: customer magic link, request received, public agent reply.
- email intent is committed before network delivery; retries are duplicate-safe.
- INTERNAL comments never generate outbound customer email.
- Mailpit REST API may be used by integration tests to assert recipient, subject, and link content.
- production stays disabled by default. Enabling delivery requires SMTP host/port/auth/credentials, required TLS, a sender mailbox, an HTTPS public base URL and active protected-mail key validation before the worker starts; host/credentials/provider responses are not exposed by ADMIN operations.
- full inbound email ticket creation remains blueprint-ready until a production ingestion boundary and threading policy are chosen.

Related ADR: `0034-mailpit-development-outbound-mail-adapter.md`.
