-- Immutable Portal policy intent, original provider receipts and safe publication evidence.
-- Provider navigation URLs and raw provider responses never belong in these tables.
create table stripe_portal_configuration_intents (
 id uuid primary key,payment_account_id uuid not null references payment_accounts(id),site_id uuid not null references sites(id),
 policy_version bigint not null check(policy_version>=0),policy_digest varchar(64) not null check(policy_digest ~ '^[a-f0-9]{64}$'),
 owner_operation uuid not null,owner_subscription uuid not null,operation_kind varchar(32) not null default 'PORTAL' check(operation_kind='PORTAL'),
 operation_key varchar(255) not null unique,fingerprint varchar(255) not null,
 unique(payment_account_id,site_id,policy_version),unique(id,payment_account_id,site_id,policy_version,policy_digest),
 foreign key(site_id,owner_subscription,owner_operation,operation_kind) references subscription_operations(site_id,subscription_id,id,kind)
);
create index idx_portal_configuration_owner on stripe_portal_configuration_intents(site_id,owner_subscription,owner_operation,operation_kind);
create table stripe_portal_configurations (
 intent_id uuid primary key,payment_account_id uuid not null,site_id uuid not null,policy_version bigint not null,policy_digest varchar(64) not null,
 configuration_reference varchar(255) not null check(configuration_reference ~ '^bpc_[A-Za-z0-9]{1,251}$'),
 creation_step uuid not null unique,step_kind varchar(32) not null default 'PORTAL_CONFIGURATION_CREATE' check(step_kind='PORTAL_CONFIGURATION_CREATE'),
 unique(payment_account_id,configuration_reference),unique(intent_id,payment_account_id,configuration_reference),
 foreign key(intent_id,payment_account_id,site_id,policy_version,policy_digest) references stripe_portal_configuration_intents(id,payment_account_id,site_id,policy_version,policy_digest),
 foreign key(creation_step,payment_account_id,intent_id,step_kind,configuration_reference) references provider_operations(id,payment_account_id,resource_id,kind,provider_reference)
);
create table stripe_portal_session_intents (
 operation_id uuid primary key,site_id uuid not null,customer_id uuid not null,subscription_id uuid not null,payment_account_id uuid not null,
 operation_kind varchar(32) not null default 'PORTAL' check(operation_kind='PORTAL'),configuration_id uuid not null,configuration_reference varchar(255) not null,
 fingerprint varchar(255) not null,descriptor jsonb not null check(jsonb_typeof(descriptor)='object'),
 unique(operation_id,payment_account_id,configuration_id,configuration_reference),
 foreign key(site_id,customer_id,subscription_id) references subscriptions(site_id,customer_id,id),
 foreign key(site_id,subscription_id,payment_account_id) references subscriptions(site_id,id,payment_account_id),
 foreign key(site_id,subscription_id,operation_id,operation_kind) references subscription_operations(site_id,subscription_id,id,kind),
 foreign key(configuration_id,payment_account_id,configuration_reference) references stripe_portal_configurations(intent_id,payment_account_id,configuration_reference)
);
create index idx_portal_session_owner on stripe_portal_session_intents(site_id,customer_id,subscription_id);
create index idx_portal_session_account on stripe_portal_session_intents(site_id,subscription_id,payment_account_id);
create index idx_portal_session_config on stripe_portal_session_intents(configuration_id,payment_account_id,configuration_reference);
create table stripe_portal_session_receipts (
 operation_id uuid primary key,payment_account_id uuid not null,configuration_id uuid not null,configuration_reference varchar(255) not null,
 session_reference varchar(255) not null check(session_reference ~ '^bps_[A-Za-z0-9]{1,251}$'),
 creation_step uuid not null unique,step_kind varchar(32) not null default 'PORTAL_SESSION_CREATE' check(step_kind='PORTAL_SESSION_CREATE'),
 expires_at timestamptz not null,unique(payment_account_id,session_reference),
 foreign key(operation_id,payment_account_id,configuration_id,configuration_reference) references stripe_portal_session_intents(operation_id,payment_account_id,configuration_id,configuration_reference),
 foreign key(creation_step,payment_account_id,operation_id,step_kind,session_reference) references provider_operations(id,payment_account_id,resource_id,kind,provider_reference)
);
create function protect_stripe_portal_identity() returns trigger language plpgsql as $$
begin
 if new is distinct from old then raise exception 'Portal proof is immutable' using errcode='23514'; end if;
 return new;
end; $$;
create trigger stripe_portal_config_intent_identity before update on stripe_portal_configuration_intents for each row execute function protect_stripe_portal_identity();
create trigger stripe_portal_config_identity before update on stripe_portal_configurations for each row execute function protect_stripe_portal_identity();
create trigger stripe_portal_session_intent_identity before update on stripe_portal_session_intents for each row execute function protect_stripe_portal_identity();
create trigger stripe_portal_session_identity before update on stripe_portal_session_receipts for each row execute function protect_stripe_portal_identity();

-- A current server-projected cancellation is a distinct predecessor, never a CANCEL operation.
alter table subscription_events add constraint uq_subscription_cancellation_event
 unique(id,site_id,subscription_id,payment_account_id,event_type,revision,provider_reference);
alter table stripe_subscription_cancellation_proofs
 add column prior_event uuid unique,add column prior_event_revision bigint,
 add column prior_event_type varchar(32) not null default 'CANCEL_SCHEDULED' check(prior_event_type='CANCEL_SCHEDULED');
alter table stripe_subscription_cancellation_proofs drop constraint stripe_subscription_cancellation_proofs_check;
alter table stripe_subscription_cancellation_proofs add constraint ck_stripe_cancellation_predecessor check(
 (mode='IMMEDIATE' and operation_kind='CANCEL' and step_kind='SUBSCRIPTION_CANCEL' and boundary is null and num_nonnulls(prior_operation,prior_event,prior_event_revision)=0)
 or (mode='PERIOD_END' and operation_kind='CANCEL' and step_kind='SUBSCRIPTION_UPDATE' and boundary is not null and num_nonnulls(prior_operation,prior_event,prior_event_revision)=0)
 or (mode='WITHDRAW' and operation_kind='WITHDRAW_CANCEL' and step_kind='SUBSCRIPTION_UPDATE' and boundary is not null
     and ((prior_operation is not null and prior_event is null and prior_event_revision is null)
       or (prior_operation is null and prior_event is not null and prior_event_revision is not null and prior_event_revision>=0))));
alter table stripe_subscription_cancellation_proofs add constraint fk_stripe_withdrawal_observed_event
 foreign key(prior_event,site_id,subscription_id,payment_account_id,prior_event_type,prior_event_revision,subscription_reference)
 references subscription_events(id,site_id,subscription_id,payment_account_id,event_type,revision,provider_reference);
