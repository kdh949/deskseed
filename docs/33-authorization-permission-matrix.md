# Authorization and Permission Matrix

## 1. 원칙

화면 숨김은 권한이 아니다. 모든 resource 접근은 server-side policy로 검사하고, UI는 permission explanation을 받아 사용자가 왜 보거나 못 보는지 설명한다.

권한 결정 입력:

```text
actor type and status
role/grants/scopes
resource group/assignee/requester
active group memberships
ticket relation
field/comment visibility
integration resource constraints
requested operation
```

## 2. Actors

- `CUSTOMER_ANONYMOUS`
- `CUSTOMER_ACCOUNT`
- `STAFF_AGENT`
- `STAFF_ADMIN`
- `STAFF_SECURITY_AUDITOR`
- `INTEGRATION_CLIENT`
- `TRIGGER`
- `AUTOMATION`
- `SYSTEM`

## 3. Core role matrix

Legend: `A` allowed, `C` conditional, `D` denied.

| Operation | Customer anon | Customer acct | Agent | Admin | Security Auditor | Integration client |
|---|---:|---:|---:|---:|---:|---:|
| create web request | C | A | D | D | D | D |
| read own public request | token C | own A | D | D | D | D |
| add own public reply | later C | own A | D | D | D | D |
| list staff ticket queue | D | D | C | A | D by default | scope C |
| read staff ticket | D | D | C | A | D by default | scope+constraint C |
| create staff ticket | D | D | C | A | D | scope C |
| search customer/requester | D | D | C | A | D | D |
| list assignment options (groups/members) | D | D | C | A | D | D |
| add PUBLIC comment | D | own only | C | A | D | explicit scope C |
| add INTERNAL comment | D | D | C | A | D | scope C |
| update fields | D | limited later | C | A | D | scope+field C |
| transfer | D | D | C | A | D | explicit scope C |
| create child | D | D | C | A | D | explicit scope C |
| read ticket external references | D | D | staff ticket read C | A | D | D |
| create/remove ticket external reference | D | D | ticket write C | A | D | D |
| execute saved view / preview | D | D | server ticket-read C | A | D | D |
| manage PERSONAL saved view | D | D | owner only C | owner only C | D | D |
| manage SHARED saved view | D | D | D | explicit capability C | D | D |
| manage PERSONAL macro | D | D | owner only C | owner only C | D | D |
| manage SHARED macro | D | D | D | explicit capability C | D | D |
| preview/apply active macro | D | D | ticket read/write policy C | ticket read/write policy C | D | D |
| upload/link/download PUBLIC attachment | ticket scoped C | own ticket C | ticket policy C | A | D | D |
| download INTERNAL attachment | D | D | staff ticket policy C | A | D | D |
| manage staff/groups | D | D | D | A | D | D |
| read Audit Explorer | D | D | D | explicit grant C | A | D |
| reveal protected audit content | D | D | D | explicit C | separate grant C | D |
| request audit export | D | D | D | explicit C | separate grant C | D |
| rebuild audit projection | D | D | D | explicit C | separate grant C | D |
| manage integration client | D | D | D | explicit capability C | D | D |
| manage external system registry | D | D | D | explicit capability C | D | D |

## 4. Agent ticket scope

Initial policy:

- every active Agent can read every staff-visible operational ticket (`ALL_TICKETS` read scope);
- queue, direct URL, search, and parent/child navigation use the same server-side global read policy;
- customer, admin, security-audit, and secret projections remain separately protected;
- Admin can read/write all operational tickets;
- cross-group write is not implied by global read. Until explicitly changed, mutation requires current assignee or active membership in the ticket group;
- relationship grant remains modeled so restrictive modes can later allow a child group to read its parent.

Future configurable read scope:

```text
ALL_TICKETS
OWN_GROUPS
ASSIGNED_ONLY
EXPLICIT_GROUP_MATRIX
```

## 5. Group access matrix

Post-MVP admin policy:

| Grant | Meaning |
|---|---|
| `NONE` | no ticket metadata/content |
| `READ` | read staff projection and comments; no mutation |
| `READ_WRITE` | run commands allowed by other grants |

`READ_WRITE` does not automatically grant:

- staff/admin settings
- protected customer PII
- Audit Explorer
- public reply if channel policy forbids
- export
- redaction

## 6. Parent/child relation authorization

For child ticket C with parent P:

- C assignee and C group `READ` members can read P staff projection.
- They cannot mutate P unless group policy independently grants it.
- P assignee can read C because P owns the collaboration relationship.
- Customer cannot infer relation existence.
- Relation deletion is admin/authorized workflow action and audited.

## 7. Comment visibility

| Projection | PUBLIC | INTERNAL |
|---|---:|---:|
| customer anonymous/account | yes, own request only | never |
| staff ticket reader | yes | yes |
| integration read | scope/field policy | separate scope, default deny or explicit |
| webhook/export | event subscription and field policy | default redacted/metadata-only unless explicit |
| analytics | body excluded by default | body excluded by default |

Never filter internal comments only in the browser.

## 8. Customer PII permissions

Separate grants:

```text
customer:basic:read
customer:contact:read
customer:history:read
customer:export
customer:anonymize
```

Queue rows should use minimal requester label without full-profile access event. Opening full profile requires contact/history permission and emits audit.

## 9. Audit permissions

```text
audit:activity:read
audit:ticket-change:read
audit:access:read
audit:admin-security:read
audit:integration:read
audit:comment-content:reveal
audit:search-query:reveal
audit:export
```

Protected reveal requires:

- specific permission
- reason
- optional reauthentication/MFA per operator policy
- no-store response
- self-audit event

`SECURITY_AUDITOR` receives routine activity/ticket-change/access/admin-security read
authorities from the role. `audit:search-query:reveal`, `audit:export`, and
`audit:projection:rebuild` are deny-by-default and become effective only from a current
`staff_authority_grants` row. ADMIN-only grant/revoke commands are CSRF-protected,
serialized with organization mutation, and commit `STAFF_AUTHORITY_GRANTED` or
`STAFF_AUTHORITY_REVOKED` in the canonical admin/security ledger. A changed grant set
invalidates an older staff session on its next protected request.

## 9.1 Saved-view management capability

```text
saved-view:shared:manage
```

`PERSONAL` definition is owner-only and does not use this capability. `SHARED` create,
update, delete and reorder require an active `STAFF_ADMIN` plus the explicit current
capability; a SYSTEM definition is immutable to every interactive actor. View execution
uses the existing server-side ticket read policy, not this management capability.

## 9.2 Macro management capability

```text
macro:shared:manage
```

`PERSONAL` macro definition은 owner만 version 생성·활성화·비활성화할 수 있다. `SHARED`
definition lifecycle은 active `STAFF_ADMIN`과 현재 `macro:shared:manage` capability를 모두
요구한다. 활성 macro 목록은 SHARED와 현재 actor 소유 PERSONAL만 포함한다. Macro preview와
apply는 관리 capability를 티켓 권한으로 바꾸지 않으며, 대상 티켓의 일반 read/write policy,
assignment invariant, comment visibility와 configuration validation을 다시 적용한다.

## 10. Integration scopes

I1에서 발급 가능한 v1 vocabulary는 다음 네 개로 고정한다:

```text
tickets:create
tickets:read
tickets:update
tickets:comment:internal
```

Effective permission:

```text
scope ∩ resource constraint ∩ field policy ∩ resource state
```

Constraints:

- allowed group IDs
- allowed external system keys/object types
- allowed fields
- allowed comment visibility
- allowed ticket kinds
- optional network/IP policy

Configured group or ticket-kind constraint와 대조할 resource dimension이 요청에 없으면 deny한다. Constraint omission은 unrestricted를 뜻하지만 request dimension omission은 configured constraint를 우회하지 못한다.

## 11. Admin capabilities

Integration client management uses the separate `integration:clients:manage` authority. The initial ADMIN role receives it, but both the HTTP route and application-service method boundary check the authority explicitly. AGENT and SECURITY_AUDITOR are denied even on direct URLs. This does not grant Platform Ticket API access.

External system registry management independently uses `integration:systems:manage`. The initial ADMIN role receives it, while AGENT and SECURITY_AUDITOR are denied at the HTTP route and application-service method boundary. The authority can register exact HTTPS hostnames but does not grant ticket writes, Platform API access, provider credentials, or any external fetch capability. Staff reference reads follow the ticket read policy; create/remove additionally require the existing ticket write policy.

Admin role is not automatically Security Auditor in stricter deployments. Initial self-hosted MVP may allow Admin to explicitly receive audit read grants, but decision and audit must be visible.

High-risk actions:

- role/grant change
- API key issuance
- webhook secret rotation
- retention change
- protected audit reveal
- export
- content redaction/anonymization

These require reauthentication/MFA when implemented and always create admin security audit.

## 12. Permission explanation

Agent UI can receive safe denial reason codes:

```text
NOT_GROUP_MEMBER
READ_ONLY_GROUP
TICKET_OUT_OF_SCOPE
COMMENT_VISIBILITY_NOT_ALLOWED
FIELD_NOT_WRITABLE
AUDIT_PERMISSION_REQUIRED
CUSTOMER_IDENTITY_NOT_VERIFIED
```

Do not leak existence of inaccessible ticket/customer to untrusted actors; use generic not-found where needed.

## 13. Tests

For every endpoint/query/command:

- positive role test
- wrong role
- disabled actor
- direct ID guess
- inactive membership
- relation grant positive/negative
- field-level restriction
- customer internal leak regression
- integration scope + constraint intersection
- audit success/denial semantics


### Initial launch preset

```text
agentTicketReadScope  = ALL_TICKETS
agentTicketWriteScope = GROUP_OR_ASSIGNEE
```

`ALL_TICKETS` applies only to the staff-visible ticket projection. It does not grant Admin Center, Audit Explorer, raw-query reveal, export, integration-secret, retention, or customer-session permissions. When configurable permission modes ship, Admin can change the read scope without rewriting Ticket ownership data.
