-- One-use original PaymentIntent confirmation. No route, scheduler or ordinary dispatch authority.
alter table provider_operations drop constraint provider_operations_kind_check;
alter table provider_operations add constraint provider_operations_kind_check check(kind in (
 'PRODUCT_CREATE','PRICE_CREATE','PAYMENT_PREPARE','REFUND_CREATE','CUSTOMER_CREATE','TEST_CLOCK_CREATE','TEST_CLOCK_ADVANCE',
 'SUBSCRIPTION_CREATE','SUBSCRIPTION_UPDATE','SUBSCRIPTION_CANCEL','SETUP_INTENT_CREATE','INVOICE_PAY',
 'SCHEDULE_CREATE','SCHEDULE_UPDATE','SCHEDULE_RELEASE','PORTAL_CONFIGURATION_CREATE','PORTAL_SESSION_CREATE','OWNED_RECOVERY_INTENT_CONFIRM'));

create table stripe_billing_failed_recovery_confirm_claims (
 provider_operation uuid primary key references provider_operations(id),
 reservation_id uuid not null unique references stripe_billing_failed_method_recoveries(reservation_id),
 claimant varchar(128) not null check(length(trim(claimant))>0), claimed_at timestamptz not null,
 deadline timestamptz not null check(deadline=claimed_at+interval '60 seconds'),
 invocation uuid not null unique, entered_at timestamptz not null,
 deadline_at timestamptz not null check(deadline_at=entered_at+interval '30 seconds'),
 recorded_transaction xid8 not null default pg_current_xact_id(),
 check(claimed_at>=entered_at and claimed_at<deadline_at),
 unique(provider_operation,reservation_id,claimant,deadline)
);
create table stripe_billing_failed_recovery_confirm_acks (
 provider_operation uuid primary key, reservation_id uuid not null unique,
 claimant varchar(128) not null, deadline timestamptz not null,
 payment_intent varchar(255) not null check(payment_intent ~ '^pi_[A-Za-z0-9]{1,252}$'),
 customer_reference varchar(255) not null check(customer_reference ~ '^cus_[A-Za-z0-9]{1,251}$'),
 method_reference varchar(255) not null check(method_reference ~ '^pm_[A-Za-z0-9]{1,252}$'),
 currency varchar(3) not null check(currency ~ '^[A-Z]{3}$'), amount bigint not null check(amount>0),
 received bigint not null check(received=amount), capturable bigint not null check(capturable=0),
 charge varchar(255) not null check(charge ~ '^ch_[A-Za-z0-9]{1,252}$'),
 observed_at timestamptz not null, acknowledged_at timestamptz not null check(acknowledged_at>=observed_at),
 foreign key(provider_operation,reservation_id,claimant,deadline)
  references stripe_billing_failed_recovery_confirm_claims(provider_operation,reservation_id,claimant,deadline)
);
create trigger owned_recovery_confirm_claim_immutable before update or delete on stripe_billing_failed_recovery_confirm_claims
 for each row execute function protect_failed_renewal_evidence();
create trigger owned_recovery_confirm_ack_immutable before update or delete on stripe_billing_failed_recovery_confirm_acks
 for each row execute function protect_failed_renewal_evidence();

create function owned_recovery_confirm_fingerprint(target uuid) returns text language sql stable as $$
 select owned_method_stripe_hash(d.payment_account_id::text,clock.external_account_id,clock.credentials_ref,'STRIPE','SANDBOX',
  'OWNED_RECOVERY_CONFIRM_V1',o.id::text,o.provider_operation_key,o.fingerprint,r.id::text,
  f.renewal_bill::text,f.bill_version::text,f.invoice_reference,f.allocation_reference,f.payment_intent_reference,
  f.projected_revision::text,f.observation_sequence::text,r.binding_id::text,r.binding_reference,
  c.setup_step::text,c.setup_step_version::text,c.method_step::text,c.method_step_version::text,c.method_reference,
  'off_session:true','error_on_requires_action:true')
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

-- Immutable descriptive lineage only. No live owner, session, debt, projection phase or positive prestate.
create function owned_recovery_confirm_identity(target uuid) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r
 join stripe_billing_failed_renewals d on d.id=r.admission_id
 join stripe_billing_failed_renewal_failures f on f.admission_id=d.id
 join stripe_billing_first_renewals first on first.id=d.first_renewal_admission
 join stripe_billing_owned_subscriptions owned on owned.id=first.owned_intent
 join stripe_billing_owned_subscription_receipts initial on initial.intent_id=owned.id
 join stripe_billing_failed_method_completions c on c.reservation_id=r.id
 join stripe_billing_failed_method_recoveries x on x.reservation_id=r.id
 join subscription_operations setup on setup.id=r.setup_operation
 join subscription_operations recovery on recovery.id=x.operation_id
 join stripe_subscription_recovery_proofs p on p.operation_id=recovery.id
 where r.id=$1 and owned_method_identity(r.id)
 and c.setup_operation=setup.id and c.setup_final_version=r.initial_operation_version+2
 and row(c.projected_revision,c.observation_sequence,x.observation_sequence,x.operation_version)
  =row(f.projected_revision,f.observation_sequence+1,f.observation_sequence+1,0::bigint)
 and c.completed_at>=r.reserved_at and x.associated_at>=c.completed_at
 and r.browser_session_id is not null and r.request_fingerprint=owned_method_browser_request(d.id,r.browser_origin)
 and row(recovery.site_id,recovery.customer_id,recovery.subscription_id,recovery.kind,recovery.expected_revision,recovery.policy_version)
  =row(d.site_id,d.customer_id,d.subscription_id,'RECOVER'::varchar,f.projected_revision,first.policy_version)
 and recovery.authority_scope='owned-failed-renewal:'||d.id::text
 and recovery.provider_operation_key='subscription:'||d.payment_account_id::text||':'||recovery.id::text||':RECOVER'
 and recovery.fingerprint='v1.recover.'||f.renewal_bill::text||'.'||first_renewal_hash('owned-failed-renewal-recover-v1',d.id::text,f.renewal_bill::text,f.bill_version::text,f.invoice_reference,f.allocation_reference,f.payment_intent_reference,f.projected_revision::text)
 and recovery.key_digest=first_renewal_hash('owned-failed-renewal-recover-key-v1',d.id::text)
 and row(p.site_id,p.customer_id,p.subscription_id,p.payment_account_id,p.billing_record_id,p.subscription_reference,p.invoice_reference,p.origin_operation,p.origin_step,p.allocation_reference,p.intent_reference)
  =row(d.site_id,d.customer_id,d.subscription_id,d.payment_account_id,f.renewal_bill,initial.reference,f.invoice_reference,owned.create_operation,initial.provider_operation,f.allocation_reference,f.payment_intent_reference)
 and p.operation_kind='RECOVER' and p.pay_step is null and p.pay_kind is null)
$$;

-- Structural ownership only: never call owned_method_recovery_pre here (it calls V34 association).
create function owned_recovery_confirm_owns(target uuid,step provider_operations) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r
 join stripe_billing_failed_method_recoveries x on x.reservation_id=r.id
 join stripe_billing_failed_renewals d on d.id=r.admission_id
 join stripe_billing_first_renewals first on first.id=d.first_renewal_admission
 join stripe_billing_owned_subscriptions owned on owned.id=first.owned_intent
 join stripe_billing_clock_intents clock on clock.id=owned.clock_intent
 join subscription_operations o on o.id=x.operation_id
 where r.id=$1 and owned_recovery_confirm_identity(r.id)
 and row(($2).payment_account_id,($2).external_account_id,($2).credentials_ref,($2).kind,($2).resource_id,($2).operation_key,($2).fingerprint)
  =row(d.payment_account_id,clock.external_account_id,clock.credentials_ref,'OWNED_RECOVERY_INTENT_CONFIRM'::varchar,o.id,o.provider_operation_key||':CONFIRM',owned_recovery_confirm_fingerprint(r.id)))
$$;

-- Retain every V34 condition, explicitly allowing only this exact dedicated structural relation.
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
    and not exists(select 1 from stripe_billing_failed_method_recoveries x where x.operation_id=o.id and owned_recovery_confirm_owns(x.reservation_id,step))))
$$;

create function owned_recovery_confirm_pre(target uuid) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r
 join stripe_billing_failed_renewals d on d.id=r.admission_id
 join customer_sessions session on session.id=r.browser_session_id
 join customers customer on customer.id=d.customer_id and customer.site_id=d.site_id
 join sites site on site.id=d.site_id
 join site_allowed_origins origin on origin.site_id=site.id and origin.origin=session.origin
 where r.id=$1 and owned_recovery_confirm_identity(r.id) and owned_method_recovery_pre(r.id)
 and owned_method_browser_permit(r.setup_operation)
 and exists(select 1 from stripe_billing_failed_method_recoveries x
   join subscription_operations recovery on recovery.id=x.operation_id
   join stripe_subscription_recovery_proofs proof on proof.operation_id=recovery.id
   where x.reservation_id=r.id and recovery.provider_reference=proof.subscription_reference)
 and row(session.site_id,session.customer_id,session.origin,session.expires_at)=row(d.site_id,d.customer_id,r.browser_origin,r.browser_session_expires_at)
 and session.revoked_at is null and session.scopes ? 'SUBSCRIPTION_MANAGE' and session.issued_at<=r.reserved_at
 and clock_timestamp()<session.expires_at and customer.status='ACTIVE' and site.status='ACTIVE'
 and not exists(select 1 from stripe_billing_failed_method_reads where reservation_id=r.id and phase='RECOVERED')
 and not exists(select 1 from stripe_billing_failed_method_publications where reservation_id=r.id))
$$;

-- Direct SQL already owns the provider row. All upstream locks are ordered NOWAIT: contention refuses,
-- never creates a provider->operation blocking inversion. Java already holds these canonical locks.
create function lock_owned_recovery_confirm(target uuid,positive boolean) returns void language plpgsql volatile as $$
declare r stripe_billing_failed_method_reservations%rowtype; d stripe_billing_failed_renewals%rowtype; upstream uuid; bill uuid;
begin
 select * into r from stripe_billing_failed_method_reservations where id=$1;
 select * into d from stripe_billing_failed_renewals where id=r.admission_id;
 if r.id is null or d.id is null then raise exception 'Owned recovery confirmation unavailable' using errcode='23514'; end if;
 select owned.create_operation into upstream from stripe_billing_first_renewals first
  join stripe_billing_owned_subscriptions owned on owned.id=first.owned_intent where first.id=d.first_renewal_admission;
 perform 1 from subscription_operations o where o.subscription_id=d.subscription_id and
  (o.id=upstream or o.id=r.setup_operation or o.id in(select operation_id from stripe_billing_failed_method_recoveries where reservation_id=r.id)
    or o.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN')) order by o.id for update nowait;
 if $2 then
  perform 1 from customer_sessions where id=r.browser_session_id for share nowait;
  perform 1 from sites where id=d.site_id for share nowait;
  perform 1 from site_allowed_origins where site_id=d.site_id and origin=r.browser_origin for share nowait;
  perform 1 from customers where id=d.customer_id and site_id=d.site_id for share nowait;
  perform 1 from payment_accounts where id=d.payment_account_id for share nowait;
  perform 1 from subscriptions where id=d.subscription_id for update nowait;
  perform 1 from subscription_billing_records where subscription_id=d.subscription_id order by id for update nowait;
  perform 1 from stripe_billing_method_browser_roots where reservation_id=r.id for update nowait;
 end if;
exception when lock_not_available then
 raise exception 'Owned recovery confirmation authority contended' using errcode='23514';
end $$;

create function require_owned_recovery_confirm_claim() returns trigger language plpgsql volatile as $$
declare step provider_operations%rowtype; db_at timestamptz;
begin
 perform lock_owned_recovery_confirm(new.reservation_id,true);
 select * into step from provider_operations where id=new.provider_operation for update nowait;
 db_at:=clock_timestamp();
 if new.recorded_transaction<>pg_current_xact_id() or not owned_recovery_confirm_owns(new.reservation_id,step)
  or not owned_recovery_confirm_pre(new.reservation_id)
  or step.status<>'RESERVED' or step.version<>0 or step.first_dispatched_at is not null or step.provider_reference is not null
  or new.entered_at>new.claimed_at or new.claimed_at>db_at or db_at>=new.deadline_at
 then raise exception 'Invalid owned recovery confirmation first claim' using errcode='23514'; end if;
 -- Explicit authentication, not a user-selected transaction identifier or a default alone.
 new.recorded_transaction:=pg_current_xact_id();return new;
end $$;
create trigger owned_recovery_confirm_claim_proof before insert on stripe_billing_failed_recovery_confirm_claims
 for each row execute function require_owned_recovery_confirm_claim();

create function require_owned_recovery_confirm_operation() returns trigger language plpgsql volatile as $$
declare reservation uuid; receipt stripe_billing_failed_recovery_confirm_claims%rowtype; db_at timestamptz;
begin
 if tg_op='DELETE' then
  if old.kind='OWNED_RECOVERY_INTENT_CONFIRM' then raise exception 'Owned recovery confirmation is immutable' using errcode='23514'; end if;return old;
 end if;
 if tg_op='UPDATE' and old.kind='OWNED_RECOVERY_INTENT_CONFIRM' and new.kind<>old.kind then
  raise exception 'Owned recovery confirmation kind is immutable' using errcode='23514'; end if;
 if new.kind<>'OWNED_RECOVERY_INTENT_CONFIRM' then return new; end if;
 select x.reservation_id into reservation from stripe_billing_failed_method_recoveries x
  join stripe_billing_failed_method_reservations r on r.id=x.reservation_id
  join stripe_billing_failed_renewals d on d.id=r.admission_id
  where x.operation_id=new.resource_id and d.payment_account_id=new.payment_account_id;
 if reservation is null or not owned_recovery_confirm_owns(reservation,new) then
  raise exception 'Invalid owned recovery confirmation ownership' using errcode='23514'; end if;
 if tg_op='INSERT' then
  perform lock_owned_recovery_confirm(reservation,true);db_at:=clock_timestamp();
  if not owned_recovery_confirm_pre(reservation) or new.status<>'RESERVED' or new.version<>0
   or num_nonnulls(new.provider_reference,new.first_dispatched_at,new.retry_before,new.lease_owner,new.lease_expires_at,new.safe_error_code)<>0
   or new.recovery_attempts<>0 or new.manual_review_required or new.next_attempt_at<>transaction_timestamp()
  then raise exception 'Invalid owned recovery confirmation reservation' using errcode='23514'; end if;
  return new;
 end if;
 if old.kind<>new.kind or row(new.id,new.payment_account_id,new.external_account_id,new.credentials_ref,new.resource_id,new.operation_key,new.fingerprint,
     new.recovery_attempts,new.next_attempt_at,new.safe_error_code,new.manual_review_required)
  is distinct from row(old.id,old.payment_account_id,old.external_account_id,old.credentials_ref,old.resource_id,old.operation_key,old.fingerprint,
     old.recovery_attempts,old.next_attempt_at,old.safe_error_code,old.manual_review_required)
 then raise exception 'Owned recovery confirmation identity is immutable' using errcode='23514'; end if;
 perform lock_owned_recovery_confirm(reservation,new.status<>'UNKNOWN');
 select * into receipt from stripe_billing_failed_recovery_confirm_claims where provider_operation=old.id;
 db_at:=clock_timestamp();
 if receipt.provider_operation is null or receipt.reservation_id<>reservation then
  raise exception 'Owned recovery confirmation claim required' using errcode='23514'; end if;
 if old.status='RESERVED' and old.version=0 and new.status='DISPATCHING' and new.version=1 then
  if receipt.recorded_transaction<>pg_current_xact_id() or not owned_recovery_confirm_pre(reservation)
   or row(new.first_dispatched_at,new.lease_owner,new.lease_expires_at) is distinct from row(receipt.claimed_at,receipt.claimant,receipt.deadline)
   or new.retry_before<>receipt.claimed_at+interval '23 hours' or new.provider_reference is not null
   or db_at>=receipt.deadline_at or db_at>=receipt.deadline
  then raise exception 'Invalid owned recovery confirmation first dispatch' using errcode='23514'; end if;
 elsif old.status='DISPATCHING' and old.version=1 and new.status in ('ACKNOWLEDGED','UNKNOWN') and new.version=2 then
  if receipt.recorded_transaction=pg_current_xact_id()
   or row(old.first_dispatched_at,old.lease_owner,old.lease_expires_at) is distinct from row(receipt.claimed_at,receipt.claimant,receipt.deadline)
   or row(new.first_dispatched_at,new.retry_before) is distinct from row(old.first_dispatched_at,old.retry_before)
   or new.lease_owner is not null or new.lease_expires_at is not null
  then raise exception 'Independently committed owned recovery confirmation claim required' using errcode='23514'; end if;
  if new.status='ACKNOWLEDGED' then
   if not owned_recovery_confirm_pre(reservation) or db_at>=receipt.deadline_at or db_at>=receipt.deadline
    or new.provider_reference is distinct from (select f.payment_intent_reference from stripe_billing_failed_method_reservations r
       join stripe_billing_failed_renewal_failures f on f.admission_id=r.admission_id where r.id=reservation)
   then raise exception 'Invalid owned recovery confirmation acknowledgment' using errcode='23514'; end if;
  elsif new.provider_reference is not null then raise exception 'Invalid owned recovery confirmation uncertainty' using errcode='23514'; end if;
 else raise exception 'Owned recovery confirmation cannot be reset or retried' using errcode='23514';
 end if;
 return new;
end $$;
-- Inspect OLD as well as NEW: kind conversion cannot escape this guard.
create trigger aaaa_owned_recovery_confirm_operation before insert or update or delete on provider_operations
 for each row execute function require_owned_recovery_confirm_operation();

create function require_owned_recovery_confirm_ack() returns trigger language plpgsql volatile as $$
declare receipt stripe_billing_failed_recovery_confirm_claims%rowtype; step provider_operations%rowtype; db_at timestamptz;
begin
 perform lock_owned_recovery_confirm(new.reservation_id,true);
 select * into step from provider_operations where id=new.provider_operation for update nowait;
 select * into receipt from stripe_billing_failed_recovery_confirm_claims where provider_operation=step.id;
 db_at:=clock_timestamp();
 if receipt.provider_operation is null or receipt.recorded_transaction=pg_current_xact_id()
  or not owned_recovery_confirm_owns(new.reservation_id,step) or not owned_recovery_confirm_pre(new.reservation_id)
  or step.status<>'ACKNOWLEDGED' or step.version<>2 or step.provider_reference<>new.payment_intent
  or row(new.reservation_id,new.claimant,new.deadline) is distinct from row(receipt.reservation_id,receipt.claimant,receipt.deadline)
  or new.observed_at<receipt.claimed_at or new.acknowledged_at>db_at or new.acknowledged_at>=receipt.deadline_at
  or db_at>=receipt.deadline_at or db_at>=receipt.deadline
  or not exists(select 1 from stripe_billing_failed_method_reservations r
    join stripe_billing_failed_renewal_failures f on f.admission_id=r.admission_id
    join stripe_billing_failed_method_completions c on c.reservation_id=r.id
    join subscription_billing_records b on b.id=f.renewal_bill where r.id=new.reservation_id
    and row(new.payment_intent,new.customer_reference,new.method_reference,new.currency,new.amount)
      =row(f.payment_intent_reference,r.binding_reference,c.method_reference,b.currency,b.amount_due_minor))
 then raise exception 'Invalid owned recovery confirmation response receipt' using errcode='23514'; end if;
 return new;
end $$;
create trigger owned_recovery_confirm_ack_proof before insert on stripe_billing_failed_recovery_confirm_acks
 for each row execute function require_owned_recovery_confirm_ack();

-- Deferred initial checks forbid orphan RESERVED0, same-transaction terminalization and ACK without its
-- exact OLD owner/deadline receipt. They validate identity/state, never re-sample time after valid precommit work.
create function check_owned_recovery_confirm_committed() returns trigger language plpgsql volatile as $$
declare selected uuid; step provider_operations%rowtype; receipt stripe_billing_failed_recovery_confirm_claims%rowtype;
begin
 if tg_table_name='provider_operations' then
  if new.kind<>'OWNED_RECOVERY_INTENT_CONFIRM' then return null;end if;selected:=new.id;
 else selected:=new.provider_operation;end if;
 select * into step from provider_operations where id=selected;
 select * into receipt from stripe_billing_failed_recovery_confirm_claims where provider_operation=selected;
 if step.id is null or receipt.provider_operation is null or not owned_recovery_confirm_owns(receipt.reservation_id,step)
 then raise exception 'Missing committed owned recovery confirmation claim' using errcode='23514';end if;
 if receipt.recorded_transaction=pg_current_xact_id() then
  if step.status<>'DISPATCHING' or step.version<>1
   or row(step.first_dispatched_at,step.lease_owner,step.lease_expires_at) is distinct from row(receipt.claimed_at,receipt.claimant,receipt.deadline)
   or exists(select 1 from stripe_billing_failed_recovery_confirm_acks where provider_operation=selected)
  then raise exception 'First owned recovery confirmation must commit dispatching' using errcode='23514';end if;
 elsif step.status='ACKNOWLEDGED' then
  if step.version<>2 or not exists(select 1 from stripe_billing_failed_recovery_confirm_acks ack where ack.provider_operation=selected
    and row(ack.reservation_id,ack.claimant,ack.deadline,ack.payment_intent)=row(receipt.reservation_id,receipt.claimant,receipt.deadline,step.provider_reference))
  then raise exception 'Owned recovery confirmation ACK receipt required' using errcode='23514';end if;
 elsif step.status='UNKNOWN' then
  if step.version<>2 or exists(select 1 from stripe_billing_failed_recovery_confirm_acks where provider_operation=selected)
  then raise exception 'Invalid terminal owned recovery confirmation state' using errcode='23514';end if;
 elsif step.status<>'DISPATCHING' or step.version<>1 then
  raise exception 'Invalid committed owned recovery confirmation state' using errcode='23514';
 end if;
 return null;
end $$;
create constraint trigger owned_recovery_confirm_provider_committed after insert or update on provider_operations
 deferrable initially deferred for each row execute function check_owned_recovery_confirm_committed();
create constraint trigger owned_recovery_confirm_claim_committed after insert on stripe_billing_failed_recovery_confirm_claims
 deferrable initially deferred for each row execute function check_owned_recovery_confirm_committed();
create constraint trigger owned_recovery_confirm_ack_committed after insert on stripe_billing_failed_recovery_confirm_acks
 deferrable initially deferred for each row execute function check_owned_recovery_confirm_committed();
