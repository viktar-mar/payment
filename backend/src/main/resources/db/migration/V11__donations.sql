-- One-time donation context; financial facts remain owned by payments.
create table donation_campaigns (
 id uuid constraint pk_donation_campaigns primary key,
 site_id uuid not null constraint fk_donation_campaigns_site references sites(id),
 campaign_key varchar(63) not null constraint ck_donation_campaigns_key check(campaign_key ~ '^[a-z0-9][a-z0-9-]{2,62}$'),
 display_name text not null constraint ck_donation_campaigns_name check(length(trim(display_name))>0),
 purpose_description text,
 currency char(3) not null constraint ck_donation_campaigns_currency check(currency ~ '^[A-Z]{3}$'),
 minimum_amount bigint not null, maximum_amount bigint not null,
 custom_amount_allowed boolean not null, anonymous_allowed boolean not null,
 status varchar(16) not null constraint ck_donation_campaigns_status check(status in ('DRAFT','ACTIVE','PAUSED','ARCHIVED')),
 activated_once boolean not null,
 version bigint not null constraint ck_donation_campaigns_version check(version>=0),
 created_at timestamptz not null, updated_at timestamptz not null,
 constraint uq_donation_campaigns_id_site unique(id,site_id),
 constraint uq_donation_campaigns_site_key unique(site_id,campaign_key),
 constraint ck_donation_campaigns_bounds check(minimum_amount>0 and maximum_amount>=minimum_amount),
 constraint ck_donation_campaigns_activation check((status='DRAFT' and not activated_once) or (status in ('ACTIVE','PAUSED') and activated_once) or status='ARCHIVED'),
 constraint ck_donation_campaigns_times check(updated_at>=created_at)
);
create table donation_campaign_suggested_amounts (
 campaign_id uuid not null constraint fk_donation_suggestions_campaign references donation_campaigns(id),
 position integer not null constraint ck_donation_suggestions_position check(position>=0),
 amount_minor bigint not null constraint ck_donation_suggestions_amount check(amount_minor>0),
 constraint pk_donation_campaign_suggested_amounts primary key(campaign_id,position),
 constraint uq_donation_campaign_suggested_amount unique(campaign_id,amount_minor)
);
create table donations (
 id uuid constraint pk_donations primary key,
 site_id uuid not null, campaign_id uuid not null,
 message_state varchar(16) not null constraint ck_donations_message_state check(message_state in ('PRESENT','ABSENT','REDACTED')),
 message_ciphertext bytea, message_nonce bytea, message_key_version varchar(128),
 created_at timestamptz not null, updated_at timestamptz not null,
 constraint uq_donations_id_site unique(id,site_id),
 constraint uq_donations_id_site_campaign unique(id,site_id,campaign_id),
 constraint fk_donations_campaign_site foreign key(campaign_id,site_id) references donation_campaigns(id,site_id),
 constraint ck_donations_message check(
  (message_state='PRESENT' and message_ciphertext is not null and octet_length(message_ciphertext)>=16
   and message_nonce is not null and octet_length(message_nonce)=12
   and message_key_version is not null and length(trim(message_key_version))>0)
  or (message_state in ('ABSENT','REDACTED') and message_ciphertext is null and message_nonce is null and message_key_version is null)),
 constraint ck_donations_times check(updated_at>=created_at)
);
create index idx_donations_site_campaign_created on donations(site_id,campaign_id,created_at desc,id desc);

alter table payments add column donation_id uuid,
 add column donation_campaign_id uuid,
 add column snapshot_campaign_key varchar(63),
 add column snapshot_campaign_version bigint,
 add column snapshot_campaign_currency char(3);
alter table payments add constraint fk_payments_donation_site_campaign foreign key(donation_id,site_id,donation_campaign_id) references donations(id,site_id,campaign_id),
 add constraint fk_payments_donation_campaign_site foreign key(donation_campaign_id,site_id) references donation_campaigns(id,site_id);
create unique index uq_payments_donation on payments(donation_id) where donation_id is not null;
create index idx_payments_site_donation_campaign on payments(site_id,donation_campaign_id,created_at desc,id desc) where source='DONATION';

alter table payments drop constraint ck_payments_source, drop constraint ck_payments_shape;
alter table payments add constraint ck_payments_source check(source in ('CATALOG','DYNAMIC','DONATION')),
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
   and snapshot_amount_minor is null and snapshot_currency is null and snapshot_tax_mode is null and external_order_id is null));

alter table site_api_keys drop constraint ck_api_keys_scopes;
alter table site_api_keys add constraint ck_api_keys_scopes check(jsonb_typeof(scopes)='array'
 and scopes <@ '["payments:create","payments:read","customers:read","customers:manage","donations:read"]'::jsonb
 and jsonb_array_length(scopes)>0);
