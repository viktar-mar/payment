alter table subscription_quotes add constraint uq_subscription_quote_owner unique(site_id,subscription_id,id);
create table subscription_change_intents (
 operation_id uuid primary key,
 site_id uuid not null,
 subscription_id uuid not null,
 quote_id uuid not null,
 foreign key(site_id,subscription_id,operation_id) references subscription_operations(site_id,subscription_id,id),
 foreign key(site_id,subscription_id,quote_id) references subscription_quotes(site_id,subscription_id,id)
);
