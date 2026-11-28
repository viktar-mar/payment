-- Original nonfinancial cancellation receipts. Never a substitute for current lifecycle facts.
create table stripe_subscription_cancellation_proofs (
 operation_id uuid primary key, site_id uuid not null, customer_id uuid not null, subscription_id uuid not null,
 payment_account_id uuid not null, operation_kind varchar(32) not null check(operation_kind in ('CANCEL','WITHDRAW_CANCEL')),
 step_id uuid not null, step_kind varchar(32) not null check(step_kind in ('SUBSCRIPTION_UPDATE','SUBSCRIPTION_CANCEL')),
 subscription_reference varchar(255) not null check(subscription_reference ~ '^sub_[A-Za-z0-9]{1,251}$'),
 mode varchar(16) not null check(mode in ('PERIOD_END','IMMEDIATE','WITHDRAW')),
 boundary timestamptz, prior_operation uuid unique, prior_mode varchar(16) not null default 'PERIOD_END' check(prior_mode='PERIOD_END'),
 acknowledged_at timestamptz not null,
 unique(operation_id,payment_account_id,subscription_id,mode,boundary),
 foreign key(site_id,customer_id,subscription_id) references subscriptions(site_id,customer_id,id),
 foreign key(site_id,subscription_id,payment_account_id) references subscriptions(site_id,id,payment_account_id),
 foreign key(subscription_id,payment_account_id,subscription_reference) references subscriptions(id,payment_account_id,provider_reference),
 foreign key(site_id,subscription_id,operation_id,operation_kind) references subscription_operations(site_id,subscription_id,id,kind),
 foreign key(step_id,payment_account_id,operation_id,step_kind,subscription_reference) references provider_operations(id,payment_account_id,resource_id,kind,provider_reference),
 foreign key(prior_operation,payment_account_id,subscription_id,prior_mode,boundary)
   references stripe_subscription_cancellation_proofs(operation_id,payment_account_id,subscription_id,mode,boundary),
 check((mode='IMMEDIATE' and operation_kind='CANCEL' and step_kind='SUBSCRIPTION_CANCEL' and boundary is null and prior_operation is null)
    or (mode='PERIOD_END' and operation_kind='CANCEL' and step_kind='SUBSCRIPTION_UPDATE' and boundary is not null and prior_operation is null)
    or (mode='WITHDRAW' and operation_kind='WITHDRAW_CANCEL' and step_kind='SUBSCRIPTION_UPDATE' and boundary is not null and prior_operation is not null)),
 check(boundary is null or (date_trunc('second',boundary)=boundary and acknowledged_at<boundary))
);
create index idx_cancellation_proof_owner on stripe_subscription_cancellation_proofs(site_id,customer_id,subscription_id);
create index idx_cancellation_proof_subscription on stripe_subscription_cancellation_proofs(site_id,subscription_id,payment_account_id);
create index idx_cancellation_proof_operation on stripe_subscription_cancellation_proofs(site_id,subscription_id,operation_id,operation_kind);
create index idx_cancellation_proof_step on stripe_subscription_cancellation_proofs(step_id);
create function protect_stripe_cancellation_proof() returns trigger language plpgsql as $$
begin
 if new is distinct from old then raise exception 'Cancellation receipt is immutable' using errcode='23514'; end if;
 return new;
end; $$;
create trigger stripe_cancellation_proof_identity before update on stripe_subscription_cancellation_proofs
 for each row execute function protect_stripe_cancellation_proof();

alter table subscription_billing_records add constraint uq_recovery_invoice_owner
 unique(id,subscription_id,payment_account_id,provider_invoice_reference);
create table stripe_subscription_recovery_proofs (
 operation_id uuid primary key,site_id uuid not null,customer_id uuid not null,subscription_id uuid not null,payment_account_id uuid not null,
 operation_kind varchar(32) not null default 'RECOVER' check(operation_kind='RECOVER'),billing_record_id uuid not null,
 subscription_reference varchar(255) not null check(subscription_reference ~ '^sub_[A-Za-z0-9]{1,251}$'),
 invoice_reference varchar(255) not null check(invoice_reference ~ '^in_[A-Za-z0-9]{1,252}$'),
 origin_operation uuid not null,origin_operation_kind varchar(32) not null default 'CREATE' check(origin_operation_kind='CREATE'),
 origin_step uuid not null,origin_kind varchar(32) not null default 'SUBSCRIPTION_CREATE' check(origin_kind='SUBSCRIPTION_CREATE'),
 pay_step uuid unique,pay_kind varchar(32),allocation_reference varchar(255),intent_reference varchar(255),
 unique(operation_id,payment_account_id,subscription_id,origin_operation,origin_step,subscription_reference,invoice_reference,allocation_reference,intent_reference),
 foreign key(site_id,customer_id,subscription_id) references subscriptions(site_id,customer_id,id),
 foreign key(site_id,subscription_id,payment_account_id) references subscriptions(site_id,id,payment_account_id),
 foreign key(subscription_id,payment_account_id,subscription_reference) references subscriptions(id,payment_account_id,provider_reference),
 foreign key(site_id,subscription_id,operation_id,operation_kind) references subscription_operations(site_id,subscription_id,id,kind),
 foreign key(site_id,subscription_id,origin_operation,origin_operation_kind) references subscription_operations(site_id,subscription_id,id,kind),
 foreign key(billing_record_id,subscription_id,payment_account_id,invoice_reference) references subscription_billing_records(id,subscription_id,payment_account_id,provider_invoice_reference),
 foreign key(origin_step,payment_account_id,origin_operation,origin_kind,subscription_reference) references provider_operations(id,payment_account_id,resource_id,kind,provider_reference),
 foreign key(pay_step,payment_account_id,operation_id,pay_kind,invoice_reference) references provider_operations(id,payment_account_id,resource_id,kind,provider_reference),
 check((pay_step is null and pay_kind is null and allocation_reference is not null and intent_reference is not null)
    or (pay_step is not null and pay_kind is not null and pay_kind='INVOICE_PAY' and num_nonnulls(allocation_reference,intent_reference) in (0,2))),
 check(allocation_reference is null or allocation_reference ~ '^inpay_[A-Za-z0-9]{1,249}$'),
 check(intent_reference is null or intent_reference ~ '^pi_[A-Za-z0-9]{1,252}$')
);
create index idx_recovery_proof_owner on stripe_subscription_recovery_proofs(site_id,customer_id,subscription_id);
create index idx_recovery_proof_subscription on stripe_subscription_recovery_proofs(site_id,subscription_id,payment_account_id);
create index idx_recovery_proof_invoice on stripe_subscription_recovery_proofs(billing_record_id,subscription_id,payment_account_id,invoice_reference);
create index idx_recovery_proof_origin on stripe_subscription_recovery_proofs(origin_step);
create function protect_stripe_recovery_proof() returns trigger language plpgsql as $$
begin
 if new is distinct from old then raise exception 'Recovery receipt is immutable' using errcode='23514'; end if;
 return new;
end; $$;
create trigger stripe_recovery_proof_identity before update on stripe_subscription_recovery_proofs for each row execute function protect_stripe_recovery_proof();

-- Legacy descriptors retain their original bytes and exact Step resource. Only recovery uses
-- its proven original CREATE resource, joined through the complete distinct RECOVER receipt.
alter table subscription_action_capabilities add column billing_step_resource_id uuid,add column billing_recovery_operation uuid;
update subscription_action_capabilities set billing_step_resource_id=operation_id where billing_key_version is not null;
alter table subscription_action_capabilities drop constraint fk_billing_grant_step;
alter table subscription_action_capabilities add constraint fk_billing_grant_step
 foreign key(billing_step_id,payment_account_id,billing_step_resource_id,billing_step_kind,billing_step_reference)
 references provider_operations(id,payment_account_id,resource_id,kind,provider_reference);
alter table subscription_action_capabilities add constraint ck_recovery_grant_resource check(
 (billing_key_version is null and billing_step_resource_id is null and billing_recovery_operation is null)
 or (billing_key_version is not null and billing_step_resource_id is not null and
   ((billing_schema='recovery-v1' and billing_recovery_operation is not null and billing_recovery_operation=operation_id)
    or (billing_schema<>'recovery-v1' and billing_recovery_operation is null and billing_step_resource_id=operation_id))));
alter table subscription_action_capabilities add constraint fk_recovery_grant_result
 foreign key(billing_recovery_operation,payment_account_id,subscription_id,billing_step_resource_id,billing_step_id,billing_subscription_reference,billing_invoice_reference,billing_allocation_reference,billing_intent_reference)
 references stripe_subscription_recovery_proofs(operation_id,payment_account_id,subscription_id,origin_operation,origin_step,subscription_reference,invoice_reference,allocation_reference,intent_reference);
alter table subscription_action_capabilities drop constraint ck_billing_discriminated_grant;
alter table subscription_action_capabilities add constraint ck_billing_discriminated_grant check(billing_key_version is null or (
 purpose='PROVIDER_CLIENT' and billing_key_version ~ '^v[1-9][0-9]{0,8}$' and token_digest ~ '^[0-9a-f]{64}$' and billing_proof ~ '^[0-9a-f]{64}$'
 and billing_customer_reference ~ '^cus_[A-Za-z0-9_]{1,200}$' and recurring_consent is true and consent_at is not null and consent_policy_version is not null and consent_purpose is not null
 and ((billing_schema in ('payment-v1','resume-v1','change-v1','recovery-v1') and num_nonnulls(billing_subscription_reference,billing_invoice_reference,billing_allocation_reference,billing_intent_reference)=4
   and billing_setup_reference is null and billing_step_reference=billing_subscription_reference
   and billing_subscription_reference ~ '^sub_[A-Za-z0-9_]{1,200}$' and billing_invoice_reference ~ '^in_[A-Za-z0-9_]{1,200}$'
   and billing_allocation_reference ~ '^inpay_[A-Za-z0-9_]{1,200}$' and billing_intent_reference ~ '^pi_[A-Za-z0-9_]{1,200}$'
   and ((billing_schema='payment-v1' and billing_step_kind='SUBSCRIPTION_CREATE' and consent_purpose='CREATE')
     or (billing_schema='resume-v1' and billing_step_kind='SUBSCRIPTION_UPDATE' and consent_purpose='RESUME')
     or (billing_schema='change-v1' and billing_step_kind='SUBSCRIPTION_UPDATE' and consent_purpose='CHANGE')
     or (billing_schema='recovery-v1' and billing_step_kind='SUBSCRIPTION_CREATE' and consent_purpose='RECOVER')))
 or (billing_schema='setup-v1' and billing_step_kind='SETUP_INTENT_CREATE' and billing_setup_reference is not null and billing_setup_reference ~ '^seti_[A-Za-z0-9]{1,250}$'
   and billing_step_reference=billing_setup_reference and num_nonnulls(billing_invoice_reference,billing_allocation_reference,billing_intent_reference)=0
   and ((consent_purpose='CREATE' and billing_subscription_reference is null) or (consent_purpose='SETUP' and billing_subscription_reference is not null and billing_subscription_reference ~ '^sub_[A-Za-z0-9]{1,251}$'))))));
create function protect_stripe_recovery_grant() returns trigger language plpgsql as $$
begin
 if old.billing_key_version is not null and row(new.billing_step_resource_id,new.billing_recovery_operation) is distinct from row(old.billing_step_resource_id,old.billing_recovery_operation) then
  raise exception 'RECOVER grant authority is immutable' using errcode='23514'; end if;
 return new;
end; $$;
create trigger stripe_recovery_grant_identity before update on subscription_action_capabilities for each row execute function protect_stripe_recovery_grant();
