-- Extend only the disposable schema-shaped fixtures created on this connection.
create temp table admin_users on commit drop as select id,status from public.admin_users with no data;
insert into admin_users values(pg_temp.cid(55),'ACTIVE');
delete from pg_temp.provider_inbox;
update pg_temp.subscription_confirmation_chains set subscription_revision=2;
update pg_temp.payment_events set received_at=transaction_timestamp();
update pg_temp.subscription_events set received_at=transaction_timestamp() where event_type='PERIOD_PAID';
update pg_temp.subscription_operations set authority_scope='customer:'||pg_temp.cid(3),fingerprint='v1.paid.'||repeat('a',64);
update pg_temp.provider_operations set operation_key='billing_customer_'||pg_temp.cid(1)||'_'||pg_temp.cid(2)||'_'||pg_temp.cid(3) where kind='CUSTOMER_CREATE';
update pg_temp.provider_operations set operation_key='subscription:'||pg_temp.cid(1)||':'||pg_temp.cid(5)||':CREATE' where kind='SUBSCRIPTION_CREATE';
update pg_temp.subscription_operations set provider_operation_key=(select operation_key from pg_temp.provider_operations where kind='SUBSCRIPTION_CREATE');
update pg_temp.subscription_action_capabilities set billing_operation_key=(select provider_operation_key from pg_temp.subscription_operations);
update pg_temp.payment_events set source='PROVIDER_EVENT',provider_object_id='pi_orig',request_id='cash_'||id,
 provider_event_id='cash:'||encode(sha256(convert_to('pi_orig'||chr(10)||'SUCCEEDED','UTF8')),'hex');
update pg_temp.subscription_action_capabilities set consumed_at=(select claimed_at from pg_temp.subscription_confirmation_attempts);
update pg_temp.subscription_events set source='RECONCILIATION',request_id='subscription-reconcile' where event_type='PERIOD_PAID';
update pg_temp.outbox_events set secret_version_id=pg_temp.cid(60);
update pg_temp.outbox_deliveries set id=gen_random_uuid(),attempt_number=1,delivery_mode='AUTOMATIC';
insert into pg_temp.subscription_operations(id,site_id,customer_id,subscription_id,kind,status,authority_scope,key_digest,fingerprint,provider_operation_key)
select pg_temp.cid(50),site_id,customer_id,subscription_id,'RECONCILE','SUCCEEDED','admin:'||pg_temp.cid(55),repeat('3',64),
 encode(sha256(convert_to('RECONCILE:'||subscription_id||':'||pg_temp.cid(1)||':'||customer_id||':null','UTF8')),'hex'),'reconcile:'||pg_temp.cid(50)
from pg_temp.subscription_operations where kind='CREATE';
update pg_temp.subscription_operations set expected_revision=2,created_at=transaction_timestamp()-interval '1 second',updated_at=transaction_timestamp() where kind='RECONCILE';

create procedure pg_temp.webhook_stage(stage text,expected_ready boolean,expected_pending boolean) language plpgsql as $$
declare r record; paid boolean:=stage in ('reconciled','paid','finalized');
begin
 execute format('execute invoice_webhook_proof(pg_temp.cid(1),pg_temp.cid(2),pg_temp.cid(3),pg_temp.cid(4),pg_temp.cid(5),pg_temp.cid(6),
    pg_temp.cid(7),pg_temp.cid(8),repeat(''1'',64),repeat(''2'',64),''http://127.0.0.1:18081'',''s5a-''||replace(pg_temp.cid(99)::text,''-'',''''),
    %L,%L,%L,%L,1700000300,%L,1700000100)',stage,case when paid then pg_temp.cid(50) end,case when paid then repeat('3',64) end,
    'evt_paid','evt_finalized') into strict r;
 if r.phase_proof is distinct from expected_ready or r.pending is distinct from expected_pending then
   raise exception 'Invoice webhook proof mismatch in % (original %, ready %, pending %)',stage,r.original,r.phase_proof,r.pending;
 end if;
 if expected_ready and (r.create_operation_id<>pg_temp.cid(5) or r.attempt_id<>pg_temp.cid(6)
     or r.invoice_reference<>'in_orig' or r.allocation_reference<>'inpay_orig' or r.intent_reference<>'pi_orig'
     or (paid and (r.reconcile_operation_id<>pg_temp.cid(50) or r.payment_event_id<>pg_temp.cid(12) or r.period_event_id<>pg_temp.cid(13)
         or jsonb_array_length(r.callbacks)<>3 or exists(select 1 from jsonb_array_elements(r.callbacks) cb where cb->>'deliveryId' is null)))
     or (not paid and r.payment_id is not null)
     or jsonb_array_length(r.inbox)<>case stage when 'paid' then 1 when 'finalized' then 2 else 0 end) then
   raise exception 'Invoice webhook proof references mismatch in %',stage;
 end if;
end $$;

create procedure pg_temp.add_original_invoice_event(paid boolean) language plpgsql as $$
declare instant timestamptz:=to_timestamp(case when paid then 1700000300 else 1700000100 end);
begin
 insert into pg_temp.provider_inbox(id,provider_type,payment_account_id,provider_event_id,provider_object_id,event_family,event_type,
   occurred_at,received_at,payload_hash,safe_payload,status,attempt_count)
 values(pg_temp.cid(case when paid then 51 else 52 end),'STRIPE',pg_temp.cid(1),case when paid then 'evt_paid' else 'evt_finalized' end,
   'in_orig','SUBSCRIPTION','SUBSCRIPTION_INVOICE_CHANGED',instant,transaction_timestamp()+case when paid then interval '0 seconds' else interval '1 second' end,
   encode(sha256(convert_to('SUBSCRIPTION_INVOICE_CHANGED'||chr(10)||'in_orig'||chr(10)||to_char(instant at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'),'UTF8')),'hex'),
   '{}','PROCESSED',1);
end $$;
