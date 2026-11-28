-- Synthetic rows use migrated column types. Transaction-local tables isolate these from application data.
do $tables$
declare relation text;
begin
  foreach relation in array array['sites','customers','payment_accounts','subscriptions','subscription_operations',
    'site_subscription_policies','stripe_subscription_cancellation_proofs','provider_operations','subscription_events',
    'payments','refunds','subscription_billing_records','subscription_provider_objects','payment_events','outbox_events',
    'outbox_deliveries','stripe_billing_customers','subscription_action_capabilities','subscription_confirmation_chains',
    'subscription_confirmation_attempts','provider_inbox','provider_product_mappings','provider_price_mappings']
  loop
    execute format('create temp table %I on commit drop as select * from public.%I with no data',relation,relation);
  end loop;
end
$tables$;
create function pg_temp.cid(int) returns uuid language sql immutable as $$
  select ('00000000-0000-4000-8000-'||lpad($1::text,12,'0'))::uuid
$$;
create temp view times as select date_trunc('second',transaction_timestamp())-interval '1 hour' start_at,
                                date_trunc('second',transaction_timestamp())+interval '30 days' end_at;
insert into sites(id,site_key,payment_account_id,status) values(pg_temp.cid(2),'s5a-'||replace(pg_temp.cid(99)::text,'-',''),pg_temp.cid(1),'ACTIVE');
insert into customers(id,site_id,status) values(pg_temp.cid(3),pg_temp.cid(2),'ACTIVE');
insert into payment_accounts(id,provider,account_mode,account_status,account_key,external_account_id,credentials_ref)
values(pg_temp.cid(1),'STRIPE','SANDBOX','ACTIVE','stripe-sandbox-default','acct_1UPiMpELswAd8tpR','stripe-sandbox-default');
insert into site_subscription_policies(site_id,version,enabled) values(pg_temp.cid(2),1,true);
insert into subscriptions(id,site_id,customer_id,product_id,payment_account_id,billing_status,provider_reference,currency,
  billing_price_id,billing_price_version,billing_amount_minor,billing_interval,billing_trial_days,billing_tax_mode,
  entitled_price_id,entitled_price_version,entitled_amount_minor,entitled_interval,entitled_trial_days,entitled_tax_mode,
  current_period_start,current_period_end,paid_through,revision,cancellation_effective_at)
select pg_temp.cid(4),pg_temp.cid(2),pg_temp.cid(3),pg_temp.cid(7),pg_temp.cid(1),'ACTIVE','sub_orig','EUR',
       pg_temp.cid(8),1,2000,'MONTH',0,'INCLUSIVE',pg_temp.cid(8),1,2000,'MONTH',0,'INCLUSIVE',start_at,end_at,end_at,3,null from times;
insert into provider_product_mappings(product_id,payment_account_id,provider_product_id) values(pg_temp.cid(7),pg_temp.cid(1),'prod_orig');
insert into provider_price_mappings(price_id,payment_account_id,provider_price_id) values(pg_temp.cid(8),pg_temp.cid(1),'price_orig');
insert into stripe_billing_customers(payment_account_id,site_id,customer_id,creation_operation_id,provider_reference)
values(pg_temp.cid(1),pg_temp.cid(2),pg_temp.cid(3),pg_temp.cid(30),'cus_orig');
insert into provider_operations(id,payment_account_id,resource_id,kind,operation_key,status,provider_reference,external_account_id,credentials_ref)
values(pg_temp.cid(30),pg_temp.cid(1),pg_temp.cid(3),'CUSTOMER_CREATE','initial-customer','ACKNOWLEDGED','cus_orig','acct_1UPiMpELswAd8tpR','stripe-sandbox-default'),
      (pg_temp.cid(31),pg_temp.cid(1),pg_temp.cid(5),'SUBSCRIPTION_CREATE','initial-create','ACKNOWLEDGED','sub_orig','acct_1UPiMpELswAd8tpR','stripe-sandbox-default');
insert into subscription_operations(id,site_id,customer_id,subscription_id,kind,status,key_digest,provider_operation_key,provider_reference)
values(pg_temp.cid(5),pg_temp.cid(2),pg_temp.cid(3),pg_temp.cid(4),'CREATE','SUCCEEDED',repeat('1',64),'initial-create','sub_orig');
insert into subscription_action_capabilities(id,operation_id,site_id,customer_id,subscription_id,payment_account_id,
  billing_step_id,billing_step_kind,billing_schema,billing_operation_key,billing_subscription_reference,billing_customer_reference,
  billing_invoice_reference,billing_allocation_reference,billing_intent_reference,recurring_consent,consent_purpose,token_digest)
values(pg_temp.cid(32),pg_temp.cid(5),pg_temp.cid(2),pg_temp.cid(3),pg_temp.cid(4),pg_temp.cid(1),
  pg_temp.cid(31),'SUBSCRIPTION_CREATE','payment-v1','initial-create','sub_orig','cus_orig','in_orig','inpay_orig','pi_orig',true,'CREATE',repeat('f',64));
insert into subscription_confirmation_chains(id,operation_id,root_digest,origin)
values(pg_temp.cid(32),pg_temp.cid(5),repeat('f',64),'http://127.0.0.1:18081');
insert into subscription_confirmation_attempts(id,chain_id,state,confirm_key,provider_key)
values(pg_temp.cid(6),pg_temp.cid(32),'OBSERVE',repeat('2',64),'billing-confirm-v2:'||pg_temp.cid(6));
insert into subscription_billing_records(id,site_id,subscription_id,product_id,payment_account_id,provider_invoice_reference,
  kind,settlement,amount_due_minor,currency,billed_price_id,billed_price_version,billed_amount_minor,billed_trial_days,
  billed_interval,billed_tax_mode,period_start,period_end)
select pg_temp.cid(11),pg_temp.cid(2),pg_temp.cid(4),pg_temp.cid(7),pg_temp.cid(1),'in_orig','INITIAL','CONFIRMED_CASH',
       2000,'EUR',pg_temp.cid(8),1,2000,0,'MONTH','INCLUSIVE',start_at,end_at from times;
insert into payments(id,site_id,customer_id,subscription_id,payment_account_id,billing_record_id,provider_payment_reference,
  source,status,amount_minor,currency,refunded_amount_minor,subscription_billing_kind,subscription_price_id,subscription_price_version,
  subscription_product_id,subscription_invoice_reference,subscription_period_start,subscription_period_end)
select pg_temp.cid(10),pg_temp.cid(2),pg_temp.cid(3),pg_temp.cid(4),pg_temp.cid(1),pg_temp.cid(11),'pi_orig',
       'SUBSCRIPTION','SUCCEEDED',2000,'EUR',0,'INITIAL',pg_temp.cid(8),1,pg_temp.cid(7),'in_orig',start_at,end_at from times;
insert into subscription_provider_objects(site_id,subscription_id,payment_account_id,provider_type,object_type,provider_reference,billing_record_id,payment_id)
values(pg_temp.cid(2),pg_temp.cid(4),pg_temp.cid(1),'STRIPE','PAYMENT','pi_orig',pg_temp.cid(11),pg_temp.cid(10)),
      (pg_temp.cid(2),pg_temp.cid(4),pg_temp.cid(1),'STRIPE','INVOICE','in_orig',pg_temp.cid(11),null);
insert into payment_events(id,site_id,payment_account_id,payment_id,event_type,amount_minor,currency)
values(pg_temp.cid(12),pg_temp.cid(2),pg_temp.cid(1),pg_temp.cid(10),'PAYMENT_SUCCEEDED',2000,'EUR');
insert into subscription_events(id,site_id,subscription_id,payment_account_id,provider_reference,event_type,revision,occurred_at,billing_record_id)
select pg_temp.cid(13),pg_temp.cid(2),pg_temp.cid(4),pg_temp.cid(1),'sub_orig','PERIOD_PAID',3,start_at,pg_temp.cid(11) from times;
create temp view callback_common as select jsonb_build_object('site_id',pg_temp.cid(2),'customer_id',pg_temp.cid(3),
  'subscription_id',pg_temp.cid(4),'product_id',pg_temp.cid(7)) payload;
insert into outbox_events(id,site_id,payment_account_id,source_kind,payment_id,callback_type,payload,status,endpoint_url)
select pg_temp.cid(12),pg_temp.cid(2),pg_temp.cid(1),'PAYMENT',pg_temp.cid(10),'payment.succeeded',
       c.payload||jsonb_build_object('event_id',pg_temp.cid(12),'event_type','payment.succeeded','schema_version',3,
         'payment_status','SUCCEEDED','payment_id',pg_temp.cid(10),'billing_record_id',pg_temp.cid(11),'amount_minor',2000,
         'currency','EUR','billing_kind','INITIAL','price_id',pg_temp.cid(8),'price_version',1,'occurred_at',start_at,
         'period_start',start_at,'period_end',end_at),'DELIVERED','http://callback-receiver:8080/callback'
from callback_common c cross join times;
insert into outbox_deliveries(outbox_event_id,status,http_status) values(pg_temp.cid(12),'SUCCEEDED',204);
create procedure pg_temp.deliver_lifecycle(event_num int,state text) language plpgsql as $$
begin
  insert into outbox_events(id,site_id,payment_account_id,source_kind,subscription_id,subscription_event_id,callback_type,payload,status,endpoint_url)
  select e.id,e.site_id,e.payment_account_id,'SUBSCRIPTION',e.subscription_id,e.id,'subscription.updated',c.payload||
    jsonb_build_object('event_id',e.id,'event_type','subscription.updated','schema_version',4,'revision',e.revision,
      'occurred_at',e.occurred_at,'state',state,'access_allowed',state<>'INCOMPLETE',
      'access_valid_until',case when state<>'INCOMPLETE' then e.occurred_at+interval '5 minutes' end,
      'billing_record_id',e.billing_record_id,'payment_id',e.payment_id),'DELIVERED','http://callback-receiver:8080/callback'
  from subscription_events e cross join callback_common c where e.id=pg_temp.cid(event_num);
  insert into outbox_deliveries(outbox_event_id,status,http_status) values(pg_temp.cid(event_num),'SUCCEEDED',204);
end
$$;
-- An additional legitimate pre-payment callback must survive both later phases byte for byte.
insert into subscription_events(id,site_id,subscription_id,payment_account_id,provider_reference,event_type,revision,occurred_at)
select pg_temp.cid(16),pg_temp.cid(2),pg_temp.cid(4),pg_temp.cid(1),'sub_orig','CREATED',2,start_at-interval '1 second' from times;
call pg_temp.deliver_lifecycle(16,'INCOMPLETE');
call pg_temp.deliver_lifecycle(13,'ACTIVE');
create procedure pg_temp.record_cancel_command(withdraw boolean) language plpgsql as $$
declare
  oid uuid:=pg_temp.cid(case when withdraw then 21 else 20 end);
  step uuid:=pg_temp.cid(case when withdraw then 41 else 40 end);
  event_num int:=case when withdraw then 15 else 14 end;
  rev int:=case when withdraw then 5 else 4 end;
  kind_name text:=case when withdraw then 'WITHDRAW_CANCEL' else 'CANCEL' end;
  mode_name text:=case when withdraw then 'WITHDRAW' else 'PERIOD_END' end;
  op_key text:='subscription:'||pg_temp.cid(1)||':'||oid||':'||kind_name;
  at_time timestamptz:=(select start_at from times)+rev*interval '5 minutes';
  boundary_at timestamptz:=(select end_at from times);
begin
  insert into subscription_operations(id,site_id,customer_id,subscription_id,kind,status,key_digest,provider_operation_key,provider_reference,
    expected_revision,authority_scope,policy_version,fingerprint,created_at)
  values(oid,pg_temp.cid(2),pg_temp.cid(3),pg_temp.cid(4),kind_name,'SUCCEEDED',repeat(case when withdraw then '4' else '3' end,64),
    op_key,'sub_orig',rev-1,'customer:'||pg_temp.cid(3),1,
    'v1.cancel.'||lower(mode_name)||'.'||extract(epoch from boundary_at)::bigint||'/0.'||repeat('a',64),at_time);
  insert into provider_operations(id,payment_account_id,resource_id,kind,operation_key,status,provider_reference,external_account_id,
    credentials_ref,first_dispatched_at,retry_before,recovery_attempts,manual_review_required,fingerprint)
  values(step,pg_temp.cid(1),oid,'SUBSCRIPTION_UPDATE',op_key,'ACKNOWLEDGED','sub_orig','acct_1UPiMpELswAd8tpR',
    'stripe-sandbox-default',at_time,at_time+interval '23 hours',0,false,repeat('a',64));
  insert into stripe_subscription_cancellation_proofs(operation_id,site_id,subscription_id,customer_id,payment_account_id,operation_kind,
    step_id,step_kind,subscription_reference,mode,boundary,acknowledged_at,prior_event,prior_event_revision)
  values(oid,pg_temp.cid(2),pg_temp.cid(4),pg_temp.cid(3),pg_temp.cid(1),kind_name,step,'SUBSCRIPTION_UPDATE','sub_orig',mode_name,
    boundary_at,at_time+interval '1 second',case when withdraw then pg_temp.cid(14) end,case when withdraw then 4 end);
  insert into subscription_events(id,site_id,subscription_id,payment_account_id,provider_reference,event_type,revision,occurred_at,fact_key)
  values(pg_temp.cid(event_num),pg_temp.cid(2),pg_temp.cid(4),pg_temp.cid(1),'sub_orig',case when withdraw then 'CANCEL_WITHDRAWN' else 'CANCEL_SCHEDULED' end,
    rev,at_time+interval '2 seconds','projection:'||rev);
  update subscriptions set revision=rev,cancellation_effective_at=case when not withdraw then boundary_at end where id=pg_temp.cid(4);
  call pg_temp.deliver_lifecycle(event_num,case when withdraw then 'ACTIVE' else 'CANCEL_AT_PERIOD_END' end);
end
$$;
update provider_operations set first_dispatched_at=(select start_at from times),recovery_attempts=0,
  manual_review_required=false,fingerprint=repeat('a',64);
update subscription_confirmation_attempts set claimed_at=(select start_at from times),token_digest=repeat('b',64),fingerprint=repeat('c',64);
insert into provider_inbox(payment_account_id,provider_type,event_family,event_type,provider_object_id,status,payload_hash)
values(pg_temp.cid(1),'STRIPE','SUBSCRIPTION','SUBSCRIPTION_INVOICE_CHANGED','in_orig','PROCESSED',repeat('a',64));
