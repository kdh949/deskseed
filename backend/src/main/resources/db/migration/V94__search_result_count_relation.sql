alter table search_audit_details
    add column result_count_relation varchar(20) not null default 'EXACT',
    add constraint search_audit_result_count_relation_valid check (
        result_count_relation in ('EXACT', 'LOWER_BOUND')
    );
