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
 and owned_method_identity(r.id) and owned_grace_handoff_link(r,$2,$3)
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
 and owned_method_identity(r.id) and owned_grace_handoff_prestate(r,$2,$3))
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
