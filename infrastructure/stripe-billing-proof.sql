-- Closed initial-success proof. Values are only the newly owned fixture and captured original dispatch keys.
-- The same SELECT is executed against actual migrations by BillingAcceptanceSqlIntegrationTest in hosted CI.
with owned as (
 select $1::uuid account_id, $2::uuid site_id, $3::uuid customer_id, $4::uuid subscription_id,
        $5::uuid operation_id, $6::uuid attempt_id, $7::uuid product_id, $8::uuid price_id,
        $9::text create_digest, $10::text confirm_digest, $11::text origin
)
select bc.provider_reference customer_reference, s.provider_reference subscription_reference,
 pm.provider_product_id product_reference, pr.provider_price_id price_reference,
 g.billing_invoice_reference invoice_reference, g.billing_allocation_reference allocation_reference,
 g.billing_intent_reference intent_reference, extract(epoch from b.period_start)::bigint period_start,
 extract(epoch from b.period_end)::bigint period_end, p.id payment_id, b.id billing_record_id,
 jsonb_build_object(
 'cash', s.billing_status='ACTIVE' and s.trial_start is null and s.trial_end is null
   and s.billing_price_id=x.price_id and s.billing_price_version=1 and s.billing_amount_minor=2000
   and s.billing_interval='MONTH' and s.billing_trial_days=0 and s.billing_tax_mode='INCLUSIVE'
   and s.entitled_price_id=x.price_id and s.entitled_price_version=1 and s.paid_through=b.period_end
   and s.current_period_start=b.period_start and s.current_period_end=b.period_end
   and s.debt_billing_record_id is null and s.pending_operation_id is null
   and b.kind='INITIAL' and b.settlement='CONFIRMED_CASH' and b.amount_due_minor=2000 and b.currency='EUR'
   and b.billed_price_id=x.price_id and b.billed_price_version=1 and b.billed_trial_days=0
   and b.billed_interval='MONTH' and b.billed_tax_mode='INCLUSIVE'
   and p.source='SUBSCRIPTION' and p.status='SUCCEEDED' and p.amount_minor=2000 and p.currency='EUR'
   and p.refunded_amount_minor=0 and p.subscription_billing_kind='INITIAL' and p.subscription_price_id=x.price_id
   and p.subscription_price_version=1 and p.subscription_product_id=x.product_id
   and p.subscription_invoice_reference=b.provider_invoice_reference
   and p.subscription_period_start=b.period_start and p.subscription_period_end=b.period_end
   and (select count(*)=1 from subscriptions z where z.site_id=x.site_id and z.customer_id=x.customer_id)
   and (select count(*)=1 from subscription_billing_records z where z.site_id=x.site_id and z.subscription_id=x.subscription_id)
   and (select count(*)=1 from payments z where z.site_id=x.site_id and z.customer_id=x.customer_id)
   and exists(select 1 from subscription_provider_objects z where z.site_id=x.site_id and z.payment_account_id=x.account_id
       and z.subscription_id=x.subscription_id and z.object_type='PAYMENT' and z.provider_type='STRIPE'
       and z.provider_reference=g.billing_intent_reference and z.billing_record_id=b.id and z.payment_id=p.id)
   and exists(select 1 from subscription_provider_objects z where z.site_id=x.site_id and z.payment_account_id=x.account_id
       and z.subscription_id=x.subscription_id and z.object_type='INVOICE' and z.provider_type='STRIPE'
       and z.provider_reference=g.billing_invoice_reference and z.billing_record_id=b.id),
 'original', o.kind='CREATE' and o.status='SUCCEEDED' and o.key_digest=x.create_digest
   and o.provider_reference=s.provider_reference and o.provider_operation_key=d.operation_key
   and d.kind='SUBSCRIPTION_CREATE' and d.status='ACKNOWLEDGED' and d.provider_reference=s.provider_reference
   and d.external_account_id='acct_1UPiMpELswAd8tpR' and d.credentials_ref='stripe-sandbox-default'
   and d.first_dispatched_at is not null and d.recovery_attempts=0 and not d.manual_review_required
   and d.fingerprint ~ '^[0-9a-f]{64}$'
   and dc.kind='CUSTOMER_CREATE' and dc.status='ACKNOWLEDGED' and dc.resource_id=x.customer_id
   and dc.provider_reference=bc.provider_reference and dc.first_dispatched_at is not null
   and dc.recovery_attempts=0 and not dc.manual_review_required
   and dc.external_account_id=d.external_account_id and dc.credentials_ref=d.credentials_ref
   and g.billing_schema='payment-v1' and g.billing_step_kind='SUBSCRIPTION_CREATE'
   and g.billing_step_id=d.id and g.billing_operation_key=d.operation_key
   and g.billing_subscription_reference=s.provider_reference and g.billing_customer_reference=bc.provider_reference
   and g.recurring_consent and g.consent_purpose='CREATE' and g.client_session_id is null
   and g.superseded_at is null and g.confirmation_key_digest is null
   and chain.root_digest=g.token_digest and chain.origin=x.origin and chain.operation_id=x.operation_id
   and a.state='OBSERVE' and a.predecessor is null and a.successor is null and a.retry_key is null
   and a.claimed_at is not null and a.confirm_key=x.confirm_digest
   and a.provider_key='billing-confirm-v2:'||x.attempt_id::text and a.token_digest ~ '^[0-9a-f]{64}$'
   and a.fingerprint ~ '^[0-9a-f]{64}$'
   and (select count(*)=1 from subscription_operations z where z.site_id=x.site_id and z.customer_id=x.customer_id)
   and (select count(*)=1 from subscription_action_capabilities z where z.site_id=x.site_id and z.operation_id=x.operation_id)
   and (select count(*)=1 from subscription_confirmation_attempts z where z.chain_id=chain.id)
   and (select count(*)=1 from provider_operations z where z.payment_account_id=x.account_id and z.kind='SUBSCRIPTION_CREATE')
   and (select count(*)=1 from provider_operations z where z.payment_account_id=x.account_id and z.kind='CUSTOMER_CREATE')
   and not exists(select 1 from provider_operations z where z.payment_account_id=x.account_id
       and z.kind not in ('PRODUCT_CREATE','PRICE_CREATE','CUSTOMER_CREATE','SUBSCRIPTION_CREATE')),
 'journal', (select count(*)=1 from payment_events e where e.site_id=x.site_id and e.payment_account_id=x.account_id
       and e.payment_id=p.id and e.event_type='PAYMENT_SUCCEEDED' and e.amount_minor=2000 and e.currency='EUR')
   and exists(select 1 from subscription_events e where e.site_id=x.site_id and e.payment_account_id=x.account_id
       and e.subscription_id=x.subscription_id and e.billing_record_id=b.id and e.event_type='PERIOD_PAID' and e.payment_id is null),
 'webhook', exists(select 1 from provider_inbox i where i.payment_account_id=x.account_id and i.provider_type='STRIPE'
       and i.event_family='SUBSCRIPTION' and i.event_type='SUBSCRIPTION_INVOICE_CHANGED'
       and i.provider_object_id=g.billing_invoice_reference and i.status='PROCESSED' and i.payload_hash ~ '^[0-9a-f]{64}$')
 ) proof,
 (select coalesce(jsonb_agg(jsonb_build_object('id',e.id,'type',e.callback_type,'payload',e.payload,
      'delivered',e.status='DELIVERED' and e.endpoint_url='http://callback-receiver:8080/callback'
       and (select count(*)=1 from outbox_deliveries z where z.outbox_event_id=e.id)
       and exists(select 1 from outbox_deliveries z where z.outbox_event_id=e.id and z.status='SUCCEEDED' and z.http_status=204)
       and (e.source_kind='PAYMENT' and e.payment_id=p.id and exists(select 1 from payment_events z where z.id=e.id and z.payment_id=p.id)
        or e.source_kind='SUBSCRIPTION' and e.subscription_id=x.subscription_id and exists(select 1 from subscription_events z where z.id=e.id and z.subscription_id=x.subscription_id))
  ) order by e.id),'[]'::jsonb) from outbox_events e where e.site_id=x.site_id and e.payment_account_id=x.account_id) callbacks
from owned x
join payment_accounts acct on acct.id=x.account_id and acct.provider='STRIPE' and acct.account_mode='SANDBOX' and acct.account_status='ACTIVE' and acct.account_key='stripe-sandbox-default' and acct.external_account_id='acct_1UPiMpELswAd8tpR' and acct.credentials_ref='stripe-sandbox-default'
join sites site on site.id=x.site_id and site.payment_account_id=x.account_id and site.status='ACTIVE'
join subscriptions s on s.id=x.subscription_id and s.site_id=x.site_id and s.customer_id=x.customer_id and s.payment_account_id=x.account_id and s.product_id=x.product_id
join subscription_operations o on o.id=x.operation_id and o.subscription_id=s.id and o.site_id=x.site_id and o.customer_id=x.customer_id
join stripe_billing_customers bc on bc.payment_account_id=x.account_id and bc.site_id=x.site_id and bc.customer_id=x.customer_id
join provider_operations dc on dc.id=bc.creation_operation_id and dc.payment_account_id=x.account_id
join subscription_action_capabilities g on g.operation_id=o.id and g.site_id=x.site_id and g.customer_id=x.customer_id and g.payment_account_id=x.account_id and g.subscription_id=s.id
join provider_operations d on d.id=g.billing_step_id and d.payment_account_id=x.account_id and d.resource_id=o.id
join subscription_confirmation_chains chain on chain.id=g.id and chain.operation_id=o.id
join subscription_confirmation_attempts a on a.id=x.attempt_id and a.chain_id=chain.id
join subscription_billing_records b on b.subscription_id=s.id and b.site_id=x.site_id and b.payment_account_id=x.account_id and b.product_id=x.product_id and b.provider_invoice_reference=g.billing_invoice_reference
join payments p on p.subscription_id=s.id and p.site_id=x.site_id and p.payment_account_id=x.account_id and p.customer_id=x.customer_id and p.billing_record_id=b.id and p.provider_payment_reference=g.billing_intent_reference
join provider_product_mappings pm on pm.payment_account_id=x.account_id and pm.product_id=x.product_id
join provider_price_mappings pr on pr.payment_account_id=x.account_id and pr.price_id=x.price_id
limit 2
