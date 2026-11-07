-- Explicit, unregistered consumer of the failed-renewal READY2 lane. V34 predicates are unchanged.
create table stripe_billing_failed_method_reservations (
 id uuid primary key, admission_id uuid not null unique references stripe_billing_failed_renewal_failures(admission_id),
 setup_operation uuid not null unique references subscription_operations(id), request_fingerprint varchar(255) not null,
 initial_operation_version bigint not null check(initial_operation_version=0), operation_snapshot jsonb not null,
 binding_id uuid not null references stripe_billing_customers(id), binding_version bigint not null check(binding_version>=0),
 binding_reference varchar(255) not null, binding_creation_operation uuid not null references provider_operations(id),
 billing_at timestamptz not null, billing_mode varchar(16) not null check(billing_mode='ISOLATED'), reserved_at timestamptz not null,
 unique(id,admission_id), unique(id,setup_operation)
);
create table stripe_billing_failed_method_reads (
 id uuid primary key, reservation_id uuid not null references stripe_billing_failed_method_reservations(id),
 phase varchar(16) not null check(phase in ('METHOD','RECOVERED')), invocation uuid not null unique,
 started_at timestamptz not null, deadline_at timestamptz not null check(deadline_at=started_at+interval '30 seconds'),
 captured_revision bigint not null check(captured_revision>=0), observation_sequence bigint not null check(observation_sequence>=0),
 selected_operation uuid not null references subscription_operations(id), selected_version bigint not null check(selected_version>=0),
 operation_snapshot jsonb not null, setup_step_snapshot jsonb not null, method_step_snapshot jsonb not null,
 billing_at timestamptz not null, billing_mode varchar(16) not null check(billing_mode='ISOLATED'),
 unique(reservation_id,phase), unique(id,reservation_id)
);
create table stripe_billing_failed_method_completions (
 reservation_id uuid primary key, claim_id uuid not null unique, setup_operation uuid not null unique,
 setup_final_version bigint not null check(setup_final_version>=1),
 setup_step uuid not null references provider_operations(id), setup_step_version bigint not null check(setup_step_version>=1),
 method_step uuid not null references provider_operations(id), method_step_version bigint not null check(method_step_version>=1),
 setup_reference varchar(255) not null check(setup_reference ~ '^seti_[A-Za-z0-9]{1,250}$'),
 method_reference varchar(255) not null check(method_reference ~ '^pm_[A-Za-z0-9]{1,252}$'),
 projected_revision bigint not null check(projected_revision>=0), observation_sequence bigint not null check(observation_sequence>=0),
 completed_at timestamptz not null, check(setup_step<>method_step),
 foreign key(reservation_id,setup_operation) references stripe_billing_failed_method_reservations(id,setup_operation),
 foreign key(claim_id,reservation_id) references stripe_billing_failed_method_reads(id,reservation_id)
);
create table stripe_billing_failed_method_recoveries (
 reservation_id uuid primary key references stripe_billing_failed_method_completions(reservation_id),
 operation_id uuid not null unique references stripe_subscription_recovery_proofs(operation_id),
 observation_sequence bigint not null check(observation_sequence>=0), operation_version bigint not null check(operation_version>=0),
 associated_at timestamptz not null
);
create table stripe_billing_failed_method_publications (
 reservation_id uuid primary key references stripe_billing_failed_method_recoveries(reservation_id),
 claim_id uuid not null unique, operation_id uuid not null unique references stripe_subscription_recovery_proofs(operation_id),
 renewal_payment uuid not null unique references payments(id), projected_revision bigint not null check(projected_revision>=0),
 observation_sequence bigint not null check(observation_sequence>=0), published_at timestamptz not null,
 foreign key(claim_id,reservation_id) references stripe_billing_failed_method_reads(id,reservation_id)
);
create index failed_method_binding on stripe_billing_failed_method_reservations(binding_id);
create index failed_method_read_operation on stripe_billing_failed_method_reads(selected_operation);
create index failed_method_completion_setup on stripe_billing_failed_method_completions(setup_step);
create index failed_method_completion_method on stripe_billing_failed_method_completions(method_step);
create trigger failed_method_reservation_immutable before update or delete on stripe_billing_failed_method_reservations for each row execute function protect_failed_renewal_evidence();
create trigger failed_method_read_immutable before update or delete on stripe_billing_failed_method_reads for each row execute function protect_failed_renewal_evidence();
create trigger failed_method_completion_immutable before update or delete on stripe_billing_failed_method_completions for each row execute function protect_failed_renewal_evidence();
create trigger failed_method_recovery_immutable before update or delete on stripe_billing_failed_method_recoveries for each row execute function protect_failed_renewal_evidence();
create trigger failed_method_publication_immutable before update or delete on stripe_billing_failed_method_publications for each row execute function protect_failed_renewal_evidence();

-- Both decisions lock the same predecessor, and recheck after waiting at READ COMMITTED.
create function exclude_failed_method_branch() returns trigger language plpgsql volatile as $$
declare upstream uuid;
begin
 select first_renewal_admission into upstream from stripe_billing_failed_renewals where id=new.admission_id;
 perform lock_owned_renewal_branch(upstream);
 if tg_table_name='stripe_billing_failed_method_reservations' then
  if exists(select 1 from stripe_billing_failed_renewal_recoveries where admission_id=new.admission_id) then
   raise exception 'Legacy recovery permanently owns this branch' using errcode='23514'; end if;
 else
  if exists(select 1 from stripe_billing_failed_method_reservations where admission_id=new.admission_id) then
   raise exception 'Method reservation permanently owns this branch' using errcode='23514'; end if;
 end if;
 return new;
end $$;
create trigger aaa_method_branch before insert on stripe_billing_failed_method_reservations for each row execute function exclude_failed_method_branch();
create trigger aaa_method_branch before insert on stripe_billing_failed_renewal_recoveries for each row execute function exclude_failed_method_branch();

-- Current immutable lineage, deliberately independent of SETUP status/version.
-- Bind SQL inputs positionally: joined advance.target is bigint and must never shadow the UUID input.
create function owned_method_identity(target uuid) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r
 join stripe_billing_failed_renewals d on d.id=r.admission_id
 join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
 join stripe_billing_owned_subscriptions i on i.id=a.owned_intent
 join stripe_billing_first_renewal_ready ready on ready.id=d.ready_id
 join stripe_billing_first_renewal_steps advance on advance.id=ready.step_id
 join stripe_billing_customers b on b.id=r.binding_id
 join subscription_operations o on o.id=r.setup_operation
 where r.id=$1
 and (to_jsonb(o)-array['status','provider_reference','safe_failure_code','updated_at','version'])
  =(r.operation_snapshot-array['status','provider_reference','safe_failure_code','updated_at','version'])
 and row(b.payment_account_id,b.site_id,b.customer_id,b.version,b.provider_reference,b.creation_operation_id)
  =row(d.payment_account_id,d.site_id,d.customer_id,r.binding_version,r.binding_reference,r.binding_creation_operation)
 and b.id=i.customer_binding and b.creation_operation_id=i.customer_creation_operation and b.provider_reference=i.customer_reference
 and o.kind='SETUP' and o.expected_revision=(select projected_revision from stripe_billing_failed_renewal_failures where admission_id=d.id)
 and o.policy_version=a.policy_version and o.fingerprint=r.request_fingerprint
 and row(o.site_id,o.customer_id,o.subscription_id)=row(d.site_id,d.customer_id,d.subscription_id)
 and o.provider_operation_key='subscription:'||d.payment_account_id::text||':'||o.id::text||':SETUP'
 and r.billing_at=to_timestamp(advance.target) and r.billing_mode='ISOLATED'
 and r.operation_snapshot->>'status'='RESERVED' and (r.operation_snapshot->>'version')::bigint=r.initial_operation_version
 and r.operation_snapshot->>'provider_reference' is null and r.operation_snapshot->>'safe_failure_code' is null)
$$;
create function owned_method_lineage(target uuid) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r where r.id=$1
 and owned_method_identity(r.id) and owned_failed_renewal_authority(r.admission_id)
 and not exists(select 1 from stripe_billing_failed_renewal_recoveries where admission_id=r.admission_id))
$$;
create function owned_method_initial_operation(target uuid) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r join subscription_operations o on o.id=r.setup_operation
 where r.id=$1 and to_jsonb(o)=r.operation_snapshot)
$$;

-- Exact unchanged debt, at either the original failure or the one method successor sequence.
-- This is a new predicate, not a widening of V34's exact original-sequence predicate.
create function owned_method_debt(target uuid, successor integer, allowed uuid) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r
 join stripe_billing_failed_renewals d on d.id=r.admission_id
 join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
 join stripe_billing_failed_renewal_failures f on f.admission_id=d.id
 join subscriptions s on s.id=d.subscription_id
 join subscription_billing_records b on b.id=f.renewal_bill
 join subscription_billing_records initial on initial.id=a.initial_bill
 where r.id=$1 and $2 in (0,1) and owned_method_lineage(r.id)
 and s.revision=f.projected_revision and s.observation_sequence=f.observation_sequence+$2
 and s.billing_status='PAST_DUE' and s.debt_billing_record_id=f.renewal_bill
 and row(s.current_period_start,s.current_period_end,s.debt_period_start,s.debt_period_end,s.paid_through,s.grace_deadline)
  =row(f.period_start,f.period_end,f.period_start,f.period_end,f.period_start,f.grace_deadline)
 and row(b.site_id,b.subscription_id,b.payment_account_id)=row(d.site_id,d.subscription_id,d.payment_account_id)
 and b.kind='RENEWAL' and b.settlement='OPEN' and b.version=f.bill_version and b.provider_invoice_reference=f.invoice_reference
 and row(b.period_start,b.period_end,b.grace_deadline,b.captured_policy_version)=row(f.period_start,f.period_end,f.grace_deadline,a.policy_version)
 and b.previous_price_id is null
 and row(b.product_id,b.billed_price_id,b.billed_price_key,b.billed_price_version,b.billed_amount_minor,b.billed_interval,b.billed_trial_days,b.billed_trial_policy,b.billed_tax_mode,b.currency,b.amount_due_minor)
  =row(initial.product_id,initial.billed_price_id,initial.billed_price_key,initial.billed_price_version,initial.billed_amount_minor,initial.billed_interval,initial.billed_trial_days,initial.billed_trial_policy,initial.billed_tax_mode,initial.currency,initial.amount_due_minor)
 and (select count(*) from subscription_billing_records where subscription_id=s.id)=2
 and (select count(*) from payments where subscription_id=s.id)=1
 and not exists(select 1 from payments where billing_record_id=b.id)
 and not exists(select 1 from subscription_operations other where other.subscription_id=s.id and other.id is distinct from $3
  and other.kind not in ('PORTAL','RECONCILE') and other.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN'))
 and exists(select 1 from subscription_events e where e.subscription_id=s.id and e.billing_record_id=b.id and e.event_type='RENEWAL_FAILED' and e.revision=f.projected_revision))
$$;

-- StripeRequestFingerprint uses UTF-16 lengths rather than PostgreSQL character/octet lengths.
create function owned_method_stripe_hash(variadic fields text[]) returns text language sql immutable as $$
 select encode(sha256(convert_to(string_agg(case when value is null then '-1:' else
  (length(value)+(select count(*) from regexp_split_to_table(value,'') ch where ascii(ch)>65535))::text||':'||value end,'' order by position),'UTF8')),'hex')
 from unnest($1) with ordinality as f(value,position)
$$;
create function owned_method_step_fingerprint(target uuid, method_step boolean) returns text language sql stable as $$
 select case when $2 then owned_method_stripe_hash(d.payment_account_id::text,c.external_account_id,c.credentials_ref,'STRIPE','SANDBOX',
 'SUBSCRIPTION_METHOD_V1',o.id::text,o.provider_operation_key||':METHOD',o.fingerprint,initial.reference,r.binding_reference,p.step_id::text,p.setup_reference,p.method_reference,'consent:true')
 else owned_method_stripe_hash(d.payment_account_id::text,c.external_account_id,c.credentials_ref,'STRIPE','SANDBOX',
 'SETUP_INTENT_CREATE_V1',o.id::text,o.provider_operation_key||':SETUP',o.fingerprint,o.policy_version::text,d.subscription_id::text,d.site_id::text,d.customer_id::text,r.binding_reference,'off_session','card','consent:true') end
 from stripe_billing_failed_method_reservations r join stripe_billing_failed_renewals d on d.id=r.admission_id
 join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
 join stripe_billing_owned_subscriptions i on i.id=a.owned_intent
 join stripe_billing_clock_intents c on c.id=i.clock_intent
 join stripe_billing_owned_subscription_receipts initial on initial.intent_id=i.id
 join subscription_operations o on o.id=r.setup_operation
 left join stripe_subscription_setup_proofs p on p.operation_id=o.id where r.id=$1
$$;
create function owned_method_steps(target uuid) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r
 join stripe_billing_failed_renewals d on d.id=r.admission_id
 join subscription_operations o on o.id=r.setup_operation
 join stripe_subscription_setup_proofs p on p.operation_id=o.id
 join payment_accounts a on a.id=d.payment_account_id
 join subscriptions s on s.id=d.subscription_id
 join provider_operations setup on setup.id=p.step_id
 join provider_operations method on method.payment_account_id=a.id and method.resource_id=o.id and method.kind='SUBSCRIPTION_UPDATE'
 where r.id=$1 and row(p.site_id,p.customer_id,p.subscription_id,p.payment_account_id,p.operation_kind)=row(d.site_id,d.customer_id,d.subscription_id,d.payment_account_id,'SETUP'::varchar)
 and p.method_reference ~ '^pm_[A-Za-z0-9]{1,252}$'
 and row(setup.payment_account_id,setup.resource_id,setup.kind,setup.status,setup.provider_reference,setup.operation_key,setup.fingerprint)
  =row(a.id,o.id,'SETUP_INTENT_CREATE'::varchar,'ACKNOWLEDGED'::varchar,p.setup_reference,o.provider_operation_key||':SETUP',owned_method_step_fingerprint(r.id,false))
 and row(method.status,method.provider_reference,method.operation_key,method.fingerprint)
  =row('ACKNOWLEDGED'::varchar,s.provider_reference,o.provider_operation_key||':METHOD',owned_method_step_fingerprint(r.id,true))
 and setup.first_dispatched_at is not null and method.first_dispatched_at>=setup.first_dispatched_at
 and setup.version=2 and method.version=2
 and setup.lease_owner is null and method.lease_owner is null and setup.lease_expires_at is null and method.lease_expires_at is null
 and row(setup.external_account_id,setup.credentials_ref,method.external_account_id,method.credentials_ref)=row(a.external_account_id,a.credentials_ref,a.external_account_id,a.credentials_ref))
$$;
create function owned_method_pre(target uuid) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r join subscription_operations o on o.id=r.setup_operation
 join stripe_billing_failed_renewals d on d.id=r.admission_id join subscriptions s on s.id=d.subscription_id
 where r.id=$1 and owned_method_debt(r.id,0,o.id) and owned_method_steps(r.id)
 and o.status='PROCESSING' and o.version=r.initial_operation_version+1 and o.provider_reference=s.provider_reference and o.safe_failure_code is null
 and not exists(select 1 from stripe_billing_failed_method_completions where reservation_id=r.id))
$$;
create function owned_method_completed(target uuid) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_completions c
 join stripe_billing_failed_method_reads q on q.id=c.claim_id and q.reservation_id=c.reservation_id and q.phase='METHOD'
 join stripe_billing_failed_method_reservations r on r.id=c.reservation_id
 join stripe_billing_failed_renewal_failures f on f.admission_id=r.admission_id
 join subscription_operations o on o.id=r.setup_operation
 join stripe_subscription_setup_proofs p on p.operation_id=o.id
 join provider_operations setup on setup.id=c.setup_step join provider_operations method on method.id=c.method_step
 where r.id=$1 and owned_method_steps(r.id) and o.status='SUCCEEDED' and o.safe_failure_code is null
 and o.version=q.selected_version+1 and c.setup_final_version=o.version and c.setup_operation=o.id
 and (to_jsonb(o)-array['status','updated_at','version'])=(q.operation_snapshot-array['status','updated_at','version'])
 and to_jsonb(setup)=q.setup_step_snapshot and to_jsonb(method)=q.method_step_snapshot
 and c.setup_step=p.step_id and c.setup_step_version=setup.version and c.method_step_version=method.version
 and c.setup_reference=p.setup_reference and c.method_reference=p.method_reference
 and q.billing_at=r.billing_at and q.billing_mode=r.billing_mode
 and q.selected_operation=o.id and q.captured_revision=f.projected_revision and q.observation_sequence=f.observation_sequence
 and c.projected_revision=f.projected_revision and c.observation_sequence=f.observation_sequence+1)
$$;
create function owned_method_recovery_pre(target uuid) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_recoveries x
 join stripe_billing_failed_method_reservations r on r.id=x.reservation_id
 join stripe_billing_failed_renewal_failures f on f.admission_id=r.admission_id
 join subscription_operations o on o.id=x.operation_id
 where r.id=$1 and owned_method_completed(r.id) and owned_method_debt(r.id,1,o.id)
 and owned_failed_renewal_association(r.admission_id,o.id)
 and o.status='RESERVED' and o.version=x.operation_version and x.observation_sequence=f.observation_sequence+1)
$$;

create function require_failed_method_reservation() returns trigger language plpgsql as $$
begin
 if new.reserved_at>clock_timestamp() or not owned_failed_renewal_failure_current(new.admission_id,new.setup_operation)
 or not exists(select 1 from stripe_billing_failed_renewals d join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
 join stripe_billing_owned_subscriptions i on i.id=a.owned_intent join stripe_billing_first_renewal_ready ready on ready.id=d.ready_id
 join stripe_billing_first_renewal_steps advance on advance.id=ready.step_id join stripe_billing_failed_renewal_failures f on f.admission_id=d.id
 join subscription_operations o on o.id=new.setup_operation join stripe_billing_customers b on b.id=new.binding_id
 where d.id=new.admission_id and new.operation_snapshot=to_jsonb(o) and o.status='RESERVED' and o.version=0
 and o.kind='SETUP' and o.expected_revision=f.projected_revision and o.policy_version=a.policy_version
 and row(o.site_id,o.customer_id,o.subscription_id)=row(d.site_id,d.customer_id,d.subscription_id)
 and o.fingerprint=new.request_fingerprint and o.safe_failure_code is null and o.provider_reference is null
 and o.provider_operation_key='subscription:'||d.payment_account_id::text||':'||o.id::text||':SETUP'
 and o.created_at>=f.recorded_at and new.reserved_at>=o.created_at and new.reserved_at>=f.recorded_at
 and row(b.id,b.version,b.provider_reference,b.creation_operation_id)=row(i.customer_binding,new.binding_version,new.binding_reference,new.binding_creation_operation)
 and row(b.payment_account_id,b.site_id,b.customer_id)=row(d.payment_account_id,d.site_id,d.customer_id)
 and new.billing_at=to_timestamp(advance.target))
 then raise exception 'Invalid owned method reservation' using errcode='23514'; end if;
 return new;
end $$;
create trigger failed_method_reservation_proof before insert on stripe_billing_failed_method_reservations for each row execute function require_failed_method_reservation();
create function require_failed_method_read() returns trigger language plpgsql as $$
begin
 if new.started_at>clock_timestamp() or clock_timestamp()>=new.deadline_at
 or not exists(select 1 from stripe_billing_failed_method_reservations r
 join stripe_billing_failed_renewals d on d.id=r.admission_id join subscriptions s on s.id=d.subscription_id
 join subscription_operations o on o.id=new.selected_operation
 join stripe_subscription_setup_proofs p on p.operation_id=r.setup_operation
 join provider_operations setup on setup.id=p.step_id
 join provider_operations method on method.resource_id=r.setup_operation and method.payment_account_id=d.payment_account_id and method.kind='SUBSCRIPTION_UPDATE'
 where r.id=new.reservation_id and new.started_at>=r.reserved_at and new.billing_at=r.billing_at and new.billing_mode=r.billing_mode
 and row(s.revision,s.observation_sequence,o.version)=row(new.captured_revision,new.observation_sequence,new.selected_version)
 and new.operation_snapshot=to_jsonb(o) and new.setup_step_snapshot=to_jsonb(setup) and new.method_step_snapshot=to_jsonb(method)
 and ((new.phase='METHOD' and new.selected_operation=r.setup_operation and owned_method_pre(r.id))
 or (new.phase='RECOVERED' and owned_method_recovery_pre(r.id) and exists(select 1 from stripe_billing_failed_method_recoveries x where x.reservation_id=r.id and x.operation_id=o.id))))
 then raise exception 'Invalid owned method read prestate' using errcode='23514'; end if;
 return new;
end $$;
create trigger failed_method_read_proof before insert on stripe_billing_failed_method_reads for each row execute function require_failed_method_read();
create function require_failed_method_completion() returns trigger language plpgsql as $$
begin
 if new.completed_at>clock_timestamp() or not exists(select 1 from stripe_billing_failed_method_reads q
 join stripe_billing_failed_method_reservations r on r.id=q.reservation_id
 join stripe_billing_failed_renewal_failures f on f.admission_id=r.admission_id
 join subscription_operations o on o.id=r.setup_operation join stripe_subscription_setup_proofs p on p.operation_id=o.id
 join provider_operations setup on setup.id=p.step_id
 join provider_operations method on method.resource_id=o.id and method.kind='SUBSCRIPTION_UPDATE' and method.payment_account_id=p.payment_account_id
 where q.id=new.claim_id and r.id=new.reservation_id and q.phase='METHOD' and owned_method_debt(r.id,1,null) and owned_method_steps(r.id)
 and new.completed_at>=q.started_at and new.completed_at<q.deadline_at and clock_timestamp()<q.deadline_at
 and o.id=q.selected_operation and o.status='SUCCEEDED' and o.version=q.selected_version+1 and new.setup_operation=o.id and new.setup_final_version=o.version
 and (to_jsonb(o)-array['status','updated_at','version'])=(q.operation_snapshot-array['status','updated_at','version'])
 and q.operation_snapshot->>'status'='PROCESSING' and o.safe_failure_code is null
 and q.billing_at=r.billing_at and q.billing_mode=r.billing_mode
 and to_jsonb(setup)=q.setup_step_snapshot and to_jsonb(method)=q.method_step_snapshot
 and row(new.setup_step,new.setup_step_version,new.method_step,new.method_step_version,new.setup_reference,new.method_reference)
  =row(setup.id,setup.version,method.id,method.version,p.setup_reference,p.method_reference)
 and q.captured_revision=f.projected_revision and q.observation_sequence=f.observation_sequence
 and new.projected_revision=f.projected_revision and new.observation_sequence=f.observation_sequence+1)
 then raise exception 'Invalid owned method completion poststate' using errcode='23514'; end if;
 return new;
end $$;
create trigger failed_method_completion_proof before insert on stripe_billing_failed_method_completions for each row execute function require_failed_method_completion();
create function require_failed_method_recovery() returns trigger language plpgsql as $$
begin
 if new.associated_at>clock_timestamp() or not exists(select 1 from stripe_billing_failed_method_reservations r
 join stripe_billing_failed_method_completions c on c.reservation_id=r.id
 join stripe_billing_failed_renewal_failures f on f.admission_id=r.admission_id
 join subscription_operations o on o.id=new.operation_id
 where r.id=new.reservation_id and owned_method_completed(r.id) and owned_method_debt(r.id,1,o.id)
 and owned_failed_renewal_association(r.admission_id,o.id) and o.status='RESERVED' and o.version=new.operation_version
 and o.created_at>=c.completed_at and new.associated_at>=o.created_at and new.associated_at>=c.completed_at
 and new.observation_sequence=f.observation_sequence+1)
 then raise exception 'Invalid owned method recovery successor' using errcode='23514'; end if;
 return new;
end $$;
create trigger failed_method_recovery_proof before insert on stripe_billing_failed_method_recoveries for each row execute function require_failed_method_recovery();

-- Exact cash successor. It intentionally never calls a METHOD/RECOVERED prestate predicate.
create function owned_method_projected(target uuid)
 returns table(renewal_payment uuid,projected_revision bigint,observation_sequence bigint) language sql stable as $$
 select p.id,s.revision,s.observation_sequence from stripe_billing_failed_method_reservations r
 join stripe_billing_failed_renewals d on d.id=r.admission_id join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
 join stripe_billing_failed_renewal_failures f on f.admission_id=d.id
 join stripe_billing_failed_method_recoveries x on x.reservation_id=r.id
 join stripe_billing_failed_method_reads q on q.reservation_id=r.id and q.phase='RECOVERED'
 join subscription_operations o on o.id=x.operation_id join subscriptions s on s.id=d.subscription_id
 join subscription_billing_records b on b.id=f.renewal_bill join subscription_billing_records initial on initial.id=a.initial_bill
 join payments p on p.billing_record_id=b.id
 where r.id=$1 and owned_method_lineage(r.id) and owned_method_completed(r.id) and owned_failed_renewal_association(d.id,o.id)
 and o.status='SUCCEEDED' and o.version=x.operation_version+1 and o.provider_reference=s.provider_reference and o.safe_failure_code is null
 and (to_jsonb(o)-array['status','provider_reference','updated_at','version'])=(q.operation_snapshot-array['status','provider_reference','updated_at','version'])
 and s.billing_status='ACTIVE' and s.debt_billing_record_id is null and s.debt_period_start is null and s.debt_period_end is null and s.grace_deadline is null
 and row(s.current_period_start,s.current_period_end,s.paid_through)=row(f.period_start,f.period_end,f.period_end)
 and s.revision=f.projected_revision+1 and s.observation_sequence=f.observation_sequence+2
 and q.captured_revision=f.projected_revision and q.observation_sequence=f.observation_sequence+1
 and q.selected_operation=o.id and q.selected_version=x.operation_version and x.observation_sequence=q.observation_sequence
 and q.billing_at=r.billing_at and q.billing_mode=r.billing_mode
 and b.kind='RENEWAL' and b.settlement='CONFIRMED_CASH' and b.version=f.bill_version+1
 and row(b.provider_invoice_reference,b.period_start,b.period_end,b.grace_deadline,b.captured_policy_version)=row(f.invoice_reference,f.period_start,f.period_end,f.grace_deadline,a.policy_version)
 and row(b.site_id,b.subscription_id,b.payment_account_id)=row(d.site_id,d.subscription_id,d.payment_account_id) and b.previous_price_id is null
 and row(b.product_id,b.billed_price_id,b.billed_price_key,b.billed_price_version,b.billed_amount_minor,b.billed_interval,b.billed_trial_days,b.billed_trial_policy,b.billed_tax_mode,b.currency,b.amount_due_minor)
  =row(initial.product_id,initial.billed_price_id,initial.billed_price_key,initial.billed_price_version,initial.billed_amount_minor,initial.billed_interval,initial.billed_trial_days,initial.billed_trial_policy,initial.billed_tax_mode,initial.currency,initial.amount_due_minor)
 and p.provider_payment_reference=f.payment_intent_reference and p.provider_payment_reference<>a.payment_intent_reference
 and p.source='SUBSCRIPTION' and p.status='SUCCEEDED' and p.refunded_amount_minor=0 and p.subscription_billing_kind='RENEWAL'
 and row(p.payment_account_id,p.site_id,p.customer_id,p.subscription_id,p.subscription_product_id,p.subscription_price_id,p.subscription_price_version,p.amount_minor,p.currency,p.tax_mode)
  =row(d.payment_account_id,d.site_id,d.customer_id,d.subscription_id,b.product_id,b.billed_price_id,b.billed_price_version,b.amount_due_minor,b.currency,b.billed_tax_mode)
 and p.subscription_previous_price_id is null and p.subscription_previous_price_version is null and p.tax_amount_minor is null
 and row(p.subscription_period_start,p.subscription_period_end,p.subscription_invoice_reference)=row(b.period_start,b.period_end,b.provider_invoice_reference)
 and (select count(*) from subscription_billing_records where subscription_id=s.id)=2
 and (select count(*) from payments where subscription_id=s.id)=2 and (select count(*) from payments where billing_record_id=b.id)=1
 and not exists(select 1 from subscription_operations other where other.subscription_id=s.id and other.kind not in ('PORTAL','RECONCILE') and other.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN'))
 and exists(select 1 from subscription_events e where e.subscription_id=s.id and e.billing_record_id=b.id and e.event_type='PERIOD_PAID' and e.revision=s.revision)
 and exists(select 1 from payment_events e where e.payment_id=p.id and e.event_type='PAYMENT_SUCCEEDED')
$$;
create function require_failed_method_publication() returns trigger language plpgsql as $$
begin
 if new.published_at>clock_timestamp() or not exists(select 1 from stripe_billing_failed_method_reads q
 join stripe_billing_failed_method_recoveries x on x.reservation_id=q.reservation_id
 join owned_method_projected(new.reservation_id) state on true
 where q.id=new.claim_id and q.reservation_id=new.reservation_id and q.phase='RECOVERED'
 and new.published_at>=q.started_at and new.published_at<q.deadline_at and clock_timestamp()<q.deadline_at
 and new.operation_id=x.operation_id and q.selected_operation=x.operation_id
 and row(new.renewal_payment,new.projected_revision,new.observation_sequence)=row(state.renewal_payment,state.projected_revision,state.observation_sequence))
 then raise exception 'Invalid owned method publication poststate' using errcode='23514'; end if;
 return new;
end $$;
create trigger failed_method_publication_proof before insert on stripe_billing_failed_method_publications for each row execute function require_failed_method_publication();
