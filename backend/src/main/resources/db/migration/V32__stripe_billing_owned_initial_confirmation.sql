-- Owned roots reuse the existing confirmation protocol with immutable original receipt identity.
alter table stripe_billing_owned_subscription_receipts add constraint uq_owned_confirmation_receipt
 unique(intent_id,payment_account_id,create_operation,provider_operation,reference);
alter table subscription_action_capabilities add column billing_owned_intent uuid;
alter table subscription_action_capabilities add constraint ck_owned_confirmation_schema check(
 (billing_schema is not distinct from 'owned-initial-v1')=(billing_owned_intent is not null));
alter table subscription_action_capabilities add constraint fk_owned_confirmation_receipt
 foreign key(billing_owned_intent,payment_account_id,operation_id,billing_step_id,billing_subscription_reference)
 references stripe_billing_owned_subscription_receipts(intent_id,payment_account_id,create_operation,provider_operation,reference);
create unique index uq_owned_confirmation_root on subscription_action_capabilities(billing_owned_intent) where billing_owned_intent is not null;
alter table subscription_action_capabilities drop constraint ck_billing_discriminated_grant;
alter table subscription_action_capabilities add constraint ck_billing_discriminated_grant check(billing_key_version is null or (
 purpose='PROVIDER_CLIENT' and billing_key_version ~ '^v[1-9][0-9]{0,8}$' and token_digest ~ '^[0-9a-f]{64}$' and billing_proof ~ '^[0-9a-f]{64}$'
 and billing_customer_reference ~ '^cus_[A-Za-z0-9_]{1,200}$' and recurring_consent is true and consent_at is not null and consent_policy_version is not null and consent_purpose is not null
 and ((billing_schema in ('payment-v1','resume-v1','change-v1','recovery-v1','owned-initial-v1') and num_nonnulls(billing_subscription_reference,billing_invoice_reference,billing_allocation_reference,billing_intent_reference)=4
   and billing_setup_reference is null and billing_step_reference=billing_subscription_reference
   and billing_subscription_reference ~ '^sub_[A-Za-z0-9_]{1,200}$' and billing_invoice_reference ~ '^in_[A-Za-z0-9_]{1,200}$'
   and billing_allocation_reference ~ '^inpay_[A-Za-z0-9_]{1,200}$' and billing_intent_reference ~ '^pi_[A-Za-z0-9_]{1,200}$'
   and ((billing_schema in ('payment-v1','owned-initial-v1') and billing_step_kind='SUBSCRIPTION_CREATE' and consent_purpose='CREATE')
     or (billing_schema='resume-v1' and billing_step_kind='SUBSCRIPTION_UPDATE' and consent_purpose='RESUME')
     or (billing_schema='change-v1' and billing_step_kind='SUBSCRIPTION_UPDATE' and consent_purpose='CHANGE')
     or (billing_schema='recovery-v1' and billing_step_kind='SUBSCRIPTION_CREATE' and consent_purpose='RECOVER')))
 or (billing_schema='setup-v1' and billing_step_kind='SETUP_INTENT_CREATE' and billing_setup_reference is not null and billing_setup_reference ~ '^seti_[A-Za-z0-9]{1,250}$'
   and billing_step_reference=billing_setup_reference and num_nonnulls(billing_invoice_reference,billing_allocation_reference,billing_intent_reference)=0
   and ((consent_purpose='CREATE' and billing_subscription_reference is null) or (consent_purpose='SETUP' and billing_subscription_reference is not null and billing_subscription_reference ~ '^sub_[A-Za-z0-9]{1,251}$'))))));

-- Reads only: lifecycle callers already hold operation -> Site -> Customer -> Account -> Subscription.
-- The Account lock serializes revocation. No provider-step lock may be acquired here.
create function owned_initial_confirmation_available(owned_id uuid) returns boolean language sql stable as $$
 select exists(
  select 1 from stripe_billing_owned_subscriptions i
  join stripe_billing_clock_intents c on c.id=i.clock_intent
  join stripe_billing_clock_ready ready on ready.intent_id=c.id
  join stripe_billing_owned_subscription_receipts r on r.intent_id=i.id
  join provider_operations step on step.id=r.provider_operation
  join provider_operations clock_step on clock_step.id=ready.creation_operation_id
  join provider_operations customer_step on customer_step.id=i.customer_creation_operation
  join stripe_billing_customers customer_binding on customer_binding.id=i.customer_binding
  join payment_accounts a on a.id=i.payment_account_id
  join sites site on site.id=i.site_id
  join customers customer on customer.id=i.customer_id
  join subscriptions s on s.id=i.subscription_id
  join subscription_operations o on o.id=i.create_operation
  join site_subscription_policies policy on policy.site_id=i.site_id
  where i.id=owned_id and c.stopped_at is null and a.account_status='ACTIVE' and a.provider='STRIPE' and a.account_mode='SANDBOX'
   and a.external_account_id=c.external_account_id and a.credentials_ref=c.credentials_ref
   and site.status='ACTIVE' and site.payment_account_id=a.id and customer.status='ACTIVE' and customer.site_id=site.id
   and ready.frozen_time=c.initial_frozen_time and r.period_start<=ready.frozen_time and ready.frozen_time<r.period_end
   and clock_step.status='ACKNOWLEDGED' and clock_step.provider_reference=ready.reference
   and clock_step.external_account_id=c.external_account_id and clock_step.credentials_ref=c.credentials_ref
   and clock_step.operation_key=c.create_key and clock_step.fingerprint=c.create_fingerprint
   and customer_step.status='ACKNOWLEDGED' and customer_step.provider_reference=i.customer_reference
   and customer_step.operation_key='billing_customer_'||c.payment_account_id::text||'_'||c.site_id::text||'_'||c.customer_id::text
   and customer_step.external_account_id=c.external_account_id and customer_step.credentials_ref=c.credentials_ref
   and customer_step.fingerprint=ready.customer_fingerprint and customer_binding.provider_reference=i.customer_reference
   and step.status='ACKNOWLEDGED' and step.kind='SUBSCRIPTION_CREATE' and step.provider_reference=r.reference
   and step.operation_key=i.operation_key and step.fingerprint=i.fingerprint and step.external_account_id=c.external_account_id and step.credentials_ref=c.credentials_ref
   and policy.enabled and policy.version=i.policy_version and o.status in ('PROCESSING','REQUIRES_ACTION','UNKNOWN')
   and o.provider_reference=r.reference and s.provider_reference=r.reference and s.billing_status='INCOMPLETE'
   and s.entitled_price_id is null and s.paid_through is null and s.trial_start is null and s.trial_end is null
   and s.debt_billing_record_id is null and s.pending_operation_id is null and s.cancellation_effective_at is null
   and ((s.current_period_start is null and s.current_period_end is null) or (s.current_period_start=to_timestamp(r.period_start) and s.current_period_end=to_timestamp(r.period_end)))
   and s.billing_price_id=i.price_id and s.billing_price_key=i.price_key and s.billing_price_version=i.price_version
   and s.billing_amount_minor=i.amount_minor and s.currency=i.currency and s.billing_interval=i.billing_interval
   and s.billing_trial_days=i.trial_days and s.billing_trial_policy=i.trial_policy and s.billing_tax_mode=i.tax_mode
 );
$$;
create function protect_owned_confirmation_root() returns trigger language plpgsql as $$
begin
 if tg_op='DELETE' then
  if old.billing_owned_intent is not null then raise exception 'Owned confirmation root cannot be deleted' using errcode='23514'; end if;
  return old;
 end if;
 if tg_op='UPDATE' and new.billing_owned_intent is distinct from old.billing_owned_intent then
  raise exception 'Owned confirmation linkage immutable' using errcode='23514'; end if;
 if new.billing_owned_intent is not null then
  if new.client_session_id is not null or new.confirmation_key_digest is not null or new.superseded_at is not null
   or not owned_initial_confirmation_available(new.billing_owned_intent)
   or not exists(select 1 from stripe_billing_owned_subscriptions i join stripe_billing_owned_subscription_receipts r on r.intent_id=i.id where i.id=new.billing_owned_intent
    and new.issued_at>=r.acknowledged_at
    and i.subscription_id=new.subscription_id and i.site_id=new.site_id and i.customer_id=new.customer_id
    and i.customer_reference=new.billing_customer_reference and i.operation_key=new.billing_operation_key)
  then raise exception 'Owned confirmation authority unavailable' using errcode='23514'; end if;
 elsif new.billing_key_version is not null and exists(select 1 from stripe_billing_clock_intents c
   where c.payment_account_id=new.payment_account_id and c.site_id=new.site_id and c.customer_id=new.customer_id) then
  raise exception 'Admitted owner cannot use ordinary confirmation' using errcode='23514';
 end if;
 return new;
end $$;
create trigger owned_confirmation_root_guard before insert or update or delete on subscription_action_capabilities
 for each row execute function protect_owned_confirmation_root();
create function protect_owned_confirmation_attempt() returns trigger language plpgsql as $$
declare owned_id uuid; target_chain uuid;
begin
 if tg_op='DELETE' then target_chain:=old.chain_id; else target_chain:=new.chain_id; end if;
 select g.billing_owned_intent into owned_id from subscription_confirmation_chains c
  join subscription_action_capabilities g on g.id=c.id where c.id=target_chain;
 if tg_op='DELETE' then
  if owned_id is not null then raise exception 'Owned confirmation attempt cannot be deleted' using errcode='23514'; end if;
  return old;
 end if;
 if owned_id is not null and (new.predecessor is not null or new.successor is not null or new.retry_key is not null
   or tg_op='INSERT' and new.state<>'READY'
   or new.state='DECLINED' or new.request_id is not null or not owned_initial_confirmation_available(owned_id)) then
  raise exception 'Owned confirmation is original first attempt only' using errcode='23514'; end if;
 return new;
end $$;
create trigger owned_confirmation_attempt_guard before insert or update or delete on subscription_confirmation_attempts
 for each row execute function protect_owned_confirmation_attempt();
create function protect_owned_confirmation_chain() returns trigger language plpgsql as $$
declare owned_id uuid; target_root uuid;
begin
 if tg_op='DELETE' then target_root:=old.id; else target_root:=new.id; end if;
 select billing_owned_intent into owned_id from subscription_action_capabilities where id=target_root;
 if tg_op='DELETE' then
  if owned_id is not null then raise exception 'Owned confirmation chain cannot be deleted' using errcode='23514'; end if;
  return old;
 end if;
 if owned_id is not null and not owned_initial_confirmation_available(owned_id) then
  raise exception 'Owned confirmation authority unavailable' using errcode='23514'; end if;
 return new;
end $$;
create trigger owned_confirmation_chain_guard before insert or delete on subscription_confirmation_chains
 for each row execute function protect_owned_confirmation_chain();
