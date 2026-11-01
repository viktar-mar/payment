-- Final read-only proof. Initial-only SQL must succeed before renewal admission.
-- V33's committed predicate rechecks immutable receipt/confirmation lineage, exact
-- ACK/READY/read fences, unchanged terms and the FactService projection; the
-- publication JOIN below proves that the existing admission_id row is committed.
with owned as (
 select $1::uuid account_id, $2::uuid site_id, $3::uuid customer_id, $4::uuid subscription_id,
        $5::uuid operation_id, $6::uuid attempt_id, $7::uuid product_id, $8::uuid price_id,
        $9::text create_digest, $10::text confirm_digest, $11::text origin,
        $12::uuid run_id, $13::text source_sha, $14::bigint frozen_time, $15::uuid admission_id
), selected as (
 select x.*, a.owned_intent, a.original_root, a.initial_bill, a.initial_payment,
        a.allocation_reference, a.invoice_reference initial_invoice, a.payment_intent_reference initial_intent,
        c.id clock_intent, c.run_id owner_run, c.initial_frozen_time, cr.reference clock_reference,
        c.external_account_id, c.credentials_ref, c.create_key clock_key, c.create_fingerprint clock_fingerprint,
        cr.creation_operation_id clock_operation, cr.customer_fingerprint,
        i.customer_creation_operation customer_operation, i.operation_key subscription_key, i.fingerprint subscription_fingerprint,
        r.provider_operation subscription_operation,
        r.period_start receipt_period_start, r.period_end receipt_period_end,
        i.customer_reference, i.price_reference, i.product_reference, r.reference subscription_reference,
        publication.admission_id publication_id, publication.renewal_bill, publication.renewal_payment,
        boundary.period_start boundary_start, boundary.period_end boundary_end,
        extract(epoch from boundary.period_start)::bigint boundary_time,
        r.period_end+3600 finalization_time,
        owned_first_renewal_committed(a.id) committed,
        o.kind='CREATE' and o.status='SUCCEEDED' and o.key_digest=x.create_digest
          and chain.root_digest=g.token_digest and chain.origin=x.origin and chain.operation_id=x.operation_id
          and attempt.id=a.original_attempt and attempt.confirm_key=x.confirm_digest
          and attempt.state='OBSERVE' and attempt.predecessor is null and attempt.successor is null and attempt.retry_key is null
          and attempt.provider_key='billing-confirm-v2:'||x.attempt_id::text original,
        c.run_id=x.run_id and a.source_sha=x.source_sha and a.stopped_at is null and c.stopped_at is null
          and c.initial_frozen_time=x.frozen_time and cr.frozen_time=x.frozen_time
          and c.external_account_id='acct_1UPiMpELswAd8tpR' and c.credentials_ref='stripe-sandbox-default'
          and c.api_version='2026-09-30.endive' and i.amount_minor=2000 and i.currency='EUR'
          and i.billing_interval='MONTH' and i.trial_days=0 and i.tax_mode='INCLUSIVE'
          and r.period_start<=x.frozen_time and x.frozen_time<r.period_end owned
 from owned x
 join stripe_billing_first_renewals a on a.id=x.admission_id and a.payment_account_id=x.account_id
   and a.site_id=x.site_id and a.customer_id=x.customer_id and a.subscription_id=x.subscription_id
 join stripe_billing_owned_subscriptions i on i.id=a.owned_intent and i.payment_account_id=x.account_id
   and i.site_id=x.site_id and i.customer_id=x.customer_id and i.subscription_id=x.subscription_id
   and i.create_operation=x.operation_id and i.product_id=x.product_id and i.price_id=x.price_id and i.price_version=1
 join stripe_billing_clock_intents c on c.id=i.clock_intent
 join stripe_billing_clock_ready cr on cr.intent_id=c.id
 join stripe_billing_owned_subscription_receipts r on r.intent_id=i.id and r.create_operation=x.operation_id
 join subscription_operations o on o.id=x.operation_id and o.subscription_id=x.subscription_id
 join subscription_action_capabilities g on g.id=a.original_root and g.billing_owned_intent=i.id and g.operation_id=x.operation_id
 join subscription_confirmation_chains chain on chain.id=g.id and chain.operation_id=x.operation_id
 join subscription_confirmation_attempts attempt on attempt.id=x.attempt_id and attempt.chain_id=chain.id
 join stripe_billing_first_renewal_publications publication on publication.admission_id=a.id
 join stripe_billing_first_renewal_boundary boundary on boundary.id=publication.boundary_id and boundary.admission_id=a.id
 join owned_first_renewal_projected(a.id) projected on projected.renewal_bill=publication.renewal_bill
   and projected.renewal_payment=publication.renewal_payment and projected.projected_revision=publication.projected_revision
), expected_operations as (
 -- The fresh fixture publishes exactly one new product/price. Catalog operation IDs
 -- are selected by V21's unique account/kind/resource binding; absence stays NULL
 -- and fails completeness. Their real keys/references are bound to the same owned
 -- catalog, without inventing an alternative catalog fingerprint encoder.
 select x.admission_id,catalog.id provider_operation_id,'PRODUCT_CREATE'::text kind,x.product_id resource_id,
        'catalog_product_'||x.account_id::text||'_'||x.product_id::text operation_key,
        null::text fingerprint,x.product_reference provider_reference,x.account_id,x.external_account_id,x.credentials_ref
 from selected x left join provider_operations catalog on catalog.payment_account_id=x.account_id
   and catalog.kind='PRODUCT_CREATE' and catalog.resource_id=x.product_id
 union all
 select x.admission_id,catalog.id,'PRICE_CREATE',x.price_id,
        'catalog_price_'||x.account_id::text||'_'||x.price_id::text||'_v1',null::text,x.price_reference,
        x.account_id,x.external_account_id,x.credentials_ref
 from selected x left join provider_operations catalog on catalog.payment_account_id=x.account_id
   and catalog.kind='PRICE_CREATE' and catalog.resource_id=x.price_id
 union all
 select x.admission_id,x.clock_operation,'TEST_CLOCK_CREATE',x.clock_intent,x.clock_key,x.clock_fingerprint,
        x.clock_reference,x.account_id,x.external_account_id,x.credentials_ref from selected x
 union all
 select x.admission_id,x.customer_operation,'CUSTOMER_CREATE',x.customer_id,
        'billing_customer_'||x.account_id::text||'_'||x.site_id::text||'_'||x.customer_id::text,x.customer_fingerprint,
        x.customer_reference,x.account_id,x.external_account_id,x.credentials_ref from selected x
 union all
 select x.admission_id,x.subscription_operation,'SUBSCRIPTION_CREATE',x.operation_id,x.subscription_key,x.subscription_fingerprint,
        x.subscription_reference,x.account_id,x.external_account_id,x.credentials_ref from selected x
 union all
 select x.admission_id,ack.provider_operation,'TEST_CLOCK_ADVANCE',step.id,step.operation_key,step.fingerprint,
        x.clock_reference,x.account_id,x.external_account_id,x.credentials_ref
 from selected x join stripe_billing_first_renewal_steps step on step.admission_id=x.admission_id
 join stripe_billing_first_renewal_acks ack on ack.step_id=step.id and ack.admission_id=x.admission_id
), verified_operations as (
 select e.admission_id,p.id
 from expected_operations e join provider_operations p on p.id=e.provider_operation_id
   and p.payment_account_id=e.account_id and p.external_account_id=e.external_account_id and p.credentials_ref=e.credentials_ref
   and p.kind=e.kind and p.resource_id=e.resource_id and p.operation_key=e.operation_key and p.provider_reference=e.provider_reference
   and p.fingerprint ~ '^[a-f0-9]{64}$' and (e.fingerprint is null or p.fingerprint=e.fingerprint)
   and p.status='ACKNOWLEDGED' and p.first_dispatched_at is not null
   and p.recovery_attempts=0 and not p.manual_review_required and p.safe_error_code is null
   and p.lease_owner is null and p.lease_expires_at is null
), pairs as (
 select x.admission_id,b.kind,
 jsonb_build_object(
  'customer_reference',x.customer_reference,'subscription_reference',x.subscription_reference,
  'product_reference',x.product_reference,'price_reference',x.price_reference,
  'invoice_reference',b.provider_invoice_reference,'intent_reference',p.provider_payment_reference,
  'payment_id',p.id,'billing_record_id',b.id,
  'period_start',extract(epoch from b.period_start)::bigint,'period_end',extract(epoch from b.period_end)::bigint,
  'callbacks',(select coalesce(jsonb_agg(jsonb_build_object('id',e.id,'type',e.callback_type,'payload',e.payload,
    'delivered',e.status='DELIVERED' and e.endpoint_url='http://callback-receiver:8080/callback'
      and (select count(*)=1 from outbox_deliveries d where d.outbox_event_id=e.id)
      and exists(select 1 from outbox_deliveries d where d.outbox_event_id=e.id and d.status='SUCCEEDED' and d.http_status=204)
   ) order by e.id),'[]'::jsonb) from outbox_events e
   where e.site_id=x.site_id and e.payment_account_id=x.account_id and
    (e.source_kind='PAYMENT' and e.payment_id=p.id
      and exists(select 1 from payment_events pe where pe.id=e.id and pe.payment_id=p.id and pe.event_type='PAYMENT_SUCCEEDED')
     or e.source_kind='SUBSCRIPTION' and e.subscription_id=x.subscription_id
      and exists(select 1 from subscription_events se where se.id=e.id and se.billing_record_id=b.id
       and se.subscription_id=x.subscription_id and se.event_type='PERIOD_PAID')))
 ) || case when b.kind='INITIAL' then jsonb_build_object('allocation_reference',x.allocation_reference) else '{}'::jsonb end pair
 from selected x
 join subscription_billing_records b on b.id in (x.initial_bill,x.renewal_bill) and b.subscription_id=x.subscription_id
   and b.payment_account_id=x.account_id and b.site_id=x.site_id and b.product_id=x.product_id
 join payments p on p.id=case when b.id=x.initial_bill then x.initial_payment else x.renewal_payment end
   and p.billing_record_id=b.id and p.subscription_id=x.subscription_id and p.customer_id=x.customer_id
   and p.payment_account_id=x.account_id and p.site_id=x.site_id
)
select x.owner_run,x.source_sha,x.clock_reference,x.initial_frozen_time frozen_time,
 x.receipt_period_start,x.receipt_period_end,x.admission_id renewal_admission_id,x.publication_id,
 x.boundary_time,x.finalization_time,
 (select pair from pairs where admission_id=x.admission_id and kind='INITIAL') initial,
 (select pair from pairs where admission_id=x.admission_id and kind='RENEWAL') renewal,
 jsonb_build_object(
 'operations',(select count(*)=7 from expected_operations e where e.admission_id=x.admission_id)
   and (select count(*)=7 and count(distinct v.id)=7 from verified_operations v where v.admission_id=x.admission_id)
   and (select count(*)=7 from provider_operations p where p.payment_account_id=x.account_id),
 'original',x.original and x.committed,
 'owned',x.owned and x.committed
   and (select count(*)=1 from stripe_billing_clock_intents c where c.payment_account_id=x.account_id)
   and (select count(*)=1 from stripe_billing_owned_subscriptions i where i.payment_account_id=x.account_id)
   and (select count(*)=1 from stripe_billing_first_renewals a where a.payment_account_id=x.account_id)
   and (select count(*)=1 from subscriptions s where s.site_id=x.site_id and s.customer_id=x.customer_id),
 'cash',x.committed
   and (select count(*)=2 and sum(amount_minor)=4000 and bool_and(source='SUBSCRIPTION' and status='SUCCEEDED'
     and currency='EUR' and amount_minor=2000 and refunded_amount_minor=0 and subscription_id=x.subscription_id
     and subscription_billing_kind in ('INITIAL','RENEWAL')) from payments p where p.site_id=x.site_id and p.customer_id=x.customer_id)
   and (select count(*)=2 and sum(amount_due_minor)=4000 and bool_and(settlement='CONFIRMED_CASH'
     and currency='EUR' and amount_due_minor=2000 and kind in ('INITIAL','RENEWAL'))
     from subscription_billing_records b where b.site_id=x.site_id and b.subscription_id=x.subscription_id)
   and (select count(*)=2 from pairs where admission_id=x.admission_id),
 'renewal',x.committed and x.publication_id=x.admission_id and x.boundary_time=x.receipt_period_end
   and x.boundary_start=to_timestamp(x.receipt_period_end) and x.boundary_end>to_timestamp(x.finalization_time)
   and (select count(*)=2 from stripe_billing_first_renewal_steps s where s.admission_id=x.admission_id)
   and (select count(*)=2 from stripe_billing_first_renewal_acks a where a.admission_id=x.admission_id)
   and (select count(*)=2 from stripe_billing_first_renewal_ready r where r.admission_id=x.admission_id)
   and (select count(*)=2 from stripe_billing_first_renewal_read_claims c where c.admission_id=x.admission_id)
   and (select count(*)=1 from stripe_billing_first_renewal_boundary b where b.admission_id=x.admission_id)
   and (select count(*)=1 from stripe_billing_first_renewal_publications p where p.admission_id=x.admission_id)
   and (select count(*)=2 and bool_and((s.ordinal=1 and s.target=x.boundary_time) or (s.ordinal=2 and s.target=x.finalization_time))
     from owned_first_renewal_step_evidence(x.admission_id) s),
 'journal',x.committed
   and (select count(*)=2 and bool_and(e.event_type='PAYMENT_SUCCEEDED' and e.amount_minor=2000 and e.currency='EUR'
     and e.payment_id in (x.initial_payment,x.renewal_payment)) from payment_events e where e.site_id=x.site_id and e.payment_account_id=x.account_id)
   and (select count(*)=2 and bool_and(e.event_type='PERIOD_PAID' and e.payment_id is null and e.source='RECONCILIATION'
     and ((e.billing_record_id=x.initial_bill and e.request_id=x.run_id::text and e.occurred_at=to_timestamp(x.frozen_time))
       or (e.billing_record_id=x.renewal_bill and e.request_id='owned-renewal:'||x.admission_id::text and e.occurred_at=to_timestamp(x.finalization_time))))
     from subscription_events e where e.site_id=x.site_id and e.payment_account_id=x.account_id and e.subscription_id=x.subscription_id)
   and (select count(*)=4 from outbox_events e where e.site_id=x.site_id and e.payment_account_id=x.account_id)
 ) proof
from selected x
limit 2
