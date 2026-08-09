alter table ticket_audits
    add column request_id varchar(100) not null default 'legacy-migration',
    add column correlation_id varchar(100) not null default 'legacy-migration',
    add column command_id varchar(100) not null default 'legacy-migration';

alter table ticket_audits
    alter column request_id drop default,
    alter column correlation_id drop default,
    alter column command_id drop default;
