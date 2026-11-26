-- One explicit RESUME for the original clock and PAUSED subscription. Normal rows back the
-- application operation and receipt. This schema is installed only in the isolated composition.
create view stripe_billing_optional_trial_resume_candidates as
select a.run_id, a.clock_intent, a.account_id, a.site_id, a.customer_id, a.subscription_id,
       a.create_operation, a.external_account, a.credentials_ref, a.subscription_reference,
       a.invoice_reference original_invoice, pause.frozen_time, setup.id setup_operation,
       so.setup_reference, so.method_reference, si.id setup_step, pm.id method_step, o.id resume_operation, o.provider_operation_key
from stripe_billing_optional_trial_pauses pause
-- Keep the original graph check scoped to this pause, separate from the resume joins.
join lateral (
 select original.* from stripe_billing_optional_trial_acknowledgements original
 where original.clock_intent=pause.clock_intent and original.subscription_id=pause.subscription_id
   and original.create_operation=pause.create_operation and original.trial_end=pause.frozen_time
 offset 0
) a on true
join subscriptions s on s.id=a.subscription_id and s.provider_reference=a.subscription_reference
join subscription_operations created on created.id=a.create_operation and created.status='SUCCEEDED'
join subscription_operations setup on setup.subscription_id=s.id and setup.site_id=a.site_id and setup.customer_id=a.customer_id
 and setup.kind='SETUP' and setup.status='SUCCEEDED' and setup.policy_version=a.policy_version
 and setup.provider_reference=s.provider_reference and setup.authority_scope='customer:'||a.customer_id::text
join stripe_subscription_setup_proofs so on so.operation_id=setup.id and so.subscription_id=s.id and so.payment_account_id=a.account_id
join provider_operations si on si.id=so.step_id and si.kind='SETUP_INTENT_CREATE' and si.status='ACKNOWLEDGED'
 and si.payment_account_id=a.account_id and si.resource_id=setup.id and si.operation_key=setup.provider_operation_key||':SETUP'
 and si.provider_reference=so.setup_reference and si.lease_owner is null
 and si.version=2 and not si.manual_review_required and si.recovery_attempts=0 and si.safe_error_code is null
 and si.external_account_id=a.external_account and si.credentials_ref=a.credentials_ref
join provider_operations pm on pm.kind='SUBSCRIPTION_UPDATE' and pm.status='ACKNOWLEDGED'
 and pm.payment_account_id=a.account_id and pm.resource_id=setup.id and pm.operation_key=setup.provider_operation_key||':METHOD'
 and pm.provider_reference=s.provider_reference and pm.lease_owner is null
 and pm.version=2 and not pm.manual_review_required and pm.recovery_attempts=0 and pm.safe_error_code is null
 and pm.external_account_id=a.external_account and pm.credentials_ref=a.credentials_ref
join subscription_operations o on o.subscription_id=s.id and o.site_id=a.site_id and o.customer_id=a.customer_id
 and o.kind='RESUME' and o.status='RESERVED' and o.version=0 and o.expected_revision=s.revision and o.policy_version=a.policy_version
 and o.provider_reference=s.provider_reference and o.authority_scope='customer:'||a.customer_id::text
 and o.provider_operation_key='subscription:'||a.account_id::text||':'||o.id::text||':RESUME'
 and o.fingerprint ~ '^v1\.resume\.none\.[0-9a-f]{64}$'
join subscription_billing_records bill on bill.id=pause.original_bill_id and bill.subscription_id=s.id
 and bill.payment_account_id=a.account_id and bill.provider_invoice_reference=a.invoice_reference
 and bill.kind='INITIAL' and bill.settlement='ZERO_AMOUNT' and bill.amount_due_minor=0
where s.billing_status='PAUSED' and s.paid_through is null and s.debt_billing_record_id is null
 and s.pending_operation_id is null and s.cancellation_effective_at is null
 and so.method_reference is not null
 and not exists(select 1 from subscription_operations other where other.subscription_id=s.id and other.id not in (created.id,setup.id,o.id))
 and not exists(select 1 from subscription_billing_records other where other.subscription_id=s.id and other.id<>bill.id)
 and not exists(select 1 from payments payment where payment.site_id=a.site_id);

create table stripe_billing_owned_optional_trial_resumes (
 clock_intent uuid primary key references stripe_billing_optional_trial_pauses(clock_intent),
 subscription_id uuid unique not null references subscriptions(id),
 setup_operation uuid unique not null references subscription_operations(id),
 resume_operation uuid unique not null references subscription_operations(id),
 step_id uuid unique not null references provider_operations(id),
 claim_id uuid unique not null,
 fingerprint text not null check(fingerprint ~ '^[0-9a-f]{64}$'),
 frozen_time bigint not null check(frozen_time>0),
 claimed_at timestamptz not null,
 status text not null check(status in ('DISPATCHING','UNKNOWN','ACKNOWLEDGED')),
 invoice_reference text check(invoice_reference ~ '^in_[A-Za-z0-9]{1,252}$'),
 allocation_reference text check(allocation_reference ~ '^inpay_[A-Za-z0-9]{1,249}$'),
 intent_reference text check(intent_reference ~ '^pi_[A-Za-z0-9]{1,252}$'),
 period_end bigint check(period_end>frozen_time),
 check((status='ACKNOWLEDGED' and num_nonnulls(invoice_reference,allocation_reference,intent_reference,period_end)=4)
    or (status<>'ACKNOWLEDGED' and num_nonnulls(invoice_reference,allocation_reference,intent_reference,period_end)=0))
);

create function lock_owned_optional_trial_resume_scope(clock_id uuid, operation_id uuid, existing_step uuid)
 returns boolean language plpgsql as $$
declare scope record;
begin
 select o.site_id, o.customer_id, o.subscription_id, s.payment_account_id into scope
 from subscription_operations o
 join subscriptions s on s.id=o.subscription_id and s.site_id=o.site_id and s.customer_id=o.customer_id
 where o.id=operation_id and o.kind='RESUME' for update of o;
 if not found then return false; end if;
 perform 1 from sites where id=scope.site_id for update;
 if not found then return false; end if;
 perform 1 from customers where site_id=scope.site_id and id=scope.customer_id for update;
 if not found then return false; end if;
 if existing_step is not null then
   perform 1 from provider_operations where id=existing_step for update;
   if not found then return false; end if;
 end if;
 perform 1 from payment_accounts where id=scope.payment_account_id for update;
 if not found then return false; end if;
 perform 1 from subscriptions where id=scope.subscription_id and payment_account_id=scope.payment_account_id for update;
 if not found then return false; end if;
 -- Canonical stop also takes Account before Clock. The original V2 graph is rechecked by the caller.
 perform 1 from stripe_billing_clock_intents where id=clock_id and site_id=scope.site_id
   and customer_id=scope.customer_id and payment_account_id=scope.payment_account_id and stopped_at is null for share;
 return found;
end $$;

create function claim_owned_optional_trial_resume(wanted_run uuid,wanted_clock uuid,wanted_subscription uuid,
 wanted_create uuid,wanted_setup uuid,wanted_resume uuid,wanted_frozen bigint,fp text,claim uuid)
 returns uuid language plpgsql as $$
declare c stripe_billing_optional_trial_resume_candidates%rowtype; step uuid:=gen_random_uuid(); lease_start timestamptz;
begin
 if claim is null or fp is null or fp !~ '^[0-9a-f]{64}$' then return null; end if;
 if not lock_owned_optional_trial_resume_scope(wanted_clock,wanted_resume,null) then return null; end if;
 -- Serialize this budget on the original pause row, without holding a database lock across I/O.
 perform 1 from stripe_billing_optional_trial_pauses where clock_intent=wanted_clock for update;
 if not found or exists(select 1 from stripe_billing_owned_optional_trial_resumes where clock_intent=wanted_clock)
   then return null; end if;
 select * into c from stripe_billing_optional_trial_resume_candidates
 where run_id=wanted_run and clock_intent=wanted_clock and subscription_id=wanted_subscription and create_operation=wanted_create
   and setup_operation=wanted_setup and resume_operation=wanted_resume and frozen_time=wanted_frozen;
 if not found then return null; end if;
 if (select count(*) from stripe_billing_optional_trial_setup_steps s where s.clock_intent=c.clock_intent
       and s.setup_operation=c.setup_operation and ((s.step_kind='SETUP_INTENT_CREATE' and s.step_id=c.setup_step)
        or (s.step_kind='SUBSCRIPTION_UPDATE' and s.step_id=c.method_step)))<>2 then return null; end if;
 -- There is no adoption path for a pre-existing or ambiguous operation.
 if exists(select 1 from provider_operations p where p.payment_account_id=c.account_id
   and (p.resource_id=c.resume_operation or p.operation_key=c.provider_operation_key))
   or exists(select 1 from stripe_subscription_resume_proofs p where p.operation_id=c.resume_operation)
   then return null; end if;
 lease_start:=clock_timestamp();
 insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,
   fingerprint,status,first_dispatched_at,retry_before,lease_owner,lease_expires_at,version,manual_review_required)
 values(step,c.account_id,c.external_account,c.credentials_ref,'SUBSCRIPTION_UPDATE',c.resume_operation,c.provider_operation_key,
   fp,'DISPATCHING',lease_start,lease_start+interval '23 hours',claim::text,lease_start+interval '60 seconds',1,true)
 on conflict do nothing;
 if not found then return null; end if;
 insert into stripe_billing_owned_optional_trial_resumes(clock_intent,subscription_id,setup_operation,resume_operation,step_id,claim_id,
   fingerprint,frozen_time,claimed_at,status)
 values(c.clock_intent,c.subscription_id,c.setup_operation,c.resume_operation,step,claim,fp,c.frozen_time,lease_start,'DISPATCHING');
 return step;
end $$;

create function owned_optional_trial_resume_dispatchable(claim uuid) returns boolean language sql as $$
 select exists(select 1 from stripe_billing_owned_optional_trial_resumes r
 join stripe_billing_optional_trial_resume_candidates c on c.clock_intent=r.clock_intent and c.subscription_id=r.subscription_id
  and c.setup_operation=r.setup_operation and c.resume_operation=r.resume_operation and c.frozen_time=r.frozen_time
 join provider_operations p on p.id=r.step_id and p.kind='SUBSCRIPTION_UPDATE' and p.payment_account_id=c.account_id
  and p.external_account_id=c.external_account and p.credentials_ref=c.credentials_ref and p.resource_id=c.resume_operation
  and p.operation_key=c.provider_operation_key and p.fingerprint=r.fingerprint and p.first_dispatched_at=r.claimed_at
 where r.claim_id=claim and r.status='DISPATCHING' and p.status='DISPATCHING' and p.version=1 and p.provider_reference is null
  and p.lease_owner=claim::text and p.lease_expires_at>clock_timestamp() and p.manual_review_required
  and p.recovery_attempts=0 and p.safe_error_code is null)
$$;

create function finish_owned_optional_trial_resume(claim uuid,invoice text,allocation text,intent text,end_at bigint)
 returns boolean language plpgsql as $$
declare r stripe_billing_owned_optional_trial_resumes%rowtype; c stripe_billing_optional_trial_resume_candidates%rowtype;
        ack boolean:=num_nonnulls(invoice,allocation,intent,end_at)=4;
begin
 if num_nonnulls(invoice,allocation,intent,end_at) not in (0,4) then return false; end if;
 select * into r from stripe_billing_owned_optional_trial_resumes where claim_id=claim;
 if not found or r.status<>'DISPATCHING' then return false; end if;
 if ack and not lock_owned_optional_trial_resume_scope(r.clock_intent,r.resume_operation,r.step_id) then return false; end if;
 perform 1 from provider_operations p where p.id=r.step_id and p.status='DISPATCHING'
  and p.lease_owner=claim::text and p.provider_reference is null and p.version=1 for update;
 if not found then return false; end if;
 select * into r from stripe_billing_owned_optional_trial_resumes where claim_id=claim for update;
 if not found or r.status<>'DISPATCHING' then return false; end if;
 if ack then
  select * into c from stripe_billing_optional_trial_resume_candidates where clock_intent=r.clock_intent
    and subscription_id=r.subscription_id and setup_operation=r.setup_operation and resume_operation=r.resume_operation
    and frozen_time=r.frozen_time;
  if not found or not owned_optional_trial_resume_dispatchable(claim) or invoice=c.original_invoice
     or end_at<>extract(epoch from (to_timestamp(r.frozen_time) at time zone 'UTC')+interval '1 month')::bigint
     then return false; end if;
 end if;
 update stripe_billing_owned_optional_trial_resumes set status=case when ack then 'ACKNOWLEDGED' else 'UNKNOWN' end,
   invoice_reference=invoice,allocation_reference=allocation,intent_reference=intent,period_end=end_at where claim_id=claim;
 update provider_operations set status=case when ack then 'ACKNOWLEDGED' else 'UNKNOWN' end,
   provider_reference=case when ack then c.subscription_reference else null end,
   lease_owner=null,lease_expires_at=null,version=2,manual_review_required=not ack where id=r.step_id;
 if ack then
  insert into stripe_subscription_resume_proofs(operation_id,site_id,customer_id,subscription_id,payment_account_id,
   step_id,subscription_reference,invoice_reference,period_start,period_end)
  values(c.resume_operation,c.site_id,c.customer_id,c.subscription_id,c.account_id,r.step_id,c.subscription_reference,
   invoice,r.frozen_time,end_at);
 end if;
 return true;
end $$;

create function protect_owned_optional_trial_resume() returns trigger language plpgsql as $$
begin
 if tg_op='INSERT' then
   if new.status<>'DISPATCHING' or not exists (
     select 1 from stripe_billing_optional_trial_resume_candidates c
     join provider_operations p on p.id=new.step_id and p.kind='SUBSCRIPTION_UPDATE' and p.resource_id=c.resume_operation
       and p.payment_account_id=c.account_id and p.operation_key=c.provider_operation_key and p.fingerprint=new.fingerprint
       and p.external_account_id=c.external_account and p.credentials_ref=c.credentials_ref
       and p.status='DISPATCHING' and p.version=1 and p.lease_owner=new.claim_id::text and p.manual_review_required
       and p.first_dispatched_at=new.claimed_at and p.lease_expires_at=p.first_dispatched_at+interval '60 seconds'
       and p.lease_expires_at>clock_timestamp()
     where c.clock_intent=new.clock_intent and c.subscription_id=new.subscription_id and c.setup_operation=new.setup_operation
       and c.resume_operation=new.resume_operation and c.frozen_time=new.frozen_time
   ) then raise exception 'Optional trial RESUME lacks first claim' using errcode='23514'; end if;
   return new;
 end if;
 if tg_op='DELETE' or old.status<>'DISPATCHING' or new.status='DISPATCHING'
   or (to_jsonb(new)-array['status','invoice_reference','allocation_reference','intent_reference','period_end'])
   is distinct from (to_jsonb(old)-array['status','invoice_reference','allocation_reference','intent_reference','period_end'])
   then raise exception 'Invalid optional trial RESUME transition' using errcode='23514'; end if;
 if new.status='ACKNOWLEDGED' and not owned_optional_trial_resume_dispatchable(old.claim_id) then
   raise exception 'Optional trial RESUME claim unavailable' using errcode='23514';
 end if;
 return new;
end $$;
create trigger owned_optional_trial_resume_immutable before insert or update or delete on stripe_billing_owned_optional_trial_resumes
 for each row execute function protect_owned_optional_trial_resume();

create function require_complete_owned_optional_trial_resume() returns trigger language plpgsql as $$
begin
 if not exists (
   select 1 from stripe_billing_owned_optional_trial_resumes r
   join provider_operations p on p.id=r.step_id and p.status=r.status and p.fingerprint=r.fingerprint
     and p.resource_id=r.resume_operation and p.manual_review_required=(r.status<>'ACKNOWLEDGED')
   left join stripe_subscription_resume_proofs proof on proof.operation_id=r.resume_operation
   where r.claim_id=new.claim_id
     and ((r.status<>'ACKNOWLEDGED' and proof.operation_id is null)
       or (r.status='ACKNOWLEDGED' and proof.subscription_id=r.subscription_id and proof.step_id=p.id
         and proof.payment_account_id=p.payment_account_id and proof.subscription_reference=p.provider_reference
         and proof.invoice_reference=r.invoice_reference and proof.period_start=r.frozen_time and proof.period_end=r.period_end))
 ) then raise exception 'Optional trial RESUME has an incomplete receipt' using errcode='23514'; end if;
 return null;
end $$;
create constraint trigger owned_optional_trial_resume_complete after insert or update on stripe_billing_owned_optional_trial_resumes
 deferrable initially deferred for each row execute function require_complete_owned_optional_trial_resume();

create function protect_owned_optional_trial_resume_step() returns trigger language plpgsql as $$
declare r stripe_billing_owned_optional_trial_resumes%rowtype; bound_reference text;
begin
 if old.kind<>'SUBSCRIPTION_UPDATE' then if tg_op='DELETE' then return old; end if; return new; end if;
 select * into r from stripe_billing_owned_optional_trial_resumes where step_id=old.id;
 if not found then if tg_op='DELETE' then return old; end if; return new; end if;
 if r.status='ACKNOWLEDGED' then select provider_reference into bound_reference from subscriptions where id=r.subscription_id; end if;
 if tg_op='DELETE' or old.status<>'DISPATCHING' or old.lease_owner<>r.claim_id::text or r.status='DISPATCHING'
    or new.status<>r.status or new.provider_reference is distinct from bound_reference
    or new.lease_owner is not null or new.lease_expires_at is not null or new.version<>old.version+1
    or new.manual_review_required is distinct from (new.status='UNKNOWN')
    or (to_jsonb(new)-array['status','provider_reference','lease_owner','lease_expires_at','version','manual_review_required'])
       is distinct from (to_jsonb(old)-array['status','provider_reference','lease_owner','lease_expires_at','version','manual_review_required'])
    then raise exception 'Optional trial RESUME step requires original claim' using errcode='23514'; end if;
 return new;
end $$;
create trigger owned_optional_trial_resume_step_immutable before update or delete on provider_operations
 for each row execute function protect_owned_optional_trial_resume_step();
