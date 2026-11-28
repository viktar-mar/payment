-- Private confirmation of the same invoice-created PaymentIntent recorded by the owned RESUME.
create view stripe_billing_optional_trial_payment_confirmation_candidates as
select evidence.*, encode(sha256(convert_to(to_jsonb(evidence)::text,'UTF8')),'hex') evidence_fingerprint
from (
 select a.*, r.setup_operation, r.resume_operation, r.frozen_time paused_time, r.period_end resume_period_end,
        p.billing_record_id, r.invoice_reference resume_invoice, r.allocation_reference, r.intent_reference,
        r.step_id resume_step, r.fingerprint resume_fingerprint, o.provider_operation_key||':CONFIRM' confirm_key,
        o.version resume_version, o.fingerprint resume_request_fingerprint, s.revision subscription_revision, s.observation_sequence,
        so.setup_reference, so.method_reference, si.id setup_step, pm.id method_step
 from stripe_billing_optional_trial_pending_resumptions p
 join stripe_billing_owned_optional_trial_resumes r on r.clock_intent=p.clock_intent
   and r.resume_operation=p.resume_operation and r.status='ACKNOWLEDGED'
 join lateral (
   select admitted.* from stripe_billing_optional_trial_acknowledgements admitted
   where admitted.clock_intent=r.clock_intent and admitted.subscription_id=r.subscription_id
     and admitted.trial_end=r.frozen_time and admitted.invoice_reference<>r.invoice_reference offset 0
 ) a on true
 join subscriptions s on s.id=a.subscription_id and s.site_id=a.site_id and s.customer_id=a.customer_id
   and s.payment_account_id=a.account_id and s.provider_reference=a.subscription_reference and s.billing_status='PAUSED'
   and s.trial_start=to_timestamp(a.trial_start) and s.trial_end=to_timestamp(a.trial_end)
   and s.current_period_start=s.trial_start and s.current_period_end=s.trial_end and s.entitled_price_id=a.price_id
   and s.paid_through is null and s.debt_billing_record_id is null
   and s.pending_operation_id is null and s.cancellation_effective_at is null
 join stripe_billing_optional_trial_pauses pause on pause.clock_intent=a.clock_intent and pause.subscription_id=s.id
   and pause.create_operation=a.create_operation and pause.frozen_time=r.frozen_time
 join subscription_operations created on created.id=a.create_operation and created.status='SUCCEEDED'
   and created.provider_reference=a.subscription_reference and created.safe_failure_code is null
 join subscription_trial_eligibility eligibility on eligibility.site_id=a.site_id and eligibility.customer_id=a.customer_id
   and eligibility.product_id=a.product_id and eligibility.reserved_by_operation=created.id and eligibility.consumed_at is not null
 join subscription_billing_records trial on trial.id=pause.original_bill_id and trial.subscription_id=s.id
   and trial.site_id=a.site_id and trial.payment_account_id=a.account_id and trial.provider_invoice_reference=a.invoice_reference
   and trial.billed_price_id=a.price_id and trial.kind='INITIAL' and trial.settlement='ZERO_AMOUNT'
   and trial.currency='EUR' and trial.amount_due_minor=0 and trial.period_start=s.trial_start and trial.period_end=s.trial_end
 join subscription_operations setup on setup.id=r.setup_operation and setup.site_id=a.site_id and setup.customer_id=a.customer_id
   and setup.subscription_id=s.id and setup.kind='SETUP' and setup.status='SUCCEEDED' and setup.policy_version=a.policy_version
   and setup.provider_reference=a.subscription_reference and setup.authority_scope='customer:'||a.customer_id::text
   and setup.safe_failure_code is null
 join stripe_subscription_setup_proofs so on so.operation_id=setup.id and so.subscription_id=s.id
   and so.payment_account_id=a.account_id and so.setup_reference ~ '^seti_[A-Za-z0-9]{1,250}$'
   and so.method_reference ~ '^pm_[A-Za-z0-9]{1,252}$'
 join provider_operations si on si.id=so.step_id and si.kind='SETUP_INTENT_CREATE' and si.status='ACKNOWLEDGED'
   and si.payment_account_id=a.account_id and si.resource_id=setup.id and si.operation_key=setup.provider_operation_key||':SETUP'
   and si.provider_reference=so.setup_reference and si.lease_owner is null
   and si.version=2 and not si.manual_review_required and si.recovery_attempts=0 and si.safe_error_code is null
   and si.external_account_id=a.external_account and si.credentials_ref=a.credentials_ref
 join provider_operations pm on pm.kind='SUBSCRIPTION_UPDATE' and pm.status='ACKNOWLEDGED'
   and pm.payment_account_id=a.account_id and pm.resource_id=setup.id and pm.operation_key=setup.provider_operation_key||':METHOD'
   and pm.provider_reference=a.subscription_reference and pm.lease_owner is null
   and pm.version=2 and not pm.manual_review_required and pm.recovery_attempts=0 and pm.safe_error_code is null
   and pm.external_account_id=a.external_account and pm.credentials_ref=a.credentials_ref
 join stripe_billing_optional_trial_setup_steps intent_step on intent_step.clock_intent=a.clock_intent
   and intent_step.setup_operation=setup.id and intent_step.step_id=si.id and intent_step.step_kind='SETUP_INTENT_CREATE'
 join stripe_billing_optional_trial_setup_steps method_step on method_step.clock_intent=a.clock_intent
   and method_step.setup_operation=setup.id and method_step.step_id=pm.id and method_step.step_kind='SUBSCRIPTION_UPDATE'
   and method_step.method_reference=so.method_reference
 join subscription_operations o on o.id=r.resume_operation and o.subscription_id=s.id and o.site_id=a.site_id and o.customer_id=a.customer_id
   and o.kind='RESUME' and o.status='PROCESSING' and o.version=1 and o.expected_revision=s.revision
   and o.policy_version=a.policy_version and o.safe_failure_code is null and o.fingerprint ~ '^v1\.resume\.none\.[0-9a-f]{64}$'
   and o.provider_reference=a.subscription_reference and o.authority_scope='customer:'||a.customer_id::text
   and o.provider_operation_key='subscription:'||a.account_id::text||':'||o.id::text||':RESUME'
 join provider_operations step on step.id=r.step_id and step.kind='SUBSCRIPTION_UPDATE'
   and step.payment_account_id=a.account_id and step.external_account_id=a.external_account and step.credentials_ref=a.credentials_ref
   and step.resource_id=o.id and step.operation_key=o.provider_operation_key and step.fingerprint=r.fingerprint
   and step.status='ACKNOWLEDGED' and step.version=2 and step.provider_reference=a.subscription_reference
   and step.first_dispatched_at=r.claimed_at and step.lease_owner is null
   and step.recovery_attempts=0 and not step.manual_review_required and step.safe_error_code is null
 join stripe_subscription_resume_proofs proof on proof.operation_id=o.id and proof.step_id=r.step_id
   and proof.site_id=a.site_id and proof.customer_id=a.customer_id and proof.subscription_id=s.id
   and proof.payment_account_id=a.account_id and proof.subscription_reference=a.subscription_reference
   and proof.invoice_reference=r.invoice_reference and (proof.period_start,proof.period_end)=(r.frozen_time,r.period_end)
 join subscription_billing_records bill on bill.id=p.billing_record_id and bill.subscription_id=s.id
   and bill.site_id=a.site_id and bill.payment_account_id=a.account_id and bill.provider_invoice_reference=r.invoice_reference
   and bill.billed_price_id=a.price_id and bill.kind='RENEWAL' and bill.settlement='OPEN' and bill.amount_due_minor=2000
   and bill.currency='EUR' and bill.period_start=to_timestamp(r.frozen_time) and bill.period_end=to_timestamp(r.period_end)
 where a.clock_deletes_after>extract(epoch from clock_timestamp())
   and not exists (select 1 from subscription_operations extra where extra.subscription_id=s.id
     and extra.id not in (created.id,setup.id,o.id))
   and not exists (select 1 from subscription_billing_records extra where extra.site_id=a.site_id and extra.id not in (trial.id,bill.id))
   and not exists (select 1 from payments cash where cash.site_id=a.site_id)
) evidence;

create table stripe_billing_owned_optional_trial_payment_confirmations (
 clock_intent uuid primary key references stripe_billing_owned_optional_trial_resumes(clock_intent),
 resume_operation uuid not null unique references stripe_billing_owned_optional_trial_resumes(resume_operation),
 billing_record_id uuid not null unique references stripe_billing_optional_trial_pending_resumptions(billing_record_id),
 claim_id uuid not null unique,
 evidence_fingerprint text not null check(evidence_fingerprint ~ '^[0-9a-f]{64}$'),
 invoice_reference text not null,
 allocation_reference text not null,
 intent_reference text not null,
 claimed_at timestamptz not null,
 status text not null check(status in ('DISPATCHING','UNKNOWN','ACKNOWLEDGED')),
 charge_reference text check(charge_reference ~ '^ch_[A-Za-z0-9]{1,252}$'),
 check((status='ACKNOWLEDGED')=(charge_reference is not null))
);

create function owned_optional_trial_payment_confirmation_dispatchable(claim uuid) returns boolean language sql as $$
 select exists (
   select 1 from stripe_billing_owned_optional_trial_payment_confirmations f
   join stripe_billing_optional_trial_payment_confirmation_candidates c on c.clock_intent=f.clock_intent
     and c.resume_operation=f.resume_operation and c.billing_record_id=f.billing_record_id
     and c.evidence_fingerprint=f.evidence_fingerprint
   where f.claim_id=claim and f.status='DISPATCHING'
     and f.claimed_at<=clock_timestamp() and f.claimed_at+interval '60 seconds'>clock_timestamp()
 )
$$;

create function guard_owned_optional_trial_payment_confirmation() returns trigger language plpgsql as $$
begin
 if tg_op='INSERT' then
   if new.status='DISPATCHING' and new.claimed_at between statement_timestamp() and clock_timestamp()
     and exists (
     select 1 from stripe_billing_optional_trial_payment_confirmation_candidates c
     where c.clock_intent=new.clock_intent and c.resume_operation=new.resume_operation and c.billing_record_id=new.billing_record_id
       and c.evidence_fingerprint=new.evidence_fingerprint
       and (c.resume_invoice,c.allocation_reference,c.intent_reference)=(new.invoice_reference,new.allocation_reference,new.intent_reference)
   ) then return new; end if;
 elsif tg_op='UPDATE' then
   if old.status='DISPATCHING' and new.status in ('UNKNOWN','ACKNOWLEDGED')
     and (to_jsonb(new)-array['status','charge_reference'])=(to_jsonb(old)-array['status','charge_reference'])
     and (new.status='UNKNOWN' or owned_optional_trial_payment_confirmation_dispatchable(old.claim_id))
   then return new; end if;
 end if;
 raise exception 'Invalid owned optional trial payment confirmation transition' using errcode='23514';
end $$;

create trigger owned_optional_trial_payment_confirmation_guard before insert or update or delete
 on stripe_billing_owned_optional_trial_payment_confirmations for each row
 execute function guard_owned_optional_trial_payment_confirmation();

create function claim_owned_optional_trial_payment_confirmation(wanted_run uuid,wanted_clock uuid,wanted_subscription uuid,
 wanted_create uuid,wanted_setup uuid,wanted_resume uuid,fp text,claim uuid)
 returns boolean language plpgsql as $$
begin
 if claim is null or not lock_owned_optional_trial_resume_scope(wanted_clock,wanted_resume,null) then return false; end if;
 insert into stripe_billing_owned_optional_trial_payment_confirmations
   (clock_intent,resume_operation,billing_record_id,claim_id,evidence_fingerprint,invoice_reference,allocation_reference,intent_reference,claimed_at,status)
 select c.clock_intent,c.resume_operation,c.billing_record_id,claim,c.evidence_fingerprint,c.resume_invoice,
   c.allocation_reference,c.intent_reference,clock_timestamp(),'DISPATCHING'
 from stripe_billing_optional_trial_payment_confirmation_candidates c
 where c.run_id=wanted_run and c.clock_intent=wanted_clock and c.subscription_id=wanted_subscription
   and c.create_operation=wanted_create and c.setup_operation=wanted_setup and c.resume_operation=wanted_resume
   and c.evidence_fingerprint=fp
 on conflict do nothing;
 return found;
end $$;

create function finish_owned_optional_trial_payment_confirmation(claim uuid,charge text) returns boolean language plpgsql as $$
declare owned stripe_billing_owned_optional_trial_payment_confirmations%rowtype;
begin
 if charge is not null then
   select * into owned from stripe_billing_owned_optional_trial_payment_confirmations where claim_id=claim and status='DISPATCHING';
   if not found or not lock_owned_optional_trial_resume_scope(owned.clock_intent,owned.resume_operation,null)
     or not owned_optional_trial_payment_confirmation_dispatchable(claim) then return false; end if;
 end if;
 update stripe_billing_owned_optional_trial_payment_confirmations
 set status=case when charge is null then 'UNKNOWN' else 'ACKNOWLEDGED' end, charge_reference=charge
 where claim_id=claim and status='DISPATCHING';
 return found;
end $$;
