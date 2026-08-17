-- V50 stored the revision search projection as text but its generic control-character
-- check also rejected the LF separators that the canonical validator deliberately emits
-- between safe blocks. Preserve V50 history and allow only LF as a projection separator.
alter table knowledge_article_revisions
    drop constraint knowledge_article_revisions_plain_text_bounded;

alter table knowledge_article_revisions
    add constraint knowledge_article_revisions_plain_text_bounded
    check (
        length(plain_text) between 1 and 500000
        and plain_text !~ E'[\\001-\\010\\013\\014\\015\\016-\\037\\177]'
    );
