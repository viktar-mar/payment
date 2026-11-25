-- Layer Portal-only evidence over the existing migrated, transaction-local initial-payment fixture.
do $tables$
declare relation text;
begin
  foreach relation in array array['stripe_portal_configuration_intents','stripe_portal_configurations',
    'stripe_portal_session_intents','stripe_portal_session_receipts','subscription_portal_sessions']
  loop
    execute format('create temp table %I on commit drop as select * from public.%I with no data',relation,relation);
  end loop;
end
$tables$;

create temp view portal_input as
select pg_temp.cid(55) operation_id, start_at+interval '2 minutes' opened_at,
     start_at+interval '2 minutes 2 seconds' configuration_started_at,
     start_at+interval '2 minutes 4 seconds' session_started_at,
     start_at+interval '2 minutes 5 seconds' published_at,
     start_at+interval '7 minutes 5 seconds' expires_at,
     'http://127.0.0.1:18081/?site_key=s5a-fixture&locale=en'::text return_url,
     'subscription:'||pg_temp.cid(1)||':'||pg_temp.cid(55)||':PORTAL' provider_key
from times;

update site_subscription_policies set portal_enabled=true,allow_immediate_cancellation=false,defer_upgrades=false,
  portal_return_urls=array[(select return_url from portal_input)];
insert into subscription_operations(id,site_id,customer_id,subscription_id,kind,status,authority_scope,key_digest,fingerprint,
  provider_operation_key,expected_revision,policy_version,provider_reference,created_at,updated_at)
select operation_id,pg_temp.cid(2),pg_temp.cid(3),pg_temp.cid(4),'PORTAL','SUCCEEDED','customer:'||pg_temp.cid(3),
  repeat('3',64),repeat('4',64),provider_key,3,1,'sub_orig',opened_at,published_at from portal_input;

insert into stripe_portal_configuration_intents(id,payment_account_id,site_id,policy_version,policy_digest,owner_operation,
  owner_subscription,operation_kind,operation_key,fingerprint)
select pg_temp.cid(56),pg_temp.cid(1),pg_temp.cid(2),1,repeat('5',64),operation_id,pg_temp.cid(4),'PORTAL',
  'portal_config_'||pg_temp.cid(56),repeat('6',64) from portal_input;

insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,
  fingerprint,status,provider_reference,first_dispatched_at,retry_before,recovery_attempts,manual_review_required)
select pg_temp.cid(57),pg_temp.cid(1),'acct_1UPiMpELswAd8tpR','stripe-sandbox-default','PORTAL_CONFIGURATION_CREATE',
  pg_temp.cid(56),'portal_config_'||pg_temp.cid(56),repeat('6',64),'ACKNOWLEDGED','bpc_Synthetic',
  configuration_started_at,configuration_started_at+interval '23 hours',0,false from portal_input
union all
select pg_temp.cid(58),pg_temp.cid(1),'acct_1UPiMpELswAd8tpR','stripe-sandbox-default','PORTAL_SESSION_CREATE',
  operation_id,provider_key||':SESSION',repeat('7',64),'ACKNOWLEDGED','bps_Synthetic',
  session_started_at,session_started_at+interval '23 hours',0,false from portal_input;

insert into stripe_portal_configurations(intent_id,payment_account_id,site_id,policy_version,policy_digest,
  configuration_reference,creation_step,step_kind)
values(pg_temp.cid(56),pg_temp.cid(1),pg_temp.cid(2),1,repeat('5',64),'bpc_Synthetic',pg_temp.cid(57),'PORTAL_CONFIGURATION_CREATE');

insert into stripe_portal_session_intents(operation_id,site_id,customer_id,subscription_id,payment_account_id,
  operation_kind,configuration_id,configuration_reference,fingerprint,descriptor)
-- The test serializes the production Scope record into descriptor before executing the proof.
select operation_id,pg_temp.cid(2),pg_temp.cid(3),pg_temp.cid(4),pg_temp.cid(1),'PORTAL',pg_temp.cid(56),
  'bpc_Synthetic',repeat('7',64),'{}'::jsonb from portal_input;

-- The original shared fixture leaves retry windows implicit. Both recorded initial steps had a native ACK.
update provider_operations set retry_before=first_dispatched_at+interval '23 hours'
where kind in ('CUSTOMER_CREATE','SUBSCRIPTION_CREATE');

insert into stripe_portal_session_receipts(operation_id,payment_account_id,configuration_id,configuration_reference,
  session_reference,creation_step,step_kind,expires_at)
select operation_id,pg_temp.cid(1),pg_temp.cid(56),'bpc_Synthetic','bps_Synthetic',pg_temp.cid(58),
  'PORTAL_SESSION_CREATE',expires_at from portal_input;

insert into subscription_portal_sessions(public_operation_id,site_id,customer_id,subscription_id,expires_at)
select operation_id,pg_temp.cid(2),pg_temp.cid(3),pg_temp.cid(4),expires_at from portal_input;

create function pg_temp.portal_matches(account_num int default 1,site_num int default 2,
  customer_num int default 3,subscription_num int default 4,policy_version bigint default 1,
  key_digest text default repeat('3',64),extra_query text default '',expiry_shift interval default '0 seconds')
returns boolean language plpgsql as $proof$
declare output record;
begin
  execute format('execute owned_portal_proof(%L,%L,%L,%L,%L,%L,%L,%L,%L,%L,%L,%L,%L,%L,%L)',
    pg_temp.cid(account_num),pg_temp.cid(site_num),pg_temp.cid(customer_num),pg_temp.cid(subscription_num),
    pg_temp.cid(5),pg_temp.cid(11),pg_temp.cid(10),pg_temp.cid(8),policy_version,key_digest,
    (select return_url||extra_query from portal_input),(select expires_at+expiry_shift from portal_input),
    3,(select start_at from times),(select end_at from times)) into strict output;
  return to_jsonb(output)='{"portal_verified":true}'::jsonb;
exception when no_data_found then
  return false;
end
$proof$;

create procedure pg_temp.assert_portal_proof() language plpgsql as $assertions$
declare original_descriptor jsonb;
begin
  if not pg_temp.portal_matches() then raise exception 'owned Portal proof missing'; end if;
  if pg_temp.portal_matches(account_num=>99) or pg_temp.portal_matches(site_num=>99)
   or pg_temp.portal_matches(customer_num=>99) or pg_temp.portal_matches(subscription_num=>99)
   or pg_temp.portal_matches(policy_version=>2) or pg_temp.portal_matches(key_digest=>repeat('9',64))
   or pg_temp.portal_matches(extra_query=>'&extra=1') or pg_temp.portal_matches(expiry_shift=>'1 second')
  then raise exception 'foreign Portal proof accepted'; end if;

  update provider_operations set status='DISPATCHING' where kind='SUBSCRIPTION_CREATE';
  if pg_temp.portal_matches() then raise exception 'unacknowledged original subscription accepted'; end if;
  update provider_operations set status='ACKNOWLEDGED' where kind='SUBSCRIPTION_CREATE';
  update provider_operations set resource_id=pg_temp.cid(99) where kind='CUSTOMER_CREATE';
  if pg_temp.portal_matches() then raise exception 'foreign original customer step accepted'; end if;
  update provider_operations set resource_id=pg_temp.cid(3) where kind='CUSTOMER_CREATE';
  insert into provider_operations(id,payment_account_id,resource_id,kind,status)
  values(pg_temp.cid(63),pg_temp.cid(1),pg_temp.cid(99),'CUSTOMER_CREATE','DISPATCHING');
  if pg_temp.portal_matches() then raise exception 'extra customer dispatch accepted'; end if;
  update provider_operations set kind='SUBSCRIPTION_CREATE',status='ACKNOWLEDGED' where id=pg_temp.cid(63);
  if pg_temp.portal_matches() then raise exception 'extra subscription acknowledgment accepted'; end if;
  delete from provider_operations where id=pg_temp.cid(63);

  select descriptor into strict original_descriptor from stripe_portal_session_intents;
  update stripe_portal_session_intents set descriptor=descriptor-'members';
  if pg_temp.portal_matches() then raise exception 'incomplete descriptor accepted'; end if;
  update stripe_portal_session_intents set descriptor=jsonb_set(original_descriptor,'{customer,site_id,value}',to_jsonb(pg_temp.cid(99)));
  if pg_temp.portal_matches() then raise exception 'foreign descriptor customer Site accepted'; end if;
  update stripe_portal_session_intents set descriptor=jsonb_set(original_descriptor,'{account,id,value}',to_jsonb(pg_temp.cid(99)));
  if pg_temp.portal_matches() then raise exception 'foreign descriptor Account accepted'; end if;
  update stripe_portal_session_intents set descriptor=jsonb_set(original_descriptor,'{operation,expected_revision}','9');
  if pg_temp.portal_matches() then raise exception 'stale descriptor operation revision accepted'; end if;
  update stripe_portal_session_intents set descriptor=jsonb_set(original_descriptor,'{operation,policy_version}','9');
  if pg_temp.portal_matches() then raise exception 'foreign descriptor operation policy accepted'; end if;
  update stripe_portal_session_intents set descriptor=jsonb_set(original_descriptor,'{policy,site_id,value}',to_jsonb(pg_temp.cid(99)));
  if pg_temp.portal_matches() then raise exception 'foreign descriptor policy Site accepted'; end if;
  update stripe_portal_session_intents set descriptor=jsonb_set(original_descriptor,'{members,0,revision}','9');
  if pg_temp.portal_matches() then raise exception 'stale descriptor membership accepted'; end if;
  update stripe_portal_session_intents set descriptor=jsonb_set(original_descriptor,'{members}',
    (original_descriptor->'members')||(original_descriptor->'members'));
  if pg_temp.portal_matches() then raise exception 'duplicate descriptor member accepted'; end if;
  update stripe_portal_session_intents set descriptor=original_descriptor;

  update provider_operations set status='UNKNOWN' where kind='PORTAL_CONFIGURATION_CREATE';
  if pg_temp.portal_matches() then raise exception 'unknown configuration accepted'; end if;
  update provider_operations set status='ACKNOWLEDGED' where kind='PORTAL_CONFIGURATION_CREATE';
  update provider_operations set status='DISPATCHING' where kind='PORTAL_SESSION_CREATE';
  if pg_temp.portal_matches() then raise exception 'unacknowledged session accepted'; end if;
  update provider_operations set status='ACKNOWLEDGED' where kind='PORTAL_SESSION_CREATE';
  update provider_operations set fingerprint=repeat('8',64) where kind='PORTAL_SESSION_CREATE';
  if pg_temp.portal_matches() then raise exception 'session fingerprint mismatch accepted'; end if;
  update provider_operations set fingerprint=repeat('7',64) where kind='PORTAL_SESSION_CREATE';
  update subscription_portal_sessions set expires_at=expires_at+interval '1 second';
  if pg_temp.portal_matches() then raise exception 'publication expiry mismatch accepted'; end if;
  update subscription_portal_sessions set expires_at=(select expires_at from portal_input);
  update stripe_portal_session_intents set descriptor=descriptor||jsonb_build_object('return_url','https://elsewhere.test');
  if pg_temp.portal_matches() then raise exception 'foreign return accepted'; end if;
  update stripe_portal_session_intents set descriptor=descriptor||jsonb_build_object('return_url',(select return_url from portal_input));
  update subscriptions set paid_through=paid_through+interval '1 day';
  if pg_temp.portal_matches() then raise exception 'changed paid-through accepted'; end if;
  update subscriptions set paid_through=current_period_end;
  update subscriptions set entitled_price_version=2;
  if pg_temp.portal_matches() then raise exception 'changed price version accepted'; end if;
  update subscriptions set entitled_price_version=1;
  update payments set refunded_amount_minor=1;
  if pg_temp.portal_matches() then raise exception 'changed original cash accepted'; end if;
  update payments set refunded_amount_minor=0;
  insert into subscription_operations(id,site_id,customer_id,subscription_id,kind)
  values(pg_temp.cid(60),pg_temp.cid(2),pg_temp.cid(3),pg_temp.cid(4),'PORTAL');
  if pg_temp.portal_matches() then raise exception 'second Portal operation accepted'; end if;
  delete from subscription_operations where id=pg_temp.cid(60);
  insert into provider_operations(id,payment_account_id,kind,status)
  values(pg_temp.cid(62),pg_temp.cid(1),'SUBSCRIPTION_UPDATE','DISPATCHING');
  if pg_temp.portal_matches() then raise exception 'unresolved mutation accepted'; end if;
  delete from provider_operations where id=pg_temp.cid(62);
  if not pg_temp.portal_matches() then raise exception 'restored Portal proof missing'; end if;
end
$assertions$;
