alter table idempotency_records drop constraint ck_idempotency_resource_type;
alter table idempotency_records add constraint ck_idempotency_resource_type check(
    resource_type in ('PAYMENT','CHECKOUT','HANDOFF','REFUND','OUTBOX_EVENT','OUTBOX_RETRY_REQUEST','DONATION_CAMPAIGN','ATTEMPT','PROVIDER_OPERATION'));
