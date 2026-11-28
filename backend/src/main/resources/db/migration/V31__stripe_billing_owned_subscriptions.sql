-- Original owned initial Subscription authority. Existing migrations and ordinary identities are unchanged.
-- Name discovery avoids depending on PostgreSQL's numbering of V30's anonymous CHECK constraints.
do $$ declare c record; begin
 for c in select conname from pg_constraint where conrelid='stripe_billing_clock_intents'::regclass
  and contype='c' and pg_get_constraintdef(oid) like '%CLOCK_CREATE_FAILED%'
 loop execute format('alter table stripe_billing_clock_intents drop constraint %I',c.conname); end loop;
end $$;
alter table stripe_billing_clock_intents add constraint billing_clock_stop_reason check(
 (stopped_at is null and stop_reason is null) or (stopped_at is not null and stopped_at>=admitted_at and stop_reason in
 ('CLOCK_CREATE_FAILED','CLOCK_READY_FAILED','CUSTOMER_CREATE_FAILED','BINDING_FAILED','SUBSCRIPTION_CREATE_FAILED','SUBSCRIPTION_RECEIPT_FAILED','AUTHORITY_REVOKED')));
alter table stripe_billing_clock_ready add constraint uq_owned_subscription_ready unique(intent_id,payment_account_id,site_id,customer_id);
alter table stripe_billing_customers add constraint uq_owned_subscription_customer unique(id,payment_account_id,site_id,customer_id,creation_operation_id,provider_reference);
alter table subscriptions add constraint uq_owned_subscription_owner unique(id,payment_account_id,site_id,customer_id);
alter table subscription_operations add constraint uq_owned_subscription_create unique(id,site_id,customer_id,subscription_id,kind,provider_operation_key,fingerprint,expected_revision,policy_version);

create table stripe_billing_owned_subscriptions (
 id uuid primary key, clock_intent uuid not null unique,
 payment_account_id uuid not null, site_id uuid not null, customer_id uuid not null,
 subscription_id uuid not null unique, create_operation uuid not null unique,
 create_kind varchar(32) not null default 'CREATE' check(create_kind='CREATE'),
 customer_binding uuid not null, customer_creation_operation uuid not null, customer_reference varchar(255) not null,
 operation_key varchar(255) not null unique, request_fingerprint varchar(255) not null check(request_fingerprint ~ '^v1\.paid\.[a-f0-9]{64}$'),
 expected_revision bigint not null check(expected_revision>=0), policy_version bigint not null check(policy_version>=0),
 price_id uuid not null, price_key varchar(63) not null, price_version integer not null check(price_version>0), product_id uuid not null,
 amount_minor bigint not null check(amount_minor>0), currency char(3) not null check(currency ~ '^[A-Z]{3}$'),
 billing_interval varchar(16) not null check(billing_interval in ('MONTH','YEAR')), trial_days integer not null check(trial_days=0),
 trial_policy varchar(32) not null check(trial_policy in ('REQUIRED_BEFORE_TRIAL','OPTIONAL_DURING_TRIAL')),
 tax_mode varchar(16) not null check(tax_mode in ('NONE','INCLUSIVE','EXCLUSIVE')),
 price_reference varchar(255) not null check(price_reference ~ '^price_[A-Za-z0-9]{1,249}$'),
 product_reference varchar(255) not null check(product_reference ~ '^prod_[A-Za-z0-9]{1,250}$'),
 fingerprint varchar(64) not null check(fingerprint ~ '^[a-f0-9]{64}$'), admitted_at timestamptz not null,
 unique(id,payment_account_id,create_operation),
 foreign key(clock_intent,payment_account_id,site_id,customer_id) references stripe_billing_clock_ready(intent_id,payment_account_id,site_id,customer_id),
 foreign key(customer_binding,payment_account_id,site_id,customer_id,customer_creation_operation,customer_reference)
  references stripe_billing_customers(id,payment_account_id,site_id,customer_id,creation_operation_id,provider_reference),
 foreign key(subscription_id,payment_account_id,site_id,customer_id) references subscriptions(id,payment_account_id,site_id,customer_id),
 foreign key(create_operation,site_id,customer_id,subscription_id,create_kind,operation_key,request_fingerprint,expected_revision,policy_version)
  references subscription_operations(id,site_id,customer_id,subscription_id,kind,provider_operation_key,fingerprint,expected_revision,policy_version),
 foreign key(site_id,product_id,price_id,price_version) references prices(site_id,product_id,id,price_version),
 check(operation_key='subscription:'||payment_account_id::text||':'||create_operation::text||':CREATE')
);
create table stripe_billing_owned_subscription_receipts (
 intent_id uuid primary key, payment_account_id uuid not null, create_operation uuid not null,
 provider_operation uuid not null unique,
 kind varchar(32) not null default 'SUBSCRIPTION_CREATE' check(kind='SUBSCRIPTION_CREATE'),
 reference varchar(255) not null check(reference ~ '^sub_[A-Za-z0-9]{1,251}$'),
 provider_created bigint not null check(provider_created>0),
 item_reference varchar(255) not null check(item_reference ~ '^si_[A-Za-z0-9]{1,252}$'),
 period_start bigint not null check(period_start>0), period_end bigint not null check(period_end>period_start),
 acknowledged_at timestamptz not null,
 foreign key(intent_id,payment_account_id,create_operation) references stripe_billing_owned_subscriptions(id,payment_account_id,create_operation),
 foreign key(provider_operation,payment_account_id,create_operation,kind,reference)
  references provider_operations(id,payment_account_id,resource_id,kind,provider_reference)
);
create function protect_owned_subscription_evidence() returns trigger language plpgsql as $$
begin
 if tg_op='DELETE' or new is distinct from old then raise exception 'Owned Subscription evidence is immutable' using errcode='23514'; end if;
 return new;
end $$;
create trigger owned_subscription_intent_immutable before update or delete on stripe_billing_owned_subscriptions
 for each row execute function protect_owned_subscription_evidence();
create trigger owned_subscription_receipt_immutable before update or delete on stripe_billing_owned_subscription_receipts
 for each row execute function protect_owned_subscription_evidence();

create function require_owned_subscription_intent() returns trigger language plpgsql as $$
begin
 if not exists (
  select 1 from stripe_billing_clock_intents c join stripe_billing_clock_ready r on r.intent_id=c.id
  join provider_operations k on k.id=r.creation_operation_id and k.kind='TEST_CLOCK_CREATE' and k.status='ACKNOWLEDGED'
  join provider_operations b on b.id=new.customer_creation_operation and b.kind='CUSTOMER_CREATE' and b.status='ACKNOWLEDGED'
  join subscriptions s on s.id=new.subscription_id join subscription_operations o on o.id=new.create_operation
  join prices p on p.id=new.price_id and p.site_id=new.site_id and p.product_id=new.product_id
  join products g on g.id=p.product_id and g.site_id=p.site_id
  join provider_price_mappings pm on pm.payment_account_id=new.payment_account_id and pm.price_id=p.id
  join provider_product_mappings gm on gm.payment_account_id=new.payment_account_id and gm.product_id=p.product_id
  where c.id=new.clock_intent and c.stopped_at is null and new.admitted_at>=r.observed_at
   and k.operation_key=c.create_key and k.fingerprint=c.create_fingerprint and k.external_account_id=c.external_account_id and k.credentials_ref=c.credentials_ref
   and b.payment_account_id=c.payment_account_id and b.resource_id=c.customer_id and b.provider_reference=new.customer_reference
   and b.operation_key='billing_customer_'||c.payment_account_id::text||'_'||c.site_id::text||'_'||c.customer_id::text
   and b.fingerprint=r.customer_fingerprint and b.external_account_id=c.external_account_id and b.credentials_ref=c.credentials_ref
   and s.provider_reference is null and s.billing_status='INCOMPLETE' and s.entitled_price_id is null
   and s.current_period_start is null and s.current_period_end is null and s.paid_through is null and s.trial_start is null and s.trial_end is null
   and s.debt_billing_record_id is null and s.pending_operation_id is null and s.cancellation_effective_at is null
   and o.status in ('RESERVED','PROCESSING') and o.provider_reference is null and s.revision=new.expected_revision
   and s.billing_price_id=new.price_id and s.billing_price_key=new.price_key and s.billing_price_version=new.price_version
   and s.billing_amount_minor=new.amount_minor and s.currency=new.currency and s.billing_interval=new.billing_interval
   and s.billing_trial_days=new.trial_days and s.billing_trial_policy=new.trial_policy and s.billing_tax_mode=new.tax_mode
   and p.status='PUBLISHED' and p.published_at is not null and p.price_type='SUBSCRIPTION' and g.status='ACTIVE'
   and p.price_key=new.price_key and p.price_version=new.price_version and p.amount_minor=new.amount_minor and p.currency=new.currency
   and p.billing_interval=new.billing_interval and p.trial_days=new.trial_days and p.trial_payment_method_policy=new.trial_policy and p.tax_mode=new.tax_mode
   and pm.provider_price_id=new.price_reference and gm.provider_product_id=new.product_reference
   and not exists(select 1 from provider_operations x where x.payment_account_id=new.payment_account_id
    and (x.resource_id=new.create_operation or x.operation_key=new.operation_key))
 ) then raise exception 'Owned Subscription lacks original pristine authority' using errcode='23514'; end if;
 return new;
end $$;
create trigger owned_subscription_intent_proof before insert on stripe_billing_owned_subscriptions
 for each row execute function require_owned_subscription_intent();

create function require_owned_subscription_receipt() returns trigger language plpgsql as $$
begin
 if not exists (
  select 1 from stripe_billing_owned_subscriptions i join stripe_billing_clock_intents c on c.id=i.clock_intent
  join stripe_billing_clock_ready r on r.intent_id=c.id join provider_operations p on p.id=new.provider_operation
  where i.id=new.intent_id and c.stopped_at is null and p.status='ACKNOWLEDGED'
   and p.operation_key=i.operation_key and p.fingerprint=i.fingerprint
   and p.external_account_id=c.external_account_id and p.credentials_ref=c.credentials_ref
   and p.first_dispatched_at is not null and new.acknowledged_at>=p.first_dispatched_at and new.acknowledged_at>=i.admitted_at
   and new.period_start<=r.frozen_time and r.frozen_time<new.period_end
 ) then raise exception 'Owned Subscription receipt lacks original ACK' using errcode='23514'; end if;
 return new;
end $$;
create trigger owned_subscription_receipt_proof before insert on stripe_billing_owned_subscription_receipts
 for each row execute function require_owned_subscription_receipt();

-- Deferred completeness permits ACK then INSERT in one transaction, never an incomplete committed ACK.
create function require_owned_subscription_ack_receipt() returns trigger language plpgsql as $$
begin
 if new.kind='SUBSCRIPTION_CREATE' and new.status='ACKNOWLEDGED' and exists(
  select 1 from stripe_billing_owned_subscriptions i where i.payment_account_id=new.payment_account_id and i.create_operation=new.resource_id
 ) and not exists(
  select 1 from stripe_billing_owned_subscriptions i join stripe_billing_owned_subscription_receipts r on r.intent_id=i.id
  where i.payment_account_id=new.payment_account_id and i.create_operation=new.resource_id and r.provider_operation=new.id
   and r.reference=new.provider_reference and i.operation_key=new.operation_key and i.fingerprint=new.fingerprint
 ) then raise exception 'Owned Subscription ACK requires atomic receipt' using errcode='23514'; end if;
 return null;
end $$;
create constraint trigger owned_subscription_ack_receipt after insert or update on provider_operations
 deferrable initially deferred for each row execute function require_owned_subscription_ack_receipt();

create function require_owned_subscription_operation() returns trigger language plpgsql as $$
declare i stripe_billing_owned_subscriptions; c stripe_billing_clock_intents; old_owned boolean := false;
begin
 -- Inspect OLD before NEW: a row cannot escape ownership by changing kind/account/resource/id.
 if tg_op in ('UPDATE','DELETE') and old.kind='SUBSCRIPTION_CREATE' then
  select * into i from stripe_billing_owned_subscriptions where payment_account_id=old.payment_account_id and create_operation=old.resource_id;
  old_owned := found;
  if old_owned then
   if tg_op='DELETE' then
    raise exception 'Owned Subscription original step cannot be deleted' using errcode='23514'; end if;
   if row(new.id,new.payment_account_id,new.kind,new.resource_id,new.operation_key,new.fingerprint,new.external_account_id,new.credentials_ref)
     is distinct from row(old.id,old.payment_account_id,old.kind,old.resource_id,old.operation_key,old.fingerprint,old.external_account_id,old.credentials_ref) then
    raise exception 'Owned Subscription original step identity is immutable' using errcode='23514'; end if;
   if old.first_dispatched_at is not null and
     (row(new.first_dispatched_at,new.retry_before) is distinct from row(old.first_dispatched_at,old.retry_before) or new.status='RESERVED') then
    raise exception 'Owned Subscription consumed dispatch history is immutable' using errcode='23514'; end if;
  end if;
 end if;
 if tg_op='DELETE' then return old; end if;
 if new.kind<>'SUBSCRIPTION_CREATE' then return new; end if;
 select * into i from stripe_billing_owned_subscriptions where payment_account_id=new.payment_account_id and create_operation=new.resource_id;
 if not found then return new; end if;
 -- Only INSERT can establish the original reserved step. An ordinary row cannot be repurposed into it.
 if tg_op='UPDATE' and not old_owned then
  raise exception 'Owned Subscription requires its original reserved step' using errcode='23514'; end if;
 select * into c from stripe_billing_clock_intents where id=i.clock_intent;
 if new.operation_key<>i.operation_key or new.fingerprint<>i.fingerprint or new.external_account_id<>c.external_account_id or new.credentials_ref<>c.credentials_ref then
  raise exception 'Owned Subscription step identity mismatch' using errcode='23514'; end if;
 if tg_op='INSERT' and (new.status<>'RESERVED' or new.first_dispatched_at is not null) then
  raise exception 'Owned Subscription requires original reservation' using errcode='23514'; end if;
 if tg_op='UPDATE' and new.status='DISPATCHING' and
   (old.status<>'RESERVED' or old.first_dispatched_at is not null or c.stopped_at is not null) then
  raise exception 'Owned Subscription dispatch is one use' using errcode='23514'; end if;
 if tg_op='UPDATE' and new.status='ACKNOWLEDGED' and new is distinct from old and
   (old.status<>'DISPATCHING' or old.lease_owner is null or old.lease_expires_at<=clock_timestamp() or c.stopped_at is not null) then
  raise exception 'Owned Subscription ACK requires original live claim' using errcode='23514'; end if;
 return new;
end $$;
create trigger owned_subscription_operation_proof before insert or update or delete on provider_operations
 for each row execute function require_owned_subscription_operation();
