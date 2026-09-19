alter table ai_provider_calls
    add column prompt_cache_status varchar(20) null,
    add column prompt_cache_prefix_tokens integer null,
    add column prompt_cache_key_version varchar(40) null,
    add constraint ai_provider_call_prompt_cache_status_valid check (
        prompt_cache_status is null
        or prompt_cache_status in ('OFF', 'INELIGIBLE', 'REQUESTED')
    ),
    add constraint ai_provider_call_prompt_cache_shape check (
        (
            prompt_cache_status is null
            and prompt_cache_prefix_tokens is null
            and prompt_cache_key_version is null
        )
        or (
            prompt_cache_status = 'OFF'
            and prompt_cache_prefix_tokens is null
            and prompt_cache_key_version is null
        )
        or (
            prompt_cache_status = 'INELIGIBLE'
            and prompt_cache_prefix_tokens is not null
            and prompt_cache_prefix_tokens between 0 and 1023
            and prompt_cache_key_version is null
        )
        or (
            prompt_cache_status = 'REQUESTED'
            and prompt_cache_prefix_tokens is not null
            and prompt_cache_prefix_tokens between 1024 and 1000000
            and prompt_cache_key_version is not null
            and prompt_cache_key_version ~ '^[a-z0-9-]{1,40}$'
        )
    );
