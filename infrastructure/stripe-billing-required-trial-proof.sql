-- Read-only local evidence for one ordinary required-method trial, before its boundary.
-- The provisioned Site key binds the run. Expected native references and period come from the owned receipts.
-- Callback delivery and a fresh native provider read are separate evidence.
with owned as (
  select $1::uuid run_id, $2::uuid account_id, $3::uuid site_id, $4::uuid customer_id,
         $5::uuid product_id, $6::uuid price_id, $7::uuid subscription_id, $8::uuid create_id,
         $9::text create_digest, $10::text customer_reference, $11::text setup_reference, $12::text method_reference,
         $13::text subscription_reference, $14::text item_reference, $15::text invoice_reference,
         $16::timestamptz trial_start, $17::timestamptz trial_end, statement_timestamp() observed_at
), scope as materialized (
  select x.run_id,x.account_id,x.site_id,x.customer_id,x.product_id,x.price_id,x.subscription_id,x.create_id,x.create_digest,
         x.customer_reference,x.setup_reference,x.method_reference,x.subscription_reference,x.item_reference,x.invoice_reference,
         x.trial_start,x.trial_end,x.observed_at,a.external_account_id,a.credentials_ref,
         array[a.id::text,a.external_account_id,a.credentials_ref,a.provider,a.account_mode] fingerprint_prefix,
         bc.creation_operation_id,p.price_key,p.price_version,p.amount_minor,p.currency,p.billing_interval,p.trial_days,
         p.trial_payment_method_policy,p.tax_mode,pm.provider_price_id,pd.provider_product_id
  from owned x
  join payment_accounts a on a.id=x.account_id and a.provider='STRIPE' and a.account_mode='SANDBOX' and a.account_status='ACTIVE'
  join sites site on site.id=x.site_id and site.payment_account_id=a.id and site.status='ACTIVE'
       and site.site_key='s5a-'||replace(x.run_id::text,'-','')
  join customers c on c.id=x.customer_id and c.site_id=site.id and c.status='ACTIVE'
  join stripe_billing_customers bc on bc.payment_account_id=a.id and bc.site_id=site.id and bc.customer_id=c.id
       and bc.provider_reference=x.customer_reference
  join prices p on p.id=x.price_id and p.site_id=site.id and p.product_id=x.product_id
       and p.status='PUBLISHED' and p.price_type='SUBSCRIPTION' and p.trial_days>0 and p.trial_payment_method_policy='REQUIRED_BEFORE_TRIAL'
  join provider_price_mappings pm on pm.payment_account_id=a.id and pm.price_id=p.id
  join provider_product_mappings pd on pd.payment_account_id=a.id and pd.product_id=x.product_id
  where x.observed_at>=x.trial_start and x.observed_at<x.trial_end and x.create_digest ~ '^[a-f0-9]{64}$'
    and a.external_account_id ~ '^acct_[A-Za-z0-9]{1,250}$' and a.credentials_ref ~ '^[A-Za-z0-9_.:-]+$'
    and pm.provider_price_id ~ '^price_[A-Za-z0-9]{1,249}$' and pd.provider_product_id ~ '^prod_[A-Za-z0-9]{1,250}$'
    and x.customer_reference ~ '^cus_[A-Za-z0-9]{1,251}$' and x.setup_reference ~ '^seti_[A-Za-z0-9]{1,250}$'
    and x.method_reference ~ '^pm_[A-Za-z0-9]{1,252}$' and x.subscription_reference ~ '^sub_[A-Za-z0-9]{1,251}$'
    and x.item_reference ~ '^si_[A-Za-z0-9]{1,252}$' and x.invoice_reference ~ '^in_[A-Za-z0-9]{1,252}$'
    and not exists(select 1 from stripe_billing_clock_intents clock where clock.payment_account_id=a.id and clock.site_id=site.id and clock.customer_id=c.id)
    and not exists(select 1 from payments cash where cash.site_id=site.id)
    and not exists(select 1 from payment_events cash_event where cash_event.site_id=site.id)
), original as materialized (
  select x.*,s.revision,o.provider_operation_key,o.fingerprint,o.expected_revision,o.policy_version,o.created_at,
         saved.step_id setup_step_id,trial.step_id create_step_id,trial.trial_start provider_start,trial.requested_end,
         eligible.consumed_at
  from scope x
  join subscriptions s on s.id=x.subscription_id and s.site_id=x.site_id and s.customer_id=x.customer_id
       and s.payment_account_id=x.account_id and s.product_id=x.product_id and s.provider_reference=x.subscription_reference
  join subscription_operations o on o.id=x.create_id and o.site_id=x.site_id and o.customer_id=x.customer_id and o.subscription_id=s.id
       and o.kind='CREATE' and o.status='SUCCEEDED' and o.safe_failure_code is null and o.key_digest=x.create_digest
       and o.authority_scope='customer:'||x.customer_id::text and o.expected_revision=0 and o.fingerprint ~ '^v1[.]trial[.][a-f0-9]{64}$'
       and o.provider_operation_key='subscription:'||x.account_id::text||':'||o.id::text||':CREATE' and o.provider_reference=s.provider_reference
  join site_subscription_policies policy on policy.site_id=x.site_id and policy.enabled and policy.version=o.policy_version
  join stripe_subscription_setup_proofs saved on saved.operation_id=o.id and saved.operation_kind=o.kind and saved.step_kind='SETUP_INTENT_CREATE'
       and saved.site_id=x.site_id and saved.customer_id=x.customer_id and saved.subscription_id=s.id and saved.payment_account_id=x.account_id
       and saved.setup_reference=x.setup_reference and saved.method_reference=x.method_reference
  join stripe_subscription_trial_proofs trial on trial.operation_id=o.id and trial.operation_kind=o.kind and trial.step_kind='SUBSCRIPTION_CREATE'
       and trial.site_id=x.site_id and trial.customer_id=x.customer_id and trial.subscription_id=s.id and trial.payment_account_id=x.account_id
       and trial.subscription_reference=s.provider_reference and trial.item_reference=x.item_reference and trial.invoice_reference=x.invoice_reference
       and to_timestamp(trial.trial_start)=x.trial_start and to_timestamp(trial.requested_end)=x.trial_end
  join subscription_trial_eligibility eligible on eligible.site_id=x.site_id and eligible.customer_id=x.customer_id
       and eligible.product_id=s.product_id and eligible.reserved_by_operation=o.id and eligible.consumed_at is not null
  where s.billing_status='TRIALING' and s.revision>0 and s.paid_through is null and s.terminal_reason is null
    and s.cancellation_effective_at is null and s.debt_billing_record_id is null and s.pending_operation_id is null
    and s.current_period_start=x.trial_start and s.current_period_end=x.trial_end and s.trial_start=x.trial_start and s.trial_end=x.trial_end
    and s.currency=x.currency
    and (s.billing_price_id,s.billing_price_key,s.billing_price_version,s.billing_amount_minor,s.billing_interval,s.billing_trial_days,s.billing_trial_policy,s.billing_tax_mode)
        =(x.price_id,x.price_key,x.price_version,x.amount_minor,x.billing_interval,x.trial_days,x.trial_payment_method_policy,x.tax_mode)
    and (s.entitled_price_id,s.entitled_price_key,s.entitled_price_version,s.entitled_amount_minor,s.entitled_interval,s.entitled_trial_days,s.entitled_trial_policy,s.entitled_tax_mode)
        =(x.price_id,x.price_key,x.price_version,x.amount_minor,x.billing_interval,x.trial_days,x.trial_payment_method_policy,x.tax_mode)
), acknowledged as materialized (
  select d.id,d.kind,d.resource_id,d.operation_key,d.fingerprint,d.provider_reference,d.first_dispatched_at,d.retry_before
  from original x
  join provider_operations d on d.id in (x.creation_operation_id,x.setup_step_id,x.create_step_id)
       and d.payment_account_id=x.account_id and d.external_account_id=x.external_account_id and d.credentials_ref=x.credentials_ref
  where d.status='ACKNOWLEDGED' and d.first_dispatched_at is not null and d.retry_before>d.first_dispatched_at
    and d.recovery_attempts=0 and not d.manual_review_required and d.safe_error_code is null
    and d.fingerprint ~ '^[a-f0-9]{64}$'
), proven_dispatch as materialized (
  select x.*
  from original x
  join acknowledged cd on cd.id=x.creation_operation_id and cd.kind='CUSTOMER_CREATE' and cd.resource_id=x.customer_id
       and cd.operation_key='billing_customer_'||x.account_id::text||'_'||x.site_id::text||'_'||x.customer_id::text
       and cd.provider_reference=x.customer_reference
  join acknowledged sd on sd.id=x.setup_step_id and sd.kind='SETUP_INTENT_CREATE' and sd.resource_id=x.create_id
       and sd.operation_key=x.provider_operation_key||':SETUP' and sd.provider_reference=x.setup_reference
  join acknowledged d on d.id=x.create_step_id and d.kind='SUBSCRIPTION_CREATE' and d.resource_id=x.create_id
       and d.operation_key=x.provider_operation_key and d.provider_reference=x.subscription_reference
  -- Recompute the exact ASCII, length-prefixed fingerprints from ProviderRequestFingerprint.
  cross join lateral (
    select encode(sha256(convert_to(string_agg(length(v)::text||':'||v,'' order by n),'UTF8')),'hex') fingerprint
    from unnest(x.fingerprint_prefix||array['CUSTOMER_CREATE',x.site_id::text,x.customer_id::text]) with ordinality as part(v,n)
  ) customer_request
  cross join lateral (
    select encode(sha256(convert_to(string_agg(length(v)::text||':'||v,'' order by n),'UTF8')),'hex') fingerprint
    from unnest(x.fingerprint_prefix||array['SETUP_INTENT_CREATE_V1',x.create_id::text,sd.operation_key,x.fingerprint,x.policy_version::text,
      x.subscription_id::text,x.site_id::text,x.customer_id::text,x.customer_reference,'off_session','card','consent:true']) with ordinality as part(v,n)
  ) setup_request
  cross join lateral (
    select encode(sha256(convert_to(string_agg(length(v)::text||':'||v,'' order by n),'UTF8')),'hex') fingerprint
    from unnest(x.fingerprint_prefix||array['SUBSCRIPTION_CREATE',x.create_id::text,x.provider_operation_key,x.fingerprint,
      x.expected_revision::text,x.policy_version::text,x.subscription_id::text,x.site_id::text,x.customer_id::text,x.customer_reference,
      x.price_id::text,x.price_version::text,x.product_id::text,x.provider_price_id,x.provider_product_id,
      x.amount_minor::text,x.currency::text,x.billing_interval,x.trial_days::text,x.trial_payment_method_policy,x.tax_mode,
      'paid','consent:true','classic','charge_automatically','default_incomplete','card','on_subscription','quantity:1']) with ordinality as part(v,n)
  ) base_request
  cross join lateral (
    select encode(sha256(convert_to(string_agg(length(v)::text||':'||v,'' order by n),'UTF8')),'hex') fingerprint
    from unnest(x.fingerprint_prefix||array['SUBSCRIPTION_TRIAL_CREATE_V1',x.create_id::text,x.provider_operation_key,x.fingerprint,
      base_request.fingerprint,x.requested_end::text,'missing_payment_method:pause',sd.id::text,x.setup_reference,
      x.method_reference]) with ordinality as part(v,n)
  ) trial_request
  where cd.fingerprint=customer_request.fingerprint and sd.fingerprint=setup_request.fingerprint and d.fingerprint=trial_request.fingerprint
    and cd.first_dispatched_at<=sd.first_dispatched_at and sd.first_dispatched_at>=x.created_at
    and sd.first_dispatched_at<=d.first_dispatched_at and d.first_dispatched_at<=x.consumed_at
    and x.provider_start>=floor(extract(epoch from d.first_dispatched_at)) and x.provider_start<floor(extract(epoch from d.retry_before))
)
select x.run_id,b.id billing_record_id,j.id trial_started_event_id,x.revision,x.trial_start,x.trial_end,
       x.observed_at access_evaluated_at,least(x.trial_end,x.observed_at+interval '5 minutes') access_valid_until
from proven_dispatch x
join subscription_billing_records b on b.site_id=x.site_id and b.subscription_id=x.subscription_id and b.product_id=x.product_id
     and b.payment_account_id=x.account_id and b.provider_invoice_reference=x.invoice_reference
     and b.kind='INITIAL' and b.settlement='ZERO_AMOUNT' and b.amount_due_minor=0 and b.previous_price_id is null
     and b.period_start=x.trial_start and b.period_end=x.trial_end and b.billed_price_id=x.price_id and b.currency=x.currency
join subscription_events j on j.site_id=x.site_id and j.subscription_id=x.subscription_id and j.payment_account_id=x.account_id
     and j.event_type='TRIAL_STARTED' and j.source='RECONCILIATION' and j.revision=x.revision
     and j.fact_key='projection:'||x.revision::text and j.provider_reference=x.subscription_reference
     and j.operation_id is null and j.payment_id is null and j.billing_record_id is null
     and j.received_at=x.consumed_at and j.occurred_at>=x.trial_start and j.occurred_at<x.trial_end
where x.consumed_at<=x.observed_at and j.occurred_at<=x.consumed_at and b.created_at=j.received_at
  and (b.billed_price_key,b.billed_price_version,b.billed_amount_minor,b.billed_interval,b.billed_trial_days,b.billed_trial_policy,b.billed_tax_mode)
      =(x.price_key,x.price_version,x.amount_minor,x.billing_interval,x.trial_days,x.trial_payment_method_policy,x.tax_mode)
  and (select count(*)=1 from subscription_events e where e.site_id=x.site_id and e.subscription_id=x.subscription_id and e.payment_account_id=x.account_id)
  and (select count(*)=1 from subscription_billing_records r where r.site_id=x.site_id and r.subscription_id=x.subscription_id and r.payment_account_id=x.account_id)
  and not exists(select 1 from subscription_provider_objects z where z.site_id=x.site_id and z.subscription_id=x.subscription_id
                 and z.payment_account_id=x.account_id and z.object_type='PAYMENT')
  and exists(select 1 from subscription_provider_objects z where z.site_id=x.site_id and z.subscription_id=x.subscription_id and z.payment_account_id=x.account_id
             and z.provider_type='STRIPE' and z.object_type='SUBSCRIPTION' and z.provider_reference=x.subscription_reference and z.billing_record_id is null)
  and exists(select 1 from subscription_provider_objects z where z.site_id=x.site_id and z.subscription_id=x.subscription_id and z.payment_account_id=x.account_id
             and z.provider_type='STRIPE' and z.object_type='SETUP' and z.provider_reference=x.setup_reference and z.billing_record_id is null)
  and exists(select 1 from subscription_provider_objects z where z.site_id=x.site_id and z.subscription_id=x.subscription_id and z.payment_account_id=x.account_id
             and z.provider_type='STRIPE' and z.object_type='INVOICE' and z.provider_reference=b.provider_invoice_reference and z.billing_record_id=b.id)
