alter table ticket_comments
    add column content_format varchar(24) not null default 'PLAIN_TEXT',
    add column content_document jsonb null,
    add constraint ticket_comments_content_format_valid check (
        content_format in ('PLAIN_TEXT', 'RICH_TEXT_V1')
    ),
    add constraint ticket_comments_content_shape_valid check (
        (content_format = 'PLAIN_TEXT' and content_document is null)
        or (
            content_format = 'RICH_TEXT_V1'
            and jsonb_typeof(content_document) = 'object'
            and content_document ->> 'type' = 'doc'
        )
    );

alter table ticket_comments
    alter column content_format drop default;

alter table ticket_drafts
    add column content_format varchar(24) not null default 'PLAIN_TEXT',
    add column content_document jsonb null,
    add constraint ticket_drafts_content_format_valid check (
        content_format in ('PLAIN_TEXT', 'RICH_TEXT_V1')
    ),
    add constraint ticket_drafts_document_shape_valid check (
        (content_format = 'PLAIN_TEXT' and content_document is null)
        or (
            content_format = 'RICH_TEXT_V1'
            and jsonb_typeof(content_document) = 'object'
            and content_document ->> 'type' = 'doc'
        )
    );

alter table ticket_drafts
    alter column content_format drop default;
