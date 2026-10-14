-- Stage 2 projections, append-only journal, and durable asynchronous work.
alter table prices add constraint uq_prices_id_site unique (id, site_id);

create table payments (
 id uuid constraint pk_payments primary key,
 site_id uuid not null constraint fk_payments_site references sites(id),
 customer_id uuid,
 payment_account_id uuid not null constraint fk_payments_account references payment_accounts(id),
 source varchar(16) not null constraint ck_payments_source check (source in ('CATALOG','DYNAMIC')),
 price_id uuid,
 snapshot_price_key varchar(255), snapshot_version integer,
 snapshot_amount_minor bigint, snapshot_currency char(3), snapshot_tax_mode varchar(16),
 external_order_id varchar(255), return_url varchar(2048),
 amount_minor bigint not null constraint ck_payments_amount check (amount_minor > 0),
 currency char(3) not null constraint ck_payments_currency check (currency ~ '^[A-Z]{3}$'),
 tax_mode varchar(16) not null constraint ck_payments_tax_mode check (tax_mode in ('NONE','INCLUSIVE','EXCLUSIVE','STRIPE_TAX')),
 tax_amount_minor bigint constraint ck_payments_tax_amount check (tax_amount_minor >= 0 and tax_amount_minor <= amount_minor),
 technical_metadata jsonb not null default '{}' constraint ck_payments_metadata check (jsonb_typeof(technical_metadata)='object'),
 status varchar(32) not null constraint ck_payments_status check (status in ('CREATED','REQUIRES_PAYMENT_METHOD','REQUIRES_ACTION','PROCESSING','SUCCEEDED','FAILED','CANCELLED','PARTIALLY_REFUNDED','REFUNDED')),
 provider_payment_reference varchar(255),
 refunded_amount_minor bigint not null default 0 constraint ck_payments_refunded check (refunded_amount_minor between 0 and amount_minor),
 refundable_amount_minor bigint generated always as (case when status in ('SUCCEEDED','PARTIALLY_REFUNDED') then amount_minor-refunded_amount_minor else 0 end) stored,
 reconciliation_lease_owner varchar(128), reconciliation_lease_expires_at timestamptz,
 created_at timestamptz not null, updated_at timestamptz not null,
 version bigint not null default 0 constraint ck_payments_version check (version >= 0),
 constraint uq_payments_id_currency unique(id,currency),
 constraint uq_payments_id_site unique(id,site_id),
 constraint uq_payments_id_account unique(id,payment_account_id),
 constraint uq_payments_ownership unique(id,site_id,payment_account_id),
 constraint fk_payments_price_site foreign key(price_id,site_id) references prices(id,site_id),
 constraint ck_payments_snapshot_version check (snapshot_version > 0),
 constraint ck_payments_snapshot_amount check (snapshot_amount_minor > 0),
 constraint ck_payments_snapshot_currency check (snapshot_currency ~ '^[A-Z]{3}$'),
 constraint ck_payments_snapshot_tax check (snapshot_tax_mode in ('NONE','INCLUSIVE','EXCLUSIVE','STRIPE_TAX')),
 constraint ck_payments_shape check (
  (source='CATALOG' and price_id is not null and snapshot_price_key is not null and length(trim(snapshot_price_key))>0
   and snapshot_version is not null and snapshot_amount_minor is not null and snapshot_currency is not null and snapshot_tax_mode is not null
   and snapshot_amount_minor=amount_minor and snapshot_currency=currency and snapshot_tax_mode=tax_mode and external_order_id is null)
  or (source='DYNAMIC' and price_id is null and snapshot_price_key is null and snapshot_version is null and snapshot_amount_minor is null
   and snapshot_currency is null and snapshot_tax_mode is null and external_order_id is not null and length(trim(external_order_id))>0)),
 constraint ck_payments_refund_status check (
  (status='PARTIALLY_REFUNDED' and refunded_amount_minor>0 and refunded_amount_minor<amount_minor)
  or (status='REFUNDED' and refunded_amount_minor=amount_minor)
  or (status not in ('PARTIALLY_REFUNDED','REFUNDED') and refunded_amount_minor=0)),
 constraint ck_payments_lease check ((reconciliation_lease_owner is null)=(reconciliation_lease_expires_at is null)),
 constraint ck_payments_refundable check (refundable_amount_minor>=0),
 constraint ck_payments_times check (updated_at>=created_at)
);
create unique index uq_payments_site_external_order on payments(site_id,external_order_id) where external_order_id is not null;
create index idx_payments_site_created on payments(site_id,created_at desc);
create index idx_payments_provider on payments(payment_account_id,provider_payment_reference);
create index idx_payments_reconciliation_lease on payments(reconciliation_lease_expires_at);

create table payment_attempts (
 id uuid constraint pk_payment_attempts primary key,
 payment_id uuid not null constraint fk_attempts_payment references payments(id),
 attempt_number integer not null constraint ck_attempts_number check(attempt_number>0),
 provider_operation_key varchar(255) not null constraint uq_attempts_operation unique,
 provider_intent_reference varchar(255), payment_method_family varchar(64), result_code varchar(64),
 status varchar(32) not null constraint ck_attempts_status check(status in ('CREATED','REQUIRES_PAYMENT_METHOD','REQUIRES_ACTION','PROCESSING','SUCCEEDED','FAILED','UNKNOWN','CANCELLED')),
 created_at timestamptz not null, updated_at timestamptz not null,
 version bigint not null default 0 constraint ck_attempts_version check(version>=0),
 constraint ck_attempts_operation check(length(trim(provider_operation_key))>0),
 constraint ck_attempts_method check(payment_method_family ~ '^[A-Za-z0-9_-]{1,64}$'),
 constraint ck_attempts_result check(result_code ~ '^[A-Za-z0-9_-]{1,64}$'),
 constraint ck_attempts_times check(updated_at>=created_at)
);
create unique index uq_payment_attempt_number on payment_attempts(payment_id,attempt_number);

create table refunds (
 id uuid constraint pk_refunds primary key, payment_id uuid not null,
 amount_minor bigint not null constraint ck_refunds_amount check(amount_minor>0),
 currency char(3) not null constraint ck_refunds_currency check(currency ~ '^[A-Z]{3}$'),
 status varchar(16) not null constraint ck_refunds_status check(status in ('PENDING','PROCESSING','SUCCEEDED','FAILED','UNKNOWN','CANCELLED')),
 provider_operation_key varchar(255) not null constraint uq_refunds_operation unique,
 provider_refund_reference varchar(255), reason_code varchar(64), technical_note varchar(500),
 created_at timestamptz not null, updated_at timestamptz not null,
 version bigint not null default 0 constraint ck_refunds_version check(version>=0),
 constraint fk_refunds_payment_currency foreign key(payment_id,currency) references payments(id,currency),
 constraint ck_refunds_operation check(length(trim(provider_operation_key))>0),
 constraint ck_refunds_reason check(reason_code ~ '^[A-Za-z0-9_-]{1,64}$'),
 constraint ck_refunds_times check(updated_at>=created_at)
);
create index idx_refunds_payment_status on refunds(payment_id,status);

create table payment_event_sequences (
 payment_id uuid constraint pk_event_sequences primary key constraint fk_event_sequences_payment references payments(id),
 next_sequence bigint not null constraint ck_event_sequences_number check(next_sequence>0)
);
create table payment_events (
 id uuid constraint pk_payment_events primary key,
 payment_id uuid not null, site_id uuid not null, payment_account_id uuid not null,
 sequence_number bigint not null constraint ck_events_sequence check(sequence_number>0),
 event_type varchar(32) not null constraint ck_events_type check(event_type in ('PAYMENT_CREATED','ATTEMPT_CREATED','PAYMENT_METHOD_REQUIRED','CUSTOMER_ACTION_REQUIRED','PAYMENT_PROCESSING','PAYMENT_SUCCEEDED','PAYMENT_FAILED','PAYMENT_CANCELLED','PROVIDER_RESULT_UNKNOWN','REFUND_REQUESTED','REFUND_PROCESSING','REFUND_SUCCEEDED','REFUND_FAILED')),
 source varchar(24) not null constraint ck_events_source check(source in ('API','PROVIDER_EVENT','RECONCILIATION','ADMIN')),
 amount_minor bigint constraint ck_events_amount check(amount_minor>0),
 currency char(3) constraint ck_events_currency check(currency ~ '^[A-Z]{3}$'),
 provider_event_id varchar(255), provider_object_id varchar(255),
 occurred_at timestamptz not null, received_at timestamptz not null,
 request_id varchar(128) not null constraint ck_events_request check(length(trim(request_id))>0),
 idempotency_key varchar(255),
 payload_hash char(64) not null constraint ck_events_hash check(payload_hash ~ '^[0-9a-fA-F]{64}$'),
 technical_metadata jsonb not null default '{}' constraint ck_events_metadata check(jsonb_typeof(technical_metadata)='object'),
 constraint ck_events_money_pair check((amount_minor is null)=(currency is null)),
 constraint fk_events_ownership foreign key(payment_id,site_id,payment_account_id) references payments(id,site_id,payment_account_id),
 constraint uq_events_ownership unique(id,site_id,payment_id)
);
create unique index uq_payment_event_sequence on payment_events(payment_id,sequence_number);
create index idx_payment_events_site_payment on payment_events(site_id,payment_id,sequence_number);
create function prevent_payment_event_mutation() returns trigger language plpgsql as $$
begin
 raise exception 'payment_events are append-only' using errcode='23514';
end;
$$;
create trigger trg_payment_events_append_only before update or delete on payment_events for each row execute function prevent_payment_event_mutation();

create table idempotency_records (
 id uuid constraint pk_idempotency_records primary key, scope varchar(255) not null, idempotency_key varchar(255) not null,
 fingerprint char(64) not null constraint ck_idempotency_fingerprint check(fingerprint ~ '^[0-9a-f]{64}$'),
 status varchar(16) not null constraint ck_idempotency_status check(status in ('IN_PROGRESS','COMPLETED')),
 resource_type varchar(64), resource_id uuid, response_status integer,
 response_secret_state varchar(8) not null default 'NONE' constraint ck_idempotency_secret_state check(response_secret_state in ('NONE','LIVE','EXPIRED')),
 response_secret_ciphertext bytea, response_secret_nonce bytea, response_secret_key_version varchar(128), response_secret_expires_at timestamptz,
 expires_at timestamptz not null, created_at timestamptz not null, updated_at timestamptz not null,
 version bigint not null default 0 constraint ck_idempotency_version check(version>=0),
 constraint ck_idempotency_response_status check(response_status between 100 and 599),
 constraint ck_idempotency_key_version check(length(response_secret_key_version) between 1 and 128 and response_secret_key_version ~ '[^[:space:]]'),
 constraint ck_idempotency_in_progress check(status<>'IN_PROGRESS' or (resource_type is null and resource_id is null and response_status is null and response_secret_state='NONE')),
 constraint ck_idempotency_resource_pair check((resource_type is null)=(resource_id is null)),
 constraint ck_idempotency_resource_type check(resource_type in ('PAYMENT','CHECKOUT','HANDOFF','REFUND','OUTBOX_EVENT','OUTBOX_RETRY_REQUEST')),
 constraint ck_idempotency_completed check(status<>'COMPLETED' or (resource_type is not null and resource_id is not null and response_status is not null)),
 constraint ck_idempotency_secret check (
  (response_secret_state='NONE' and response_secret_ciphertext is null and response_secret_nonce is null and response_secret_key_version is null and response_secret_expires_at is null)
  or (response_secret_state='LIVE' and response_secret_ciphertext is not null and octet_length(response_secret_ciphertext)>=16 and response_secret_nonce is not null and octet_length(response_secret_nonce)=12 and response_secret_key_version is not null and response_secret_expires_at is not null)
  or (response_secret_state='EXPIRED' and response_secret_ciphertext is null and response_secret_nonce is null and response_secret_key_version is null and response_secret_expires_at is not null)),
 constraint ck_idempotency_secret_expiry check(response_secret_expires_at is null or (response_secret_expires_at>created_at and response_secret_expires_at<=expires_at)),
 constraint ck_idempotency_times check(updated_at>=created_at and expires_at>created_at)
);
create unique index uq_idempotency_scope_key on idempotency_records(scope,idempotency_key);
create index idx_idempotency_expiry on idempotency_records(expires_at) where status='COMPLETED';

create table checkout_sessions (
 id uuid constraint pk_checkout_sessions primary key,
 session_type varchar(16) not null constraint ck_sessions_type check(session_type in ('CHECKOUT','HANDOFF')),
 payment_id uuid not null, site_id uuid not null,
 token_hash char(64) not null constraint uq_sessions_token unique constraint ck_sessions_hash check(token_hash ~ '^[0-9a-fA-F]{64}$'),
 origin varchar(2048), scopes jsonb not null,
 expires_at timestamptz not null, consumed_at timestamptz, created_at timestamptz not null,
 constraint fk_sessions_payment_site foreign key(payment_id,site_id) references payments(id,site_id),
 constraint ck_sessions_origin check(session_type<>'HANDOFF' or (origin is not null and length(trim(origin))>0)),
 constraint ck_sessions_scopes check(jsonb_typeof(scopes)='array'),
 constraint ck_sessions_times check(expires_at>created_at and (consumed_at is null or consumed_at>=created_at))
);
create index idx_sessions_payment on checkout_sessions(payment_id);
create index idx_sessions_expiry on checkout_sessions(expires_at);
create table site_api_keys (
 id uuid constraint pk_site_api_keys primary key, site_id uuid not null constraint fk_api_keys_site references sites(id),
 prefix varchar(64) not null constraint uq_api_keys_prefix unique,
 key_hash varchar(512) not null constraint ck_api_keys_hash check(key_hash like '$argon2id$%'),
 scopes jsonb not null constraint ck_api_keys_scopes check(jsonb_typeof(scopes)='array' and scopes <@ '["payments:create","payments:read"]'::jsonb and jsonb_array_length(scopes)>0),
 status varchar(16) not null constraint ck_api_keys_status check(status in ('ACTIVE','REVOKED')),
 created_at timestamptz not null, expires_at timestamptz, last_used_at timestamptz, revoked_at timestamptz,
 version bigint not null default 0 constraint ck_api_keys_version check(version>=0),
 constraint ck_api_keys_revoked check((status='REVOKED')=(revoked_at is not null)),
 constraint ck_api_keys_times check((expires_at is null or expires_at>created_at) and (last_used_at is null or last_used_at>=created_at) and (revoked_at is null or revoked_at>=created_at))
);
create index idx_api_keys_site on site_api_keys(site_id);

create table site_callback_secret_versions (
 id uuid constraint pk_callback_secrets primary key, site_id uuid not null constraint fk_callback_secrets_site references sites(id),
 secret_version integer not null constraint ck_callback_secrets_version check(secret_version>0),
 ciphertext bytea, nonce bytea constraint ck_callback_secrets_nonce check(octet_length(nonce)=12),
 master_key_version varchar(128) constraint ck_callback_secrets_master_version check(length(master_key_version) between 1 and 128 and master_key_version ~ '[^[:space:]]'),
 created_at timestamptz not null, retire_after timestamptz, destroyed_at timestamptz,
 constraint uq_callback_secrets_site_version unique(site_id,secret_version),
 constraint uq_callback_secrets_id_site unique(id,site_id),
 constraint ck_callback_secrets_times check(retire_after is null or retire_after>=created_at),
 constraint ck_callback_secrets_material check(
  (destroyed_at is null and ciphertext is not null and nonce is not null and master_key_version is not null)
  or (destroyed_at is not null and ciphertext is null and nonce is null and master_key_version is null)),
 constraint ck_callback_secrets_destruction_time check(destroyed_at is null or (retire_after is not null and destroyed_at>=retire_after))
);
create table site_callback_configs (
 site_id uuid constraint pk_callback_configs primary key constraint fk_callback_configs_site references sites(id),
 endpoint_url varchar(2048) not null, active_secret_version_id uuid not null, enabled boolean not null,
 created_at timestamptz not null, updated_at timestamptz not null,
 version bigint not null default 0 constraint ck_callback_configs_version check(version>=0),
 constraint fk_callback_configs_secret_site foreign key(active_secret_version_id,site_id) references site_callback_secret_versions(id,site_id),
 constraint ck_callback_configs_times check(updated_at>=created_at)
);
create table provider_inbox (
 id uuid constraint pk_provider_inbox primary key,
 provider_type varchar(16) not null constraint ck_inbox_provider check(provider_type in ('FAKE','STRIPE')),
 payment_account_id uuid not null constraint fk_inbox_account references payment_accounts(id),
 provider_event_id varchar(255) not null, provider_object_id varchar(255) not null,
 event_type varchar(32) not null constraint ck_inbox_event_type check(event_type in ('PAYMENT_CREATED','ATTEMPT_CREATED','PAYMENT_METHOD_REQUIRED','CUSTOMER_ACTION_REQUIRED','PAYMENT_PROCESSING','PAYMENT_SUCCEEDED','PAYMENT_FAILED','PAYMENT_CANCELLED','PROVIDER_RESULT_UNKNOWN','REFUND_REQUESTED','REFUND_PROCESSING','REFUND_SUCCEEDED','REFUND_FAILED')),
 occurred_at timestamptz not null, received_at timestamptz not null,
 payload_hash char(64) not null constraint ck_inbox_hash check(payload_hash ~ '^[0-9a-fA-F]{64}$'),
 safe_payload jsonb not null default '{}' constraint ck_inbox_payload check(jsonb_typeof(safe_payload)='object'),
 status varchar(16) not null default 'PENDING' constraint ck_inbox_status check(status in ('PENDING','PROCESSING','PROCESSED','FAILED')),
 lease_owner varchar(128), lease_expires_at timestamptz, error_category varchar(64),
 next_attempt_at timestamptz not null default current_timestamp,
 attempt_count integer not null default 0 constraint ck_inbox_attempts check(attempt_count>=0),
 constraint ck_inbox_lease check((lease_owner is null)=(lease_expires_at is null)),
 constraint ck_inbox_processing check(status<>'PROCESSING' or lease_owner is not null)
);
create unique index uq_provider_inbox_event on provider_inbox(provider_type,payment_account_id,provider_event_id);
create index idx_inbox_claim on provider_inbox(status,next_attempt_at,lease_expires_at,received_at);

create table outbox_events (
 id uuid constraint pk_outbox_events primary key, site_id uuid not null, payment_id uuid not null,
 callback_type varchar(40) not null constraint ck_outbox_type check(callback_type in ('payment.requires_payment_method','payment.requires_action','payment.processing','payment.succeeded','payment.failed','payment.cancelled','refund.processing','refund.succeeded','refund.failed')),
 payload jsonb not null constraint ck_outbox_payload check(jsonb_typeof(payload)='object'),
 endpoint_url varchar(2048) not null, secret_version_id uuid not null,
 status varchar(16) not null default 'PENDING' constraint ck_outbox_status check(status in ('PENDING','DELIVERED','DEAD')),
 next_attempt_at timestamptz not null, lease_owner varchar(128), lease_expires_at timestamptz,
 attempt_count integer not null default 0 constraint ck_outbox_attempts check(attempt_count>=0),
 created_at timestamptz not null, updated_at timestamptz not null,
 version bigint not null default 0 constraint ck_outbox_version check(version>=0),
 constraint fk_outbox_source foreign key(id,site_id,payment_id) references payment_events(id,site_id,payment_id),
 constraint fk_outbox_secret_site foreign key(secret_version_id,site_id) references site_callback_secret_versions(id,site_id),
 constraint uq_outbox_id_site unique(id,site_id),
 constraint ck_outbox_lease check((lease_owner is null)=(lease_expires_at is null)),
 constraint ck_outbox_times check(updated_at>=created_at)
);
create index idx_outbox_claim on outbox_events(status,next_attempt_at,lease_expires_at);
create index idx_outbox_secret_pending on outbox_events(secret_version_id) where status='PENDING';
create function prevent_outbox_fact_mutation() returns trigger language plpgsql as $$
begin
 if (new.id,new.site_id,new.payment_id,new.callback_type,new.payload,new.endpoint_url,new.secret_version_id,new.created_at)
  is distinct from (old.id,old.site_id,old.payment_id,old.callback_type,old.payload,old.endpoint_url,old.secret_version_id,old.created_at) then
  raise exception 'outbox facts are immutable' using errcode='23514';
 end if;
 return new;
end;
$$;
create trigger trg_outbox_facts_immutable before update on outbox_events for each row execute function prevent_outbox_fact_mutation();

create table outbox_retry_requests (
 id uuid constraint pk_outbox_retry_requests primary key,
 outbox_event_id uuid not null, site_id uuid not null,
 delivery_mode varchar(24) not null constraint ck_retry_mode check(delivery_mode in ('SNAPSHOT_RETRY','CURRENT_CONFIG_RETRY')),
 endpoint_url varchar(2048) not null, secret_version_id uuid not null,
 status varchar(16) not null default 'PENDING' constraint ck_retry_status check(status in ('PENDING','PROCESSING','PROCESSED')),
 lease_owner varchar(128), lease_expires_at timestamptz,
 created_at timestamptz not null, completed_at timestamptz,
 attempt_number integer constraint ck_retry_attempt check(attempt_number>0), result varchar(16),
 constraint fk_retry_event_site foreign key(outbox_event_id,site_id) references outbox_events(id,site_id),
 constraint fk_retry_secret_site foreign key(secret_version_id,site_id) references site_callback_secret_versions(id,site_id),
 constraint ck_retry_lease check((lease_owner is null)=(lease_expires_at is null)),
 constraint ck_retry_processing check(status<>'PROCESSING' or (lease_owner is not null and attempt_number is not null)),
 constraint ck_retry_completed check((status='PROCESSED')=(completed_at is not null and result is not null)),
 constraint ck_retry_state check(
  (status='PENDING' and lease_owner is null and attempt_number is null and completed_at is null and result is null)
  or (status='PROCESSING' and lease_owner is not null and attempt_number is not null and completed_at is null and result is null)
  or (status='PROCESSED' and lease_owner is null and attempt_number is not null and completed_at is not null and result is not null)),
 constraint ck_retry_result check(result in ('SUCCEEDED','FAILED')),
 constraint ck_retry_times check(completed_at is null or completed_at>=created_at)
);
create index idx_outbox_retry_claim on outbox_retry_requests(status,lease_expires_at,created_at,id);
create index idx_outbox_retry_secret on outbox_retry_requests(secret_version_id) where status in ('PENDING','PROCESSING');
create function prevent_retry_fact_mutation() returns trigger language plpgsql as $$
begin
 if (new.id,new.outbox_event_id,new.site_id,new.delivery_mode,new.endpoint_url,new.secret_version_id,new.created_at)
  is distinct from (old.id,old.outbox_event_id,old.site_id,old.delivery_mode,old.endpoint_url,old.secret_version_id,old.created_at)
  or (old.status='PROCESSED' and new is distinct from old) then
  raise exception 'retry facts are immutable' using errcode='23514';
 end if;
 return new;
end;
$$;
create trigger trg_retry_facts_immutable before update on outbox_retry_requests for each row execute function prevent_retry_fact_mutation();

-- Keep immutable secret identities for callback/delivery history. Cleanup destroys
-- only material, after the caller-scheduled overlap and all unfinished deliveries.
create function guard_callback_secret_lifecycle() returns trigger language plpgsql as $$
begin
 if tg_op='DELETE' then
  raise exception 'callback secret identity is retained' using errcode='23514';
 end if;
 if tg_op='INSERT' then
  if new.destroyed_at is not null then
   raise exception 'callback secret must begin with live material' using errcode='23514';
  end if;
  return new;
 end if;
 if old.destroyed_at is not null and new is distinct from old then
  raise exception 'destroyed callback secret is immutable' using errcode='23514';
 end if;
 if (new.id,new.site_id,new.secret_version,new.created_at)
  is distinct from (old.id,old.site_id,old.secret_version,old.created_at) then
  raise exception 'callback secret identity is immutable' using errcode='23514';
 end if;
 if old.retire_after is not null and (new.retire_after is null or new.retire_after<old.retire_after) then
  raise exception 'callback secret overlap cannot be shortened' using errcode='23514';
 end if;
 if old.destroyed_at is null and new.destroyed_at is not null then
  if old.retire_after is null or new.destroyed_at<old.retire_after then
   raise exception 'callback secret overlap has not elapsed' using errcode='23514';
  end if;
  if exists(select 1 from site_callback_configs where active_secret_version_id=old.id)
   or exists(select 1 from outbox_events where secret_version_id=old.id and status='PENDING')
   or exists(select 1 from outbox_retry_requests where secret_version_id=old.id and status in ('PENDING','PROCESSING')) then
   raise exception 'callback secret is still in use' using errcode='23514';
  end if;
 end if;
 return new;
end;
$$;
create trigger trg_callback_secret_lifecycle after insert or update or delete on site_callback_secret_versions
for each row execute function guard_callback_secret_lifecycle();

-- Reference creation/retry takes the same row lock as destruction. Checking only
-- from the cleanup side would allow a concurrent new reference to race it.
create function require_live_callback_secret() returns trigger language plpgsql as $$
declare
 secret_id uuid;
 destroyed timestamptz;
begin
 if tg_table_name='site_callback_configs' then
  secret_id:=new.active_secret_version_id;
 else
  if tg_op='UPDATE' and (tg_table_name='outbox_events' and new.status<>'PENDING'
     or tg_table_name='outbox_retry_requests' and new.status='PROCESSED') then return new; end if;
  secret_id:=new.secret_version_id;
 end if;
 select destroyed_at into destroyed from site_callback_secret_versions
  where id=secret_id and site_id=new.site_id FOR UPDATE;
 -- Let the existing named ownership FK report absent or wrong-Site identities.
 if found and destroyed is not null then
  raise exception 'callback secret material is destroyed' using errcode='23514';
 end if;
 return new;
end;
$$;
create trigger trg_callback_config_live_secret before insert or update on site_callback_configs
for each row execute function require_live_callback_secret();
create trigger trg_outbox_live_secret before insert or update on outbox_events
for each row execute function require_live_callback_secret();
create trigger trg_retry_live_secret before insert or update on outbox_retry_requests
for each row execute function require_live_callback_secret();

create table outbox_deliveries (
 id uuid constraint pk_outbox_deliveries primary key,
 outbox_event_id uuid not null constraint fk_deliveries_outbox references outbox_events(id),
 attempt_number integer not null constraint ck_deliveries_attempt check(attempt_number>0),
 delivery_mode varchar(24) not null constraint ck_deliveries_mode check(delivery_mode in ('AUTOMATIC','SNAPSHOT_RETRY','CURRENT_CONFIG_RETRY')),
 status varchar(16) not null constraint ck_deliveries_status check(status in ('SUCCEEDED','FAILED')),
 http_status integer constraint ck_deliveries_http check(http_status between 100 and 599),
 error_category varchar(64), started_at timestamptz not null, finished_at timestamptz not null,
 constraint uq_delivery_attempt unique(outbox_event_id,attempt_number),
 constraint ck_deliveries_success check(status<>'SUCCEEDED' or (http_status is not null and http_status between 200 and 299)),
 constraint ck_deliveries_times check(finished_at>=started_at)
);
create table fake_provider_objects (
 provider_reference varchar(255) constraint pk_fake_provider_objects primary key,
 provider_operation_key varchar(255) not null constraint uq_fake_operation unique,
 object_type varchar(16) not null constraint ck_fake_object_type check(object_type in ('PAYMENT','REFUND')),
 internal_object_id uuid not null,
 payment_id uuid generated always as (case when object_type='PAYMENT' then internal_object_id end) stored,
 refund_id uuid generated always as (case when object_type='REFUND' then internal_object_id end) stored,
 payment_account_id uuid not null constraint fk_fake_account references payment_accounts(id),
 state varchar(32) not null constraint ck_fake_state check(state in ('CREATED','PENDING','REQUIRES_PAYMENT_METHOD','REQUIRES_ACTION','PROCESSING','SUCCEEDED','FAILED','UNKNOWN','CANCELLED')),
 result_code varchar(64),
 scenario varchar(32) constraint ck_fake_scenario check(scenario in ('ACCEPTED','PROCESSING','REQUIRES_ACTION','RECOVERABLE_DECLINE','TERMINAL_DECLINE','TEMPORARILY_UNAVAILABLE','UNKNOWN')),
 request_fingerprint char(64) constraint ck_fake_fingerprint check(request_fingerprint ~ '^[0-9a-f]{64}$'),
 event_counter bigint not null default 0 constraint ck_fake_counter check(event_counter>=0),
 created_at timestamptz not null, updated_at timestamptz not null,
 version bigint not null default 0 constraint ck_fake_version check(version>=0),
 constraint ck_fake_result check(result_code ~ '^[A-Za-z0-9_-]{1,64}$'),
 constraint ck_fake_times check(updated_at>=created_at),
 constraint fk_fake_payment_account foreign key(payment_id,payment_account_id) references payments(id,payment_account_id),
 constraint fk_fake_refund foreign key(refund_id) references refunds(id),
 constraint uq_fake_internal unique(object_type,internal_object_id,payment_account_id)
);
alter table fake_provider_objects add constraint uq_fake_receipt_identity
 unique(provider_reference,payment_account_id,object_type,internal_object_id);

-- Current truth stays on the object; immutable receipts preserve each command's original acknowledgement.
create table fake_provider_operations (
 operation_key varchar(255) constraint pk_fake_operations primary key,
 fingerprint char(64) not null constraint ck_fake_operation_fingerprint check(fingerprint ~ '^[0-9a-f]{64}$'),
 provider_reference varchar(255) not null,
 payment_account_id uuid not null,
 object_type varchar(16) not null,
 internal_object_id uuid not null,
 result_kind varchar(32) not null constraint ck_fake_operation_kind check(result_kind in ('ACCEPTED','PROCESSING','REQUIRES_ACTION','RECOVERABLE_DECLINE','TERMINAL_DECLINE','TEMPORARILY_UNAVAILABLE','UNKNOWN')),
 result_reference varchar(255),
 result_code varchar(32) not null,
 action_kind varchar(32), action_payload varchar(128),
 created_at timestamptz not null,
 constraint ck_fake_operation_key check(operation_key ~ '^(prepare|confirm|refund)_[0-9a-f-]{36}$'),
 constraint fk_fake_operation_object foreign key(provider_reference,payment_account_id,object_type,internal_object_id)
  references fake_provider_objects(provider_reference,payment_account_id,object_type,internal_object_id),
 constraint ck_fake_operation_reference check((result_kind='TEMPORARILY_UNAVAILABLE' and result_reference is null)
  or (result_kind='UNKNOWN' and (result_reference is null or result_reference=provider_reference))
  or (result_kind not in ('TEMPORARILY_UNAVAILABLE','UNKNOWN') and result_reference is not null and result_reference=provider_reference)),
 constraint ck_fake_operation_code check(result_code=case result_kind
  when 'REQUIRES_ACTION' then 'ACTION_REQUIRED' when 'RECOVERABLE_DECLINE' then 'DECLINED'
  when 'TEMPORARILY_UNAVAILABLE' then 'UNAVAILABLE' else result_kind end),
 constraint ck_fake_operation_action check((result_kind='REQUIRES_ACTION' and action_kind is not null and action_kind='COMPLETE_VERIFICATION' and action_payload is not null and action_payload ~ '^[A-Za-z0-9_-]{1,128}$')
  or (result_kind<>'REQUIRES_ACTION' and action_kind is null and action_payload is null))
);
create function prevent_fake_operation_mutation() returns trigger language plpgsql as $$
begin raise exception 'fake provider operation receipts are immutable' using errcode='23514'; end;
$$;
create trigger trg_fake_operations_immutable before update or delete on fake_provider_operations
 for each row execute function prevent_fake_operation_mutation();

-- A refund's account is inherited through its Payment; the generated FK above
-- prevents orphaning while this guard checks the account on each fake write.
create function validate_fake_refund_account() returns trigger language plpgsql as $$
begin
 if new.object_type='REFUND' and not exists (
  select 1 from refunds r join payments p on p.id=r.payment_id
  where r.id=new.internal_object_id and p.payment_account_id=new.payment_account_id
 ) then
  raise exception 'fake refund account does not match Payment' using errcode='23503';
 end if;
 return new;
end;
$$;
create trigger trg_fake_refund_account before insert or update on fake_provider_objects
for each row execute function validate_fake_refund_account();
