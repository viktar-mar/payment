alter table subscriptions add column observed_provider_revision bigint check(observed_provider_revision>=0);
alter table provider_inbox add column event_family varchar(16) not null default 'PAYMENT';
alter table provider_inbox drop constraint ck_inbox_event_type;
alter table provider_inbox add constraint ck_inbox_family_event check (
 (event_family='PAYMENT' and event_type in ('PAYMENT_CREATED','ATTEMPT_CREATED','PAYMENT_METHOD_REQUIRED','CUSTOMER_ACTION_REQUIRED','PAYMENT_PROCESSING','PAYMENT_SUCCEEDED','PAYMENT_FAILED','PAYMENT_CANCELLED','PROVIDER_RESULT_UNKNOWN','REFUND_REQUESTED','REFUND_PROCESSING','REFUND_SUCCEEDED','REFUND_FAILED'))
 or (event_family='SUBSCRIPTION' and event_type in ('SUBSCRIPTION_CHANGED','SUBSCRIPTION_INVOICE_CHANGED','SUBSCRIPTION_SETUP_CHANGED')));
create index idx_subscription_inbox_ready on provider_inbox(next_attempt_at,received_at,id) where event_family='SUBSCRIPTION';

alter table outbox_events add column source_kind varchar(16) not null default 'PAYMENT';
alter table outbox_events alter column payment_id drop not null;
alter table outbox_events add column subscription_id uuid;
alter table outbox_events add column subscription_event_id uuid;
alter table outbox_events add column payment_account_id uuid;
update outbox_events e set payment_account_id=p.payment_account_id from payments p where p.id=e.payment_id;
alter table outbox_events alter column payment_account_id set not null;
alter table subscription_events add constraint uq_subscription_callback_source unique(id,site_id,subscription_id,payment_account_id);
alter table outbox_events add constraint fk_callback_subscription foreign key(subscription_event_id,site_id,subscription_id,payment_account_id) references subscription_events(id,site_id,subscription_id,payment_account_id);
alter table outbox_events add constraint fk_callback_payment_account foreign key(payment_id,site_id,payment_account_id) references payments(id,site_id,payment_account_id);
alter table outbox_events add constraint fk_callback_account foreign key(payment_account_id) references payment_accounts(id);
alter table outbox_events drop constraint ck_outbox_type;
alter table outbox_events add constraint ck_callback_source check (
 (source_kind='PAYMENT' and payment_id is not null and subscription_id is null and subscription_event_id is null and callback_type in ('payment.requires_payment_method','payment.requires_action','payment.processing','payment.succeeded','payment.failed','payment.cancelled','refund.processing','refund.succeeded','refund.failed'))
 or (source_kind='SUBSCRIPTION' and payment_id is null and subscription_id is not null and subscription_event_id is not null and subscription_event_id=id and callback_type in ('subscription.updated','subscription.access_changed')));
create or replace function prevent_outbox_fact_mutation() returns trigger language plpgsql as $$
begin
 if (new.id,new.site_id,new.payment_id,new.callback_type,new.payload,new.endpoint_url,new.secret_version_id,new.created_at,new.source_kind,new.subscription_id,new.subscription_event_id,new.payment_account_id)
 is distinct from (old.id,old.site_id,old.payment_id,old.callback_type,old.payload,old.endpoint_url,old.secret_version_id,old.created_at,old.source_kind,old.subscription_id,old.subscription_event_id,old.payment_account_id) then
  raise exception 'outbox facts are immutable' using errcode='23514';
 end if;
 return new;
end;$$;
