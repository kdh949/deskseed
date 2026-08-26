alter table outbound_mail_intents
    drop constraint outbound_mail_template_valid,
    add constraint outbound_mail_template_valid check (
        template_key in (
            'CUSTOMER_MAGIC_LINK',
            'CUSTOMER_REGISTRATION_VERIFICATION',
            'REQUEST_RECEIVED',
            'PUBLIC_AGENT_REPLY'
        )
    );
