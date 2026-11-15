-- One explicit browser handoff on the existing unique admission. No backfill of historical transactions.
alter table stripe_billing_failed_method_reservations
 add column after_ready_id uuid, add column after_preparation_id uuid, add column after_phase integer, add column handoff_xid xid8,
 add constraint owned_grace_handoff_shape check(num_nonnulls(after_ready_id,after_preparation_id,after_phase,handoff_xid)=0
  or (num_nonnulls(after_ready_id,after_preparation_id,after_phase,handoff_xid)=4 and after_phase=3 and browser_session_id is not null)),
 add foreign key(after_ready_id,after_preparation_id,admission_id,after_phase)
  references stripe_billing_failed_grace_boundary_ready(id,preparation_id,admission_id,phase);

-- The V40 immutable clock/failure evidence is also valid after the selected METHOD changes debt.
-- Keep V40's resource-based original receipt available to its unchanged unpaid reader.
create function owned_grace_original_receipt(preparation uuid, utc timestamptz, reader xid8) returns boolean language sql stable set join_collapse_limit=1 as $$
 select exists(select 1 from stripe_billing_failed_grace_preparations p
 join stripe_billing_failed_renewals d on d.id=p.admission_id
 join stripe_billing_failed_renewal_failures f on f.admission_id=d.id
 join stripe_billing_first_renewal_ready predecessor on predecessor.id=d.ready_id and predecessor.admission_id=d.first_renewal_admission
 join provider_operations o on o.kind='OWNED_GRACE_CLOCK_ADVANCE' and o.resource_id=p.id
 join stripe_billing_failed_grace_claims q on q.preparation_id=p.id
 join stripe_billing_failed_grace_acks ack on ack.preparation_id=p.id
 join stripe_billing_failed_grace_ready ready on ready.preparation_id=p.id
 where p.id=$1
 and not exists(select 1 from provider_operations sibling
  where sibling.kind='OWNED_GRACE_CLOCK_ADVANCE' and sibling.resource_id=p.id and sibling.id<>o.id)
 and not exists(select 1 from stripe_billing_failed_grace_ready sibling
  where (sibling.preparation_id=p.id or sibling.admission_id=d.id) and sibling.id<>ready.id)
 and not exists(select 1 from stripe_billing_failed_grace_cleanup cleanup
  where cleanup.preparation_id=p.id or cleanup.operation_id=o.id)
 and owned_failed_grace_owns(p.id,o)
 and exists(select 1 from stripe_billing_failed_renewals current_d
  join stripe_billing_first_renewals current_a on current_a.id=current_d.first_renewal_admission
  join stripe_billing_owned_subscriptions current_i on current_i.id=current_a.owned_intent
  join stripe_billing_owned_subscription_receipts initial on initial.intent_id=current_i.id
  join stripe_billing_first_renewal_ready current_r on current_r.id=current_d.ready_id and current_r.admission_id=current_a.id
  join stripe_billing_first_renewal_steps current_step on current_step.id=current_r.step_id and current_step.admission_id=current_a.id
  join stripe_billing_first_renewal_boundary current_b on current_b.id=current_d.boundary_id and current_b.admission_id=current_a.id
  join stripe_billing_first_renewal_read_claims boundary_claim on boundary_claim.ready_id=current_b.ready_id and boundary_claim.admission_id=current_a.id
  join stripe_billing_failed_renewal_failures current_f on current_f.admission_id=current_d.id
  join stripe_billing_failed_renewal_reads current_q on current_q.id=current_f.claim_id and current_q.admission_id=current_d.id and current_q.phase='FAILED'
  where current_d.id=d.id and current_d.source_sha=current_a.source_sha
   and current_step.ordinal=2 and current_step.boundary_id=current_b.id and current_step.predecessor_ready=current_b.ready_id
   and current_step.target=current_r.frozen_time and current_d.admitted_at>=current_r.observed_at
   and current_f.recorded_at>=current_d.admitted_at and current_f.recorded_at>=current_r.observed_at and current_f.recorded_at<=$2
   and current_f.projected_revision=current_a.subscription_revision+1 and current_f.observation_sequence=current_q.observation_sequence+1
   and current_f.renewal_bill<>current_a.initial_bill and current_f.invoice_reference=current_b.invoice_reference and current_f.invoice_reference<>current_a.invoice_reference
   and current_f.allocation_reference<>current_a.allocation_reference and current_f.payment_intent_reference<>current_a.payment_intent_reference
   and row(current_f.period_start,current_f.period_end)=row(current_b.period_start,current_b.period_end)
   and current_f.grace_deadline=date_trunc('second',current_f.grace_deadline) and current_f.period_end=date_trunc('second',current_f.period_end)
   and extract(epoch from current_f.grace_deadline)-1>current_step.target::numeric
   and current_f.grace_deadline+interval '1 second'<current_f.period_end
   and extract(epoch from current_f.grace_deadline)+1-current_step.target::numeric<=case current_i.billing_interval when 'MONTH' then 56::numeric*86400 when 'YEAR' then 730::numeric*86400 else -1 end
   and current_q.captured_revision=current_a.subscription_revision
   and exists(select 1 from subscription_events e where e.subscription_id=current_d.subscription_id and e.billing_record_id=current_f.renewal_bill
    and e.event_type='RENEWAL_FAILED' and e.revision=current_f.projected_revision)
   and owned_first_renewal_identity(current_a.id)
   and not exists(select 1 from stripe_billing_failed_renewal_stops s where s.admission_id=current_d.id)
   and exists(select 1 from owned_first_renewal_step_evidence(current_a.id) step where step.ordinal=1 and step.ready_id=current_b.ready_id)
   and exists(select 1 from owned_first_renewal_step_evidence(current_a.id) step where step.ordinal=2 and step.ready_id=current_d.ready_id and step.step_id=current_step.id)
   and current_b.item_reference=initial.item_reference and current_b.period_start=to_timestamp(initial.period_end)
   and boundary_claim.captured_revision=current_a.subscription_revision and boundary_claim.selected_operation is null and boundary_claim.selected_version is null
   and current_b.observed_at>=boundary_claim.started_at and current_b.observed_at<boundary_claim.deadline_at
   and current_b.fingerprint=first_renewal_hash('gp:owned-first-renewal-boundary:v1',current_b.id::text,current_a.id::text,current_b.ready_id::text,
    current_b.item_reference,current_b.invoice_reference,first_renewal_instant(current_b.period_start),first_renewal_instant(current_b.period_end),first_renewal_instant(current_b.observed_at)))
 and p.admission_id=d.id and p.predecessor_ready=predecessor.id
 and p.prepared_at>=f.recorded_at and p.prepared_at>=predecessor.observed_at
 and p.prepared_at<=q.claimed_at and p.prepared_at<=$2
 and row(q.preparation_id,q.operation_id)=row(p.id,o.id)
 and row(ack.preparation_id,ack.operation_id,ack.invocation,ack.claimant,ack.deadline)
  =row(q.preparation_id,q.operation_id,q.invocation,q.claimant,q.deadline)
 and row(ready.preparation_id,ready.admission_id,ready.operation_id,ready.invocation)
  =row(p.id,d.id,q.operation_id,q.invocation)
 and q.recorded_transaction<>ack.recorded_transaction and q.recorded_transaction<>ready.recorded_transaction
 and ack.recorded_transaction<>ready.recorded_transaction
 and q.recorded_transaction<>$3 and ack.recorded_transaction<>$3
 and ready.recorded_transaction<>$3
 and o.status='ACKNOWLEDGED' and o.version=2 and o.provider_reference=predecessor.reference
 and o.first_dispatched_at=q.claimed_at and o.retry_before=q.claimed_at+interval '23 hours'
 and o.lease_owner is null and o.lease_expires_at is null and o.safe_error_code is null
 and o.recovery_attempts=0 and not o.manual_review_required
 and q.deadline_at=q.entered_at+interval '30 seconds' and q.deadline=q.claimed_at+interval '60 seconds'
 and q.entered_at<=q.claimed_at and q.claimed_at<q.deadline_at and q.claimed_at<=$2
 and ack.observed_at>=q.claimed_at and ack.acknowledged_at>=ack.observed_at
 and ack.acknowledged_at<q.deadline_at and ack.acknowledged_at<q.deadline and ack.acknowledged_at<=$2
 and ready.observed_at>=ack.acknowledged_at and ready.observed_at<=$2
 and ready.observed_at<q.deadline_at and ready.observed_at<q.deadline
 and row(ack.reference,ack.object_type,ack.livemode,ack.name,ack.provider_created,ack.provider_deletes_after)
  =row(predecessor.reference,predecessor.object_type,predecessor.livemode,predecessor.name,predecessor.provider_created,predecessor.provider_deletes_after)
 and row(ready.reference,ready.object_type,ready.livemode,ready.name,ready.provider_created,ready.provider_deletes_after)
  =row(ack.reference,ack.object_type,ack.livemode,ack.name,ack.provider_created,ack.provider_deletes_after)
 and ack.object_type='test_helpers.test_clock' and not ack.livemode
 and (ack.advancing_target is null or ack.advancing_target=p.target)
 and ((ack.status='ready' and ack.frozen_time=p.target)
  or (ack.status='advancing' and ack.frozen_time>=predecessor.frozen_time and ack.frozen_time<=p.target))
 and ready.status='ready' and ready.frozen_time=p.target
 and (ready.advancing_target is null or ready.advancing_target=p.target)
)
$$;

create or replace function owned_grace_original_committed(preparation uuid, utc timestamptz, reader xid8) returns boolean language sql stable as $$
 select owned_grace_original_receipt($1,$2,$3) and exists(select 1 from stripe_billing_failed_grace_preparations p
 join stripe_billing_failed_renewals d on d.id=p.admission_id where p.id=$1 and owned_failed_renewal_failure_current(d.id,null)
 and not exists(select 1 from stripe_billing_failed_method_reservations x where x.admission_id=d.id)
 and not exists(select 1 from stripe_billing_failed_renewal_recoveries x where x.admission_id=d.id)
 and not exists(select 1 from stripe_billing_failed_renewal_publications x where x.admission_id=d.id)
 and not exists(select 1 from stripe_billing_failed_renewal_stops x where x.admission_id=d.id)
 and not exists(select 1 from subscription_operations x where x.subscription_id=d.subscription_id
  and x.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN') and x.kind<>'RECONCILE'))
$$;

create function owned_grace_original_history(preparation uuid, utc timestamptz, reader xid8) returns boolean language sql stable as $$
 select owned_grace_original_receipt($1,$2,$3)
 and not exists(select 1 from stripe_billing_failed_grace_preparations p join stripe_billing_failed_grace_claims q on q.preparation_id=p.id
  join provider_operations other on other.operation_key=p.operation_key and other.id<>q.operation_id where p.id=$1)
$$;

create function owned_grace_history_committed(preparation uuid, utc timestamptz, reader xid8) returns boolean language sql stable as $$
 select owned_grace_original_history($1,$2,$3) and owned_grace_boundary_receipt($1,2,$2,$3) and owned_grace_boundary_receipt($1,3,$2,$3)
$$;

-- Keep insertion provenance outside subscription_operations: earlier immutable V35
-- snapshots compare to_jsonb(operation) and must retain their original row shape.
create table stripe_billing_failed_grace_setup_origins (
 setup_operation uuid primary key references subscription_operations(id),
 admission_id uuid not null, after_ready_id uuid not null, after_preparation_id uuid not null,
 after_phase integer not null check(after_phase=3), producer_xid xid8 not null,
 foreign key(after_ready_id,after_preparation_id,admission_id,after_phase)
  references stripe_billing_failed_grace_boundary_ready(id,preparation_id,admission_id,phase)
);

-- The sole nested INSERT is capture_owned_grace_setup_origin, on actual operation INSERT.
-- No default, caller-supplied xid, UPDATE, conflict/reload, or pre-V41 backfill mints an origin.
create function stamp_owned_grace_setup_origin() returns trigger language plpgsql volatile as $$
begin
 if pg_trigger_depth()<>2 or new.producer_xid is not null then
  raise exception 'SETUP origin requires original insertion' using errcode='23514';end if;
 new.producer_xid:=pg_current_xact_id();return new;
end $$;
create trigger grace_setup_origin_insert before insert on stripe_billing_failed_grace_setup_origins
 for each row execute function stamp_owned_grace_setup_origin();
create trigger grace_setup_origin_immutable before update or delete on stripe_billing_failed_grace_setup_origins
 for each row execute function protect_failed_renewal_evidence();

create function capture_owned_grace_setup_origin() returns trigger language plpgsql volatile as $$
declare after_row stripe_billing_failed_grace_boundary_ready%rowtype;
begin
 select b.* into after_row from stripe_billing_failed_renewals d
 join stripe_billing_failed_renewal_failures f on f.admission_id=d.id
 join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
 join stripe_billing_failed_grace_boundary_ready b on b.admission_id=d.id and b.phase=3
 join subscription_operations inserted on inserted.id=new.id and inserted.subscription_id=d.subscription_id
 where (d.site_id,d.customer_id,d.subscription_id)=(new.site_id,new.customer_id,new.subscription_id)
 and new.authority_scope='owned-failed-renewal-method:'||d.id::text
 and new.provider_operation_key='subscription:'||d.payment_account_id::text||':'||new.id::text||':SETUP'
 and new.expected_revision=f.projected_revision and new.policy_version=a.policy_version
 and new.provider_reference is null and new.safe_failure_code is null
 and b.observed_at<=new.created_at and new.created_at<=clock_timestamp()
 and owned_grace_history_committed(b.preparation_id,clock_timestamp(),pg_current_xact_id())
 for key share of inserted,b nowait;
 if found then
  insert into stripe_billing_failed_grace_setup_origins(setup_operation,admission_id,after_ready_id,after_preparation_id,after_phase)
  values(new.id,after_row.admission_id,after_row.id,after_row.preparation_id,after_row.phase);
 end if;return null;
end $$;
create trigger grace_setup_origin after insert on subscription_operations for each row
 when(new.kind='SETUP' and new.status='RESERVED' and new.version=0 and new.authority_scope like 'owned-failed-renewal-method:%')
 execute function capture_owned_grace_setup_origin();

create function owned_grace_setup_inserted(r stripe_billing_failed_method_reservations) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_grace_setup_origins origin
 where (origin.setup_operation,origin.admission_id,origin.after_ready_id,origin.after_preparation_id,origin.after_phase,origin.producer_xid)
 =(($1).setup_operation,($1).admission_id,($1).after_ready_id,($1).after_preparation_id,($1).after_phase,($1).handoff_xid))
$$;

create function owned_grace_successor_entry(preparation uuid,utc timestamptz,reader xid8) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_grace_preparations p join stripe_billing_failed_renewals d on d.id=p.admission_id
 where p.id=$1 and owned_grace_history_committed(p.id,$2,$3) and owned_failed_renewal_failure_current(d.id,null)
 and not exists(select 1 from stripe_billing_failed_method_reservations r where r.admission_id=d.id)
 and not exists(select 1 from stripe_billing_failed_renewal_recoveries x where x.admission_id=d.id)
 and not exists(select 1 from stripe_billing_failed_renewal_publications x where x.admission_id=d.id)
 and not exists(select 1 from subscription_operations x where x.subscription_id=d.subscription_id
  and x.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN') and x.kind<>'RECONCILE'))
$$;

-- Historical SETUP/session enrollment; current customer_sessions is required only during entry/management.
create function owned_grace_handoff_link(r stripe_billing_failed_method_reservations, utc timestamptz, reader xid8) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_grace_boundary_ready b
 join stripe_billing_failed_renewals d on d.id=b.admission_id
 join subscription_operations o on o.id=($1).setup_operation
 where (b.id,b.preparation_id,b.admission_id,b.phase)=(($1).after_ready_id,($1).after_preparation_id,($1).admission_id,3)
 and ($1).after_phase=3 and ($1).browser_session_id is not null and ($1).browser_session_expires_at>($1).reserved_at
 and b.observed_at<=o.created_at and o.created_at<=($1).reserved_at and ($1).reserved_at<=$2
 and o.authority_scope='owned-failed-renewal-method:'||d.id::text and o.key_digest ~ '^[a-f0-9]{64}$'
 and ($1).request_fingerprint=owned_method_browser_request(d.id,($1).browser_origin)
 and o.fingerprint=($1).request_fingerprint
 and owned_grace_history_committed(b.preparation_id,$2,$3) and owned_failed_renewal_authority(d.id)
 and not exists(select 1 from stripe_billing_failed_renewal_recoveries x where x.admission_id=d.id)
 and not exists(select 1 from stripe_billing_failed_renewal_publications x where x.admission_id=d.id))
$$;

create function owned_grace_successor_identity(reservation uuid,utc timestamptz,reader xid8) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r where r.id=$1 and r.handoff_xid<>$3
 and owned_grace_setup_inserted(r) and owned_method_identity(r.id) and owned_grace_handoff_link(r,$2,$3)
 and not exists(select 1 from stripe_billing_failed_grace_claims q join stripe_billing_failed_grace_acks a using(preparation_id)
  join stripe_billing_failed_grace_ready b using(preparation_id) where q.preparation_id=r.after_preparation_id
  and r.handoff_xid in(q.recorded_transaction,a.recorded_transaction,b.recorded_transaction))
 and not exists(select 1 from stripe_billing_failed_grace_boundary_claims q join stripe_billing_failed_grace_boundary_acks a using(preparation_id,phase)
  join stripe_billing_failed_grace_boundary_ready b using(preparation_id,phase) where q.preparation_id=r.after_preparation_id
  and r.handoff_xid in(q.producer_xid,a.producer_xid,b.producer_xid)))
$$;

-- Write-local completion only. It is deliberately excluded from downstream authority.
create function owned_grace_handoff_prestate(r stripe_billing_failed_method_reservations,utc timestamptz,reader xid8)
 returns boolean language sql stable set join_collapse_limit=1 as $$
 select owned_grace_handoff_link($1,$2,$3) and owned_failed_renewal_failure_current(($1).admission_id,($1).setup_operation)
 and exists(select 1 from stripe_billing_failed_renewals d join subscription_operations o on o.id=($1).setup_operation
 join customer_sessions c on c.id=($1).browser_session_id join site_allowed_origins allowed on allowed.site_id=d.site_id and allowed.origin=c.origin
 where d.id=($1).admission_id and (c.site_id,c.customer_id,c.origin,c.expires_at)=(d.site_id,d.customer_id,($1).browser_origin,($1).browser_session_expires_at)
 and c.revoked_at is null and c.issued_at<=($1).reserved_at and $2<c.expires_at and c.scopes ? 'SUBSCRIPTION_MANAGE'
 and to_jsonb(o)=($1).operation_snapshot and o.status='RESERVED' and o.version=0 and o.kind='SETUP'
 and (o.site_id,o.customer_id,o.subscription_id)=(d.site_id,d.customer_id,d.subscription_id)
 and not exists(select 1 from subscription_operations other where other.subscription_id=d.subscription_id and other.id<>o.id
  and other.kind<>'RECONCILE' and other.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN'))
 and not exists(select 1 from provider_operations step where step.resource_id=o.id or step.operation_key in(o.provider_operation_key||':SETUP',o.provider_operation_key||':METHOD'))
 and not exists(select 1 from stripe_subscription_setup_proofs proof where proof.operation_id=o.id))
$$;

create function owned_grace_handoff_current(reservation uuid,utc timestamptz,reader xid8) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r where r.id=$1 and r.handoff_xid=$3
 and owned_grace_setup_inserted(r) and owned_method_identity(r.id) and owned_grace_handoff_prestate(r,$2,$3))
$$;

-- Both branches keep the V39 exclusion. Only an explicit, fully authenticated tagged row
-- may pass the old firstclaim. V36 browser and V35 reservation triggers still run afterwards.
create or replace function exclude_failed_method_branch() returns trigger language plpgsql volatile as $$
declare upstream uuid;
begin
 if tg_table_name='stripe_billing_failed_method_reservations' then
  if num_nonnulls(new.after_ready_id,new.after_preparation_id,new.after_phase,new.handoff_xid)>0 then
   if new.handoff_xid is not null and new.handoff_xid<>pg_current_xact_id() then
    raise exception 'Invalid handoff transaction' using errcode='23514';end if;
   new.handoff_xid:=pg_current_xact_id();
   if not owned_grace_setup_inserted(new) then
    raise exception 'Handoff requires original SETUP insertion' using errcode='23514';end if;
   if not owned_grace_handoff_prestate(new,clock_timestamp(),pg_current_xact_id()) then
    raise exception 'Invalid handoff predecessor' using errcode='23514';end if;
   perform lock_owned_grace_boundary(new.after_preparation_id,3,false);
   -- These are the three remaining immediate METHOD FK targets outside the V40 lock set.
   -- Resolve the binding and creation operation through the authenticated original intent.
   perform b.id from stripe_billing_failed_renewals d join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
    join stripe_billing_owned_subscriptions i on i.id=a.owned_intent join stripe_billing_customers b on b.id=i.customer_binding
    join provider_operations step on step.id=b.creation_operation_id
    where d.id=new.admission_id and b.id=new.binding_id and step.id=new.binding_creation_operation
    and step.id=i.customer_creation_operation for key share of b,step nowait;
   if not found then raise exception 'Invalid handoff binding' using errcode='23514';end if;
   perform c.id from customer_sessions c join stripe_billing_failed_renewals d on d.id=new.admission_id
    where c.id=new.browser_session_id and (c.site_id,c.customer_id,c.origin,c.expires_at)=(d.site_id,d.customer_id,new.browser_origin,new.browser_session_expires_at)
    for share of c nowait;
   if not found or not owned_grace_handoff_prestate(new,clock_timestamp(),pg_current_xact_id()) then
    raise exception 'Handoff authority changed' using errcode='23514';end if;
   return new;
  end if;
 end if;
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

create function check_owned_grace_handoff() returns trigger language plpgsql volatile as $$
begin
 if not owned_grace_handoff_current(new.id,clock_timestamp(),pg_current_xact_id()) then
  raise exception 'Handoff authority changed before commit' using errcode='23514';end if;
 return null;
end $$;
create constraint trigger grace_handoff_committed after insert on stripe_billing_failed_method_reservations
 deferrable initially deferred for each row when(new.after_ready_id is not null) execute function check_owned_grace_handoff();

create or replace function owned_method_lineage(target uuid) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r where r.id=$1
 and case when r.after_ready_id is null then
  owned_method_identity(r.id) and owned_failed_renewal_authority(r.admission_id)
  and not exists(select 1 from stripe_billing_failed_renewal_recoveries where admission_id=r.admission_id)
  and not exists(select 1 from stripe_billing_failed_grace_claims q join stripe_billing_failed_grace_preparations p on p.id=q.preparation_id where p.admission_id=r.admission_id)
 else owned_grace_successor_identity(r.id,statement_timestamp(),pg_current_xact_id())
  and not exists(select 1 from stripe_billing_failed_renewals d join subscription_operations o on o.subscription_id=d.subscription_id
   where d.id=r.admission_id and o.kind='PORTAL' and o.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN'))
 end)
$$;

-- Keep the complete V37 proof and untagged V39 exclusion. Tagged RECOVER is initially
-- prospective during the V35 BEFORE trigger; the saved association pins every later use.
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
 and ((not exists(select 1 from stripe_billing_failed_method_reservations tagged where tagged.admission_id=d.id and tagged.after_ready_id is not null)
   and not exists(select 1 from stripe_billing_failed_grace_claims q join stripe_billing_failed_grace_preparations grace
       on grace.id=q.preparation_id where grace.admission_id=d.id))
  or exists(select 1 from stripe_billing_failed_method_reservations r
   join stripe_billing_failed_method_completions c on c.reservation_id=r.id
   left join stripe_billing_failed_method_recoveries x on x.reservation_id=r.id
   where r.admission_id=d.id and r.after_ready_id is not null and owned_method_lineage(r.id)
   and owned_method_completed(r.id) and owned_method_browser_permit(r.setup_operation)
   and o.created_at>=c.completed_at and o.key_digest=first_renewal_hash('owned-failed-renewal-recover-key-v1',d.id::text)
   and o.fingerprint='v1.recover.'||f.renewal_bill::text||'.'||first_renewal_hash('owned-failed-renewal-recover-v1',d.id::text,
     f.renewal_bill::text,f.bill_version::text,f.invoice_reference,f.allocation_reference,f.payment_intent_reference,f.projected_revision::text)
   and not exists(select 1 from provider_operations sibling
    where ((sibling.resource_id=o.id and sibling.kind='OWNED_RECOVERY_INTENT_CONFIRM') or sibling.operation_key=o.provider_operation_key||':CONFIRM')
    and not owned_recovery_confirm_owns(r.id,sibling))
   and not exists(select 1 from provider_operations pay where pay.kind='INVOICE_PAY' and pay.resource_id=o.id)
   and ((x.operation_id is null and o.status='RESERVED' and o.version=0 and owned_method_debt(r.id,1,o.id))
     or (x.operation_id=o.id and owned_recovery_confirm_identity(r.id))))))
$$;

-- Original provider WRITE claims. These survive ACK clearing lease_owner, without storing the preimage.
create table stripe_billing_failed_grace_method_claims (
 operation_id uuid primary key references provider_operations(id),
 reservation_id uuid not null references stripe_billing_failed_method_reservations(id),
 setup_operation uuid not null references subscription_operations(id),
 payment_account_id uuid not null references payment_accounts(id),
 step_kind varchar(32) not null check(step_kind in ('SETUP_INTENT_CREATE','SUBSCRIPTION_UPDATE')),
 operation_key varchar(255) not null, fingerprint varchar(255) not null,
 external_account_id varchar(255) not null, credentials_ref varchar(255) not null,
 first_dispatched_at timestamptz not null, lease_expires_at timestamptz not null,
 retry_before timestamptz not null, claim_version bigint not null check(claim_version=1),
 successor_owner_digest text not null check(successor_owner_digest ~ '^[a-f0-9]{64}$'),
 successor_producer_xid xid8 not null, unique(reservation_id,step_kind),
 check(lease_expires_at=first_dispatched_at+interval '60 seconds' and retry_before=first_dispatched_at+interval '23 hours')
);
create function stamp_owned_grace_method_claim() returns trigger language plpgsql volatile as $$
begin
 if pg_trigger_depth()<>2 or new.successor_producer_xid is not null then
  raise exception 'Original provider dispatch required' using errcode='23514';end if;
 new.successor_producer_xid:=pg_current_xact_id();return new;
end $$;
create trigger grace_method_claim_insert before insert on stripe_billing_failed_grace_method_claims
 for each row execute function stamp_owned_grace_method_claim();
create trigger grace_method_claim_immutable before update or delete on stripe_billing_failed_grace_method_claims
 for each row execute function protect_failed_renewal_evidence();

-- Immutable lookup usable during cleanup even after positive authority has expired.
create function owned_grace_write_claim_matches(reservation uuid,p provider_operations) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_grace_method_claims q where q.operation_id=($2).id and q.reservation_id=$1
 and (q.setup_operation,q.payment_account_id,q.step_kind,q.operation_key,q.fingerprint,q.external_account_id,q.credentials_ref,
      q.first_dispatched_at,q.retry_before)
 =(($2).resource_id,($2).payment_account_id,($2).kind,($2).operation_key,($2).fingerprint,($2).external_account_id,($2).credentials_ref,
      ($2).first_dispatched_at,($2).retry_before)
 and ((($2).status='DISPATCHING' and (($2).lease_owner,($2).lease_expires_at,($2).version)=(q.successor_owner_digest,q.lease_expires_at,q.claim_version))
   or (($2).status in ('ACKNOWLEDGED','UNKNOWN') and ($2).version=q.claim_version+1 and ($2).lease_owner is null and ($2).lease_expires_at is null)))
$$;

create function owned_grace_write_current(reservation uuid,p provider_operations,utc timestamptz) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r join stripe_billing_failed_renewals d on d.id=r.admission_id
 join subscription_operations o on o.id=r.setup_operation join payment_accounts a on a.id=d.payment_account_id
 join customer_sessions s on s.id=r.browser_session_id join site_allowed_origins origin on origin.site_id=d.site_id and origin.origin=s.origin
 where r.id=$1 and r.after_ready_id is not null and owned_method_debt(r.id,0,o.id) and owned_method_initial_operation(r.id)
 and (s.site_id,s.customer_id,s.origin,s.expires_at)=(d.site_id,d.customer_id,r.browser_origin,r.browser_session_expires_at)
 and s.revoked_at is null and s.issued_at<=r.reserved_at and $3<s.expires_at and s.scopes ? 'SUBSCRIPTION_MANAGE'
 and (a.provider,a.account_mode,a.account_status)=('STRIPE','SANDBOX','ACTIVE')
 and (($2).payment_account_id,($2).external_account_id,($2).credentials_ref,($2).resource_id)=(a.id,a.external_account_id,a.credentials_ref,o.id)
 and ($2).operation_key=o.provider_operation_key||case when ($2).kind='SETUP_INTENT_CREATE' then ':SETUP' else ':METHOD' end
 and ($2).fingerprint=owned_method_step_fingerprint(r.id,($2).kind='SUBSCRIPTION_UPDATE')
 and ((($2).kind='SETUP_INTENT_CREATE' and not exists(select 1 from stripe_subscription_setup_proofs where operation_id=o.id))
 or (($2).kind='SUBSCRIPTION_UPDATE' and owned_method_browser_permit(o.id) and exists(
  select 1 from stripe_subscription_setup_proofs proof join provider_operations step on step.id=proof.step_id
  join stripe_billing_failed_grace_method_claims q on q.operation_id=step.id
  where proof.operation_id=o.id and proof.method_reference ~ '^pm_[A-Za-z0-9]{1,252}$' and proof.setup_reference=step.provider_reference
   and step.status='ACKNOWLEDGED' and step.kind='SETUP_INTENT_CREATE' and owned_grace_write_claim_matches(r.id,step)
   and q.successor_producer_xid<>pg_current_xact_id()))))
$$;

-- Raw transitions also obey the original typed phase. A first dispatch is recorded by this
-- actual row transition; an existing lease, receipt or version can never be adopted or reset.
-- The terminal takes FOR UPDATE on this exact reservation. A fresh RC statement after
-- acquiring this conflicting lock observes any terminal that won before the lock.
create function require_live_grace_successor(reservation uuid) returns void language plpgsql volatile as $$
begin
 perform id from stripe_billing_failed_method_reservations where id=$1 and after_ready_id is not null for key share nowait;
 if not found or current_setting('transaction_isolation')<>'read committed' or exists(
  select 1 from stripe_billing_failed_renewal_stops s join stripe_billing_failed_method_reservations r on r.admission_id=s.admission_id where r.id=$1)
 then raise exception 'Live grace successor required' using errcode='23514';end if;
end $$;

create function gate_owned_grace_method_write() returns trigger language plpgsql volatile as $$
declare target uuid; utc timestamptz;
begin
 if (tg_op='INSERT' or old.kind not in ('SETUP_INTENT_CREATE','SUBSCRIPTION_UPDATE'))
    and (tg_op='DELETE' or new.kind not in ('SETUP_INTENT_CREATE','SUBSCRIPTION_UPDATE')) then return null;end if;
 if tg_op<>'INSERT' then
  select r.id into target from stripe_billing_failed_method_reservations r where r.setup_operation=old.resource_id
    and r.after_ready_id is not null and old.kind in ('SETUP_INTENT_CREATE','SUBSCRIPTION_UPDATE');
 end if;
 if target is null and tg_op<>'DELETE' then
  select r.id into target from stripe_billing_failed_method_reservations r where r.setup_operation=new.resource_id
    and r.after_ready_id is not null and new.kind in ('SETUP_INTENT_CREATE','SUBSCRIPTION_UPDATE');
 end if;
 if target is null then return null;end if;
 if tg_op='INSERT' or (tg_op='UPDATE' and new.status in ('DISPATCHING','ACKNOWLEDGED')) then
  perform require_live_grace_successor(target);
 end if;
 utc:=clock_timestamp();
 if tg_op='INSERT' then
  if new.status='RESERVED' and new.version=0 and new.recovery_attempts=0 and new.safe_error_code is null and not new.manual_review_required
    and owned_grace_write_current(target,new,utc) then return null;end if;
 elsif tg_op='UPDATE' and (to_jsonb(new)-array['status','version','lease_owner','lease_expires_at','first_dispatched_at','retry_before','provider_reference'])
     =(to_jsonb(old)-array['status','version','lease_owner','lease_expires_at','first_dispatched_at','retry_before','provider_reference']) then
  if old.status='RESERVED' and old.version=0 and new.status='DISPATCHING' and new.version=1 and new.first_dispatched_at<=utc
    and new.first_dispatched_at>=(select reserved_at from stripe_billing_failed_method_reservations where id=target)
    and utc<new.lease_expires_at and owned_grace_write_current(target,new,utc) then
   insert into stripe_billing_failed_grace_method_claims(operation_id,reservation_id,setup_operation,payment_account_id,step_kind,
    operation_key,fingerprint,external_account_id,credentials_ref,first_dispatched_at,lease_expires_at,retry_before,claim_version,successor_owner_digest)
   values(new.id,target,new.resource_id,new.payment_account_id,new.kind,new.operation_key,new.fingerprint,new.external_account_id,
    new.credentials_ref,new.first_dispatched_at,new.lease_expires_at,new.retry_before,new.version,new.lease_owner);return null;
  elsif old.status='DISPATCHING' and new.status='UNKNOWN' and owned_grace_write_claim_matches(target,old)
    and owned_grace_write_claim_matches(target,new) and exists(select 1 from stripe_billing_failed_grace_successor_terminals t
     where t.reservation_id=target and t.origin_id=new.id and t.origin in ('SETUP_WRITE','METHOD_WRITE')
     and t.successor_owner_digest=old.lease_owner and t.successor_producer_xid=pg_current_xact_id()) then return null;
  elsif old.status='DISPATCHING' and new.status='ACKNOWLEDGED' and utc<old.lease_expires_at
    and owned_grace_write_claim_matches(target,old) and owned_grace_write_claim_matches(target,new)
    and exists(select 1 from stripe_billing_failed_grace_method_claims q where q.operation_id=old.id and q.successor_producer_xid<>pg_current_xact_id())
    and owned_grace_write_current(target,new,utc)
    and ((new.kind='SETUP_INTENT_CREATE' and new.provider_reference ~ '^seti_[A-Za-z0-9]{1,250}$') or
     (new.kind='SUBSCRIPTION_UPDATE' and exists(select 1 from stripe_billing_failed_method_reservations r
      join stripe_billing_failed_renewals d on d.id=r.admission_id join subscriptions s on s.id=d.subscription_id
      where r.id=target and s.provider_reference=new.provider_reference))) then
   -- Transaction-lifetime denial marker on this original tagged ACK. A released
   -- savepoint retains the lock; rollback of the ACK also rolls back its marker.
   if not pg_try_advisory_xact_lock_shared(1499220551,hashtext(new.id::text)) then
    raise exception 'Original WRITE acknowledgment contended' using errcode='55P03';end if;
   return null;
  end if;
 end if;
 raise exception 'Original grace provider write unavailable' using errcode='23514';
end $$;
create trigger grace_method_write after insert or update or delete on provider_operations
 for each row execute function gate_owned_grace_method_write();

-- Only tagged emissions receive provenance. Descriptor rows and old receipt snapshots stay intact.
alter table stripe_billing_failed_method_reads add successor_owner_digest text check(successor_owner_digest ~ '^[a-f0-9]{64}$'),
 add successor_producer_xid xid8;
alter table stripe_billing_failed_method_completions add successor_producer_xid xid8;
alter table stripe_billing_failed_method_recoveries add successor_producer_xid xid8;
alter table stripe_billing_failed_method_publications add successor_producer_xid xid8;
alter table stripe_billing_failed_recovery_confirm_claims add successor_owner_digest text check(successor_owner_digest ~ '^[a-f0-9]{64}$');
alter table stripe_billing_failed_recovery_confirm_acks add successor_producer_xid xid8;

-- This backend's tagged ACK marker lasts until transaction end, including after
-- savepoint release. Presence can only refuse consumption of the original ACK.
create function owned_grace_local_write_ack(operation uuid) returns boolean language sql volatile strict as $$
 select exists(select 1 from pg_locks where locktype='advisory' and pid=pg_backend_pid() and granted
   and database=(select oid from pg_database where datname=current_database()) and objsubid=2
   and classid=1499220551::oid and objid=hashtext($1::text)::oid)
$$;

create function owned_grace_method_writes_acked(reservation uuid,reader xid8) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r join subscription_operations o on o.id=r.setup_operation
 join stripe_subscription_setup_proofs proof on proof.operation_id=o.id
 join stripe_billing_failed_grace_method_claims a on a.reservation_id=r.id and a.step_kind='SETUP_INTENT_CREATE'
 join stripe_billing_failed_grace_method_claims b on b.reservation_id=r.id and b.step_kind='SUBSCRIPTION_UPDATE'
 join provider_operations setup on setup.id=a.operation_id join provider_operations method on method.id=b.operation_id
 where r.id=$1 and r.after_ready_id is not null and owned_method_steps(r.id) and owned_method_browser_permit(o.id)
 and proof.step_id=setup.id and setup.status='ACKNOWLEDGED' and method.status='ACKNOWLEDGED'
 and owned_grace_write_claim_matches(r.id,setup) and owned_grace_write_claim_matches(r.id,method)
 and a.successor_producer_xid<>b.successor_producer_xid and $2 not in(a.successor_producer_xid,b.successor_producer_xid)
 and not exists(select 1 from provider_operations sibling where
  ((sibling.resource_id=o.id and sibling.kind in ('SETUP_INTENT_CREATE','SUBSCRIPTION_UPDATE')) or
    sibling.operation_key in(o.provider_operation_key||':SETUP',o.provider_operation_key||':METHOD'))
  and sibling.id not in(setup.id,method.id)))
$$;

-- Only a producer about to consume these ACKs needs this backend's live marker.
-- Historical readers use the sealed rows and their already guarded READ origin.
create function owned_grace_method_writes_consumable(reservation uuid,reader xid8) returns boolean language sql volatile as $$
 select owned_grace_method_writes_acked($1,$2) and not exists(
  select 1 from stripe_billing_failed_grace_method_claims q
  where q.reservation_id=$1 and owned_grace_local_write_ack(q.operation_id))
$$;

-- This predicate may be used after financial projection; do not re-enter V37's pre-confirm debt state.
create function owned_grace_confirm_ack_committed(reservation uuid,latest timestamptz,reader xid8) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r join stripe_billing_failed_renewal_failures f on f.admission_id=r.admission_id
 join stripe_billing_failed_method_completions c on c.reservation_id=r.id
 join stripe_billing_failed_method_reads m on (m.id,m.reservation_id,m.phase)=(c.claim_id,r.id,'METHOD')
 join stripe_billing_failed_method_recoveries x on x.reservation_id=r.id join subscription_operations o on o.id=x.operation_id
 join stripe_billing_failed_recovery_confirm_claims q on q.reservation_id=r.id join provider_operations step on step.id=q.provider_operation
 join stripe_billing_failed_recovery_confirm_acks a on a.provider_operation=q.provider_operation and a.reservation_id=q.reservation_id
 join subscription_billing_records bill on bill.id=f.renewal_bill
 where r.id=$1 and r.after_ready_id is not null and owned_recovery_confirm_owns(r.id,step)
 and owned_failed_renewal_association(r.admission_id,o.id) and owned_method_completed(r.id)
 and q.successor_owner_digest is not null and m.successor_owner_digest is not null
 and num_nulls(m.successor_producer_xid,c.successor_producer_xid,x.successor_producer_xid,a.successor_producer_xid)=0
 and $3 not in(m.successor_producer_xid,c.successor_producer_xid,x.successor_producer_xid,q.recorded_transaction,a.successor_producer_xid)
 and x.successor_producer_xid<>q.recorded_transaction and q.recorded_transaction<>a.successor_producer_xid
 and c.completed_at<=x.associated_at and x.associated_at<=q.claimed_at
 and q.entered_at<=q.claimed_at and q.claimed_at<=a.observed_at and a.observed_at<=a.acknowledged_at
 and a.acknowledged_at<least(q.deadline_at,q.deadline) and a.acknowledged_at<=$2
 and step.status='ACKNOWLEDGED' and step.version=2 and step.lease_owner is null and step.lease_expires_at is null
 and step.first_dispatched_at=q.claimed_at and step.retry_before=q.claimed_at+interval '23 hours'
 and (a.claimant,a.deadline,a.payment_intent,a.customer_reference,a.method_reference,a.currency,a.amount,a.received,a.capturable)
 =(q.claimant,q.deadline,f.payment_intent_reference,r.binding_reference,c.method_reference,bill.currency,bill.amount_due_minor,bill.amount_due_minor,0::bigint)
 and step.provider_reference=a.payment_intent and q.invocation is not null
 and not exists(select 1 from provider_operations sibling where sibling.id<>step.id and
    ((sibling.kind='OWNED_RECOVERY_INTENT_CONFIRM' and sibling.resource_id=o.id) or sibling.operation_key=o.provider_operation_key||':CONFIRM')))
$$;
create function owned_grace_read_predecessor(reservation uuid,phase text,started timestamptz,reader xid8) returns boolean language sql stable as $$
 select owned_grace_method_writes_acked($1,$4) and ($2='METHOD' or ($2='RECOVERED' and owned_grace_confirm_ack_committed($1,$3,$4)))
$$;

create function stamp_owned_grace_read_origin() returns trigger language plpgsql volatile as $$
declare tagged boolean;
begin
 select after_ready_id is not null into strict tagged from stripe_billing_failed_method_reservations where id=new.reservation_id;
 if (new.successor_owner_digest is not null)<>tagged or new.successor_producer_xid is not null then
  raise exception 'Invalid grace read provenance' using errcode='23514';end if;
 if tagged then
  perform require_live_grace_successor(new.reservation_id);
  if not owned_grace_method_writes_consumable(new.reservation_id,pg_current_xact_id())
    or (new.phase='RECOVERED' and not owned_grace_confirm_ack_committed(new.reservation_id,new.started_at,pg_current_xact_id())) then
   raise exception 'Missing committed grace read predecessor' using errcode='23514';end if;
  new.successor_producer_xid:=pg_current_xact_id();
 end if;
 return new;
end $$;
create trigger grace_read_origin before insert on stripe_billing_failed_method_reads for each row execute function stamp_owned_grace_read_origin();

create function stamp_owned_grace_read_receipt() returns trigger language plpgsql volatile as $$
declare tagged boolean; q stripe_billing_failed_method_reads%rowtype; reader xid8:=pg_current_xact_id();
begin
 select after_ready_id is not null into strict tagged from stripe_billing_failed_method_reservations where id=new.reservation_id;
 if new.successor_producer_xid is not null then raise exception 'Producer xid is assigned by the database' using errcode='23514';end if;
 if not tagged then return new;end if;
 perform require_live_grace_successor(new.reservation_id);
 if tg_table_name='stripe_billing_failed_method_recoveries' then
  if not exists(select 1 from stripe_billing_failed_method_completions c where c.reservation_id=new.reservation_id and c.successor_producer_xid<>reader)
   then raise exception 'Original committed METHOD completion required' using errcode='23514';end if;
 else
  select * into strict q from stripe_billing_failed_method_reads where id=new.claim_id and reservation_id=new.reservation_id;
  if q.successor_producer_xid is null or q.successor_producer_xid=reader or q.successor_owner_digest is null
    or not owned_grace_read_predecessor(q.reservation_id,q.phase,q.started_at,reader)
  then raise exception 'Original committed grace read required' using errcode='23514';end if;
 end if;
 new.successor_producer_xid:=reader;return new;
end $$;
create trigger grace_method_receipt before insert on stripe_billing_failed_method_completions for each row execute function stamp_owned_grace_read_receipt();
create trigger grace_associated_receipt before insert on stripe_billing_failed_method_recoveries for each row execute function stamp_owned_grace_read_receipt();
create trigger grace_publication_receipt before insert on stripe_billing_failed_method_publications for each row execute function stamp_owned_grace_read_receipt();

create function require_owned_grace_confirm_origin() returns trigger language plpgsql volatile as $$
declare tagged boolean;
begin
 select after_ready_id is not null into strict tagged from stripe_billing_failed_method_reservations where id=new.reservation_id;
 if tagged then perform require_live_grace_successor(new.reservation_id);end if;
 if tg_table_name='stripe_billing_failed_recovery_confirm_claims' then
  if (new.successor_owner_digest is not null)<>tagged or (tagged and not exists(select 1 from stripe_billing_failed_method_recoveries x
     where x.reservation_id=new.reservation_id and x.successor_producer_xid<>pg_current_xact_id())) then
   raise exception 'Original grace confirmation owner required' using errcode='23514';end if;
 else
  if new.successor_producer_xid is not null then raise exception 'Invalid grace confirmation producer' using errcode='23514';end if;
  if tagged then new.successor_producer_xid:=pg_current_xact_id();end if;
 end if;
 return new;
end $$;
create trigger grace_confirm_origin before insert on stripe_billing_failed_recovery_confirm_claims for each row execute function require_owned_grace_confirm_origin();
create trigger grace_confirm_ack_origin before insert on stripe_billing_failed_recovery_confirm_acks for each row execute function require_owned_grace_confirm_origin();

-- The writer can inspect its own completed receipt. Every later reader must be independent.
create function owned_grace_publication_proof(reservation uuid,utc timestamptz,reader xid8,writing boolean) returns boolean
 language sql stable set join_collapse_limit=1 as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r
 join stripe_billing_failed_method_publications p on p.reservation_id=r.id
 join stripe_billing_failed_method_reads q on (q.id,q.reservation_id)=(p.claim_id,r.id)
 join stripe_billing_failed_method_recoveries x on x.reservation_id=r.id
 join owned_method_projected(r.id) projected on
   (projected.renewal_payment,projected.projected_revision,projected.observation_sequence)=(p.renewal_payment,p.projected_revision,p.observation_sequence)
 where r.id=$1 and r.after_ready_id is not null and owned_grace_successor_identity(r.id,$2,$3)
 and (p.successor_producer_xid=$3)=$4 and q.successor_producer_xid<>$3 and q.successor_producer_xid<>p.successor_producer_xid
 and q.successor_owner_digest is not null and q.phase='RECOVERED' and q.selected_operation=x.operation_id and p.operation_id=x.operation_id
 and q.started_at<=p.published_at and p.published_at<q.deadline_at and p.published_at<=$2
 and owned_grace_read_predecessor(r.id,'RECOVERED',q.started_at,$3))
$$;

create function check_owned_grace_read_committed() returns trigger language plpgsql volatile as $$
declare reader xid8:=pg_current_xact_id();utc timestamptz:=clock_timestamp();valid boolean;
begin
 if new.successor_producer_xid is null then return null;end if;
 if tg_table_name='stripe_billing_failed_method_reads' then
  valid:=owned_grace_read_predecessor(new.reservation_id,new.phase,new.started_at,reader)
    and case when new.phase='METHOD' then owned_method_pre(new.reservation_id) else owned_method_recovery_pre(new.reservation_id) end;
 elsif tg_table_name='stripe_billing_failed_method_completions' then
  valid:=owned_method_lineage(new.reservation_id) and owned_method_completed(new.reservation_id)
   and owned_grace_method_writes_acked(new.reservation_id,reader);
 elsif tg_table_name='stripe_billing_failed_method_recoveries' then
  valid:=owned_method_recovery_pre(new.reservation_id);
 else
  valid:=owned_grace_publication_proof(new.reservation_id,utc,reader,true);
 end if;
 if valid is not true or new.successor_producer_xid<>reader then raise exception 'Invalid grace read completion' using errcode='23514';end if;
 return null;
end $$;
create constraint trigger grace_read_committed after insert on stripe_billing_failed_method_reads deferrable initially deferred
 for each row execute function check_owned_grace_read_committed();
create constraint trigger grace_method_committed after insert on stripe_billing_failed_method_completions deferrable initially deferred
 for each row execute function check_owned_grace_read_committed();
create constraint trigger grace_associated_committed after insert on stripe_billing_failed_method_recoveries deferrable initially deferred
 for each row execute function check_owned_grace_read_committed();
create constraint trigger grace_publication_committed after insert on stripe_billing_failed_method_publications deferrable initially deferred
 for each row execute function check_owned_grace_read_committed();

-- V37 writer-local proof. The original claim predates its ACK; both precede the later observer.
create function owned_grace_confirm_producer(reservation uuid,operation uuid,stage text,commitment text,writer xid8)
 returns boolean language sql stable set join_collapse_limit=1 as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r
 join stripe_billing_failed_method_recoveries x on x.reservation_id=r.id
 join stripe_billing_failed_renewal_failures f on f.admission_id=r.admission_id
 join stripe_billing_failed_method_completions c on c.reservation_id=r.id join subscription_billing_records bill on bill.id=f.renewal_bill
 join stripe_billing_failed_recovery_confirm_claims q on q.reservation_id=r.id
 join provider_operations step on step.id=q.provider_operation
 left join stripe_billing_failed_recovery_confirm_acks a on a.provider_operation=step.id and a.reservation_id=r.id
 where r.id=$1 and r.after_ready_id is not null and q.provider_operation=$2 and q.successor_owner_digest=$4
 and x.successor_producer_xid<>q.recorded_transaction and x.associated_at<=q.claimed_at
 and owned_grace_method_writes_acked(r.id,$5) and owned_recovery_confirm_pre(r.id) and owned_recovery_confirm_owns(r.id,step)
 and step.first_dispatched_at=q.claimed_at and step.retry_before=q.claimed_at+interval '23 hours' and step.status=$3
 and (($3='DISPATCHING' and q.recorded_transaction=$5 and a.provider_operation is null and step.version=1
      and (step.lease_owner,step.lease_expires_at)=(q.claimant,q.deadline))
  or ($3='ACKNOWLEDGED' and q.recorded_transaction<>$5 and a.successor_producer_xid=$5 and step.version=2
      and step.lease_owner is null and step.lease_expires_at is null and step.provider_reference=a.payment_intent
      and (a.claimant,a.deadline,a.payment_intent,a.customer_reference,a.method_reference,a.amount,a.currency)
       =(q.claimant,q.deadline,f.payment_intent_reference,r.binding_reference,c.method_reference,bill.amount_due_minor,bill.currency)
      and q.claimed_at<=a.observed_at and a.observed_at<=a.acknowledged_at and a.acknowledged_at<least(q.deadline_at,q.deadline))))
$$;
create function check_owned_grace_confirm_producer() returns trigger language plpgsql volatile as $$
declare stage text; commitment text;
begin
 select successor_owner_digest into strict commitment from stripe_billing_failed_recovery_confirm_claims where provider_operation=new.provider_operation;
 if commitment is null then return null;end if;
 stage:=case when tg_table_name='stripe_billing_failed_recovery_confirm_claims' then 'DISPATCHING' else 'ACKNOWLEDGED' end;
 if not owned_grace_confirm_producer(new.reservation_id,new.provider_operation,stage,commitment,pg_current_xact_id()) then
  raise exception 'Invalid grace confirmation producer' using errcode='23514';end if;
 return null;
end $$;
create constraint trigger grace_confirm_dispatch_committed after insert on stripe_billing_failed_recovery_confirm_claims deferrable initially deferred
 for each row execute function check_owned_grace_confirm_producer();
create constraint trigger grace_confirm_ack_committed after insert on stripe_billing_failed_recovery_confirm_acks deferrable initially deferred
 for each row execute function check_owned_grace_confirm_producer();

alter table stripe_billing_failed_renewal_stops add successor_producer_xid xid8;
create table stripe_billing_failed_grace_successor_terminals (
 reservation_id uuid primary key, admission_id uuid not null unique references stripe_billing_failed_renewals(id),
 origin text not null check(origin in ('SETUP_WRITE','METHOD_WRITE','METHOD_READ','RECOVERED_READ','RECOVERY_CONFIRM')),
 origin_id uuid not null,
 write_claim uuid generated always as (case when origin in ('SETUP_WRITE','METHOD_WRITE') then origin_id end) stored references stripe_billing_failed_grace_method_claims(operation_id),
 read_claim uuid generated always as (case when origin in ('METHOD_READ','RECOVERED_READ') then origin_id end) stored references stripe_billing_failed_method_reads(id),
 confirm_claim uuid generated always as (case when origin='RECOVERY_CONFIRM' then origin_id end) stored references stripe_billing_failed_recovery_confirm_claims(provider_operation),
 owner_nonce uuid check(owner_nonce is null), successor_owner_digest text not null check(successor_owner_digest ~ '^[a-f0-9]{64}$'),
 reason text not null check(reason='UNCERTAIN' or (origin in ('METHOD_READ','RECOVERED_READ') and reason='AUTHORITY_DRIFT')),
 stopped_at timestamptz not null, successor_producer_xid xid8 not null,
 foreign key(reservation_id,admission_id) references stripe_billing_failed_method_reservations(id,admission_id)
);
create trigger grace_successor_terminal_immutable before update or delete on stripe_billing_failed_grace_successor_terminals
 for each row execute function protect_failed_renewal_evidence();

-- Serialize cleanup with each original phase and every FK written below. Never reacquire
-- positive session, policy, debt, original invocation budget, or lease authority.
create function lock_owned_grace_successor_terminal(reservation uuid) returns void language plpgsql volatile as $$
declare r stripe_billing_failed_method_reservations%rowtype; recovery uuid;
begin
 select * into strict r from stripe_billing_failed_method_reservations where id=$1 and after_ready_id is not null;
 perform owned_failed_grace_limits(2000,true);
 perform lock_owned_grace_boundary(r.after_preparation_id,3,true);
 perform id from stripe_billing_failed_method_reservations where id=r.id for update nowait;
 select operation_id into recovery from stripe_billing_failed_method_recoveries where reservation_id=r.id;
 perform id from subscription_operations where id in(r.setup_operation,recovery) order by id for update nowait;
 perform id from provider_operations where resource_id in(r.setup_operation,recovery) order by id for update nowait;
 perform operation_id from stripe_billing_failed_grace_method_claims where reservation_id=r.id order by operation_id for key share nowait;
 perform operation_id from stripe_subscription_setup_proofs where operation_id=r.setup_operation for update nowait;
 perform id from stripe_billing_failed_method_reads where reservation_id=r.id order by id for key share nowait;
 perform reservation_id from stripe_billing_failed_method_completions where reservation_id=r.id for key share nowait;
 perform reservation_id from stripe_billing_failed_method_recoveries where reservation_id=r.id for key share nowait;
 perform provider_operation from stripe_billing_failed_recovery_confirm_claims where reservation_id=r.id for key share nowait;
 perform provider_operation from stripe_billing_failed_recovery_confirm_acks where reservation_id=r.id for key share nowait;
 perform reservation_id from stripe_billing_failed_method_publications where reservation_id=r.id for key share nowait;
end $$;

-- An immutable origin remains usable after expiry. Its successful next phase permanently
-- fences off delayed failures. The two provider poststates are first UNKNOWN or a preserved WRITE ACK.
create function owned_grace_terminal_origin(reservation uuid,src text,origin_id uuid,final_state boolean,writer xid8,stop_at timestamptz)
 returns text language sql stable set join_collapse_limit=1 as $$
 select evidence.commitment from stripe_billing_failed_method_reservations r join subscription_operations setup on setup.id=r.setup_operation
 join lateral (
  select q.successor_owner_digest as commitment from stripe_billing_failed_grace_method_claims q join provider_operations p on p.id=q.operation_id
  where $2 in ('SETUP_WRITE','METHOD_WRITE') and q.operation_id=$3 and q.reservation_id=r.id
   and q.step_kind=case when $2='SETUP_WRITE' then 'SETUP_INTENT_CREATE' else 'SUBSCRIPTION_UPDATE' end
   and q.successor_producer_xid<>$5 and owned_grace_write_claim_matches(r.id,p) and q.first_dispatched_at<=$6
   and ((p.status=case when $4 then 'UNKNOWN' else 'DISPATCHING' end) or p.status='ACKNOWLEDGED')
   and setup.status='RESERVED' and setup.version=r.initial_operation_version
   and not exists(select 1 from stripe_billing_failed_method_reads where reservation_id=r.id)
   and not exists(select 1 from stripe_billing_failed_method_completions where reservation_id=r.id)
   and ($2<>'SETUP_WRITE' or (not exists(select 1 from stripe_subscription_setup_proofs where operation_id=setup.id)
    and not exists(select 1 from provider_operations sibling where sibling.resource_id=setup.id and sibling.kind='SUBSCRIPTION_UPDATE')))
  union all
  select q.successor_owner_digest from stripe_billing_failed_method_reads q
  where $2 in ('METHOD_READ','RECOVERED_READ') and q.reservation_id=r.id and q.id=$3 and q.successor_producer_xid<>$5 and q.started_at<=$6
   and (($2='METHOD_READ' and q.phase='METHOD' and q.selected_operation=r.setup_operation
      and not exists(select 1 from stripe_billing_failed_method_completions where reservation_id=r.id))
    or ($2='RECOVERED_READ' and q.phase='RECOVERED' and exists(select 1 from stripe_billing_failed_method_recoveries x
       where x.reservation_id=r.id and x.operation_id=q.selected_operation)))
  union all
  select q.successor_owner_digest from stripe_billing_failed_recovery_confirm_claims q join provider_operations p on p.id=q.provider_operation
  where $2='RECOVERY_CONFIRM' and q.reservation_id=r.id and q.provider_operation=$3 and q.recorded_transaction<>$5 and q.claimed_at<=$6
   and owned_recovery_confirm_owns(r.id,p) and p.first_dispatched_at=q.claimed_at and p.retry_before=q.claimed_at+interval '23 hours'
   and (($4 and p.status='UNKNOWN' and p.version=2 and p.lease_owner is null and p.lease_expires_at is null)
      or (not $4 and p.status='DISPATCHING' and p.version=1 and (p.lease_owner,p.lease_expires_at)=(q.claimant,q.deadline)))
   and not exists(select 1 from stripe_billing_failed_recovery_confirm_acks where reservation_id=r.id)
   and not exists(select 1 from stripe_billing_failed_method_reads where reservation_id=r.id and phase='RECOVERED')
 ) evidence on true
 where r.id=$1 and r.handoff_xid<>$5 and owned_grace_setup_inserted(r) and owned_failed_grace_identity(r.after_preparation_id)
 and exists(select 1 from stripe_billing_failed_grace_boundary_ready after_ready
    where (after_ready.id,after_ready.preparation_id,after_ready.admission_id,after_ready.phase)=(r.after_ready_id,r.after_preparation_id,r.admission_id,3))
 and not exists(select 1 from stripe_billing_failed_method_publications where reservation_id=r.id)
$$;
create function require_owned_grace_terminal() returns trigger language plpgsql volatile as $$
declare original text;
begin
 perform lock_owned_grace_successor_terminal(new.reservation_id);
 if new.stopped_at is not null or new.successor_producer_xid is not null or new.owner_nonce is null
    or exists(select 1 from stripe_billing_failed_renewal_stops where admission_id=new.admission_id)
 then raise exception 'Original grace invocation required' using errcode='23514';end if;
 new.stopped_at:=clock_timestamp();new.successor_producer_xid:=pg_current_xact_id();
 if new.stopped_at>=statement_timestamp()+interval '2 seconds' then
  raise exception 'Grace terminal cleanup budget expired' using errcode='23514';end if;
 original:=owned_grace_terminal_origin(new.reservation_id,new.origin,new.origin_id,false,new.successor_producer_xid,new.stopped_at);
 if original is null or original is distinct from new.successor_owner_digest or
    first_renewal_hash('owned-grace-successor-owner-v1',new.origin,new.origin_id::text,new.owner_nonce::text) is distinct from original
 then raise exception 'Original grace invocation required' using errcode='23514';end if;
 -- The unchanged ACK row has no writer xid. Only refuse on this backend's tagged
 -- ACK marker. Even an unrelated hash collision can only deny cleanup.
 if new.origin in ('SETUP_WRITE','METHOD_WRITE') and exists(select 1 from provider_operations where id=new.origin_id and status='ACKNOWLEDGED')
    and owned_grace_local_write_ack(new.origin_id)
 then raise exception 'Independent original WRITE acknowledgment required' using errcode='23514';end if;
 new.owner_nonce:=null;return new;
end $$;
create trigger grace_successor_terminal_proof before insert on stripe_billing_failed_grace_successor_terminals
 for each row execute function require_owned_grace_terminal();

create function finish_owned_grace_terminal() returns trigger language plpgsql volatile as $$
begin
 if new.origin in ('SETUP_WRITE','METHOD_WRITE','RECOVERY_CONFIRM') then
  update provider_operations set status='UNKNOWN',version=2,lease_owner=null,lease_expires_at=null
   where id=new.origin_id and status='DISPATCHING' and version=1;
 end if;
 insert into stripe_billing_failed_renewal_stops(admission_id,reason,stopped_at) values(new.admission_id,new.reason,new.stopped_at);
 return null;
end $$;
create trigger grace_successor_terminal_finish after insert on stripe_billing_failed_grace_successor_terminals
 for each row execute function finish_owned_grace_terminal();
create function owned_grace_terminal_complete(reservation uuid,writer xid8) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_grace_successor_terminals t join stripe_billing_failed_renewal_stops s using(admission_id)
 where t.reservation_id=$1 and t.successor_producer_xid=$2 and s.successor_producer_xid=$2 and t.owner_nonce is null
 and clock_timestamp()>=t.stopped_at and clock_timestamp()<t.stopped_at+interval '2 seconds'
 and (s.reason,s.stopped_at)=(t.reason,t.stopped_at)
 and owned_grace_terminal_origin(t.reservation_id,t.origin,t.origin_id,true,$2,t.stopped_at)=t.successor_owner_digest)
$$;
create function check_owned_grace_terminal_complete() returns trigger language plpgsql volatile as $$
begin
 if not owned_grace_terminal_complete(new.reservation_id,pg_current_xact_id()) then
  raise exception 'Atomic grace terminal and stop required' using errcode='23514';end if;
 return null;
end $$;
create constraint trigger grace_successor_terminal_committed after insert on stripe_billing_failed_grace_successor_terminals deferrable initially deferred
 for each row execute function check_owned_grace_terminal_complete();

-- Preserve the original V40 stop gate for other lanes. Tagged stops must belong to the
-- just-written terminal after its exact original provider transition.
create or replace function require_failed_renewal_stop() returns trigger language plpgsql volatile as $$
declare upstream uuid; preparation uuid;
begin
 if current_setting('transaction_isolation')<>'read committed' then
  raise exception 'Owned failed renewal stop requires READ COMMITTED' using errcode='23514';end if;
 select first_renewal_admission into upstream from stripe_billing_failed_renewals where id=new.admission_id;
 perform id from stripe_billing_first_renewals where id=upstream for update nowait;
 -- Separate fresh statement after serialization, including for the old unclaimed lane.
 select p.id into preparation from stripe_billing_failed_grace_preparations p join stripe_billing_failed_grace_claims q on q.preparation_id=p.id where p.admission_id=new.admission_id;
 if new.successor_producer_xid is not null then raise exception 'Invalid terminal stop xid' using errcode='23514';end if;
 if exists(select 1 from stripe_billing_failed_method_reservations r where r.admission_id=new.admission_id and r.after_ready_id is not null) then
  if not exists(select 1 from stripe_billing_failed_grace_successor_terminals t
    where t.admission_id=new.admission_id and (t.reason,t.stopped_at)=(new.reason,new.stopped_at)
    and t.successor_producer_xid=pg_current_xact_id()
    and owned_grace_terminal_origin(t.reservation_id,t.origin,t.origin_id,true,pg_current_xact_id(),t.stopped_at)=t.successor_owner_digest)
   then raise exception 'Original successor terminal required' using errcode='23514';end if;
  new.successor_producer_xid:=pg_current_xact_id();
 elsif preparation is not null then
  perform owned_failed_grace_limits(2000,true);
  if new.reason<>'UNCERTAIN' or (not exists(select 1 from stripe_billing_failed_grace_cleanup c
   join stripe_billing_failed_grace_claims q on row(q.preparation_id,q.operation_id,q.invocation)=row(c.preparation_id,c.operation_id,c.invocation)
   join provider_operations o on o.id=q.operation_id
   where c.preparation_id=preparation and c.recorded_transaction=pg_current_xact_id() and q.recorded_transaction<>pg_current_xact_id()
    and c.stopped_at=new.stopped_at and o.status in ('UNKNOWN','ACKNOWLEDGED') and o.version=2
    and not exists(select 1 from stripe_billing_failed_grace_ready r where r.preparation_id=preparation))
  and not exists(select 1 from stripe_billing_failed_grace_boundary_cleanup c
   join stripe_billing_failed_grace_boundary_claims q on
    (q.preparation_id,q.phase,q.operation_id,q.invocation,q.claimant,q.deadline)=(c.preparation_id,c.phase,c.operation_id,c.invocation,c.claimant,c.deadline)
   join provider_operations o on o.id=q.operation_id
   left join stripe_billing_failed_grace_boundary_acks a on a.operation_id=o.id
   where q.preparation_id=preparation and q.admission_id=new.admission_id and c.stopped_at=new.stopped_at
   and c.producer_xid=pg_current_xact_id() and q.producer_xid<>pg_current_xact_id()
   and owned_grace_boundary_claim_shape(q,o) and o.version=2
   and ((o.status='UNKNOWN' and a.operation_id is null) or (o.status='ACKNOWLEDGED'
     and a.producer_xid not in (c.producer_xid,q.producer_xid) and owned_grace_boundary_ack_shape(q,a,o,clock_timestamp())))
   and not exists(select 1 from stripe_billing_failed_grace_boundary_ready r where r.preparation_id=q.preparation_id and r.phase=q.phase)))
  then raise exception 'Only original grace cleanup may stop its claimed lane' using errcode='23514';end if;
 end if;
 -- Preserve every V34 condition for old unclaimed stops and the claimed original stop.
 if new.stopped_at>clock_timestamp() or not exists(select 1 from stripe_billing_failed_renewals d where d.id=new.admission_id and new.stopped_at>=d.admitted_at)
 then raise exception 'Invalid terminal failed renewal receipt' using errcode='23514';end if;return new;
end $$;

-- V37's ordinary UNKNOWN is valid for its legacy lane. For a tagged financial claim,
-- the exact original terminal must already be performing its atomic transition.
create function require_owned_grace_confirm_unknown() returns trigger language plpgsql volatile as $$
declare target uuid;
begin
 select q.reservation_id into target from stripe_billing_failed_recovery_confirm_claims q
  join stripe_billing_failed_method_reservations r on r.id=q.reservation_id
  where q.provider_operation=old.id and r.after_ready_id is not null;
 if target is not null and not exists(select 1 from stripe_billing_failed_grace_successor_terminals t
   join stripe_billing_failed_recovery_confirm_claims q on q.provider_operation=t.origin_id and q.reservation_id=t.reservation_id
   where t.reservation_id=target and t.origin='RECOVERY_CONFIRM' and t.origin_id=old.id
   and t.successor_producer_xid=pg_current_xact_id() and q.successor_owner_digest=t.successor_owner_digest)
 then raise exception 'Atomic successor confirmation termination required' using errcode='23514';end if;
 return new;
end $$;
create trigger grace_confirm_unknown before update on provider_operations for each row
 when(old.kind='OWNED_RECOVERY_INTENT_CONFIRM' and new.status='UNKNOWN') execute function require_owned_grace_confirm_unknown();

-- Recording an unbound SETUP proof after its independent ACK, or binding its method later,
-- races the terminal for this original reservation. Serialize before the first proof mutation.
create function require_live_grace_setup_proof() returns trigger language plpgsql volatile as $$
declare target uuid;
begin
 select id into target from stripe_billing_failed_method_reservations where setup_operation=new.operation_id and after_ready_id is not null;
 if target is null then return new;end if;
 perform require_live_grace_successor(target);
 if not exists(select 1 from stripe_billing_failed_grace_method_claims q join provider_operations p on p.id=q.operation_id
   where q.reservation_id=target and q.operation_id=new.step_id and q.step_kind='SETUP_INTENT_CREATE'
   and p.status='ACKNOWLEDGED' and p.provider_reference=new.setup_reference and owned_grace_write_claim_matches(target,p)
   and q.successor_producer_xid<>pg_current_xact_id() and not owned_grace_local_write_ack(p.id))
 then raise exception 'Original live grace SETUP proof required' using errcode='23514';end if;
 return new;
end $$;
create trigger grace_live_setup_proof before insert or update on stripe_subscription_setup_proofs
 for each row execute function require_live_grace_setup_proof();

create function require_live_grace_operation_progress() returns trigger language plpgsql volatile as $$
declare target uuid;
begin
 select r.id into target from stripe_billing_failed_method_reservations r
  where r.after_ready_id is not null and (r.setup_operation=old.id or exists(
    select 1 from stripe_billing_failed_method_recoveries x where x.reservation_id=r.id and x.operation_id=old.id));
 if target is not null then
  perform require_live_grace_successor(target);
  if old.kind='SETUP' and new.status='PROCESSING' and not owned_grace_method_writes_consumable(target,pg_current_xact_id()) then
   raise exception 'Original grace METHOD writes required' using errcode='23514';end if;
 end if;
 return new;
end $$;
create trigger grace_live_operation_progress before update of status on subscription_operations for each row
 when(old.kind in ('SETUP','RECOVER') and new.status in ('PROCESSING','SUCCEEDED') and new.status<>old.status)
 execute function require_live_grace_operation_progress();

-- Refusal locators, not proof. Start separately from each original ownership key:
-- a broken reservation/preparation/AFTER/payment link must not hide a tagged child.
-- NULL identifies a terminal, or an original SETUP insert with no matching handoff.
create function owned_grace_successor_locators(admission uuid) returns table(reservation_id uuid)
 language sql stable set join_collapse_limit=1 as $$
 with d as materialized(select id,subscription_id from stripe_billing_failed_renewals where id=$1),
 ops as materialized(select o.id from subscription_operations o,d where o.subscription_id=d.subscription_id
   or o.authority_scope in ('owned-failed-renewal-method:'||d.id::text,'owned-failed-renewal:'||d.id::text)),
 steps as materialized(select id from provider_operations where resource_id in(select id from ops)),
 preps as materialized(select id from stripe_billing_failed_grace_preparations where admission_id=$1),
 boundaries as materialized(select id from stripe_billing_failed_grace_boundary_ready
   where admission_id=$1 or preparation_id in(select id from preps)),
 origins as materialized(select o.* from stripe_billing_failed_grace_setup_origins o
   where o.admission_id=$1 or o.setup_operation in(select id from ops) or o.after_ready_id in(select id from boundaries)
     or o.after_preparation_id in(select id from preps)),
 reservations as materialized(select r.* from stripe_billing_failed_method_reservations r
   where r.admission_id=$1 or r.setup_operation in(select id from ops) or r.after_ready_id in(select id from boundaries)
     or r.after_preparation_id in(select id from preps) or r.setup_operation in(select setup_operation from origins)),
 reads as materialized(select q.* from stripe_billing_failed_method_reads q
   where q.reservation_id in(select id from reservations) or q.selected_operation in(select id from ops)),
 confirms as materialized(select q.* from stripe_billing_failed_recovery_confirm_claims q
   where q.reservation_id in(select id from reservations) or q.provider_operation in(select id from steps))
 select id from reservations where num_nonnulls(after_ready_id,after_preparation_id,after_phase,handoff_xid)>0
 union all select r.id from origins o left join reservations r on
   (r.setup_operation,r.admission_id,r.after_ready_id,r.after_preparation_id,r.after_phase,r.handoff_xid)
   =(o.setup_operation,o.admission_id,o.after_ready_id,o.after_preparation_id,o.after_phase,o.producer_xid)
 union all select q.reservation_id from stripe_billing_failed_grace_method_claims q where q.reservation_id in(select id from reservations)
   or q.setup_operation in(select id from ops) or q.operation_id in(select id from steps)
 union all select q.reservation_id from reads q where q.successor_producer_xid is not null or q.successor_owner_digest is not null
 union all select c.reservation_id from stripe_billing_failed_method_completions c where c.successor_producer_xid is not null
   and (c.reservation_id in(select id from reservations) or c.claim_id in(select id from reads) or c.setup_operation in(select id from ops)
     or c.setup_step in(select id from steps) or c.method_step in(select id from steps))
 union all select x.reservation_id from stripe_billing_failed_method_recoveries x where x.successor_producer_xid is not null
   and (x.reservation_id in(select id from reservations) or x.operation_id in(select id from ops))
 union all select q.reservation_id from confirms q where q.successor_owner_digest is not null
 union all select a.reservation_id from stripe_billing_failed_recovery_confirm_acks a where a.successor_producer_xid is not null
   and (a.reservation_id in(select id from reservations) or a.provider_operation in(select provider_operation from confirms)
     or a.provider_operation in(select id from steps))
 union all select p.reservation_id from stripe_billing_failed_method_publications p where p.successor_producer_xid is not null
   and (p.reservation_id in(select id from reservations) or p.claim_id in(select id from reads) or p.operation_id in(select id from ops)
     or p.renewal_payment in(select id from payments where subscription_id in(select subscription_id from d)
       or billing_record_id in(select renewal_bill from stripe_billing_failed_renewal_failures where admission_id=$1)))
 union all select null::uuid from stripe_billing_failed_grace_successor_terminals t where t.admission_id=$1
   or t.reservation_id in(select id from reservations) or t.origin_id in(select id from steps union all select id from reads)
 union all select null::uuid from stripe_billing_failed_renewal_stops where admission_id=$1 and successor_producer_xid is not null
$$;
create function owned_grace_successor_present(admission uuid) returns boolean language sql stable as $$
 select exists(select 1 from owned_grace_successor_locators($1))
$$;

-- Exact reads consume the independently committed publication, never writer-local
-- publication authority, pre-confirm debt eligibility, or a live WRITE ACK marker.
create function owned_grace_successor_projected(reservation uuid,utc timestamptz,reader xid8) returns boolean
 language sql stable set join_collapse_limit=1 as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r
 join stripe_billing_failed_grace_method_claims sw on sw.reservation_id=r.id and sw.step_kind='SETUP_INTENT_CREATE'
 join stripe_billing_failed_grace_method_claims mw on mw.reservation_id=r.id and mw.step_kind='SUBSCRIPTION_UPDATE'
 join stripe_billing_method_browser_roots root on (root.reservation_id,root.setup_operation,root.setup_step)=(r.id,r.setup_operation,sw.operation_id)
 join stripe_billing_method_browser_releases rel on rel.root_id=root.id
 join stripe_billing_method_browser_consumptions consumed on consumed.root_id=root.id
 join stripe_billing_failed_method_reads m on m.reservation_id=r.id and m.phase='METHOD'
 join stripe_billing_failed_method_completions c on (c.reservation_id,c.claim_id)=(r.id,m.id)
 join stripe_billing_failed_method_recoveries x on x.reservation_id=r.id
 join stripe_billing_failed_recovery_confirm_claims q on q.reservation_id=r.id
 join stripe_billing_failed_recovery_confirm_acks ack on (ack.reservation_id,ack.provider_operation)=(r.id,q.provider_operation)
 join stripe_billing_failed_method_reads obs on obs.reservation_id=r.id and obs.phase='RECOVERED'
 join stripe_billing_failed_method_publications pub on (pub.reservation_id,pub.claim_id)=(r.id,obs.id)
 join stripe_billing_failed_renewals d on d.id=r.admission_id
 join stripe_billing_failed_renewal_failures f on f.admission_id=d.id
 join subscriptions sub on sub.id=d.subscription_id
 join payments cash on cash.id=pub.renewal_payment and cash.billing_record_id=f.renewal_bill
 join subscription_events period on period.subscription_id=sub.id and period.billing_record_id=f.renewal_bill and period.event_type='PERIOD_PAID'
 join subscription_event_sequences journal on journal.subscription_id=sub.id
 join payment_events paid on paid.payment_id=cash.id and paid.event_type='PAYMENT_SUCCEEDED'
 where r.id=$1 and r.after_ready_id is not null and owned_grace_publication_proof(r.id,$2,$3,false)
 and not exists(select 1 from owned_grace_successor_locators(r.admission_id) e where e.reservation_id is distinct from r.id)
 and sw.first_dispatched_at>=r.reserved_at and root.issued_at>=sw.first_dispatched_at
 and root.expires_at=least(root.issued_at+interval '600 seconds',r.browser_session_expires_at)
 and mw.first_dispatched_at>=consumed.consumed_at and m.started_at>=mw.first_dispatched_at
 and c.completed_at>=m.started_at and c.completed_at<m.deadline_at and x.associated_at>=c.completed_at
 and ack.acknowledged_at<=obs.started_at and obs.started_at<=pub.published_at and pub.published_at<=$2
 and (period.site_id,period.payment_account_id,period.revision,period.source,period.provider_reference,period.occurred_at)
  =(d.site_id,d.payment_account_id,pub.projected_revision,'RECONCILIATION',sub.provider_reference,r.billing_at)
 and period.received_at between obs.started_at and pub.published_at and period.operation_id is null and period.payment_id is null
 and period.sequence_number=journal.next_sequence
 and (paid.site_id,paid.payment_account_id,paid.source,paid.sequence_number,paid.provider_object_id,paid.amount_minor,paid.currency)
  =(d.site_id,d.payment_account_id,'PROVIDER_EVENT',1,f.payment_intent_reference,cash.amount_minor,cash.currency)
 and not exists(select 1 from payment_events extra where extra.payment_id=cash.id and extra.id<>paid.id)
 and not exists(select 1 from subscription_events extra where extra.subscription_id=sub.id
  and extra.billing_record_id=f.renewal_bill and extra.event_type='PERIOD_PAID' and extra.id<>period.id)
 and (select count(distinct xid)=21 and bool_and(xid<>$3) from (
   select unnest(array[r.handoff_xid,sw.successor_producer_xid,rel.recorded_transaction,consumed.recorded_transaction,
    mw.successor_producer_xid,m.successor_producer_xid,c.successor_producer_xid,x.successor_producer_xid,q.recorded_transaction,
    ack.successor_producer_xid,obs.successor_producer_xid,pub.successor_producer_xid]) xid
   union all select unnest(array[g.recorded_transaction,a.recorded_transaction,b.recorded_transaction])
    from stripe_billing_failed_grace_claims g join stripe_billing_failed_grace_acks a using(preparation_id)
    join stripe_billing_failed_grace_ready b using(preparation_id) where g.preparation_id=r.after_preparation_id
   union all select unnest(array[g.producer_xid,a.producer_xid,b.producer_xid])
    from stripe_billing_failed_grace_boundary_claims g join stripe_billing_failed_grace_boundary_acks a using(preparation_id,phase)
    join stripe_billing_failed_grace_boundary_ready b using(preparation_id,phase) where g.preparation_id=r.after_preparation_id
   ) producers))
$$;
