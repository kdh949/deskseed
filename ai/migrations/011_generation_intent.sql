alter table ai_jobs
    add column generation_mode varchar(24) null,
    add column candidate_id uuid null,
    add column candidate_sequence integer null,
    add constraint ai_job_generation_intent_shape check (
        (generation_mode is null and candidate_id is null and candidate_sequence is null)
        or (generation_mode = 'REUSE_OR_CREATE' and candidate_id is null and candidate_sequence is null)
        or (
            generation_mode = 'NEW_CANDIDATE'
            and candidate_id is not null
            and candidate_sequence is not null
            and candidate_sequence > 0
        )
    );

create unique index ai_jobs_candidate_id_idx
    on ai_jobs (candidate_id) where candidate_id is not null;
create unique index ai_jobs_candidate_sequence_scope_idx
    on ai_jobs (
        workspace_key, requester_id, ticket_id, feature, ai_input_revision, candidate_sequence
    ) where generation_mode = 'NEW_CANDIDATE';

alter table ai_jobs drop constraint ai_job_result_reuse_shape;
alter table ai_jobs add constraint ai_job_result_reuse_shape check (
    (result_origin_job_id is null and (reuse_kind is null or reuse_kind = 'GENERATED'))
    or (
        result_origin_job_id is not null
        and result_origin_job_id <> job_id
        and reuse_kind is not null
        and reuse_kind in ('EXACT_CACHE_HIT', 'COALESCED')
    )
);
