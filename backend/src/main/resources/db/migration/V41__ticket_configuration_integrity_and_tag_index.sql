alter table ticket_field_options
    drop constraint ticket_field_options_field_definition_id_display_order_key,
    add constraint ticket_field_options_field_definition_id_display_order_key
        unique (field_definition_id, display_order) deferrable initially immediate;

create unique index ticket_forms_published_default_customer_idx
    on ticket_forms (default_for_customer)
    where lifecycle = 'PUBLISHED' and default_for_customer;

create unique index ticket_forms_published_default_agent_idx
    on ticket_forms (default_for_agent)
    where lifecycle = 'PUBLISHED' and default_for_agent;

create index ticket_tag_assignments_tag_definition_idx
    on ticket_tag_assignments (tag_definition_id);
