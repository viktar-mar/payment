-- Billing steps reuse the original dispatch receipts and their existing unique key/resource fences.
alter table provider_operations drop constraint provider_operations_kind_check;
alter table provider_operations add constraint provider_operations_kind_check check (kind in (
 'PRODUCT_CREATE','PRICE_CREATE','PAYMENT_PREPARE','REFUND_CREATE','CUSTOMER_CREATE',
 'SUBSCRIPTION_CREATE','SUBSCRIPTION_UPDATE','SUBSCRIPTION_CANCEL','SETUP_INTENT_CREATE','INVOICE_PAY',
 'SCHEDULE_CREATE','SCHEDULE_UPDATE','SCHEDULE_RELEASE','PORTAL_CONFIGURATION_CREATE','PORTAL_SESSION_CREATE'));

-- Reference presence already proves ACKNOWLEDGED through ck_provider_operation_reference.
-- Include kind so a different object's ACK cannot be supplied as Customer creation proof.
alter table provider_operations add constraint uq_provider_operation_customer_proof
 unique(id,payment_account_id,resource_id,kind,provider_reference);
create table stripe_billing_customers (
 id uuid primary key,
 payment_account_id uuid not null references payment_accounts(id),
 site_id uuid not null,
 customer_id uuid not null,
 provider_reference varchar(255) not null check(provider_reference ~ '^cus_[A-Za-z0-9]{1,251}$'),
 creation_operation_id uuid not null unique,
 creation_operation_kind varchar(32) not null default 'CUSTOMER_CREATE' check(creation_operation_kind='CUSTOMER_CREATE'),
 version bigint not null default 0 check(version=0),
 unique(payment_account_id,site_id,customer_id),
 unique(payment_account_id,provider_reference),
 foreign key(site_id,customer_id) references customers(site_id,id),
 foreign key(creation_operation_id,payment_account_id,customer_id,creation_operation_kind,provider_reference)
  references provider_operations(id,payment_account_id,resource_id,kind,provider_reference)
);
-- FK prefixes not covered by the account-first and operation-first unique indexes above.
create index idx_stripe_billing_customer_owner on stripe_billing_customers(site_id,customer_id);
create function protect_stripe_billing_customer_identity() returns trigger language plpgsql as $$
begin
 if new is distinct from old then
  raise exception 'Stripe Billing Customer identity is immutable' using errcode='23514';
 end if;
 return new;
end; $$;
create trigger stripe_billing_customer_identity before update on stripe_billing_customers
 for each row execute function protect_stripe_billing_customer_identity();

-- Schedule references keep the same owned Subscription mapping boundary as the other kinds.
alter table subscription_provider_objects add constraint ck_subscription_provider_object_kind
 check(object_type in ('SUBSCRIPTION','INVOICE','SETUP','PAYMENT','SCHEDULE'));
