-- Invoice-created PaymentIntents reject error_on_requires_action.
-- Bind new claims to the actual two-parameter request and preserve committed V1 lineage.
create function owned_recovery_confirm_fingerprint_v2(target uuid) returns text language sql stable as $$
 select owned_method_stripe_hash(d.payment_account_id::text,clock.external_account_id,clock.credentials_ref,'STRIPE','SANDBOX',
  'OWNED_RECOVERY_CONFIRM_V2',o.id::text,o.provider_operation_key,o.fingerprint,r.id::text,
  f.renewal_bill::text,f.bill_version::text,f.invoice_reference,f.allocation_reference,f.payment_intent_reference,
  f.projected_revision::text,f.observation_sequence::text,r.binding_id::text,r.binding_reference,
  c.setup_step::text,c.setup_step_version::text,c.method_step::text,c.method_step_version::text,c.method_reference,
  'off_session:true')
 from stripe_billing_failed_method_reservations r
 join stripe_billing_failed_method_recoveries x on x.reservation_id=r.id
 join stripe_billing_failed_method_completions c on c.reservation_id=r.id
 join stripe_billing_failed_renewals d on d.id=r.admission_id
 join stripe_billing_failed_renewal_failures f on f.admission_id=d.id
 join stripe_billing_first_renewals first on first.id=d.first_renewal_admission
 join stripe_billing_owned_subscriptions owned on owned.id=first.owned_intent
 join stripe_billing_clock_intents clock on clock.id=owned.clock_intent
 join subscription_operations o on o.id=x.operation_id where r.id=$1
$$;

create or replace function owned_recovery_confirm_owns(target uuid,step provider_operations) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r
 join stripe_billing_failed_method_recoveries x on x.reservation_id=r.id
 join stripe_billing_failed_renewals d on d.id=r.admission_id
 join stripe_billing_first_renewals first on first.id=d.first_renewal_admission
 join stripe_billing_owned_subscriptions owned on owned.id=first.owned_intent
 join stripe_billing_clock_intents clock on clock.id=owned.clock_intent
 join subscription_operations o on o.id=x.operation_id
 where r.id=$1 and owned_recovery_confirm_identity(r.id)
 and row(($2).payment_account_id,($2).external_account_id,($2).credentials_ref,($2).kind,($2).resource_id,($2).operation_key)
  =row(d.payment_account_id,clock.external_account_id,clock.credentials_ref,'OWNED_RECOVERY_INTENT_CONFIRM'::varchar,o.id,o.provider_operation_key||':CONFIRM')
 and (($2).fingerprint=owned_recovery_confirm_fingerprint_v2(r.id)
   or (($2).fingerprint=owned_recovery_confirm_fingerprint(r.id)
    and exists(select 1 from stripe_billing_failed_recovery_confirm_claims q
     where q.provider_operation=($2).id and q.reservation_id=r.id
       and q.claimed_at=($2).first_dispatched_at and q.claimed_at+interval '23 hours'=($2).retry_before))))
$$;
