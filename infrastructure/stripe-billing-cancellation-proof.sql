-- Only the fresh run's Site, customer, subscription, original v2 payment and captured command IDs.
with owned as (
  select $1::uuid account_id, $2::uuid site_id, $3::uuid customer_id, $4::uuid subscription_id,
         $5::uuid create_id, $6::uuid attempt_id, $7::uuid product_id, $8::uuid price_id,
         $9::text create_digest, $10::text confirm_digest, $11::text origin, $12::text site_key,
         $13::uuid schedule_id, $14::text schedule_digest, $15::uuid withdraw_id, $16::text withdraw_digest
), expected as (
  select schedule_id id,schedule_digest digest,'CANCEL' kind,'PERIOD_END' mode,'CANCEL_SCHEDULED' event_type from owned
  union all
  select withdraw_id,withdraw_digest,'WITHDRAW_CANCEL','WITHDRAW','CANCEL_WITHDRAWN' from owned where withdraw_id is not null
), transitions as (
  select o.id operation_id,o.key_digest key_hash,o.kind,p.mode,o.expected_revision,e.id event_id,e.revision,
         extract(epoch from p.boundary)::bigint boundary,p.prior_operation,p.prior_event,p.prior_event_revision,
         (o.status='SUCCEEDED' and o.safe_failure_code is null and o.key_digest=w.digest and p.mode=w.mode
          and o.authority_scope='customer:'||x.customer_id::text and o.policy_version=policy.version and policy.enabled
          and o.provider_operation_key='subscription:'||x.account_id::text||':'||o.id::text||':'||w.kind
          and o.fingerprint ~ ('^'||case when w.kind='WITHDRAW_CANCEL' then 'v[12]' else 'v1' end
            ||'[.]cancel[.]'||lower(w.mode)||'[.]'||extract(epoch from p.boundary)::bigint::text||'/0[.][a-f0-9]{64}$')
          and o.provider_reference=s.provider_reference and p.subscription_reference=s.provider_reference and p.boundary=s.paid_through
          and step.status='ACKNOWLEDGED' and step.operation_key=o.provider_operation_key
          and step.external_account_id='acct_1UPiMpELswAd8tpR' and step.credentials_ref='stripe-sandbox-default'
          and step.first_dispatched_at>=o.created_at and p.acknowledged_at>=step.first_dispatched_at
          and p.acknowledged_at<step.retry_before and p.acknowledged_at<p.boundary
          and step.recovery_attempts=0 and not step.manual_review_required and step.fingerprint ~ '^[a-f0-9]{64}$'
          and e.provider_reference=s.provider_reference and e.fact_key='projection:'||e.revision::text
          and e.billing_record_id is null and e.payment_id is null and e.occurred_at<p.boundary
         ) acknowledged
  from owned x cross join expected w
  join subscription_operations o on o.id=w.id and o.kind=w.kind
  join subscriptions s on s.id=o.subscription_id and s.site_id=o.site_id and s.customer_id=o.customer_id
  join site_subscription_policies policy on policy.site_id=s.site_id
  join stripe_subscription_cancellation_proofs p on p.operation_id=o.id and p.subscription_id=s.id
       and p.payment_account_id=s.payment_account_id and p.site_id=s.site_id and p.customer_id=s.customer_id and p.operation_kind=o.kind
  join provider_operations step on step.id=p.step_id and step.payment_account_id=p.payment_account_id
       and step.resource_id=o.id and p.step_kind='SUBSCRIPTION_UPDATE' and step.kind=p.step_kind and step.provider_reference=p.subscription_reference
  join subscription_events e on e.site_id=s.site_id and e.subscription_id=s.id and e.payment_account_id=s.payment_account_id
       and e.event_type=w.event_type and e.revision=o.expected_revision+1
  where s.id=x.subscription_id and s.site_id=x.site_id and s.customer_id=x.customer_id and s.payment_account_id=x.account_id
)
select bc.provider_reference customer_reference,s.provider_reference subscription_reference,
       pm.provider_product_id product_reference,pr.provider_price_id price_reference,g.billing_invoice_reference invoice_reference,
       g.billing_allocation_reference allocation_reference,g.billing_intent_reference intent_reference,
       extract(epoch from b.period_start)::bigint period_start,extract(epoch from b.period_end)::bigint period_end,
       b.id billing_record_id,p.id payment_id,paid.id original_paid_event_id,s.revision,extract(epoch from s.cancellation_effective_at)::bigint cancellation_at,
       (s.billing_status='ACTIVE' and s.trial_start is null and s.trial_end is null and s.terminal_reason is null
        and s.billing_price_id=x.price_id and s.billing_price_version=1 and s.billing_amount_minor=2000 and s.currency='EUR'
        and s.billing_interval='MONTH' and s.billing_trial_days=0 and s.billing_tax_mode='INCLUSIVE'
        and s.entitled_price_id=x.price_id and s.entitled_price_version=1 and s.entitled_amount_minor=2000
        and s.entitled_interval='MONTH' and s.entitled_trial_days=0 and s.entitled_tax_mode='INCLUSIVE'
        and s.current_period_start=b.period_start and s.current_period_end=b.period_end and s.paid_through=b.period_end
        and statement_timestamp()>=b.period_start and statement_timestamp()<b.period_end
        and s.debt_billing_record_id is null and s.pending_operation_id is null
        and b.kind='INITIAL' and b.settlement='CONFIRMED_CASH' and b.amount_due_minor=2000 and b.currency='EUR'
        and b.billed_price_id=x.price_id and b.billed_price_version=1 and b.billed_amount_minor=2000 and b.billed_trial_days=0
        and b.billed_interval='MONTH' and b.billed_tax_mode='INCLUSIVE'
        and p.source='SUBSCRIPTION' and p.status='SUCCEEDED' and p.amount_minor=2000 and p.currency='EUR' and p.refunded_amount_minor=0
        and p.subscription_billing_kind='INITIAL' and p.subscription_price_id=x.price_id and p.subscription_price_version=1
        and p.subscription_product_id=x.product_id and p.subscription_invoice_reference=b.provider_invoice_reference
        and p.subscription_period_start=b.period_start and p.subscription_period_end=b.period_end
        and not exists(select 1 from refunds r where r.payment_id=p.id)
        and exists(select 1 from subscription_provider_objects z where z.site_id=s.site_id and z.subscription_id=s.id and z.payment_account_id=s.payment_account_id
                   and z.provider_type='STRIPE' and z.object_type='PAYMENT' and z.provider_reference=g.billing_intent_reference and z.billing_record_id=b.id and z.payment_id=p.id)
        and exists(select 1 from subscription_provider_objects z where z.site_id=s.site_id and z.subscription_id=s.id and z.payment_account_id=s.payment_account_id
                   and z.provider_type='STRIPE' and z.object_type='INVOICE' and z.provider_reference=b.provider_invoice_reference and z.billing_record_id=b.id)
       ) cash,
       (o.status='SUCCEEDED' and o.key_digest=x.create_digest and o.provider_reference=s.provider_reference
        and d.kind='SUBSCRIPTION_CREATE' and d.status='ACKNOWLEDGED' and d.provider_reference=s.provider_reference
        and d.operation_key=o.provider_operation_key and d.external_account_id=acct.external_account_id and d.credentials_ref=acct.credentials_ref
        and dc.kind='CUSTOMER_CREATE' and dc.status='ACKNOWLEDGED' and dc.provider_reference=bc.provider_reference
        and dc.resource_id=x.customer_id and dc.external_account_id=acct.external_account_id and dc.credentials_ref=acct.credentials_ref
        and g.billing_step_kind=d.kind and g.billing_schema='payment-v1' and g.billing_operation_key=d.operation_key
        and g.billing_subscription_reference=s.provider_reference and g.billing_customer_reference=bc.provider_reference
        and g.recurring_consent and g.consent_purpose='CREATE' and g.superseded_at is null
        and chain.root_digest=g.token_digest and chain.origin=x.origin
        and attempt.state='OBSERVE' and attempt.predecessor is null and attempt.successor is null
        and attempt.confirm_key=x.confirm_digest and attempt.provider_key='billing-confirm-v2:'||x.attempt_id::text
        and (select count(*)=1 from subscription_confirmation_attempts z where z.chain_id=chain.id)
       ) original,
       ((select count(*)=1 from payment_events e where e.site_id=s.site_id and e.payment_account_id=s.payment_account_id and e.payment_id=p.id
           and e.event_type='PAYMENT_SUCCEEDED' and e.amount_minor=2000 and e.currency='EUR')
        and (select count(*)=1 from payment_events e where e.site_id=s.site_id and e.payment_account_id=s.payment_account_id and e.payment_id=p.id)
        and not exists(select 1 from subscription_events e where e.site_id=s.site_id and e.payment_account_id=s.payment_account_id and e.subscription_id=s.id
             and e.event_type in ('CANCEL_SCHEDULED','CANCEL_WITHDRAWN','CANCELLED')
             and not exists(select 1 from transitions r where r.event_id=e.id))
       ) journal,
       ((select count(*)=1 from subscriptions z where z.site_id=s.site_id and z.customer_id=s.customer_id)
        and (select count(*)=1 from subscription_billing_records z where z.site_id=s.site_id and z.subscription_id=s.id)
        and (select count(*)=1 from payments z where z.site_id=s.site_id and z.customer_id=s.customer_id)
        and (select count(*)=1+(select count(*) from expected) from subscription_operations z where z.site_id=s.site_id and z.customer_id=s.customer_id and z.subscription_id=s.id)
        and not exists(select 1 from subscription_operations z where z.site_id=s.site_id and z.customer_id=s.customer_id and z.subscription_id=s.id
                       and z.id<>x.create_id and not exists(select 1 from expected w where w.id=z.id and w.kind=z.kind))
        and not exists(select 1 from provider_operations z where z.payment_account_id=x.account_id and z.kind not in ('PRODUCT_CREATE','PRICE_CREATE')
                       and z.id not in (dc.id,d.id) and not exists(select 1 from expected w where w.id=z.resource_id and z.kind='SUBSCRIPTION_UPDATE'))
       ) closed_inventory,
       (select coalesce(jsonb_agg(to_jsonb(t) order by t.expected_revision),'[]'::jsonb) from transitions t) receipts,
       (select coalesce(jsonb_agg(jsonb_build_object('id',e.id,'type',e.callback_type,'payload',e.payload,'delivered',
          e.status='DELIVERED' and e.endpoint_url='http://callback-receiver:8080/callback'
          and (select count(*)=1 from outbox_deliveries z where z.outbox_event_id=e.id)
          and exists(select 1 from outbox_deliveries z where z.outbox_event_id=e.id and z.status='SUCCEEDED' and z.http_status=204)
          and (e.source_kind='PAYMENT' and e.payment_id=p.id and exists(select 1 from payment_events z where z.id=e.id and z.payment_id=p.id and z.event_type='PAYMENT_SUCCEEDED')
            or e.source_kind='SUBSCRIPTION' and e.subscription_event_id=e.id and exists(select 1 from subscription_events z
                  where z.id=e.id and z.site_id=s.site_id and z.subscription_id=s.id and z.payment_account_id=s.payment_account_id
                    and e.payload->>'revision'=z.revision::text and (e.payload->>'occurred_at')::timestamptz=z.occurred_at
                    and (e.payload->>'billing_record_id') is not distinct from z.billing_record_id::text
                    and (e.payload->>'payment_id') is not distinct from z.payment_id::text))
          ) order by e.id),'[]'::jsonb)
          from outbox_events e where e.site_id=s.site_id and e.payment_account_id=s.payment_account_id
            and (e.subscription_id=s.id or e.payment_id=p.id)) callbacks
from owned x
join sites site on site.id=x.site_id and site.site_key=x.site_key and site.payment_account_id=x.account_id and site.status='ACTIVE'
join customers customer on customer.id=x.customer_id and customer.site_id=site.id and customer.status='ACTIVE'
join payment_accounts acct on acct.id=x.account_id and acct.provider='STRIPE' and acct.account_mode='SANDBOX' and acct.account_status='ACTIVE'
     and acct.account_key='stripe-sandbox-default' and acct.external_account_id='acct_1UPiMpELswAd8tpR' and acct.credentials_ref='stripe-sandbox-default'
join subscriptions s on s.id=x.subscription_id and s.site_id=x.site_id and s.customer_id=x.customer_id and s.product_id=x.product_id and s.payment_account_id=x.account_id
join subscription_operations o on o.id=x.create_id and o.site_id=s.site_id and o.customer_id=s.customer_id and o.subscription_id=s.id and o.kind='CREATE'
join stripe_billing_customers bc on bc.payment_account_id=x.account_id and bc.site_id=s.site_id and bc.customer_id=s.customer_id
join provider_operations dc on dc.id=bc.creation_operation_id and dc.payment_account_id=x.account_id
join subscription_action_capabilities g on g.operation_id=o.id and g.site_id=s.site_id and g.customer_id=s.customer_id and g.subscription_id=s.id and g.payment_account_id=x.account_id
join provider_operations d on d.id=g.billing_step_id and d.resource_id=o.id and d.payment_account_id=x.account_id
join subscription_confirmation_chains chain on chain.id=g.id and chain.operation_id=o.id
join subscription_confirmation_attempts attempt on attempt.id=x.attempt_id and attempt.chain_id=chain.id
join subscription_billing_records b on b.site_id=s.site_id and b.subscription_id=s.id and b.payment_account_id=x.account_id
     and b.product_id=x.product_id and b.provider_invoice_reference=g.billing_invoice_reference
join subscription_events paid on paid.site_id=s.site_id and paid.subscription_id=s.id and paid.payment_account_id=x.account_id
     and paid.event_type='PERIOD_PAID' and paid.billing_record_id=b.id and paid.provider_reference=s.provider_reference and paid.payment_id is null
join payments p on p.site_id=s.site_id and p.customer_id=s.customer_id and p.subscription_id=s.id and p.payment_account_id=x.account_id
     and p.billing_record_id=b.id and p.provider_payment_reference=g.billing_intent_reference
join provider_product_mappings pm on pm.product_id=x.product_id and pm.payment_account_id=x.account_id
join provider_price_mappings pr on pr.price_id=x.price_id and pr.payment_account_id=x.account_id
limit 2
