-- Two finite successors to the immutable V39 G-1 READY, on the original preparation.
alter table provider_operations drop constraint provider_operations_kind_check;
alter table provider_operations add constraint provider_operations_kind_check check(kind in (
 'PRODUCT_CREATE','PRICE_CREATE','PAYMENT_PREPARE','REFUND_CREATE','CUSTOMER_CREATE','TEST_CLOCK_CREATE','TEST_CLOCK_ADVANCE',
 'SUBSCRIPTION_CREATE','SUBSCRIPTION_UPDATE','SUBSCRIPTION_CANCEL','SETUP_INTENT_CREATE','INVOICE_PAY',
 'SCHEDULE_CREATE','SCHEDULE_UPDATE','SCHEDULE_RELEASE','PORTAL_CONFIGURATION_CREATE','PORTAL_SESSION_CREATE',
 'OWNED_RECOVERY_INTENT_CONFIRM','OWNED_GRACE_CLOCK_ADVANCE','OWNED_GRACE_AT_CLOCK_ADVANCE','OWNED_GRACE_AFTER_CLOCK_ADVANCE'));

alter table stripe_billing_failed_grace_ready add unique(id,preparation_id,admission_id);
create table stripe_billing_failed_grace_boundary_claims (
 preparation_id uuid not null, admission_id uuid not null, phase integer not null check(phase in (2,3)),
 original_grace_ready_id uuid not null, predecessor_phase integer, predecessor_ready_id uuid,
 primary key(preparation_id,phase), unique(admission_id,phase),
 check((phase=2 and predecessor_phase is null and predecessor_ready_id is null) or
       (phase=3 and predecessor_phase is not null and predecessor_phase=2 and predecessor_ready_id is not null)),
 foreign key(preparation_id,admission_id) references stripe_billing_failed_grace_preparations(id,admission_id),
 foreign key(original_grace_ready_id,preparation_id,admission_id) references stripe_billing_failed_grace_ready(id,preparation_id,admission_id),
 operation_id uuid not null unique references provider_operations(id),
 invocation uuid not null unique, claimant varchar(128) not null check(length(trim(claimant))>0 and claimant !~ '[[:cntrl:]]'),
 entered_at timestamptz not null, deadline_at timestamptz not null check(deadline_at=entered_at+interval '30 seconds'),
 claimed_at timestamptz not null, deadline timestamptz not null check(deadline=claimed_at+interval '60 seconds'),
 producer_xid xid8 not null default pg_current_xact_id(),
 check(claimed_at>=entered_at and claimed_at<deadline_at),
 unique(preparation_id,phase,operation_id,invocation), unique(preparation_id,phase,operation_id,invocation,claimant,deadline)
);
create table stripe_billing_failed_grace_boundary_acks (
 preparation_id uuid not null, phase integer not null, primary key(preparation_id,phase), operation_id uuid not null unique, invocation uuid not null unique,
 claimant varchar(128) not null, deadline timestamptz not null,
 reference varchar(255) not null check(reference ~ '^clock_[A-Za-z0-9]{1,249}$'),
 object_type varchar(64) not null check(object_type='test_helpers.test_clock'), livemode boolean not null check(not livemode),
 name varchar(255) not null, frozen_time bigint not null check(frozen_time>0), status varchar(32) not null check(status in ('ready','advancing')),
 provider_created bigint not null check(provider_created>0), provider_deletes_after bigint not null check(provider_deletes_after>0),
 advancing_target bigint check(advancing_target>0), observed_at timestamptz not null,
 acknowledged_at timestamptz not null check(acknowledged_at>=observed_at),
 producer_xid xid8 not null default pg_current_xact_id(),
 foreign key(preparation_id,phase,operation_id,invocation,claimant,deadline)
  references stripe_billing_failed_grace_boundary_claims(preparation_id,phase,operation_id,invocation,claimant,deadline),
 unique(preparation_id,phase,operation_id,invocation)
);
create table stripe_billing_failed_grace_boundary_ready (
 id uuid primary key, preparation_id uuid not null, admission_id uuid not null, phase integer not null,
 unique(preparation_id,phase),unique(admission_id,phase),unique(id,preparation_id,admission_id,phase),
 operation_id uuid not null unique, invocation uuid not null unique,
 reference varchar(255) not null, object_type varchar(64) not null, livemode boolean not null,
 name varchar(255) not null, frozen_time bigint not null, status varchar(32) not null,
 provider_created bigint not null, provider_deletes_after bigint not null, advancing_target bigint,
 observed_at timestamptz not null, producer_xid xid8 not null default pg_current_xact_id(),
 foreign key(preparation_id,admission_id) references stripe_billing_failed_grace_preparations(id,admission_id),
 foreign key(preparation_id,phase,operation_id,invocation) references stripe_billing_failed_grace_boundary_acks(preparation_id,phase,operation_id,invocation)
);
create table stripe_billing_failed_grace_boundary_cleanup (
 preparation_id uuid not null, phase integer not null, primary key(preparation_id,phase), operation_id uuid not null unique, invocation uuid not null unique,
 claimant varchar(128) not null, deadline timestamptz not null, stopped_at timestamptz not null, producer_xid xid8 not null default pg_current_xact_id(),
 foreign key(preparation_id,phase,operation_id,invocation,claimant,deadline) references stripe_billing_failed_grace_boundary_claims(preparation_id,phase,operation_id,invocation,claimant,deadline)
);
create trigger boundary_claim_immutable before update or delete on stripe_billing_failed_grace_boundary_claims for each row execute function protect_failed_renewal_evidence();
create trigger boundary_ack_immutable before update or delete on stripe_billing_failed_grace_boundary_acks for each row execute function protect_failed_renewal_evidence();
create trigger boundary_ready_immutable before update or delete on stripe_billing_failed_grace_boundary_ready for each row execute function protect_failed_renewal_evidence();
create trigger boundary_cleanup_immutable before update or delete on stripe_billing_failed_grace_boundary_cleanup for each row execute function protect_failed_renewal_evidence();

alter table stripe_billing_failed_grace_boundary_claims add foreign key(predecessor_ready_id,preparation_id,admission_id,predecessor_phase)
 references stripe_billing_failed_grace_boundary_ready(id,preparation_id,admission_id,phase);

create function owned_grace_original_committed(preparation uuid, utc timestamptz, reader xid8) returns boolean language sql stable set join_collapse_limit=1 as $$
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
  join stripe_billing_first_renewal_ready current_r on current_r.id=current_d.ready_id and current_r.admission_id=current_a.id
  join stripe_billing_first_renewal_steps current_step on current_step.id=current_r.step_id and current_step.admission_id=current_a.id
  join stripe_billing_first_renewal_boundary current_b on current_b.id=current_d.boundary_id and current_b.admission_id=current_a.id
  join stripe_billing_failed_renewal_failures current_f on current_f.admission_id=current_d.id
  join stripe_billing_failed_renewal_reads current_q on current_q.id=current_f.claim_id and current_q.admission_id=current_d.id and current_q.phase='FAILED'
  where current_d.id=d.id and current_d.source_sha=current_a.source_sha
   and owned_failed_renewal_authority(current_d.id) and owned_failed_renewal_failure_current(current_d.id,null)
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
   and not exists(select 1 from stripe_billing_failed_method_reservations x where x.admission_id=current_d.id)
   and not exists(select 1 from stripe_billing_failed_renewal_recoveries x where x.admission_id=current_d.id)
   and not exists(select 1 from stripe_billing_failed_renewal_publications x where x.admission_id=current_d.id)
   and not exists(select 1 from stripe_billing_failed_renewal_stops x where x.admission_id=current_d.id)
   and not exists(select 1 from subscription_operations x where x.subscription_id=current_d.subscription_id
    and x.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN') and x.kind<>'RECONCILE'))
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

-- Immutable descriptions only. The caller must additionally prove the committed predecessor
-- at its snapshot. Cleanup uses descriptions without granting current authority.
create function owned_grace_boundary_edge(preparation uuid, ordinal integer)
returns table(original_id uuid,previous_id uuid,operation_id uuid,invocation uuid,operation_fingerprint text,
 reference text,object_type text,livemode boolean,name text,provider_created bigint,provider_deletes_after bigint,
 frozen_time bigint,status text,advancing_target bigint,observed_at timestamptz,kind text,target bigint,operation_key text,fingerprint text)
language sql stable set join_collapse_limit=1 as $$
 select origin.id,v.id,v.operation_id,v.invocation,prior.fingerprint::text,
 v.reference::text,v.object_type::text,v.livemode,v.name::text,v.provider_created,v.provider_deletes_after,
 v.frozen_time,v.status::text,v.advancing_target,v.observed_at,h.kind,h.target,h.key,
 first_renewal_hash('billing-failed-grace-boundary-v1',p.admission_id::text,p.id::text,p.fingerprint,origin.id::text,
 $2::text,h.kind,v.id::text,v.operation_id::text,v.invocation::text,prior.fingerprint,
 v.reference,v.object_type,v.livemode::text,v.name,v.provider_created::text,v.provider_deletes_after::text,
 v.frozen_time::text,v.status,v.advancing_target::text,first_renewal_instant(v.observed_at),first_renewal_instant(p.grace_deadline),h.target::text,h.key)
 from stripe_billing_failed_grace_preparations p
 join stripe_billing_failed_grace_ready origin on origin.preparation_id=p.id and origin.admission_id=p.admission_id
 cross join lateral (
  select r.id,r.operation_id,r.invocation,r.reference,r.object_type,r.livemode,r.name,r.provider_created,r.provider_deletes_after,r.frozen_time,r.status,r.advancing_target,r.observed_at
  from stripe_billing_failed_grace_ready r where $2=2 and r.id=origin.id
  union all select r.id,r.operation_id,r.invocation,r.reference,r.object_type,r.livemode,r.name,r.provider_created,r.provider_deletes_after,r.frozen_time,r.status,r.advancing_target,r.observed_at
  from stripe_billing_failed_grace_boundary_ready r where $2=3 and r.preparation_id=p.id and r.admission_id=p.admission_id and r.phase=2
 ) v join provider_operations prior on prior.id=v.operation_id
 cross join lateral(select case $2 when 2 then 'OWNED_GRACE_AT_CLOCK_ADVANCE' else 'OWNED_GRACE_AFTER_CLOCK_ADVANCE' end as kind,
 (extract(epoch from p.grace_deadline)::bigint+($2-2)) as target,
 'billing-failed-grace-boundary-v1:'||p.admission_id::text||case $2 when 2 then ':at' else ':after' end as key) h
 where p.id=$1 and $2 in (2,3) and h.target::numeric=v.frozen_time::numeric+1
$$;
create function owned_grace_boundary_owns(p uuid,h integer,o provider_operations) returns boolean language sql stable as $$
 select exists(select 1 from owned_grace_boundary_edge($1,$2) e join provider_operations prior on prior.id=e.operation_id
 where row(($3).payment_account_id,($3).external_account_id,($3).credentials_ref,($3).resource_id,($3).kind,($3).operation_key,($3).fingerprint)
 =row(prior.payment_account_id,prior.external_account_id,prior.credentials_ref,$1,e.kind,e.operation_key,e.fingerprint))
$$;

-- Recheck durable identity even when a caller invokes a transition directly.
create function owned_grace_boundary_claim_shape(q stripe_billing_failed_grace_boundary_claims,o provider_operations) returns boolean language sql stable as $$
 select exists(select 1 from owned_grace_boundary_edge(($1).preparation_id,($1).phase) e
 join stripe_billing_failed_grace_preparations p on p.id=($1).preparation_id
 where ($1).admission_id=p.admission_id and ($1).original_grace_ready_id=e.original_id
 and (($1).phase=2 and ($1).predecessor_phase is null and ($1).predecessor_ready_id is null
  or ($1).phase=3 and ($1).predecessor_phase=2 and ($1).predecessor_ready_id=e.previous_id)
 and ($1).operation_id=($2).id and owned_grace_boundary_owns(p.id,($1).phase,$2)
 and ($1).entered_at>=e.observed_at and ($1).claimed_at>=($1).entered_at and ($1).claimed_at<($1).deadline_at
 and ($1).deadline_at=($1).entered_at+interval '30 seconds' and ($1).deadline=($1).claimed_at+interval '60 seconds'
 and ($2).recovery_attempts=0 and not ($2).manual_review_required and ($2).safe_error_code is null
 and (($2).status='RESERVED' or (($2).first_dispatched_at=($1).claimed_at and ($2).retry_before=($1).claimed_at+interval '23 hours')))
$$;

-- One edge only: the public bounded helper below explicitly composes all earlier edges.
create function owned_grace_boundary_ack_shape(q stripe_billing_failed_grace_boundary_claims,a stripe_billing_failed_grace_boundary_acks,o provider_operations,utc timestamptz)
 returns boolean language sql stable as $$
 select exists(select 1 from owned_grace_boundary_edge(($1).preparation_id,($1).phase) e
  where owned_grace_boundary_claim_shape($1,$3) and ($3).status='ACKNOWLEDGED' and ($3).version=2
  and ($3).lease_owner is null and ($3).lease_expires_at is null and ($3).provider_reference=($2).reference
  and (($2).preparation_id,($2).phase,($2).operation_id,($2).invocation,($2).claimant,($2).deadline)
    =(($1).preparation_id,($1).phase,($1).operation_id,($1).invocation,($1).claimant,($1).deadline)
  and (($2).reference,($2).object_type,($2).livemode,($2).name,($2).provider_created,($2).provider_deletes_after)
    =(e.reference,e.object_type,e.livemode,e.name,e.provider_created,e.provider_deletes_after)
  and (($2).advancing_target is null or ($2).advancing_target=e.target)
  and ((($2).status='ready' and ($2).frozen_time=e.target) or (($2).status='advancing' and ($2).frozen_time between e.frozen_time and e.target))
  and ($1).producer_xid<>($2).producer_xid and ($2).observed_at>=($1).claimed_at
  and ($2).acknowledged_at>=($2).observed_at and ($2).acknowledged_at<= $4 and ($2).acknowledged_at<least(($1).deadline,($1).deadline_at))
$$;
create function owned_grace_boundary_receipt(p uuid,h integer,utc timestamptz,reader xid8) returns boolean language sql stable set join_collapse_limit=1 as $$
 select exists(select 1 from stripe_billing_failed_grace_boundary_claims q
 join stripe_billing_failed_grace_preparations p on p.id=q.preparation_id and p.admission_id=q.admission_id
 join owned_grace_boundary_edge($1,$2) e on e.original_id=q.original_grace_ready_id
 join provider_operations o on o.id=q.operation_id
 join stripe_billing_failed_grace_boundary_acks a on (a.preparation_id,a.phase,a.operation_id,a.invocation,a.claimant,a.deadline)
 =(q.preparation_id,q.phase,q.operation_id,q.invocation,q.claimant,q.deadline)
 join stripe_billing_failed_grace_boundary_ready r on (r.preparation_id,r.admission_id,r.phase,r.operation_id,r.invocation)
 =(q.preparation_id,q.admission_id,q.phase,q.operation_id,q.invocation)
 where q.preparation_id=$1 and q.phase=$2 and owned_grace_boundary_owns($1,$2,o)
 and ($2<>2 or r.id<>q.original_grace_ready_id)
 -- Keep conflicting siblings discovered by resource, admission, operation or invocation.
 and not exists(select 1 from provider_operations other where other.id<>o.id
  and ((other.kind=e.kind and other.resource_id=$1) or other.operation_key=e.operation_key))
 and not exists(select 1 from stripe_billing_failed_grace_boundary_claims x
  where x.phase=$2 and (x.preparation_id=$1 or x.admission_id=q.admission_id) and x.operation_id<>o.id)
 and not exists(select 1 from stripe_billing_failed_grace_boundary_ready x
  where x.phase=$2 and (x.preparation_id=$1 or x.admission_id=q.admission_id) and x.id<>r.id)
 and (($2=2 and q.predecessor_phase is null and q.predecessor_ready_id is null)
  or ($2=3 and q.predecessor_phase=2 and q.predecessor_ready_id=e.previous_id))
 and not exists(select 1 from stripe_billing_failed_grace_boundary_cleanup u where (u.preparation_id=$1 and u.phase=$2)
  or u.operation_id=q.operation_id or u.invocation=q.invocation)
 and o.status='ACKNOWLEDGED' and o.version=2 and o.provider_reference=e.reference and o.safe_error_code is null
 and o.recovery_attempts=0 and not o.manual_review_required and o.lease_owner is null and o.lease_expires_at is null
 and o.first_dispatched_at=q.claimed_at and o.retry_before=q.claimed_at+interval '23 hours'
 and q.entered_at>=e.observed_at and q.claimed_at>=q.entered_at and q.claimed_at<q.deadline_at
 and q.deadline_at=q.entered_at+interval '30 seconds' and q.deadline=q.claimed_at+interval '60 seconds'
 and a.observed_at>=q.claimed_at and a.acknowledged_at>=a.observed_at
 and r.observed_at>=a.acknowledged_at and r.observed_at<= $3 and r.observed_at<least(q.deadline_at,q.deadline)
 and row(a.reference,a.object_type,a.livemode,a.name,a.provider_created,a.provider_deletes_after)
 =row(e.reference,e.object_type,e.livemode,e.name,e.provider_created,e.provider_deletes_after)
 and row(r.reference,r.object_type,r.livemode,r.name,r.provider_created,r.provider_deletes_after)
 =row(e.reference,e.object_type,e.livemode,e.name,e.provider_created,e.provider_deletes_after)
 and (a.advancing_target is null or a.advancing_target=e.target)
 and ((a.status='ready' and a.frozen_time=e.target) or (a.status='advancing' and a.frozen_time between e.frozen_time and e.target))
 and r.status='ready' and r.frozen_time=e.target and (r.advancing_target is null or r.advancing_target=e.target)
 and q.producer_xid<>a.producer_xid and q.producer_xid<>r.producer_xid and a.producer_xid<>r.producer_xid
 and $4 not in (q.producer_xid,a.producer_xid,r.producer_xid)
 and not exists(select 1 from stripe_billing_failed_grace_claims oq
  join stripe_billing_failed_grace_acks oa using(preparation_id) join stripe_billing_failed_grace_ready orig using(preparation_id)
  where oq.preparation_id=$1 and array[oq.recorded_transaction,oa.recorded_transaction,orig.recorded_transaction] && array[q.producer_xid,a.producer_xid,r.producer_xid])
 and ($2=2 or not exists(select 1 from stripe_billing_failed_grace_boundary_claims aq
  join stripe_billing_failed_grace_boundary_acks aa using(preparation_id,phase) join stripe_billing_failed_grace_boundary_ready ar using(preparation_id,phase)
  where aq.preparation_id=$1 and aq.phase=2 and array[aq.producer_xid,aa.producer_xid,ar.producer_xid] && array[q.producer_xid,a.producer_xid,r.producer_xid])))
$$;
create function owned_grace_phase_committed(p uuid,h integer,utc timestamptz,reader xid8) returns boolean language sql stable as $$
 select $2 in (2,3) and owned_grace_original_committed($1,$3,$4) and owned_grace_boundary_receipt($1,2,$3,$4)
 and ($2=2 or owned_grace_boundary_receipt($1,3,$3,$4))
$$;
create function owned_grace_predecessor_committed(p uuid,h integer,utc timestamptz,reader xid8) returns boolean language sql stable as $$
 select case $2 when 2 then owned_grace_original_committed($1,$3,$4) when 3 then owned_grace_phase_committed($1,2,$3,$4) else false end
$$;
create function owned_grace_boundary_present(p uuid,h integer) returns boolean language sql stable as $$
 with root as materialized(select admission_id from stripe_billing_failed_grace_preparations where id=$1),
 ops as materialized(select id from provider_operations
  where (resource_id=$1 and kind=case $2 when 2 then 'OWNED_GRACE_AT_CLOCK_ADVANCE' else 'OWNED_GRACE_AFTER_CLOCK_ADVANCE' end)
   or operation_key=(select 'billing-failed-grace-boundary-v1:'||admission_id::text||case $2 when 2 then ':at' when 3 then ':after' end from root)),
 claims as materialized(select operation_id,invocation from stripe_billing_failed_grace_boundary_claims
   where (phase=$2 and (preparation_id=$1 or admission_id=(select admission_id from root))) or operation_id in(select id from ops))
 select exists(select 1 from ops) or exists(select 1 from claims)
 or exists(select 1 from stripe_billing_failed_grace_boundary_acks where (preparation_id=$1 and phase=$2)
   or operation_id in(select id from ops union all select operation_id from claims) or invocation in(select invocation from claims))
 or exists(select 1 from stripe_billing_failed_grace_boundary_ready where (phase=$2 and (preparation_id=$1 or admission_id=(select admission_id from root)))
   or operation_id in(select id from ops union all select operation_id from claims) or invocation in(select invocation from claims))
 or exists(select 1 from stripe_billing_failed_grace_boundary_cleanup where (preparation_id=$1 and phase=$2)
   or operation_id in(select id from ops union all select operation_id from claims) or invocation in(select invocation from claims))
$$;
create function owned_grace_boundary_fresh(p uuid,h integer) returns boolean language plpgsql volatile as $$
begin
 if current_setting('transaction_isolation')<>'read committed' then raise exception 'Boundary requires READ COMMITTED' using errcode='23514';end if;
 perform id from stripe_billing_failed_grace_preparations where id=$1 for no key update nowait;
 if not found then return false;end if;
 return not owned_grace_boundary_present(p,h) and (h<>2 or not owned_grace_boundary_present(p,3))
  and owned_grace_predecessor_committed(p,h,clock_timestamp(),pg_current_xact_id());
end $$;
create function lock_owned_grace_boundary(p uuid,h integer,cleanup boolean) returns void language plpgsql volatile as $$
begin
 if cleanup then perform lock_owned_failed_grace_cleanup(p);else perform lock_owned_failed_grace_advance(p);end if;
 perform id from stripe_billing_failed_grace_ready where preparation_id=p for key share nowait;
 perform id from provider_operations where resource_id=p and kind in ('OWNED_GRACE_AT_CLOCK_ADVANCE','OWNED_GRACE_AFTER_CLOCK_ADVANCE') order by kind for update nowait;
 perform preparation_id from stripe_billing_failed_grace_boundary_claims where preparation_id=p order by phase for key share nowait;
 perform preparation_id from stripe_billing_failed_grace_boundary_acks where preparation_id=p order by phase for key share nowait;
 perform id from stripe_billing_failed_grace_boundary_ready where preparation_id=p order by phase for key share nowait;
end $$;
create function require_owned_grace_boundary_claim() returns trigger language plpgsql volatile as $$
declare step provider_operations%rowtype; admission uuid; db_at timestamptz; edge record;
begin
 perform owned_failed_grace_limits(greatest(1,floor(extract(epoch from(new.deadline_at-clock_timestamp()))*1000)::bigint),false);
 perform lock_owned_grace_boundary(new.preparation_id,new.phase,false);
 select admission_id into admission from stripe_billing_failed_grace_preparations where id=new.preparation_id;
 select * into step from provider_operations where id=new.operation_id for update nowait;db_at:=clock_timestamp();
 select * into edge from owned_grace_boundary_edge(new.preparation_id,new.phase);
 if not found or new.producer_xid<>pg_current_xact_id() or not owned_grace_boundary_owns(new.preparation_id,new.phase,step)
  or not owned_grace_predecessor_committed(new.preparation_id,new.phase,db_at,pg_current_xact_id())
  or not exists(select 1 from provider_operations where id=step.id and xmin=pg_current_xact_id()::xid)
  or new.admission_id is distinct from admission or new.original_grace_ready_id is distinct from edge.original_id
  or (new.phase=3 and new.predecessor_ready_id is distinct from edge.previous_id) or new.entered_at<edge.observed_at
  or step.status<>'RESERVED' or step.version<>0
  or num_nonnulls(step.provider_reference,step.first_dispatched_at,step.retry_before,step.lease_owner,step.lease_expires_at)<>0
  or new.entered_at>new.claimed_at or new.claimed_at>db_at or db_at>=new.deadline_at
  or new.claimed_at<(select prepared_at from stripe_billing_failed_grace_preparations where id=new.preparation_id)
 then raise exception 'Invalid original owned grace firstclaim' using errcode='23514';end if;
 new.producer_xid:=pg_current_xact_id();return new;
end $$;
create trigger boundary_claim_proof before insert on stripe_billing_failed_grace_boundary_claims for each row execute function require_owned_grace_boundary_claim();

create function require_owned_grace_boundary_operation() returns trigger language plpgsql volatile as $$
declare p stripe_billing_failed_grace_preparations%rowtype; q stripe_billing_failed_grace_boundary_claims%rowtype; db_at timestamptz; expected_clock text; h integer;
begin
 if tg_op='DELETE' then
  if old.kind in ('OWNED_GRACE_AT_CLOCK_ADVANCE','OWNED_GRACE_AFTER_CLOCK_ADVANCE') then raise exception 'Owned grace operation is immutable' using errcode='23514';end if;return old;
 end if;
 if tg_op='UPDATE' and old.kind in ('OWNED_GRACE_AT_CLOCK_ADVANCE','OWNED_GRACE_AFTER_CLOCK_ADVANCE') and new.kind<>old.kind then
  raise exception 'Owned grace kind is immutable' using errcode='23514';end if;
 if new.kind not in ('OWNED_GRACE_AT_CLOCK_ADVANCE','OWNED_GRACE_AFTER_CLOCK_ADVANCE') then return new;end if;
 h:=case new.kind when 'OWNED_GRACE_AT_CLOCK_ADVANCE' then 2 else 3 end;
 if tg_op='UPDATE' and old.kind<>new.kind then raise exception 'Owned grace cannot convert an existing operation' using errcode='23514';end if;
 if tg_op='UPDATE' then
  if row(new.id,new.payment_account_id,new.external_account_id,new.credentials_ref,new.kind,new.resource_id,new.operation_key,new.fingerprint,new.recovery_attempts,new.next_attempt_at,new.safe_error_code,new.manual_review_required)
   is distinct from row(old.id,old.payment_account_id,old.external_account_id,old.credentials_ref,old.kind,old.resource_id,old.operation_key,old.fingerprint,old.recovery_attempts,old.next_attempt_at,old.safe_error_code,old.manual_review_required)
  then raise exception 'Owned grace history is immutable' using errcode='23514';end if;
 end if;
 select * into p from stripe_billing_failed_grace_preparations where id=new.resource_id;
 if p.id is null or not owned_failed_grace_identity(p.id) or not owned_grace_boundary_owns(p.id,h,new) then raise exception 'Invalid owned grace operation identity' using errcode='23514';end if;
 if tg_op='INSERT' then
  perform owned_failed_grace_limits(30000,false);
  if not owned_grace_boundary_fresh(p.id,h) then raise exception 'Owned grace firstclaim already consumed' using errcode='23514';end if;
  perform lock_owned_grace_boundary(p.id,h,false);
  if not owned_grace_predecessor_committed(p.id,h,clock_timestamp(),pg_current_xact_id()) or new.status<>'RESERVED' or new.version<>0
   or num_nonnulls(new.provider_reference,new.first_dispatched_at,new.retry_before,new.lease_owner,new.lease_expires_at,new.safe_error_code)<>0
   or new.recovery_attempts<>0 or new.manual_review_required or new.next_attempt_at<>transaction_timestamp()
  then raise exception 'Invalid pristine owned grace reservation' using errcode='23514';end if;return new;
 end if;
 select * into q from stripe_billing_failed_grace_boundary_claims where preparation_id=p.id and phase=h and operation_id=old.id;
 if q.preparation_id is null then raise exception 'Owned grace original claim required' using errcode='23514';end if;
 if new.status='UNKNOWN' then perform owned_failed_grace_limits(2000,true);perform lock_owned_grace_boundary(p.id,h,true);
 else
  perform owned_failed_grace_limits(greatest(1,floor(extract(epoch from(q.deadline_at-clock_timestamp()))*1000)::bigint),false);
  perform lock_owned_grace_boundary(p.id,h,false);
 end if;db_at:=clock_timestamp();
 if not owned_grace_boundary_claim_shape(q,old) then raise exception 'Boundary claim lost original identity' using errcode='23514';end if;
 if old.status='RESERVED' and old.version=0 and new.status='DISPATCHING' and new.version=1 then
  if q.producer_xid<>pg_current_xact_id() or not owned_grace_predecessor_committed(p.id,h,clock_timestamp(),pg_current_xact_id())
   or row(new.first_dispatched_at,new.lease_owner,new.lease_expires_at) is distinct from row(q.claimed_at,q.claimant,q.deadline)
   or new.retry_before<>q.claimed_at+interval '23 hours' or new.provider_reference is not null or db_at>=q.deadline_at or db_at>=q.deadline
  then raise exception 'Invalid original owned grace dispatch' using errcode='23514';end if;
 elsif old.status='DISPATCHING' and old.version=1 and new.status in ('ACKNOWLEDGED','UNKNOWN') and new.version=2 then
  if q.producer_xid=pg_current_xact_id()
   or row(old.first_dispatched_at,old.lease_owner,old.lease_expires_at) is distinct from row(q.claimed_at,q.claimant,q.deadline)
   or row(new.first_dispatched_at,new.retry_before) is distinct from row(old.first_dispatched_at,old.retry_before)
   or new.lease_owner is not null or new.lease_expires_at is not null
  then raise exception 'Independently committed original grace claim required' using errcode='23514';end if;
  if new.status='ACKNOWLEDGED' then
   select r.reference into expected_clock from stripe_billing_first_renewal_ready r where r.id=p.predecessor_ready;
   if not owned_grace_predecessor_committed(p.id,h,clock_timestamp(),pg_current_xact_id()) or db_at>=q.deadline_at or db_at>=q.deadline or new.provider_reference is distinct from expected_clock
   then raise exception 'Invalid owned grace acknowledgment' using errcode='23514';end if;
  elsif new.provider_reference is not null or exists(select 1 from stripe_billing_failed_grace_boundary_ready where preparation_id=p.id and phase=h) then
   raise exception 'Invalid owned grace uncertainty' using errcode='23514';end if;
 else raise exception 'Owned grace cannot be reset, renewed or retried' using errcode='23514';
 end if;return new;
end $$;
create trigger aaaa_owned_grace_boundary_operation before insert or update or delete on provider_operations for each row execute function require_owned_grace_boundary_operation();

create function require_owned_grace_boundary_ack() returns trigger language plpgsql volatile as $$
declare p stripe_billing_failed_grace_preparations%rowtype; q stripe_billing_failed_grace_boundary_claims%rowtype; step provider_operations%rowtype; original record; db_at timestamptz;
begin
 select * into q from stripe_billing_failed_grace_boundary_claims where preparation_id=new.preparation_id and phase=new.phase;
 if q.preparation_id is null then raise exception 'Missing owned grace ACK original claim' using errcode='23514';end if;
 perform owned_failed_grace_limits(greatest(1,floor(extract(epoch from(q.deadline_at-clock_timestamp()))*1000)::bigint),false);
 perform lock_owned_grace_boundary(new.preparation_id,new.phase,false);
 select * into p from stripe_billing_failed_grace_preparations where id=new.preparation_id;
 select * into step from provider_operations where id=new.operation_id for update nowait;
 select * into original from owned_grace_boundary_edge(p.id,new.phase);db_at:=clock_timestamp();
 if new.producer_xid<>pg_current_xact_id() or q.producer_xid=pg_current_xact_id()
  or not owned_grace_boundary_ack_shape(q,new,step,db_at) or not owned_grace_predecessor_committed(p.id,new.phase,clock_timestamp(),pg_current_xact_id())
  or row(new.preparation_id,new.operation_id,new.invocation,new.claimant,new.deadline) is distinct from row(q.preparation_id,q.operation_id,q.invocation,q.claimant,q.deadline)
  or step.status<>'ACKNOWLEDGED' or step.version<>2 or step.provider_reference is distinct from new.reference
  or new.observed_at<q.claimed_at or new.acknowledged_at>db_at or new.acknowledged_at>=q.deadline_at or new.acknowledged_at>=q.deadline
  or db_at>=q.deadline_at or db_at>=q.deadline
  or row(new.reference,new.object_type,new.livemode,new.name,new.provider_created,new.provider_deletes_after)
   is distinct from row(original.reference,original.object_type,original.livemode,original.name,original.provider_created,original.provider_deletes_after)
  or new.advancing_target is not null and new.advancing_target<>(p.target+new.phase-1)
  or not ((new.status='ready' and new.frozen_time=(p.target+new.phase-1)) or (new.status='advancing' and new.frozen_time>=original.frozen_time and new.frozen_time<=(p.target+new.phase-1)))
 then raise exception 'Invalid owned grace exact response ACK' using errcode='23514';end if;
 new.producer_xid:=pg_current_xact_id();return new;
end $$;
create trigger boundary_ack_proof before insert on stripe_billing_failed_grace_boundary_acks for each row execute function require_owned_grace_boundary_ack();

create function require_owned_grace_boundary_ready() returns trigger language plpgsql volatile as $$
declare p stripe_billing_failed_grace_preparations%rowtype; q stripe_billing_failed_grace_boundary_claims%rowtype; ack stripe_billing_failed_grace_boundary_acks%rowtype; step provider_operations%rowtype; db_at timestamptz;
begin
 select * into q from stripe_billing_failed_grace_boundary_claims where preparation_id=new.preparation_id and phase=new.phase;
 if q.preparation_id is null then raise exception 'Missing owned grace READY original claim' using errcode='23514';end if;
 perform owned_failed_grace_limits(greatest(1,floor(extract(epoch from(q.deadline_at-clock_timestamp()))*1000)::bigint),false);
 perform lock_owned_grace_boundary(new.preparation_id,new.phase,false);
 select * into p from stripe_billing_failed_grace_preparations where id=new.preparation_id;
 select * into ack from stripe_billing_failed_grace_boundary_acks where preparation_id=p.id and phase=new.phase;
 select * into step from provider_operations where id=new.operation_id for update nowait;db_at:=clock_timestamp();
 if new.producer_xid<>pg_current_xact_id() or ack.preparation_id is null or ack.producer_xid=pg_current_xact_id() or q.producer_xid=pg_current_xact_id()
  or not owned_grace_boundary_ack_shape(q,ack,step,db_at) or not owned_grace_predecessor_committed(p.id,new.phase,clock_timestamp(),pg_current_xact_id())
  or row(new.preparation_id,new.operation_id,new.invocation) is distinct from row(ack.preparation_id,ack.operation_id,ack.invocation)
  or new.admission_id<>p.admission_id or (new.phase=2 and new.id=q.original_grace_ready_id)
  or step.status<>'ACKNOWLEDGED' or step.version<>2 or step.provider_reference is distinct from new.reference
  or row(new.reference,new.object_type,new.livemode,new.name,new.provider_created,new.provider_deletes_after)
   is distinct from row(ack.reference,ack.object_type,ack.livemode,ack.name,ack.provider_created,ack.provider_deletes_after)
  or new.status<>'ready' or new.frozen_time<>(p.target+new.phase-1) or new.advancing_target is not null and new.advancing_target<>(p.target+new.phase-1)
  or new.observed_at<ack.acknowledged_at or new.observed_at>db_at or new.observed_at>=q.deadline_at or new.observed_at>=q.deadline
  or db_at>=q.deadline_at or db_at>=q.deadline
 then raise exception 'Invalid independent owned grace READY' using errcode='23514';end if;
 new.producer_xid:=pg_current_xact_id();return new;
end $$;
create trigger boundary_ready_proof before insert on stripe_billing_failed_grace_boundary_ready for each row execute function require_owned_grace_boundary_ready();

create function require_owned_grace_boundary_cleanup() returns trigger language plpgsql volatile as $$
declare q stripe_billing_failed_grace_boundary_claims%rowtype; step provider_operations%rowtype;
begin
 perform owned_failed_grace_limits(2000,true);perform lock_owned_grace_boundary(new.preparation_id,new.phase,true);
 select * into q from stripe_billing_failed_grace_boundary_claims where preparation_id=new.preparation_id and phase=new.phase;
 select * into step from provider_operations where id=new.operation_id for update nowait;
 if new.producer_xid<>pg_current_xact_id() or q.preparation_id is null or q.producer_xid=pg_current_xact_id()
  or row(new.preparation_id,new.operation_id,new.invocation) is distinct from row(q.preparation_id,q.operation_id,q.invocation)
  or not owned_grace_boundary_claim_shape(q,step) or step.status not in ('UNKNOWN','ACKNOWLEDGED') or step.version<>2
  or step.first_dispatched_at<>q.claimed_at or step.retry_before<>q.claimed_at+interval '23 hours'
  or step.lease_owner is not null or step.lease_expires_at is not null
  or row(new.claimant,new.deadline) is distinct from row(q.claimant,q.deadline)
  or exists(select 1 from stripe_billing_failed_grace_boundary_acks a where a.operation_id=step.id and a.producer_xid=pg_current_xact_id())
  or new.stopped_at<q.claimed_at or new.stopped_at>clock_timestamp()
  or exists(select 1 from stripe_billing_failed_grace_boundary_ready where preparation_id=q.preparation_id and phase=q.phase)
  or exists(select 1 from stripe_billing_failed_grace_boundary_acks ack where ack.preparation_id=q.preparation_id and ack.phase=q.phase and new.stopped_at<ack.acknowledged_at)
  or (step.status='ACKNOWLEDGED') is distinct from exists(select 1 from stripe_billing_failed_grace_boundary_acks ack where ack.preparation_id=q.preparation_id and ack.phase=q.phase and ack.operation_id=step.id)
  or (step.status='ACKNOWLEDGED' and not exists(select 1 from stripe_billing_failed_grace_boundary_acks a
   where a.operation_id=step.id and owned_grace_boundary_ack_shape(q,a,step,clock_timestamp())))
 then raise exception 'Invalid original invocation grace cleanup receipt' using errcode='23514';end if;
 new.producer_xid:=pg_current_xact_id();return new;
end $$;
create trigger boundary_cleanup_proof before insert on stripe_billing_failed_grace_boundary_cleanup for each row execute function require_owned_grace_boundary_cleanup();


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

-- Shared by the ordinary deferred proof and the supported JPA caller's independent
-- beforeCommit proof after flush. An unrestricted SQL caller can drain deferred events early.
create function require_owned_boundary_positive(q stripe_billing_failed_grace_boundary_claims)
 returns void language plpgsql volatile as $$
declare db_at timestamptz;
begin
 if not owned_grace_predecessor_committed(q.preparation_id,q.phase,clock_timestamp(),pg_current_xact_id()) then
  raise exception 'Owned grace positive authority changed before commit' using errcode='23514';end if;
 db_at:=clock_timestamp();
 if db_at>=q.deadline_at or db_at>=q.deadline then
  raise exception 'Original owned grace positive deadline expired before commit' using errcode='23514';end if;
end $$;

-- Deferred atomicity/provenance validates at constraint execution under the caller's mode.
-- Only receipts actually written by this xid are positive work; later original cleanup stays valid
-- after deadline/authority drift. A committed READY's delayed return does not run this gate again.
create function check_owned_boundary_committed() returns trigger language plpgsql volatile as $$
declare selected uuid; step provider_operations%rowtype; q stripe_billing_failed_grace_boundary_claims%rowtype;
 ack stripe_billing_failed_grace_boundary_acks%rowtype; ready stripe_billing_failed_grace_boundary_ready%rowtype; positive boolean;
begin
 if tg_table_name='provider_operations' then if new.kind not in ('OWNED_GRACE_AT_CLOCK_ADVANCE','OWNED_GRACE_AFTER_CLOCK_ADVANCE') then return null;end if;selected:=new.id;
 else selected:=new.operation_id;end if;
 select * into step from provider_operations where id=selected;
 select * into q from stripe_billing_failed_grace_boundary_claims where operation_id=selected;
 select * into ack from stripe_billing_failed_grace_boundary_acks where operation_id=selected;
 select * into ready from stripe_billing_failed_grace_boundary_ready where operation_id=selected;
 if step.id is null or q.preparation_id is null or not owned_grace_boundary_claim_shape(q,step)
 then raise exception 'Missing committed original grace claim' using errcode='23514';end if;
 if q.producer_xid=pg_current_xact_id() then
  if step.status<>'DISPATCHING' or step.version<>1 or row(step.first_dispatched_at,step.lease_owner,step.lease_expires_at) is distinct from row(q.claimed_at,q.claimant,q.deadline)
   or ack.preparation_id is not null or exists(select 1 from stripe_billing_failed_grace_boundary_ready where preparation_id=q.preparation_id and phase=q.phase)
   or exists(select 1 from stripe_billing_failed_grace_boundary_cleanup where preparation_id=q.preparation_id and phase=q.phase)
  then raise exception 'Original grace claim must independently commit DISPATCHING1' using errcode='23514';end if;
 elsif step.status='ACKNOWLEDGED' then
  if step.version<>2 or ack.preparation_id is null
   or row(ack.preparation_id,ack.operation_id,ack.invocation,ack.claimant,ack.deadline,ack.reference) is distinct from row(q.preparation_id,q.operation_id,q.invocation,q.claimant,q.deadline,step.provider_reference)
  then raise exception 'Atomic exact original grace ACK required' using errcode='23514';end if;
 elsif step.status='UNKNOWN' then
  if step.version<>2 or ack.preparation_id is not null or not exists(select 1 from stripe_billing_failed_grace_boundary_cleanup c where c.preparation_id=q.preparation_id and c.phase=q.phase and c.producer_xid=pg_current_xact_id())
  then raise exception 'Atomic original grace UNKNOWN cleanup required' using errcode='23514';end if;
 elsif step.status<>'DISPATCHING' or step.version<>1 then raise exception 'Invalid committed owned grace state' using errcode='23514';end if;
 if tg_table_name='stripe_billing_failed_grace_boundary_ready' then
  if not exists(select 1 from stripe_billing_failed_grace_boundary_acks a where a.preparation_id=q.preparation_id and a.phase=q.phase and a.producer_xid<>new.producer_xid) then
   raise exception 'Independent grace ACK commit required before READY' using errcode='23514';end if;
 end if;
 positive:=q.producer_xid=pg_current_xact_id()
  or coalesce(ack.producer_xid=pg_current_xact_id(),false)
  or coalesce(ready.producer_xid=pg_current_xact_id(),false);
 if positive then
  -- Separate fresh volatile proof sees mutations made by the writing transaction after its
  -- initial INSERT/callback. Read the deadline clock after that proof, not before its work.
  perform require_owned_boundary_positive(q);
 end if;
 return null;
end $$;
create constraint trigger boundary_provider_committed after insert or update on provider_operations deferrable initially deferred for each row execute function check_owned_boundary_committed();
create constraint trigger boundary_claim_committed after insert on stripe_billing_failed_grace_boundary_claims deferrable initially deferred for each row execute function check_owned_boundary_committed();
create constraint trigger boundary_ack_committed after insert on stripe_billing_failed_grace_boundary_acks deferrable initially deferred for each row execute function check_owned_boundary_committed();
create constraint trigger boundary_ready_committed after insert on stripe_billing_failed_grace_boundary_ready deferrable initially deferred for each row execute function check_owned_boundary_committed();

create function check_owned_boundary_cleanup_committed() returns trigger language plpgsql volatile as $$
begin
 if not exists(select 1 from stripe_billing_failed_grace_preparations p join stripe_billing_failed_renewal_stops s on s.admission_id=p.admission_id
  where p.id=new.preparation_id and s.reason='UNCERTAIN' and s.stopped_at=new.stopped_at
   and s.xmin=pg_current_xact_id()::xid and new.producer_xid=pg_current_xact_id())
 then raise exception 'Atomic original grace terminal stop required' using errcode='23514';end if;return null;
end $$;
create constraint trigger boundary_cleanup_committed after insert on stripe_billing_failed_grace_boundary_cleanup deferrable initially deferred for each row execute function check_owned_boundary_cleanup_committed();
