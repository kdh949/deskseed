alter table ai_jobs
    add column trace_id char(32) generated always as (
        replace(job_id::text, '-', '')
    ) stored,
    add constraint ai_job_trace_id_shape check (trace_id ~ '^[0-9a-f]{32}$'),
    add constraint ai_job_trace_id_unique unique (trace_id);

alter table ai_provider_calls
    add column trace_id char(32) generated always as (
        replace(coalesce(job_id, reservation_id)::text, '-', '')
    ) stored,
    add column observation_id char(32) generated always as (
        replace(call_id::text, '-', '')
    ) stored,
    add constraint ai_provider_call_trace_id_shape check (trace_id ~ '^[0-9a-f]{32}$'),
    add constraint ai_provider_call_observation_id_shape check (observation_id ~ '^[0-9a-f]{32}$'),
    add constraint ai_provider_call_observation_id_unique unique (observation_id);

create index ai_provider_calls_trace_idx
    on ai_provider_calls (trace_id, created_at, call_id);

create table ai_telemetry_counters (
    counter_key varchar(40) primary key,
    counter_value bigint not null default 0,
    updated_at timestamptz not null,
    constraint ai_telemetry_counter_key_valid check (
        counter_key in (
            'jobStart', 'jobUpdate', 'jobEnd', 'providerObservation', 'flush', 'feedbackRetry'
        )
    ),
    constraint ai_telemetry_counter_value_nonnegative check (counter_value >= 0)
);

insert into ai_telemetry_counters (counter_key, counter_value, updated_at)
values
    ('jobStart', 0, clock_timestamp()),
    ('jobUpdate', 0, clock_timestamp()),
    ('jobEnd', 0, clock_timestamp()),
    ('providerObservation', 0, clock_timestamp()),
    ('flush', 0, clock_timestamp()),
    ('feedbackRetry', 0, clock_timestamp());
