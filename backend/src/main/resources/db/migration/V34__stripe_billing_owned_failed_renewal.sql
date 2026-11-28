-- Internal, explicit-constructor failed-renewal lane. No runtime activation.
-- All six receipts are append-only; a stop or expired read never releases ownership.
create table stripe_billing_failed_renewals (
 id uuid primary key, first_renewal_admission uuid not null unique,
 payment_account_id uuid not null, site_id uuid not null, customer_id uuid not null, subscription_id uuid not null unique,
 ready_id uuid not null unique, boundary_id uuid not null unique,
 source_sha varchar(40) not null check(source_sha ~ '^[a-f0-9]{40}$'), admitted_at timestamptz not null,
 unique(id,payment_account_id,site_id,customer_id,subscription_id),
 foreign key(first_renewal_admission,payment_account_id,site_id,customer_id,subscription_id)
  references stripe_billing_first_renewals(id,payment_account_id,site_id,customer_id,subscription_id),
 foreign key(ready_id,first_renewal_admission) references stripe_billing_first_renewal_ready(id,admission_id),
 foreign key(boundary_id,first_renewal_admission) references stripe_billing_first_renewal_boundary(id,admission_id)
);
create table stripe_billing_failed_renewal_reads (
 id uuid primary key, admission_id uuid not null references stripe_billing_failed_renewals(id),
 phase varchar(16) not null check(phase in ('FAILED','RECOVERED')), invocation uuid not null unique,
 started_at timestamptz not null, deadline_at timestamptz not null check(deadline_at=started_at+interval '30 seconds'),
 captured_revision bigint not null check(captured_revision>=0), observation_sequence bigint not null check(observation_sequence>=0),
 selected_operation uuid references subscription_operations(id), selected_version bigint,
 unique(admission_id,phase), unique(id,admission_id),
 check((phase='FAILED' and selected_operation is null and selected_version is null)
    or (phase='RECOVERED' and selected_operation is not null and selected_version is not null and selected_version>=0))
);
create table stripe_billing_failed_renewal_failures (
 admission_id uuid primary key, claim_id uuid not null unique, renewal_bill uuid not null unique,
 payment_account_id uuid not null, site_id uuid not null, customer_id uuid not null, subscription_id uuid not null,
 bill_version bigint not null check(bill_version>=0), invoice_reference varchar(255) not null check(invoice_reference ~ '^in_[A-Za-z0-9]{1,252}$'),
 allocation_reference varchar(255) not null check(allocation_reference ~ '^inpay_[A-Za-z0-9]{1,249}$'),
 payment_intent_reference varchar(255) not null check(payment_intent_reference ~ '^pi_[A-Za-z0-9]{1,252}$'),
 period_start timestamptz not null, period_end timestamptz not null check(period_start<period_end), grace_deadline timestamptz not null,
 projected_revision bigint not null check(projected_revision>=0), observation_sequence bigint not null check(observation_sequence>=0), recorded_at timestamptz not null,
 unique(admission_id,renewal_bill),
 foreign key(admission_id,payment_account_id,site_id,customer_id,subscription_id)
  references stripe_billing_failed_renewals(id,payment_account_id,site_id,customer_id,subscription_id),
 foreign key(claim_id,admission_id) references stripe_billing_failed_renewal_reads(id,admission_id),
 foreign key(site_id,subscription_id,payment_account_id,renewal_bill) references subscription_billing_records(site_id,subscription_id,payment_account_id,id)
);
create table stripe_billing_failed_renewal_recoveries (
 admission_id uuid primary key, operation_id uuid not null unique references stripe_subscription_recovery_proofs(operation_id),
 renewal_bill uuid not null, payment_account_id uuid not null, site_id uuid not null, customer_id uuid not null, subscription_id uuid not null,
 allocation_reference varchar(255) not null, payment_intent_reference varchar(255) not null,
 expected_revision bigint not null check(expected_revision>=0), operation_version bigint not null check(operation_version>=0), associated_at timestamptz not null,
 unique(admission_id,operation_id,renewal_bill),
 foreign key(admission_id,renewal_bill) references stripe_billing_failed_renewal_failures(admission_id,renewal_bill),
 foreign key(admission_id,payment_account_id,site_id,customer_id,subscription_id)
  references stripe_billing_failed_renewals(id,payment_account_id,site_id,customer_id,subscription_id),
 foreign key(site_id,subscription_id,operation_id) references subscription_operations(site_id,subscription_id,id)
);
create table stripe_billing_failed_renewal_publications (
 admission_id uuid primary key, claim_id uuid not null unique, operation_id uuid not null unique, renewal_bill uuid not null unique,
 renewal_payment uuid not null unique, payment_account_id uuid not null, site_id uuid not null, customer_id uuid not null, subscription_id uuid not null,
 projected_revision bigint not null check(projected_revision>=0), published_at timestamptz not null,
 foreign key(admission_id,operation_id,renewal_bill) references stripe_billing_failed_renewal_recoveries(admission_id,operation_id,renewal_bill),
 foreign key(claim_id,admission_id) references stripe_billing_failed_renewal_reads(id,admission_id),
 foreign key(admission_id,payment_account_id,site_id,customer_id,subscription_id)
  references stripe_billing_failed_renewals(id,payment_account_id,site_id,customer_id,subscription_id),
 foreign key(renewal_payment,site_id,payment_account_id) references payments(id,site_id,payment_account_id)
);
create table stripe_billing_failed_renewal_stops (
 admission_id uuid primary key references stripe_billing_failed_renewals(id),
 reason varchar(32) not null check(reason in ('UNCERTAIN','AUTHORITY_DRIFT')), stopped_at timestamptz not null
);

create function protect_failed_renewal_evidence() returns trigger language plpgsql as $$
begin raise exception 'Owned failed renewal evidence is immutable' using errcode='23514'; end $$;
create trigger failed_renewal_immutable before update or delete on stripe_billing_failed_renewals for each row execute function protect_failed_renewal_evidence();
create trigger failed_read_immutable before update or delete on stripe_billing_failed_renewal_reads for each row execute function protect_failed_renewal_evidence();
create trigger failed_failure_immutable before update or delete on stripe_billing_failed_renewal_failures for each row execute function protect_failed_renewal_evidence();
create trigger failed_recovery_immutable before update or delete on stripe_billing_failed_renewal_recoveries for each row execute function protect_failed_renewal_evidence();
create trigger failed_publication_immutable before update or delete on stripe_billing_failed_renewal_publications for each row execute function protect_failed_renewal_evidence();
create trigger failed_stop_immutable before update or delete on stripe_billing_failed_renewal_stops for each row execute function protect_failed_renewal_evidence();

-- A shared pre-existing admission row serializes both branch decisions. The next query
-- must see the winner committed after lock acquisition: reject unsupported isolation levels.
create function lock_owned_renewal_branch(admission uuid) returns void language plpgsql volatile as $$
begin
 if current_setting('transaction_isolation')<>'read committed' then
  raise exception 'Owned branch admission requires READ COMMITTED' using errcode='23514'; end if;
 perform id from stripe_billing_first_renewals where id=admission for update;
 if not found then raise exception 'Missing renewal admission' using errcode='23514'; end if;
end $$;
create function exclude_failed_renewal_from_success() returns trigger language plpgsql volatile as $$
begin
 if tg_table_name='stripe_billing_first_renewal_read_claims' then
  if not exists(select 1 from stripe_billing_first_renewal_steps where id=new.step_id and ordinal=2) then return new; end if;
 end if;
 perform lock_owned_renewal_branch(new.admission_id);
 if exists(select 1 from stripe_billing_failed_renewals where first_renewal_admission=new.admission_id) then
  raise exception 'Failed renewal permanently owns this branch' using errcode='23514'; end if;
 return new;
end $$;
create trigger aaa_failed_renewal_exclusion before insert on stripe_billing_first_renewal_read_claims for each row execute function exclude_failed_renewal_from_success();
create trigger aaa_failed_renewal_exclusion before insert on stripe_billing_first_renewal_publications for each row execute function exclude_failed_renewal_from_success();

create function require_failed_renewal_admission() returns trigger language plpgsql volatile as $$
begin
 perform lock_owned_renewal_branch(new.first_renewal_admission);
 if new.admitted_at>clock_timestamp() or not owned_first_renewal_original(new.first_renewal_admission)
  or exists(select 1 from stripe_billing_first_renewal_publications where admission_id=new.first_renewal_admission)
  or exists(select 1 from stripe_billing_first_renewal_read_claims q join stripe_billing_first_renewal_steps t on t.id=q.step_id
     where q.admission_id=new.first_renewal_admission and t.ordinal=2)
  or not exists(select 1 from stripe_billing_first_renewals a
    join owned_first_renewal_chain(a.id) chain on chain.ordinal=2
    join stripe_billing_first_renewal_ready r on r.id=chain.ready_id
    join stripe_billing_owned_subscriptions i on i.id=a.owned_intent
    where a.id=new.first_renewal_admission and new.ready_id=chain.ready_id and new.boundary_id=chain.boundary_id
     and new.source_sha=a.source_sha and new.admitted_at>=r.observed_at
     and i.amount_minor>0 and i.trial_days=0 and i.tax_mode='NONE')
 then raise exception 'Failed renewal requires genuine unclaimed paid/READY2 lineage' using errcode='23514'; end if;
 return new;
end $$;
create trigger failed_renewal_admission_proof before insert on stripe_billing_failed_renewals for each row execute function require_failed_renewal_admission();

-- Independent immutable evidence plus active owner/catalog/credentials and unchanged INITIAL cash.
-- Deliberately does not reuse the mutable V33 chain or original-paid state after debt.
create function owned_failed_renewal_authority(target_admission uuid) returns boolean
 language sql stable set join_collapse_limit=1 as $$
 select exists(select 1
 from stripe_billing_failed_renewals d
 join stripe_billing_first_renewals admission on admission.id=d.first_renewal_admission
 join stripe_billing_owned_subscriptions i on i.id=admission.owned_intent
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
 where d.id=target_admission and c.stopped_at is null and c.api_version='2026-09-30.endive'
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
  and s.provider_reference=r.reference
  and s.trial_start is null and s.trial_end is null
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
  and p.source='SUBSCRIPTION' and p.status='SUCCEEDED' and p.refunded_amount_minor=0
  and p.payment_account_id=i.payment_account_id and p.site_id=i.site_id and p.customer_id=i.customer_id
  and p.provider_payment_reference=g.billing_intent_reference and p.subscription_id=s.id and p.subscription_billing_kind='INITIAL'
  and p.subscription_product_id=i.product_id and p.subscription_price_id=i.price_id and p.subscription_price_version=i.price_version
  and p.subscription_previous_price_id is null and p.subscription_previous_price_version is null
  and p.subscription_period_start=b.period_start and p.subscription_period_end=b.period_end and p.subscription_invoice_reference=b.provider_invoice_reference
  and p.amount_minor=i.amount_minor and p.currency=i.currency and p.tax_mode=i.tax_mode and p.tax_amount_minor is null
  and (select count(*) from payments other where other.billing_record_id=b.id)=1
 and owned_first_renewal_identity(admission.id)
 and row(g.id,t.id,t.version,b.id,b.version,p.id,p.version)=row(admission.original_root,admission.original_attempt,admission.attempt_version,admission.initial_bill,admission.bill_version,admission.initial_payment,admission.payment_version)
 and row(g.billing_invoice_reference,g.billing_allocation_reference,g.billing_intent_reference)=row(admission.invoice_reference,admission.allocation_reference,admission.payment_intent_reference)
 and i.amount_minor>0 and i.trial_days=0 and i.tax_mode='NONE'
 and not exists(select 1 from stripe_billing_failed_renewal_stops stop where stop.admission_id=d.id)
 and not exists(select 1 from stripe_billing_first_renewal_publications pub where pub.admission_id=admission.id)
 and not exists(select 1 from stripe_billing_first_renewal_read_claims q join stripe_billing_first_renewal_steps t on t.id=q.step_id where q.admission_id=admission.id and t.ordinal=2)
 and not exists(select 1 from subscription_trial_eligibility trial where trial.site_id=s.site_id and trial.customer_id=s.customer_id and trial.product_id=s.product_id and trial.consumed_at is null and trial.reserved_by_operation is not null)
 and exists(select 1 from owned_first_renewal_step_evidence(admission.id) first_step
   join owned_first_renewal_step_evidence(admission.id) final_step on final_step.ordinal=2
   join stripe_billing_first_renewal_steps step2 on step2.id=final_step.step_id
   join stripe_billing_first_renewal_boundary boundary on boundary.id=d.boundary_id and boundary.admission_id=admission.id
   join stripe_billing_first_renewal_read_claims q on q.ready_id=boundary.ready_id and q.admission_id=admission.id
   where first_step.ordinal=1 and first_step.ready_id=boundary.ready_id and final_step.ready_id=d.ready_id
    and step2.boundary_id=boundary.id and step2.predecessor_ready=boundary.ready_id
    and boundary.item_reference=r.item_reference and boundary.invoice_reference<>admission.invoice_reference
    and boundary.period_start=to_timestamp(r.period_end) and boundary.period_end>to_timestamp(final_step.target)
    and q.captured_revision=admission.subscription_revision and q.selected_operation is null and q.selected_version is null
    and boundary.observed_at>=q.started_at and boundary.observed_at<q.deadline_at
    and boundary.fingerprint=first_renewal_hash('gp:owned-first-renewal-boundary:v1',boundary.id::text,admission.id::text,boundary.ready_id::text,boundary.item_reference,boundary.invoice_reference,first_renewal_instant(boundary.period_start),first_renewal_instant(boundary.period_end),first_renewal_instant(boundary.observed_at))));
$$;

-- Works both just after failed facts (before receipt INSERT) and with the persisted debt.
create function owned_failed_renewal_debt(target_admission uuid, allowed_operation uuid)
 returns table(renewal_bill uuid,bill_version bigint,grace_deadline timestamptz,projected_revision bigint,observation_sequence bigint)
 language sql stable set join_collapse_limit=1 as $$
 select b.id,b.version,s.grace_deadline,s.revision,s.observation_sequence
 from stripe_billing_failed_renewals d join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
 join stripe_billing_first_renewal_boundary boundary on boundary.id=d.boundary_id
 join subscriptions s on s.id=d.subscription_id
 join subscription_billing_records initial on initial.id=a.initial_bill
 join subscription_billing_records b on b.id=s.debt_billing_record_id
 join stripe_billing_failed_renewal_reads q on q.admission_id=d.id and q.phase='FAILED'
 where d.id=target_admission and owned_failed_renewal_authority(d.id)
 and s.billing_status='PAST_DUE' and s.revision=q.captured_revision+1 and q.captured_revision=a.subscription_revision
 and s.observation_sequence=q.observation_sequence+1
 and row(s.current_period_start,s.current_period_end,s.debt_period_start,s.debt_period_end,s.paid_through)
  =row(boundary.period_start,boundary.period_end,boundary.period_start,boundary.period_end,boundary.period_start)
 and row(b.site_id,b.subscription_id,b.payment_account_id)=row(d.site_id,d.subscription_id,d.payment_account_id)
 and b.id<>initial.id and b.kind='RENEWAL' and b.settlement='OPEN' and b.provider_invoice_reference=boundary.invoice_reference
 and b.period_start=boundary.period_start and b.period_end=boundary.period_end and b.previous_price_id is null
 and row(b.product_id,b.billed_price_id,b.billed_price_key,b.billed_price_version,b.billed_amount_minor,b.billed_interval,b.billed_trial_days,b.billed_trial_policy,b.billed_tax_mode,b.currency,b.amount_due_minor)
  =row(initial.product_id,initial.billed_price_id,initial.billed_price_key,initial.billed_price_version,initial.billed_amount_minor,initial.billed_interval,initial.billed_trial_days,initial.billed_trial_policy,initial.billed_tax_mode,initial.currency,initial.amount_due_minor)
 and b.captured_policy_version=a.policy_version and b.grace_deadline=s.grace_deadline and s.grace_deadline is not null
 and (select count(*) from subscription_billing_records other where other.subscription_id=s.id)=2
 and (select count(*) from payments other where other.subscription_id=s.id)=1
 and not exists(select 1 from payments other where other.billing_record_id=b.id)
 and not exists(select 1 from subscription_operations other where other.subscription_id=s.id and other.id is distinct from allowed_operation
  and other.kind not in ('PORTAL','RECONCILE') and other.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN'))
 and exists(select 1 from subscription_events event where event.subscription_id=s.id and event.billing_record_id=b.id
  and event.event_type='RENEWAL_FAILED' and event.revision=s.revision)
$$;

-- Exact original association, independent of operation completion and projection phase.
create function owned_failed_renewal_association(target_admission uuid, selected uuid) returns boolean
 language sql stable set join_collapse_limit=1 as $$
 select exists(select 1 from stripe_billing_failed_renewals d
 join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
 join stripe_billing_owned_subscriptions i on i.id=a.owned_intent
 join stripe_billing_owned_subscription_receipts initial on initial.intent_id=i.id
 join stripe_billing_failed_renewal_failures f on f.admission_id=d.id
 join subscription_operations o on o.id=selected
 join stripe_subscription_recovery_proofs p on p.operation_id=o.id
 where d.id=target_admission and o.kind='RECOVER'
 and row(o.site_id,o.customer_id,o.subscription_id,o.expected_revision,o.policy_version)=row(d.site_id,d.customer_id,d.subscription_id,f.projected_revision,a.policy_version)
 and o.authority_scope='owned-failed-renewal:'||d.id::text
 and o.fingerprint ~ ('^v1[.]recover[.]'||f.renewal_bill::text||'[.][a-f0-9]{64}$')
 and o.provider_operation_key='subscription:'||d.payment_account_id::text||':'||o.id::text||':RECOVER'
 and o.created_at>=f.recorded_at and o.safe_failure_code is null
 and row(p.site_id,p.customer_id,p.subscription_id,p.payment_account_id,p.billing_record_id,p.subscription_reference,p.invoice_reference,p.origin_operation,p.origin_step,p.allocation_reference,p.intent_reference)
  =row(d.site_id,d.customer_id,d.subscription_id,d.payment_account_id,f.renewal_bill,initial.reference,f.invoice_reference,i.create_operation,initial.provider_operation,f.allocation_reference,f.payment_intent_reference)
 and p.pay_step is null and p.pay_kind is null and p.operation_kind='RECOVER'
 and not exists(select 1 from subscription_action_capabilities g where g.payment_account_id=d.payment_account_id and g.subscription_id=d.subscription_id and g.billing_invoice_reference=f.invoice_reference)
 and not exists(select 1 from stripe_subscription_recovery_proofs other where other.payment_account_id=d.payment_account_id and other.subscription_id=d.subscription_id and other.invoice_reference=f.invoice_reference and other.operation_id<>o.id)
 and not exists(select 1 from provider_operations step join subscription_operations other on other.id=step.resource_id
   where step.payment_account_id=d.payment_account_id and other.subscription_id=d.subscription_id and step.kind='INVOICE_PAY'
    and (other.id=o.id or split_part(other.fingerprint,'.',3)=f.renewal_bill::text)
    and (step.first_dispatched_at is not null or step.status in ('DISPATCHING','UNKNOWN','ACKNOWLEDGED'))))
$$;

create function owned_failed_renewal_failure_current(target_admission uuid, allowed_operation uuid) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_renewal_failures f
 join owned_failed_renewal_debt(target_admission,allowed_operation) state on
  row(state.renewal_bill,state.bill_version,state.grace_deadline,state.projected_revision,state.observation_sequence)
  =row(f.renewal_bill,f.bill_version,f.grace_deadline,f.projected_revision,f.observation_sequence)
 where f.admission_id=target_admission)
$$;
create function owned_failed_renewal_recovery_current(target_admission uuid) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_renewal_recoveries r
 join subscription_operations o on o.id=r.operation_id
 join stripe_billing_failed_renewal_failures f on f.admission_id=r.admission_id
 where r.admission_id=target_admission and owned_failed_renewal_failure_current(r.admission_id,o.id)
 and owned_failed_renewal_association(r.admission_id,o.id)
 and o.status='RESERVED' and o.version=r.operation_version and o.expected_revision=r.expected_revision
 and row(r.renewal_bill,r.allocation_reference,r.payment_intent_reference,r.expected_revision)
  =row(f.renewal_bill,f.allocation_reference,f.payment_intent_reference,f.projected_revision))
$$;

create function owned_failed_renewal_projected(target_admission uuid)
 returns table(renewal_bill uuid,renewal_payment uuid,projected_revision bigint)
 language sql stable set join_collapse_limit=1 as $$
 select b.id,p.id,s.revision
 from stripe_billing_failed_renewals d
 join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
 join stripe_billing_failed_renewal_failures f on f.admission_id=d.id
 join stripe_billing_failed_renewal_recoveries r on r.admission_id=d.id
 join stripe_billing_failed_renewal_reads q on q.admission_id=d.id and q.phase='RECOVERED'
 join subscription_operations o on o.id=r.operation_id
 join subscriptions s on s.id=d.subscription_id
 join subscription_billing_records b on b.id=f.renewal_bill
 join subscription_billing_records initial on initial.id=a.initial_bill
 join payments p on p.billing_record_id=b.id
 where d.id=target_admission and owned_failed_renewal_authority(d.id) and owned_failed_renewal_association(d.id,o.id)
 and o.status='SUCCEEDED' and o.version=r.operation_version+1 and o.provider_reference=s.provider_reference
 and s.billing_status='ACTIVE' and s.debt_billing_record_id is null and s.grace_deadline is null
 and s.current_period_start=f.period_start and s.current_period_end=f.period_end and s.paid_through=f.period_end
 and s.revision=q.captured_revision+1 and q.captured_revision=f.projected_revision
 and s.observation_sequence=q.observation_sequence+1 and q.observation_sequence=f.observation_sequence
 and q.selected_operation=o.id and q.selected_version=r.operation_version
 and b.kind='RENEWAL' and b.settlement='CONFIRMED_CASH' and b.version=f.bill_version+1
 and b.provider_invoice_reference=f.invoice_reference and b.period_start=f.period_start and b.period_end=f.period_end
 and b.captured_policy_version=a.policy_version and b.grace_deadline=f.grace_deadline and b.previous_price_id is null
 and row(b.site_id,b.subscription_id,b.payment_account_id)=row(d.site_id,d.subscription_id,d.payment_account_id)
 and row(b.product_id,b.billed_price_id,b.billed_price_key,b.billed_price_version,b.billed_amount_minor,b.billed_interval,b.billed_trial_days,b.billed_trial_policy,b.billed_tax_mode,b.currency,b.amount_due_minor)
  =row(initial.product_id,initial.billed_price_id,initial.billed_price_key,initial.billed_price_version,initial.billed_amount_minor,initial.billed_interval,initial.billed_trial_days,initial.billed_trial_policy,initial.billed_tax_mode,initial.currency,initial.amount_due_minor)
 and p.provider_payment_reference=f.payment_intent_reference and p.provider_payment_reference<>a.payment_intent_reference
 and p.source='SUBSCRIPTION' and p.status='SUCCEEDED' and p.refunded_amount_minor=0 and p.subscription_billing_kind='RENEWAL'
 and row(p.payment_account_id,p.site_id,p.customer_id,p.subscription_id,p.subscription_product_id,p.subscription_price_id,p.subscription_price_version,p.amount_minor,p.currency,p.tax_mode)
  =row(d.payment_account_id,d.site_id,d.customer_id,d.subscription_id,b.product_id,b.billed_price_id,b.billed_price_version,b.amount_due_minor,b.currency,b.billed_tax_mode)
 and p.subscription_previous_price_id is null and p.subscription_previous_price_version is null and p.tax_amount_minor is null
 and p.subscription_period_start=b.period_start and p.subscription_period_end=b.period_end and p.subscription_invoice_reference=b.provider_invoice_reference
 and (select count(*) from subscription_billing_records other where other.subscription_id=s.id)=2
 and (select count(*) from payments other where other.subscription_id=s.id)=2
 and (select count(*) from payments other where other.billing_record_id=b.id)=1
 and not exists(select 1 from subscription_operations other where other.subscription_id=s.id and other.kind not in ('PORTAL','RECONCILE')
  and other.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN'))
 and exists(select 1 from subscription_events event where event.subscription_id=s.id and event.billing_record_id=b.id and event.event_type='PERIOD_PAID' and event.revision=s.revision)
 and exists(select 1 from payment_events event where event.payment_id=p.id and event.event_type='PAYMENT_SUCCEEDED')
$$;
create function owned_failed_renewal_current(target_admission uuid) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_renewals d where d.id=target_admission
 and owned_failed_renewal_authority(d.id) and
 case when exists(select 1 from stripe_billing_failed_renewal_publications where admission_id=d.id) then
   exists(select 1 from stripe_billing_failed_renewal_publications p join owned_failed_renewal_projected(d.id) state
    on row(p.renewal_bill,p.renewal_payment,p.projected_revision)=row(state.renewal_bill,state.renewal_payment,state.projected_revision) where p.admission_id=d.id)
 when exists(select 1 from stripe_billing_failed_renewal_recoveries where admission_id=d.id) then owned_failed_renewal_recovery_current(d.id)
 when exists(select 1 from stripe_billing_failed_renewal_failures where admission_id=d.id) then owned_failed_renewal_failure_current(d.id,null)
 else owned_first_renewal_original(d.first_renewal_admission) end)
$$;

create function require_failed_renewal_read() returns trigger language plpgsql as $$
begin
 if not owned_failed_renewal_current(new.admission_id) or new.started_at>clock_timestamp() or clock_timestamp()>=new.deadline_at
  or not exists(select 1 from stripe_billing_failed_renewals d join subscriptions s on s.id=d.subscription_id
    where d.id=new.admission_id and s.revision=new.captured_revision and s.observation_sequence=new.observation_sequence and new.started_at>=d.admitted_at)
  or (new.phase='FAILED' and exists(select 1 from stripe_billing_failed_renewal_failures where admission_id=new.admission_id))
  or (new.phase='RECOVERED' and not exists(select 1 from stripe_billing_failed_renewal_recoveries r
     where r.admission_id=new.admission_id and r.operation_id=new.selected_operation and r.operation_version=new.selected_version and new.started_at>=r.associated_at
      and owned_failed_renewal_recovery_current(r.admission_id)))
 then raise exception 'Failed renewal read lacks exact current one-use fence' using errcode='23514'; end if;
 return new;
end $$;
create trigger failed_read_proof before insert on stripe_billing_failed_renewal_reads for each row execute function require_failed_renewal_read();
create function require_failed_renewal_failure() returns trigger language plpgsql as $$
begin
 if new.recorded_at>clock_timestamp() or not exists(select 1 from stripe_billing_failed_renewals d
   join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
   join stripe_billing_first_renewal_boundary b on b.id=d.boundary_id
   join stripe_billing_failed_renewal_reads q on q.admission_id=d.id and q.id=new.claim_id and q.phase='FAILED'
   join owned_failed_renewal_debt(d.id,null) state on true
   where d.id=new.admission_id and new.recorded_at>=q.started_at and new.recorded_at<q.deadline_at and clock_timestamp()<q.deadline_at
    and new.payment_intent_reference<>a.payment_intent_reference and new.allocation_reference<>a.allocation_reference
    and row(new.invoice_reference,new.period_start,new.period_end)=row(b.invoice_reference,b.period_start,b.period_end)
    and row(new.renewal_bill,new.bill_version,new.grace_deadline,new.projected_revision,new.observation_sequence)
      =row(state.renewal_bill,state.bill_version,state.grace_deadline,state.projected_revision,state.observation_sequence))
 then raise exception 'Failed renewal receipt lacks exact projected OPEN debt' using errcode='23514'; end if;
 return new;
end $$;
create trigger failed_failure_proof before insert on stripe_billing_failed_renewal_failures for each row execute function require_failed_renewal_failure();
create function require_failed_renewal_recovery() returns trigger language plpgsql as $$
begin
 if new.associated_at>clock_timestamp() or not owned_failed_renewal_failure_current(new.admission_id,new.operation_id)
 or not owned_failed_renewal_association(new.admission_id,new.operation_id)
 or not exists(select 1 from stripe_billing_failed_renewal_failures f join subscription_operations o on o.id=new.operation_id
  where f.admission_id=new.admission_id and new.associated_at>=f.recorded_at and new.associated_at>=o.created_at and o.status='RESERVED' and o.version=new.operation_version
   and row(new.renewal_bill,new.allocation_reference,new.payment_intent_reference,new.expected_revision)
    =row(f.renewal_bill,f.allocation_reference,f.payment_intent_reference,f.projected_revision))
 then raise exception 'Failed renewal recovery lacks exact RESERVED existing-PI association' using errcode='23514'; end if;
 return new;
end $$;
create trigger failed_recovery_proof before insert on stripe_billing_failed_renewal_recoveries for each row execute function require_failed_renewal_recovery();
create function require_failed_renewal_publication() returns trigger language plpgsql as $$
begin
 if new.published_at>clock_timestamp() or not exists(select 1 from stripe_billing_failed_renewal_reads q
  join stripe_billing_failed_renewal_recoveries r on r.admission_id=q.admission_id and r.operation_id=q.selected_operation
  join owned_failed_renewal_projected(new.admission_id) state on true
  where q.id=new.claim_id and q.admission_id=new.admission_id and q.phase='RECOVERED'
   and new.published_at>=q.started_at and new.published_at<q.deadline_at and clock_timestamp()<q.deadline_at
   and new.operation_id=r.operation_id and row(new.renewal_bill,new.renewal_payment,new.projected_revision)=row(state.renewal_bill,state.renewal_payment,state.projected_revision))
 then raise exception 'Failed renewal publication lacks exact SUCCEEDED cleared-debt CASH state' using errcode='23514'; end if;
 return new;
end $$;
create trigger failed_publication_proof before insert on stripe_billing_failed_renewal_publications for each row execute function require_failed_renewal_publication();
create function require_failed_renewal_stop() returns trigger language plpgsql as $$
begin
 if new.stopped_at>clock_timestamp() or not exists(select 1 from stripe_billing_failed_renewals d where d.id=new.admission_id and new.stopped_at>=d.admitted_at)
 then raise exception 'Invalid terminal failed renewal receipt' using errcode='23514'; end if;
 return new;
end $$;
create trigger failed_stop_proof before insert on stripe_billing_failed_renewal_stops for each row execute function require_failed_renewal_stop();
