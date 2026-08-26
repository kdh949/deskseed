alter table customer_accounts
    add constraint customer_accounts_id_email_unique
    unique (id, email_normalized);

alter table customer_registration_intents
    add constraint customer_registration_intents_id_email_unique
    unique (id, email_normalized);

alter table customer_one_time_tokens
    add constraint customer_one_time_tokens_registration_email_fkey
        foreign key (registration_intent_id, email_normalized)
        references customer_registration_intents(id, email_normalized),
    add constraint customer_one_time_tokens_account_email_fkey
        foreign key (account_id, email_normalized)
        references customer_accounts(id, email_normalized),
    alter column purpose drop default;
