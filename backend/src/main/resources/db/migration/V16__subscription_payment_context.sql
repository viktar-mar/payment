alter table payments
 add column subscription_id uuid,
 add column billing_record_id uuid,
 add column subscription_billing_kind varchar(16),
 add column subscription_product_id uuid,
 add column subscription_price_id uuid,
 add column subscription_price_version integer,
 add column subscription_previous_price_id uuid,
 add column subscription_previous_price_version integer,
 add column subscription_period_start timestamptz,
 add column subscription_period_end timestamptz,
 add column subscription_invoice_reference varchar(255);

alter table payments drop constraint ck_payments_source, drop constraint ck_payments_shape;
alter table payments add constraint ck_payments_source check(source in ('CATALOG','DYNAMIC','DONATION','SUBSCRIPTION')),
 add constraint ck_payments_shape check(
  (source='CATALOG' and price_id is not null and snapshot_price_key is not null and length(trim(snapshot_price_key))>0
   and snapshot_version is not null and snapshot_amount_minor is not null and snapshot_currency is not null and snapshot_tax_mode is not null
   and snapshot_amount_minor=amount_minor and snapshot_currency=currency and snapshot_tax_mode=tax_mode and external_order_id is null
   and donation_id is null and donation_campaign_id is null and snapshot_campaign_key is null and snapshot_campaign_version is null and snapshot_campaign_currency is null)
  or (source='DYNAMIC' and price_id is null and snapshot_price_key is null and snapshot_version is null and snapshot_amount_minor is null
   and snapshot_currency is null and snapshot_tax_mode is null and external_order_id is not null and length(trim(external_order_id))>0
   and donation_id is null and donation_campaign_id is null and snapshot_campaign_key is null and snapshot_campaign_version is null and snapshot_campaign_currency is null)
  or (source='DONATION' and donation_id is not null and donation_campaign_id is not null
   and snapshot_campaign_key is not null and snapshot_campaign_key ~ '^[a-z0-9][a-z0-9-]{2,62}$'
   and snapshot_campaign_version is not null and snapshot_campaign_version>=0
   and snapshot_campaign_currency is not null and snapshot_campaign_currency=currency
   and tax_mode='NONE' and price_id is null and snapshot_price_key is null and snapshot_version is null
   and snapshot_amount_minor is null and snapshot_currency is null and snapshot_tax_mode is null and external_order_id is null)
  or (source='SUBSCRIPTION' and customer_id is not null and price_id is null and snapshot_price_key is null and snapshot_version is null
   and snapshot_amount_minor is null and snapshot_currency is null and snapshot_tax_mode is null and external_order_id is null
   and donation_id is null and donation_campaign_id is null and snapshot_campaign_key is null and snapshot_campaign_version is null and snapshot_campaign_currency is null)),
 add constraint ck_payments_subscription_context check(
  (source<>'SUBSCRIPTION' and num_nonnulls(subscription_id,billing_record_id,subscription_billing_kind,subscription_product_id,
   subscription_price_id,subscription_price_version,subscription_previous_price_id,subscription_previous_price_version,
   subscription_period_start,subscription_period_end,subscription_invoice_reference)=0)
  or (source='SUBSCRIPTION' and num_nonnulls(subscription_id,billing_record_id,subscription_billing_kind,subscription_product_id,
   subscription_price_id,subscription_price_version,subscription_period_start,subscription_period_end,subscription_invoice_reference)=9
   and subscription_billing_kind in ('INITIAL','RENEWAL','PRORATION') and subscription_price_version>0
   and num_nonnulls(subscription_previous_price_id,subscription_previous_price_version) in (0,2)
   and (subscription_previous_price_version is null or subscription_previous_price_version>0)
   and subscription_period_start<subscription_period_end and subscription_invoice_reference ~ '^[A-Za-z0-9_.:/-]+$')),
 add constraint fk_payment_subscription_customer foreign key(site_id,customer_id,subscription_id) references subscriptions(site_id,customer_id,id),
 add constraint fk_payment_subscription_bill foreign key(site_id,subscription_id,payment_account_id,billing_record_id)
  references subscription_billing_records(site_id,subscription_id,payment_account_id,id);

create unique index uq_subscription_payment_object
 on payments(payment_account_id,provider_payment_reference)
 where source='SUBSCRIPTION' and provider_payment_reference is not null;

-- Validate the entire pinned snapshot even for callers bypassing the application adapter.
create function validate_subscription_payment_context() returns trigger language plpgsql as $$
begin
 if TG_OP='UPDATE' and old.source='SUBSCRIPTION' then
  if (new.source,new.site_id,new.customer_id,new.payment_account_id,new.subscription_id,new.billing_record_id,
      new.subscription_billing_kind,new.subscription_product_id,new.subscription_price_id,new.subscription_price_version,
      new.subscription_previous_price_id,new.subscription_previous_price_version,new.subscription_period_start,
      new.subscription_period_end,new.subscription_invoice_reference,new.amount_minor,new.currency,new.tax_mode)
    is distinct from
     (old.source,old.site_id,old.customer_id,old.payment_account_id,old.subscription_id,old.billing_record_id,
      old.subscription_billing_kind,old.subscription_product_id,old.subscription_price_id,old.subscription_price_version,
      old.subscription_previous_price_id,old.subscription_previous_price_version,old.subscription_period_start,
      old.subscription_period_end,old.subscription_invoice_reference,old.amount_minor,old.currency,old.tax_mode)
    or (old.provider_payment_reference is not null and new.provider_payment_reference is distinct from old.provider_payment_reference) then
   raise exception 'Subscription payment identity and terms are immutable' using errcode='23514';
  end if;
 end if;
 if new.source='SUBSCRIPTION' and not exists (
  select 1 from subscription_billing_records b
  where b.id=new.billing_record_id and b.site_id=new.site_id and b.subscription_id=new.subscription_id
   and b.payment_account_id=new.payment_account_id and b.product_id=new.subscription_product_id
   and b.kind=new.subscription_billing_kind and b.billed_price_id=new.subscription_price_id and b.billed_price_version=new.subscription_price_version
   and b.previous_price_id is not distinct from new.subscription_previous_price_id
   and b.previous_price_version is not distinct from new.subscription_previous_price_version
   and b.period_start=new.subscription_period_start and b.period_end=new.subscription_period_end
   and b.provider_invoice_reference=new.subscription_invoice_reference and b.currency=new.currency
   and b.billed_tax_mode=new.tax_mode and b.amount_due_minor>=new.amount_minor
 ) then raise exception 'Subscription payment must match its billing record' using errcode='23514'; end if;
 return new;
end;
$$;
create trigger subscription_payment_context before insert or update on payments
 for each row execute function validate_subscription_payment_context();
