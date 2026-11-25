-- Read-only, after the paired Portal 201 and the independently proved initial €20 payment.
-- Pass the captured expiry as UTC and the initial proof's operation, billing, payment, price, revision and period.
-- One true row proves local publication and unchanged cash. Provider navigation and callbacks are separate evidence.
with owned as (
  select $1::uuid account_id, $2::uuid site_id, $3::uuid customer_id, $4::uuid subscription_id,
         $5::uuid create_id, $6::uuid billing_record_id, $7::uuid payment_id, $8::uuid price_id,
         $9::bigint policy_version, $10::text portal_key_digest, $11::text return_url, $12::timestamptz expires_at,
         $13::bigint initial_revision, $14::timestamptz period_start, $15::timestamptz period_end
), acknowledged_dispatch as (
  select d.id,d.kind,d.resource_id,d.operation_key,d.fingerprint,d.provider_reference,d.first_dispatched_at
  from owned x
  join payment_accounts a on a.id=x.account_id
  join provider_operations d on d.payment_account_id=a.id
       and d.external_account_id=a.external_account_id and d.credentials_ref=a.credentials_ref
  where d.kind in ('CUSTOMER_CREATE','SUBSCRIPTION_CREATE','PORTAL_CONFIGURATION_CREATE','PORTAL_SESSION_CREATE')
       and d.status='ACKNOWLEDGED' and d.first_dispatched_at is not null and d.retry_before>d.first_dispatched_at
       and d.recovery_attempts=0 and not d.manual_review_required and d.safe_error_code is null
       and d.fingerprint ~ '^[a-f0-9]{64}$'
)
select true as portal_verified
from owned x
join payment_accounts a on a.id=x.account_id and a.provider='STRIPE' and a.account_mode='SANDBOX'
     and a.account_status='ACTIVE' and a.account_key='stripe-sandbox-default'
     and a.external_account_id='acct_1UPiMpELswAd8tpR' and a.credentials_ref='stripe-sandbox-default'
join sites site on site.id=x.site_id and site.payment_account_id=a.id and site.status='ACTIVE'
join customers c on c.id=x.customer_id and c.site_id=site.id and c.status='ACTIVE'
join stripe_billing_customers bc on bc.payment_account_id=a.id and bc.site_id=site.id and bc.customer_id=c.id
join acknowledged_dispatch customer_step on customer_step.id=bc.creation_operation_id and customer_step.kind='CUSTOMER_CREATE'
     and customer_step.resource_id=c.id and customer_step.provider_reference=bc.provider_reference
join site_subscription_policies policy on policy.site_id=site.id and policy.version=x.policy_version
     and policy.enabled and policy.portal_enabled and not policy.allow_immediate_cancellation
     and policy.portal_return_urls=array[x.return_url]
join subscriptions s on s.id=x.subscription_id and s.site_id=site.id and s.customer_id=c.id and s.payment_account_id=a.id
join subscription_operations original on original.id=x.create_id and original.site_id=site.id
     and original.customer_id=c.id and original.subscription_id=s.id and original.kind='CREATE' and original.status='SUCCEEDED'
     and original.provider_reference=s.provider_reference
join acknowledged_dispatch original_step on original_step.resource_id=original.id and original_step.kind='SUBSCRIPTION_CREATE'
     and original_step.operation_key=original.provider_operation_key and original_step.provider_reference=s.provider_reference
join subscription_operations o on o.site_id=site.id and o.customer_id=c.id and o.subscription_id=s.id
     and o.kind='PORTAL' and o.status='SUCCEEDED' and o.safe_failure_code is null and o.policy_version=policy.version
     and o.key_digest=x.portal_key_digest and o.authority_scope='customer:'||c.id::text and o.expected_revision=s.revision
     and o.provider_reference=s.provider_reference and o.provider_operation_key='subscription:'||a.id::text||':'||o.id::text||':PORTAL'
join stripe_portal_configuration_intents ci on ci.payment_account_id=a.id and ci.site_id=site.id
     and ci.policy_version=policy.version and ci.owner_operation=o.id and ci.owner_subscription=s.id and ci.operation_kind=o.kind
join stripe_portal_configurations cfg on cfg.intent_id=ci.id and cfg.payment_account_id=a.id and cfg.site_id=site.id
     and cfg.policy_version=policy.version and cfg.policy_digest=ci.policy_digest
join acknowledged_dispatch cfg_step on cfg_step.id=cfg.creation_step and cfg_step.resource_id=ci.id
     and cfg_step.kind=cfg.step_kind and cfg_step.kind='PORTAL_CONFIGURATION_CREATE'
     and cfg_step.operation_key=ci.operation_key and cfg_step.operation_key='portal_config_'||ci.id::text
     and cfg_step.fingerprint=ci.fingerprint and cfg_step.provider_reference=cfg.configuration_reference
join stripe_portal_session_intents si on si.operation_id=o.id and si.payment_account_id=a.id and si.site_id=site.id
     and si.customer_id=c.id and si.subscription_id=s.id and si.operation_kind=o.kind
     and si.configuration_id=cfg.intent_id and si.configuration_reference=cfg.configuration_reference
     and si.descriptor->>'return_url'=x.return_url and si.descriptor#>>'{account,id,value}'=a.id::text
     and si.descriptor#>>'{customer,account_id,value}'=a.id::text and si.descriptor#>>'{customer,site_id,value}'=site.id::text
     and si.descriptor#>>'{customer,customer_id,value}'=c.id::text
     and si.descriptor#>>'{customer,provider_reference}'=bc.provider_reference
     and si.descriptor#>>'{customer,creation_operation_id}'=customer_step.id::text
     and si.descriptor#>>'{operation,id}'=o.id::text and si.descriptor#>>'{operation,fingerprint}'=o.fingerprint
     and si.descriptor#>>'{operation,site_id,value}'=site.id::text and si.descriptor#>>'{operation,customer_id,value}'=c.id::text
     and si.descriptor#>>'{operation,subscription_id}'=s.id::text
     and si.descriptor#>'{operation,expected_revision}'=to_jsonb(o.expected_revision)
     and si.descriptor#>'{operation,policy_version}'=to_jsonb(o.policy_version)
     and si.descriptor#>>'{policy,site_id,value}'=site.id::text and si.descriptor#>'{policy,version}'=to_jsonb(policy.version)
     and jsonb_typeof(si.descriptor->'members')='array' and si.descriptor->'members'->1 is null
     and si.descriptor#>>'{members,0,id}'=s.id::text and si.descriptor#>'{members,0,revision}'=to_jsonb(s.revision)
     and si.descriptor#>>'{members,0,provider_reference}'=s.provider_reference
     and si.descriptor#>>'{members,0,payment_account_id,value}'=a.id::text
     and si.descriptor#>>'{members,0,site_id,value}'=site.id::text and si.descriptor#>>'{members,0,customer_id,value}'=c.id::text
join stripe_portal_session_receipts r on r.operation_id=o.id and r.payment_account_id=a.id
     and r.configuration_id=si.configuration_id and r.configuration_reference=si.configuration_reference
join acknowledged_dispatch sess_step on sess_step.id=r.creation_step and sess_step.resource_id=o.id
     and sess_step.kind=r.step_kind and sess_step.kind='PORTAL_SESSION_CREATE'
     and sess_step.operation_key=o.provider_operation_key||':SESSION'
     and sess_step.fingerprint=si.fingerprint and sess_step.provider_reference=r.session_reference
join subscription_portal_sessions published on published.public_operation_id=o.id and published.site_id=site.id
     and published.customer_id=c.id and published.subscription_id=s.id and published.expires_at=r.expires_at
join subscription_billing_records b on b.id=x.billing_record_id and b.site_id=site.id
     and b.subscription_id=s.id and b.payment_account_id=a.id and b.product_id=s.product_id
join payments p on p.id=x.payment_id and p.billing_record_id=b.id and p.site_id=site.id
     and p.customer_id=c.id and p.subscription_id=s.id and p.payment_account_id=a.id
where x.portal_key_digest ~ '^[a-f0-9]{64}$' and o.fingerprint ~ '^[a-f0-9]{64}$'
  and cfg.configuration_reference ~ '^bpc_[A-Za-z0-9]{1,251}$' and r.session_reference ~ '^bps_[A-Za-z0-9]{1,251}$'
  and r.expires_at=x.expires_at and cfg_step.first_dispatched_at>=o.created_at
  and sess_step.first_dispatched_at>=cfg_step.first_dispatched_at
  and o.updated_at>=sess_step.first_dispatched_at and o.updated_at<r.expires_at
  and s.billing_status='ACTIVE' and s.revision=x.initial_revision and s.currency='EUR'
  and s.billing_price_id=x.price_id and s.entitled_price_id=x.price_id
  and s.billing_price_version=1 and s.entitled_price_version=1
  and s.billing_amount_minor=2000 and s.entitled_amount_minor=2000
  and s.current_period_start=x.period_start and s.current_period_end=x.period_end and s.paid_through=x.period_end
  and statement_timestamp()>=x.period_start and statement_timestamp()<x.period_end
  and s.cancellation_effective_at is null and s.debt_billing_record_id is null and s.pending_operation_id is null
  and b.kind='INITIAL' and b.settlement='CONFIRMED_CASH' and b.amount_due_minor=2000 and b.currency='EUR'
  and b.billed_price_id=x.price_id and b.billed_price_version=1 and b.period_start=x.period_start and b.period_end=x.period_end
  and p.source='SUBSCRIPTION' and p.status='SUCCEEDED' and p.amount_minor=2000 and p.currency='EUR'
  and p.refunded_amount_minor=0 and p.subscription_billing_kind='INITIAL'
  and p.subscription_price_id=x.price_id and p.subscription_price_version=1
  and p.subscription_invoice_reference=b.provider_invoice_reference
  and p.subscription_period_start=x.period_start and p.subscription_period_end=x.period_end
  and not exists(select 1 from refunds refund where refund.payment_id=p.id)
  and (select count(*)=1 from subscriptions extra where extra.site_id=site.id and extra.customer_id=c.id)
  and (select count(*)=1 from subscription_billing_records extra where extra.site_id=site.id and extra.subscription_id=s.id)
  and (select count(*)=1 from payments extra where extra.site_id=site.id and extra.customer_id=c.id)
  and not exists(select 1 from subscription_operations extra where extra.site_id=site.id and extra.customer_id=c.id
        and extra.id not in (o.id,original.id))
  and not exists(select 1 from provider_operations extra where extra.payment_account_id=a.id
        and extra.id not in (customer_step.id,original_step.id,cfg_step.id,sess_step.id)
        and (extra.status='ACKNOWLEDGED' and
          (extra.kind='PRODUCT_CREATE' and extra.resource_id=s.product_id
           or extra.kind='PRICE_CREATE' and extra.resource_id=x.price_id)) is not true)
