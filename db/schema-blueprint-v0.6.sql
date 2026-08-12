-- Deskseed schema blueprint v0.6
-- This is a design aid, not a ready-to-run migration.

-- Core principle: tickets have no description column. The request body is the first PUBLIC comment.

-- Main groups of tables:
-- customers, customer_accounts, customer_access_tokens
-- staff_accounts, staff_authority_grants, groups, group_memberships
-- tickets, ticket_comments, ticket_relations
-- ticket_audits, ticket_audit_events
-- activity_audit_events, search_audit_details
-- integration_clients, integration_credentials
-- external_systems, external_references
-- idempotency_records
-- outbox_events, webhook_endpoints, webhook_deliveries, webhook_attempts
-- system_settings
-- later: SLA, analytics, triggers, attachments, channel delivery

-- Required PostgreSQL protections:
-- 1. application runtime role cannot UPDATE/DELETE canonical audit tables
-- 2. DB trigger rejects audit mutation even if ORM attempts it
-- 3. unique(ticket_number)
-- 4. unique(ticket_id, sequence_number) for comments
-- 5. unique(client_id, idempotency_key)
-- 6. ticket relation self-link check
-- 7. assignee requires group; membership checked in application transaction
