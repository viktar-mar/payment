-- Additive evidence relations. The original clock and initial-only evidence stay unchanged.
alter table provider_operations drop constraint provider_operations_kind_check;
alter table provider_operations add constraint provider_operations_kind_check check(kind in (
 'PRODUCT_CREATE','PRICE_CREATE','PAYMENT_PREPARE','REFUND_CREATE','CUSTOMER_CREATE','TEST_CLOCK_CREATE','TEST_CLOCK_ADVANCE',
 'SUBSCRIPTION_CREATE','SUBSCRIPTION_UPDATE','SUBSCRIPTION_CANCEL','SETUP_INTENT_CREATE','INVOICE_PAY',
 'SCHEDULE_CREATE','SCHEDULE_UPDATE','SCHEDULE_RELEASE','PORTAL_CONFIGURATION_CREATE','PORTAL_SESSION_CREATE'));
drop index uq_provider_operation_reference;
create unique index uq_provider_operation_reference on provider_operations(payment_account_id,kind,provider_reference)
 where provider_reference is not null and kind not in ('SUBSCRIPTION_UPDATE','TEST_CLOCK_ADVANCE');
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
 unique(id,payment_account_id,site_id,customer_id,subscription_id), unique(id,payment_account_id),
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
 payment_account_id uuid not null, operation_kind varchar(32) not null default 'TEST_CLOCK_ADVANCE' check(operation_kind='TEST_CLOCK_ADVANCE'),
 claimant varchar(255) not null, deadline timestamptz not null,
 reference varchar(255) not null, object_type varchar(32) not null, livemode boolean not null,
 name varchar(255) not null, frozen_time bigint not null, status varchar(32) not null,
 provider_created bigint not null, provider_deletes_after bigint not null, advancing_target bigint, acknowledged_at timestamptz not null,
 unique(step_id,admission_id),
 foreign key(step_id,admission_id) references stripe_billing_first_renewal_steps(id,admission_id),
 foreign key(admission_id,payment_account_id) references stripe_billing_first_renewals(id,payment_account_id),
 foreign key(provider_operation,payment_account_id,step_id,operation_kind,reference)
  references provider_operations(id,payment_account_id,resource_id,kind,provider_reference)
);
create table stripe_billing_first_renewal_ready (
 id uuid primary key, admission_id uuid not null, step_id uuid not null unique,
 reference varchar(255) not null, object_type varchar(32) not null, livemode boolean not null,
 name varchar(255) not null, frozen_time bigint not null, status varchar(32) not null,
 provider_created bigint not null, provider_deletes_after bigint not null, advancing_target bigint, observed_at timestamptz not null,
 unique(id,admission_id), unique(id,admission_id,step_id), foreign key(step_id,admission_id) references stripe_billing_first_renewal_acks(step_id,admission_id)
);
-- Durable consume-before-graph receipt. No state, counter, takeover, refresh or provider operation.
create table stripe_billing_first_renewal_read_claims (
 ready_id uuid primary key, step_id uuid not null unique, admission_id uuid not null, invocation uuid not null unique,
 started_at timestamptz not null, deadline_at timestamptz not null check(deadline_at=started_at+interval '30 seconds'),
 captured_revision bigint not null check(captured_revision>=0), selected_operation uuid, selected_version bigint,
 observation_sequence bigint not null check(observation_sequence>=0), unique(ready_id,admission_id),
 check(num_nonnulls(selected_operation,selected_version) in (0,2)), check(selected_version is null or selected_version>=0),
 foreign key(ready_id,admission_id,step_id) references stripe_billing_first_renewal_ready(id,admission_id,step_id)
);
create table stripe_billing_first_renewal_boundary (
 id uuid primary key, admission_id uuid not null unique references stripe_billing_first_renewals(id), ready_id uuid not null unique,
 item_reference varchar(255) not null, invoice_reference varchar(255) not null,
 period_start timestamptz not null, period_end timestamptz not null check(period_start<period_end),
 fingerprint varchar(64) not null check(fingerprint ~ '^[a-f0-9]{64}$'), observed_at timestamptz not null,
 unique(id,admission_id), foreign key(ready_id,admission_id) references stripe_billing_first_renewal_ready(id,admission_id),
 foreign key(ready_id,admission_id) references stripe_billing_first_renewal_read_claims(ready_id,admission_id)
);
alter table stripe_billing_first_renewal_steps add foreign key(predecessor_ready,admission_id) references stripe_billing_first_renewal_ready(id,admission_id);
alter table stripe_billing_first_renewal_steps add foreign key(boundary_id,admission_id) references stripe_billing_first_renewal_boundary(id,admission_id);
create table stripe_billing_first_renewal_publications (
 admission_id uuid primary key references stripe_billing_first_renewals(id), ready_id uuid not null unique,
 boundary_id uuid not null unique, renewal_bill uuid not null unique references subscription_billing_records(id),
 renewal_payment uuid not null unique references payments(id), projected_revision bigint not null check(projected_revision>=0), published_at timestamptz not null, graph_accepted_at timestamptz not null,
 foreign key(ready_id,admission_id) references stripe_billing_first_renewal_ready(id,admission_id),
 foreign key(ready_id,admission_id) references stripe_billing_first_renewal_read_claims(ready_id,admission_id),
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
create trigger first_renewal_read_claim_immutable before update or delete on stripe_billing_first_renewal_read_claims for each row execute function protect_first_renewal_evidence();
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
   -- Conservative two-shortest-interval cap from the exact predecessor F/B; never derive N here.
   and new.target-case when new.ordinal=1 then original.frozen_time else r.period_end end
    between 1 and case i.billing_interval when 'MONTH' then 56::bigint*86400 when 'YEAR' then 730::bigint*86400 else 0 end
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

-- The advance producer owns ACK/READY. Boundary/read/publication consumers are added sequentially
-- before Task5 freezes this unreleased migration; only disposable hosted fixtures may apply it now.
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
   and new.status in ('ready','advancing') and new.frozen_time>=case when s.ordinal=1 then original.frozen_time else s.target-3600 end and new.frozen_time<=s.target
   and (new.advancing_target is null or new.advancing_target=s.target)
   and length(trim(new.claimant))>0 and new.acknowledged_at>=p.first_dispatched_at and new.acknowledged_at<new.deadline
   and new.deadline=p.first_dispatched_at+interval '60 seconds' and new.deadline>clock_timestamp() and new.acknowledged_at<=clock_timestamp()
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
   and new.observed_at<a.deadline and a.deadline>clock_timestamp() and new.observed_at<=clock_timestamp()
   and exists(select 1 from stripe_billing_first_renewals owner join stripe_billing_owned_subscriptions i on i.id=owner.owned_intent
    join stripe_billing_clock_intents c on c.id=i.clock_intent where owner.id=s.admission_id and owner.stopped_at is null and c.stopped_at is null)
   and not exists(select 1 from stripe_billing_first_renewal_steps higher where higher.admission_id=s.admission_id and higher.ordinal>s.ordinal)
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
  join stripe_billing_first_renewal_read_claims claim on claim.ready_id=ready.id and claim.admission_id=a.id
  join subscriptions local on local.id=a.subscription_id
  join owned_first_renewal_paid(a.owned_intent) paid on true
  where ready.id=new.ready_id and ready.admission_id=new.admission_id and s.ordinal=1 and ready.status='ready'
   and a.stopped_at is null and not exists(select 1 from stripe_billing_first_renewal_steps later where later.admission_id=a.id and later.ordinal>1)
   and claim.step_id=s.id and claim.captured_revision=local.revision and claim.captured_revision=a.subscription_revision
   and claim.observation_sequence=local.observation_sequence and claim.selected_operation is null and claim.selected_version is null
   and not exists(select 1 from subscription_trial_eligibility t where t.site_id=local.site_id and t.customer_id=local.customer_id
    and t.product_id=local.product_id and t.consumed_at is null and t.reserved_by_operation is not null)
   and paid.root_id=a.original_root and paid.attempt_id=a.original_attempt and paid.attempt_version=a.attempt_version
   and paid.bill_id=a.initial_bill and paid.bill_version=a.bill_version and paid.payment_id=a.initial_payment and paid.payment_version=a.payment_version
   and paid.invoice_reference=a.invoice_reference and paid.allocation_reference=a.allocation_reference and paid.payment_intent_reference=a.payment_intent_reference
   and paid.subscription_revision=a.subscription_revision and paid.policy_version=a.policy_version
   and new.period_start=to_timestamp(r.period_end) and new.item_reference=r.item_reference
   and new.period_end>to_timestamp(r.period_end+3600)
   and new.invoice_reference<>a.invoice_reference and new.observed_at>=claim.started_at
   and new.observed_at<claim.deadline_at and claim.deadline_at>clock_timestamp() and new.observed_at<=clock_timestamp()
 ) then raise exception 'First renewal boundary lacks exact READY1' using errcode='23514'; end if;
 return new;
end $$;
create trigger first_renewal_boundary_proof before insert on stripe_billing_first_renewal_boundary for each row execute function require_first_renewal_boundary();

create function require_first_renewal_read_claim() returns trigger language plpgsql as $$
begin
 if new.started_at>clock_timestamp() or new.deadline_at<=clock_timestamp() or not exists(
  select 1 from stripe_billing_first_renewal_ready r
  join stripe_billing_first_renewal_steps step on step.id=r.step_id and step.admission_id=r.admission_id
  join stripe_billing_first_renewals a on a.id=r.admission_id
  join subscriptions s on s.id=a.subscription_id
  join owned_first_renewal_paid(a.owned_intent) p on true
  where r.id=new.ready_id and r.step_id=new.step_id and r.admission_id=new.admission_id and a.stopped_at is null
   and new.started_at>=r.observed_at and new.captured_revision=a.subscription_revision and new.captured_revision=s.revision
   and new.observation_sequence=s.observation_sequence
   and p.root_id=a.original_root and p.attempt_id=a.original_attempt and p.attempt_version=a.attempt_version
   and p.bill_id=a.initial_bill and p.bill_version=a.bill_version and p.payment_id=a.initial_payment and p.payment_version=a.payment_version
   and p.invoice_reference=a.invoice_reference and p.allocation_reference=a.allocation_reference and p.payment_intent_reference=a.payment_intent_reference
   and p.subscription_revision=a.subscription_revision and p.policy_version=a.policy_version
   and not exists(select 1 from stripe_billing_first_renewal_steps higher where higher.admission_id=a.id and higher.ordinal>step.ordinal)
   and not exists(select 1 from stripe_billing_first_renewal_publications completed where completed.admission_id=a.id)
   and (step.ordinal=2 or not exists(select 1 from stripe_billing_first_renewal_boundary b where b.admission_id=a.id))
   -- Paid predecessor already forbids unresolved financial operations; reject a retained trial selection too.
   and new.selected_operation is null and new.selected_version is null
   and not exists(select 1 from subscription_trial_eligibility t where t.site_id=s.site_id and t.customer_id=s.customer_id
    and t.product_id=s.product_id and t.consumed_at is null and t.reserved_by_operation is not null)
 ) then raise exception 'First renewal read start lacks exact current READY and fence' using errcode='23514'; end if;
 return new;
end $$;
create trigger first_renewal_read_claim_proof before insert on stripe_billing_first_renewal_read_claims
 for each row execute function require_first_renewal_read_claim();

-- OLD identity is inspected first so changing kind/account/resource cannot escape owned guards.
create function require_first_renewal_operation() returns trigger language plpgsql as $$
declare step stripe_billing_first_renewal_steps; admission stripe_billing_first_renewals; owner stripe_billing_clock_intents;
 old_owned boolean := false;
begin
 if tg_op in ('UPDATE','DELETE') and old.kind='TEST_CLOCK_ADVANCE' then
  old_owned := true;
  if tg_op='DELETE' then raise exception 'Owned advance cannot be deleted' using errcode='23514'; end if;
  if row(new.id,new.payment_account_id,new.kind,new.resource_id,new.operation_key,new.fingerprint,new.external_account_id,new.credentials_ref)
   is distinct from row(old.id,old.payment_account_id,old.kind,old.resource_id,old.operation_key,old.fingerprint,old.external_account_id,old.credentials_ref) then
   raise exception 'Owned advance identity is immutable' using errcode='23514'; end if;
  if old.first_dispatched_at is not null and row(new.first_dispatched_at,new.retry_before) is distinct from row(old.first_dispatched_at,old.retry_before) then
   raise exception 'Owned advance dispatch history is immutable' using errcode='23514'; end if;
  if old.status in ('ACKNOWLEDGED','UNKNOWN','FAILED') and new is distinct from old then
   raise exception 'Owned advance consumed state is immutable' using errcode='23514'; end if;
 end if;
 if tg_op='DELETE' then return old; end if;
 if new.kind<>'TEST_CLOCK_ADVANCE' then return new; end if;
 if tg_op='UPDATE' and not old_owned then raise exception 'Owned advance requires original reservation' using errcode='23514'; end if;
 select * into step from stripe_billing_first_renewal_steps where id=new.resource_id;
 if not found then raise exception 'Owned advance lacks persisted step' using errcode='23514'; end if;
 select * into admission from stripe_billing_first_renewals where id=step.admission_id;
 select c.* into owner from stripe_billing_clock_intents c join stripe_billing_owned_subscriptions i on i.clock_intent=c.id where i.id=admission.owned_intent;
 if new.payment_account_id<>admission.payment_account_id or new.operation_key<>step.operation_key or new.fingerprint<>step.fingerprint
  or new.external_account_id<>owner.external_account_id or new.credentials_ref<>owner.credentials_ref then
  raise exception 'Owned advance identity mismatch' using errcode='23514'; end if;
 if tg_op='INSERT' and (new.status<>'RESERVED' or new.version<>0 or new.first_dispatched_at is not null
  or new.lease_owner is not null or new.provider_reference is not null or admission.stopped_at is not null or owner.stopped_at is not null) then
  raise exception 'Owned advance requires pristine reservation' using errcode='23514'; end if;
 if tg_op='UPDATE' and new is distinct from old then
  if new.version<>old.version+1 then raise exception 'Owned advance version must follow original claim' using errcode='23514'; end if;
  if new.status='DISPATCHING' then
   if old.status<>'RESERVED' or old.first_dispatched_at is not null or admission.stopped_at is not null or owner.stopped_at is not null
    or new.lease_expires_at<>new.first_dispatched_at+interval '60 seconds' or new.first_dispatched_at>clock_timestamp()
    or new.lease_expires_at<=clock_timestamp() then
    raise exception 'Owned advance dispatch is once only' using errcode='23514'; end if;
  elsif new.status='ACKNOWLEDGED' then
   if old.status<>'DISPATCHING' or old.lease_owner is null or old.lease_expires_at<=clock_timestamp()
    or admission.stopped_at is not null or owner.stopped_at is not null or not exists(
     select 1 from stripe_billing_clock_ready r where r.intent_id=owner.id and r.reference=new.provider_reference) then
    raise exception 'Owned advance ACK requires original live claim' using errcode='23514'; end if;
  elsif new.status<>'UNKNOWN' or old.status<>'DISPATCHING' then
   raise exception 'Owned advance cannot reset or recover' using errcode='23514';
  end if;
 end if;
 return new;
end $$;
create trigger first_renewal_operation_proof before insert or update or delete on provider_operations
 for each row execute function require_first_renewal_operation();

-- Atomic update+receipt, including the original live owner/deadline, not a newly supplied lease.
create function require_first_renewal_ack_receipt() returns trigger language plpgsql as $$
begin
 if new.kind='TEST_CLOCK_ADVANCE' and new.status='ACKNOWLEDGED' then
  if not exists(select 1 from stripe_billing_first_renewal_acks a
   where a.provider_operation=new.id and a.step_id=new.resource_id and a.payment_account_id=new.payment_account_id
    and a.reference=new.provider_reference) then
   raise exception 'Owned advance ACK requires atomic receipt' using errcode='23514'; end if;
  if tg_op='UPDATE' and old.status='DISPATCHING' and not exists(select 1 from stripe_billing_first_renewal_acks a
   where a.provider_operation=new.id and a.claimant=old.lease_owner and a.deadline=old.lease_expires_at) then
   raise exception 'Owned advance ACK must preserve original claim' using errcode='23514'; end if;
 end if;
 return null;
end $$;
create constraint trigger first_renewal_ack_receipt after insert or update on provider_operations
 deferrable initially deferred for each row execute function require_first_renewal_ack_receipt();

-- Existing Java canonical encoding, solely for rechecking the existing immutable fingerprints.
-- No graph attestation or new authority is introduced by these representation helpers.
create function first_renewal_canonical(variadic fields text[]) returns text language sql immutable as $$
 select string_agg(case when value is null then '-1:' else octet_length(value)::text||':'||value end,'' order by position)
 from unnest(fields) with ordinality as f(value,position)
$$;
create function first_renewal_instant(value timestamptz) returns text language sql immutable strict as $$
 select to_char(value at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS')||
 case when to_char(value at time zone 'UTC','US')='000000' then ''
 when right(to_char(value at time zone 'UTC','US'),3)='000' then '.'||to_char(value at time zone 'UTC','MS')
 else '.'||to_char(value at time zone 'UTC','US') end||'Z'
$$;
create function first_renewal_hash(variadic fields text[]) returns text language sql immutable as $$
 select encode(sha256(convert_to(first_renewal_canonical(variadic fields),'UTF8')),'hex')
$$;

create function owned_first_renewal_identity(admission uuid) returns boolean
 language sql stable set join_collapse_limit=1 as $$
 select exists(select 1 from stripe_billing_first_renewals a
 join stripe_billing_owned_subscriptions i on i.id=a.owned_intent
 join stripe_billing_clock_intents c on c.id=i.clock_intent
 join stripe_billing_clock_ready k on k.intent_id=c.id
 join stripe_billing_owned_subscription_receipts r on r.intent_id=i.id
 join subscription_action_capabilities g on g.id=a.original_root
 cross join lateral (select first_renewal_canonical('gp:stripe-billing-owned-initial-proof:v1',i.id::text,i.fingerprint,first_renewal_instant(i.admitted_at),
  c.id::text,c.run_id::text,c.payment_account_id::text,c.site_id::text,c.customer_id::text,c.external_account_id,c.credentials_ref,c.api_version,c.initial_frozen_time::text,
  c.create_key,c.create_fingerprint,first_renewal_instant(c.admitted_at),k.creation_operation_id::text,k.reference,k.frozen_time::text,k.provider_created::text,
  k.provider_deletes_after::text,first_renewal_instant(k.observed_at),k.customer_fingerprint,i.customer_binding::text,i.customer_creation_operation::text,i.customer_reference,
  i.subscription_id::text,i.create_operation::text,i.operation_key,i.request_fingerprint,i.expected_revision::text,i.policy_version::text,i.price_reference,i.product_reference,
  r.provider_operation::text,r.reference,r.provider_created::text,r.item_reference,r.period_start::text,r.period_end::text,first_renewal_instant(r.acknowledged_at),
  a.invoice_reference,a.allocation_reference,a.payment_intent_reference) as descriptor) proof
 where a.id=admission and a.stopped_at is null and c.stopped_at is null
  and row(a.payment_account_id,a.site_id,a.customer_id,a.subscription_id,a.policy_version)=row(i.payment_account_id,i.site_id,i.customer_id,i.subscription_id,i.policy_version)
  and g.billing_proof=encode(sha256(convert_to(proof.descriptor,'UTF8')),'hex')
  and a.fingerprint=first_renewal_hash('gp:owned-first-renewal-admission:v1',a.id::text,proof.descriptor,a.source_sha,a.original_root::text,a.original_attempt::text,
   a.attempt_version::text,a.initial_bill::text,a.bill_version::text,a.initial_payment::text,a.payment_version::text,a.subscription_revision::text,a.policy_version::text,first_renewal_instant(a.admitted_at)))
$$;

-- Each row proves a complete immutable step, even when it is no longer highest.
create function owned_first_renewal_step_evidence(admission uuid) returns table(step_id uuid,ready_id uuid,ordinal integer,target bigint)
 language sql stable set join_collapse_limit=1 as $$
 select s.id,r.id,s.ordinal,s.target from stripe_billing_first_renewals a
 join stripe_billing_owned_subscriptions i on i.id=a.owned_intent
 join stripe_billing_clock_intents c on c.id=i.clock_intent
 join stripe_billing_clock_ready original on original.intent_id=c.id
 join stripe_billing_owned_subscription_receipts initial on initial.intent_id=i.id
 join stripe_billing_first_renewal_steps s on s.admission_id=a.id
 join stripe_billing_first_renewal_acks ack on ack.step_id=s.id and ack.admission_id=a.id
 join provider_operations p on p.id=ack.provider_operation
 join stripe_billing_first_renewal_ready r on r.step_id=s.id and r.admission_id=a.id
 where a.id=admission and a.stopped_at is null and c.stopped_at is null
  and s.target=initial.period_end+case when s.ordinal=2 then 3600 else 0 end
  and s.target>case when s.ordinal=1 then original.frozen_time else initial.period_end end
  and s.target-case when s.ordinal=1 then original.frozen_time else initial.period_end end
   <=case when i.billing_interval='MONTH' then 56::bigint*86400 else 730::bigint*86400 end
  and s.operation_key='billing-renewal-advance-v1:'||a.id::text||':'||s.ordinal::text
  and s.fingerprint=first_renewal_hash('gp:owned-first-renewal-advance:v1',s.id::text,a.id::text,a.fingerprint,s.ordinal::text,s.target::text,
   original.creation_operation_id::text,original.reference,original.frozen_time::text,s.predecessor_ready::text,s.boundary_id::text,s.operation_key,first_renewal_instant(s.prepared_at))
  and p.payment_account_id=a.payment_account_id and p.resource_id=s.id and p.kind='TEST_CLOCK_ADVANCE' and p.status='ACKNOWLEDGED'
  and p.operation_key=s.operation_key and p.fingerprint=s.fingerprint and p.provider_reference=original.reference
  and p.external_account_id=c.external_account_id and p.credentials_ref=c.credentials_ref and p.first_dispatched_at is not null
  and ack.payment_account_id=a.payment_account_id and ack.operation_kind='TEST_CLOCK_ADVANCE' and ack.reference=original.reference
  and ack.object_type='test_helpers.test_clock' and not ack.livemode and ack.name=c.name
  and ack.provider_created=original.provider_created and ack.provider_deletes_after=original.provider_deletes_after
  and ack.status in ('ready','advancing') and ack.frozen_time>=case when s.ordinal=1 then original.frozen_time else initial.period_end end and ack.frozen_time<=s.target
  and (ack.advancing_target is null or ack.advancing_target=s.target)
  and length(trim(ack.claimant))>0 and ack.acknowledged_at>=p.first_dispatched_at and ack.acknowledged_at<ack.deadline
  and ack.deadline=p.first_dispatched_at+interval '60 seconds'
  and r.reference=ack.reference and r.name=ack.name and r.object_type='test_helpers.test_clock' and not r.livemode
  and r.status='ready' and r.frozen_time=s.target and r.provider_created=ack.provider_created and r.provider_deletes_after=ack.provider_deletes_after
  and (r.advancing_target is null or r.advancing_target=s.target) and r.observed_at>=ack.acknowledged_at and r.observed_at<ack.deadline
$$;

create function owned_first_renewal_chain(admission uuid) returns table(step_id uuid,ready_id uuid,ordinal integer,target bigint,boundary_id uuid)
 language sql stable set join_collapse_limit=1 as $$
 select selected.step_id,selected.ready_id,selected.ordinal,selected.target,b.id
 from stripe_billing_first_renewals a
 join subscriptions local on local.id=a.subscription_id
 join stripe_billing_owned_subscription_receipts initial on initial.intent_id=a.owned_intent
 join lateral (select * from stripe_billing_first_renewal_steps where admission_id=a.id order by ordinal desc limit 1) highest on true
 join owned_first_renewal_step_evidence(admission) selected on selected.step_id=highest.id
 left join stripe_billing_first_renewal_boundary b on b.id=highest.boundary_id and b.admission_id=a.id and b.ready_id=highest.predecessor_ready
 left join owned_first_renewal_step_evidence(admission) first on first.ready_id=b.ready_id and first.ordinal=1
 left join stripe_billing_first_renewal_read_claims claim on claim.ready_id=first.ready_id and claim.step_id=first.step_id and claim.admission_id=a.id
 where a.id=admission and owned_first_renewal_identity(a.id)
  and not exists(select 1 from stripe_billing_first_renewal_read_claims current_claim where current_claim.ready_id=selected.ready_id
   and (current_claim.captured_revision<>a.subscription_revision or current_claim.selected_operation is not null or current_claim.selected_version is not null
    or current_claim.observation_sequence<>local.observation_sequence-case when local.revision=a.subscription_revision+1 then 1 else 0 end))
  and (selected.ordinal=1 or (selected.ordinal=2 and first.target=initial.period_end
   and b.item_reference=initial.item_reference and b.invoice_reference<>a.invoice_reference
   and b.period_start=to_timestamp(initial.period_end) and b.period_end>to_timestamp(selected.target)
   and claim.captured_revision=a.subscription_revision and claim.selected_operation is null and claim.selected_version is null
   and claim.observation_sequence=local.observation_sequence-case when local.revision=a.subscription_revision+1 then 1 else 0 end
   and b.observed_at>=claim.started_at and b.observed_at<claim.deadline_at
   and b.fingerprint=first_renewal_hash('gp:owned-first-renewal-boundary:v1',b.id::text,a.id::text,b.ready_id::text,b.item_reference,
    b.invoice_reference,first_renewal_instant(b.period_start),first_renewal_instant(b.period_end),first_renewal_instant(b.observed_at))))
$$;

-- Original-state identity comparison shared by the hosted selector; original paid proof is unchanged.
create function owned_first_renewal_original(admission uuid) returns boolean
 language sql stable set join_collapse_limit=1 as $$
 select exists(select 1 from stripe_billing_first_renewals a join owned_first_renewal_paid(a.owned_intent) p on true
 where a.id=admission and a.stopped_at is null and owned_first_renewal_identity(a.id)
  and row(p.root_id,p.attempt_id,p.attempt_version,p.bill_id,p.bill_version,p.payment_id,p.payment_version,p.invoice_reference,p.allocation_reference,
   p.payment_intent_reference,p.subscription_revision,p.policy_version)=row(a.original_root,a.original_attempt,a.attempt_version,a.initial_bill,a.bill_version,
   a.initial_payment,a.payment_version,a.invoice_reference,a.allocation_reference,a.payment_intent_reference,a.subscription_revision,a.policy_version)
  and not exists(select 1 from subscription_trial_eligibility t join stripe_billing_owned_subscriptions i on i.id=a.owned_intent
   where t.site_id=a.site_id and t.customer_id=a.customer_id and t.product_id=i.product_id and t.consumed_at is null and t.reserved_by_operation is not null)
  and not exists(select 1 from stripe_billing_first_renewal_publications p where p.admission_id=a.id))
$$;

-- Exact post-FactService state, shared by INSERT proof and every committed receipt read.
create function owned_first_renewal_projected(target_admission uuid) returns table(renewal_bill uuid,renewal_payment uuid,projected_revision bigint)
 language sql stable set join_collapse_limit=1 as $$
 select nb.id,np.id,s.revision
 from stripe_billing_first_renewals admission join stripe_billing_owned_subscriptions i on i.id=admission.owned_intent
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
join owned_first_renewal_chain(admission.id) chain_proof on chain_proof.ordinal=2
join stripe_billing_first_renewal_boundary boundary on boundary.id=chain_proof.boundary_id and boundary.admission_id=admission.id
join stripe_billing_first_renewal_read_claims claim on claim.ready_id=chain_proof.ready_id and claim.step_id=chain_proof.step_id and claim.admission_id=admission.id
join subscription_billing_records nb on nb.subscription_id=s.id and nb.site_id=s.site_id and nb.payment_account_id=s.payment_account_id
join payments np on np.billing_record_id=nb.id
where admission.id=target_admission and c.stopped_at is null and c.api_version='2026-09-30.endive'
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
  and s.current_period_start=boundary.period_start and s.current_period_end=boundary.period_end and s.paid_through=boundary.period_end
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
  and (select count(*) from subscription_billing_records other where other.subscription_id=s.id)=2
  and p.source='SUBSCRIPTION' and p.status='SUCCEEDED' and p.refunded_amount_minor=0
  and p.payment_account_id=i.payment_account_id and p.site_id=i.site_id and p.customer_id=i.customer_id
  and p.provider_payment_reference=g.billing_intent_reference and p.subscription_id=s.id and p.subscription_billing_kind='INITIAL'
  and p.subscription_product_id=i.product_id and p.subscription_price_id=i.price_id and p.subscription_price_version=i.price_version
  and p.subscription_previous_price_id is null and p.subscription_previous_price_version is null
  and p.subscription_period_start=b.period_start and p.subscription_period_end=b.period_end and p.subscription_invoice_reference=b.provider_invoice_reference
  and p.amount_minor=i.amount_minor and p.currency=i.currency and p.tax_mode=i.tax_mode and p.tax_amount_minor is null
  and (select count(*) from payments other where other.billing_record_id=b.id)=1
 and admission.stopped_at is null and owned_first_renewal_identity(admission.id)
 and row(g.id,t.id,t.version,b.id,b.version,p.id,p.version,g.billing_invoice_reference,g.billing_allocation_reference,g.billing_intent_reference,i.policy_version)
  =row(admission.original_root,admission.original_attempt,admission.attempt_version,admission.initial_bill,admission.bill_version,admission.initial_payment,
   admission.payment_version,admission.invoice_reference,admission.allocation_reference,admission.payment_intent_reference,admission.policy_version)
 and s.revision=admission.subscription_revision+1 and claim.captured_revision=admission.subscription_revision
 and s.observation_sequence=claim.observation_sequence+1 and claim.selected_operation is null and claim.selected_version is null
 and not exists(select 1 from subscription_trial_eligibility trial where trial.site_id=s.site_id and trial.customer_id=s.customer_id
  and trial.product_id=s.product_id and trial.consumed_at is null and trial.reserved_by_operation is not null)
 and nb.id<>b.id and nb.kind='RENEWAL' and nb.settlement='CONFIRMED_CASH' and nb.provider_invoice_reference=boundary.invoice_reference
 and nb.period_start=boundary.period_start and nb.period_end=boundary.period_end and nb.previous_price_id is null
 and row(nb.product_id,nb.billed_price_id,nb.billed_price_key,nb.billed_price_version,nb.billed_amount_minor,nb.billed_interval,
  nb.billed_trial_days,nb.billed_trial_policy,nb.billed_tax_mode,nb.currency,nb.amount_due_minor)
  =row(b.product_id,b.billed_price_id,b.billed_price_key,b.billed_price_version,b.billed_amount_minor,b.billed_interval,
  b.billed_trial_days,b.billed_trial_policy,b.billed_tax_mode,b.currency,b.amount_due_minor)
 and np.id<>p.id and np.provider_payment_reference<>p.provider_payment_reference and np.provider_payment_reference ~ '^pi_[A-Za-z0-9]{1,252}$'
 and np.source='SUBSCRIPTION' and np.status='SUCCEEDED' and np.refunded_amount_minor=0 and np.subscription_billing_kind='RENEWAL'
 and row(np.payment_account_id,np.site_id,np.customer_id,np.subscription_id,np.subscription_product_id,np.subscription_price_id,np.subscription_price_version,
  np.amount_minor,np.currency,np.tax_mode)=row(p.payment_account_id,p.site_id,p.customer_id,p.subscription_id,p.subscription_product_id,p.subscription_price_id,p.subscription_price_version,
  p.amount_minor,p.currency,p.tax_mode)
 and np.subscription_previous_price_id is null and np.subscription_previous_price_version is null and np.tax_amount_minor is null
 and np.subscription_period_start=nb.period_start and np.subscription_period_end=nb.period_end and np.subscription_invoice_reference=nb.provider_invoice_reference
 and (select count(*) from payments other where other.subscription_id=s.id)=2
 and (select count(*) from payments other where other.billing_record_id=nb.id)=1
 and exists(select 1 from subscription_events event where event.subscription_id=s.id and event.billing_record_id=nb.id
  and event.event_type='PERIOD_PAID' and event.revision=s.revision)
 and exists(select 1 from payment_events event where event.payment_id=np.id and event.event_type='PAYMENT_SUCCEEDED');
$$;

create function owned_first_renewal_committed(admission uuid) returns boolean
 language sql stable set join_collapse_limit=1 as $$
 select exists(select 1 from stripe_billing_first_renewal_publications p
 join owned_first_renewal_projected(admission) state on state.renewal_bill=p.renewal_bill and state.renewal_payment=p.renewal_payment and state.projected_revision=p.projected_revision
 join owned_first_renewal_chain(admission) chain on chain.ready_id=p.ready_id and chain.boundary_id=p.boundary_id and chain.ordinal=2
 join stripe_billing_first_renewal_read_claims claim on claim.ready_id=p.ready_id and claim.admission_id=p.admission_id
 where p.admission_id=admission and p.graph_accepted_at>=claim.started_at and p.graph_accepted_at<claim.deadline_at
  and p.graph_accepted_at<=p.published_at)
$$;

create function require_first_renewal_publication() returns trigger language plpgsql as $$
begin
 if new.published_at>clock_timestamp() or new.graph_accepted_at>new.published_at or not exists(
  select 1 from owned_first_renewal_projected(new.admission_id) state
  join owned_first_renewal_chain(new.admission_id) chain on chain.ordinal=2
  join stripe_billing_first_renewal_read_claims claim on claim.ready_id=chain.ready_id and claim.step_id=chain.step_id and claim.admission_id=new.admission_id
  where row(new.renewal_bill,new.renewal_payment,new.projected_revision)=row(state.renewal_bill,state.renewal_payment,state.projected_revision)
   and new.ready_id=chain.ready_id and new.boundary_id=chain.boundary_id
   and new.graph_accepted_at>=claim.started_at and new.graph_accepted_at<claim.deadline_at
 ) then raise exception 'First renewal publication lacks exact committed CASH and original read claim' using errcode='23514'; end if;
 return new;
end $$;
create trigger first_renewal_publication_proof before insert on stripe_billing_first_renewal_publications
 for each row execute function require_first_renewal_publication();
