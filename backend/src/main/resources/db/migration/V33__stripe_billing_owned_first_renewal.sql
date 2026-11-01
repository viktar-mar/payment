-- Additive evidence relations. The original clock and initial-only evidence stay unchanged.
-- Phase A establishes relation and foreign-key shapes; behavioral guards follow hosted RED.
create table stripe_billing_first_renewals (
 id uuid primary key, owned_intent uuid not null unique references stripe_billing_owned_subscriptions(id),
 payment_account_id uuid not null, site_id uuid not null, customer_id uuid not null, subscription_id uuid not null unique,
 source_sha varchar(40) not null check(source_sha ~ '^[a-f0-9]{40}$'),
 original_root uuid not null references subscription_action_capabilities(id),
 original_attempt uuid not null references subscription_confirmation_attempts(id),
 initial_bill uuid not null references subscription_billing_records(id), bill_version bigint not null check(bill_version>=0),
 initial_payment uuid not null references payments(id), payment_version bigint not null check(payment_version>=0),
 invoice_reference varchar(255) not null, allocation_reference varchar(255) not null, payment_intent_reference varchar(255) not null,
 subscription_revision bigint not null check(subscription_revision>=0), policy_version bigint not null check(policy_version>=0),
 fingerprint varchar(64) not null check(fingerprint ~ '^[a-f0-9]{64}$'), admitted_at timestamptz not null,
 stopped_at timestamptz, stop_reason varchar(32),
 unique(id,payment_account_id,site_id,customer_id,subscription_id),
 foreign key(subscription_id,payment_account_id,site_id,customer_id) references subscriptions(id,payment_account_id,site_id,customer_id),
 check((stopped_at is null and stop_reason is null) or (stopped_at>=admitted_at and stop_reason in
  ('AUTHORITY_DRIFT','ADVANCE_UNKNOWN','READY_UNAVAILABLE','GRAPH_UNSUPPORTED','PAYMENT_NOT_OBSERVED')))
);
create table stripe_billing_first_renewal_steps (
 id uuid primary key, admission_id uuid not null references stripe_billing_first_renewals(id),
 ordinal integer not null check(ordinal in (1,2)), target bigint not null check(target>0),
 predecessor_ready uuid, boundary_id uuid,
 operation_key varchar(255) not null unique, fingerprint varchar(64) not null check(fingerprint ~ '^[a-f0-9]{64}$'),
 prepared_at timestamptz not null, unique(admission_id,ordinal), unique(id,admission_id),
 check(operation_key='billing-renewal-advance-v1:'||admission_id::text||':'||ordinal::text),
 check((ordinal=1 and predecessor_ready is null and boundary_id is null) or (ordinal=2 and predecessor_ready is not null and boundary_id is not null))
);
create table stripe_billing_first_renewal_acks (
 step_id uuid primary key, admission_id uuid not null, provider_operation uuid not null unique references provider_operations(id),
 claimant varchar(255) not null, deadline timestamptz not null,
 reference varchar(255) not null, object_type varchar(32) not null, livemode boolean not null,
 name varchar(255) not null, frozen_time bigint not null, status varchar(32) not null,
 provider_created bigint not null, provider_deletes_after bigint not null, advancing_target bigint, acknowledged_at timestamptz not null,
 unique(step_id,admission_id),
 foreign key(step_id,admission_id) references stripe_billing_first_renewal_steps(id,admission_id)
);
create table stripe_billing_first_renewal_ready (
 id uuid primary key, admission_id uuid not null, step_id uuid not null unique,
 reference varchar(255) not null, object_type varchar(32) not null, livemode boolean not null,
 name varchar(255) not null, frozen_time bigint not null, status varchar(32) not null,
 provider_created bigint not null, provider_deletes_after bigint not null, advancing_target bigint, observed_at timestamptz not null,
 unique(id,admission_id), foreign key(step_id,admission_id) references stripe_billing_first_renewal_acks(step_id,admission_id)
);
create table stripe_billing_first_renewal_boundary (
 id uuid primary key, admission_id uuid not null unique references stripe_billing_first_renewals(id), ready_id uuid not null unique,
 item_reference varchar(255) not null, invoice_reference varchar(255) not null,
 period_start timestamptz not null, period_end timestamptz not null check(period_start<period_end),
 fingerprint varchar(64) not null check(fingerprint ~ '^[a-f0-9]{64}$'), observed_at timestamptz not null,
 unique(id,admission_id), foreign key(ready_id,admission_id) references stripe_billing_first_renewal_ready(id,admission_id)
);
alter table stripe_billing_first_renewal_steps add foreign key(predecessor_ready,admission_id) references stripe_billing_first_renewal_ready(id,admission_id);
alter table stripe_billing_first_renewal_steps add foreign key(boundary_id,admission_id) references stripe_billing_first_renewal_boundary(id,admission_id);
create table stripe_billing_first_renewal_publications (
 admission_id uuid primary key references stripe_billing_first_renewals(id), ready_id uuid not null unique,
 boundary_id uuid not null unique, renewal_bill uuid not null unique references subscription_billing_records(id),
 renewal_payment uuid not null unique references payments(id), projected_revision bigint not null check(projected_revision>=0), published_at timestamptz not null,
 foreign key(ready_id,admission_id) references stripe_billing_first_renewal_ready(id,admission_id),
 foreign key(boundary_id,admission_id) references stripe_billing_first_renewal_boundary(id,admission_id)
);
