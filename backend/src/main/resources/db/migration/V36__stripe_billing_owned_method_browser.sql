-- Retention-safe original enrollment: ephemeral session cleanup never deletes the historical permit.
alter table stripe_billing_failed_method_reservations
 add column browser_session_id uuid, add column browser_origin varchar(2048), add column browser_session_expires_at timestamptz,
 add constraint owned_method_browser_enrollment check(num_nonnulls(browser_session_id,browser_origin,browser_session_expires_at) in (0,3)
  and (browser_session_id is null or (browser_origin ~ '^https?://[^[:space:]]+$' and browser_session_expires_at>reserved_at)));

create function owned_method_browser_request(admission uuid, origin text) returns text language sql stable as $$
 select owned_method_stripe_hash('customer:'||d.customer_id::text,'SiteId[value='||d.site_id::text||']',
 'CustomerId[value='||d.customer_id::text||']',$2,'owned-failed-renewal-method-v1',d.id::text,f.renewal_bill::text,
 f.bill_version::text,f.invoice_reference,f.allocation_reference,f.payment_intent_reference,f.projected_revision::text,
 f.observation_sequence::text,'SETUP','true')
 from stripe_billing_failed_renewals d join stripe_billing_failed_renewal_failures f on f.admission_id=d.id where d.id=$1
$$;
create function require_owned_method_browser_enrollment() returns trigger language plpgsql as $$
begin
 -- Also serializes direct provider-step/proof inserts with enrollment.
 perform 1 from subscription_operations where id=new.setup_operation for update;
 if new.browser_session_id is not null then
  perform 1 from customer_sessions where id=new.browser_session_id for share;
  if new.request_fingerprint is distinct from owned_method_browser_request(new.admission_id,new.browser_origin)
   or not exists(select 1 from subscription_operations where id=new.setup_operation and authority_scope='owned-failed-renewal-method:'||new.admission_id::text)
   or not exists(select 1 from customer_sessions c join stripe_billing_failed_renewals d on d.id=new.admission_id
    join customers customer on customer.id=d.customer_id and customer.site_id=d.site_id
    join sites site on site.id=d.site_id join site_allowed_origins origin on origin.site_id=site.id and origin.origin=c.origin
    where c.id=new.browser_session_id and row(c.site_id,c.customer_id,c.origin,c.expires_at)
     =row(d.site_id,d.customer_id,new.browser_origin,new.browser_session_expires_at)
    and customer.status='ACTIVE' and site.status='ACTIVE' and c.revoked_at is null and c.scopes ? 'SUBSCRIPTION_MANAGE'
    and c.issued_at<=new.reserved_at and new.reserved_at<=clock_timestamp() and clock_timestamp()<c.expires_at)
   or exists(select 1 from stripe_subscription_setup_proofs where operation_id=new.setup_operation)
   or exists(select 1 from provider_operations where resource_id=new.setup_operation)
  then raise exception 'Invalid original owned method browser enrollment' using errcode='23514'; end if;
 end if;
 return new;
end $$;
create trigger aab_owned_method_browser_enrollment before insert on stripe_billing_failed_method_reservations
 for each row execute function require_owned_method_browser_enrollment();

alter table stripe_subscription_setup_proofs add constraint owned_method_browser_proof_identity
 unique(operation_id,step_id,setup_reference,payment_account_id);
create table stripe_billing_method_browser_roots (
 id uuid primary key, reservation_id uuid not null unique, setup_operation uuid not null unique,
 setup_step uuid not null unique, step_version bigint not null check(step_version=2),
 payment_account_id uuid not null, setup_kind varchar(32) not null default 'SETUP_INTENT_CREATE' check(setup_kind='SETUP_INTENT_CREATE'),
 setup_reference varchar(255) not null check(setup_reference ~ '^seti_[A-Za-z0-9]{1,250}$'),
 key_version varchar(16) not null check(key_version ~ '^v[1-9][0-9]{0,8}$'), digest varchar(64) not null unique check(digest ~ '^[a-f0-9]{64}$'),
 issued_at timestamptz not null, expires_at timestamptz not null check(expires_at>issued_at and expires_at<=issued_at+interval '600 seconds'),
 foreign key(reservation_id,setup_operation) references stripe_billing_failed_method_reservations(id,setup_operation),
 foreign key(setup_operation,setup_step,setup_reference,payment_account_id)
  references stripe_subscription_setup_proofs(operation_id,step_id,setup_reference,payment_account_id),
 foreign key(setup_step,payment_account_id,setup_operation,setup_kind,setup_reference)
  references provider_operations(id,payment_account_id,resource_id,kind,provider_reference)
);
create table stripe_billing_method_browser_selections (
 root_id uuid primary key references stripe_billing_method_browser_roots(id),
 key_digest varchar(64) not null check(key_digest ~ '^[a-f0-9]{64}$'), started_at timestamptz not null,
 deadline_at timestamptz not null check(deadline_at>started_at and deadline_at<=started_at+interval '30 seconds')
);
create table stripe_billing_method_browser_releases (
 root_id uuid primary key references stripe_billing_method_browser_selections(root_id), released_at timestamptz not null,
 recorded_transaction xid8 not null default pg_current_xact_id()
);
create table stripe_billing_method_browser_consumptions (
 root_id uuid primary key references stripe_billing_method_browser_releases(root_id),
 key_digest varchar(64) not null check(key_digest ~ '^[a-f0-9]{64}$'),
 request_fingerprint varchar(64) not null check(request_fingerprint ~ '^[a-f0-9]{64}$'), consumed_at timestamptz not null,
 recorded_transaction xid8 not null default pg_current_xact_id()
);
create trigger method_browser_root_immutable before update or delete on stripe_billing_method_browser_roots for each row execute function protect_failed_renewal_evidence();
create trigger method_browser_selection_immutable before update or delete on stripe_billing_method_browser_selections for each row execute function protect_failed_renewal_evidence();
create trigger method_browser_release_immutable before update or delete on stripe_billing_method_browser_releases for each row execute function protect_failed_renewal_evidence();
create trigger method_browser_consumption_immutable before update or delete on stripe_billing_method_browser_consumptions for each row execute function protect_failed_renewal_evidence();

-- Client authority is live only during the browser phase. Positional arguments avoid SQL name shadowing.
create function owned_method_browser_pending(reservation uuid) returns boolean language sql stable as $$
 select exists(select 1 from stripe_billing_failed_method_reservations r
 join stripe_billing_failed_renewals d on d.id=r.admission_id join subscription_operations o on o.id=r.setup_operation
 join customer_sessions c on c.id=r.browser_session_id join customers customer on customer.id=d.customer_id and customer.site_id=d.site_id
 join sites site on site.id=d.site_id join site_allowed_origins origin on origin.site_id=site.id and origin.origin=c.origin
 join stripe_subscription_setup_proofs p on p.operation_id=o.id join provider_operations step on step.id=p.step_id
 join payment_accounts account on account.id=d.payment_account_id
 where r.id=$1 and owned_method_debt(r.id,0,o.id) and owned_method_initial_operation(r.id)
 and r.request_fingerprint=owned_method_browser_request(d.id,r.browser_origin)
 and row(c.site_id,c.customer_id,c.origin,c.expires_at)=row(d.site_id,d.customer_id,r.browser_origin,r.browser_session_expires_at)
 and c.revoked_at is null and c.scopes ? 'SUBSCRIPTION_MANAGE' and c.issued_at<=r.reserved_at
 and customer.status='ACTIVE' and site.status='ACTIVE'
 and row(p.site_id,p.customer_id,p.subscription_id,p.payment_account_id,p.operation_kind)
  =row(d.site_id,d.customer_id,d.subscription_id,d.payment_account_id,'SETUP'::varchar)
 and p.method_reference is null
 and row(step.payment_account_id,step.resource_id,step.kind,step.status,step.provider_reference,step.operation_key,step.fingerprint)
  =row(account.id,o.id,'SETUP_INTENT_CREATE'::varchar,'ACKNOWLEDGED'::varchar,p.setup_reference,o.provider_operation_key||':SETUP',owned_method_step_fingerprint(r.id,false))
 and step.version=2 and step.first_dispatched_at is not null and step.lease_owner is null and step.lease_expires_at is null
 and row(step.external_account_id,step.credentials_ref)=row(account.external_account_id,account.credentials_ref)
 and account.provider='STRIPE' and account.account_mode='SANDBOX' and account.account_status='ACTIVE'
 and not exists(select 1 from provider_operations method where method.resource_id=o.id and method.kind='SUBSCRIPTION_UPDATE')
 and not exists(select 1 from stripe_billing_failed_method_completions where reservation_id=r.id)
 and not exists(select 1 from stripe_billing_failed_method_recoveries where reservation_id=r.id))
$$;
-- The caller holds existing operation/authority/account/subscription/bill locks before taking a root lock.
-- Direct receipt inserts serialize on the same operation. Sample clock_timestamp only AFTER waits.
create function lock_owned_method_browser(reservation uuid) returns void language plpgsql volatile as $$
declare operation uuid; session uuid;
begin
 select setup_operation,browser_session_id into operation,session from stripe_billing_failed_method_reservations where id=$1;
 if operation is null or session is null then raise exception 'Owned method browser unavailable' using errcode='23514'; end if;
 perform 1 from subscription_operations where id=operation for update;
 perform 1 from customer_sessions where id=session for share;
 perform 1 from stripe_billing_method_browser_roots where reservation_id=$1 for update;
end $$;
create function require_owned_method_browser_receipt() returns trigger language plpgsql volatile as $$
declare reservation uuid; root stripe_billing_method_browser_roots%rowtype;
 selection stripe_billing_method_browser_selections%rowtype; at timestamptz; db_at timestamptz; session_expiry timestamptz;
begin
 if tg_table_name='stripe_billing_method_browser_roots' then reservation:=new.reservation_id;
 else select reservation_id into reservation from stripe_billing_method_browser_roots where id=new.root_id; end if;
 perform lock_owned_method_browser(reservation);
 db_at:=clock_timestamp();
 select browser_session_expires_at into session_expiry from stripe_billing_failed_method_reservations where id=reservation;
 if not owned_method_browser_pending(reservation) or db_at>=session_expiry then
  raise exception 'Owned method browser unavailable' using errcode='23514'; end if;
 if tg_table_name='stripe_billing_method_browser_roots' then
  if new.issued_at>db_at or db_at>=new.expires_at or new.expires_at<>least(new.issued_at+interval '600 seconds',session_expiry)
   or not exists(select 1 from stripe_billing_failed_method_reservations r
    join stripe_subscription_setup_proofs p on p.operation_id=r.setup_operation join provider_operations step on step.id=p.step_id
    where r.id=reservation and new.issued_at>=r.reserved_at and new.issued_at>=step.first_dispatched_at
     and row(new.setup_operation,new.setup_step,new.step_version,new.setup_reference,new.payment_account_id)
      =row(r.setup_operation,p.step_id,step.version,p.setup_reference,p.payment_account_id))
  then raise exception 'Invalid owned method browser root' using errcode='23514'; end if;
 else
  select * into root from stripe_billing_method_browser_roots where id=new.root_id;
  if tg_table_name='stripe_billing_method_browser_selections' then
   at:=new.started_at;
   if new.deadline_at<>least(new.started_at+interval '30 seconds',root.expires_at)
    or exists(select 1 from stripe_billing_method_browser_releases where root_id=root.id)
    or exists(select 1 from stripe_billing_method_browser_consumptions where root_id=root.id)
   then raise exception 'Invalid owned method browser selection' using errcode='23514'; end if;
  else
   select * into selection from stripe_billing_method_browser_selections where root_id=root.id;
   if tg_table_name='stripe_billing_method_browser_releases' then
    at:=new.released_at;
    if new.recorded_transaction<>pg_current_xact_id() or selection.root_id is null or at<selection.started_at or at>=selection.deadline_at or db_at>=selection.deadline_at
     or exists(select 1 from stripe_billing_method_browser_consumptions where root_id=root.id)
    then raise exception 'Invalid owned method browser release' using errcode='23514'; end if;
   else
    at:=new.consumed_at;
    -- Top-level xid8 identity remains exact across savepoints and transaction-ID wraparound.
    -- The 30 second selection window ended at release, not while the customer types card details.
    if new.recorded_transaction<>pg_current_xact_id() or not exists(select 1 from stripe_billing_method_browser_releases where root_id=root.id and released_at<=at
      and recorded_transaction<>pg_current_xact_id())
    then raise exception 'Missing owned method browser release' using errcode='23514'; end if;
   end if;
  end if;
  if at<root.issued_at or at>db_at or at>=root.expires_at or db_at>=root.expires_at then
   raise exception 'Expired owned method browser receipt' using errcode='23514'; end if;
 end if;
 return new;
end $$;
create trigger method_browser_root_proof before insert on stripe_billing_method_browser_roots for each row execute function require_owned_method_browser_receipt();
create trigger method_browser_selection_proof before insert on stripe_billing_method_browser_selections for each row execute function require_owned_method_browser_receipt();
create trigger method_browser_release_proof before insert on stripe_billing_method_browser_releases for each row execute function require_owned_method_browser_receipt();
create trigger method_browser_consumption_proof before insert on stripe_billing_method_browser_consumptions for each row execute function require_owned_method_browser_receipt();

-- Historical permission: independent of expiry/logout and of the later mutable bound-method field.
create function owned_method_browser_permit(operation uuid) returns boolean language sql stable as $$
 select not exists(select 1 from stripe_billing_failed_method_reservations where setup_operation=$1 and browser_session_id is not null)
 or exists(select 1 from stripe_billing_failed_method_reservations r
 join stripe_billing_method_browser_roots root on root.reservation_id=r.id and root.setup_operation=r.setup_operation
 join stripe_billing_method_browser_selections selection on selection.root_id=root.id
 join stripe_billing_method_browser_releases release on release.root_id=root.id
 join stripe_billing_method_browser_consumptions consumption on consumption.root_id=root.id
 join stripe_subscription_setup_proofs proof on proof.operation_id=root.setup_operation and proof.step_id=root.setup_step
 join provider_operations step on step.id=root.setup_step
 where r.setup_operation=$1 and r.browser_session_id is not null
 and row(step.version,step.status,step.provider_reference)=row(root.step_version,'ACKNOWLEDGED'::varchar,root.setup_reference)
 and proof.setup_reference=root.setup_reference and root.expires_at<=r.browser_session_expires_at
 and selection.started_at>=root.issued_at and selection.deadline_at=least(selection.started_at+interval '30 seconds',root.expires_at)
 and release.released_at>=selection.started_at and release.released_at<selection.deadline_at
 and consumption.consumed_at>=release.released_at and consumption.consumed_at<root.expires_at
 -- Receipt writes grant downstream authority only after their original transaction commits.
 and consumption.recorded_transaction<>pg_current_xact_id())
$$;
create function gate_owned_method_browser_binding() returns trigger language plpgsql volatile as $$
begin
 perform 1 from subscription_operations o where o.id=new.operation_id and
  (o.authority_scope like 'owned-failed-renewal-method:%' or exists(select 1 from stripe_billing_failed_method_reservations r where r.setup_operation=o.id and r.browser_session_id is not null)) for update;
 if new.method_reference is not null and not owned_method_browser_permit(new.operation_id) then
  raise exception 'Owned method browser consumption required' using errcode='23514'; end if;
 return new;
end $$;
create trigger aaa_owned_method_browser_binding before insert or update on stripe_subscription_setup_proofs
 for each row execute function gate_owned_method_browser_binding();
create function gate_owned_method_browser_provider() returns trigger language plpgsql volatile as $$
begin
 if new.kind='SUBSCRIPTION_UPDATE' then
  perform 1 from subscription_operations o where o.id=new.resource_id and
   (o.authority_scope like 'owned-failed-renewal-method:%' or exists(select 1 from stripe_billing_failed_method_reservations r where r.setup_operation=o.id and r.browser_session_id is not null)) for update;
  if not owned_method_browser_permit(new.resource_id) then
   raise exception 'Owned method browser consumption required' using errcode='23514'; end if;
 end if;
 return new;
end $$;
create trigger aaa_owned_method_browser_provider before insert or update on provider_operations
 for each row when (new.kind='SUBSCRIPTION_UPDATE') execute function gate_owned_method_browser_provider();
