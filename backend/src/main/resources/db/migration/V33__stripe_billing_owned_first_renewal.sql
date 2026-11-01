-- Additive evidence relations. The original clock and initial-only evidence stay unchanged.
create table stripe_billing_first_renewals (
 id uuid primary key, owned_intent uuid not null unique references stripe_billing_owned_subscriptions(id),
 payment_account_id uuid not null, site_id uuid not null, customer_id uuid not null, subscription_id uuid not null unique,
 source_sha varchar(40) not null check(source_sha ~ '^[a-f0-9]{40}$'),
 original_root uuid not null references subscription_action_capabilities(id),
 original_attempt uuid not null references subscription_confirmation_attempts(id), attempt_version bigint not null check(attempt_version>=1),
 initial_bill uuid not null references subscription_billing_records(id), bill_version bigint not null check(bill_version>=0),
 initial_payment uuid not null references payments(id), payment_version bigint not null check(payment_version>=0),
 invoice_reference varchar(255) not null, allocation_reference varchar(255) not null, payment_intent_reference varchar(255) not null,
 subscription_revision bigint not null check(subscription_revision>=0), policy_version bigint not null check(policy_version>=0),
 fingerprint varchar(64) not null check(fingerprint ~ '^[a-f0-9]{64}$'), admitted_at timestamptz not null,
 stopped_at timestamptz, stop_reason varchar(32),
 unique(id,payment_account_id,site_id,customer_id,subscription_id),
 foreign key(subscription_id,payment_account_id,site_id,customer_id) references subscriptions(id,payment_account_id,site_id,customer_id),
 check(num_nonnulls(stopped_at,stop_reason) in (0,2)),
 check((stopped_at is null and stop_reason is null) or (stopped_at>=admitted_at and stop_reason in
  ('AUTHORITY_DRIFT','ADVANCE_UNKNOWN','READY_UNAVAILABLE','GRAPH_UNSUPPORTED','PAYMENT_NOT_OBSERVED')))
);

-- Read only. Caller owns lifecycle locks, or provider-step -> Site -> Customer -> Account.
-- A consumed association/OBSERVE is only one relation in this complete paid proof.
-- This exact-ID proof follows a selective FK chain. Bound join-order search to its declared
-- order inside this function only; unconstrained planning held lifecycle locks for seconds.
create function owned_first_renewal_paid(owned_id uuid) returns table(
 root_id uuid,attempt_id uuid,attempt_version bigint,bill_id uuid,bill_version bigint,payment_id uuid,payment_version bigint,
 invoice_reference varchar,allocation_reference varchar,payment_intent_reference varchar,
 subscription_revision bigint,policy_version bigint,root_proof varchar)
 language sql stable set join_collapse_limit=1 as $$
 select g.id,t.id,t.version,b.id,b.version,p.id,p.version,g.billing_invoice_reference,g.billing_allocation_reference,
  g.billing_intent_reference,s.revision,i.policy_version,g.billing_proof
 from stripe_billing_owned_subscriptions i
 join stripe_billing_clock_intents c on c.id=i.clock_intent
 join stripe_billing_clock_ready ready on ready.intent_id=c.id
 join stripe_billing_owned_subscription_receipts r on r.intent_id=i.id
 join provider_operations step on step.id=r.provider_operation
 join provider_operations clock_step on clock_step.id=ready.creation_operation_id
 join provider_operations customer_step on customer_step.id=i.customer_creation_operation
 join stripe_billing_customers binding on binding.id=i.customer_binding
 join payment_accounts a on a.id=i.payment_account_id
 join sites site on site.id=i.site_id
 join customers customer on customer.id=i.customer_id
 join subscriptions s on s.id=i.subscription_id
 join subscription_operations o on o.id=i.create_operation
 join site_subscription_policies policy on policy.site_id=i.site_id
 join prices price on price.id=i.price_id and price.site_id=i.site_id and price.product_id=i.product_id
 join products product on product.id=i.product_id and product.site_id=i.site_id
 join provider_price_mappings pm on pm.payment_account_id=i.payment_account_id and pm.price_id=i.price_id
 join provider_product_mappings gm on gm.payment_account_id=i.payment_account_id and gm.product_id=i.product_id
 join subscription_action_capabilities g on g.billing_owned_intent=i.id
 join subscription_confirmation_chains chain on chain.id=g.id and chain.operation_id=i.create_operation
 join subscription_confirmation_attempts t on t.chain_id=chain.id
 join subscription_billing_records b on b.subscription_id=s.id and b.site_id=i.site_id and b.payment_account_id=i.payment_account_id
 join payments p on p.billing_record_id=b.id
 where i.id=owned_id and c.stopped_at is null and c.api_version='2026-09-30.endive'
  and a.account_status='ACTIVE' and a.provider='STRIPE' and a.account_mode='SANDBOX'
  and a.external_account_id=c.external_account_id and a.credentials_ref=c.credentials_ref
  and site.status='ACTIVE' and site.payment_account_id=a.id and customer.status='ACTIVE' and customer.site_id=site.id
  and ready.frozen_time=c.initial_frozen_time and r.period_start<=ready.frozen_time and ready.frozen_time<r.period_end
  and clock_step.kind='TEST_CLOCK_CREATE' and clock_step.status='ACKNOWLEDGED' and clock_step.provider_reference=ready.reference
  and clock_step.operation_key=c.create_key and clock_step.fingerprint=c.create_fingerprint
  and clock_step.external_account_id=c.external_account_id and clock_step.credentials_ref=c.credentials_ref
  and customer_step.kind='CUSTOMER_CREATE' and customer_step.status='ACKNOWLEDGED' and customer_step.provider_reference=i.customer_reference
  and customer_step.operation_key='billing_customer_'||c.payment_account_id::text||'_'||c.site_id::text||'_'||c.customer_id::text
  and customer_step.fingerprint=ready.customer_fingerprint and customer_step.external_account_id=c.external_account_id and customer_step.credentials_ref=c.credentials_ref
  and binding.creation_operation_id=customer_step.id and binding.provider_reference=i.customer_reference
  and step.kind='SUBSCRIPTION_CREATE' and step.status='ACKNOWLEDGED' and step.provider_reference=r.reference
  and step.operation_key=i.operation_key and step.fingerprint=i.fingerprint
  and step.external_account_id=c.external_account_id and step.credentials_ref=c.credentials_ref and step.first_dispatched_at is not null
  and policy.enabled and policy.version=i.policy_version
  and o.kind='CREATE' and o.status='SUCCEEDED' and o.site_id=i.site_id and o.customer_id=i.customer_id and o.subscription_id=s.id
  and o.provider_operation_key=i.operation_key and o.fingerprint=i.request_fingerprint
  and o.expected_revision=i.expected_revision and o.policy_version=i.policy_version and o.provider_reference=r.reference
  and s.payment_account_id=i.payment_account_id and s.site_id=i.site_id and s.customer_id=i.customer_id
  and s.provider_reference=r.reference and s.billing_status='ACTIVE'
  and s.current_period_start=to_timestamp(r.period_start) and s.current_period_end=to_timestamp(r.period_end) and s.paid_through=to_timestamp(r.period_end)
  and s.trial_start is null and s.trial_end is null and s.debt_billing_record_id is null
  and s.pending_operation_id is null and s.cancellation_effective_at is null and s.terminal_reason is null
  and s.billing_price_id=i.price_id and s.billing_price_key=i.price_key and s.billing_price_version=i.price_version and s.product_id=i.product_id
  and s.billing_amount_minor=i.amount_minor and s.currency=i.currency and s.billing_interval=i.billing_interval
  and s.billing_trial_days=i.trial_days and s.billing_trial_policy=i.trial_policy and s.billing_tax_mode=i.tax_mode
  and row(s.entitled_price_id,s.entitled_price_key,s.entitled_price_version,s.entitled_amount_minor,s.entitled_interval,s.entitled_trial_days,s.entitled_trial_policy,s.entitled_tax_mode)
   =row(i.price_id,i.price_key,i.price_version,i.amount_minor,i.billing_interval,i.trial_days,i.trial_policy,i.tax_mode)
  and price.price_key=i.price_key and price.price_version=i.price_version and price.amount_minor=i.amount_minor and price.currency=i.currency
  and price.billing_interval=i.billing_interval and price.trial_days=0 and price.trial_payment_method_policy=i.trial_policy and price.tax_mode=i.tax_mode
  and price.price_type='SUBSCRIPTION' and price.published_at is not null and price.status in ('PUBLISHED','ARCHIVED') and product.status='ACTIVE'
  and pm.provider_price_id=i.price_reference and gm.provider_product_id=i.product_reference
  and not exists(select 1 from subscription_operations x where x.subscription_id=s.id and x.kind not in ('PORTAL','RECONCILE') and x.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN'))
  and g.billing_schema='owned-initial-v1' and g.billing_step_id=r.provider_operation and g.billing_step_kind='SUBSCRIPTION_CREATE'
  and g.billing_operation_key=i.operation_key and g.billing_subscription_reference=r.reference and g.billing_step_reference=r.reference
  and g.billing_customer_reference=i.customer_reference and g.operation_id=o.id and g.subscription_id=s.id
  and g.payment_account_id=i.payment_account_id and g.site_id=i.site_id and g.customer_id=i.customer_id
  and g.consumed_at is not null and g.superseded_at is null and g.client_session_id is null and g.confirmation_key_digest is null
  and t.predecessor is null and t.successor is null and t.retry_key is null and t.request_id is null
  and t.claimed_at is not null and t.confirm_key is not null and t.token_digest is not null and t.fingerprint is not null
  and t.state in ('DISPATCHING','OBSERVE','REQUIRES_ACTION','UNKNOWN') and t.version>=1
  and not exists(select 1 from subscription_confirmation_attempts other where other.chain_id=chain.id and other.id<>t.id)
  and b.kind='INITIAL' and b.settlement='CONFIRMED_CASH' and b.product_id=i.product_id and b.provider_invoice_reference=g.billing_invoice_reference
  and b.billed_price_id=i.price_id and b.billed_price_key=i.price_key and b.billed_price_version=i.price_version and b.billed_amount_minor=i.amount_minor
  and b.billed_interval=i.billing_interval and b.billed_trial_days=0 and b.billed_trial_policy=i.trial_policy and b.billed_tax_mode=i.tax_mode
  and b.currency=i.currency and b.amount_due_minor=i.amount_minor and b.previous_price_id is null
  and b.period_start=to_timestamp(r.period_start) and b.period_end=to_timestamp(r.period_end)
  and (select count(*) from subscription_billing_records other where other.subscription_id=s.id)=1
  and p.source='SUBSCRIPTION' and p.status='SUCCEEDED' and p.refunded_amount_minor=0
  and p.payment_account_id=i.payment_account_id and p.site_id=i.site_id and p.customer_id=i.customer_id
  and p.provider_payment_reference=g.billing_intent_reference and p.subscription_id=s.id and p.subscription_billing_kind='INITIAL'
  and p.subscription_product_id=i.product_id and p.subscription_price_id=i.price_id and p.subscription_price_version=i.price_version
  and p.subscription_previous_price_id is null and p.subscription_previous_price_version is null
  and p.subscription_period_start=b.period_start and p.subscription_period_end=b.period_end and p.subscription_invoice_reference=b.provider_invoice_reference
  and p.amount_minor=i.amount_minor and p.currency=i.currency and p.tax_mode=i.tax_mode and p.tax_amount_minor is null
  and (select count(*) from payments other where other.billing_record_id=b.id)=1;
$$;
create table stripe_billing_first_renewal_steps (
 id uuid primary key, admission_id uuid not null references stripe_billing_first_renewals(id),
 ordinal integer not null check(ordinal in (1,2)), target bigint not null check(target>0),
 predecessor_ready uuid, boundary_id uuid,
 operation_key varchar(255) not null unique, fingerprint varchar(64) not null check(fingerprint ~ '^[a-f0-9]{64}$'),
 prepared_at timestamptz not null, unique(admission_id,ordinal), unique(id,admission_id),
 check(operation_key='billing-renewal-advance-v1:'||admission_id::text||':'||ordinal::text),
 check((ordinal=1 and predecessor_ready is null and boundary_id is null) or (ordinal=2 and predecessor_ready is not null and boundary_id is not null))
);
create table stripe_billing_first_renewal_acks (
 step_id uuid primary key, admission_id uuid not null, provider_operation uuid not null unique references provider_operations(id),
 claimant varchar(255) not null, deadline timestamptz not null,
 reference varchar(255) not null, object_type varchar(32) not null, livemode boolean not null,
 name varchar(255) not null, frozen_time bigint not null, status varchar(32) not null,
 provider_created bigint not null, provider_deletes_after bigint not null, advancing_target bigint, acknowledged_at timestamptz not null,
 unique(step_id,admission_id),
 foreign key(step_id,admission_id) references stripe_billing_first_renewal_steps(id,admission_id)
);
create table stripe_billing_first_renewal_ready (
 id uuid primary key, admission_id uuid not null, step_id uuid not null unique,
 reference varchar(255) not null, object_type varchar(32) not null, livemode boolean not null,
 name varchar(255) not null, frozen_time bigint not null, status varchar(32) not null,
 provider_created bigint not null, provider_deletes_after bigint not null, advancing_target bigint, observed_at timestamptz not null,
 unique(id,admission_id), foreign key(step_id,admission_id) references stripe_billing_first_renewal_acks(step_id,admission_id)
);
create table stripe_billing_first_renewal_boundary (
 id uuid primary key, admission_id uuid not null unique references stripe_billing_first_renewals(id), ready_id uuid not null unique,
 item_reference varchar(255) not null, invoice_reference varchar(255) not null,
 period_start timestamptz not null, period_end timestamptz not null check(period_start<period_end),
 fingerprint varchar(64) not null check(fingerprint ~ '^[a-f0-9]{64}$'), observed_at timestamptz not null,
 unique(id,admission_id), foreign key(ready_id,admission_id) references stripe_billing_first_renewal_ready(id,admission_id)
);
alter table stripe_billing_first_renewal_steps add foreign key(predecessor_ready,admission_id) references stripe_billing_first_renewal_ready(id,admission_id);
alter table stripe_billing_first_renewal_steps add foreign key(boundary_id,admission_id) references stripe_billing_first_renewal_boundary(id,admission_id);
create table stripe_billing_first_renewal_publications (
 admission_id uuid primary key references stripe_billing_first_renewals(id), ready_id uuid not null unique,
 boundary_id uuid not null unique, renewal_bill uuid not null unique references subscription_billing_records(id),
 renewal_payment uuid not null unique references payments(id), projected_revision bigint not null check(projected_revision>=0), published_at timestamptz not null,
 foreign key(ready_id,admission_id) references stripe_billing_first_renewal_ready(id,admission_id),
 foreign key(boundary_id,admission_id) references stripe_billing_first_renewal_boundary(id,admission_id)
);

create function protect_first_renewal_admission() returns trigger language plpgsql as $$
begin
 if tg_op='DELETE' then raise exception 'First renewal admission is immutable' using errcode='23514'; end if;
 if tg_op='UPDATE' then
  if (to_jsonb(new)-'stopped_at'-'stop_reason') is distinct from (to_jsonb(old)-'stopped_at'-'stop_reason')
   or old.stopped_at is not null and new is distinct from old then
   raise exception 'First renewal admission is immutable' using errcode='23514'; end if;
  return new;
 end if;
 if new.stopped_at is not null or not exists(
  select 1 from stripe_billing_owned_subscriptions i
  join stripe_billing_owned_subscription_receipts r on r.intent_id=i.id
  join owned_first_renewal_paid(i.id) p on true
  where i.id=new.owned_intent and i.payment_account_id=new.payment_account_id and i.site_id=new.site_id
   and i.customer_id=new.customer_id and i.subscription_id=new.subscription_id and new.admitted_at>=r.acknowledged_at
   and p.root_id=new.original_root and p.attempt_id=new.original_attempt and p.attempt_version=new.attempt_version and p.bill_id=new.initial_bill and p.bill_version=new.bill_version
   and p.payment_id=new.initial_payment and p.payment_version=new.payment_version
   and p.invoice_reference=new.invoice_reference and p.allocation_reference=new.allocation_reference
   and p.payment_intent_reference=new.payment_intent_reference and p.subscription_revision=new.subscription_revision and p.policy_version=new.policy_version
 ) then raise exception 'First renewal admission lacks exact paid predecessor' using errcode='23514'; end if;
 return new;
end $$;
create trigger first_renewal_admission_identity before insert or update or delete on stripe_billing_first_renewals
 for each row execute function protect_first_renewal_admission();

create function protect_first_renewal_evidence() returns trigger language plpgsql as $$
begin
 if tg_op='DELETE' or new is distinct from old then raise exception 'First renewal evidence is immutable' using errcode='23514'; end if;
 return new;
end $$;
create trigger first_renewal_step_immutable before update or delete on stripe_billing_first_renewal_steps for each row execute function protect_first_renewal_evidence();
create trigger first_renewal_ack_immutable before update or delete on stripe_billing_first_renewal_acks for each row execute function protect_first_renewal_evidence();
create trigger first_renewal_ready_immutable before update or delete on stripe_billing_first_renewal_ready for each row execute function protect_first_renewal_evidence();
create trigger first_renewal_boundary_immutable before update or delete on stripe_billing_first_renewal_boundary for each row execute function protect_first_renewal_evidence();
create trigger first_renewal_publication_immutable before update or delete on stripe_billing_first_renewal_publications for each row execute function protect_first_renewal_evidence();

create function require_first_renewal_step() returns trigger language plpgsql as $$
begin
 if not exists(select 1 from stripe_billing_first_renewals a
  join stripe_billing_owned_subscription_receipts r on r.intent_id=a.owned_intent
  join stripe_billing_owned_subscriptions i on i.id=a.owned_intent
  join stripe_billing_clock_ready original on original.intent_id=i.clock_intent
  join owned_first_renewal_paid(a.owned_intent) p on true
  where a.id=new.admission_id and a.stopped_at is null and new.prepared_at>=a.admitted_at
   and p.root_id=a.original_root and p.attempt_id=a.original_attempt and p.attempt_version=a.attempt_version and p.bill_id=a.initial_bill and p.bill_version=a.bill_version
   and p.payment_id=a.initial_payment and p.payment_version=a.payment_version and p.subscription_revision=a.subscription_revision
   and ((new.ordinal=1 and new.target=r.period_end and new.target>original.frozen_time)
    or (new.ordinal=2 and new.target=r.period_end+3600 and exists(
     select 1 from stripe_billing_first_renewal_ready ready
     join stripe_billing_first_renewal_steps first_step on first_step.id=ready.step_id and first_step.ordinal=1
     join stripe_billing_first_renewal_boundary b on b.ready_id=ready.id and b.admission_id=a.id
     where ready.id=new.predecessor_ready and ready.admission_id=a.id and b.id=new.boundary_id
      and ready.status='ready' and ready.frozen_time=r.period_end and b.period_start=to_timestamp(r.period_end)
      and b.period_start<to_timestamp(new.target) and to_timestamp(new.target)<b.period_end)))
 ) then raise exception 'First renewal step lacks exact paid predecessor' using errcode='23514'; end if;
 return new;
end $$;
create trigger first_renewal_step_proof before insert on stripe_billing_first_renewal_steps for each row execute function require_first_renewal_step();

-- These evidence producers remain absent until their owning consumers are implemented.
-- Existing rows nevertheless cannot be replaced or detached through direct SQL.
create function require_first_renewal_ack() returns trigger language plpgsql as $$
begin
 if not exists(select 1 from stripe_billing_first_renewal_steps s
  join stripe_billing_first_renewals a on a.id=s.admission_id
  join stripe_billing_owned_subscriptions i on i.id=a.owned_intent
  join stripe_billing_clock_intents owner on owner.id=i.clock_intent
  join stripe_billing_clock_ready original on original.intent_id=owner.id
  join provider_operations p on p.id=new.provider_operation
  where s.id=new.step_id and s.admission_id=new.admission_id and a.stopped_at is null and owner.stopped_at is null
   and p.payment_account_id=a.payment_account_id and p.resource_id=s.id and p.kind='TEST_CLOCK_ADVANCE' and p.status='ACKNOWLEDGED'
   and p.operation_key=s.operation_key and p.fingerprint=s.fingerprint and p.provider_reference=original.reference
   and p.external_account_id=owner.external_account_id and p.credentials_ref=owner.credentials_ref and p.first_dispatched_at is not null
   and new.reference=original.reference and new.object_type='test_helpers.test_clock' and not new.livemode and new.name=owner.name
   and new.provider_created=original.provider_created and new.provider_deletes_after=original.provider_deletes_after
   and new.status in ('ready','advancing') and new.frozen_time>=original.frozen_time and new.frozen_time<=s.target
   and (new.advancing_target is null or new.advancing_target=s.target)
   and length(trim(new.claimant))>0 and new.acknowledged_at>=p.first_dispatched_at and new.acknowledged_at<new.deadline
 ) then raise exception 'First renewal ACK lacks original owned dispatch' using errcode='23514'; end if;
 return new;
end $$;
create trigger first_renewal_ack_proof before insert on stripe_billing_first_renewal_acks for each row execute function require_first_renewal_ack();
create function require_first_renewal_ready() returns trigger language plpgsql as $$
begin
 if not exists(select 1 from stripe_billing_first_renewal_steps s
  join stripe_billing_first_renewal_acks a on a.step_id=s.id and a.admission_id=s.admission_id
  where s.id=new.step_id and s.admission_id=new.admission_id and new.reference=a.reference and new.name=a.name
   and new.object_type='test_helpers.test_clock' and not new.livemode and new.status='ready' and new.frozen_time=s.target
   and new.provider_created=a.provider_created and new.provider_deletes_after=a.provider_deletes_after
   and (new.advancing_target is null or new.advancing_target=s.target) and new.observed_at>=a.acknowledged_at
 ) then raise exception 'First renewal READY lacks exact ACK' using errcode='23514'; end if;
 return new;
end $$;
create trigger first_renewal_ready_proof before insert on stripe_billing_first_renewal_ready for each row execute function require_first_renewal_ready();
create function require_first_renewal_boundary() returns trigger language plpgsql as $$
begin
 if not exists(select 1 from stripe_billing_first_renewal_ready ready
  join stripe_billing_first_renewal_steps s on s.id=ready.step_id and s.admission_id=ready.admission_id
  join stripe_billing_first_renewals a on a.id=s.admission_id
  join stripe_billing_owned_subscription_receipts r on r.intent_id=a.owned_intent
  where ready.id=new.ready_id and ready.admission_id=new.admission_id and s.ordinal=1 and ready.status='ready'
   and new.period_start=to_timestamp(r.period_end) and new.item_reference=r.item_reference
   and new.invoice_reference<>a.invoice_reference and new.observed_at>=ready.observed_at
 ) then raise exception 'First renewal boundary lacks exact READY1' using errcode='23514'; end if;
 return new;
end $$;
create trigger first_renewal_boundary_proof before insert on stripe_billing_first_renewal_boundary for each row execute function require_first_renewal_boundary();
