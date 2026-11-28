import { render, screen, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { SubscriptionDetails } from './SubscriptionDetails';
import { instant, operationId, siteId, subscriptionId, unknownCancellationSubscription } from './test-fixtures';
afterEach(()=>{vi.unstubAllGlobals();window.history.replaceState(null,'','/');});
it('distinguishes provider acknowledgement, authoritative billing and local access with read-only Admin requests',async()=>{
  window.history.replaceState(null,'',`/subscriptions/${subscriptionId}?site_id=${siteId}`);
  const calls:string[]=[];const value=unknownCancellationSubscription();
  vi.stubGlobal('fetch',async(path:string)=>{
    calls.push(path);
    if(path.includes('/operations/'))return Response.json({operation_id:operationId,subscription_id:subscriptionId,status:'PROCESSING',action_available:false});
    if(path.includes('/billing-records'))return Response.json({items:[],next_cursor:null});
    if(path.includes('/events'))return Response.json({items:[{event_id:operationId,subscription_id:subscriptionId,sequence:2,revision:3,type:'RECONCILIATION_REQUIRED',source:'RECONCILIATION',occurred_at:instant,received_at:instant,operation_id:operationId,billing_record_id:null,payment_id:null}],next_cursor:null});
    return Response.json({...value,billing_status:'ACTIVE',entitled_price:null,access:{...value.access,allowed:false,reason:'EXPIRED',entitled_price_id:null,entitled_price_version:null,valid_until:null}});
  });
  render(<SubscriptionDetails subscriptionId={subscriptionId}/>);
  const summary=await screen.findByRole('region',{name:'Subscription summary'});
  expect(within(summary).getAllByText('ACTIVE').length).toBeGreaterThan(0);
  expect(within(summary).getByText(/Not allowed · EXPIRED/)).toBeInTheDocument();
  expect(await screen.findByText('Provider acknowledgement does not confirm billing settlement or grant access.')).toBeInTheDocument();
  expect(await screen.findByText('Reconciliation required. Billing and access remain based on verified server records.')).toBeInTheDocument();
  expect(calls.some(path=>/\/(?:action|client-session|action-confirmations)(?:\?|$)/.test(path))).toBe(false);
});
