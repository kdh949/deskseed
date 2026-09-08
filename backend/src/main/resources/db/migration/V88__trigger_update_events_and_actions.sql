alter table trigger_evaluation_jobs drop constraint trigger_jobs_event_valid;
alter table trigger_evaluation_jobs add constraint trigger_jobs_event_valid check (event_type in ('TICKET_CREATED','TICKET_UPDATED','CUSTOMER_REPLIED'));
alter table trigger_conditions drop constraint trigger_conditions_field_valid;
alter table trigger_conditions add constraint trigger_conditions_field_valid check (field_name in ('EVENT','PRIORITY','GROUP','ASSIGNEE','TAG','FORM'));
alter table trigger_actions drop constraint trigger_actions_type_valid;
alter table trigger_actions add constraint trigger_actions_type_valid check (action_type in ('SET_GROUP','SET_PRIORITY','SET_ASSIGNEE','NOTIFY_UNASSIGNED_GROUP','ENQUEUE_WEBHOOK'));

alter table staff_notifications alter column note_id drop not null;
alter table staff_notifications add column trigger_id uuid null;
alter table staff_notifications add column trigger_version integer null;
alter table staff_notifications add column trigger_execution_id uuid null;
alter table staff_notifications add constraint staff_notifications_trigger_version_fk foreign key (trigger_id, trigger_version) references trigger_versions(trigger_id, version);
alter table staff_notifications drop constraint staff_notifications_type_valid;
alter table staff_notifications add constraint staff_notifications_type_valid check (
    (notification_type = 'COLLABORATION_MENTION' and note_id is not null and trigger_id is null and trigger_version is null and trigger_execution_id is null)
    or (notification_type = 'UNASSIGNED_TICKET_ALERT' and note_id is null and trigger_id is not null and trigger_version is not null and trigger_execution_id is not null)
);
create unique index staff_notifications_trigger_recipient_idx on staff_notifications (recipient_staff_id, trigger_execution_id) where trigger_execution_id is not null;
