-- A durable description of G−1 only. No clock dispatch, receipt, time selector or financial authority.
create table stripe_billing_failed_grace_preparations (
 id uuid primary key,
 admission_id uuid not null unique references stripe_billing_failed_renewal_failures(admission_id),
 failure_claim_id uuid not null references stripe_billing_failed_renewal_reads(id),
 renewal_bill uuid not null references subscription_billing_records(id),
 predecessor_ready uuid not null references stripe_billing_first_renewal_ready(id),
 grace_deadline timestamptz not null,
 target bigint not null check(target>0),
 operation_key varchar(255) not null unique,
 fingerprint varchar(64) not null check(fingerprint ~ '^[a-f0-9]{64}$'),
 prepared_at timestamptz not null
);

-- Exact frozen 56-scalar vector. first_renewal_instant is UTC and microsecond canonical;
-- first_renewal_hash uses UTF-8 byte lengths, never JSON/record/session timestamp formatting.
create function owned_failed_grace_fingerprint(preparation uuid, admission uuid, target bigint, operation_key text, prepared_at timestamptz)
 returns text language sql stable set join_collapse_limit=1 as $$
 select first_renewal_hash('gp:owned-failed-grace-preparation:v1',$1::text,d.id::text,a.id::text,a.fingerprint,d.source_sha,first_renewal_instant(d.admitted_at),
  a.original_root::text,a.original_attempt::text,a.attempt_version::text,a.initial_bill::text,a.bill_version::text,a.initial_payment::text,a.payment_version::text,
  a.invoice_reference,a.allocation_reference,a.payment_intent_reference,a.subscription_revision::text,a.policy_version::text,
  c.id::text,c.run_id::text,c.payment_account_id::text,c.site_id::text,c.customer_id::text,c.external_account_id,c.credentials_ref,c.api_version,
  k.reference,k.creation_operation_id::text,k.frozen_time::text,i.id::text,i.subscription_id::text,i.create_operation::text,i.customer_binding::text,i.customer_reference,
  r.reference,r.item_reference,d.boundary_id::text,d.ready_id::text,ready.step_id::text,step.target::text,
  f.claim_id::text,f.renewal_bill::text,f.bill_version::text,f.invoice_reference,f.allocation_reference,f.payment_intent_reference,
  first_renewal_instant(f.period_start),first_renewal_instant(f.period_end),first_renewal_instant(f.grace_deadline),f.projected_revision::text,f.observation_sequence::text,
  first_renewal_instant(f.recorded_at),$3::text,$4,first_renewal_instant($5))
 from stripe_billing_failed_renewals d
 join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
 join stripe_billing_owned_subscriptions i on i.id=a.owned_intent
 join stripe_billing_clock_intents c on c.id=i.clock_intent
 join stripe_billing_clock_ready k on k.intent_id=c.id
 join stripe_billing_owned_subscription_receipts r on r.intent_id=i.id
 join stripe_billing_first_renewal_ready ready on ready.id=d.ready_id and ready.admission_id=a.id
 join stripe_billing_first_renewal_steps step on step.id=ready.step_id and step.admission_id=a.id
 join stripe_billing_failed_renewal_failures f on f.admission_id=d.id
 where d.id=$2
$$;

-- Current eligibility is deliberately separate from locking. A VOLATILE caller executes it
-- as a fresh statement after all NOWAIT locks at READ COMMITTED, including for raw INSERT.
create function owned_failed_grace_current(admission uuid) returns boolean
 language sql volatile set join_collapse_limit=1 as $$
 select exists(select 1 from stripe_billing_failed_renewals d
 join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
 join stripe_billing_owned_subscriptions i on i.id=a.owned_intent
 join stripe_billing_first_renewal_ready r on r.id=d.ready_id and r.admission_id=a.id
 join stripe_billing_first_renewal_steps step on step.id=r.step_id and step.admission_id=a.id
 join stripe_billing_first_renewal_boundary b on b.id=d.boundary_id and b.admission_id=a.id
 join stripe_billing_failed_renewal_failures f on f.admission_id=d.id
 join stripe_billing_failed_renewal_reads q on q.id=f.claim_id and q.admission_id=d.id and q.phase='FAILED'
 where d.id=admission and d.source_sha=a.source_sha
 and owned_failed_renewal_authority(d.id) and owned_failed_renewal_failure_current(d.id,null)
 and step.ordinal=2 and step.boundary_id=b.id and step.predecessor_ready=b.ready_id
 and step.target=r.frozen_time and d.admitted_at>=r.observed_at
 and f.recorded_at>=d.admitted_at and f.recorded_at>=r.observed_at and f.recorded_at<=clock_timestamp()
 and f.projected_revision=a.subscription_revision+1 and f.observation_sequence=q.observation_sequence+1
 and f.renewal_bill<>a.initial_bill and f.invoice_reference=b.invoice_reference and f.invoice_reference<>a.invoice_reference
 and f.allocation_reference<>a.allocation_reference and f.payment_intent_reference<>a.payment_intent_reference
 and row(f.period_start,f.period_end)=row(b.period_start,b.period_end)
 and f.grace_deadline=date_trunc('second',f.grace_deadline) and f.period_end=date_trunc('second',f.period_end)
 and extract(epoch from f.grace_deadline)-1>step.target::numeric
 and f.grace_deadline+interval '1 second'<f.period_end
 and extract(epoch from f.grace_deadline)+1-step.target::numeric<=case i.billing_interval when 'MONTH' then 56::numeric*86400 when 'YEAR' then 730::numeric*86400 else -1 end
 and not exists(select 1 from stripe_billing_failed_method_reservations x where x.admission_id=d.id)
 and not exists(select 1 from stripe_billing_failed_renewal_recoveries x where x.admission_id=d.id)
 and not exists(select 1 from stripe_billing_failed_renewal_publications x where x.admission_id=d.id)
 and not exists(select 1 from stripe_billing_failed_renewal_stops x where x.admission_id=d.id)
 -- V34 debt(NULL) permits PORTAL. Grace matches requireNoFinancial and refuses it.
 and not exists(select 1 from subscription_operations x where x.subscription_id=d.subscription_id
   and x.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN') and x.kind<>'RECONCILE'))
$$;

-- One canonical operation closure, including an unconsumed trial reservation. This
-- fresh selector is reused before locks and after parent FKs stabilize operation INSERTs.
create function owned_failed_grace_operation_ids(admission uuid) returns uuid[] language sql volatile as $$
 select coalesce(array_agg(ids.id order by
  ('x'||substr(replace(ids.id::text,'-',''),1,16))::bit(64)::bigint,
  ('x'||substr(replace(ids.id::text,'-',''),17,16))::bit(64)::bigint),'{}'::uuid[])
 from (
  select i.create_operation as id from stripe_billing_failed_renewals d
   join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
   join stripe_billing_owned_subscriptions i on i.id=a.owned_intent where d.id=$1
  union select o.id from subscription_operations o join stripe_billing_failed_renewals d on d.subscription_id=o.subscription_id
   where d.id=$1 and o.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN')
  union select t.reserved_by_operation from stripe_billing_failed_renewals d join subscriptions s on s.id=d.subscription_id
   join subscription_trial_eligibility t on t.site_id=d.site_id and t.customer_id=d.customer_id and t.product_id=s.product_id
   where d.id=$1 and t.consumed_at is null and t.reserved_by_operation is not null
 ) ids
$$;
create function require_owned_failed_grace_operation_membership(admission uuid, locked_ids uuid[]) returns void language plpgsql volatile as $$
begin
 if locked_ids is null or locked_ids is distinct from owned_failed_grace_operation_ids(admission) then
  raise exception 'Owned grace operation membership changed' using errcode='23514'; end if;
end $$;

-- Shared by service preflight and INSERT trigger. Do not enter existing blocking locks
-- first: speculative V35 SETUP holds immediate customer/subscription FK KEY SHARE locks.
create function lock_owned_failed_grace(admission uuid) returns void language plpgsql volatile as $$
declare d stripe_billing_failed_renewals%rowtype; a stripe_billing_first_renewals%rowtype; locked_ids uuid[];
begin
 if current_setting('transaction_isolation')<>'read committed' then
  raise exception 'Owned grace preparation requires READ COMMITTED' using errcode='23514'; end if;
 select * into strict d from stripe_billing_failed_renewals where id=admission;
 select * into strict a from stripe_billing_first_renewals where id=d.first_renewal_admission;
 locked_ids:=owned_failed_grace_operation_ids(admission);
 -- Match Java UUID.compareTo: signed most-significant then signed least-significant long.
 perform o.id from subscription_operations o where o.id=any(locked_ids)
 order by ('x'||substr(replace(o.id::text,'-',''),1,16))::bit(64)::bigint,('x'||substr(replace(o.id::text,'-',''),17,16))::bit(64)::bigint
 for update of o nowait;
 perform id from sites where id=d.site_id for update nowait;
 perform id from customers where id=d.customer_id and site_id=d.site_id for update nowait;
 perform id from payment_accounts where id=d.payment_account_id for update nowait;
 perform id from subscriptions where id=d.subscription_id for update nowait;
 -- Parent FOR UPDATE locks now conflict with operation INSERT FK KEY SHARE. A
 -- RECONCILE committed between the initial scan and these locks must never enter
 -- current()'s blocking rediscovery. Refuse any changed closure in a fresh statement.
 perform require_owned_failed_grace_operation_membership(admission,locked_ids);
 perform b.id from subscription_billing_records b where b.id=a.initial_bill
  or b.id=(select f.renewal_bill from stripe_billing_failed_renewal_failures f where f.admission_id=d.id)
 order by ('x'||substr(replace(b.id::text,'-',''),1,16))::bit(64)::bigint,('x'||substr(replace(b.id::text,'-',''),17,16))::bit(64)::bigint
 for update of b nowait;
 perform id from stripe_billing_first_renewals where id=a.id for update nowait;
 perform id from stripe_billing_failed_renewals where id=d.id for update nowait;
 perform admission_id from stripe_billing_failed_renewal_failures where admission_id=d.id for update nowait;
end $$;

create function require_owned_failed_grace_preparation() returns trigger language plpgsql volatile as $$
begin
 perform lock_owned_failed_grace(new.admission_id);
 -- This separate statement obtains fresh post-lock visibility, rather than the INSERT snapshot.
 if not owned_failed_grace_current(new.admission_id) then
  raise exception 'Owned grace preparation authority unavailable' using errcode='23514'; end if;
 if new.prepared_at>clock_timestamp() or not exists(select 1 from stripe_billing_failed_renewals d
  join stripe_billing_failed_renewal_failures f on f.admission_id=d.id
  join stripe_billing_first_renewal_ready r on r.id=d.ready_id
  where d.id=new.admission_id and new.failure_claim_id=f.claim_id and new.renewal_bill=f.renewal_bill
   and new.predecessor_ready=d.ready_id and new.grace_deadline=f.grace_deadline
   and new.target::numeric=extract(epoch from f.grace_deadline)-1
   and new.prepared_at>=f.recorded_at and new.prepared_at>=r.observed_at
   and new.operation_key='billing-failed-grace-v1:'||d.id::text||':1'
   and new.fingerprint=owned_failed_grace_fingerprint(new.id,new.admission_id,new.target,new.operation_key,new.prepared_at)) then
  raise exception 'Owned grace preparation requires exact original failure and G-1' using errcode='23514'; end if;
 return new;
end $$;
create trigger failed_grace_preparation_proof before insert on stripe_billing_failed_grace_preparations
 for each row execute function require_owned_failed_grace_preparation();
create trigger failed_grace_preparation_immutable before update or delete on stripe_billing_failed_grace_preparations
 for each row execute function protect_failed_renewal_evidence();
