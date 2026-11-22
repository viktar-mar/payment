-- A separate single-use receipt for the optional seven-day trial. V31 remains paid-only.
create view stripe_billing_optional_trial_candidates as
select v.*, encode(sha256(convert_to(to_jsonb(v)::text,'UTF8')),'hex') fingerprint
from (
 select c.run_id,c.id clock_intent,c.payment_account_id account_id,c.site_id,c.customer_id,
        s.id subscription_id,o.id create_operation,p.id price_id,c.external_account_id external_account,
        c.credentials_ref,r.reference clock_reference,c.name clock_name,b.provider_reference customer_reference,
        pm.provider_price_id price_reference,gm.provider_product_id product_reference,o.provider_operation_key operation_key,
        r.frozen_time,r.provider_created clock_created,r.provider_deletes_after clock_deletes_after,t.requested_end trial_end,
        b.id customer_binding,b.creation_operation_id customer_step,r.creation_operation_id clock_step,
        p.product_id,p.price_key,p.price_version,o.fingerprint create_fingerprint,o.expected_revision,o.policy_version
 from stripe_billing_clock_intents c
 join stripe_billing_clock_ready r on r.intent_id=c.id
 join payment_accounts a on a.id=c.payment_account_id
 join sites site on site.id=c.site_id and site.payment_account_id=a.id
 join customers cust on cust.site_id=site.id and cust.id=c.customer_id
 join site_subscription_policies policy on policy.site_id=site.id
 join stripe_billing_customers b on b.payment_account_id=a.id and b.site_id=site.id and b.customer_id=cust.id
 join provider_operations k on k.id=r.creation_operation_id and k.payment_account_id=a.id
 join provider_operations bc on bc.id=b.creation_operation_id and bc.payment_account_id=a.id
 join subscriptions s on s.payment_account_id=a.id and s.site_id=site.id and s.customer_id=cust.id
 join subscription_operations o on o.subscription_id=s.id and o.site_id=site.id and o.customer_id=cust.id
 join subscription_trial_eligibility e on e.site_id=site.id and e.customer_id=cust.id and e.product_id=s.product_id
 join stripe_subscription_trial_proofs t on t.operation_id=o.id and t.subscription_id=s.id and t.payment_account_id=a.id
 join prices p on p.id=s.billing_price_id and p.site_id=site.id and p.product_id=s.product_id
 join products product on product.id=p.product_id and product.site_id=site.id
 join provider_price_mappings pm on pm.price_id=p.id and pm.payment_account_id=a.id
 join provider_product_mappings gm on gm.product_id=product.id and gm.payment_account_id=a.id
 where c.stopped_at is null and c.api_version='2026-09-30.endive'
   and c.external_account_id='acct_1UPiMpELswAd8tpR' and c.credentials_ref='stripe-sandbox-default'
   and a.external_account_id=c.external_account_id and a.credentials_ref=c.credentials_ref
   and a.account_key='stripe-sandbox-default' and a.provider='STRIPE' and a.account_mode='SANDBOX' and a.account_status='ACTIVE'
   and site.status='ACTIVE' and site.site_key='s5a-'||replace(c.run_id::text,'-','') and cust.status='ACTIVE'
   and k.kind='TEST_CLOCK_CREATE' and k.status='ACKNOWLEDGED' and k.resource_id=c.id and k.provider_reference=r.reference
   and k.fingerprint=c.create_fingerprint and k.operation_key=c.create_key
   and k.external_account_id=c.external_account_id and k.credentials_ref=c.credentials_ref
   and bc.kind='CUSTOMER_CREATE' and bc.status='ACKNOWLEDGED' and bc.resource_id=c.customer_id
   and bc.provider_reference=b.provider_reference and bc.fingerprint=r.customer_fingerprint
   and bc.operation_key='billing_customer_'||a.id::text||'_'||site.id::text||'_'||cust.id::text
   and bc.external_account_id=c.external_account_id and bc.credentials_ref=c.credentials_ref
   and o.kind='CREATE' and o.status='RESERVED' and o.provider_reference is null and o.safe_failure_code is null
   and o.authority_scope='customer:'||cust.id::text and o.provider_operation_key='subscription:'||a.id::text||':'||o.id::text||':CREATE'
   and o.fingerprint ~ '^v1[.]trial[.][a-f0-9]{64}$' and o.expected_revision=s.revision
   and policy.enabled and o.policy_version=policy.version
   and e.reserved_by_operation=o.id and e.consumed_at is null and t.step_id is null
   and t.requested_end=r.frozen_time+604800
   and s.provider_reference is null and s.billing_status='INCOMPLETE' and s.entitled_price_id is null
   and s.current_period_start is null and s.current_period_end is null and s.paid_through is null
   and s.trial_start is null and s.trial_end is null
   and s.debt_billing_record_id is null and s.cancellation_effective_at is null and s.pending_operation_id is null
   and p.status='PUBLISHED' and p.published_at is not null and p.price_type='SUBSCRIPTION' and product.status='ACTIVE'
   and p.price_version=1 and p.price_version=s.billing_price_version and p.price_key=s.billing_price_key
   and p.amount_minor=2000 and s.billing_amount_minor=p.amount_minor and p.currency='EUR' and s.currency=p.currency
   and p.billing_interval='MONTH' and s.billing_interval=p.billing_interval and p.trial_days=7 and s.billing_trial_days=p.trial_days
   and p.trial_payment_method_policy='OPTIONAL_DURING_TRIAL' and s.billing_trial_policy=p.trial_payment_method_policy
   and p.tax_mode='INCLUSIVE' and s.billing_tax_mode=p.tax_mode
   and not exists(select 1 from stripe_billing_owned_subscriptions z where z.clock_intent=c.id)
   and not exists(select 1 from subscriptions z where z.site_id=site.id and z.customer_id=cust.id and z.id<>s.id)
   and not exists(select 1 from subscription_operations z where z.subscription_id=s.id and z.id<>o.id)
   and not exists(select 1 from subscription_events z where z.subscription_id=s.id)
   and not exists(select 1 from subscription_billing_records z where z.subscription_id=s.id)
   and not exists(select 1 from payments z where z.site_id=site.id)
   and not exists(select 1 from stripe_subscription_setup_proofs z where z.subscription_id=s.id)
) v;

create table stripe_billing_owned_optional_trial_creates (
 clock_intent uuid primary key references stripe_billing_clock_intents(id),
 subscription_id uuid not null unique references subscriptions(id),
 create_operation uuid not null unique references stripe_subscription_trial_proofs(operation_id),
 claim_id uuid not null unique, step_id uuid not null unique references provider_operations(id),
 fingerprint text not null check(fingerprint ~ '^[a-f0-9]{64}$'),
 trial_end bigint not null, claimed_at timestamptz not null,
 status text not null check(status in ('DISPATCHING','ACKNOWLEDGED','UNKNOWN')),
 subscription_reference text, item_reference text, invoice_reference text, trial_start bigint,
 check((status='ACKNOWLEDGED' and num_nonnulls(subscription_reference,item_reference,invoice_reference,trial_start)=4
    and subscription_reference ~ '^sub_[a-zA-Z0-9]{1,251}$' and item_reference ~ '^si_[a-zA-Z0-9]{1,252}$'
    and invoice_reference ~ '^in_[a-zA-Z0-9]{1,252}$' and trial_start=trial_end-604800)
    or (status<>'ACKNOWLEDGED' and num_nonnulls(subscription_reference,item_reference,invoice_reference,trial_start)=0))
);

create function protect_owned_optional_trial() returns trigger language plpgsql as $$
begin
 -- Each waiter must see the other budget table after acquiring the shared clock lock.
 if current_setting('transaction_isolation') <> 'read committed' then
   raise exception 'Owned clock budget requires READ COMMITTED' using errcode='25000';
 end if;
 if tg_op='DELETE' then raise exception 'Optional trial receipt is immutable' using errcode='23514'; end if;
 if tg_op='INSERT' then
   -- Direct receipt insertion must serialize against V31 before reading the candidate.
   perform 1 from stripe_billing_clock_intents where id=new.clock_intent for update;
   if new.status<>'DISPATCHING' or not exists (
     select 1 from stripe_billing_optional_trial_candidates c join provider_operations p on p.id=new.step_id
     where c.clock_intent=new.clock_intent and c.create_operation=new.create_operation and c.subscription_id=new.subscription_id
       and c.fingerprint=new.fingerprint and c.trial_end=new.trial_end and p.status='DISPATCHING'
       and p.kind='SUBSCRIPTION_CREATE' and p.resource_id=c.create_operation and p.payment_account_id=c.account_id
       and p.operation_key=c.operation_key and p.fingerprint=c.fingerprint and p.external_account_id=c.external_account
       and p.credentials_ref=c.credentials_ref and p.first_dispatched_at=new.claimed_at and p.lease_owner=new.claim_id::text)
     then raise exception 'Missing original optional trial claim' using errcode='23514'; end if;
 elsif old.status<>'DISPATCHING' or new.status not in ('UNKNOWN','ACKNOWLEDGED')
   or row(new.clock_intent,new.subscription_id,new.create_operation,new.claim_id,new.step_id,new.fingerprint,new.trial_end,new.claimed_at)
     is distinct from row(old.clock_intent,old.subscription_id,old.create_operation,old.claim_id,old.step_id,old.fingerprint,old.trial_end,old.claimed_at)
   then raise exception 'Invalid optional trial transition' using errcode='23514';
 elsif new.status='ACKNOWLEDGED' and not exists(
   select 1 from stripe_billing_optional_trial_candidates c join provider_operations p on p.id=old.step_id
   where c.clock_intent=old.clock_intent and c.fingerprint=old.fingerprint and c.frozen_time=new.trial_start
     and p.status='DISPATCHING' and p.lease_owner=old.claim_id::text and p.lease_expires_at>clock_timestamp())
   then raise exception 'Expired optional trial claim' using errcode='23514';
 end if;
 return new;
end $$;
create trigger optional_trial_receipt_guard before insert or update or delete on stripe_billing_owned_optional_trial_creates
 for each row execute function protect_owned_optional_trial();

create function claim_owned_optional_trial(wanted_run uuid,wanted_clock uuid,wanted_subscription uuid,wanted_operation uuid,
 expected_fingerprint text,claim uuid) returns boolean language plpgsql as $$
declare c stripe_billing_optional_trial_candidates%rowtype; step uuid:=gen_random_uuid(); at_time timestamptz;
begin
 if claim is null then return false; end if;
 -- Serialize this isolated budget before inspecting any competing original step.
 perform 1 from stripe_billing_clock_intents where id=wanted_clock and run_id=wanted_run and stopped_at is null for update;
 if not found then return false; end if;
 select * into c from stripe_billing_optional_trial_candidates where clock_intent=wanted_clock and run_id=wanted_run
   and subscription_id=wanted_subscription and create_operation=wanted_operation and fingerprint=expected_fingerprint for share;
 if not found or exists(select 1 from stripe_billing_owned_optional_trial_creates where clock_intent=wanted_clock)
   or exists(select 1 from provider_operations p where p.payment_account_id=c.account_id and (p.resource_id=c.create_operation or p.operation_key=c.operation_key))
   then return false; end if;
 at_time:=clock_timestamp();
 insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,fingerprint,
   status,first_dispatched_at,retry_before,lease_owner,lease_expires_at,version)
 values(step,c.account_id,c.external_account,c.credentials_ref,'SUBSCRIPTION_CREATE',c.create_operation,c.operation_key,c.fingerprint,
   'DISPATCHING',at_time,at_time+interval '23 hours',claim::text,at_time+interval '60 seconds',1);
 insert into stripe_billing_owned_optional_trial_creates(clock_intent,subscription_id,create_operation,claim_id,step_id,fingerprint,trial_end,claimed_at,status)
 values(wanted_clock,wanted_subscription,wanted_operation,claim,step,c.fingerprint,c.trial_end,at_time,'DISPATCHING');
 return true;
end $$;

create function finish_owned_optional_trial(claim uuid,sub_ref text,item text,invoice text,start_at bigint)
 returns boolean language plpgsql as $$
declare r stripe_billing_owned_optional_trial_creates%rowtype; ack boolean:=num_nonnulls(sub_ref,item,invoice,start_at)=4;
begin
 if num_nonnulls(sub_ref,item,invoice,start_at) not in (0,4) then return false; end if;
 select * into r from stripe_billing_owned_optional_trial_creates where claim_id=claim for update;
 if not found or r.status<>'DISPATCHING' then return false; end if;
 if ack then
  perform 1 from stripe_billing_optional_trial_candidates c where c.clock_intent=r.clock_intent and c.create_operation=r.create_operation
    and c.subscription_id=r.subscription_id and c.fingerprint=r.fingerprint and c.frozen_time=start_at for share;
  if not found then return false; end if;
 end if;
 perform 1 from provider_operations p where p.id=r.step_id and p.status='DISPATCHING'
   and p.lease_owner=claim::text and (not ack or p.lease_expires_at>clock_timestamp()) for update;
 if not found then return false; end if;
 update stripe_billing_owned_optional_trial_creates set status=case when ack then 'ACKNOWLEDGED' else 'UNKNOWN' end,
   subscription_reference=sub_ref,item_reference=item,invoice_reference=invoice,trial_start=start_at where claim_id=claim;
 update provider_operations set status=case when ack then 'ACKNOWLEDGED' else 'UNKNOWN' end,provider_reference=sub_ref,
   lease_owner=null,lease_expires_at=null,version=version+1 where id=r.step_id;
 if ack then
   update stripe_subscription_trial_proofs set step_id=r.step_id,subscription_reference=sub_ref,item_reference=item,
     invoice_reference=invoice,trial_start=start_at where operation_id=r.create_operation and subscription_id=r.subscription_id
     and requested_end=r.trial_end and step_id is null;
   if not found then raise exception 'Original trial proof no longer available' using errcode='23514'; end if;
 end if;
 return true;
end $$;

create function protect_optional_trial_provider_step() returns trigger language plpgsql as $$
declare r stripe_billing_owned_optional_trial_creates%rowtype;
begin
 if old.kind<>'SUBSCRIPTION_CREATE' then if tg_op='DELETE' then return old; end if; return new; end if;
 select * into r from stripe_billing_owned_optional_trial_creates where step_id=old.id;
 if not found then if tg_op='DELETE' then return old; end if; return new; end if;
 if tg_op='DELETE' or old.status<>'DISPATCHING' or r.status='DISPATCHING' or new.status<>r.status
   or new.provider_reference is distinct from r.subscription_reference or new.lease_owner is not null or new.lease_expires_at is not null
   or new.version<>old.version+1
   or to_jsonb(new)-array['status','provider_reference','lease_owner','lease_expires_at','version'] <>
      to_jsonb(old)-array['status','provider_reference','lease_owner','lease_expires_at','version']
   then raise exception 'Optional trial step requires original claim' using errcode='23514'; end if;
 return new;
end $$;
create trigger optional_trial_provider_step_guard before update or delete on provider_operations
 for each row execute function protect_optional_trial_provider_step();

-- V31 and the optional-trial case compete for the same single clock budget.
create function exclude_optional_trial_from_paid_clock() returns trigger language plpgsql as $$
begin
 -- This trigger exists only in the opted-in fixture database.
 if current_setting('transaction_isolation') <> 'read committed' then
   raise exception 'Owned clock budget requires READ COMMITTED' using errcode='25000';
 end if;
 perform 1 from stripe_billing_clock_intents c join sites site on site.id=c.site_id
   where c.id=new.clock_intent and (site.site_key='s5a-'||replace(c.run_id::text,'-','')
     or exists(select 1 from stripe_billing_owned_optional_trial_creates r where r.clock_intent=c.id))
   for update of c;
 if not found then return new; end if;
 if exists(select 1 from stripe_billing_owned_optional_trial_creates where clock_intent=new.clock_intent) then
   raise exception 'Clock already has an optional trial claim' using errcode='23514';
 end if;
 return new;
end $$;
create trigger exclude_optional_trial_from_paid_clock before insert on stripe_billing_owned_subscriptions
 for each row execute function exclude_optional_trial_from_paid_clock();

-- A direct status update cannot publish an orphan ACK or release a consumed budget.
create function require_complete_owned_optional_trial() returns trigger language plpgsql as $$
begin
 if not exists (
   select 1 from stripe_billing_owned_optional_trial_creates r
   join provider_operations p on p.id=r.step_id and p.status=r.status
   join stripe_subscription_trial_proofs t on t.operation_id=r.create_operation
   where r.clock_intent=new.clock_intent
     and (r.status<>'ACKNOWLEDGED' and t.step_id is null
       or r.status='ACKNOWLEDGED' and p.provider_reference=r.subscription_reference
         and (t.step_id,t.subscription_reference,t.item_reference,t.invoice_reference,t.trial_start,t.requested_end)=
             (r.step_id,r.subscription_reference,r.item_reference,r.invoice_reference,r.trial_start,r.trial_end))
 ) then raise exception 'Optional trial evidence must be atomic' using errcode='23514'; end if;
 return null;
end $$;
create constraint trigger optional_trial_evidence_atomic after insert or update on stripe_billing_owned_optional_trial_creates
 deferrable initially deferred for each row execute function require_complete_owned_optional_trial();
