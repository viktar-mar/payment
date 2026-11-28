-- PAYMENT only. Task7 may add SETUP's distinct pre-Subscription proof with a later migration.
alter table subscription_action_capabilities
 add column billing_key_version varchar(10),
 add column billing_step_id uuid,
 add column billing_step_kind varchar(32),
 add column billing_operation_key varchar(255),
 add column billing_proof varchar(64),
 add column billing_subscription_reference varchar(255),
 add column billing_customer_reference varchar(255),
 add column billing_invoice_reference varchar(255),
 add column billing_allocation_reference varchar(255),
 add column billing_intent_reference varchar(255),
 add column superseded_at timestamptz,
 add column client_session_id uuid,
 add column client_origin varchar(2048),
 add column client_expires_at timestamptz,
 add constraint ck_billing_grant_group check(num_nonnulls(billing_key_version,billing_step_id,billing_step_kind,billing_operation_key,billing_proof,billing_subscription_reference,billing_customer_reference,billing_invoice_reference,billing_allocation_reference,billing_intent_reference) in (0,10)),
 add constraint ck_billing_payment_grant check(billing_key_version is null or (
  purpose='PROVIDER_CLIENT' and billing_key_version ~ '^v[1-9][0-9]{0,8}$' and billing_step_kind='SUBSCRIPTION_CREATE'
  and token_digest ~ '^[0-9a-f]{64}$' and billing_proof ~ '^[0-9a-f]{64}$'
  and billing_subscription_reference ~ '^sub_[A-Za-z0-9_]{1,200}$'
  and billing_customer_reference ~ '^cus_[A-Za-z0-9_]{1,200}$'
  and billing_invoice_reference ~ '^in_[A-Za-z0-9_]{1,200}$'
  and billing_allocation_reference ~ '^inpay_[A-Za-z0-9_]{1,200}$'
  and billing_intent_reference ~ '^pi_[A-Za-z0-9_]{1,200}$'
  and recurring_consent is true and consent_purpose='CREATE' and consent_at is not null and consent_policy_version is not null)),
 add constraint ck_billing_client_binding check(num_nonnulls(client_session_id,client_origin,client_expires_at) in (0,3)
  and (client_session_id is null or (billing_key_version is not null and length(client_origin)>0 and client_expires_at>issued_at and client_expires_at<=expires_at))),
 add constraint ck_billing_grant_superseded check(superseded_at is null or (billing_key_version is not null and consumed_at is null and superseded_at>=expires_at)),
 add constraint fk_billing_grant_step foreign key(billing_step_id,payment_account_id,operation_id,billing_step_kind,billing_subscription_reference)
  references provider_operations(id,payment_account_id,resource_id,kind,provider_reference);
alter table stripe_billing_customers add constraint uq_billing_customer_grant_owner unique(payment_account_id,site_id,customer_id,provider_reference);
alter table subscription_action_capabilities add constraint fk_billing_grant_customer
 foreign key(payment_account_id,site_id,customer_id,billing_customer_reference)
 references stripe_billing_customers(payment_account_id,site_id,customer_id,provider_reference);
create unique index uq_current_billing_action on subscription_action_capabilities(payment_account_id,operation_id)
 where billing_key_version is not null and superseded_at is null;
create index idx_billing_action_step on subscription_action_capabilities(billing_step_id) where billing_step_id is not null;
create index idx_billing_action_customer on subscription_action_capabilities(payment_account_id,site_id,customer_id,billing_customer_reference) where billing_key_version is not null;
-- Session identity is a snapshot, not a restrictive FK or cascading financial-receipt deletion path.
create index idx_billing_action_session on subscription_action_capabilities(client_session_id) where client_session_id is not null;
create function protect_billing_action_identity() returns trigger language plpgsql as $$
begin
 if old.billing_key_version is not null then
  if row(new.id,new.site_id,new.customer_id,new.payment_account_id,new.subscription_id,new.operation_id,new.purpose,new.token_digest,new.issued_at,new.expires_at,
   new.billing_key_version,new.billing_step_id,new.billing_step_kind,new.billing_operation_key,new.billing_proof,new.billing_subscription_reference,new.billing_customer_reference,new.billing_invoice_reference,new.billing_allocation_reference,new.billing_intent_reference,
   new.recurring_consent,new.consent_purpose,new.consent_policy_version,new.consent_at)
   is distinct from row(old.id,old.site_id,old.customer_id,old.payment_account_id,old.subscription_id,old.operation_id,old.purpose,old.token_digest,old.issued_at,old.expires_at,
   old.billing_key_version,old.billing_step_id,old.billing_step_kind,old.billing_operation_key,old.billing_proof,old.billing_subscription_reference,old.billing_customer_reference,old.billing_invoice_reference,old.billing_allocation_reference,old.billing_intent_reference,
   old.recurring_consent,old.consent_purpose,old.consent_policy_version,old.consent_at)
   or old.consumed_at is not null and new.consumed_at is distinct from old.consumed_at
   or old.superseded_at is not null and new.superseded_at is distinct from old.superseded_at
   or old.confirmation_key_digest is not null and row(new.confirmation_authority_scope,new.confirmation_key_digest,new.confirmation_fingerprint) is distinct from row(old.confirmation_authority_scope,old.confirmation_key_digest,old.confirmation_fingerprint)
   or old.client_session_id is not null and row(new.client_session_id,new.client_origin,new.client_expires_at) is distinct from row(old.client_session_id,old.client_origin,old.client_expires_at)
     and not (old.consumed_at is not null and num_nonnulls(new.client_session_id,new.client_origin,new.client_expires_at)=0)
  then raise exception 'Billing action identity is immutable' using errcode='23514'; end if;
 end if;
 return new;
end; $$;
create trigger billing_action_identity before update on subscription_action_capabilities for each row execute function protect_billing_action_identity();
