-- Typed ledger snapshots deliberately contain no bearer credentials, email or raw provider payload.
alter table prices add constraint uq_prices_exact_version unique(site_id,product_id,id,price_version);

create table subscriptions (
 id uuid primary key, site_id uuid not null references sites(id), customer_id uuid not null,
 product_id uuid not null, payment_account_id uuid not null references payment_accounts(id),
 currency char(3) not null check(currency ~ '^[A-Z]{3}$'),
 billing_price_id uuid not null,
 billing_price_key varchar(63), billing_price_version integer, billing_amount_minor bigint,
 billing_interval varchar(16), billing_trial_days integer, billing_trial_policy varchar(32), billing_tax_mode varchar(16),
 check ((billing_price_id is null and num_nonnulls(billing_price_key,billing_price_version,billing_amount_minor,billing_interval,billing_trial_days,billing_trial_policy,billing_tax_mode)=0)
   or (billing_price_id is not null and num_nonnulls(billing_price_key,billing_price_version,billing_amount_minor,billing_interval,billing_trial_days,billing_trial_policy,billing_tax_mode)=7
   and billing_price_key ~ '^[a-z0-9][a-z0-9-]{2,62}$' and billing_price_version>0 and billing_amount_minor>0 and billing_interval in ('MONTH','YEAR')
   and billing_trial_days>=0 and billing_trial_policy in ('REQUIRED_BEFORE_TRIAL','OPTIONAL_DURING_TRIAL') and billing_tax_mode in ('NONE','INCLUSIVE','EXCLUSIVE'))),
 foreign key(site_id,product_id,billing_price_id,billing_price_version) references prices(site_id,product_id,id,price_version),
 entitled_price_id uuid,
 entitled_price_key varchar(63), entitled_price_version integer, entitled_amount_minor bigint,
 entitled_interval varchar(16), entitled_trial_days integer, entitled_trial_policy varchar(32), entitled_tax_mode varchar(16),
 check ((entitled_price_id is null and num_nonnulls(entitled_price_key,entitled_price_version,entitled_amount_minor,entitled_interval,entitled_trial_days,entitled_trial_policy,entitled_tax_mode)=0)
   or (entitled_price_id is not null and num_nonnulls(entitled_price_key,entitled_price_version,entitled_amount_minor,entitled_interval,entitled_trial_days,entitled_trial_policy,entitled_tax_mode)=7
   and entitled_price_key ~ '^[a-z0-9][a-z0-9-]{2,62}$' and entitled_price_version>0 and entitled_amount_minor>0 and entitled_interval in ('MONTH','YEAR')
   and entitled_trial_days>=0 and entitled_trial_policy in ('REQUIRED_BEFORE_TRIAL','OPTIONAL_DURING_TRIAL') and entitled_tax_mode in ('NONE','INCLUSIVE','EXCLUSIVE'))),
 foreign key(site_id,product_id,entitled_price_id,entitled_price_version) references prices(site_id,product_id,id,price_version),
 pending_price_id uuid,
 pending_price_key varchar(63), pending_price_version integer, pending_amount_minor bigint,
 pending_interval varchar(16), pending_trial_days integer, pending_trial_policy varchar(32), pending_tax_mode varchar(16),
 check ((pending_price_id is null and num_nonnulls(pending_price_key,pending_price_version,pending_amount_minor,pending_interval,pending_trial_days,pending_trial_policy,pending_tax_mode)=0)
   or (pending_price_id is not null and num_nonnulls(pending_price_key,pending_price_version,pending_amount_minor,pending_interval,pending_trial_days,pending_trial_policy,pending_tax_mode)=7
   and pending_price_key ~ '^[a-z0-9][a-z0-9-]{2,62}$' and pending_price_version>0 and pending_amount_minor>0 and pending_interval in ('MONTH','YEAR')
   and pending_trial_days>=0 and pending_trial_policy in ('REQUIRED_BEFORE_TRIAL','OPTIONAL_DURING_TRIAL') and pending_tax_mode in ('NONE','INCLUSIVE','EXCLUSIVE'))),
 foreign key(site_id,product_id,pending_price_id,pending_price_version) references prices(site_id,product_id,id,price_version),
 billing_status varchar(16) not null check(billing_status in ('INCOMPLETE','TRIALING','ACTIVE','PAST_DUE','PAUSED','UNPAID','CANCELLED')),
 provider_reference varchar(255), current_period_start timestamptz, current_period_end timestamptz,
 paid_through timestamptz, trial_start timestamptz, trial_end timestamptz,
 cancellation_effective_at timestamptz, pending_operation_id uuid, pending_effective_at timestamptz, pending_state varchar(32),
 debt_billing_record_id uuid, debt_period_start timestamptz, debt_period_end timestamptz, grace_deadline timestamptz,
 terminal_reason varchar(255), revision bigint not null default 0 check(revision>=0),
 created_at timestamptz not null, updated_at timestamptz not null, last_reconciled_at timestamptz,
 next_reconcile_at timestamptz not null default now(), reconcile_lease_owner varchar(255),
 reconcile_lease_token uuid, reconcile_lease_until timestamptz, reconcile_lease_revision bigint,
 reconcile_attempts integer not null default 0 check(reconcile_attempts>=0),
 unique(site_id,id), unique(site_id,id,payment_account_id), unique(site_id,customer_id,id),
 unique(site_id,id,product_id,payment_account_id), unique(site_id,id,product_id),
 foreign key(site_id,customer_id) references customers(site_id,id),
 foreign key(site_id,product_id) references products(site_id,id),
 check(updated_at>=created_at),
 check((current_period_start is null)=(current_period_end is null) and (current_period_start is null or current_period_start<current_period_end)),
 check((trial_start is null)=(trial_end is null) and (trial_start is null or trial_start<trial_end)),
 check((pending_operation_id is null and pending_price_id is null and pending_effective_at is null and pending_state is null)
   or (pending_operation_id is not null and pending_price_id is not null and pending_effective_at is not null and pending_state is not null and pending_state in ('PAYMENT_PENDING','SCHEDULED','UNKNOWN'))),
 check((debt_billing_record_id is null and debt_period_start is null and debt_period_end is null and grace_deadline is null)
   or (debt_billing_record_id is not null and debt_period_start is not null and debt_period_end is not null and debt_period_start<debt_period_end)),
 check(terminal_reason is null or billing_status='CANCELLED'),
 check(num_nonnulls(reconcile_lease_owner,reconcile_lease_token,reconcile_lease_until,reconcile_lease_revision) in (0,4)),
 check(reconcile_lease_revision is null or reconcile_lease_revision>=0)
);
create unique index uq_subscription_live_product on subscriptions(site_id,customer_id,product_id) where billing_status<>'CANCELLED';
create index ix_subscription_reconcile on subscriptions(next_reconcile_at,id);

create table subscription_operations (
 id uuid primary key, site_id uuid not null, customer_id uuid not null, subscription_id uuid,
 kind varchar(32) not null check(kind in ('CREATE','CHANGE','CLEAR_CHANGE','CANCEL','WITHDRAW_CANCEL','SETUP','RECOVER','RESUME','PORTAL','RECONCILE')),
 status varchar(32) not null check(status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN','SUCCEEDED','FAILED')),
 authority_scope varchar(255) not null check(authority_scope ~ '^[A-Za-z0-9_.:/-]+$'),
 key_digest varchar(255) not null check(key_digest ~ '^[A-Za-z0-9_.:/-]+$'),
 fingerprint varchar(255) not null check(fingerprint ~ '^[A-Za-z0-9_.:/-]+$'),
 provider_operation_key varchar(255) not null unique,
 expected_revision bigint not null check(expected_revision>=0), policy_version bigint not null check(policy_version>=0),
 provider_reference varchar(255), safe_failure_code varchar(255),
 created_at timestamptz not null, updated_at timestamptz not null, version bigint not null default 0 check(version>=0),
 unique(site_id,authority_scope,kind,key_digest), unique(site_id,customer_id,id), unique(site_id,subscription_id,id),
 foreign key(site_id,customer_id) references customers(site_id,id),
 foreign key(site_id,customer_id,subscription_id) references subscriptions(site_id,customer_id,id),
 check(kind='CREATE' or subscription_id is not null), check(updated_at>=created_at)
);
create unique index uq_subscription_unresolved_operation on subscription_operations(subscription_id)
 where status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN') and kind not in ('PORTAL','RECONCILE');
alter table subscriptions add constraint fk_subscription_pending_operation foreign key(site_id,id,pending_operation_id) references subscription_operations(site_id,subscription_id,id) deferrable initially deferred;

create table subscription_trial_eligibility (
 site_id uuid not null, customer_id uuid not null, product_id uuid not null, reserved_by_operation uuid, consumed_at timestamptz,
 version bigint not null default 0 check(version>=0), primary key(site_id,customer_id,product_id),
 foreign key(site_id,customer_id) references customers(site_id,id),
 foreign key(site_id,product_id) references products(site_id,id),
 foreign key(site_id,customer_id,reserved_by_operation) references subscription_operations(site_id,customer_id,id),
 check(consumed_at is null or reserved_by_operation is not null)
);
create unique index uq_trial_operation on subscription_trial_eligibility(reserved_by_operation) where reserved_by_operation is not null;

create table subscription_billing_records (
 id uuid primary key, site_id uuid not null, subscription_id uuid not null, product_id uuid not null, payment_account_id uuid not null,
 provider_invoice_reference varchar(255) not null, kind varchar(16) not null check(kind in ('INITIAL','RENEWAL','PRORATION')),
 currency char(3) not null check(currency ~ '^[A-Z]{3}$'),
 billed_price_id uuid not null,
 billed_price_key varchar(63), billed_price_version integer, billed_amount_minor bigint,
 billed_interval varchar(16), billed_trial_days integer, billed_trial_policy varchar(32), billed_tax_mode varchar(16),
 check ((billed_price_id is null and num_nonnulls(billed_price_key,billed_price_version,billed_amount_minor,billed_interval,billed_trial_days,billed_trial_policy,billed_tax_mode)=0)
   or (billed_price_id is not null and num_nonnulls(billed_price_key,billed_price_version,billed_amount_minor,billed_interval,billed_trial_days,billed_trial_policy,billed_tax_mode)=7
   and billed_price_key ~ '^[a-z0-9][a-z0-9-]{2,62}$' and billed_price_version>0 and billed_amount_minor>0 and billed_interval in ('MONTH','YEAR')
   and billed_trial_days>=0 and billed_trial_policy in ('REQUIRED_BEFORE_TRIAL','OPTIONAL_DURING_TRIAL') and billed_tax_mode in ('NONE','INCLUSIVE','EXCLUSIVE'))),
 foreign key(site_id,product_id,billed_price_id,billed_price_version) references prices(site_id,product_id,id,price_version),
 previous_price_id uuid,
 previous_price_key varchar(63), previous_price_version integer, previous_amount_minor bigint,
 previous_interval varchar(16), previous_trial_days integer, previous_trial_policy varchar(32), previous_tax_mode varchar(16),
 check ((previous_price_id is null and num_nonnulls(previous_price_key,previous_price_version,previous_amount_minor,previous_interval,previous_trial_days,previous_trial_policy,previous_tax_mode)=0)
   or (previous_price_id is not null and num_nonnulls(previous_price_key,previous_price_version,previous_amount_minor,previous_interval,previous_trial_days,previous_trial_policy,previous_tax_mode)=7
   and previous_price_key ~ '^[a-z0-9][a-z0-9-]{2,62}$' and previous_price_version>0 and previous_amount_minor>0 and previous_interval in ('MONTH','YEAR')
   and previous_trial_days>=0 and previous_trial_policy in ('REQUIRED_BEFORE_TRIAL','OPTIONAL_DURING_TRIAL') and previous_tax_mode in ('NONE','INCLUSIVE','EXCLUSIVE'))),
 foreign key(site_id,product_id,previous_price_id,previous_price_version) references prices(site_id,product_id,id,price_version),
 period_start timestamptz not null, period_end timestamptz not null check(period_start<period_end),
 amount_due_minor bigint not null check(amount_due_minor>=0),
 settlement varchar(32) not null check(settlement in ('OPEN','CONFIRMED_CASH','ZERO_AMOUNT','UNSUPPORTED','VOID')),
 version bigint not null default 0 check(version>=0), created_at timestamptz not null, updated_at timestamptz not null check(updated_at>=created_at),
 captured_policy_version bigint check(captured_policy_version>=0), grace_deadline timestamptz,
 unique(payment_account_id,provider_invoice_reference), unique(site_id,subscription_id,payment_account_id,id),
 foreign key(site_id,subscription_id,product_id,payment_account_id) references subscriptions(site_id,id,product_id,payment_account_id),
 check(settlement<>'ZERO_AMOUNT' or amount_due_minor=0), check(settlement<>'CONFIRMED_CASH' or amount_due_minor>0)
);
alter table subscriptions add constraint fk_subscription_debt foreign key(site_id,id,payment_account_id,debt_billing_record_id)
 references subscription_billing_records(site_id,subscription_id,payment_account_id,id) deferrable initially deferred;

create table subscription_provider_objects (
 id uuid primary key, site_id uuid not null, subscription_id uuid not null, payment_account_id uuid not null,
 provider_type varchar(16) not null, object_type varchar(32) not null, provider_reference varchar(255) not null,
 billing_record_id uuid, created_at timestamptz not null,
 foreign key(site_id,subscription_id,payment_account_id) references subscriptions(site_id,id,payment_account_id),
 foreign key(site_id,subscription_id,payment_account_id,billing_record_id) references subscription_billing_records(site_id,subscription_id,payment_account_id,id)
);
create unique index uq_subscription_provider_object on subscription_provider_objects(provider_type,payment_account_id,object_type,provider_reference);

create table subscription_event_sequences (
 subscription_id uuid primary key references subscriptions(id), next_sequence bigint not null check(next_sequence>=0)
);
create table subscription_events (
 id uuid primary key, site_id uuid not null, subscription_id uuid not null, payment_account_id uuid not null,
 sequence_number bigint not null check(sequence_number>0), revision bigint not null check(revision>=0),
 event_type varchar(32) not null check(event_type in ('CREATED','CONFIRMED','TRIAL_STARTED','TRIAL_ENDED','PAUSED','PERIOD_PAID','RENEWAL_FAILED','GRACE_STARTED','ACCESS_SUSPENDED','RECOVERED','CHANGE_REQUESTED','CHANGE_SCHEDULED','CHANGE_APPLIED','CHANGE_CLEARED','CANCEL_SCHEDULED','CANCEL_WITHDRAWN','CANCELLED','METHOD_UPDATED','RECONCILIATION_REQUIRED')),
 source varchar(32) not null check(source in ('API','PROVIDER_WEBHOOK','RECONCILIATION','DEADLINE')),
 occurred_at timestamptz not null, received_at timestamptz not null, operation_id uuid, billing_record_id uuid, payment_id uuid,
 provider_reference varchar(255), fact_key varchar(255) not null, request_id varchar(255),
 foreign key(site_id,subscription_id,payment_account_id) references subscriptions(site_id,id,payment_account_id),
 foreign key(site_id,subscription_id,operation_id) references subscription_operations(site_id,subscription_id,id),
 foreign key(payment_id,site_id,payment_account_id) references payments(id,site_id,payment_account_id),
 foreign key(site_id,subscription_id,payment_account_id,billing_record_id) references subscription_billing_records(site_id,subscription_id,payment_account_id,id)
);
create unique index uq_subscription_fact on subscription_events(subscription_id,fact_key);
create unique index uq_subscription_event_sequence on subscription_events(subscription_id,sequence_number);
create function protect_subscription_journal() returns trigger language plpgsql as $$
begin raise exception 'subscription journal is append only' using errcode='23514'; end; $$;
create trigger subscription_journal_append_only before update or delete on subscription_events for each row execute function protect_subscription_journal();

create table subscription_quotes (
 id uuid primary key, site_id uuid not null, customer_id uuid not null, subscription_id uuid not null,
 product_id uuid not null, authority_scope varchar(255) not null, from_price_id uuid not null, from_price_version integer not null,
 currency char(3) not null check(currency ~ '^[A-Z]{3}$'),
 target_price_id uuid not null,
 target_price_key varchar(63), target_price_version integer, target_amount_minor bigint,
 target_interval varchar(16), target_trial_days integer, target_trial_policy varchar(32), target_tax_mode varchar(16),
 check ((target_price_id is null and num_nonnulls(target_price_key,target_price_version,target_amount_minor,target_interval,target_trial_days,target_trial_policy,target_tax_mode)=0)
   or (target_price_id is not null and num_nonnulls(target_price_key,target_price_version,target_amount_minor,target_interval,target_trial_days,target_trial_policy,target_tax_mode)=7
   and target_price_key ~ '^[a-z0-9][a-z0-9-]{2,62}$' and target_price_version>0 and target_amount_minor>0 and target_interval in ('MONTH','YEAR')
   and target_trial_days>=0 and target_trial_policy in ('REQUIRED_BEFORE_TRIAL','OPTIONAL_DURING_TRIAL') and target_tax_mode in ('NONE','INCLUSIVE','EXCLUSIVE'))),
 foreign key(site_id,product_id,target_price_id,target_price_version) references prices(site_id,product_id,id,price_version),
 timing varchar(16) not null check(timing in ('IMMEDIATE','NEXT_PERIOD')),
 due_now_minor bigint not null, next_amount_minor bigint not null check(next_amount_minor>0),
 effective_at timestamptz not null, expires_at timestamptz not null, created_at timestamptz not null default now(), tax_final boolean not null,
 subscription_revision bigint not null check(subscription_revision>=0), policy_version bigint not null check(policy_version>=0), provider_quote_reference varchar(255) not null,
 foreign key(site_id,customer_id,subscription_id) references subscriptions(site_id,customer_id,id),
 foreign key(site_id,subscription_id,product_id) references subscriptions(site_id,id,product_id),
 foreign key(site_id,product_id,from_price_id,from_price_version) references prices(site_id,product_id,id,price_version),
 check(expires_at>created_at and expires_at<=created_at+interval '5 minutes')
);

create table subscription_action_capabilities (
 id uuid primary key, site_id uuid not null, customer_id uuid not null, payment_account_id uuid not null,
 subscription_id uuid not null, operation_id uuid not null, purpose varchar(32) not null, token_digest varchar(255) not null unique,
 issued_at timestamptz not null, expires_at timestamptz not null, consumed_at timestamptz,
 confirmation_authority_scope varchar(255), confirmation_key_digest varchar(255), confirmation_fingerprint varchar(255),
 consent_purpose varchar(32), recurring_consent boolean, consent_policy_version bigint check(consent_policy_version>=0), consent_at timestamptz,
 foreign key(site_id,customer_id,subscription_id) references subscriptions(site_id,customer_id,id),
 foreign key(site_id,subscription_id,payment_account_id) references subscriptions(site_id,id,payment_account_id),
 foreign key(site_id,subscription_id,operation_id) references subscription_operations(site_id,subscription_id,id),
 check(expires_at>issued_at and expires_at<=issued_at+interval '10 minutes'),
 check(consumed_at is null or consumed_at>=issued_at),
 check(num_nonnulls(confirmation_authority_scope,confirmation_key_digest,confirmation_fingerprint) in (0,3)),
 check(num_nonnulls(consent_purpose,recurring_consent,consent_policy_version,consent_at) in (0,4))
);
create unique index uq_subscription_action_confirmation on subscription_action_capabilities(site_id,confirmation_authority_scope,confirmation_key_digest) where confirmation_key_digest is not null;
create table subscription_portal_sessions (
 public_operation_id uuid primary key, site_id uuid not null, customer_id uuid not null, subscription_id uuid not null, expires_at timestamptz not null,
 foreign key(site_id,customer_id,subscription_id) references subscriptions(site_id,customer_id,id),
 foreign key(site_id,subscription_id,public_operation_id) references subscription_operations(site_id,subscription_id,id)
);
