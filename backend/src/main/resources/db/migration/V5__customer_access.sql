-- One complete Stage 3A migration. Sensitive material is encrypted or keyed-digested.
create table customers (
 id uuid constraint pk_customers primary key,
 site_id uuid not null constraint fk_customers_site references sites(id),
 status varchar(16) not null constraint ck_customers_status check(status in ('ACTIVE','ANONYMIZED')),
 email_ciphertext bytea, email_nonce bytea, email_key_version varchar(128),
 email_digest varchar(512), email_digest_key_version varchar(16),
 created_at timestamptz not null, updated_at timestamptz not null, anonymized_at timestamptz,
 version bigint not null default 0 constraint ck_customers_version check(version>=0),
 constraint uq_customers_site_id unique(site_id,id),
 constraint ck_customers_email check (
  (email_ciphertext is null and email_nonce is null and email_key_version is null and email_digest is null and email_digest_key_version is null)
  or (email_ciphertext is not null and octet_length(email_ciphertext)>=16 and email_nonce is not null and octet_length(email_nonce)=12
   and email_key_version is not null and email_key_version ~ '[^[:space:]]' and length(email_key_version)>0
   and email_digest is not null and email_digest ~ '^[A-Za-z0-9_-]+$' and email_digest_key_version is not null and email_digest_key_version ~ '^v[1-9][0-9]{0,8}$')),
 constraint ck_customers_anonymized check((status='ANONYMIZED')=(anonymized_at is not null) and (status<>'ANONYMIZED' or email_ciphertext is null)),
 constraint ck_customers_times check(updated_at>=created_at and (anonymized_at is null or (anonymized_at>=created_at and anonymized_at<=updated_at)))
);
create index idx_customers_site_created on customers(site_id,created_at desc,id desc);
-- A profile email is deliberately not unique and never implies identity merging.
create index idx_customers_email on customers(site_id,email_digest,email_digest_key_version) where email_digest is not null;

create table identity_provider_configurations (
 id uuid constraint pk_identity_providers primary key,
 site_id uuid not null constraint fk_identity_providers_site references sites(id),
 provider_key varchar(63) not null constraint ck_identity_providers_key check(provider_key ~ '^[a-z0-9][a-z0-9-]{2,62}$'),
 mode varchar(16) not null constraint ck_identity_providers_mode check(mode in ('OIDC_TOKEN','SITE_TOKEN','EMAIL_OTP')),
 status varchar(16) not null constraint ck_identity_providers_status check(status in ('ACTIVE','DISABLED')),
 issuer varchar(512), audiences jsonb, allowed_algorithms jsonb,
 jwks_source_type varchar(16), jwks_location varchar(512), public_jwk_set_ref varchar(512), public_jwk_set jsonb,
 email_claim varchar(512), verified_email_claim varchar(512),
 sender_ref varchar(512), template_key varchar(63),
 created_at timestamptz not null, updated_at timestamptz not null,
 version bigint not null default 0 constraint ck_identity_providers_version check(version>=0),
 constraint uq_identity_providers_site_key unique(site_id,provider_key),
 constraint uq_identity_providers_site_id unique(site_id,id),
 constraint ck_identity_providers_shape check (
  (mode='EMAIL_OTP' and sender_ref is not null and sender_ref ~ '[^[:space:]]' and template_key is not null and template_key ~ '^[a-z0-9][a-z0-9-]{2,62}$'
   and issuer is null and audiences is null and allowed_algorithms is null and jwks_source_type is null and jwks_location is null and email_claim is null and verified_email_claim is null)
  or (mode in ('OIDC_TOKEN','SITE_TOKEN') and issuer is not null and issuer ~ '[^[:space:]]' and audiences is not null and jsonb_typeof(audiences)='array' and jsonb_array_length(audiences)>0
   and allowed_algorithms is not null and jsonb_typeof(allowed_algorithms)='array' and jsonb_array_length(allowed_algorithms)>0 and allowed_algorithms <@ '["RS256","ES256"]'::jsonb
   and jwks_source_type is not null and jwks_source_type in ('REMOTE','STATIC_PUBLIC')
   and sender_ref is null and template_key is null and (email_claim is null)=(verified_email_claim is null) and (mode<>'SITE_TOKEN' or email_claim is null))),
 constraint ck_identity_providers_jwks_source check (
  (mode='EMAIL_OTP' and jwks_source_type is null and jwks_location is null and public_jwk_set_ref is null and public_jwk_set is null)
  or (mode in ('OIDC_TOKEN','SITE_TOKEN') and (
   (jwks_source_type='REMOTE' and jwks_location is not null and jwks_location ~ '^https://' and public_jwk_set_ref is null and public_jwk_set is null)
   or (jwks_source_type='STATIC_PUBLIC' and jwks_location is null and public_jwk_set_ref is not null and public_jwk_set_ref ~ '[^[:space:]]'
    and public_jwk_set is not null and jsonb_typeof(public_jwk_set)='object'
    and public_jwk_set ? 'keys' and jsonb_typeof(public_jwk_set->'keys')='array'
    and jsonb_array_length(public_jwk_set->'keys') between 1 and 20 and octet_length(public_jwk_set::text)<=262144)))),
 constraint ck_identity_providers_times check(updated_at>=created_at)
);

create table customer_identities (
 id uuid constraint pk_customer_identities primary key,
 site_id uuid not null, customer_id uuid not null,
 mode varchar(16) not null constraint ck_customer_identities_mode check(mode in ('OIDC_TOKEN','SITE_TOKEN','EMAIL_OTP')),
 issuer_key varchar(63) not null constraint ck_customer_identities_issuer check(issuer_key ~ '^[a-z0-9][a-z0-9-]{2,62}$'),
 subject_digest varchar(512) not null constraint ck_customer_identities_digest check(subject_digest ~ '^[A-Za-z0-9_-]+$'),
 subject_digest_key_version varchar(16) not null constraint ck_customer_identities_digest_version check(subject_digest_key_version ~ '^v[1-9][0-9]{0,8}$'),
 verified_at timestamptz not null, created_at timestamptz not null, last_used_at timestamptz not null,
 version bigint not null default 0 constraint ck_customer_identities_version check(version>=0),
 constraint fk_customer_identities_customer_site foreign key(site_id,customer_id) references customers(site_id,id),
 constraint uq_customer_identities_subject unique(site_id,issuer_key,subject_digest,subject_digest_key_version),
 constraint uq_customer_identities_site_id unique(site_id,id),
 constraint ck_customer_identities_times check(verified_at>=created_at and last_used_at>=verified_at)
);
create index idx_customer_identities_customer on customer_identities(site_id,customer_id);

-- Reject pre-existing invalid ownership deterministically; never fabricate Customers.
do $$ begin
 if exists(select 1 from payments p where p.customer_id is not null and not exists (
  select 1 from customers c where c.site_id=p.site_id and c.id=p.customer_id)) then
  raise exception 'orphan payment customer_id' using errcode='23514', constraint='ck_payments_customer_migration_precondition';
 end if;
end $$;
alter table payments add constraint fk_payments_customer_site foreign key(site_id,customer_id) references customers(site_id,id);
create index idx_payments_customer_history on payments(site_id,customer_id,created_at desc,id desc) where customer_id is not null;
alter table site_api_keys drop constraint ck_api_keys_scopes;
alter table site_api_keys add constraint ck_api_keys_scopes check(jsonb_typeof(scopes)='array'
 and scopes <@ '["payments:create","payments:read","customers:read","customers:manage"]'::jsonb and jsonb_array_length(scopes)>0);

create table customer_sessions (
 id uuid constraint pk_customer_sessions primary key,
 site_id uuid not null, customer_id uuid not null,
 origin varchar(2048) not null constraint ck_customer_sessions_origin check(origin ~ '^https?://[^[:space:]]+$'),
 token_digest varchar(512) not null constraint ck_customer_sessions_digest check(token_digest ~ '^[A-Za-z0-9_-]+$'),
 token_digest_key_version varchar(16) not null constraint ck_customer_sessions_digest_version check(token_digest_key_version ~ '^v[1-9][0-9]{0,8}$'),
 scopes jsonb not null constraint ck_customer_sessions_scopes check(jsonb_typeof(scopes)='array' and jsonb_array_length(scopes)>0 and scopes <@ '["CUSTOMER_READ","CHECKOUT_CREATE","IDENTITY_LINK"]'::jsonb),
 issued_at timestamptz not null, last_used_at timestamptz not null, expires_at timestamptz not null,
 revoked_at timestamptz, revoked_reason varchar(32) constraint ck_customer_sessions_reason check(revoked_reason in ('LOGOUT','ANONYMIZED','ADMIN','EXPIRED')),
 version bigint not null default 0 constraint ck_customer_sessions_version check(version>=0),
 constraint fk_customer_sessions_customer_site foreign key(site_id,customer_id) references customers(site_id,id),
 constraint uq_customer_sessions_token unique(site_id,token_digest,token_digest_key_version),
 constraint ck_customer_sessions_revoked check((revoked_at is null)=(revoked_reason is null)),
 constraint ck_customer_sessions_times check(expires_at>issued_at and last_used_at>=issued_at and last_used_at<=expires_at and (revoked_at is null or revoked_at>=issued_at))
);
create index idx_customer_sessions_active on customer_sessions(site_id,customer_id,expires_at) where revoked_at is null;
create index idx_customer_sessions_expiry on customer_sessions(expires_at);

create table oidc_authentication_attempts (
 id uuid constraint pk_oidc_attempts primary key, site_id uuid not null,
 provider_key varchar(63) not null,
 origin varchar(2048) not null constraint ck_oidc_attempts_origin check(origin ~ '^https?://[^[:space:]]+$'),
 intent varchar(16) not null constraint ck_oidc_attempts_intent check(intent in ('AUTHENTICATE','LINK')),
 nonce_digest varchar(512) not null constraint ck_oidc_attempts_digest check(nonce_digest ~ '^[A-Za-z0-9_-]+$'),
 nonce_digest_key_version varchar(16) not null constraint ck_oidc_attempts_digest_version check(nonce_digest_key_version ~ '^v[1-9][0-9]{0,8}$'),
 created_at timestamptz not null, expires_at timestamptz not null, consumed_at timestamptz,
 version bigint not null default 0 constraint ck_oidc_attempts_version check(version>=0),
 constraint fk_oidc_attempts_provider_site foreign key(site_id,provider_key) references identity_provider_configurations(site_id,provider_key),
 constraint ck_oidc_attempts_times check(expires_at>created_at and (consumed_at is null or consumed_at>=created_at))
);
create index idx_oidc_attempts_expiry on oidc_authentication_attempts(expires_at) where consumed_at is null;

create table consumed_site_token_jtis (
 id uuid constraint pk_consumed_jtis primary key,
 site_id uuid not null constraint fk_consumed_jtis_site references sites(id),
 issuer_key varchar(63) not null constraint ck_consumed_jtis_issuer check(issuer_key ~ '^[a-z0-9][a-z0-9-]{2,62}$'),
 jti_digest varchar(512) not null constraint ck_consumed_jtis_digest check(jti_digest ~ '^[A-Za-z0-9_-]+$'),
 jti_digest_key_version varchar(16) not null constraint ck_consumed_jtis_digest_version check(jti_digest_key_version ~ '^v[1-9][0-9]{0,8}$'),
 consumed_at timestamptz not null, expires_at timestamptz not null,
 version bigint not null default 0 constraint ck_consumed_jtis_version check(version>=0),
 constraint uq_consumed_jtis unique(site_id,issuer_key,jti_digest,jti_digest_key_version),
 constraint ck_consumed_jtis_times check(expires_at>consumed_at)
);
create index idx_consumed_jtis_expiry on consumed_site_token_jtis(expires_at);

create table identity_link_proofs (
 id uuid constraint pk_identity_link_proofs primary key,
 site_id uuid not null constraint fk_identity_link_proofs_site references sites(id),
 origin varchar(2048) not null constraint ck_identity_link_proofs_origin check(origin ~ '^https?://[^[:space:]]+$'),
 mode varchar(16) not null constraint ck_identity_link_proofs_mode check(mode in ('OIDC_TOKEN','SITE_TOKEN','EMAIL_OTP')),
 issuer_key varchar(63) not null constraint ck_identity_link_proofs_issuer check(issuer_key ~ '^[a-z0-9][a-z0-9-]{2,62}$'),
 subject_digest varchar(512) not null constraint ck_identity_link_proofs_subject check(subject_digest ~ '^[A-Za-z0-9_-]+$'),
 subject_digest_key_version varchar(16) not null constraint ck_identity_link_proofs_subject_version check(subject_digest_key_version ~ '^v[1-9][0-9]{0,8}$'),
 token_digest varchar(512) not null constraint ck_identity_link_proofs_token check(token_digest ~ '^[A-Za-z0-9_-]+$'),
 token_digest_key_version varchar(16) not null constraint ck_identity_link_proofs_token_version check(token_digest_key_version ~ '^v[1-9][0-9]{0,8}$'),
 verified_at timestamptz not null, created_at timestamptz not null, expires_at timestamptz not null, consumed_at timestamptz,
 version bigint not null default 0 constraint ck_identity_link_proofs_version check(version>=0),
 constraint uq_identity_link_proofs_token unique(site_id,token_digest,token_digest_key_version),
 constraint ck_identity_link_proofs_times check(expires_at>created_at and verified_at<=created_at and (consumed_at is null or consumed_at>=created_at))
);
create index idx_identity_link_proofs_expiry on identity_link_proofs(expires_at) where consumed_at is null;

create table email_otp_challenges (
 id uuid constraint pk_email_otp_challenges primary key,
 site_id uuid not null constraint fk_email_otp_challenges_site references sites(id),
 origin varchar(2048) not null constraint ck_email_otp_challenges_origin check(origin ~ '^https?://[^[:space:]]+$'),
 intent varchar(16) not null constraint ck_email_otp_challenges_intent check(intent in ('AUTHENTICATE','LINK')),
 email_digest varchar(512) not null constraint ck_email_otp_challenges_email_digest check(email_digest ~ '^[A-Za-z0-9_-]+$'),
 email_digest_key_version varchar(16) not null constraint ck_email_otp_challenges_email_version check(email_digest_key_version ~ '^v[1-9][0-9]{0,8}$'),
 email_ciphertext bytea not null, email_nonce bytea not null, email_key_version varchar(128) not null,
 otp_digest varchar(512) not null constraint ck_email_otp_challenges_otp_digest check(otp_digest ~ '^[A-Za-z0-9_-]+$'),
 otp_digest_key_version varchar(16) not null constraint ck_email_otp_challenges_otp_version check(otp_digest_key_version ~ '^v[1-9][0-9]{0,8}$'),
 attempt_count integer not null default 0 constraint ck_email_otp_challenges_attempts check(attempt_count between 0 and 5),
 previous_challenge_id uuid,
 created_at timestamptz not null, expires_at timestamptz not null, consumed_at timestamptz, invalidated_at timestamptz,
 version bigint not null default 0 constraint ck_email_otp_challenges_version check(version>=0),
 constraint uq_email_otp_challenges_site_id unique(site_id,id),
 constraint fk_email_otp_challenges_previous_site foreign key(site_id,previous_challenge_id) references email_otp_challenges(site_id,id),
 constraint ck_email_otp_challenges_encryption check(octet_length(email_ciphertext)>=16 and octet_length(email_nonce)=12 and email_key_version ~ '[^[:space:]]'),
 constraint ck_email_otp_challenges_times check(expires_at>created_at and (consumed_at is null or consumed_at>=created_at) and (invalidated_at is null or invalidated_at>=created_at) and not(consumed_at is not null and invalidated_at is not null))
);
create index idx_email_otp_challenges_active on email_otp_challenges(site_id,email_digest,email_digest_key_version,origin,created_at desc) where consumed_at is null and invalidated_at is null;
create index idx_email_otp_challenges_expiry on email_otp_challenges(expires_at);

create table customer_rate_limit_buckets (
 id uuid constraint pk_customer_rate_limits primary key,
 site_id uuid not null constraint fk_customer_rate_limits_site references sites(id),
 bucket_type varchar(16) not null constraint ck_customer_rate_limits_type check(bucket_type in ('SITE','EMAIL','SOURCE_IP','TOKEN','LINK')),
 bucket_digest varchar(512) not null constraint ck_customer_rate_limits_digest check(bucket_digest ~ '^[A-Za-z0-9_-]+$'),
 bucket_digest_key_version varchar(16) not null constraint ck_customer_rate_limits_digest_version check(bucket_digest_key_version ~ '^v[1-9][0-9]{0,8}$'),
 window_started_at timestamptz not null, expires_at timestamptz not null,
 request_count integer not null default 0 constraint ck_customer_rate_limits_count check(request_count>=0),
 version bigint not null default 0 constraint ck_customer_rate_limits_version check(version>=0),
 constraint uq_customer_rate_limits_bucket unique(site_id,bucket_type,bucket_digest,bucket_digest_key_version,window_started_at),
 constraint ck_customer_rate_limits_times check(expires_at>window_started_at)
);
create index idx_customer_rate_limits_expiry on customer_rate_limit_buckets(expires_at);

create table customer_mail_outbox (
 id uuid constraint pk_customer_mail primary key, site_id uuid not null, challenge_id uuid not null,
 status varchar(16) not null default 'PENDING' constraint ck_customer_mail_status check(status in ('PENDING','PROCESSING','SENT','DEAD')),
 payload_ciphertext bytea, payload_nonce bytea, payload_key_version varchar(128),
 attempt_count integer not null default 0 constraint ck_customer_mail_attempts check(attempt_count between 0 and 8),
 next_attempt_at timestamptz not null, lease_owner varchar(128), lease_expires_at timestamptz,
 error_category varchar(32) constraint ck_customer_mail_error check(error_category in ('TRANSIENT','PERMANENT','CONFIGURATION','EXHAUSTED')),
 created_at timestamptz not null, updated_at timestamptz not null, completed_at timestamptz, payload_destroyed_at timestamptz,
 version bigint not null default 0 constraint ck_customer_mail_version check(version>=0),
 constraint fk_customer_mail_challenge_site foreign key(site_id,challenge_id) references email_otp_challenges(site_id,id),
 constraint ck_customer_mail_payload check(
  (payload_destroyed_at is null and payload_ciphertext is not null and octet_length(payload_ciphertext)>=16 and payload_nonce is not null and octet_length(payload_nonce)=12 and payload_key_version is not null and payload_key_version ~ '[^[:space:]]')
  or (payload_destroyed_at is not null and payload_ciphertext is null and payload_nonce is null and payload_key_version is null and status in ('SENT','DEAD'))),
 constraint ck_customer_mail_lease check((lease_owner is null)=(lease_expires_at is null) and (lease_owner is null or lease_owner ~ '[^[:space:]]') and (status='PROCESSING')=(lease_owner is not null)),
 constraint ck_customer_mail_terminal check((status in ('SENT','DEAD'))=(completed_at is not null) and (status<>'SENT' or payload_destroyed_at is not null)),
 constraint ck_customer_mail_times check(updated_at>=created_at and (completed_at is null or completed_at>=created_at) and (payload_destroyed_at is null or payload_destroyed_at>=created_at))
);
create index idx_customer_mail_claim on customer_mail_outbox(next_attempt_at,lease_expires_at,created_at,id) where status in ('PENDING','PROCESSING');
create index idx_customer_mail_retention on customer_mail_outbox(completed_at) where status in ('SENT','DEAD');

create table customer_idempotency_records (
 id uuid constraint pk_customer_idempotency primary key,
 site_id uuid not null constraint fk_customer_idempotency_site references sites(id),
 scope varchar(128) not null constraint ck_customer_idempotency_scope check(scope ~ '^[A-Za-z0-9:_-]+$'),
 idempotency_key varchar(512) not null constraint ck_customer_idempotency_key check(idempotency_key ~ '^[A-Za-z0-9_-]+$'),
 fingerprint char(64) not null constraint ck_customer_idempotency_fingerprint check(fingerprint ~ '^[0-9a-f]{64}$'),
 status varchar(16) not null default 'IN_PROGRESS' constraint ck_customer_idempotency_status check(status in ('IN_PROGRESS','COMPLETED')),
 resource_id uuid, response_status integer constraint ck_customer_idempotency_response_status check(response_status between 100 and 599),
 response_ciphertext bytea, response_nonce bytea, response_key_version varchar(128),
 created_at timestamptz not null, updated_at timestamptz not null, expires_at timestamptz not null,
 version bigint not null default 0 constraint ck_customer_idempotency_version check(version>=0),
 constraint uq_customer_idempotency_key unique(site_id,scope,idempotency_key),
 constraint ck_customer_idempotency_result check((status='IN_PROGRESS' and resource_id is null and response_status is null and response_ciphertext is null)
  or (status='COMPLETED' and resource_id is not null and response_status is not null)),
 constraint ck_customer_idempotency_encryption check((response_ciphertext is null and response_nonce is null and response_key_version is null)
  or (response_ciphertext is not null and octet_length(response_ciphertext)>=16 and response_nonce is not null and octet_length(response_nonce)=12 and response_key_version is not null and response_key_version ~ '[^[:space:]]')),
 constraint ck_customer_idempotency_times check(updated_at>=created_at and expires_at>created_at)
);
create index idx_customer_idempotency_expiry on customer_idempotency_records(expires_at) where status='COMPLETED';
