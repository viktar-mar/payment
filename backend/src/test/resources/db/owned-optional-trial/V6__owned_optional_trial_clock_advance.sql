-- Installed only by the owned optional-trial fixture.
create view stripe_billing_optional_trial_advance_contexts as
select published.*, encode(sha256(convert_to(to_jsonb(published)::text,'UTF8')),'hex') as advance_fingerprint
from (
 select a.*, s.revision, s.observation_sequence, o.version as create_version,
        b.id as original_bill_id, b.version as bill_version, e.id as trial_started_event_id
 from stripe_billing_optional_trial_acknowledgements a
 join subscriptions s on s.id=a.subscription_id and s.billing_status='TRIALING'
   and s.provider_reference=a.subscription_reference and s.billing_price_id=a.price_id and s.entitled_price_id=a.price_id
   and s.trial_start=to_timestamp(a.trial_start) and s.trial_end=to_timestamp(a.trial_end)
   and s.current_period_start=s.trial_start and s.current_period_end=s.trial_end
   and s.paid_through is null and s.debt_billing_record_id is null and s.pending_operation_id is null
   and s.cancellation_effective_at is null
 join subscription_operations o on o.id=a.create_operation and o.status='SUCCEEDED'
   and o.provider_reference=a.subscription_reference and o.safe_failure_code is null
 join subscription_trial_eligibility t on t.site_id=a.site_id and t.customer_id=a.customer_id
   and t.product_id=a.product_id and t.reserved_by_operation=o.id and t.consumed_at is not null
 join subscription_billing_records b on b.subscription_id=s.id and b.site_id=a.site_id
   and b.payment_account_id=a.account_id and b.product_id=a.product_id and b.billed_price_id=a.price_id
   and b.billed_price_key=a.price_key and b.billed_price_version=1 and b.billed_amount_minor=2000
   and b.billed_interval='MONTH' and b.billed_trial_days=7 and b.billed_trial_policy='OPTIONAL_DURING_TRIAL'
   and b.billed_tax_mode='INCLUSIVE' and b.previous_price_id is null and b.provider_invoice_reference=a.invoice_reference
   and b.kind='INITIAL' and b.settlement='ZERO_AMOUNT' and b.amount_due_minor=0 and b.currency='EUR'
   and b.period_start=s.trial_start and b.period_end=s.trial_end
 join subscription_events e on e.subscription_id=s.id and e.site_id=a.site_id and e.payment_account_id=a.account_id
   and e.event_type='TRIAL_STARTED' and e.revision=s.revision and e.payment_id is null
   and e.occurred_at=s.trial_start and e.provider_reference=a.subscription_reference
   and (e.billing_record_id is null or e.billing_record_id=b.id)
 where a.clock_deletes_after>extract(epoch from clock_timestamp())
   and not exists(select 1 from stripe_billing_optional_trial_pauses paused where paused.clock_intent=a.clock_intent)
   and not exists(select 1 from subscription_operations other where other.subscription_id=s.id and other.id<>o.id)
   and not exists(select 1 from subscription_billing_records other where other.site_id=a.site_id and other.id<>b.id)
   and not exists(select 1 from subscription_events other where other.subscription_id=s.id and other.id<>e.id)
   and not exists(select 1 from payments other where other.site_id=a.site_id)
) published;

create table stripe_billing_optional_trial_clock_advances (
 clock_intent uuid primary key references stripe_billing_owned_optional_trial_creates(clock_intent),
 claim_id uuid not null unique,
 fingerprint char(64) not null check(fingerprint ~ '^[0-9a-f]{64}$'),
 frozen_start bigint not null check(frozen_start>0),
 target bigint not null check(target=frozen_start+604800),
 claimed_at timestamptz not null,
 status varchar(12) not null check(status in ('DISPATCHING','ACKNOWLEDGED','UNKNOWN')),
 acknowledged_at timestamptz,
 post_status varchar(10),
 post_frozen_time bigint,
 post_advancing_target bigint,
 ready_at timestamptz,
 check ((status in ('DISPATCHING','UNKNOWN')
          and num_nonnulls(acknowledged_at,post_status,post_frozen_time,post_advancing_target,ready_at)=0)
     or (status='ACKNOWLEDGED' and acknowledged_at is not null
          and acknowledged_at>=claimed_at and acknowledged_at<claimed_at+interval '60 seconds'
          and post_frozen_time is not null and post_status is not null
          and (post_advancing_target is null or post_advancing_target=target)
          and ((post_status='ready' and post_frozen_time=target)
              or (post_status='advancing' and post_frozen_time between frozen_start and target))
          and (ready_at is null or ready_at>=acknowledged_at)))
);

create function protect_owned_optional_trial_advance() returns trigger language plpgsql as $$
begin
 if tg_op='DELETE' then
   raise exception 'Optional trial clock advance is immutable' using errcode='23514';
 end if;
 if tg_op='UPDATE' then
   if row(new.clock_intent,new.claim_id,new.fingerprint,new.frozen_start,new.target,new.claimed_at)
      is distinct from row(old.clock_intent,old.claim_id,old.fingerprint,old.frozen_start,old.target,old.claimed_at)
      or (old.status='DISPATCHING' and (new.status='DISPATCHING' or new.ready_at is not null))
      or (old.status='ACKNOWLEDGED' and (new.status<>'ACKNOWLEDGED' or old.ready_at is not null or new.ready_at is null
          or row(new.acknowledged_at,new.post_status,new.post_frozen_time,new.post_advancing_target)
             is distinct from row(old.acknowledged_at,old.post_status,old.post_frozen_time,old.post_advancing_target)))
      or old.status='UNKNOWN'
   then raise exception 'Optional trial clock advance cannot be replayed' using errcode='23514'; end if;
   if new.status='UNKNOWN' then return new; end if;
   if old.status='DISPATCHING' then
     new.acknowledged_at:=clock_timestamp();
   else
     new.ready_at:=clock_timestamp();
   end if;
 else
   if new.status<>'DISPATCHING' then
     raise exception 'Optional trial clock advance requires a first claim' using errcode='23514';
   end if;
   new.claimed_at:=clock_timestamp();
 end if;

 -- The clock owner lock serializes against stop and PAUSED publication.
 perform 1 from stripe_billing_clock_intents where id=new.clock_intent and stopped_at is null for update;
 if not found or not exists (
   select 1 from stripe_billing_optional_trial_advance_contexts c where c.clock_intent=new.clock_intent
    and c.advance_fingerprint=new.fingerprint and c.frozen_time=new.frozen_start and c.trial_end=new.target
 ) then raise exception 'Optional trial clock advance lacks current authority' using errcode='23514'; end if;
 if tg_op='UPDATE' and old.status='DISPATCHING' and clock_timestamp()>=old.claimed_at+interval '60 seconds' then
   raise exception 'Optional trial clock advance claim has expired' using errcode='23514';
 end if;
 return new;
end $$;

create trigger owned_optional_trial_advance_guard before insert or update or delete
 on stripe_billing_optional_trial_clock_advances for each row execute function protect_owned_optional_trial_advance();

-- An existing owned advance must prove exact READY before pause. The independent
-- observation path reconstructs its provider evidence without an advance claim.
create function require_ready_owned_optional_trial_advance_for_pause() returns trigger language plpgsql as $$
begin
 perform 1 from stripe_billing_clock_intents where id=new.clock_intent for share;
 if exists(select 1 from stripe_billing_optional_trial_clock_advances r where r.clock_intent=new.clock_intent)
   and not exists (
     select 1 from stripe_billing_optional_trial_clock_advances r
     join stripe_billing_optional_trial_advance_contexts c on c.clock_intent=r.clock_intent
       and c.advance_fingerprint=r.fingerprint and c.trial_end=r.target
     where r.clock_intent=new.clock_intent and r.status='ACKNOWLEDGED' and r.ready_at is not null
       and r.target=new.frozen_time and c.subscription_id=new.subscription_id
       and c.create_operation=new.create_operation and c.original_bill_id=new.original_bill_id
       and c.trial_started_event_id=new.trial_started_event_id
   )
 then raise exception 'Optional trial pause lacks owned clock readiness' using errcode='23514'; end if;
 return new;
end $$;

create trigger owned_optional_trial_pause_advance_guard before insert
 on stripe_billing_optional_trial_pauses for each row execute function require_ready_owned_optional_trial_advance_for_pause();
