create table checkout_return_urls (
    site_id uuid not null,
    origin varchar(255) not null,
    return_url varchar(2048) not null,
    version bigint not null default 0 check(version >= 0),
    primary key(site_id,origin),
    foreign key(site_id,origin) references site_allowed_origins(site_id,origin) on delete cascade
);
alter table idempotency_records drop constraint ck_idempotency_resource_type;
alter table idempotency_records add constraint ck_idempotency_resource_type check(
    resource_type in ('PAYMENT','CHECKOUT','HANDOFF','REFUND','OUTBOX_EVENT','OUTBOX_RETRY_REQUEST','DONATION_CAMPAIGN','ATTEMPT'));
