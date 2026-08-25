# Customer Support Home Workspace

## Goal

고객이 Deskseed 지원 홈에서 문의 번호를 바로 조회하고, 새 문의·고객 로그인 경로를 작업 공간형 정보 구조에서 찾는다.

## Decision and source references

- Decision IDs: D-006, D-030, D-032, D-053
- Accepted ADRs: 0006, 0019, 0021, 0039
- PRD/domain sections: `docs/01-prd-mvp.md`, `docs/28-frontend-product-and-information-architecture.md`, `docs/30-screen-specifications.md`
- API contract operation IDs: 변경 없음; 기존 anonymous request/customer session operations를 재사용한다.
- Verification gate IDs: UI-002, UI-004, UI-005, AUTH-003

## Actor and source

- Actor type: anonymous CUSTOMER 또는 authenticated CUSTOMER
- Source: CUSTOMER_PORTAL
- Required role/scopes: 홈·문의 접수는 익명 허용; 내 문의는 customer session 필요
- Resource constraints: 익명 조회는 해당 ticket의 browser-held `X-Request-Access-Token` proof만 사용한다.
- Interaction semantics: 문의 번호는 local form state이고, 성공 시 기존 `/requests/:ticketNumber` route로 이동한다.

## Product and UX contract

- Requirement IDs: REQ-PROD-002, REQ-TKT-001, REQ-TKT-003, REQ-AUTH-001
- Screen/route: PUB-000, `/`
- Zendesk parity: global rail + work navigation + primary task + complementary action의 정보 구조만 참고한다.
- States: lookup empty, invalid number, missing email-link proof; shell stories는 supported loading/error/denied content composition을 검증한다.
- Accessibility: skip link, named navigation/main/complementary landmarks, label-based input, visible focus, keyboard-submit.
- Responsive: customer portal은 960px 아래 단일 열, 640px 아래 horizontal rail로 재조합한다.

## In scope

- canonical design-system rail, customer support shell, lookup panel
- root customer home composition and existing lookup-page reuse
- Storybook docs/states/interactions/a11y, focused unit/E2E expectations
- design-system manifest and PUB-000 screen contract

## Out of scope

- 새 API, capability-token/조회-key 입력란, 자동 ticket discovery
- Zendesk 자산·CSS·브랜드 복제
- 기존 접수·로그인·내 문의 route의 사이트 presentation 재설계

## Invariants and failure semantics

- ticket number alone never authorizes a read.
- absent/invalid ticket-scoped sessionStorage proof does not navigate or call an API.
- capability token is never rendered as an input, query parameter, log, or persistent local storage value.
- no transaction, audit, concurrency, idempotency, or external-I/O boundary changes.

## Data and privacy

- Reads only the typed ticket number and the existing ticket-scoped sessionStorage proof.
- No new PII, secret retention, export, webhook, or API projection.
- Existing `no-referrer` customer layout policy remains active.

## Acceptance scenarios

1. Given the root route, when an anonymous customer opens it, then the named workspace regions and one inquiry-number field are visible without an eyebrow heading or token field.
2. Given an invalid number, when the customer submits, then an in-place validation state appears and navigation does not occur.
3. Given no matching browser proof, when a valid number is submitted, then the email-link-required state appears without asking for a secret.
4. Given a matching ticket-scoped proof, when the number is submitted, then the existing anonymous detail route opens.
5. Given any non-root customer route, when it renders, then the existing customer site presentation remains in use.

## Validation

- focused unit tests for home, layout, lookup, App routing, and AgentShell regression
- Storybook MCP `get-changed-stories`, `preview-stories`, focused/full `run-story-tests` with a11y
- `npm run check:design-system-boundaries`
- `npm run typecheck`
- focused `customer-portal.spec.ts`

## Compatibility and migration

- OpenAPI classification: no change
- Database migration/backfill: none
- Existing customer routes and `AgentShell` public props remain compatible.

## Human explanation

- Authorization remains in the existing browser-proof/API boundary; the new UI only composes that behavior.
- One canonical rail and lookup pattern avoids feature-local copies while preserving Deskseed branding.
- A future design change should be driven by observed task failure or responsive/a11y evidence, not proprietary pixel matching.
