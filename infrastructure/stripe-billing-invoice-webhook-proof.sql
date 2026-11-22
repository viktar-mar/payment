-- The five stages read the same original ordinary subscription. Stripe normalizes both ingress
-- subtypes; the owner supplies independently authenticated provider event IDs and occurrence times.
with scope as (
 select $1::uuid account_id,$2::uuid site_id,$3::uuid customer_id,$4::uuid subscription_id,
   $5::uuid operation_id,$6::uuid attempt_id,$7::uuid product_id,$8::uuid price_id,
   $9::text create_digest,$10::text confirm_digest,$11::text origin,$12::text site_key,$13::text stage,
   $14::uuid reconcile_id,$15::text reconcile_digest,$16::text paid_event,$17::bigint paid_time,
   $18::text finalized_event,$19::bigint finalized_time
), graph as (
 select x.*,s,bc,g,o,a,b,p,re,dc,d,chain,pm.provider_product_id product_reference,pr.provider_price_id price_reference,
   x.stage in ('reconciled','paid','finalized') settled
 from scope x
 join payment_accounts acct on acct.id=x.account_id and acct.account_key='stripe-sandbox-default'
   and acct.credentials_ref='stripe-sandbox-default' and acct.external_account_id='acct_1UPiMpELswAd8tpR'
   and acct.provider='STRIPE' and acct.account_mode='SANDBOX' and acct.account_status='ACTIVE'
 join sites site on site.id=x.site_id and site.site_key=x.site_key and site.payment_account_id=x.account_id and site.status='ACTIVE'
 join customers cust on cust.id=x.customer_id and cust.site_id=x.site_id and cust.status='ACTIVE'
 join subscriptions s on s.id=x.subscription_id and s.site_id=x.site_id and s.customer_id=x.customer_id
   and s.payment_account_id=x.account_id and s.product_id=x.product_id
 join subscription_operations o on o.id=x.operation_id and o.subscription_id=s.id and o.site_id=x.site_id and o.customer_id=x.customer_id
 join stripe_billing_customers bc on bc.customer_id=x.customer_id and bc.site_id=x.site_id and bc.payment_account_id=x.account_id
 join provider_operations dc on dc.id=bc.creation_operation_id and dc.payment_account_id=x.account_id and dc.resource_id=x.customer_id
 join subscription_action_capabilities g on g.operation_id=o.id and g.subscription_id=s.id and g.site_id=x.site_id
   and g.customer_id=x.customer_id and g.payment_account_id=x.account_id
 join provider_operations d on d.id=g.billing_step_id and d.payment_account_id=x.account_id and d.resource_id=o.id
 join subscription_confirmation_chains chain on chain.id=g.id and chain.operation_id=o.id
 join subscription_confirmation_attempts a on a.id=x.attempt_id and a.chain_id=chain.id
 join provider_product_mappings pm on pm.payment_account_id=x.account_id and pm.product_id=x.product_id
 join provider_price_mappings pr on pr.payment_account_id=x.account_id and pr.price_id=x.price_id
 left join subscription_operations re on re.id=x.reconcile_id and re.subscription_id=s.id and re.customer_id=x.customer_id and re.site_id=x.site_id
 left join subscription_billing_records b on b.site_id=x.site_id and b.subscription_id=s.id and b.payment_account_id=x.account_id
   and b.product_id=x.product_id and b.provider_invoice_reference=g.billing_invoice_reference
 left join payments p on p.site_id=x.site_id and p.customer_id=x.customer_id and p.subscription_id=s.id and p.payment_account_id=x.account_id
   and p.billing_record_id=b.id and p.provider_payment_reference=g.billing_intent_reference
), journal as (
 select e.id,'cash' role,e.payment_account_id=x.account_id and e.payment_id=(x.p).id and e.event_type='PAYMENT_SUCCEEDED'
   and e.amount_minor=2000 and e.currency='EUR' and e.source='PROVIDER_EVENT'
   and e.received_at between (x.re).created_at and (x.re).updated_at
   and e.provider_object_id=(x.g).billing_intent_reference and e.request_id='cash_'||e.id
   and e.provider_event_id='cash:'||encode(sha256(convert_to((x.g).billing_intent_reference||chr(10)||'SUCCEEDED','UTF8')),'hex') valid
 from graph x join payment_events e on e.site_id=x.site_id
 union all
 select e.id,case when e.event_type='CREATED' then 'created' else 'period' end,
   e.payment_account_id=x.account_id and e.subscription_id=x.subscription_id and e.payment_id is null and
   case when e.event_type='CREATED' then e.billing_record_id is null
     else e.event_type='PERIOD_PAID' and e.billing_record_id=(x.b).id and e.source='RECONCILIATION'
       and e.request_id='subscription-reconcile' and e.provider_reference=(x.s).provider_reference
       and e.received_at between (x.re).created_at and (x.re).updated_at end
 from graph x join subscription_events e on e.site_id=x.site_id
), callbacks as (
 select e.id,e.callback_type,e.payload,d.id delivery_id,
   e.payment_account_id=x.account_id and e.endpoint_url='http://callback-receiver:8080/callback'
   and e.secret_version_id is not null and e.payload->>'event_id'=e.id::text and e.payload->>'event_type'=e.callback_type
   and e.payload->>'subscription_id'=x.subscription_id::text and e.payload->>'site_id'=x.site_id::text
   and e.payload->>'customer_id'=x.customer_id::text and e.payload->>'product_id'=x.product_id::text and j.valid
   and case j.role
     when 'cash' then e.source_kind='PAYMENT' and e.payment_id=(x.p).id and e.callback_type='payment.succeeded'
       and e.payload->>'payment_id'=(x.p).id::text and e.payload->>'billing_record_id'=(x.b).id::text
     when 'period' then e.source_kind='SUBSCRIPTION' and e.subscription_id=x.subscription_id and e.subscription_event_id=e.id
       and e.callback_type='subscription.updated' and e.payload->>'state'='ACTIVE'
       and e.payload->>'access_allowed'='true' and e.payload->>'billing_record_id'=(x.b).id::text
     when 'created' then e.source_kind='SUBSCRIPTION' and e.subscription_id=x.subscription_id and e.subscription_event_id=e.id
       and e.callback_type='subscription.updated' and e.payload->>'access_allowed'='false' and e.payload->>'payment_id' is null
       and e.payload->>'billing_record_id' is null end valid,
   e.status='DELIVERED' and d.id is not null and d.attempt_number=1 and d.delivery_mode='AUTOMATIC'
      and d.status='SUCCEEDED' and d.http_status=204 delivered,
   e.status='PENDING' and d.id is null pending
 from graph x join outbox_events e on e.site_id=x.site_id
 left join journal j on j.id=e.id
 left join outbox_deliveries d on d.outbox_event_id=e.id
), expected_ingress as (
 select 1 ordering,'invoice.paid' type,paid_event event_id,paid_time occurred from graph where stage in ('paid','finalized')
 union all select 2,'invoice.finalized',finalized_event,finalized_time from graph where stage='finalized'
), ingress as (
 select i.*,ex.ordering,ex.type,
   ex.event_id is not null and i.provider_type='STRIPE' and i.provider_object_id=(x.g).billing_invoice_reference
   and i.event_family='SUBSCRIPTION' and i.event_type='SUBSCRIPTION_INVOICE_CHANGED'
   and i.occurred_at=to_timestamp(ex.occurred) and i.safe_payload='{}'::jsonb
   and i.payload_hash=encode(sha256(convert_to(i.event_type||chr(10)||i.provider_object_id||chr(10)||
       to_char(i.occurred_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'),'UTF8')),'hex')
   and i.error_category is null and (i.status='PENDING' and i.attempt_count=0 and i.lease_owner is null
       or i.status='PROCESSING' and i.attempt_count=1 and i.lease_owner is not null
       or i.status='PROCESSED' and i.attempt_count=1 and i.lease_owner is null) valid
 from graph x join provider_inbox i on i.payment_account_id=x.account_id
 left join expected_ingress ex on ex.event_id=i.provider_event_id
), checks as (
 select x.*,
   ((o).kind='CREATE' and (o).authority_scope='customer:'||customer_id and (o).fingerprint ~ '^v1[.]paid[.][a-f0-9]{64}$'
   and (o).key_digest=create_digest and (o).provider_reference=(s).provider_reference and (o).provider_operation_key=(d).operation_key
   and (d).operation_key='subscription:'||account_id||':'||operation_id||':CREATE' and (d).kind='SUBSCRIPTION_CREATE' and (d).status='ACKNOWLEDGED' and (d).provider_reference=(s).provider_reference
   and (d).external_account_id='acct_1UPiMpELswAd8tpR' and (d).credentials_ref='stripe-sandbox-default'
   and (d).first_dispatched_at is not null and (d).recovery_attempts=0 and not (d).manual_review_required and (d).fingerprint ~ '^[a-f0-9]{64}$'
   and (dc).kind='CUSTOMER_CREATE' and (dc).status='ACKNOWLEDGED' and (dc).provider_reference=(bc).provider_reference
   and (dc).first_dispatched_at is not null and (dc).recovery_attempts=0 and not (dc).manual_review_required
   and (dc).operation_key='billing_customer_'||account_id||'_'||site_id||'_'||customer_id
   and (dc).external_account_id=(d).external_account_id and (dc).credentials_ref=(d).credentials_ref
   and (g).billing_schema='payment-v1' and (g).billing_step_kind=(d).kind and (g).billing_operation_key=(d).operation_key
   and (g).billing_subscription_reference=(s).provider_reference and (g).billing_customer_reference=(bc).provider_reference
   and (g).recurring_consent and (g).consent_purpose='CREATE' and (g).client_session_id is null
   and (g).superseded_at is null and (g).confirmation_key_digest is null and (chain).root_digest=(g).token_digest and (chain).origin=origin
   and (a).provider_key='billing-confirm-v2:'||attempt_id and (a).predecessor is null and (a).successor is null
   and (a).retry_key is null and (a).request_id is null and (a).charge_reference is null and (a).decline_code is null
   and (s).billing_price_id=price_id and (s).billing_price_version=1 and (s).billing_amount_minor=2000
   and (s).billing_interval='MONTH' and (s).currency='EUR' and (s).billing_trial_days=0 and (s).billing_tax_mode='INCLUSIVE'
   and (s).trial_start is null and (s).trial_end is null and (s).pending_operation_id is null and (s).terminal_reason is null
   and (s).cancellation_effective_at is null and (select count(*)=1 from subscriptions z where z.site_id=x.site_id)
   and (select count(*)=1 from customers z where z.site_id=x.site_id)
   and (select count(*)=1 from subscription_action_capabilities z where z.site_id=x.site_id)
   and (select count(*)=1 from subscription_confirmation_attempts z where z.chain_id=(chain).id)
   and (select count(*)=1 from provider_operations z where z.payment_account_id=x.account_id and z.kind='SUBSCRIPTION_CREATE')
   and (select count(*)=1 from provider_operations z where z.payment_account_id=x.account_id and z.kind='CUSTOMER_CREATE')
   and not exists(select 1 from subscription_provider_objects z where z.site_id=x.site_id and
     (z.payment_account_id=x.account_id and z.subscription_id=x.subscription_id and z.provider_type='STRIPE'
       and case z.object_type when 'SUBSCRIPTION' then z.provider_reference=(s).provider_reference and z.billing_record_id is null and z.payment_id is null
         when 'INVOICE' then z.provider_reference=(g).billing_invoice_reference and z.billing_record_id=(b).id and z.payment_id is null
         when 'PAYMENT' then z.provider_reference=(g).billing_intent_reference and z.billing_record_id=(b).id and z.payment_id=(p).id
         else false end) is not true)
   and not exists(select 1 from provider_operations z where z.payment_account_id=x.account_id and
     not (z.id in ((d).id,(dc).id) or z.kind='PRODUCT_CREATE' and z.resource_id=x.product_id or z.kind='PRICE_CREATE' and z.resource_id=x.price_id))
   and (select count(*) from subscription_billing_records z where z.site_id=x.site_id)=case when (b).id is null then 0 else 1 end
   and (select count(*) from payments z where z.site_id=x.site_id)=case when (p).id is null then 0 else 1 end
   and (select count(*) from subscription_operations z where z.site_id=x.site_id)=case when settled then 2 else 1 end
   and (not settled or (re).kind='RECONCILE' and (re).status='SUCCEEDED' and (re).key_digest=reconcile_digest
     and (re).provider_operation_key='reconcile:'||reconcile_id and (re).provider_reference is null and (re).safe_failure_code is null
     and (re).fingerprint=encode(sha256(convert_to('RECONCILE:'||subscription_id||':'||account_id||':'||customer_id||':null','UTF8')),'hex')
     and (re).expected_revision=(chain).subscription_revision and (s).revision>(re).expected_revision
     and (select count(*)=1 from admin_users u where u.status='ACTIVE')
     and exists(select 1 from admin_users u where (re).authority_scope='admin:'||u.id and u.status='ACTIVE'))
   and (settled or reconcile_id is null)
   and (stage='beforeConfirm' or paid_event ~ '^evt_[A-Za-z0-9]+$' and finalized_event ~ '^evt_[A-Za-z0-9]+$'
     and paid_event<>finalized_event and paid_time>finalized_time and finalized_time>0)
   ) is true original,
   (case when stage='beforeConfirm' then (a).state='READY' and (a).confirm_key is null and (a).claimed_at is null
     and (a).fingerprint is null and (a).token_digest is null and (g).consumed_at is null
   else (a).state='OBSERVE' and (a).confirm_key=confirm_digest and (a).claimed_at is not null
     and (a).token_digest ~ '^[0-9a-f]{64}$' and (a).fingerprint ~ '^[0-9a-f]{64}$' and (a).claimed_at=(g).consumed_at end) is true confirmed,
   (case when settled then (s).billing_status='ACTIVE' and (s).entitled_price_id=price_id and (s).entitled_price_version=1
     and (s).current_period_start=(b).period_start and (s).current_period_end=(b).period_end and (s).paid_through=(b).period_end
     and statement_timestamp()>=(b).period_start and statement_timestamp()<(b).period_end and (s).debt_billing_record_id is null
     and (o).status='SUCCEEDED' and (b).settlement='CONFIRMED_CASH' and (b).kind='INITIAL' and (b).amount_due_minor=2000 and (b).currency='EUR'
     and (b).billed_price_id=price_id and (b).billed_price_version=1 and (b).billed_amount_minor=2000 and (b).billed_trial_days=0
     and (b).billed_interval='MONTH' and (b).billed_tax_mode='INCLUSIVE' and (p).source='SUBSCRIPTION' and (p).status='SUCCEEDED'
     and (p).amount_minor=2000 and (p).currency='EUR' and (p).refunded_amount_minor=0 and not exists(select 1 from refunds z where z.payment_id=(p).id)
     and (p).subscription_billing_kind='INITIAL' and (p).subscription_price_id=price_id and (p).subscription_price_version=1
     and (p).subscription_product_id=product_id and (p).subscription_invoice_reference=(b).provider_invoice_reference
     and (p).subscription_period_start=(b).period_start and (p).subscription_period_end=(b).period_end
     and exists(select 1 from subscription_provider_objects z where z.payment_account_id=x.account_id and z.site_id=x.site_id
       and z.subscription_id=x.subscription_id and z.provider_type='STRIPE' and z.object_type='PAYMENT' and z.provider_reference=(g).billing_intent_reference
       and z.payment_id=(p).id and z.billing_record_id=(b).id)
     and exists(select 1 from subscription_provider_objects z where z.payment_account_id=x.account_id and z.site_id=x.site_id
       and z.subscription_id=x.subscription_id and z.provider_type='STRIPE' and z.object_type='INVOICE' and z.provider_reference=(g).billing_invoice_reference
       and z.billing_record_id=(b).id)
   else stage in ('beforeConfirm','beforeReconcile') and (o).status in ('REQUIRES_ACTION','PROCESSING')
     and (chain).subscription_revision=(s).revision and (p).id is null and (s).billing_status in ('INCOMPLETE','PAST_DUE') and (s).entitled_price_id is null and (s).paid_through is null
     and (s).grace_deadline is null and ((s).debt_billing_record_id is null or (s).debt_billing_record_id=(b).id)
     and ((b).id is null or (b).kind='INITIAL' and (b).settlement='OPEN' and (b).amount_due_minor=2000 and (b).currency='EUR'
       and (b).billed_price_id=price_id and (b).billed_price_version=1) end) is true financial,
   (not exists(select 1 from journal where valid is not true)
     and (select count(*) from journal where role='created')<=1
     and (select count(*) from journal where role='cash')=case when settled then 1 else 0 end
     and (select count(*) from journal where role='period')=case when settled then 1 else 0 end) journal_valid,
   (not exists(select 1 from callbacks where valid is not true or not (delivered is true or pending is true))
     and (select count(*) from callbacks)=(select count(*) from journal)
     and (select count(distinct id) from callbacks)=(select count(*) from callbacks)) callbacks_valid,
   not exists(select 1 from ingress where valid is not true) and
     (select count(*) from ingress)=(select count(*) from expected_ingress) and
     (stage<>'finalized' or exists(select 1 from ingress first,ingress second where first.ordering=1 and second.ordering=2
       and first.status='PROCESSED' and first.received_at<second.received_at)) ingress_valid
 from graph x
), result as (
 select c.*,original and confirmed and financial and journal_valid and callbacks_valid and ingress_valid consistent,
   not exists(select 1 from ingress where status<>'PROCESSED') and
   (not settled or not exists(select 1 from callbacks where delivered is not true)) complete
 from checks c
)
select (bc).provider_reference customer_reference,(s).provider_reference subscription_reference,product_reference,price_reference,
 (g).billing_invoice_reference invoice_reference,(g).billing_allocation_reference allocation_reference,(g).billing_intent_reference intent_reference,
 extract(epoch from (b).period_start)::bigint period_start,extract(epoch from (b).period_end)::bigint period_end,
 (p).id payment_id,(b).id billing_record_id,(s).revision subscription_revision,operation_id create_operation_id,(re).id reconcile_operation_id,attempt_id,
 (select id from journal where role='cash' limit 1) payment_event_id,(select id from journal where role='period' limit 1) period_event_id,
 original,consistent and complete phase_proof,consistent and not complete pending,
 (select coalesce(jsonb_agg(jsonb_build_object('id',id,'type',callback_type,'payload',payload,'delivered',delivered is true,
   'deliveryId',delivery_id) order by id),'[]'::jsonb) from callbacks) callbacks,
 (select coalesce(jsonb_agg(jsonb_build_object('id',id,'eventId',provider_event_id,'type',type,'normalizedType',event_type,
   'occurredAt',extract(epoch from occurred_at)::bigint,'receivedAt',received_at,'status',status) order by received_at,id),'[]'::jsonb) from ingress) inbox
from result limit 2
