alter table trigger_evaluation_jobs drop constraint trigger_jobs_event_valid;
alter table trigger_evaluation_jobs add constraint trigger_jobs_event_valid
    check (event_type = 'TICKET_CREATED');
