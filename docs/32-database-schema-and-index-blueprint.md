# Database Schema and Index Blueprint

## 1. 목적

이 문서는 JPA entity를 그대로 설계하는 문서가 아니라 PostgreSQL에서 지켜야 할 데이터 관계, 제약, 인덱스, 보존 경계를 정의한다. 실제 Flyway migration은 기능 slice마다 생성한다.

## 2. ID와 시간

- 내부 PK: UUID 또는 ULID 중 하나를 ADR로 고정.
- 외부 노출 ticket number: 증가 bigint sequence.
- 모든 시간: `timestamptz`, UTC.
- 비즈니스 코드: 주입된 `Clock`.
- 모든 mutable aggregate: `version bigint not null`.

## 3. Core tables

Audit projection note: V15 adds immutable `actor_display_snapshot`/`group_id` facts to canonical ticket/access audit rows and `projected_count` to projection state. The one-time backfill is the best value available at migration time; rebuilds must not join mutable staff or ticket ownership to reinterpret older events.

### customers

```text
id
primary_email_normalized
name
email_verified_at nullable
status
created_at
updated_at
version
```

Constraints/indexes:

- normalized email index.
- 미검증 email은 identity proof가 아니므로 중복을 허용하고 문의마다 별도 Customer를 만든다.
- `verified_at is not null`인 행만 normalized email partial unique index로 보호한다.
- 익명 profile 자동 병합/claim은 금지하며 verified ownership을 확인하는 명시적 후속 흐름만 연결할 수 있다.

### customer_accounts

```text
id
customer_id
provider
provider_subject
status
created_at
last_login_at
```

Unique `(provider, provider_subject)`.

### customer_one_time_tokens

```text
id UUID PK
username_or_account_key
token_hash or framework-safe token value storage
created_at
expires_at
consumed_at nullable
request_ip_hash nullable
```

- production uses DB-backed storage; in-memory only in unit tests.
- single successful consume.
- expiry and replay indexes/cleanup.
- application logs never contain token.

### staff_accounts

```text
id
email_normalized
name
role
status
password_hash/identity_subject
created_at
updated_at
last_login_at
version
```

### groups / group_memberships

```text
groups(id, name, status, created_at, updated_at, version)
group_memberships(group_id, staff_id, status, created_at, updated_at, version)
```

- `lower(btrim(groups.name))` is unique.
- A membership pair has one mutable row and an optimistic version.

### staff_authority_grants

```text
id
staff_id
authority             AUDIT_SEARCH_QUERY_REVEAL | AUDIT_EXPORT | AUDIT_PROJECTION_REBUILD
granted_by_staff_id
granted_at
unique(staff_id, authority)
```

- only active `SECURITY_AUDITOR` targets are accepted by the application transaction.
- this table stores current effective grants; canonical grant/revoke history is kept in `AdminSecurityAuditEvent`.
- default Security Auditor identity contains routine read authorities only.

### tickets

```text
id
ticket_number
kind                 CUSTOMER_REQUEST | INTERNAL_CHILD | AGENT_CREATED | INTERNAL_WORK_ITEM
requester_customer_id nullable
subject
status
priority
group_id nullable
assignee_staff_id nullable
channel
source_type
source_id nullable
version
created_at
updated_at
solved_at nullable
closed_at nullable
```

의도적 부재:

```text
description
parent_id
```

Constraints:

- assignee가 있으면 group 필수.
- assignee membership 검증은 transaction application rule + deferred integrity test.
- CLOSED는 system-only.

Initial indexes:

```text
unique(ticket_number)
(status, updated_at desc, ticket_number desc)
(group_id, status, updated_at desc, ticket_number desc)
(assignee_staff_id, status, updated_at desc, ticket_number desc)
(requester_customer_id, created_at desc)
(priority, status, updated_at desc)
```

실제 인덱스는 query plan 근거로 조정한다.

### ticket_comments

```text
id
ticket_id
sequence_number
author_type
author_id nullable
visibility
body_format
body
source_type
source_id nullable
created_at
```

- unique `(ticket_id, sequence_number)`.
- MVP comment immutable.
- body full-text index는 검색 단계에서 결정.

### ticket_relations

```text
id
source_ticket_id
target_ticket_id
relation_type
created_by_actor_type
created_by_actor_id
created_at
```

- unique relation.
- self relation 금지.
- MVP는 `PARENT_CHILD` depth 1.

### ticket_audits / ticket_audit_events

```text
ticket_audits(
  id, ticket_id, actor_type, actor_id, source,
  request_id, correlation_id, causation_id,
  expected_version, ticket_version (result version), occurred_at
)

ticket_audit_events(
  id, audit_id, ordinal, event_type,
  field_name nullable,
  old_value_json nullable,
  new_value_json nullable,
  metadata_json,
  occurred_at
)
```

- unique `(audit_id, ordinal)`.
- UPDATE/DELETE 권한 제거와 DB trigger.
- ticket change와 같은 transaction.

## 4. Access/Security audit

### activity_audit_events

```text
id
ledger_type
activity_type
actor_type
actor_id nullable
target_type
target_id nullable
ticket_id nullable
customer_id nullable
group_id nullable
source
result
interaction_id nullable
search_session_id nullable
request_id
correlation_id nullable
ip_address_encrypted_or_masked nullable
user_agent_hash_or_text nullable
summary_json
occurred_at
```

Indexes:

```text
(occurred_at desc, id desc)
(actor_id, occurred_at desc)
(ticket_id, occurred_at desc)
(activity_type, occurred_at desc)
(search_session_id, occurred_at)
(request_id)
(correlation_id)
```

대량화 시 `occurred_at` range partition 검토.

### search_audit_details

```text
activity_event_id
query_redacted
query_fingerprint
query_key_version
normalized_filter_json
sort
result_count
```

### search_audit_result_items

```text
activity_event_id
ticket_id
ticket_number
result_ordinal
```

서버가 반환한 bounded result membership을 불변 child metadata로 보존해 search-result open linkage를 client 주장만으로 만들지 않는다.

### search_audit_query_ciphertexts

```text
activity_event_id
key_version
query_ciphertext required
created_at
expires_at
```

민감 원문 ciphertext를 기본 activity/search metadata와 분리하고 별도 단기 retention으로 삭제한다. 평문 query column은 두지 않는다.

## 5. Integration tables

### integration_clients / credentials

```text
integration_clients(
 id, name, description, status, scopes_json, constraints_json,
 created_by_staff_id, created_at, updated_at, last_used_at, last_used_ip, version
)
integration_credentials(
 id, client_id, sequence, public_key_id, secret_hash, status,
 expires_at, overlap_expires_at, rotated_from_credential_id,
 created_by_staff_id, created_at, revoked_at, last_used_at, last_used_ip, version
)
```

- 원문 secret column은 두지 않고 발급/회전 응답에서만 한 번 반환한다.
- `public_key_id`는 unique locator이고 `secret_hash`는 salt가 포함된 slow verifier다.
- status는 client `ACTIVE/DISABLED/REVOKED`, credential `ACTIVE/RETIRING/REVOKED`로 제한한다. `EXPIRED`는 시간에서 계산하는 projection 상태다.
- client마다 ACTIVE credential은 최대 1개, RETIRING credential은 최대 1개인 partial unique index로 bounded-overlap rotation을 보장한다.
- `expires_at > created_at`, RETIRING에만 `overlap_expires_at` 존재, revoke metadata 정합성을 check constraint로 보호한다.
- scopes/constraints JSON shape를 DB constraint로 검증하고 지원 scope vocabulary는 application/OpenAPI enum으로 고정한다.
- V18 migration이 이 구조와 actor/source audit enum 확장을 소유한다.

### external_systems / external_references

```text
external_systems(
 id, system_key, display_name, status, allowed_hostnames_json,
 created_by_staff_id, created_at, updated_at, version
)
external_references(
 id, ticket_id, external_system_id, object_type, external_id,
 display_label, safe_deep_link, metadata_snapshot_json, metadata_observed_at,
 created_by_actor_type, created_by_actor_id, created_by_actor_display, created_at
)
```

- Unique `(ticket_id, external_system_id, object_type, external_id)`; the same external identity may be linked to another ticket.
- status is `ACTIVE/DISABLED`; object type is `ORDER/PAYMENT/REFUND/USER/STORE/OPS_CASE/CUSTOM`.
- allowed hostnames and metadata remain bounded JSON text with DB shape/byte checks plus stricter application allowlists.
- ticket/created and external identity indexes support bounded context reads and duplicate investigation.
- V19 adds these tables without backfill or any provider credential/raw payload column.

### idempotency_records

```text
client_id
operation_id
idempotency_key_hash
request_hash
status                  IN_PROGRESS | SUCCEEDED | FAILED_FINAL
response_status nullable
response_headers_json nullable
response_body_json nullable
resource_id nullable
created_at
expires_at
```

Unique `(client_id, operation_id, idempotency_key_hash)`. Raw idempotency keys and Authorization values are never stored. V20 adds the
receipt table together with nullable `INTERNAL_WORK_ITEM` requester support and the `INTEGRATION_CLIENT` comment author. The
`(expires_at, id)` index drives `FOR UPDATE SKIP LOCKED` bounded cleanup; final receipts expire immediately while stale `IN_PROGRESS` rows
use a distinct abandonment grace before deletion.

### outbox_events / webhook tables

```text
outbox_events
webhook_endpoints
webhook_subscriptions
webhook_deliveries
webhook_attempts
```

Event와 delivery를 분리해 동일 event의 여러 endpoint 전달을 지원한다.

## 6. SLA and analytics later

```text
business_calendars
business_calendar_intervals
sla_policies
sla_policy_versions
sla_target_instances
ticket_state_intervals
analytics_ticket_facts
analytics_update_facts
backlog_snapshots
automation_execution_facts
```

정책은 mutable row를 덮어쓰기보다 version을 만들고 ticket 적용 시 snapshot한다.

## 7. Trigger/automation later

```text
business_rules
business_rule_versions
rule_conditions
rule_actions
rule_executions
scheduled_automation_candidates
```

조건/액션 JSON만으로 무제한 자유도를 주지 않고 허용 operator/action catalog를 둔다.

## 8. Settings

```text
system_settings
setting_change_audits (canonical audit ledger와 연결)
```

설정은 typed key registry를 통해 접근한다. 임의 string key를 코드 곳곳에서 사용하지 않는다.

## 9. Migration 순서

1. foundation IDs/settings.
2. customer.
3. staff/group.
4. ticket/comment.
5. ticket audit.
6. customer access token.
7. relations.
8. access audit.
9. integration.
10. SLA/analytics/automation.

## 10. 성능 검증 쿼리

최소 query plan 보관 대상:

- My open tickets.
- Unassigned tickets.
- Customer ticket history.
- Ticket conversation paging.
- Global audit by actor/date.
- Global audit by ticket/date.
- Search-to-open linkage.
- Idempotency concurrent claim.
- Webhook delivery retry queue.
- SLA at-risk queue.

## 11. 삭제·보존

- canonical audit는 operator policy에 따라 보존하고 직접 cascade delete하지 않는다.
- customer deletion은 legal/business policy에 따라 pseudonymization과 ticket retention을 분리한다.
- attachment object storage lifecycle와 DB metadata를 일치시킨다.
- backup에서 삭제가 언제 반영되는지 운영 문서에 적는다.
