alter table ai_requests
    add column generation_mode varchar(24) null,
    add column candidate_id uuid null,
    add column candidate_sequence integer null,
    add column candidate_quota_refunded_at timestamptz null,
    add constraint ai_request_generation_intent_shape check (
        (generation_mode is null and candidate_id is null and candidate_sequence is null)
        or (generation_mode = 'REUSE_OR_CREATE' and candidate_id is null and candidate_sequence is null)
        or (
            generation_mode = 'NEW_CANDIDATE'
            and candidate_id is not null
            and candidate_sequence is not null
            and candidate_sequence > 0
        )
    ),
    add constraint ai_request_candidate_refund_shape check (
        candidate_quota_refunded_at is null or generation_mode = 'NEW_CANDIDATE'
    );

create unique index ai_requests_candidate_id_idx
    on ai_requests (candidate_id) where candidate_id is not null;
create unique index ai_requests_candidate_sequence_scope_idx
    on ai_requests (
        workspace_key, requester_staff_id, ticket_id, feature, ai_input_revision, candidate_sequence
    ) where generation_mode = 'NEW_CANDIDATE';
create index ai_requests_candidate_limit_idx
    on ai_requests (
        workspace_key, requester_staff_id, ticket_id, feature, ai_input_revision, created_at
    ) where generation_mode = 'NEW_CANDIDATE' and candidate_quota_refunded_at is null;
