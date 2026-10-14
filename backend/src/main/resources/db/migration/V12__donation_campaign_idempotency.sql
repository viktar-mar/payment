-- Campaign Admin mutations use the existing atomic, actor/Site-scoped receipt store.
alter table idempotency_records drop constraint ck_idempotency_resource_type;
alter table idempotency_records add constraint ck_idempotency_resource_type check(
 resource_type in ('PAYMENT','CHECKOUT','HANDOFF','REFUND','OUTBOX_EVENT','OUTBOX_RETRY_REQUEST','DONATION_CAMPAIGN'));
create index idx_donation_campaigns_site_created on donation_campaigns(site_id,created_at desc,id desc);
