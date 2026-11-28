-- One read-only snapshot on the fresh owned database. Runtime descriptors provide locators,
-- never substitute for committed receipts, actual journal rows or independent delivery evidence.
with scope as (
 select $1::uuid account_id,$2::uuid site_id,$3::uuid customer_id,$4::uuid subscription_id,
        $5::uuid operation_id,$6::uuid attempt_id,$7::uuid product_id,$8::uuid price_id,
        $9::text create_digest,$10::text confirm_digest,$11::text origin,$12::uuid run_id,
        $13::text source_sha,$14::bigint frozen_time,$15::uuid admission_id
), graph as materialized (
 select x.*,c.run_id owner_run,a.source_sha stored_source,c.initial_frozen_time,
        i.customer_reference,i.product_reference,i.price_reference,c.external_account_id,c.credentials_ref,
        cr.creation_operation_id clock_operation,i.customer_creation_operation customer_operation,
        receipt.provider_operation create_provider_operation,receipt.period_start initial_start,
        receipt.period_end initial_end,product_op.id product_operation,price_op.id price_operation,
        owned_failed_renewal_authority(d.id) authority,
        owned_grace_successor_projected(res.id,clock_timestamp(),pg_current_xact_id()) published,
        (create_op.key_digest=x.create_digest and chain.root_digest=root.token_digest and chain.origin=x.origin
         and attempt.confirm_key=x.confirm_digest and attempt.state='OBSERVE' and attempt.predecessor is null
         and attempt.successor is null and attempt.retry_key is null
         and attempt.provider_key='billing-confirm-v2:'||x.attempt_id::text) original,
        (a.source_sha=x.source_sha and d.source_sha=x.source_sha and c.run_id=x.run_id
         and c.initial_frozen_time=x.frozen_time and c.api_version='2026-09-30.endive'
         and c.external_account_id='acct_1UPiMpELswAd8tpR' and c.credentials_ref='stripe-sandbox-default'
         and i.amount_minor=2000 and i.currency='EUR' and i.billing_interval='MONTH' and i.trial_days=0
         and i.tax_mode='INCLUSIVE' and policy.enabled and policy.grace_days=3 and a.stopped_at is null
         and receipt.period_end=b1.frozen_time and receipt.period_start<=x.frozen_time and x.frozen_time<receipt.period_end
         and res.billing_at=to_timestamp(b2.frozen_time) and res.after_ready_id=after_ready.id) owned,
        a.id renewal_admission_id,a.initial_bill initial_bill_id,a.initial_payment initial_payment_id,
        a.invoice_reference initial_invoice_id,a.allocation_reference initial_allocation_id,a.payment_intent_reference initial_payment_intent_id,
        boundary.id boundary_id,b1.id boundary_ready_id,b1.step_id boundary_advance_id,ack1.provider_operation boundary_operation_id,
        read1.invocation pending_graph_invocation_id,boundary.invoice_reference renewal_invoice_id,
        first_renewal_instant(boundary.period_start) boundary_time,first_renewal_instant(boundary.period_start) period_start,
        first_renewal_instant(boundary.period_end) period_end,first_renewal_instant(boundary.observed_at) pending_graph_accepted_at,
        d.id failed_admission_id,b2.id finalization_ready_id,b2.step_id finalization_advance_id,ack2.provider_operation finalization_operation_id,
        b2.frozen_time finalization_time,f.claim_id failure_claim_id,f.renewal_bill failed_bill_id,
        f.allocation_reference failed_allocation_id,f.payment_intent_reference failed_payment_intent_id,
        first_renewal_instant(f.grace_deadline) grace_deadline,first_renewal_instant(f.recorded_at) failed_recorded_at,
        prep.id grace_preparation_id,prep.target grace_target,last_ready.id grace_ready_id,last_ready.operation_id grace_operation_id,
        last_ready.invocation grace_invocation_id,last_ready.frozen_time grace_frozen_time,
        at_ready.id grace_at_ready_id,at_ready.operation_id grace_at_operation_id,at_ready.invocation grace_at_invocation_id,at_ready.frozen_time grace_at_frozen_time,
        after_ready.id grace_after_ready_id,after_ready.operation_id grace_after_operation_id,after_ready.invocation grace_after_invocation_id,after_ready.frozen_time grace_after_frozen_time,
        res.id method_reservation_id,res.setup_operation method_operation_id,done.claim_id method_claim_id,done.setup_step method_setup_step_id,
        done.method_step method_update_step_id,first_renewal_instant(done.completed_at) method_completed_at,done.setup_reference setup_intent_id,
        done.method_reference replacement_method_id,recovery.operation_id recovery_operation_id,f.renewal_bill recovery_bill_id,
        f.allocation_reference recovery_allocation_id,f.payment_intent_reference recovery_payment_intent_id,
        confirm_op.id recovery_confirm_operation_id,confirm_op.resource_id recovery_confirm_resource_id,
        confirm_op.version recovery_confirm_operation_version,confirm_op.status recovery_confirm_status,
        first_renewal_instant(ack.acknowledged_at) recovery_acknowledged_at,ack.payment_intent recovery_confirm_payment_intent_id,ack.charge recovery_charge_id,
        pub.claim_id recovery_publication_claim_id,res.admission_id recovery_publication_admission_id,
        pub.operation_id recovery_publication_operation_id,f.renewal_bill recovery_publication_bill_id,pub.reservation_id recovery_publication_reservation_id,
        pub.renewal_payment recovery_payment_id,first_renewal_instant(pub.published_at) recovery_published_at,
        pub.projected_revision recovery_revision,pub.observation_sequence recovery_observation_sequence
 from scope x
 join stripe_billing_first_renewals a on (a.id,a.payment_account_id,a.site_id,a.customer_id,a.subscription_id)=(x.admission_id,x.account_id,x.site_id,x.customer_id,x.subscription_id)
 join stripe_billing_owned_subscriptions i on (i.id,i.create_operation,i.product_id,i.price_id,i.price_version)=(a.owned_intent,x.operation_id,x.product_id,x.price_id,1)
 join stripe_billing_clock_intents c on c.id=i.clock_intent
 join stripe_billing_clock_ready cr on cr.intent_id=c.id
 join stripe_billing_owned_subscription_receipts receipt on receipt.intent_id=i.id
 join subscription_operations create_op on create_op.id=i.create_operation
 join site_subscription_policies policy on policy.site_id=x.site_id and policy.version=i.policy_version
 join provider_operations product_op on (product_op.payment_account_id,product_op.kind,product_op.resource_id,product_op.provider_reference)=(x.account_id,'PRODUCT_CREATE',x.product_id,i.product_reference)
  and product_op.operation_key='catalog_product_'||x.account_id::text||'_'||x.product_id::text
 join provider_operations price_op on (price_op.payment_account_id,price_op.kind,price_op.resource_id,price_op.provider_reference)=(x.account_id,'PRICE_CREATE',x.price_id,i.price_reference)
  and price_op.operation_key='catalog_price_'||x.account_id::text||'_'||x.price_id::text||'_v1'
 join subscription_action_capabilities root on (root.id,root.operation_id,root.billing_owned_intent)=(a.original_root,x.operation_id,i.id)
 join subscription_confirmation_chains chain on (chain.id,chain.operation_id)=(root.id,x.operation_id)
 join subscription_confirmation_attempts attempt on attempt.id=x.attempt_id and attempt.id=a.original_attempt and attempt.chain_id=chain.id
 join stripe_billing_failed_renewals d on d.first_renewal_admission=a.id
 join stripe_billing_first_renewal_boundary boundary on boundary.id=d.boundary_id and boundary.admission_id=a.id
 join stripe_billing_first_renewal_ready b1 on b1.id=boundary.ready_id
 join stripe_billing_first_renewal_read_claims read1 on read1.ready_id=b1.id
 join stripe_billing_first_renewal_acks ack1 on ack1.step_id=b1.step_id
 join stripe_billing_first_renewal_ready b2 on b2.id=d.ready_id
 join stripe_billing_first_renewal_acks ack2 on ack2.step_id=b2.step_id
 join stripe_billing_failed_renewal_failures f on f.admission_id=d.id and f.invoice_reference=boundary.invoice_reference
 join stripe_billing_failed_grace_preparations prep on prep.admission_id=d.id and prep.renewal_bill=f.renewal_bill
 join stripe_billing_failed_grace_ready last_ready on last_ready.preparation_id=prep.id
 join stripe_billing_failed_grace_boundary_ready at_ready on at_ready.preparation_id=prep.id and at_ready.phase=2
 join stripe_billing_failed_grace_boundary_ready after_ready on after_ready.preparation_id=prep.id and after_ready.phase=3
 join stripe_billing_failed_method_reservations res on res.admission_id=d.id and res.after_preparation_id=prep.id
 join stripe_billing_failed_method_completions done on done.reservation_id=res.id
 join stripe_billing_failed_method_recoveries recovery on recovery.reservation_id=res.id
 join stripe_billing_failed_recovery_confirm_acks ack on ack.reservation_id=res.id and ack.payment_intent=f.payment_intent_reference
  and ack.method_reference=done.method_reference and ack.customer_reference=i.customer_reference and ack.amount=2000 and ack.received=2000 and ack.currency='EUR'
 join provider_operations confirm_op on confirm_op.id=ack.provider_operation and confirm_op.resource_id=recovery.operation_id
 join stripe_billing_failed_method_publications pub on pub.reservation_id=res.id and pub.operation_id=recovery.operation_id
), journal as (
 select e.id,e.callback_type,e.payload,e.status,e.endpoint_url,e.attempt_count,
        case when e.source_kind='PAYMENT' then pe.event_type else se.event_type end kind,
        case when e.source_kind='PAYMENT' then
         pe.id=e.id and e.payment_id in (g.initial_payment_id,g.recovery_payment_id) and pe.event_type='PAYMENT_SUCCEEDED'
         and pe.amount_minor=2000 and pe.currency='EUR' and pe.sequence_number=1
         and (e.payload->>'occurred_at')::timestamptz=pe.occurred_at
         and (e.payload->>'period_start')::timestamptz=case when e.payment_id=g.initial_payment_id then to_timestamp(g.initial_start) else g.period_start::timestamptz end
         and (e.payload->>'period_end')::timestamptz=case when e.payment_id=g.initial_payment_id then g.period_start::timestamptz else g.period_end::timestamptz end
        else e.source_kind='SUBSCRIPTION' and e.subscription_id=g.subscription_id and se.id=e.id
         and se.source='RECONCILIATION' and se.payment_id is null and se.revision=(e.payload->>'revision')::bigint
         and (e.payload->>'occurred_at')::timestamptz=se.occurred_at and (
          se.billing_record_id=g.initial_bill_id and se.event_type='PERIOD_PAID' and se.request_id=g.run_id::text and se.occurred_at=to_timestamp(g.frozen_time)
          or se.billing_record_id=g.failed_bill_id and se.event_type in ('RENEWAL_FAILED','PERIOD_PAID') and se.occurred_at=to_timestamp(g.finalization_time))
        end journal_match
 from graph g join outbox_events e on e.site_id=g.site_id and e.payment_account_id=g.account_id
 left join payment_events pe on e.source_kind='PAYMENT' and pe.id=e.id and pe.payment_id=e.payment_id and pe.payment_account_id=g.account_id
 left join subscription_events se on e.source_kind='SUBSCRIPTION' and se.id=e.subscription_event_id and se.id=e.id and se.subscription_id=g.subscription_id and se.payment_account_id=g.account_id
)
select g.owner_run,g.stored_source source_sha,g.initial_frozen_time frozen_time,
 to_jsonb(g)-array['account_id','site_id','customer_id','subscription_id','operation_id','attempt_id','product_id','price_id',
  'create_digest','confirm_digest','origin','run_id','source_sha','frozen_time','admission_id','owner_run','stored_source','initial_frozen_time',
  'customer_reference','product_reference','price_reference','external_account_id','credentials_ref','clock_operation','customer_operation',
  'create_provider_operation','initial_start','initial_end','product_operation','price_operation','authority','published','original','owned'] lineage,
 jsonb_build_object(
  'original',g.original and g.authority,
  'owned',g.owned and (select count(*)=1 from stripe_billing_clock_intents where payment_account_id=g.account_id)
    and (select count(*)=1 from stripe_billing_owned_subscriptions where payment_account_id=g.account_id)
    and (select count(*)=1 from stripe_billing_first_renewals where payment_account_id=g.account_id)
    and (select count(*)=1 from subscriptions where site_id=g.site_id and customer_id=g.customer_id),
  'lineage',g.published and g.authority and g.initial_end=g.finalization_time-3600
    and g.grace_deadline::timestamptz=to_timestamp(g.initial_end+259200)
    and g.grace_target=g.initial_end+259199 and g.grace_frozen_time=g.grace_target
    and g.grace_at_frozen_time=g.grace_target+1 and g.grace_after_frozen_time=g.grace_target+2
    and to_timestamp(g.grace_after_frozen_time)<g.period_end::timestamptz
    and (select count(*)=1 from stripe_billing_first_renewal_read_claims where admission_id=g.admission_id)
    and not exists(select 1 from stripe_billing_first_renewal_publications where admission_id=g.admission_id)
    and not exists(select 1 from stripe_billing_failed_renewal_stops where admission_id=g.failed_admission_id),
  'operations',(select count(*)=13 and count(*) filter(where
     p.id=any(array[g.product_operation,g.price_operation,g.clock_operation,g.customer_operation,g.create_provider_operation,
      g.boundary_operation_id,g.finalization_operation_id,g.grace_operation_id,g.grace_at_operation_id,g.grace_after_operation_id,
      g.method_setup_step_id,g.method_update_step_id,g.recovery_confirm_operation_id])
     and p.status='ACKNOWLEDGED' and p.first_dispatched_at is not null and p.recovery_attempts=0 and not p.manual_review_required
     and p.safe_error_code is null and p.lease_owner is null and p.lease_expires_at is null and p.fingerprint ~ '^[a-f0-9]{64}$'
     and p.external_account_id=g.external_account_id and p.credentials_ref=g.credentials_ref)=13 from provider_operations p where p.payment_account_id=g.account_id)
    and (select count(*)=3 and count(*) filter(where id in (g.operation_id,g.method_operation_id,g.recovery_operation_id) and status='SUCCEEDED')=3
         from subscription_operations where subscription_id=g.subscription_id),
  'cash',(select count(*)=2 and sum(amount_minor)=4000 and count(*) filter(where
     id in (g.initial_payment_id,g.recovery_payment_id) and source='SUBSCRIPTION' and status='SUCCEEDED'
     and currency='EUR' and amount_minor=2000 and refunded_amount_minor=0 and tax_mode='INCLUSIVE' and tax_amount_minor is null)=2
     from payments where payment_account_id=g.account_id)
    and (select count(*)=2 and count(*) filter(where id in (g.initial_bill_id,g.failed_bill_id) and settlement='CONFIRMED_CASH'
         and amount_due_minor=2000 and currency='EUR')=2 from subscription_billing_records where payment_account_id=g.account_id),
  'journal',(select count(*)=2 from payment_events where payment_account_id=g.account_id)
    and (select count(*)=3 from subscription_events where payment_account_id=g.account_id)
    and (select count(*)=5 and count(*) filter(where journal_match)=5 from journal)
 ) proof,
 (select jsonb_agg(jsonb_build_object('id',j.id,'type',j.callback_type,'kind',j.kind,'payload',j.payload,
   'delivered',case
     when j.endpoint_url<>'http://callback-receiver:8080/callback' then null
     when exists(select 1 from outbox_retry_requests r where r.outbox_event_id=j.id) then null
     when j.status='PENDING' and j.attempt_count in (0,1)
      and not exists(select 1 from outbox_deliveries d where d.outbox_event_id=j.id) then false
     when j.status='DELIVERED' and j.attempt_count=1
      and (select count(*)=1 and count(*) filter(where d.delivery_mode='AUTOMATIC' and d.attempt_number=1
           and d.status='SUCCEEDED' and d.http_status=204)=1
           from outbox_deliveries d where d.outbox_event_id=j.id) then true
     else null end) order by j.id) from journal j) callbacks
from graph g limit 2
