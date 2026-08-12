create table business_schedules (
    id uuid primary key,
    name_normalized varchar(100) not null,
    current_version integer not null,
    active_version integer null,
    aggregate_version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint business_schedule_current_version_positive check (current_version >= 1),
    constraint business_schedule_active_version_valid check (
        active_version is null or (active_version >= 1 and active_version <= current_version)
    ),
    constraint business_schedule_name_normalized_not_blank check (length(btrim(name_normalized)) > 0)
);

create unique index business_schedules_name_ci_unique
    on business_schedules (name_normalized);

create table business_schedule_versions (
    schedule_id uuid not null references business_schedules(id),
    version integer not null,
    name varchar(100) not null,
    timezone varchar(100) not null,
    created_by_actor_type varchar(20) not null,
    created_by_staff_id uuid null references staff_accounts(id),
    created_by_display varchar(100) not null,
    created_at timestamptz not null,
    primary key (schedule_id, version),
    constraint business_schedule_version_positive check (version >= 1),
    constraint business_schedule_version_name_not_blank check (length(btrim(name)) > 0),
    constraint business_schedule_version_timezone_not_blank check (length(btrim(timezone)) > 0),
    constraint business_schedule_version_actor_valid check (created_by_actor_type in ('STAFF', 'SYSTEM')),
    constraint business_schedule_version_actor_id_valid check (
        (created_by_actor_type = 'STAFF' and created_by_staff_id is not null) or
        (created_by_actor_type = 'SYSTEM' and created_by_staff_id is null)
    )
);

alter table business_schedules
    add constraint business_schedule_current_version_fk
        foreign key (id, current_version)
        references business_schedule_versions(schedule_id, version)
        deferrable initially deferred,
    add constraint business_schedule_active_version_fk
        foreign key (id, active_version)
        references business_schedule_versions(schedule_id, version)
        deferrable initially deferred;

create table business_schedule_weekdays (
    schedule_id uuid not null,
    schedule_version integer not null,
    weekday varchar(9) not null,
    enabled boolean not null,
    primary key (schedule_id, schedule_version, weekday),
    foreign key (schedule_id, schedule_version)
        references business_schedule_versions(schedule_id, version),
    constraint business_schedule_weekday_valid check (
        weekday in ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')
    )
);

create table business_schedule_weekday_intervals (
    schedule_id uuid not null,
    schedule_version integer not null,
    weekday varchar(9) not null,
    ordinal smallint not null,
    start_time time(0) without time zone not null,
    end_time time(0) without time zone not null,
    primary key (schedule_id, schedule_version, weekday, ordinal),
    foreign key (schedule_id, schedule_version, weekday)
        references business_schedule_weekdays(schedule_id, schedule_version, weekday),
    constraint business_schedule_weekday_interval_ordinal_valid check (ordinal between 0 and 11),
    constraint business_schedule_weekday_interval_range_valid check (start_time < end_time)
);

create table business_schedule_exceptions (
    schedule_id uuid not null,
    schedule_version integer not null,
    exception_date date not null,
    mode varchar(10) not null,
    label varchar(200) null,
    primary key (schedule_id, schedule_version, exception_date),
    foreign key (schedule_id, schedule_version)
        references business_schedule_versions(schedule_id, version),
    constraint business_schedule_exception_mode_valid check (mode in ('CLOSED', 'OPEN')),
    constraint business_schedule_exception_label_safe check (
        label is null or (length(label) <= 200 and label !~ '[[:cntrl:]]')
    )
);

create table business_schedule_exception_intervals (
    schedule_id uuid not null,
    schedule_version integer not null,
    exception_date date not null,
    ordinal smallint not null,
    start_time time(0) without time zone not null,
    end_time time(0) without time zone not null,
    primary key (schedule_id, schedule_version, exception_date, ordinal),
    foreign key (schedule_id, schedule_version, exception_date)
        references business_schedule_exceptions(schedule_id, schedule_version, exception_date),
    constraint business_schedule_exception_interval_ordinal_valid check (ordinal between 0 and 11),
    constraint business_schedule_exception_interval_range_valid check (start_time < end_time)
);

create table business_schedule_activations (
    id uuid primary key,
    schedule_id uuid not null,
    schedule_version integer not null,
    actor_type varchar(20) not null,
    actor_id uuid null references staff_accounts(id),
    actor_display_snapshot varchar(100) not null,
    request_id varchar(100) not null,
    correlation_id varchar(100) not null,
    activated_at timestamptz not null,
    foreign key (schedule_id, schedule_version)
        references business_schedule_versions(schedule_id, version),
    constraint business_schedule_activation_actor_valid check (actor_type in ('STAFF', 'SYSTEM')),
    constraint business_schedule_activation_actor_id_valid check (
        (actor_type = 'STAFF' and actor_id is not null) or
        (actor_type = 'SYSTEM' and actor_id is null)
    )
);

create index business_schedule_versions_history_idx
    on business_schedule_versions (schedule_id, version desc);
create index business_schedule_activations_history_idx
    on business_schedule_activations (schedule_id, activated_at desc, id desc);
create index business_schedule_exceptions_date_idx
    on business_schedule_exceptions (exception_date, schedule_id, schedule_version);

create or replace function reject_business_schedule_history_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'Business schedule version history is immutable';
end;
$$;

create trigger business_schedule_versions_immutable
before update or delete on business_schedule_versions
for each row execute function reject_business_schedule_history_mutation();

create trigger business_schedule_weekdays_immutable
before update or delete on business_schedule_weekdays
for each row execute function reject_business_schedule_history_mutation();

create trigger business_schedule_weekday_intervals_immutable
before update or delete on business_schedule_weekday_intervals
for each row execute function reject_business_schedule_history_mutation();

create trigger business_schedule_exceptions_immutable
before update or delete on business_schedule_exceptions
for each row execute function reject_business_schedule_history_mutation();

create trigger business_schedule_exception_intervals_immutable
before update or delete on business_schedule_exception_intervals
for each row execute function reject_business_schedule_history_mutation();

create trigger business_schedule_activations_immutable
before update or delete on business_schedule_activations
for each row execute function reject_business_schedule_history_mutation();

create or replace function reject_business_schedule_delete()
returns trigger
language plpgsql
as $$
begin
    raise exception 'Business schedules cannot be deleted';
end;
$$;

create trigger business_schedules_no_delete
before delete on business_schedules
for each row execute function reject_business_schedule_delete();

insert into business_schedules (
    id, name_normalized, current_version, active_version, aggregate_version, created_at, updated_at
) values (
    '51000000-0000-0000-0000-000000000001',
    'default support hours',
    1,
    1,
    0,
    '2026-08-10 00:00:00+00',
    '2026-08-10 00:00:00+00'
);

insert into business_schedule_versions (
    schedule_id, version, name, timezone, created_by_actor_type,
    created_by_staff_id, created_by_display, created_at
) values (
    '51000000-0000-0000-0000-000000000001',
    1,
    'Default Support Hours',
    'Asia/Seoul',
    'SYSTEM',
    null,
    'Deskseed seed',
    '2026-08-10 00:00:00+00'
);

insert into business_schedule_weekdays (schedule_id, schedule_version, weekday, enabled)
values
    ('51000000-0000-0000-0000-000000000001', 1, 'MONDAY', true),
    ('51000000-0000-0000-0000-000000000001', 1, 'TUESDAY', true),
    ('51000000-0000-0000-0000-000000000001', 1, 'WEDNESDAY', true),
    ('51000000-0000-0000-0000-000000000001', 1, 'THURSDAY', true),
    ('51000000-0000-0000-0000-000000000001', 1, 'FRIDAY', true),
    ('51000000-0000-0000-0000-000000000001', 1, 'SATURDAY', false),
    ('51000000-0000-0000-0000-000000000001', 1, 'SUNDAY', false);

insert into business_schedule_weekday_intervals (
    schedule_id, schedule_version, weekday, ordinal, start_time, end_time
) values
    ('51000000-0000-0000-0000-000000000001', 1, 'MONDAY', 0, '09:00', '18:00'),
    ('51000000-0000-0000-0000-000000000001', 1, 'TUESDAY', 0, '09:00', '18:00'),
    ('51000000-0000-0000-0000-000000000001', 1, 'WEDNESDAY', 0, '09:00', '18:00'),
    ('51000000-0000-0000-0000-000000000001', 1, 'THURSDAY', 0, '09:00', '18:00'),
    ('51000000-0000-0000-0000-000000000001', 1, 'FRIDAY', 0, '09:00', '18:00');

insert into business_schedule_activations (
    id, schedule_id, schedule_version, actor_type, actor_id, actor_display_snapshot,
    request_id, correlation_id, activated_at
) values (
    '52000000-0000-0000-0000-000000000001',
    '51000000-0000-0000-0000-000000000001',
    1,
    'SYSTEM',
    null,
    'Deskseed seed',
    'seed-business-schedule',
    'seed-business-schedule',
    '2026-08-10 00:00:00+00'
);
