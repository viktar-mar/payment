-- Read-only evidence for the same paused trial after explicit resumption.
-- Pass the original trial receipt, the saved setup and resume operation, and the new bill's ID from the pending checkpoint.
-- For PENDING pass NULL as the PaymentIntent. For PAID pass the independently verified PaymentIntent for that bill.
with owned as (
  select $1::uuid site_id, $2::text site_key, $3::uuid customer_id, $4::uuid account_id,
         $5::uuid product_id, $6::uuid price_id, $7::uuid subscription_id, $8::uuid create_id,
         $9::uuid trial_bill_id, $10::uuid started_id, $11::text subscription_reference,
         $12::text trial_invoice_reference, $13::timestamptz trial_start, $14::timestamptz trial_end,
         $15::uuid paused_id, $16::uuid setup_id, $17::uuid resume_id, $18::text resume_invoice_reference,
         $19::timestamptz resume_start, $20::timestamptz resume_end, $21::uuid resume_bill_id,
         $22::text payment_reference, $23::text phase
), resumed as (
  select x.*, s.revision, s.billing_status, s.current_period_start, s.current_period_end, s.paid_through,
         resumed_bill.settlement, resume.status as operation_status, paused.sequence_number as paused_sequence,
         account.external_account_id, card.setup_reference, card.method_reference, original.item_reference
  from owned x
  join payment_accounts account on account.id=x.account_id and account.provider='STRIPE'
       and account.account_mode='SANDBOX' and account.account_status='ACTIVE'
  join sites site on site.id=x.site_id and site.site_key=x.site_key
       and site.payment_account_id=account.id and site.status='ACTIVE'
  join customers customer on customer.id=x.customer_id and customer.site_id=site.id and customer.status='ACTIVE'
  join subscriptions s on s.id=x.subscription_id and s.site_id=site.id and s.customer_id=customer.id
       and s.payment_account_id=account.id and s.product_id=x.product_id and s.provider_reference=x.subscription_reference
  join subscription_operations creation on creation.id=x.create_id and creation.site_id=site.id
       and creation.customer_id=customer.id and creation.subscription_id=s.id and creation.kind='CREATE'
       and creation.provider_reference=s.provider_reference and creation.status='SUCCEEDED'
  join stripe_subscription_trial_proofs original on original.operation_id=creation.id and original.site_id=site.id
       and original.customer_id=customer.id and original.subscription_id=s.id and original.payment_account_id=account.id
       and original.subscription_reference=s.provider_reference and original.invoice_reference=x.trial_invoice_reference
       and to_timestamp(original.trial_start)=x.trial_start and to_timestamp(original.requested_end)=x.trial_end
  join subscription_billing_records trial_bill on trial_bill.id=x.trial_bill_id and trial_bill.site_id=site.id
       and trial_bill.subscription_id=s.id and trial_bill.payment_account_id=account.id and trial_bill.product_id=s.product_id
       and trial_bill.provider_invoice_reference=original.invoice_reference and trial_bill.billed_price_id=x.price_id
       and trial_bill.kind='INITIAL' and trial_bill.settlement='ZERO_AMOUNT' and trial_bill.amount_due_minor=0
       and trial_bill.currency='EUR' and trial_bill.period_start=x.trial_start and trial_bill.period_end=x.trial_end
  join subscription_events started on started.id=x.started_id and started.site_id=site.id
       and started.subscription_id=s.id and started.payment_account_id=account.id
       and started.event_type='TRIAL_STARTED' and started.payment_id is null
       and (started.billing_record_id is null or started.billing_record_id=trial_bill.id)
       and started.occurred_at>=x.trial_start and started.occurred_at<x.trial_end
  join subscription_events paused on paused.id=x.paused_id and paused.site_id=site.id
       and paused.subscription_id=s.id and paused.payment_account_id=account.id
       and paused.event_type='PAUSED' and paused.sequence_number=started.sequence_number+1
       and paused.occurred_at>=x.trial_end and paused.occurred_at<=x.resume_start
       and paused.payment_id is null and paused.billing_record_id is null
  join subscription_operations setup on setup.id=x.setup_id and setup.site_id=site.id
       and setup.customer_id=customer.id and setup.subscription_id=s.id and setup.kind='SETUP'
       and setup.status='SUCCEEDED' and setup.safe_failure_code is null
       and setup.created_at>=paused.received_at
  join stripe_subscription_setup_proofs card on card.operation_id=setup.id and card.site_id=site.id
       and card.customer_id=customer.id and card.subscription_id=s.id and card.payment_account_id=account.id
       and card.operation_kind='SETUP' and card.method_reference is not null
  join provider_operations setup_intent on setup_intent.id=card.step_id
       and setup_intent.payment_account_id=account.id and setup_intent.resource_id=setup.id
       and setup_intent.external_account_id=account.external_account_id and setup_intent.credentials_ref=account.credentials_ref
       and setup_intent.kind='SETUP_INTENT_CREATE' and setup_intent.operation_key=setup.provider_operation_key||':SETUP'
       and setup_intent.provider_reference=card.setup_reference and setup_intent.status='ACKNOWLEDGED'
       and setup_intent.first_dispatched_at is not null and setup_intent.safe_error_code is null
       and setup_intent.recovery_attempts=0 and not setup_intent.manual_review_required
  join provider_operations method on method.payment_account_id=account.id and method.resource_id=setup.id
       and method.external_account_id=account.external_account_id and method.credentials_ref=account.credentials_ref
       and method.kind='SUBSCRIPTION_UPDATE' and method.operation_key=setup.provider_operation_key||':METHOD'
       and method.provider_reference=s.provider_reference and method.status='ACKNOWLEDGED'
       and method.first_dispatched_at>=setup_intent.first_dispatched_at and method.safe_error_code is null
       and method.recovery_attempts=0 and not method.manual_review_required
  join subscription_operations resume on resume.id=x.resume_id and resume.site_id=site.id
       and resume.customer_id=customer.id and resume.subscription_id=s.id and resume.kind='RESUME'
       and resume.authority_scope='customer:'||customer.id::text and resume.fingerprint ~ '^v1[.]resume[.]none[.][a-f0-9]{64}$'
       and resume.provider_reference=s.provider_reference and resume.safe_failure_code is null
       and resume.created_at>=setup.updated_at and resume.expected_revision>=paused.revision
  join stripe_subscription_resume_proofs original_resume on original_resume.operation_id=resume.id
       and original_resume.site_id=site.id and original_resume.customer_id=customer.id
       and original_resume.subscription_id=s.id and original_resume.payment_account_id=account.id
       and original_resume.subscription_reference=s.provider_reference
       and original_resume.invoice_reference=x.resume_invoice_reference
       and to_timestamp(original_resume.period_start)=x.resume_start and to_timestamp(original_resume.period_end)=x.resume_end
  join provider_operations dispatch on dispatch.id=original_resume.step_id
       and dispatch.payment_account_id=account.id and dispatch.resource_id=resume.id
       and dispatch.external_account_id=account.external_account_id and dispatch.credentials_ref=account.credentials_ref
       and dispatch.kind='SUBSCRIPTION_UPDATE' and dispatch.operation_key=resume.provider_operation_key
       and dispatch.provider_reference=s.provider_reference and dispatch.status='ACKNOWLEDGED'
       and dispatch.first_dispatched_at>=method.first_dispatched_at and dispatch.safe_error_code is null
       and dispatch.recovery_attempts=0 and not dispatch.manual_review_required
  join subscription_billing_records resumed_bill on resumed_bill.id=x.resume_bill_id and resumed_bill.site_id=site.id
       and resumed_bill.subscription_id=s.id and resumed_bill.payment_account_id=account.id and resumed_bill.product_id=s.product_id
       and resumed_bill.provider_invoice_reference=original_resume.invoice_reference and resumed_bill.billed_price_id=x.price_id
       and resumed_bill.kind='RENEWAL' and resumed_bill.currency='EUR' and resumed_bill.amount_due_minor=2000
       and resumed_bill.period_start=x.resume_start and resumed_bill.period_end=x.resume_end
  where s.trial_start=x.trial_start and s.trial_end=x.trial_end and s.billing_trial_days=7
    and s.billing_trial_policy='OPTIONAL_DURING_TRIAL' and s.currency='EUR'
    and s.billing_price_id=x.price_id and s.entitled_price_id=x.price_id
    and s.debt_billing_record_id is null and s.grace_deadline is null and s.pending_operation_id is null
    and s.cancellation_effective_at is null and s.terminal_reason is null
    and resumed_bill.id<>trial_bill.id and x.resume_start>=x.trial_end
    and (select count(*) from subscription_billing_records bill
         where bill.site_id=site.id and bill.subscription_id=s.id and bill.payment_account_id=account.id)=2
    and not exists (
      select 1 from subscription_operations other where other.site_id=site.id and other.subscription_id=s.id
        and other.id not in (creation.id,setup.id,resume.id)
        and (other.kind<>'RECONCILE' or other.status<>'SUCCEEDED')
    )
    and (select count(*) from subscription_provider_objects mapped
         where mapped.site_id=site.id and mapped.subscription_id=s.id and mapped.payment_account_id=account.id
           and mapped.provider_type='STRIPE' and mapped.payment_id is null
           and ((mapped.object_type='SUBSCRIPTION' and mapped.provider_reference=s.provider_reference and mapped.billing_record_id is null)
             or (mapped.object_type='INVOICE' and mapped.provider_reference=trial_bill.provider_invoice_reference and mapped.billing_record_id=trial_bill.id)
             or (mapped.object_type='INVOICE' and mapped.provider_reference=resumed_bill.provider_invoice_reference and mapped.billing_record_id=resumed_bill.id)))=3
    and (select count(*) from subscription_provider_objects mapped
         where mapped.site_id=site.id and mapped.subscription_id=s.id and mapped.payment_account_id=account.id)
        =case when x.phase='PAID' then 4 else 3 end
)
select x.subscription_id, x.trial_bill_id, x.resume_bill_id,
       payment.id payment_id, cash_event.id cash_event_id, period_event.id period_event_id,
       x.external_account_id, x.setup_reference, x.method_reference, x.item_reference, cash_event.occurred_at cash_occurred_at
from resumed x
left join payments payment on payment.site_id=x.site_id and payment.payment_account_id=x.account_id
     and payment.customer_id=x.customer_id and payment.subscription_id=x.subscription_id and payment.billing_record_id=x.resume_bill_id
     and payment.provider_payment_reference=x.payment_reference and payment.subscription_invoice_reference=x.resume_invoice_reference
     and payment.status='SUCCEEDED' and payment.source='SUBSCRIPTION' and payment.currency='EUR' and payment.amount_minor=2000
     and payment.refunded_amount_minor=0
left join payment_events cash_event on cash_event.site_id=x.site_id and cash_event.payment_account_id=x.account_id
     and cash_event.payment_id=payment.id and cash_event.event_type='PAYMENT_SUCCEEDED'
     and cash_event.amount_minor=payment.amount_minor and cash_event.currency=payment.currency
left join subscription_events period_event on period_event.site_id=x.site_id and period_event.payment_account_id=x.account_id
     and period_event.subscription_id=x.subscription_id and period_event.billing_record_id=x.resume_bill_id
     and period_event.event_type='PERIOD_PAID' and period_event.revision=x.revision and period_event.sequence_number>x.paused_sequence
     and period_event.occurred_at>=x.resume_start and period_event.occurred_at<x.resume_end
where (
  x.phase='PENDING' and x.payment_reference is null and x.billing_status='PAUSED'
  and x.operation_status='PROCESSING' and x.settlement='OPEN' and x.paid_through is null
  and x.current_period_start=x.trial_start and x.current_period_end=x.trial_end
  and not exists (select 1 from payments cash where cash.site_id=x.site_id)
  and not exists (select 1 from payment_events cash where cash.site_id=x.site_id)
  and not exists (select 1 from outbox_events callback where callback.site_id=x.site_id and callback.callback_type='payment.succeeded')
  and not exists (select 1 from subscription_events event where event.site_id=x.site_id
                  and event.subscription_id=x.subscription_id and event.event_type='PERIOD_PAID')
) or (
  x.phase='PAID' and x.payment_reference ~ '^pi_[A-Za-z0-9]{1,252}$'
  and x.billing_status='ACTIVE' and x.operation_status='SUCCEEDED' and x.settlement='CONFIRMED_CASH'
  and x.paid_through=x.resume_end and x.current_period_start=x.resume_start and x.current_period_end=x.resume_end
  and payment.id is not null and cash_event.id is not null and period_event.id is not null
  and exists (select 1 from subscription_provider_objects mapped
              where mapped.site_id=x.site_id and mapped.subscription_id=x.subscription_id and mapped.payment_account_id=x.account_id
                and mapped.provider_type='STRIPE' and mapped.object_type='PAYMENT'
                and mapped.provider_reference=x.payment_reference and mapped.billing_record_id=x.resume_bill_id
                and mapped.payment_id=payment.id)
  and (select count(*) from payments cash where cash.site_id=x.site_id)=1
  and (select count(*) from payment_events cash where cash.site_id=x.site_id and cash.event_type='PAYMENT_SUCCEEDED')=1
  and (select count(*) from subscription_events event where event.site_id=x.site_id and event.subscription_id=x.subscription_id
       and event.event_type='PERIOD_PAID')=1
  and exists (select 1 from outbox_events callback where callback.site_id=x.site_id and callback.payment_account_id=x.account_id
              and callback.id=cash_event.id and callback.payment_id=payment.id and callback.callback_type='payment.succeeded')
  and (select count(*) from outbox_events callback where callback.site_id=x.site_id and callback.callback_type='payment.succeeded')=1
  and exists (select 1 from outbox_events callback where callback.site_id=x.site_id and callback.payment_account_id=x.account_id
              and callback.id=period_event.id and callback.subscription_id=x.subscription_id
              and callback.subscription_event_id=period_event.id and callback.callback_type='subscription.updated')
);
