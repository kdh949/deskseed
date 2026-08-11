alter table staff_accounts
    drop constraint staff_role_valid;

alter table staff_accounts
    add constraint staff_role_valid check (
        role in ('ADMIN', 'AGENT', 'SECURITY_AUDITOR')
    );
