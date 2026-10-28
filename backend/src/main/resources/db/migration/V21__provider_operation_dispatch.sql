-- Dispatch receipts are separate from the financial ledger. No credentials or raw provider payloads.
create table provider_operations (
    id uuid primary key,
    payment_account_id uuid not null references payment_accounts(id),
    external_account_id varchar(255) not null check (length(trim(external_account_id)) > 0),
    credentials_ref varchar(255) not null check (length(trim(credentials_ref)) > 0),
    kind varchar(32) not null check (kind in ('PRODUCT_CREATE','PRICE_CREATE','PAYMENT_PREPARE','REFUND_CREATE')),
    resource_id uuid not null,
    operation_key varchar(255) not null check (length(trim(operation_key)) > 0),
    fingerprint varchar(255) not null check (length(trim(fingerprint)) > 0),
    status varchar(16) not null default 'RESERVED'
        check (status in ('RESERVED','DISPATCHING','ACKNOWLEDGED','UNKNOWN','FAILED')),
    provider_reference varchar(255) check (length(trim(provider_reference)) > 0),
    first_dispatched_at timestamptz,
    retry_before timestamptz,
    lease_owner varchar(128) check (length(trim(lease_owner)) > 0),
    lease_expires_at timestamptz,
    version bigint not null default 0 check (version >= 0),
    recovery_attempts integer not null default 0 check (recovery_attempts >= 0),
    next_attempt_at timestamptz not null default now(),
    safe_error_code varchar(64) check (safe_error_code ~ '^[A-Z_]{1,64}$'),
    manual_review_required boolean not null default false,
    unique (payment_account_id, operation_key),
    unique (payment_account_id, kind, resource_id),
    constraint ck_provider_operation_dispatch_bounds check (
        (first_dispatched_at is null and retry_before is null and status = 'RESERVED')
        or (first_dispatched_at is not null and retry_before is not null and status <> 'RESERVED'
            and retry_before = first_dispatched_at + interval '23 hours')),
    constraint ck_provider_operation_lease check (
        (status = 'DISPATCHING' and lease_owner is not null and lease_expires_at is not null
            and lease_expires_at > first_dispatched_at)
        or (status <> 'DISPATCHING' and lease_owner is null and lease_expires_at is null)),
    constraint ck_provider_operation_reference check (
        (status = 'ACKNOWLEDGED') = (provider_reference is not null))
);

create unique index uq_provider_operation_reference
    on provider_operations(payment_account_id, kind, provider_reference) where provider_reference is not null;
create index idx_provider_operation_recovery
    on provider_operations(next_attempt_at) where status in ('DISPATCHING','UNKNOWN','FAILED');
