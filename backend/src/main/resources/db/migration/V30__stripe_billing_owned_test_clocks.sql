-- Additive ownership evidence. Existing operation keys, fingerprints and Customer bindings are untouched.
alter table provider_operations drop constraint provider_operations_kind_check;
alter table provider_operations add constraint provider_operations_kind_check check(kind in (
 'PRODUCT_CREATE','PRICE_CREATE','PAYMENT_PREPARE','REFUND_CREATE','CUSTOMER_CREATE','TEST_CLOCK_CREATE',
 'SUBSCRIPTION_CREATE','SUBSCRIPTION_UPDATE','SUBSCRIPTION_CANCEL','SETUP_INTENT_CREATE','INVOICE_PAY',
 'SCHEDULE_CREATE','SCHEDULE_UPDATE','SCHEDULE_RELEASE','PORTAL_CONFIGURATION_CREATE','PORTAL_SESSION_CREATE'));
-- Retain every prior member, including future kinds, and explicitly retain the original UPDATE exclusion.
drop index uq_provider_operation_reference;
create unique index uq_provider_operation_reference on provider_operations(payment_account_id,kind,provider_reference)
 where provider_reference is not null and kind<>'SUBSCRIPTION_UPDATE';
alter table sites add constraint uq_billing_clock_site_account unique(id,payment_account_id);
create table stripe_billing_clock_intents (
 id uuid primary key, run_id uuid not null, payment_account_id uuid not null references payment_accounts(id),
 site_id uuid not null, customer_id uuid not null, external_account_id varchar(255) not null,
 credentials_ref varchar(255) not null, api_version varchar(255) not null,
 initial_frozen_time bigint not null check(initial_frozen_time>0), name varchar(255) not null,
 create_key varchar(255) not null unique, create_fingerprint varchar(64) not null check(create_fingerprint ~ '^[a-f0-9]{64}$'),
 admitted_at timestamptz not null, stopped_at timestamptz, stop_reason varchar(32),
 unique(payment_account_id,site_id,customer_id), unique(payment_account_id,run_id),
 unique(id,payment_account_id,site_id,customer_id,initial_frozen_time),
 foreign key(site_id,customer_id) references customers(site_id,id),
 foreign key(site_id,payment_account_id) references sites(id,payment_account_id),
 check(name='billing_clock_'||id::text),
 check(create_key='billing_clock_'||payment_account_id::text||'_'||id::text),
 check(length(trim(external_account_id))>0 and length(trim(credentials_ref))>0 and length(trim(api_version))>0),
 check(num_nonnulls(stopped_at,stop_reason) in (0,2)),
 check((stopped_at is null and stop_reason is null) or (stopped_at is not null and stopped_at>=admitted_at
   and stop_reason in ('CLOCK_CREATE_FAILED','CLOCK_READY_FAILED','CUSTOMER_CREATE_FAILED','BINDING_FAILED','AUTHORITY_REVOKED')))
);
create index idx_billing_clock_customer on stripe_billing_clock_intents(site_id,customer_id);
create table stripe_billing_clock_ready (
 intent_id uuid primary key, payment_account_id uuid not null, site_id uuid not null, customer_id uuid not null,
 creation_operation_id uuid not null unique,
 creation_kind varchar(32) not null default 'TEST_CLOCK_CREATE' check(creation_kind='TEST_CLOCK_CREATE'),
 reference varchar(255) not null check(reference ~ '^clock_[A-Za-z0-9]{1,249}$'),
 frozen_time bigint not null, provider_created bigint not null check(provider_created>0),
 provider_deletes_after bigint not null check(provider_deletes_after>0),
 state varchar(16) not null default 'READY' check(state='READY'), observed_at timestamptz not null,
 customer_fingerprint varchar(64) not null check(customer_fingerprint ~ '^[a-f0-9]{64}$'),
 unique(payment_account_id,reference),
 foreign key(intent_id,payment_account_id,site_id,customer_id,frozen_time)
   references stripe_billing_clock_intents(id,payment_account_id,site_id,customer_id,initial_frozen_time),
 foreign key(creation_operation_id,payment_account_id,intent_id,creation_kind,reference)
   references provider_operations(id,payment_account_id,resource_id,kind,provider_reference)
);
create function protect_billing_clock_intent() returns trigger language plpgsql as $$
begin
 if tg_op='DELETE' then raise exception 'Clock ownership is immutable' using errcode='23514'; end if;
 if (to_jsonb(new)-'stopped_at'-'stop_reason') is distinct from (to_jsonb(old)-'stopped_at'-'stop_reason')
   or (old.stopped_at is not null and new is distinct from old) then
  raise exception 'Clock ownership is immutable' using errcode='23514';
 end if;
 return new;
end; $$;
create trigger billing_clock_intent_identity before update or delete on stripe_billing_clock_intents
 for each row execute function protect_billing_clock_intent();
create function protect_billing_clock_ready() returns trigger language plpgsql as $$
begin
 if tg_op='DELETE' or new is distinct from old then
  raise exception 'Clock READY evidence is immutable' using errcode='23514';
 end if;
 return new;
end; $$;
create trigger billing_clock_ready_identity before update or delete on stripe_billing_clock_ready
 for each row execute function protect_billing_clock_ready();
-- SQL cannot manufacture a clock ACK for another owner or request, even when bypassing Java.
create function require_billing_clock_operation_proof() returns trigger language plpgsql as $$
begin
 if new.kind='TEST_CLOCK_CREATE' and not exists (
  select 1 from stripe_billing_clock_intents i where i.id=new.resource_id and i.payment_account_id=new.payment_account_id
   and i.external_account_id=new.external_account_id and i.credentials_ref=new.credentials_ref
   and i.create_key=new.operation_key and i.create_fingerprint=new.fingerprint
   and (new.status<>'ACKNOWLEDGED' or (i.stopped_at is null and new.provider_reference ~ '^clock_[A-Za-z0-9]{1,249}$'))
 ) then raise exception 'Clock operation lacks original ownership' using errcode='23514'; end if;
 return new;
end; $$;
create trigger billing_clock_operation_proof before insert or update on provider_operations
 for each row execute function require_billing_clock_operation_proof();
