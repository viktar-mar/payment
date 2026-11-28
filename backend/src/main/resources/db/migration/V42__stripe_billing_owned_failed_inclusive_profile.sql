-- Preserve both V34 contracts; admit only the original fixed inclusive failed profile.
create or replace function require_failed_renewal_admission() returns trigger language plpgsql volatile as $$
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
     and i.amount_minor>0 and i.trial_days=0 and (i.tax_mode='NONE' or (
      i.tax_mode='INCLUSIVE' and i.amount_minor=2000 and i.currency='EUR' and i.billing_interval='MONTH'
      and exists(select 1 from site_subscription_policies fixed_policy where fixed_policy.site_id=i.site_id
        and fixed_policy.version=i.policy_version and fixed_policy.enabled and fixed_policy.grace_days=3)
      and exists(select 1 from prices fixed_price where fixed_price.id=i.price_id and fixed_price.site_id=i.site_id
        and fixed_price.product_id=i.product_id and fixed_price.price_version=i.price_version and fixed_price.tax_code is null))))
 then raise exception 'Failed renewal requires genuine unclaimed paid/READY2 lineage' using errcode='23514'; end if;
 return new;
end $$;

create or replace function owned_failed_renewal_authority(target_admission uuid) returns boolean
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
 and i.amount_minor>0 and i.trial_days=0 and (i.tax_mode='NONE' or (
   i.tax_mode='INCLUSIVE' and i.amount_minor=2000 and i.currency='EUR' and i.billing_interval='MONTH'
   and policy.grace_days=3 and price.tax_code is null))
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
