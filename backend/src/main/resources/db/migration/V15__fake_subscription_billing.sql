-- Independent provider simulator. No FK to platform Subscription, Invoice or Payment projections.
create table fake_subscription_account_clocks (
 payment_account_id uuid primary key references payment_accounts(id), simulated_at timestamptz not null
);
create table fake_subscription_scenarios (
 payment_account_id uuid not null references payment_accounts(id), subscription_id uuid not null,
 scenario varchar(40) not null check(scenario in ('SUCCESS','PROCESSING','REQUIRES_ACTION','DECLINE','UNKNOWN','ZERO_PRORATION','UNSUPPORTED_SETTLEMENT')),
 primary key(payment_account_id,subscription_id)
);
create table fake_subscription_objects (
 payment_account_id uuid not null references payment_accounts(id), subscription_id uuid not null,
 provider_reference varchar(255) not null, state jsonb not null,
 primary key(payment_account_id,subscription_id), unique(payment_account_id,provider_reference),
 unique(payment_account_id,subscription_id,provider_reference)
);
create table fake_subscription_operations (
 payment_account_id uuid not null, subscription_id uuid not null, operation_key varchar(255) not null,
 fingerprint varchar(64) not null check(fingerprint ~ '^[a-f0-9]{64}$'),
 provider_reference varchar(255) not null, reply jsonb not null, created_at timestamptz not null,
 foreign key(payment_account_id,subscription_id,provider_reference)
 references fake_subscription_objects(payment_account_id,subscription_id,provider_reference)
);
create unique index uq_fake_subscription_operation on fake_subscription_operations(payment_account_id,operation_key);
create table fake_subscription_invoices (
 payment_account_id uuid not null, subscription_id uuid not null, provider_reference varchar(255) not null,
 ordinal bigint not null check(ordinal>0), invoice jsonb not null,
 primary key(payment_account_id,subscription_id,provider_reference),
 foreign key(payment_account_id,subscription_id) references fake_subscription_objects(payment_account_id,subscription_id),
 unique(payment_account_id,subscription_id,ordinal)
);
create unique index uq_fake_subscription_invoice on fake_subscription_invoices(payment_account_id,provider_reference);
create table fake_subscription_cash_objects (
 payment_account_id uuid not null, subscription_id uuid not null, invoice_reference varchar(255) not null,
 provider_reference varchar(255) not null, amount_minor bigint not null check(amount_minor>0),
 currency varchar(3) not null check(currency ~ '^[A-Z]{3}$'), status varchar(32) not null,
 occurred_at timestamptz not null, cash_object jsonb not null,
 primary key(payment_account_id,provider_reference),
 foreign key(payment_account_id,subscription_id,invoice_reference)
 references fake_subscription_invoices(payment_account_id,subscription_id,provider_reference)
);
create table fake_subscription_event_queue (
 id uuid primary key, payment_account_id uuid not null, subscription_id uuid not null,
 provider_reference varchar(255) not null, provider_revision bigint not null check(provider_revision>0),
 event_type varchar(64) not null, occurred_at timestamptz not null, payload jsonb not null,
 delivered_at timestamptz,
 foreign key(payment_account_id,subscription_id,provider_reference)
 references fake_subscription_objects(payment_account_id,subscription_id,provider_reference),
 unique(payment_account_id,subscription_id,provider_revision)
);
create table fake_subscription_quotes (
 payment_account_id uuid not null, subscription_id uuid not null, provider_reference varchar(255) not null,
 quote jsonb not null, primary key(payment_account_id,provider_reference),
 foreign key(payment_account_id,subscription_id) references fake_subscription_objects(payment_account_id,subscription_id)
);
create function reject_fake_subscription_receipt_mutation() returns trigger language plpgsql as $$
begin raise exception 'Fake subscription receipts are immutable' using errcode='23514'; end;
$$;
create trigger immutable_fake_subscription_receipts before update or delete on fake_subscription_operations
 for each row execute function reject_fake_subscription_receipt_mutation();
create function preserve_fake_subscription_reference() returns trigger language plpgsql as $$
begin
 if (new.payment_account_id,new.subscription_id,new.provider_reference) is distinct from
    (old.payment_account_id,old.subscription_id,old.provider_reference) then
  raise exception 'Fake subscription references are immutable' using errcode='23514';
 end if;
 return new;
end;
$$;
create trigger immutable_fake_subscription_reference before update on fake_subscription_objects
 for each row execute function preserve_fake_subscription_reference();
