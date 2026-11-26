-- This schema is explicitly installed only in the owned optional-trial test fixture.
create table stripe_billing_optional_trial_pauses (
 clock_intent uuid primary key references stripe_billing_owned_optional_trial_creates(clock_intent),
 subscription_id uuid not null unique references subscriptions(id),
 create_operation uuid not null unique references stripe_subscription_trial_proofs(operation_id),
 frozen_time bigint not null,
 original_bill_id uuid not null unique references subscription_billing_records(id),
 trial_started_event_id uuid not null unique references subscription_events(id)
);

create function require_owned_optional_trial_pause() returns trigger language plpgsql as $$
begin
 if tg_op <> 'INSERT' then raise exception 'Optional trial pause observation is immutable' using errcode='23514'; end if;
 if not exists (
   with original as materialized (
     select * from stripe_billing_optional_trial_acknowledgements
     where clock_intent=new.clock_intent and subscription_id=new.subscription_id and create_operation=new.create_operation
   )
   select 1 from original a
   join subscriptions s on s.id=a.subscription_id
   join subscription_operations o on o.id=a.create_operation
   join subscription_trial_eligibility e on e.site_id=a.site_id and e.customer_id=a.customer_id
     and e.product_id=a.product_id and e.reserved_by_operation=o.id and e.consumed_at is not null
   join subscription_billing_records b on b.id=new.original_bill_id and b.site_id=a.site_id
     and b.payment_account_id=a.account_id and b.subscription_id=s.id and b.provider_invoice_reference=a.invoice_reference
     and b.kind='INITIAL' and b.settlement='ZERO_AMOUNT' and b.amount_due_minor=0 and b.currency='EUR'
     and b.billed_price_id=a.price_id and b.period_start=to_timestamp(a.trial_start) and b.period_end=to_timestamp(a.trial_end)
   join subscription_events started on started.id=new.trial_started_event_id and started.site_id=a.site_id
     and started.payment_account_id=a.account_id and started.subscription_id=s.id and started.event_type='TRIAL_STARTED'
     and started.payment_id is null and (started.billing_record_id is null or started.billing_record_id=b.id)
   where a.clock_intent=new.clock_intent and s.id=new.subscription_id and o.id=new.create_operation
     and a.trial_end=new.frozen_time and s.trial_end=to_timestamp(new.frozen_time)
     and o.status='SUCCEEDED' and s.billing_status='TRIALING' and s.provider_reference=a.subscription_reference
     and s.paid_through is null and s.debt_billing_record_id is null
     and not exists(select 1 from payments cash where cash.site_id=s.site_id)
     and (select count(*) from subscription_billing_records bill where bill.subscription_id=s.id)=1
     and not exists(select 1 from subscription_operations other where other.subscription_id=s.id and other.id<>o.id)
 ) then raise exception 'Optional trial pause observation lacks original authority' using errcode='23514'; end if;
 return new;
end; $$;

create trigger owned_optional_trial_pause_guard before insert or update or delete on stripe_billing_optional_trial_pauses
 for each row execute function require_owned_optional_trial_pause();

create table stripe_billing_optional_trial_pending_resumptions (
 clock_intent uuid primary key references stripe_billing_optional_trial_pauses(clock_intent),
 resume_operation uuid not null unique references stripe_subscription_resume_proofs(operation_id),
 billing_record_id uuid not null unique references subscription_billing_records(id)
);

create function require_owned_optional_trial_pending_resumption() returns trigger language plpgsql as $$
begin
 if tg_op <> 'INSERT' then raise exception 'Optional trial pending resumption is immutable' using errcode='23514'; end if;
 if not exists (
   with original as materialized (
     select * from stripe_billing_optional_trial_acknowledgements where clock_intent=new.clock_intent
   )
   select 1 from stripe_billing_optional_trial_pauses p
   join original a on a.clock_intent=p.clock_intent
   join subscriptions s on s.id=p.subscription_id and s.billing_status='PAUSED'
   join stripe_subscription_resume_proofs r on r.operation_id=new.resume_operation
     and r.subscription_id=s.id and r.payment_account_id=s.payment_account_id
     and r.subscription_reference=a.subscription_reference and r.period_start=p.frozen_time
   join subscription_operations o on o.id=r.operation_id and o.kind='RESUME' and o.status='PROCESSING'
   join provider_operations step on step.id=r.step_id and step.status='ACKNOWLEDGED'
     and step.resource_id=o.id and step.provider_reference=r.subscription_reference
   join subscription_billing_records b on b.id=new.billing_record_id and b.provider_invoice_reference=r.invoice_reference
     and b.subscription_id=s.id and b.payment_account_id=s.payment_account_id and b.site_id=s.site_id
     and b.kind='RENEWAL' and b.settlement='OPEN' and b.amount_due_minor=2000 and b.currency='EUR'
     and b.period_start=to_timestamp(r.period_start) and b.period_end=to_timestamp(r.period_end)
   where p.clock_intent=new.clock_intent
 ) then raise exception 'Optional trial pending resumption lacks original authority' using errcode='23514'; end if;
 return new;
end; $$;

create trigger owned_optional_trial_pending_resumption_guard before insert or update or delete
 on stripe_billing_optional_trial_pending_resumptions for each row execute function require_owned_optional_trial_pending_resumption();

-- Ownership evidence and the normal FactService PAUSED projection commit together.
create function require_owned_optional_trial_pause_publication() returns trigger language plpgsql as $$
begin
 if not exists (
   select 1 from subscriptions s
   join subscription_events started on started.id=new.trial_started_event_id and started.subscription_id=s.id
   join subscription_events paused on paused.subscription_id=s.id and paused.site_id=s.site_id
     and paused.payment_account_id=s.payment_account_id and paused.event_type='PAUSED'
     and paused.occurred_at=to_timestamp(new.frozen_time) and paused.sequence_number=started.sequence_number+1
     and paused.revision=s.revision and paused.billing_record_id is null and paused.payment_id is null
   where s.id=new.subscription_id and s.billing_status='PAUSED' and s.trial_end=to_timestamp(new.frozen_time)
     and s.debt_billing_record_id is null and s.paid_through is null
 ) then raise exception 'Optional trial pause observation lacks atomic publication' using errcode='23514'; end if;
 return new;
end; $$;

create constraint trigger owned_optional_trial_pause_publication after insert on stripe_billing_optional_trial_pauses
 deferrable initially deferred for each row execute function require_owned_optional_trial_pause_publication();
