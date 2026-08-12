alter table customers
    drop constraint customers_email_normalized_key;

create unique index customers_verified_email_normalized_unique
    on customers (email_normalized)
    where verified_at is not null;

create index customers_email_normalized_idx
    on customers (email_normalized);
