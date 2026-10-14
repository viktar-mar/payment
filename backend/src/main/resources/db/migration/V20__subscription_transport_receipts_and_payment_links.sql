-- Additive owned linkage; only existing immutable actual cash context is copied.
alter table subscription_provider_objects add column payment_id uuid;
alter table payments add constraint uq_payment_owned_subscription_object
 unique(id,site_id,subscription_id,payment_account_id,billing_record_id,provider_payment_reference);
alter table subscription_provider_objects add constraint fk_subscription_object_payment
 foreign key(payment_id,site_id,subscription_id,payment_account_id,billing_record_id,provider_reference)
 references payments(id,site_id,subscription_id,payment_account_id,billing_record_id,provider_payment_reference);
alter table subscription_provider_objects add constraint ck_subscription_payment_object
 check ((object_type='PAYMENT' and payment_id is not null and billing_record_id is not null)
     or (object_type<>'PAYMENT' and payment_id is null));
create unique index uq_subscription_owned_payment on subscription_provider_objects(payment_id) where payment_id is not null;
insert into subscription_provider_objects(id,site_id,subscription_id,payment_account_id,provider_type,object_type,provider_reference,billing_record_id,payment_id,created_at)
 select gen_random_uuid(),p.site_id,p.subscription_id,p.payment_account_id,a.provider,'PAYMENT',p.provider_payment_reference,p.billing_record_id,p.id,p.created_at
 from payments p join payment_accounts a on a.id=p.payment_account_id
 where p.source='SUBSCRIPTION' and p.provider_payment_reference is not null;
create function protect_subscription_object_identity() returns trigger language plpgsql as $$
begin
 if (new.id,new.site_id,new.subscription_id,new.payment_account_id,new.provider_type,new.object_type,new.provider_reference,new.billing_record_id,new.payment_id,new.created_at)
 is distinct from (old.id,old.site_id,old.subscription_id,old.payment_account_id,old.provider_type,old.object_type,old.provider_reference,old.billing_record_id,old.payment_id,old.created_at) then
 raise exception 'Subscription provider object identity is immutable' using errcode='23514'; end if;
 return new;
end; $$;
create trigger subscription_object_identity before update on subscription_provider_objects for each row execute function protect_subscription_object_identity();

create table subscription_admin_receipts (
 site_id uuid not null references sites(id), admin_id uuid not null references admin_users(id),
 kind varchar(32) not null, key_digest char(64) not null, fingerprint char(64) not null,
 response text not null check(octet_length(response)<=32768),
 primary key(site_id,admin_id,kind,key_digest)
);
