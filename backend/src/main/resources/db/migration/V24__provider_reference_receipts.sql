alter table provider_inbox alter column event_family type varchar(24);
alter table provider_inbox add column reference_object_type varchar(16);
alter table provider_inbox add column provider_event_type varchar(64);
alter table provider_inbox add column provider_api_version varchar(64);

alter table provider_inbox drop constraint ck_inbox_family_event;
alter table provider_inbox add constraint ck_inbox_family_event check (
 (event_family='PAYMENT' and event_type in ('PAYMENT_CREATED','ATTEMPT_CREATED','PAYMENT_METHOD_REQUIRED','CUSTOMER_ACTION_REQUIRED','PAYMENT_PROCESSING','PAYMENT_SUCCEEDED','PAYMENT_FAILED','PAYMENT_CANCELLED','PROVIDER_RESULT_UNKNOWN','REFUND_REQUESTED','REFUND_PROCESSING','REFUND_SUCCEEDED','REFUND_FAILED'))
 or (event_family='SUBSCRIPTION' and event_type in ('SUBSCRIPTION_CHANGED','SUBSCRIPTION_INVOICE_CHANGED','SUBSCRIPTION_SETUP_CHANGED'))
 or (event_family='PROVIDER_REFERENCE' and event_type='PROVIDER_REFERENCE'));

alter table provider_inbox drop constraint ck_inbox_status;
alter table provider_inbox add constraint ck_inbox_status check (
 status in ('PENDING','PROCESSING','PROCESSED','FAILED')
 or (event_family='PROVIDER_REFERENCE' and status='DEAD' and attempt_count=12 and lease_owner is null));
alter table provider_inbox add constraint ck_inbox_reference_shape check (
 (event_family<>'PROVIDER_REFERENCE' and reference_object_type is null and provider_event_type is null and provider_api_version is null)
 or (event_family='PROVIDER_REFERENCE' and provider_type='STRIPE' and reference_object_type is not null
   and provider_event_type is not null and provider_api_version='2026-09-30.endive' and provider_api_version is not null
   and safe_payload='{}'::jsonb and attempt_count<=12
   and provider_event_id ~ '^evt_[A-Za-z0-9]{1,251}$'
   and ((reference_object_type='PAYMENT' and provider_object_id ~ '^pi_[A-Za-z0-9]{1,252}$'
     and provider_event_type in ('payment_intent.processing','payment_intent.requires_action','payment_intent.succeeded','payment_intent.payment_failed','payment_intent.canceled'))
    or (reference_object_type='REFUND' and provider_object_id ~ '^re_[A-Za-z0-9]{1,252}$'
     and provider_event_type in ('refund.created','refund.updated','refund.failed')))));
create index idx_reference_inbox_ready on provider_inbox(next_attempt_at,received_at,id) where event_family='PROVIDER_REFERENCE';
