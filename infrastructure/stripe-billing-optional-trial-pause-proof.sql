-- Read-only local evidence at the optional trial pause, before any card setup or resume.
-- Pass the identity and original invoice and trial-start event from the accepted trial receipt.
-- The provider status and the absence of provider-side charges require a separate Stripe read.
with owned as (
  select $1::uuid site_id, $2::text site_key, $3::uuid customer_id, $4::uuid account_id,
         $5::uuid product_id, $6::uuid price_id, $7::uuid subscription_id, $8::uuid create_id,
         $9::uuid bill_id, $10::uuid started_id, $11::text subscription_reference,
         $12::text invoice_reference, $13::timestamptz trial_start, $14::timestamptz trial_end
)
select s.id subscription_id, bill.id billing_record_id, started.id trial_started_event_id,
       paused.id paused_event_id, s.revision, s.trial_end
from owned x
join payment_accounts account on account.id=x.account_id and account.provider='STRIPE'
     and account.account_mode='SANDBOX' and account.account_status='ACTIVE'
join sites site on site.id=x.site_id and site.site_key=x.site_key
     and site.payment_account_id=account.id and site.status='ACTIVE'
join customers customer on customer.id=x.customer_id and customer.site_id=site.id and customer.status='ACTIVE'
join subscriptions s on s.id=x.subscription_id and s.site_id=site.id and s.customer_id=customer.id
     and s.payment_account_id=account.id and s.product_id=x.product_id
     and s.provider_reference=x.subscription_reference
join subscription_operations creation on creation.id=x.create_id and creation.site_id=site.id
     and creation.customer_id=customer.id and creation.subscription_id=s.id
     and creation.kind='CREATE' and creation.status='SUCCEEDED' and creation.safe_failure_code is null
     and creation.authority_scope='customer:'||customer.id::text and creation.expected_revision=0
     and creation.provider_reference=s.provider_reference
join stripe_subscription_trial_proofs trial on trial.operation_id=creation.id and trial.site_id=site.id
     and trial.customer_id=customer.id and trial.subscription_id=s.id and trial.payment_account_id=account.id
     and trial.subscription_reference=s.provider_reference and trial.invoice_reference=x.invoice_reference
     and to_timestamp(trial.trial_start)=x.trial_start and to_timestamp(trial.requested_end)=x.trial_end
join provider_operations provider_create on provider_create.id=trial.step_id
     and provider_create.payment_account_id=account.id and provider_create.resource_id=creation.id
     and provider_create.external_account_id=account.external_account_id and provider_create.credentials_ref=account.credentials_ref
     and provider_create.kind='SUBSCRIPTION_CREATE' and provider_create.status='ACKNOWLEDGED'
     and provider_create.operation_key=creation.provider_operation_key and provider_create.provider_reference=s.provider_reference
     and provider_create.first_dispatched_at is not null and provider_create.safe_error_code is null
     and provider_create.recovery_attempts=0 and not provider_create.manual_review_required
join subscription_trial_eligibility eligible on eligible.site_id=site.id and eligible.customer_id=customer.id
     and eligible.product_id=s.product_id and eligible.reserved_by_operation=creation.id and eligible.consumed_at is not null
join subscription_billing_records bill on bill.id=x.bill_id and bill.site_id=site.id and bill.subscription_id=s.id
     and bill.payment_account_id=account.id and bill.product_id=s.product_id
     and bill.provider_invoice_reference=trial.invoice_reference and bill.billed_price_id=x.price_id
     and bill.period_start=x.trial_start and bill.period_end=x.trial_end
     and bill.kind='INITIAL' and bill.settlement='ZERO_AMOUNT' and bill.amount_due_minor=0 and bill.currency=s.currency
join subscription_events started on started.id=x.started_id and started.site_id=site.id
     and started.subscription_id=s.id and started.payment_account_id=account.id
     and started.event_type='TRIAL_STARTED' and started.payment_id is null
     and (started.billing_record_id is null or started.billing_record_id=bill.id)
     and started.occurred_at>=x.trial_start and started.occurred_at<x.trial_end
     and started.received_at=eligible.consumed_at
join subscription_events paused on paused.site_id=site.id and paused.subscription_id=s.id
     and paused.payment_account_id=account.id and paused.event_type='PAUSED' and paused.revision=s.revision
     and paused.sequence_number=started.sequence_number+1 and paused.revision>started.revision
     and paused.occurred_at>=x.trial_end and paused.payment_id is null and paused.billing_record_id is null
where s.billing_status='PAUSED' and s.billing_trial_policy='OPTIONAL_DURING_TRIAL' and s.billing_trial_days=7
  and s.billing_price_id=x.price_id and s.entitled_price_id=x.price_id and s.currency='EUR'
  and s.trial_start=x.trial_start and s.trial_end=x.trial_end
  and s.current_period_start=x.trial_start and s.current_period_end=x.trial_end
  and s.paid_through is null and s.debt_billing_record_id is null and s.grace_deadline is null
  and s.pending_operation_id is null and s.cancellation_effective_at is null and s.terminal_reason is null
  and (select count(*) from subscription_billing_records b
       where b.site_id=site.id and b.subscription_id=s.id and b.payment_account_id=account.id)=1
  and (select count(*) from subscription_events e
       where e.site_id=site.id and e.subscription_id=s.id and e.payment_account_id=account.id)=2
  and not exists (
    select 1 from subscription_operations other where other.site_id=site.id and other.subscription_id=s.id
      and other.id<>creation.id and (other.kind<>'RECONCILE' or other.status<>'SUCCEEDED')
  )
  and not exists (select 1 from payments cash where cash.site_id=site.id)
  and not exists (select 1 from payment_events cash_event where cash_event.site_id=site.id)
  and not exists (select 1 from outbox_events cash_callback
                  where cash_callback.site_id=site.id and cash_callback.callback_type='payment.succeeded')
  and not exists (select 1 from stripe_subscription_setup_proofs setup
                  where setup.site_id=site.id and setup.subscription_id=s.id and setup.payment_account_id=account.id)
  and exists (select 1 from subscription_provider_objects mapped
              where mapped.site_id=site.id and mapped.subscription_id=s.id and mapped.payment_account_id=account.id
                and mapped.provider_type='STRIPE' and mapped.object_type='SUBSCRIPTION'
                and mapped.provider_reference=s.provider_reference and mapped.billing_record_id is null)
  and exists (select 1 from subscription_provider_objects mapped
              where mapped.site_id=site.id and mapped.subscription_id=s.id and mapped.payment_account_id=account.id
                and mapped.provider_type='STRIPE' and mapped.object_type='INVOICE'
                and mapped.provider_reference=bill.provider_invoice_reference and mapped.billing_record_id=bill.id)
  and (select count(*) from subscription_provider_objects mapped
       where mapped.site_id=site.id and mapped.subscription_id=s.id and mapped.payment_account_id=account.id)=2;
