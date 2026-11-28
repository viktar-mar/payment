-- Original operation evidence, independent of mutable entitlement and provider status.
alter table subscription_operations add constraint uq_subscription_proof_operation unique(site_id,subscription_id,id,kind);
create table stripe_subscription_trial_proofs (
 operation_id uuid primary key, site_id uuid not null, customer_id uuid not null, subscription_id uuid not null,
 payment_account_id uuid not null, operation_kind varchar(32) not null default 'CREATE' check(operation_kind='CREATE'),
 requested_end bigint not null check(requested_end>0), step_id uuid, step_kind varchar(32) not null default 'SUBSCRIPTION_CREATE' check(step_kind='SUBSCRIPTION_CREATE'),
 subscription_reference varchar(255), item_reference varchar(255), invoice_reference varchar(255), trial_start bigint,
 unique(payment_account_id,subscription_id), unique(payment_account_id,invoice_reference),
 foreign key(site_id,customer_id,subscription_id) references subscriptions(site_id,customer_id,id),
 foreign key(site_id,subscription_id,payment_account_id) references subscriptions(site_id,id,payment_account_id),
 foreign key(site_id,subscription_id,operation_id,operation_kind) references subscription_operations(site_id,subscription_id,id,kind),
 foreign key(step_id,payment_account_id,operation_id,step_kind,subscription_reference) references provider_operations(id,payment_account_id,resource_id,kind,provider_reference),
 check(num_nonnulls(step_id,subscription_reference,item_reference,invoice_reference,trial_start) in (0,5)),
 check(trial_start is null or (trial_start>0 and trial_start<requested_end and subscription_reference ~ '^sub_[A-Za-z0-9]{1,251}$' and item_reference ~ '^si_[A-Za-z0-9]{1,252}$' and invoice_reference ~ '^in_[A-Za-z0-9]{1,252}$'))
);
create index idx_trial_proof_owner on stripe_subscription_trial_proofs(site_id,customer_id,subscription_id);
create index idx_trial_proof_subscription on stripe_subscription_trial_proofs(site_id,subscription_id,payment_account_id);
create index idx_trial_proof_operation on stripe_subscription_trial_proofs(site_id,subscription_id,operation_id,operation_kind);
create index idx_trial_proof_step on stripe_subscription_trial_proofs(step_id);
create table stripe_subscription_setup_proofs (
 operation_id uuid primary key, site_id uuid not null, customer_id uuid not null, subscription_id uuid not null,
 payment_account_id uuid not null, operation_kind varchar(32) not null check(operation_kind in ('CREATE','SETUP')),
 step_id uuid not null, step_kind varchar(32) not null default 'SETUP_INTENT_CREATE' check(step_kind='SETUP_INTENT_CREATE'),
 setup_reference varchar(255) not null check(setup_reference ~ '^seti_[A-Za-z0-9]{1,250}$'),
 method_reference varchar(255) check(method_reference ~ '^pm_[A-Za-z0-9]{1,252}$'),
 unique(payment_account_id,setup_reference),
 foreign key(site_id,customer_id,subscription_id) references subscriptions(site_id,customer_id,id),
 foreign key(site_id,subscription_id,payment_account_id) references subscriptions(site_id,id,payment_account_id),
 foreign key(site_id,subscription_id,operation_id,operation_kind) references subscription_operations(site_id,subscription_id,id,kind),
 foreign key(step_id,payment_account_id,operation_id,step_kind,setup_reference) references provider_operations(id,payment_account_id,resource_id,kind,provider_reference)
);
create index idx_setup_proof_owner on stripe_subscription_setup_proofs(site_id,customer_id,subscription_id);
create index idx_setup_proof_subscription on stripe_subscription_setup_proofs(site_id,subscription_id,payment_account_id);
create index idx_setup_proof_operation on stripe_subscription_setup_proofs(site_id,subscription_id,operation_id,operation_kind);
create index idx_setup_proof_step on stripe_subscription_setup_proofs(step_id);
create index idx_setup_proof_method on stripe_subscription_setup_proofs(payment_account_id,subscription_id,method_reference);
create table stripe_subscription_resume_proofs (
 operation_id uuid primary key, site_id uuid not null, customer_id uuid not null, subscription_id uuid not null,
 payment_account_id uuid not null, operation_kind varchar(32) not null default 'RESUME' check(operation_kind='RESUME'),
 step_id uuid not null, step_kind varchar(32) not null default 'SUBSCRIPTION_UPDATE' check(step_kind='SUBSCRIPTION_UPDATE'),
 subscription_reference varchar(255) not null check(subscription_reference ~ '^sub_[A-Za-z0-9]{1,251}$'),
 invoice_reference varchar(255) not null check(invoice_reference ~ '^in_[A-Za-z0-9]{1,252}$'),
 period_start bigint not null check(period_start>0), period_end bigint not null check(period_end>period_start),
 unique(payment_account_id,invoice_reference),
 foreign key(site_id,customer_id,subscription_id) references subscriptions(site_id,customer_id,id),
 foreign key(site_id,subscription_id,payment_account_id) references subscriptions(site_id,id,payment_account_id),
 foreign key(site_id,subscription_id,operation_id,operation_kind) references subscription_operations(site_id,subscription_id,id,kind),
 foreign key(step_id,payment_account_id,operation_id,step_kind,subscription_reference) references provider_operations(id,payment_account_id,resource_id,kind,provider_reference)
);
create index idx_resume_proof_owner on stripe_subscription_resume_proofs(site_id,customer_id,subscription_id);
create index idx_resume_proof_subscription on stripe_subscription_resume_proofs(site_id,subscription_id,payment_account_id);
create index idx_resume_proof_operation on stripe_subscription_resume_proofs(site_id,subscription_id,operation_id,operation_kind);
create index idx_resume_proof_step on stripe_subscription_resume_proofs(step_id);
create function protect_subscription_billing_proof() returns trigger language plpgsql as $$
begin
 if tg_table_name='stripe_subscription_trial_proofs' then
  if (to_jsonb(new)-array['step_id','subscription_reference','item_reference','invoice_reference','trial_start']) is distinct from
     (to_jsonb(old)-array['step_id','subscription_reference','item_reference','invoice_reference','trial_start'])
     or old.trial_start is not null and new is distinct from old then
   raise exception 'Trial proof is immutable' using errcode='23514'; end if;
 elsif tg_table_name='stripe_subscription_setup_proofs' then
  if (to_jsonb(new)-'method_reference') is distinct from (to_jsonb(old)-'method_reference')
    or old.method_reference is not null and new is distinct from old then
   raise exception 'Setup proof is immutable' using errcode='23514'; end if;
 else
  if new is distinct from old then raise exception 'Resume proof is immutable' using errcode='23514'; end if;
 end if;
 return new;
end; $$;
create trigger trial_proof_identity before update on stripe_subscription_trial_proofs for each row execute function protect_subscription_billing_proof();
create trigger setup_proof_identity before update on stripe_subscription_setup_proofs for each row execute function protect_subscription_billing_proof();
create trigger resume_proof_identity before update on stripe_subscription_resume_proofs for each row execute function protect_subscription_billing_proof();
-- Repeated explicit owned updates acknowledge the same real sub, retaining each original receipt.
drop index uq_provider_operation_reference;
create unique index uq_provider_operation_reference on provider_operations(payment_account_id,kind,provider_reference)
 where provider_reference is not null and kind<>'SUBSCRIPTION_UPDATE';

-- Preserve every PAYMENT v1 descriptor and consumed receipt; discriminate new exact purposes.
alter table subscription_action_capabilities add column billing_schema varchar(32),
 add column billing_setup_reference varchar(255), add column billing_step_reference varchar(255);
update subscription_action_capabilities set billing_schema='payment-v1',billing_step_reference=billing_subscription_reference where billing_key_version is not null;
alter table subscription_action_capabilities drop constraint ck_billing_grant_group,
 drop constraint ck_billing_payment_grant, drop constraint fk_billing_grant_step;
alter table subscription_action_capabilities add constraint ck_billing_grant_group check(
 (billing_key_version is null and num_nonnulls(billing_schema,billing_setup_reference,billing_step_reference,billing_step_id,billing_step_kind,billing_operation_key,billing_proof,billing_subscription_reference,billing_customer_reference,billing_invoice_reference,billing_allocation_reference,billing_intent_reference)=0)
 or (billing_key_version is not null and num_nonnulls(billing_schema,billing_step_reference,billing_step_id,billing_step_kind,billing_operation_key,billing_proof,billing_customer_reference)=7));
alter table subscription_action_capabilities add constraint ck_billing_discriminated_grant check(billing_key_version is null or (
 purpose='PROVIDER_CLIENT' and billing_key_version ~ '^v[1-9][0-9]{0,8}$' and token_digest ~ '^[0-9a-f]{64}$' and billing_proof ~ '^[0-9a-f]{64}$'
 and billing_customer_reference ~ '^cus_[A-Za-z0-9_]{1,200}$' and recurring_consent is true and consent_at is not null and consent_policy_version is not null and consent_purpose is not null
 and ((billing_schema in ('payment-v1','resume-v1') and num_nonnulls(billing_subscription_reference,billing_invoice_reference,billing_allocation_reference,billing_intent_reference)=4
   and billing_setup_reference is null and billing_step_reference=billing_subscription_reference
   and billing_subscription_reference ~ '^sub_[A-Za-z0-9_]{1,200}$' and billing_invoice_reference ~ '^in_[A-Za-z0-9_]{1,200}$'
   and billing_allocation_reference ~ '^inpay_[A-Za-z0-9_]{1,200}$' and billing_intent_reference ~ '^pi_[A-Za-z0-9_]{1,200}$'
   and ((billing_schema='payment-v1' and billing_step_kind='SUBSCRIPTION_CREATE' and consent_purpose='CREATE')
     or (billing_schema='resume-v1' and billing_step_kind='SUBSCRIPTION_UPDATE' and consent_purpose='RESUME')))
 or (billing_schema='setup-v1' and billing_step_kind='SETUP_INTENT_CREATE' and billing_setup_reference is not null and billing_setup_reference ~ '^seti_[A-Za-z0-9]{1,250}$'
   and billing_step_reference=billing_setup_reference and num_nonnulls(billing_invoice_reference,billing_allocation_reference,billing_intent_reference)=0
   and ((consent_purpose='CREATE' and billing_subscription_reference is null) or (consent_purpose='SETUP' and billing_subscription_reference is not null and billing_subscription_reference ~ '^sub_[A-Za-z0-9]{1,251}$'))))));
alter table subscription_action_capabilities add constraint fk_billing_grant_step foreign key(billing_step_id,payment_account_id,operation_id,billing_step_kind,billing_step_reference)
 references provider_operations(id,payment_account_id,resource_id,kind,provider_reference);
create function protect_billing_descriptor_version() returns trigger language plpgsql as $$
begin
 if old.billing_key_version is not null and row(new.billing_schema,new.billing_setup_reference,new.billing_step_reference)
  is distinct from row(old.billing_schema,old.billing_setup_reference,old.billing_step_reference) then
  raise exception 'Billing descriptor is immutable' using errcode='23514'; end if;
 return new;
end; $$;
create trigger billing_descriptor_identity before update on subscription_action_capabilities for each row execute function protect_billing_descriptor_version();
