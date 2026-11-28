-- Extend the synthetic initial paid fixture with the real V29 predecessor/successor columns.
update pg_temp.subscription_confirmation_chains set subscription_revision=3;
update pg_temp.subscription_confirmation_attempts
set state='DECLINED',request_id='req_original',charge_reference='ch_declined',decline_code='generic_decline',
    key_version='v1',issued_at=claimed_at-interval '5 seconds',expires_at=claimed_at+interval '15 minutes',
    successor=pg_temp.cid(51),retry_key=repeat('3',64);
update pg_temp.subscription_action_capabilities
set consumed_at=(select claimed_at from pg_temp.subscription_confirmation_attempts);
insert into pg_temp.subscription_confirmation_attempts(id,chain_id,predecessor,state,provider_key,confirm_key,
    token_digest,fingerprint,key_version,issued_at,claimed_at,expires_at)
select pg_temp.cid(51),chain_id,id,'OBSERVE','billing-confirm-v2:'||pg_temp.cid(51),repeat('4',64),
       repeat('5',64),repeat('6',64),key_version,claimed_at+interval '1 second',claimed_at+interval '2 seconds',expires_at
from pg_temp.subscription_confirmation_attempts where id=pg_temp.cid(6);

-- Rewind the disposable fixture to the uncaptured first attempt, preserving the original open bill.
create procedure pg_temp.only_original_decline() language plpgsql as $$
begin
  delete from pg_temp.subscription_confirmation_attempts where predecessor is not null;
  update pg_temp.subscription_confirmation_attempts set successor=null,retry_key=null;
  update pg_temp.subscription_operations set status='REQUIRES_ACTION';
  update pg_temp.subscriptions set billing_status='PAST_DUE',entitled_price_id=null,paid_through=null;
  update pg_temp.subscription_billing_records set settlement='OPEN';
  delete from pg_temp.payments;
  delete from pg_temp.subscription_provider_objects where object_type='PAYMENT';
  delete from pg_temp.payment_events;
  delete from pg_temp.subscription_events;
  delete from pg_temp.outbox_events;
  delete from pg_temp.outbox_deliveries;
end
$$;
