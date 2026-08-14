create extension if not exists pg_trgm;

create index customers_name_trgm_idx on customers using gin (name gin_trgm_ops);
create index customers_email_normalized_trgm_idx on customers using gin (email_normalized gin_trgm_ops);
