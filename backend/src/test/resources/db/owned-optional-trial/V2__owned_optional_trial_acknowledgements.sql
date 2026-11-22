-- Reconstruct the original admission fingerprint from current account, catalog, clock and customer rows.
-- A read view over the existing receipts. Publication stays in the ordinary subscription ledger.
create view stripe_billing_optional_trial_acknowledgements as
select original.*, receipt.fingerprint, receipt.step_id, receipt.subscription_reference, receipt.item_reference,
       receipt.invoice_reference, receipt.trial_start
from (
 select c.run_id,c.id clock_intent,c.payment_account_id account_id,c.site_id,c.customer_id,
        s.id subscription_id,o.id create_operation,p.id price_id,c.external_account_id external_account,
        c.credentials_ref,r.reference clock_reference,c.name clock_name,b.provider_reference customer_reference,
        pm.provider_price_id price_reference,gm.provider_product_id product_reference,o.provider_operation_key operation_key,
        r.frozen_time,r.provider_created clock_created,r.provider_deletes_after clock_deletes_after,t.requested_end trial_end,
        b.id customer_binding,b.creation_operation_id customer_step,r.creation_operation_id clock_step,
        p.product_id,p.price_key,p.price_version,o.fingerprint create_fingerprint,o.expected_revision,o.policy_version
 from stripe_billing_clock_intents c
 join stripe_billing_clock_ready r on r.intent_id=c.id
 join payment_accounts a on a.id=c.payment_account_id
 join sites site on site.id=c.site_id and site.payment_account_id=a.id
 join customers cust on cust.site_id=site.id and cust.id=c.customer_id
 join site_subscription_policies policy on policy.site_id=site.id
 join stripe_billing_customers b on b.payment_account_id=a.id and b.site_id=site.id and b.customer_id=cust.id
 join provider_operations k on k.id=r.creation_operation_id and k.payment_account_id=a.id
 join provider_operations bc on bc.id=b.creation_operation_id and bc.payment_account_id=a.id
 join subscriptions s on s.payment_account_id=a.id and s.site_id=site.id and s.customer_id=cust.id
 join subscription_operations o on o.subscription_id=s.id and o.site_id=site.id and o.customer_id=cust.id
 join subscription_trial_eligibility e on e.site_id=site.id and e.customer_id=cust.id and e.product_id=s.product_id
 join stripe_subscription_trial_proofs t on t.operation_id=o.id and t.subscription_id=s.id and t.payment_account_id=a.id
 join prices p on p.id=s.billing_price_id and p.site_id=site.id and p.product_id=s.product_id
 join products product on product.id=p.product_id and product.site_id=site.id
 join provider_price_mappings pm on pm.price_id=p.id and pm.payment_account_id=a.id
 join provider_product_mappings gm on gm.product_id=product.id and gm.payment_account_id=a.id
 where c.stopped_at is null and c.api_version='2026-09-30.endive'
   and c.external_account_id='acct_1UPiMpELswAd8tpR' and c.credentials_ref='stripe-sandbox-default'
   and a.external_account_id=c.external_account_id and a.credentials_ref=c.credentials_ref
   and a.account_key='stripe-sandbox-default' and a.provider='STRIPE' and a.account_mode='SANDBOX' and a.account_status='ACTIVE'
   and site.status='ACTIVE' and site.site_key='s5a-'||replace(c.run_id::text,'-','') and cust.status='ACTIVE'
   and k.kind='TEST_CLOCK_CREATE' and k.status='ACKNOWLEDGED' and k.resource_id=c.id and k.provider_reference=r.reference
   and k.fingerprint=c.create_fingerprint and k.operation_key=c.create_key
   and k.external_account_id=c.external_account_id and k.credentials_ref=c.credentials_ref
   and bc.kind='CUSTOMER_CREATE' and bc.status='ACKNOWLEDGED' and bc.resource_id=c.customer_id
   and bc.provider_reference=b.provider_reference and bc.fingerprint=r.customer_fingerprint
   and bc.operation_key='billing_customer_'||a.id::text||'_'||site.id::text||'_'||cust.id::text
   and bc.external_account_id=c.external_account_id and bc.credentials_ref=c.credentials_ref
   and o.kind='CREATE' and o.authority_scope='customer:'||cust.id::text
   and policy.enabled and o.policy_version=policy.version
   and e.reserved_by_operation=o.id and t.requested_end=r.frozen_time+604800
   and p.status='PUBLISHED' and product.status='ACTIVE' and p.price_type='SUBSCRIPTION'
   and p.price_key=s.billing_price_key and p.price_version=1 and p.price_version=s.billing_price_version
   and p.amount_minor=2000 and s.billing_amount_minor=p.amount_minor and p.currency='EUR' and s.currency=p.currency
   and p.billing_interval='MONTH' and s.billing_interval=p.billing_interval and p.trial_days=7 and s.billing_trial_days=p.trial_days
   and p.trial_payment_method_policy='OPTIONAL_DURING_TRIAL' and s.billing_trial_policy=p.trial_payment_method_policy
   and p.tax_mode='INCLUSIVE' and s.billing_tax_mode=p.tax_mode
   and not exists(select 1 from subscriptions sibling where sibling.site_id=site.id
                  and sibling.customer_id=cust.id and sibling.id<>s.id)
) original
join stripe_billing_owned_optional_trial_creates receipt on receipt.clock_intent=original.clock_intent
 and receipt.subscription_id=original.subscription_id and receipt.create_operation=original.create_operation
 and receipt.status='ACKNOWLEDGED' and receipt.trial_start=original.frozen_time and receipt.trial_end=original.trial_end
 and receipt.fingerprint=encode(sha256(convert_to(to_jsonb(original)::text,'UTF8')),'hex')
join provider_operations step on step.id=receipt.step_id and step.kind='SUBSCRIPTION_CREATE'
 and step.resource_id=original.create_operation and step.payment_account_id=original.account_id
 and step.lease_owner is null and step.status='ACKNOWLEDGED' and step.provider_reference=receipt.subscription_reference
 and step.fingerprint=receipt.fingerprint and step.operation_key=original.operation_key
 and step.external_account_id=original.external_account and step.credentials_ref=original.credentials_ref
join stripe_subscription_trial_proofs proof on proof.operation_id=original.create_operation
 and (proof.step_id,proof.subscription_reference,proof.item_reference,proof.invoice_reference,proof.trial_start,proof.requested_end)=
     (receipt.step_id,receipt.subscription_reference,receipt.item_reference,receipt.invoice_reference,receipt.trial_start,receipt.trial_end);
