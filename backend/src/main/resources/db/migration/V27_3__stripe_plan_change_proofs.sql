-- App-authored immutable quote terms; raw SDK/provider payloads and secrets are never stored.
alter table subscription_change_intents add constraint uq_stripe_plan_intent unique(operation_id,site_id,subscription_id,quote_id);
alter table subscription_quotes add constraint uq_stripe_plan_common_quote unique(id,site_id,subscription_id,provider_quote_reference);
alter table subscriptions add constraint uq_stripe_plan_remote_subscription unique(id,payment_account_id,provider_reference);
create table stripe_plan_quotes (
 id uuid primary key, payment_account_id uuid not null, site_id uuid not null, customer_id uuid not null, subscription_id uuid not null,
 reference varchar(255) not null unique, subscription_reference varchar(255) not null check(subscription_reference ~ '^sub_[A-Za-z0-9]{1,251}$'),
 customer_reference varchar(255) not null, source_price_id uuid not null references prices(id), target_price_id uuid not null references prices(id),
 subscription_revision bigint not null check(subscription_revision>=0), policy_version bigint not null check(policy_version>=0),
 timing varchar(16) not null check(timing in ('IMMEDIATE','NEXT_PERIOD')), anchor bigint not null check(anchor>0),
 period_start bigint not null check(period_start>0), period_end bigint not null check(period_end>period_start),
 expires_at bigint not null check(expires_at>anchor and expires_at<=anchor+300), due_now_minor bigint not null check(due_now_minor>=0),
 descriptor jsonb not null check(jsonb_typeof(descriptor)='object'),
 unique(id,payment_account_id,site_id,customer_id,subscription_id), unique(id,payment_account_id,subscription_id),
 foreign key(site_id,customer_id,subscription_id) references subscriptions(site_id,customer_id,id),
 foreign key(site_id,subscription_id,payment_account_id) references subscriptions(site_id,id,payment_account_id),
 foreign key(payment_account_id,site_id,customer_id,customer_reference) references stripe_billing_customers(payment_account_id,site_id,customer_id,provider_reference),
 foreign key(subscription_id,payment_account_id,subscription_reference) references subscriptions(id,payment_account_id,provider_reference),
 check(reference='bq1:'||id::text), check(anchor>=period_start and anchor<period_end),
 check(timing='IMMEDIATE' or due_now_minor=0),
 check(((descriptor->>'id')::uuid=id and (descriptor->'account'->>'value')::uuid=payment_account_id
   and (descriptor->'site'->>'value')::uuid=site_id and (descriptor->'customer'->>'value')::uuid=customer_id
   and (descriptor->>'subscription')::uuid=subscription_id and descriptor->>'subscription_reference'=subscription_reference
   and descriptor->>'customer_reference'=customer_reference) is true)
);
create index idx_stripe_plan_quote_owner on stripe_plan_quotes(site_id,customer_id,subscription_id);
create table stripe_plan_results (
 operation_id uuid primary key, operation_kind varchar(32) not null default 'CHANGE' check(operation_kind='CHANGE'),
 site_id uuid not null, customer_id uuid not null, subscription_id uuid not null, payment_account_id uuid not null, quote_id uuid not null,
 common_quote_id uuid not null, quote_reference varchar(255) not null check(quote_reference='bq1:'||quote_id::text),
 step_id uuid not null, step_kind varchar(32) not null default 'SUBSCRIPTION_UPDATE' check(step_kind='SUBSCRIPTION_UPDATE'),
 subscription_reference varchar(255) not null check(subscription_reference ~ '^sub_[A-Za-z0-9]{1,251}$'),
 invoice_reference varchar(255) not null check(invoice_reference ~ '^in_[A-Za-z0-9]{1,252}$'), pending_expiry bigint check(pending_expiry>0),
 credit_line varchar(255) not null check(credit_line ~ '^il_[A-Za-z0-9]{1,252}$'),
 debit_line varchar(255) not null check(debit_line ~ '^il_[A-Za-z0-9]{1,252}$' and debit_line<>credit_line),
 unique(payment_account_id,invoice_reference),
 foreign key(site_id,customer_id,subscription_id) references subscriptions(site_id,customer_id,id),
 foreign key(site_id,subscription_id,operation_id,operation_kind) references subscription_operations(site_id,subscription_id,id,kind),
 foreign key(operation_id,site_id,subscription_id,common_quote_id) references subscription_change_intents(operation_id,site_id,subscription_id,quote_id),
 foreign key(common_quote_id,site_id,subscription_id,quote_reference) references subscription_quotes(id,site_id,subscription_id,provider_quote_reference),
 foreign key(quote_id,payment_account_id,site_id,customer_id,subscription_id) references stripe_plan_quotes(id,payment_account_id,site_id,customer_id,subscription_id),
 foreign key(step_id,payment_account_id,operation_id,step_kind,subscription_reference) references provider_operations(id,payment_account_id,resource_id,kind,provider_reference)
);
create index idx_stripe_plan_result_owner on stripe_plan_results(site_id,customer_id,subscription_id);
create index idx_stripe_plan_result_quote on stripe_plan_results(quote_id);
create index idx_stripe_plan_result_step on stripe_plan_results(step_id);
create table stripe_plan_schedules (
 operation_id uuid primary key, operation_kind varchar(32) not null default 'CHANGE' check(operation_kind='CHANGE'),
 site_id uuid not null, customer_id uuid not null, subscription_id uuid not null, payment_account_id uuid not null, quote_id uuid not null,
 common_quote_id uuid not null, quote_reference varchar(255) not null check(quote_reference='bq1:'||quote_id::text),
 schedule_reference varchar(255) not null check(schedule_reference ~ '^sub_sched_[A-Za-z0-9]{1,245}$'),
 creation_step uuid not null, creation_kind varchar(32) not null default 'SCHEDULE_CREATE' check(creation_kind='SCHEDULE_CREATE'),
 phase_start bigint not null check(phase_start>0),
 source_proration_behavior varchar(32) not null check(source_proration_behavior in ('none','create_prorations','always_invoice')),
 source_default_payment_method varchar(255) check(source_default_payment_method ~ '^pm_[A-Za-z0-9]{1,252}$'),
 source_collection_method varchar(32) not null check(source_collection_method='charge_automatically'),
 update_step uuid, update_kind varchar(32) not null default 'SCHEDULE_UPDATE' check(update_kind='SCHEDULE_UPDATE'),
 target_end bigint check(target_end>phase_start), clear_operation uuid, clear_kind varchar(32) not null default 'CLEAR_CHANGE' check(clear_kind='CLEAR_CHANGE'),
 release_step uuid, release_kind varchar(32) not null default 'SCHEDULE_RELEASE' check(release_kind='SCHEDULE_RELEASE'),
 unique(payment_account_id,schedule_reference),
 foreign key(site_id,customer_id,subscription_id) references subscriptions(site_id,customer_id,id),
 foreign key(site_id,subscription_id,operation_id,operation_kind) references subscription_operations(site_id,subscription_id,id,kind),
 foreign key(operation_id,site_id,subscription_id,common_quote_id) references subscription_change_intents(operation_id,site_id,subscription_id,quote_id),
 foreign key(common_quote_id,site_id,subscription_id,quote_reference) references subscription_quotes(id,site_id,subscription_id,provider_quote_reference),
 foreign key(quote_id,payment_account_id,site_id,customer_id,subscription_id) references stripe_plan_quotes(id,payment_account_id,site_id,customer_id,subscription_id),
 foreign key(creation_step,payment_account_id,operation_id,creation_kind,schedule_reference) references provider_operations(id,payment_account_id,resource_id,kind,provider_reference),
 foreign key(update_step,payment_account_id,operation_id,update_kind,schedule_reference) references provider_operations(id,payment_account_id,resource_id,kind,provider_reference),
 foreign key(site_id,subscription_id,clear_operation,clear_kind) references subscription_operations(site_id,subscription_id,id,kind),
 foreign key(release_step,payment_account_id,clear_operation,release_kind,schedule_reference) references provider_operations(id,payment_account_id,resource_id,kind,provider_reference),
 check(num_nonnulls(update_step,target_end) in (0,2)), check(num_nonnulls(clear_operation,release_step) in (0,2)), check(release_step is null or update_step is not null)
);
create index idx_stripe_plan_schedule_owner on stripe_plan_schedules(site_id,customer_id,subscription_id);
create index idx_stripe_plan_schedule_quote on stripe_plan_schedules(quote_id);
create index idx_stripe_plan_schedule_create on stripe_plan_schedules(creation_step);
create index idx_stripe_plan_schedule_update on stripe_plan_schedules(update_step);
create index idx_stripe_plan_schedule_clear on stripe_plan_schedules(clear_operation);
create index idx_stripe_plan_schedule_release on stripe_plan_schedules(release_step);
create function protect_stripe_plan_proof() returns trigger language plpgsql as $$
begin
 if tg_table_name='stripe_plan_schedules' then
  if (to_jsonb(new)-array['update_step','target_end','clear_operation','release_step']) is distinct from (to_jsonb(old)-array['update_step','target_end','clear_operation','release_step'])
   or old.update_step is not null and row(new.update_step,new.target_end) is distinct from row(old.update_step,old.target_end)
   or old.release_step is not null and row(new.clear_operation,new.release_step) is distinct from row(old.clear_operation,old.release_step) then
   raise exception 'Schedule proof is immutable' using errcode='23514'; end if;
 elsif new is distinct from old then raise exception 'Plan proof is immutable' using errcode='23514'; end if;
 return new;
end; $$;
create trigger stripe_plan_quote_identity before update on stripe_plan_quotes for each row execute function protect_stripe_plan_proof();
create trigger stripe_plan_result_identity before update on stripe_plan_results for each row execute function protect_stripe_plan_proof();
create trigger stripe_plan_schedule_identity before update on stripe_plan_schedules for each row execute function protect_stripe_plan_proof();

-- A CHANGE grant references its actual original update result, never CREATE or RESUME authority.
alter table stripe_plan_results add constraint uq_stripe_change_grant_result
 unique(operation_id,payment_account_id,subscription_id,step_id,subscription_reference,invoice_reference);
alter table subscription_action_capabilities add column billing_change_operation uuid;
alter table subscription_action_capabilities add constraint ck_stripe_change_grant check(
 (billing_schema='change-v1' and billing_change_operation is not null and billing_change_operation=operation_id)
 or (billing_schema is distinct from 'change-v1' and billing_change_operation is null));
alter table subscription_action_capabilities add constraint fk_stripe_change_grant_result
 foreign key(billing_change_operation,payment_account_id,subscription_id,billing_step_id,billing_subscription_reference,billing_invoice_reference)
 references stripe_plan_results(operation_id,payment_account_id,subscription_id,step_id,subscription_reference,invoice_reference);
alter table subscription_action_capabilities drop constraint ck_billing_discriminated_grant;
alter table subscription_action_capabilities add constraint ck_billing_discriminated_grant check(billing_key_version is null or (
 purpose='PROVIDER_CLIENT' and billing_key_version ~ '^v[1-9][0-9]{0,8}$' and token_digest ~ '^[0-9a-f]{64}$' and billing_proof ~ '^[0-9a-f]{64}$'
 and billing_customer_reference ~ '^cus_[A-Za-z0-9_]{1,200}$' and recurring_consent is true and consent_at is not null and consent_policy_version is not null and consent_purpose is not null
 and ((billing_schema in ('payment-v1','resume-v1','change-v1') and num_nonnulls(billing_subscription_reference,billing_invoice_reference,billing_allocation_reference,billing_intent_reference)=4
   and billing_setup_reference is null and billing_step_reference=billing_subscription_reference
   and billing_subscription_reference ~ '^sub_[A-Za-z0-9_]{1,200}$' and billing_invoice_reference ~ '^in_[A-Za-z0-9_]{1,200}$'
   and billing_allocation_reference ~ '^inpay_[A-Za-z0-9_]{1,200}$' and billing_intent_reference ~ '^pi_[A-Za-z0-9_]{1,200}$'
   and ((billing_schema='payment-v1' and billing_step_kind='SUBSCRIPTION_CREATE' and consent_purpose='CREATE')
     or (billing_schema='resume-v1' and billing_step_kind='SUBSCRIPTION_UPDATE' and consent_purpose='RESUME')
     or (billing_schema='change-v1' and billing_step_kind='SUBSCRIPTION_UPDATE' and consent_purpose='CHANGE')))
 or (billing_schema='setup-v1' and billing_step_kind='SETUP_INTENT_CREATE' and billing_setup_reference is not null and billing_setup_reference ~ '^seti_[A-Za-z0-9]{1,250}$'
   and billing_step_reference=billing_setup_reference and num_nonnulls(billing_invoice_reference,billing_allocation_reference,billing_intent_reference)=0
   and ((consent_purpose='CREATE' and billing_subscription_reference is null) or (consent_purpose='SETUP' and billing_subscription_reference is not null and billing_subscription_reference ~ '^sub_[A-Za-z0-9]{1,251}$'))))));
create function protect_stripe_change_grant() returns trigger language plpgsql as $$
begin
 if old.billing_key_version is not null and new.billing_change_operation is distinct from old.billing_change_operation then
  raise exception 'CHANGE grant authority is immutable' using errcode='23514'; end if;
 return new;
end; $$;
create trigger stripe_change_grant_identity before update on subscription_action_capabilities for each row execute function protect_stripe_change_grant();
