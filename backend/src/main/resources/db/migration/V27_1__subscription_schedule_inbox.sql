-- Admission only: does not enable Schedule mutation or weaken reference receipt constraints.
alter table provider_inbox drop constraint ck_inbox_family_event;
alter table provider_inbox add constraint ck_inbox_family_event check (
 (event_family='PAYMENT' and event_type in ('PAYMENT_CREATED','ATTEMPT_CREATED','PAYMENT_METHOD_REQUIRED','CUSTOMER_ACTION_REQUIRED','PAYMENT_PROCESSING','PAYMENT_SUCCEEDED','PAYMENT_FAILED','PAYMENT_CANCELLED','PROVIDER_RESULT_UNKNOWN','REFUND_REQUESTED','REFUND_PROCESSING','REFUND_SUCCEEDED','REFUND_FAILED'))
 or (event_family='SUBSCRIPTION' and event_type in ('SUBSCRIPTION_CHANGED','SUBSCRIPTION_INVOICE_CHANGED','SUBSCRIPTION_SETUP_CHANGED','SUBSCRIPTION_SCHEDULE_CHANGED'))
 or (event_family='PROVIDER_REFERENCE' and event_type='PROVIDER_REFERENCE'));
