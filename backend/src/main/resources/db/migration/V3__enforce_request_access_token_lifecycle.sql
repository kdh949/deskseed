update request_access_tokens
set expires_at = created_at + interval '30 days'
where expires_at is null;

alter table request_access_tokens
    alter column expires_at set not null,
    add constraint request_access_token_hash_format_valid
        check (token_hash ~ '^[0-9a-f]{64}$'),
    add constraint request_access_token_expiry_valid
        check (expires_at > created_at),
    add constraint request_access_token_revocation_valid
        check (revoked_at is null or revoked_at >= created_at);
