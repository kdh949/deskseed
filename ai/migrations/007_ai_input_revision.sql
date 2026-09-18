alter table ai_jobs
    add column ai_input_revision char(64) null,
    add column input_policy_version varchar(40) null,
    add constraint ai_job_input_revision_shape check (
        (ai_input_revision is null and input_policy_version is null)
        or (
            ai_input_revision is not null
            and input_policy_version is not null
            and ai_input_revision ~ '^[0-9a-f]{64}$'
            and (
                (feature = 'ticket.summary' and input_policy_version = 'summary-input-v1')
                or (feature = 'ticket.triage' and input_policy_version = 'triage-input-v1')
                or (feature = 'ticket.reply_draft' and input_policy_version = 'reply-input-v1')
            )
        )
    );
