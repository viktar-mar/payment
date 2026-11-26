-- Installed only for the owned optional-trial fixtures.
create view stripe_billing_optional_trial_setup_contexts as
select a.*, s.revision
from stripe_billing_optional_trial_pauses p
-- Keep the original graph check scoped to this pause, separate from the setup joins.
join lateral (
 select original.* from stripe_billing_optional_trial_acknowledgements original
 where original.clock_intent=p.clock_intent and original.subscription_id=p.subscription_id
   and original.create_operation=p.create_operation and original.trial_end=p.frozen_time
 offset 0
) a on true
join subscriptions s on s.id=p.subscription_id
join subscription_operations created on created.id=p.create_operation and created.status='SUCCEEDED'
 and created.provider_reference=a.subscription_reference
join subscription_billing_records b on b.id=p.original_bill_id and b.site_id=a.site_id
 and b.payment_account_id=a.account_id and b.subscription_id=s.id and b.provider_invoice_reference=a.invoice_reference
 and b.kind='INITIAL' and b.settlement='ZERO_AMOUNT' and b.amount_due_minor=0 and b.currency='EUR'
 and b.period_start=to_timestamp(a.trial_start) and b.period_end=to_timestamp(a.trial_end)
join subscription_events started on started.id=p.trial_started_event_id and started.subscription_id=s.id
 and started.site_id=a.site_id and started.payment_account_id=a.account_id and started.event_type='TRIAL_STARTED'
where s.billing_status='PAUSED' and s.provider_reference=a.subscription_reference
 and s.paid_through is null and s.debt_billing_record_id is null and s.pending_operation_id is null
 and s.cancellation_effective_at is null and s.trial_start=to_timestamp(a.trial_start) and s.trial_end=to_timestamp(a.trial_end)
 and exists(select 1 from subscription_events paused where paused.subscription_id=s.id and paused.site_id=a.site_id
   and paused.payment_account_id=a.account_id and paused.event_type='PAUSED' and paused.occurred_at=to_timestamp(a.trial_end)
   and paused.sequence_number=started.sequence_number+1 and paused.revision=s.revision
   and paused.billing_record_id is null and paused.payment_id is null)
 and s.current_period_start=to_timestamp(a.trial_start) and s.current_period_end=to_timestamp(a.trial_end)
 and not exists(select 1 from subscription_billing_records other where other.subscription_id=s.id and other.id<>b.id)
 and not exists(select 1 from payments other where other.site_id=a.site_id);

create table stripe_billing_optional_trial_setup_steps (
 clock_intent uuid not null references stripe_billing_optional_trial_pauses(clock_intent),
 step_kind varchar(32) not null check(step_kind in ('SETUP_INTENT_CREATE','SUBSCRIPTION_UPDATE')),
 setup_operation uuid not null references subscription_operations(id),
 step_id uuid not null unique references provider_operations(id),
 claim_id uuid not null unique,
 method_reference varchar(255),
 check((step_kind='SETUP_INTENT_CREATE' and method_reference is null)
    or (step_kind='SUBSCRIPTION_UPDATE' and method_reference is not null and method_reference ~ '^pm_[A-Za-z0-9]{1,252}$')),
 primary key(clock_intent,step_kind)
);

create function require_owned_optional_trial_setup_step() returns trigger language plpgsql as $$
begin
 if tg_op <> 'INSERT' then
   raise exception 'Optional trial SETUP claim is immutable' using errcode='23514';
 end if;
 if not exists (
   with context as materialized (
     select * from stripe_billing_optional_trial_setup_contexts where clock_intent=new.clock_intent
   )
   select 1 from context a
   join subscription_operations o on o.id=new.setup_operation and o.kind='SETUP' and o.subscription_id=a.subscription_id
    and o.site_id=a.site_id and o.customer_id=a.customer_id and o.provider_reference=a.subscription_reference
    and o.expected_revision=a.revision and o.policy_version=a.policy_version and o.authority_scope='customer:'||a.customer_id
    and o.provider_operation_key='subscription:'||a.account_id||':'||o.id||':SETUP'
   join provider_operations step on step.id=new.step_id and step.resource_id=o.id
    and step.kind=new.step_kind and step.payment_account_id=a.account_id
    and step.external_account_id=a.external_account and step.credentials_ref=a.credentials_ref
    and step.status='DISPATCHING' and step.version=1 and step.manual_review_required
    and step.lease_owner=new.claim_id::text and step.lease_expires_at>clock_timestamp()
   where a.clock_intent=new.clock_intent
    and not exists(select 1 from subscription_operations other where other.subscription_id=a.subscription_id
                   and other.id not in (a.create_operation,o.id))
    and (
     (new.step_kind='SETUP_INTENT_CREATE' and step.operation_key=o.provider_operation_key||':SETUP'
       and o.status='RESERVED' and o.version=0
       and not exists(select 1 from stripe_subscription_setup_proofs proof where proof.subscription_id=a.subscription_id))
     or
     (new.step_kind='SUBSCRIPTION_UPDATE' and step.operation_key=o.provider_operation_key||':METHOD'
       and o.status='PROCESSING' and o.version=1
       and exists(select 1 from stripe_billing_optional_trial_setup_steps original
         join provider_operations ack on ack.id=original.step_id and ack.status='ACKNOWLEDGED' and ack.version=2
         join stripe_subscription_setup_proofs proof on proof.operation_id=o.id and proof.step_id=ack.id
            and proof.setup_reference=ack.provider_reference and proof.method_reference is null
         where original.clock_intent=a.clock_intent and original.setup_operation=o.id
           and original.step_kind='SETUP_INTENT_CREATE'))
    )
 ) then raise exception 'Optional trial SETUP lacks current authority' using errcode='23514'; end if;
 return new;
end $$;

create trigger owned_optional_trial_setup_step_guard before insert or update or delete
 on stripe_billing_optional_trial_setup_steps for each row execute function require_owned_optional_trial_setup_step();

create function protect_owned_optional_trial_setup_dispatch() returns trigger language plpgsql as $$
begin
 if not exists(select 1 from stripe_billing_optional_trial_setup_steps where step_id=old.id) then
   if tg_op='DELETE' then return old; end if;
   return new;
 end if;
 if tg_op='DELETE' then raise exception 'Optional trial SETUP dispatch is immutable' using errcode='23514'; end if;
 if old.status<>'DISPATCHING' or new.status not in ('ACKNOWLEDGED','UNKNOWN') or new.version<>old.version+1
    or new.manual_review_required is distinct from (new.status='UNKNOWN')
    or row(new.id,new.payment_account_id,new.external_account_id,new.credentials_ref,new.kind,new.resource_id,
           new.operation_key,new.fingerprint,new.first_dispatched_at,new.retry_before)
       is distinct from row(old.id,old.payment_account_id,old.external_account_id,old.credentials_ref,old.kind,old.resource_id,
           old.operation_key,old.fingerprint,old.first_dispatched_at,old.retry_before)
    or (new.status='ACKNOWLEDGED' and (old.lease_expires_at<=clock_timestamp() or not exists(
        select 1 from stripe_billing_optional_trial_setup_steps claim join stripe_billing_optional_trial_setup_contexts a
           on a.clock_intent=claim.clock_intent
        where claim.step_id=old.id and old.lease_owner=claim.claim_id::text
          and ((new.kind='SETUP_INTENT_CREATE' and new.provider_reference ~ '^seti_[A-Za-z0-9]{1,250}$')
            or (new.kind='SUBSCRIPTION_UPDATE' and new.provider_reference=a.subscription_reference)))))
 then raise exception 'Optional trial SETUP dispatch cannot be replayed' using errcode='23514'; end if;
 return new;
end $$;

create trigger owned_optional_trial_setup_dispatch_guard before update or delete
 on provider_operations for each row
 when (old.kind in ('SETUP_INTENT_CREATE','SUBSCRIPTION_UPDATE'))
 execute function protect_owned_optional_trial_setup_dispatch();

create function protect_owned_optional_trial_setup_proof() returns trigger language plpgsql as $$
declare original stripe_billing_optional_trial_setup_steps%rowtype;
begin
 select * into original from stripe_billing_optional_trial_setup_steps where
   setup_operation=case when tg_op='DELETE' then old.operation_id else new.operation_id end
   and step_kind='SETUP_INTENT_CREATE';
 if not found then
   if tg_op='DELETE' then return old; end if;
   return new;
 end if;
 if tg_op='DELETE' then raise exception 'Optional trial SETUP proof is immutable' using errcode='23514'; end if;
 if new.step_id<>original.step_id or (tg_op='INSERT' and new.method_reference is not null)
    or (tg_op='UPDATE' and (old.method_reference is not null or new.method_reference is null or not exists(
        select 1 from stripe_billing_optional_trial_setup_steps method
        join provider_operations ack on ack.id=method.step_id and ack.status='ACKNOWLEDGED' and ack.version=2
        where method.clock_intent=original.clock_intent and method.setup_operation=original.setup_operation
          and method.step_kind='SUBSCRIPTION_UPDATE' and method.method_reference=new.method_reference)))
 then raise exception 'Optional trial SETUP proof lacks exact method ACK' using errcode='23514'; end if;
 return new;
end $$;

create trigger owned_optional_trial_setup_proof_guard before insert or update or delete
 on stripe_subscription_setup_proofs for each row execute function protect_owned_optional_trial_setup_proof();

-- The standard proof and lifecycle operation must accompany an ACK in the same commit.
create function require_owned_optional_trial_setup_ack() returns trigger language plpgsql as $$
declare claim stripe_billing_optional_trial_setup_steps%rowtype;
begin
 select * into claim from stripe_billing_optional_trial_setup_steps where step_id=new.id;
 if not found then return new; end if;
 if not exists (
   select 1 from stripe_subscription_setup_proofs proof
   join subscription_operations o on o.id=proof.operation_id
   where o.id=claim.setup_operation and o.kind='SETUP' and o.status in ('PROCESSING','SUCCEEDED')
     and proof.payment_account_id=new.payment_account_id
     and ((claim.step_kind='SETUP_INTENT_CREATE' and proof.step_id=new.id and proof.setup_reference=new.provider_reference)
       or (claim.step_kind='SUBSCRIPTION_UPDATE' and proof.method_reference=claim.method_reference))
 ) then raise exception 'Optional trial SETUP ACK requires its standard proof' using errcode='23514'; end if;
 return new;
end $$;

create constraint trigger owned_optional_trial_setup_ack_guard after update on provider_operations
 deferrable initially deferred for each row
 when (new.kind in ('SETUP_INTENT_CREATE','SUBSCRIPTION_UPDATE') and new.status='ACKNOWLEDGED')
 execute function require_owned_optional_trial_setup_ack();
