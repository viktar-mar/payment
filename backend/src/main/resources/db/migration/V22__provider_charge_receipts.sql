-- Verified provider facts and browser attempt baselines are separate from the financial journal.
create table provider_charge_receipts (
    payment_account_id uuid not null,
    payment_id uuid not null,
    charge_reference varchar(203) not null check (charge_reference ~ '^ch_[A-Za-z0-9_]{1,200}$'),
    paid boolean not null,
    safe_outcome varchar(16) not null check (safe_outcome in ('SUCCEEDED','FAILED')),
    primary key(payment_account_id,charge_reference),
    foreign key(payment_id,payment_account_id) references payments(id,payment_account_id),
    check (paid = (safe_outcome='SUCCEEDED'))
);

alter table payment_attempts add constraint uq_attempt_payment_identity unique(id,payment_id);
create table payment_client_attempts (
    attempt_id uuid primary key,
    payment_account_id uuid not null,
    payment_id uuid not null,
    customer_id uuid,
    credentials_ref varchar(255) not null check (credentials_ref ~ '^[A-Za-z0-9_-]{1,255}$'),
    external_account_id varchar(205) not null check (external_account_id ~ '^acct_[A-Za-z0-9_]{1,200}$'),
    initial_failed_charge_reference varchar(203) check (initial_failed_charge_reference ~ '^ch_[A-Za-z0-9_]{1,200}$'),
    foreign key(attempt_id,payment_id) references payment_attempts(id,payment_id),
    foreign key(payment_id,payment_account_id) references payments(id,payment_account_id)
);
create index idx_payment_client_attempts_payment on payment_client_attempts(payment_id);

create function require_client_attempt_original_context() returns trigger language plpgsql as $$
begin
    if not exists (
        select 1 from payments p join payment_accounts a on a.id=p.payment_account_id
        where p.id=new.payment_id and p.payment_account_id=new.payment_account_id
          and p.customer_id is not distinct from new.customer_id
          and a.provider='STRIPE' and a.account_mode='SANDBOX'
          and a.credentials_ref=new.credentials_ref and a.external_account_id=new.external_account_id
    ) then raise exception 'Client attempt original context mismatch' using errcode='23514'; end if;
    return new;
end $$;
create trigger client_attempt_original_context before insert on payment_client_attempts
    for each row execute function require_client_attempt_original_context();

create function prevent_provider_receipt_mutation() returns trigger language plpgsql as $$
begin
    raise exception 'Provider receipts are immutable' using errcode='23514';
end $$;
create trigger provider_charge_receipt_immutable before update or delete on provider_charge_receipts
    for each row execute function prevent_provider_receipt_mutation();
create trigger client_attempt_baseline_immutable before update or delete on payment_client_attempts
    for each row execute function prevent_provider_receipt_mutation();
