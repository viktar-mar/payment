-- One original G-1 invocation; descriptive clock evidence only. Historical selectors are untouched.
alter table provider_operations drop constraint provider_operations_kind_check;
alter table provider_operations add constraint provider_operations_kind_check check(kind in (
 'PRODUCT_CREATE','PRICE_CREATE','PAYMENT_PREPARE','REFUND_CREATE','CUSTOMER_CREATE','TEST_CLOCK_CREATE','TEST_CLOCK_ADVANCE',
 'SUBSCRIPTION_CREATE','SUBSCRIPTION_UPDATE','SUBSCRIPTION_CANCEL','SETUP_INTENT_CREATE','INVOICE_PAY',
 'SCHEDULE_CREATE','SCHEDULE_UPDATE','SCHEDULE_RELEASE','PORTAL_CONFIGURATION_CREATE','PORTAL_SESSION_CREATE',
 'OWNED_RECOVERY_INTENT_CONFIRM','OWNED_GRACE_CLOCK_ADVANCE'));

alter table stripe_billing_failed_grace_preparations add unique(id,admission_id);
create table stripe_billing_failed_grace_claims (
 preparation_id uuid primary key references stripe_billing_failed_grace_preparations(id),
 operation_id uuid not null unique references provider_operations(id),
 invocation uuid not null unique, claimant varchar(128) not null check(length(trim(claimant))>0 and claimant !~ '[[:cntrl:]]'),
 entered_at timestamptz not null, deadline_at timestamptz not null check(deadline_at=entered_at+interval '30 seconds'),
 claimed_at timestamptz not null, deadline timestamptz not null check(deadline=claimed_at+interval '60 seconds'),
 recorded_transaction xid8 not null default pg_current_xact_id(),
 check(claimed_at>=entered_at and claimed_at<deadline_at),
 unique(preparation_id,operation_id,invocation), unique(preparation_id,operation_id,invocation,claimant,deadline)
);
create table stripe_billing_failed_grace_acks (
 preparation_id uuid primary key, operation_id uuid not null unique, invocation uuid not null unique,
 claimant varchar(128) not null, deadline timestamptz not null,
 reference varchar(255) not null check(reference ~ '^clock_[A-Za-z0-9]{1,249}$'),
 object_type varchar(64) not null check(object_type='test_helpers.test_clock'), livemode boolean not null check(not livemode),
 name varchar(255) not null, frozen_time bigint not null check(frozen_time>0), status varchar(32) not null check(status in ('ready','advancing')),
 provider_created bigint not null check(provider_created>0), provider_deletes_after bigint not null check(provider_deletes_after>0),
 advancing_target bigint check(advancing_target>0), observed_at timestamptz not null,
 acknowledged_at timestamptz not null check(acknowledged_at>=observed_at),
 recorded_transaction xid8 not null default pg_current_xact_id(),
 foreign key(preparation_id,operation_id,invocation,claimant,deadline)
  references stripe_billing_failed_grace_claims(preparation_id,operation_id,invocation,claimant,deadline),
 unique(preparation_id,operation_id,invocation)
);
create table stripe_billing_failed_grace_ready (
 id uuid primary key, preparation_id uuid not null unique, admission_id uuid not null unique,
 operation_id uuid not null unique, invocation uuid not null unique,
 reference varchar(255) not null, object_type varchar(64) not null, livemode boolean not null,
 name varchar(255) not null, frozen_time bigint not null, status varchar(32) not null,
 provider_created bigint not null, provider_deletes_after bigint not null, advancing_target bigint,
 observed_at timestamptz not null, recorded_transaction xid8 not null default pg_current_xact_id(),
 foreign key(preparation_id,admission_id) references stripe_billing_failed_grace_preparations(id,admission_id),
 foreign key(preparation_id,operation_id,invocation) references stripe_billing_failed_grace_acks(preparation_id,operation_id,invocation)
);
create table stripe_billing_failed_grace_cleanup (
 preparation_id uuid primary key, operation_id uuid not null unique, invocation uuid not null unique,
 stopped_at timestamptz not null, recorded_transaction xid8 not null default pg_current_xact_id(),
 foreign key(preparation_id,operation_id,invocation) references stripe_billing_failed_grace_claims(preparation_id,operation_id,invocation)
);
create trigger failed_grace_claim_immutable before update or delete on stripe_billing_failed_grace_claims for each row execute function protect_failed_renewal_evidence();
create trigger failed_grace_ack_immutable before update or delete on stripe_billing_failed_grace_acks for each row execute function protect_failed_renewal_evidence();
create trigger failed_grace_ready_immutable before update or delete on stripe_billing_failed_grace_ready for each row execute function protect_failed_renewal_evidence();
create trigger failed_grace_cleanup_immutable before update or delete on stripe_billing_failed_grace_cleanup for each row execute function protect_failed_renewal_evidence();

-- No timeout may extend a stricter inherited value. The Java budget is never reset by this projection.
create function owned_failed_grace_limits(requested bigint,cleanup boolean) returns void language plpgsql volatile as $$
declare ceiling bigint; inherited bigint;
begin
 if requested<=0 then raise exception 'Owned grace budget exhausted' using errcode='23514';end if;
 ceiling:=least(requested,case when cleanup then 2000 else 30000 end);
 select setting::bigint into inherited from pg_settings where name='statement_timeout';
 perform set_config('statement_timeout',least(ceiling,case when inherited=0 then ceiling else inherited end)::text,true);
 select setting::bigint into inherited from pg_settings where name='lock_timeout';
 perform set_config('lock_timeout',least(ceiling,case when inherited=0 then ceiling else inherited end)::text,true);
end $$;

-- Immutable lineage only; current owner/debt/status is deliberately absent for original cleanup.
create function owned_failed_grace_identity(preparation uuid) returns boolean language sql stable set join_collapse_limit=1 as $$
 select exists(select 1 from stripe_billing_failed_grace_preparations p
 join stripe_billing_failed_renewals d on d.id=p.admission_id
 join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
 join stripe_billing_failed_renewal_failures f on f.admission_id=d.id
 join stripe_billing_first_renewal_ready r on r.id=d.ready_id and r.admission_id=a.id
 join stripe_billing_first_renewal_steps s on s.id=r.step_id and s.admission_id=a.id
 where p.id=$1 and d.source_sha=a.source_sha and s.ordinal=2 and s.target=r.frozen_time
 and row(p.failure_claim_id,p.renewal_bill,p.predecessor_ready,p.grace_deadline)=row(f.claim_id,f.renewal_bill,d.ready_id,f.grace_deadline)
 and p.target::numeric=extract(epoch from f.grace_deadline)-1
 and p.operation_key='billing-failed-grace-v1:'||d.id::text||':1'
 and p.fingerprint=owned_failed_grace_fingerprint(p.id,d.id,p.target,p.operation_key,p.prepared_at))
$$;
create function owned_failed_grace_owns(preparation uuid,step provider_operations) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_grace_preparations p
 join stripe_billing_failed_renewals d on d.id=p.admission_id
 join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
 join stripe_billing_owned_subscriptions i on i.id=a.owned_intent
 join stripe_billing_clock_intents c on c.id=i.clock_intent
 where p.id=$1 and owned_failed_grace_identity(p.id)
 and row(($2).payment_account_id,($2).external_account_id,($2).credentials_ref,($2).kind,($2).resource_id,($2).operation_key,($2).fingerprint)
  =row(d.payment_account_id,c.external_account_id,c.credentials_ref,'OWNED_GRACE_CLOCK_ADVANCE'::varchar,p.id,p.operation_key,p.fingerprint))
$$;

-- Permanent branch choice starts only at firstclaim, never at provisional preparation.
create or replace function exclude_failed_method_branch() returns trigger language plpgsql volatile as $$
declare upstream uuid;
begin
 select first_renewal_admission into upstream from stripe_billing_failed_renewals where id=new.admission_id;
 perform lock_owned_renewal_branch(upstream);
 if exists(select 1 from stripe_billing_failed_grace_claims q join stripe_billing_failed_grace_preparations p on p.id=q.preparation_id where p.admission_id=new.admission_id) then
  raise exception 'Grace firstclaim permanently owns this branch' using errcode='23514';end if;
 if tg_table_name='stripe_billing_failed_method_reservations' then
  if exists(select 1 from stripe_billing_failed_renewal_recoveries where admission_id=new.admission_id) then
   raise exception 'Legacy recovery permanently owns this branch' using errcode='23514';end if;
 else
  if exists(select 1 from stripe_billing_failed_method_reservations where admission_id=new.admission_id) then
   raise exception 'Method reservation permanently owns this branch' using errcode='23514';end if;
 end if;return new;
end $$;

-- Retain the V35 lineage conditions and explicitly close financial method authority on a
-- grace-owned lane, independently of the INSERT exclusion and the new operation's resource type.
create or replace function owned_method_lineage(target uuid) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r where r.id=$1
 and owned_method_identity(r.id) and owned_failed_renewal_authority(r.admission_id)
 and not exists(select 1 from stripe_billing_failed_renewal_recoveries where admission_id=r.admission_id)
 and not exists(select 1 from stripe_billing_failed_grace_claims q join stripe_billing_failed_grace_preparations p on p.id=q.preparation_id where p.admission_id=r.admission_id))
$$;

-- V37 association, preserving every existing condition and adding permanent grace exclusion.
create or replace function owned_failed_renewal_association(target_admission uuid, selected uuid) returns boolean
 language sql stable set join_collapse_limit=1 as $$
 select exists(select 1 from stripe_billing_failed_renewals d
 join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
 join stripe_billing_owned_subscriptions i on i.id=a.owned_intent
 join stripe_billing_owned_subscription_receipts initial on initial.intent_id=i.id
 join stripe_billing_failed_renewal_failures f on f.admission_id=d.id
 join subscription_operations o on o.id=selected
 join stripe_subscription_recovery_proofs p on p.operation_id=o.id
 where d.id=target_admission and o.kind='RECOVER'
 and row(o.site_id,o.customer_id,o.subscription_id,o.expected_revision,o.policy_version)=row(d.site_id,d.customer_id,d.subscription_id,f.projected_revision,a.policy_version)
 and o.authority_scope='owned-failed-renewal:'||d.id::text
 and o.fingerprint ~ ('^v1[.]recover[.]'||f.renewal_bill::text||'[.][a-f0-9]{64}$')
 and o.provider_operation_key='subscription:'||d.payment_account_id::text||':'||o.id::text||':RECOVER'
 and o.created_at>=f.recorded_at and o.safe_failure_code is null
 and row(p.site_id,p.customer_id,p.subscription_id,p.payment_account_id,p.billing_record_id,p.subscription_reference,p.invoice_reference,p.origin_operation,p.origin_step,p.allocation_reference,p.intent_reference)
  =row(d.site_id,d.customer_id,d.subscription_id,d.payment_account_id,f.renewal_bill,initial.reference,f.invoice_reference,i.create_operation,initial.provider_operation,f.allocation_reference,f.payment_intent_reference)
 and p.pay_step is null and p.pay_kind is null and p.operation_kind='RECOVER'
 and not exists(select 1 from subscription_action_capabilities g where g.payment_account_id=d.payment_account_id and g.subscription_id=d.subscription_id and g.billing_invoice_reference=f.invoice_reference)
 and not exists(select 1 from stripe_subscription_recovery_proofs other where other.payment_account_id=d.payment_account_id and other.subscription_id=d.subscription_id and other.invoice_reference=f.invoice_reference and other.operation_id<>o.id)
 and not exists(select 1 from provider_operations step join subscription_operations other on other.id=step.resource_id
   where step.payment_account_id=d.payment_account_id and other.subscription_id=d.subscription_id and step.kind='INVOICE_PAY'
    and (other.id=o.id or split_part(other.fingerprint,'.',3)=f.renewal_bill::text)
    and (step.first_dispatched_at is not null or step.status in ('DISPATCHING','UNKNOWN','ACKNOWLEDGED')))
 and not exists(select 1 from provider_operations step join subscription_operations other on other.id=step.resource_id
   where step.payment_account_id=d.payment_account_id and other.subscription_id=d.subscription_id and step.kind='OWNED_RECOVERY_INTENT_CONFIRM'
    and (other.id=o.id or split_part(other.fingerprint,'.',3)=f.renewal_bill::text)
    and not exists(select 1 from stripe_billing_failed_method_recoveries x where x.operation_id=o.id and owned_recovery_confirm_owns(x.reservation_id,step)))
 and not exists(select 1 from stripe_billing_failed_grace_claims q join stripe_billing_failed_grace_preparations grace on grace.id=q.preparation_id where grace.admission_id=d.id))
$$;

-- Canonical V38 lifecycle closure first; remaining immediate references are pre-acquired NOWAIT.
-- A direct provider UPDATE owns its row already: every reverse edge here is nonblocking.
create function lock_owned_failed_grace_advance(preparation uuid) returns void language plpgsql volatile as $$
declare admission uuid;
begin
 select admission_id into admission from stripe_billing_failed_grace_preparations where id=$1;
 if admission is null then raise exception 'Missing owned grace preparation' using errcode='23514';end if;
 perform lock_owned_failed_grace(admission);
 perform id from stripe_billing_failed_grace_preparations where id=$1 for key share nowait;
 perform id from provider_operations where kind='OWNED_GRACE_CLOCK_ADVANCE' and resource_id=$1 for update nowait;
 perform preparation_id from stripe_billing_failed_grace_claims where preparation_id=$1 for key share nowait;
 perform preparation_id from stripe_billing_failed_grace_acks where preparation_id=$1 for key share nowait;
end $$;
create function lock_owned_failed_grace_cleanup(preparation uuid) returns void language plpgsql volatile as $$
declare d stripe_billing_failed_renewals%rowtype;
begin
 if current_setting('transaction_isolation')<>'read committed' then raise exception 'Owned grace cleanup requires READ COMMITTED' using errcode='23514';end if;
 select admission.* into d from stripe_billing_failed_renewals admission join stripe_billing_failed_grace_preparations p on p.admission_id=admission.id where p.id=$1;
 if d.id is null then raise exception 'Missing owned grace cleanup identity' using errcode='23514';end if;
 perform id from stripe_billing_first_renewals where id=d.first_renewal_admission for update nowait;
 perform id from stripe_billing_failed_renewals where id=d.id for key share nowait;
 perform id from stripe_billing_failed_grace_preparations where id=$1 for key share nowait;
 perform id from provider_operations where kind='OWNED_GRACE_CLOCK_ADVANCE' and resource_id=$1 for update nowait;
 perform preparation_id from stripe_billing_failed_grace_claims where preparation_id=$1 for key share nowait;
 perform preparation_id from stripe_billing_failed_grace_acks where preparation_id=$1 for key share nowait;
end $$;

create function require_owned_failed_grace_claim() returns trigger language plpgsql volatile as $$
declare step provider_operations%rowtype; admission uuid; db_at timestamptz;
begin
 perform owned_failed_grace_limits(greatest(1,floor(extract(epoch from(new.deadline_at-clock_timestamp()))*1000)::bigint),false);
 perform lock_owned_failed_grace_advance(new.preparation_id);
 select admission_id into admission from stripe_billing_failed_grace_preparations where id=new.preparation_id;
 select * into step from provider_operations where id=new.operation_id for update nowait;db_at:=clock_timestamp();
 if new.recorded_transaction<>pg_current_xact_id() or not owned_failed_grace_owns(new.preparation_id,step)
  or not owned_failed_grace_current(admission) or step.status<>'RESERVED' or step.version<>0
  or num_nonnulls(step.provider_reference,step.first_dispatched_at,step.retry_before,step.lease_owner,step.lease_expires_at)<>0
  or new.entered_at>new.claimed_at or new.claimed_at>db_at or db_at>=new.deadline_at
  or new.claimed_at<(select prepared_at from stripe_billing_failed_grace_preparations where id=new.preparation_id)
 then raise exception 'Invalid original owned grace firstclaim' using errcode='23514';end if;
 new.recorded_transaction:=pg_current_xact_id();return new;
end $$;
create trigger failed_grace_claim_proof before insert on stripe_billing_failed_grace_claims for each row execute function require_owned_failed_grace_claim();

create function require_owned_failed_grace_operation() returns trigger language plpgsql volatile as $$
declare p stripe_billing_failed_grace_preparations%rowtype; q stripe_billing_failed_grace_claims%rowtype; db_at timestamptz; expected_clock text;
begin
 if tg_op='DELETE' then
  if old.kind='OWNED_GRACE_CLOCK_ADVANCE' then raise exception 'Owned grace operation is immutable' using errcode='23514';end if;return old;
 end if;
 if tg_op='UPDATE' and old.kind='OWNED_GRACE_CLOCK_ADVANCE' and new.kind<>old.kind then
  raise exception 'Owned grace kind is immutable' using errcode='23514';end if;
 if new.kind<>'OWNED_GRACE_CLOCK_ADVANCE' then return new;end if;
 if tg_op='UPDATE' and old.kind<>new.kind then raise exception 'Owned grace cannot convert an existing operation' using errcode='23514';end if;
 if tg_op='UPDATE' then
  if row(new.id,new.payment_account_id,new.external_account_id,new.credentials_ref,new.kind,new.resource_id,new.operation_key,new.fingerprint,new.recovery_attempts,new.next_attempt_at,new.safe_error_code,new.manual_review_required)
   is distinct from row(old.id,old.payment_account_id,old.external_account_id,old.credentials_ref,old.kind,old.resource_id,old.operation_key,old.fingerprint,old.recovery_attempts,old.next_attempt_at,old.safe_error_code,old.manual_review_required)
  then raise exception 'Owned grace history is immutable' using errcode='23514';end if;
 end if;
 select * into p from stripe_billing_failed_grace_preparations where id=new.resource_id;
 if p.id is null or not owned_failed_grace_owns(p.id,new) then raise exception 'Invalid owned grace operation identity' using errcode='23514';end if;
 if tg_op='INSERT' then
  perform owned_failed_grace_limits(30000,false);perform lock_owned_failed_grace_advance(p.id);
  if not owned_failed_grace_current(p.admission_id) or new.status<>'RESERVED' or new.version<>0
   or num_nonnulls(new.provider_reference,new.first_dispatched_at,new.retry_before,new.lease_owner,new.lease_expires_at,new.safe_error_code)<>0
   or new.recovery_attempts<>0 or new.manual_review_required or new.next_attempt_at<>transaction_timestamp()
  then raise exception 'Invalid pristine owned grace reservation' using errcode='23514';end if;return new;
 end if;
 select * into q from stripe_billing_failed_grace_claims where preparation_id=p.id and operation_id=old.id;
 if q.preparation_id is null then raise exception 'Owned grace original claim required' using errcode='23514';end if;
 if new.status='UNKNOWN' then perform owned_failed_grace_limits(2000,true);perform lock_owned_failed_grace_cleanup(p.id);
 else
  perform owned_failed_grace_limits(greatest(1,floor(extract(epoch from(q.deadline_at-clock_timestamp()))*1000)::bigint),false);
  perform lock_owned_failed_grace_advance(p.id);
 end if;db_at:=clock_timestamp();
 if old.status='RESERVED' and old.version=0 and new.status='DISPATCHING' and new.version=1 then
  if q.recorded_transaction<>pg_current_xact_id() or not owned_failed_grace_current(p.admission_id)
   or row(new.first_dispatched_at,new.lease_owner,new.lease_expires_at) is distinct from row(q.claimed_at,q.claimant,q.deadline)
   or new.retry_before<>q.claimed_at+interval '23 hours' or new.provider_reference is not null or db_at>=q.deadline_at or db_at>=q.deadline
  then raise exception 'Invalid original owned grace dispatch' using errcode='23514';end if;
 elsif old.status='DISPATCHING' and old.version=1 and new.status in ('ACKNOWLEDGED','UNKNOWN') and new.version=2 then
  if q.recorded_transaction=pg_current_xact_id()
   or row(old.first_dispatched_at,old.lease_owner,old.lease_expires_at) is distinct from row(q.claimed_at,q.claimant,q.deadline)
   or row(new.first_dispatched_at,new.retry_before) is distinct from row(old.first_dispatched_at,old.retry_before)
   or new.lease_owner is not null or new.lease_expires_at is not null
  then raise exception 'Independently committed original grace claim required' using errcode='23514';end if;
  if new.status='ACKNOWLEDGED' then
   select r.reference into expected_clock from stripe_billing_first_renewal_ready r where r.id=p.predecessor_ready;
   if not owned_failed_grace_current(p.admission_id) or db_at>=q.deadline_at or db_at>=q.deadline or new.provider_reference is distinct from expected_clock
   then raise exception 'Invalid owned grace acknowledgment' using errcode='23514';end if;
  elsif new.provider_reference is not null or exists(select 1 from stripe_billing_failed_grace_ready where preparation_id=p.id) then
   raise exception 'Invalid owned grace uncertainty' using errcode='23514';end if;
 else raise exception 'Owned grace cannot be reset, renewed or retried' using errcode='23514';
 end if;return new;
end $$;
create trigger aaaa_owned_failed_grace_operation before insert or update or delete on provider_operations for each row execute function require_owned_failed_grace_operation();

create function require_owned_failed_grace_ack() returns trigger language plpgsql volatile as $$
declare p stripe_billing_failed_grace_preparations%rowtype; q stripe_billing_failed_grace_claims%rowtype; step provider_operations%rowtype; original stripe_billing_first_renewal_ready%rowtype; db_at timestamptz;
begin
 select * into q from stripe_billing_failed_grace_claims where preparation_id=new.preparation_id;
 if q.preparation_id is null then raise exception 'Missing owned grace ACK original claim' using errcode='23514';end if;
 perform owned_failed_grace_limits(greatest(1,floor(extract(epoch from(q.deadline_at-clock_timestamp()))*1000)::bigint),false);
 perform lock_owned_failed_grace_advance(new.preparation_id);
 select * into p from stripe_billing_failed_grace_preparations where id=new.preparation_id;
 select * into step from provider_operations where id=new.operation_id for update nowait;
 select * into original from stripe_billing_first_renewal_ready where id=p.predecessor_ready;db_at:=clock_timestamp();
 if new.recorded_transaction<>pg_current_xact_id() or q.recorded_transaction=pg_current_xact_id()
  or not owned_failed_grace_owns(p.id,step) or not owned_failed_grace_current(p.admission_id)
  or row(new.preparation_id,new.operation_id,new.invocation,new.claimant,new.deadline) is distinct from row(q.preparation_id,q.operation_id,q.invocation,q.claimant,q.deadline)
  or step.status<>'ACKNOWLEDGED' or step.version<>2 or step.provider_reference is distinct from new.reference
  or new.observed_at<q.claimed_at or new.acknowledged_at>db_at or new.acknowledged_at>=q.deadline_at or new.acknowledged_at>=q.deadline
  or db_at>=q.deadline_at or db_at>=q.deadline
  or row(new.reference,new.object_type,new.livemode,new.name,new.provider_created,new.provider_deletes_after)
   is distinct from row(original.reference,original.object_type,original.livemode,original.name,original.provider_created,original.provider_deletes_after)
  or new.advancing_target is not null and new.advancing_target<>p.target
  or not ((new.status='ready' and new.frozen_time=p.target) or (new.status='advancing' and new.frozen_time>=original.frozen_time and new.frozen_time<=p.target))
 then raise exception 'Invalid owned grace exact response ACK' using errcode='23514';end if;
 new.recorded_transaction:=pg_current_xact_id();return new;
end $$;
create trigger failed_grace_ack_proof before insert on stripe_billing_failed_grace_acks for each row execute function require_owned_failed_grace_ack();

create function require_owned_failed_grace_ready() returns trigger language plpgsql volatile as $$
declare p stripe_billing_failed_grace_preparations%rowtype; q stripe_billing_failed_grace_claims%rowtype; ack stripe_billing_failed_grace_acks%rowtype; step provider_operations%rowtype; db_at timestamptz;
begin
 select * into q from stripe_billing_failed_grace_claims where preparation_id=new.preparation_id;
 if q.preparation_id is null then raise exception 'Missing owned grace READY original claim' using errcode='23514';end if;
 perform owned_failed_grace_limits(greatest(1,floor(extract(epoch from(q.deadline_at-clock_timestamp()))*1000)::bigint),false);
 perform lock_owned_failed_grace_advance(new.preparation_id);
 select * into p from stripe_billing_failed_grace_preparations where id=new.preparation_id;
 select * into ack from stripe_billing_failed_grace_acks where preparation_id=p.id;
 select * into step from provider_operations where id=new.operation_id for update nowait;db_at:=clock_timestamp();
 if new.recorded_transaction<>pg_current_xact_id() or ack.preparation_id is null or ack.recorded_transaction=pg_current_xact_id() or q.recorded_transaction=pg_current_xact_id()
  or not owned_failed_grace_owns(p.id,step) or not owned_failed_grace_current(p.admission_id)
  or row(new.preparation_id,new.operation_id,new.invocation) is distinct from row(ack.preparation_id,ack.operation_id,ack.invocation)
  or new.admission_id<>p.admission_id or step.status<>'ACKNOWLEDGED' or step.version<>2 or step.provider_reference is distinct from new.reference
  or row(new.reference,new.object_type,new.livemode,new.name,new.provider_created,new.provider_deletes_after)
   is distinct from row(ack.reference,ack.object_type,ack.livemode,ack.name,ack.provider_created,ack.provider_deletes_after)
  or new.status<>'ready' or new.frozen_time<>p.target or new.advancing_target is not null and new.advancing_target<>p.target
  or new.observed_at<ack.acknowledged_at or new.observed_at>db_at or new.observed_at>=q.deadline_at or new.observed_at>=q.deadline
  or db_at>=q.deadline_at or db_at>=q.deadline
 then raise exception 'Invalid independent owned grace READY' using errcode='23514';end if;
 new.recorded_transaction:=pg_current_xact_id();return new;
end $$;
create trigger failed_grace_ready_proof before insert on stripe_billing_failed_grace_ready for each row execute function require_owned_failed_grace_ready();

create function require_owned_failed_grace_cleanup() returns trigger language plpgsql volatile as $$
declare q stripe_billing_failed_grace_claims%rowtype; step provider_operations%rowtype;
begin
 perform owned_failed_grace_limits(2000,true);perform lock_owned_failed_grace_cleanup(new.preparation_id);
 select * into q from stripe_billing_failed_grace_claims where preparation_id=new.preparation_id;
 select * into step from provider_operations where id=new.operation_id for update nowait;
 if new.recorded_transaction<>pg_current_xact_id() or q.preparation_id is null or q.recorded_transaction=pg_current_xact_id()
  or row(new.preparation_id,new.operation_id,new.invocation) is distinct from row(q.preparation_id,q.operation_id,q.invocation)
  or not owned_failed_grace_owns(q.preparation_id,step) or step.status not in ('UNKNOWN','ACKNOWLEDGED') or step.version<>2
  or step.first_dispatched_at<>q.claimed_at or step.retry_before<>q.claimed_at+interval '23 hours'
  or step.lease_owner is not null or step.lease_expires_at is not null
  or new.stopped_at<q.claimed_at or new.stopped_at>clock_timestamp()
  or exists(select 1 from stripe_billing_failed_grace_ready where preparation_id=q.preparation_id)
  or exists(select 1 from stripe_billing_failed_grace_acks ack where ack.preparation_id=q.preparation_id and new.stopped_at<ack.acknowledged_at)
  or (step.status='ACKNOWLEDGED') is distinct from exists(select 1 from stripe_billing_failed_grace_acks ack where ack.preparation_id=q.preparation_id and ack.operation_id=step.id)
 then raise exception 'Invalid original invocation grace cleanup receipt' using errcode='23514';end if;
 new.recorded_transaction:=pg_current_xact_id();return new;
end $$;
create trigger failed_grace_cleanup_proof before insert on stripe_billing_failed_grace_cleanup for each row execute function require_owned_failed_grace_cleanup();

-- ALWAYS take upstream NOWAIT before asking whether a grace claim exists. A stale no-claim
-- snapshot must not wait on the later FK and then stop a newly committed grace winner.
create or replace function require_failed_renewal_stop() returns trigger language plpgsql volatile as $$
declare upstream uuid; preparation uuid;
begin
 if current_setting('transaction_isolation')<>'read committed' then
  raise exception 'Owned failed renewal stop requires READ COMMITTED' using errcode='23514';end if;
 select first_renewal_admission into upstream from stripe_billing_failed_renewals where id=new.admission_id;
 perform id from stripe_billing_first_renewals where id=upstream for update nowait;
 -- Separate fresh statement after serialization, including for the old unclaimed lane.
 select p.id into preparation from stripe_billing_failed_grace_preparations p join stripe_billing_failed_grace_claims q on q.preparation_id=p.id where p.admission_id=new.admission_id;
 if preparation is not null then
  perform owned_failed_grace_limits(2000,true);
  if new.reason<>'UNCERTAIN' or not exists(select 1 from stripe_billing_failed_grace_cleanup c
   join stripe_billing_failed_grace_claims q on row(q.preparation_id,q.operation_id,q.invocation)=row(c.preparation_id,c.operation_id,c.invocation)
   join provider_operations o on o.id=q.operation_id
   where c.preparation_id=preparation and c.recorded_transaction=pg_current_xact_id() and q.recorded_transaction<>pg_current_xact_id()
    and c.stopped_at=new.stopped_at and o.status in ('UNKNOWN','ACKNOWLEDGED') and o.version=2
    and not exists(select 1 from stripe_billing_failed_grace_ready r where r.preparation_id=preparation))
  then raise exception 'Only original grace cleanup may stop its claimed lane' using errcode='23514';end if;
 end if;
 -- Preserve every V34 condition for old unclaimed stops and the claimed original stop.
 if new.stopped_at>clock_timestamp() or not exists(select 1 from stripe_billing_failed_renewals d where d.id=new.admission_id and new.stopped_at>=d.admitted_at)
 then raise exception 'Invalid terminal failed renewal receipt' using errcode='23514';end if;return new;
end $$;

-- Atomicity/provenance checks never resample the positive clock after a valid precommit write.
create function check_owned_failed_grace_committed() returns trigger language plpgsql volatile as $$
declare selected uuid; step provider_operations%rowtype; q stripe_billing_failed_grace_claims%rowtype; ack stripe_billing_failed_grace_acks%rowtype;
begin
 if tg_table_name='provider_operations' then if new.kind<>'OWNED_GRACE_CLOCK_ADVANCE' then return null;end if;selected:=new.id;
 else selected:=new.operation_id;end if;
 select * into step from provider_operations where id=selected;
 select * into q from stripe_billing_failed_grace_claims where operation_id=selected;
 select * into ack from stripe_billing_failed_grace_acks where operation_id=selected;
 if step.id is null or q.preparation_id is null or not owned_failed_grace_owns(q.preparation_id,step)
 then raise exception 'Missing committed original grace claim' using errcode='23514';end if;
 if q.recorded_transaction=pg_current_xact_id() then
  if step.status<>'DISPATCHING' or step.version<>1 or row(step.first_dispatched_at,step.lease_owner,step.lease_expires_at) is distinct from row(q.claimed_at,q.claimant,q.deadline)
   or ack.preparation_id is not null or exists(select 1 from stripe_billing_failed_grace_ready where preparation_id=q.preparation_id)
   or exists(select 1 from stripe_billing_failed_grace_cleanup where preparation_id=q.preparation_id)
  then raise exception 'Original grace claim must independently commit DISPATCHING1' using errcode='23514';end if;
 elsif step.status='ACKNOWLEDGED' then
  if step.version<>2 or ack.preparation_id is null
   or row(ack.preparation_id,ack.operation_id,ack.invocation,ack.claimant,ack.deadline,ack.reference) is distinct from row(q.preparation_id,q.operation_id,q.invocation,q.claimant,q.deadline,step.provider_reference)
  then raise exception 'Atomic exact original grace ACK required' using errcode='23514';end if;
 elsif step.status='UNKNOWN' then
  if step.version<>2 or ack.preparation_id is not null or not exists(select 1 from stripe_billing_failed_grace_cleanup c where c.preparation_id=q.preparation_id and c.recorded_transaction=pg_current_xact_id())
  then raise exception 'Atomic original grace UNKNOWN cleanup required' using errcode='23514';end if;
 elsif step.status<>'DISPATCHING' or step.version<>1 then raise exception 'Invalid committed owned grace state' using errcode='23514';end if;
 if tg_table_name='stripe_billing_failed_grace_ready' then
  if not exists(select 1 from stripe_billing_failed_grace_acks a where a.preparation_id=q.preparation_id and a.recorded_transaction<>new.recorded_transaction) then
   raise exception 'Independent grace ACK commit required before READY' using errcode='23514';end if;
 end if;
 return null;
end $$;
create constraint trigger failed_grace_provider_committed after insert or update on provider_operations deferrable initially deferred for each row execute function check_owned_failed_grace_committed();
create constraint trigger failed_grace_claim_committed after insert on stripe_billing_failed_grace_claims deferrable initially deferred for each row execute function check_owned_failed_grace_committed();
create constraint trigger failed_grace_ack_committed after insert on stripe_billing_failed_grace_acks deferrable initially deferred for each row execute function check_owned_failed_grace_committed();
create constraint trigger failed_grace_ready_committed after insert on stripe_billing_failed_grace_ready deferrable initially deferred for each row execute function check_owned_failed_grace_committed();

create function check_owned_failed_grace_cleanup_committed() returns trigger language plpgsql volatile as $$
begin
 if not exists(select 1 from stripe_billing_failed_grace_preparations p join stripe_billing_failed_renewal_stops s on s.admission_id=p.admission_id
  where p.id=new.preparation_id and s.reason='UNCERTAIN' and s.stopped_at=new.stopped_at
   and s.xmin=pg_current_xact_id()::xid and new.recorded_transaction=pg_current_xact_id())
 then raise exception 'Atomic original grace terminal stop required' using errcode='23514';end if;return null;
end $$;
create constraint trigger failed_grace_cleanup_committed after insert on stripe_billing_failed_grace_cleanup deferrable initially deferred for each row execute function check_owned_failed_grace_cleanup_committed();
