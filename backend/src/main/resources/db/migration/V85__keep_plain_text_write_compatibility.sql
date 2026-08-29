-- Legacy application instances still write the required plain-text body while a
-- rolling deployment introduces structured content. Keep that write path valid;
-- rich writers always set both columns explicitly.
alter table ticket_comments
    alter column content_format set default 'PLAIN_TEXT';

alter table ticket_drafts
    alter column content_format set default 'PLAIN_TEXT';
