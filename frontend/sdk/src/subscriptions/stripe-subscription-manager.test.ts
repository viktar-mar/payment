import { beforeEach, expect, it, vi } from 'vitest';
import { fireEvent, getByLabelText, getByRole, waitFor } from '@testing-library/dom';
import * as sdk from '../index.js';
import { base, json } from '../test/customer-fixtures.js';
import { actionWire, mutationWire, offersWire, operationId, operationWire, productId, subscriptionId, subscriptionPriceId, subscriptionSession, subscriptionWire } from '../test/subscription-fixtures.js';
const provider = vi.hoisted(() => ({ load: vi.fn() }));
vi.mock('../providers/stripe-loader.js', () => ({ loadStripeClient: provider.load }));
const secrets=['G'.repeat(43),'pk_test_fixture','pi_fixture_secret_fixture'];
beforeEach(() => { vi.spyOn(HTMLElement.prototype,'getClientRects').mockReturnValue([{width:300,height:100}] as unknown as DOMRectList); });
function fixture(permission=true,expiresIn=60000,options:{prepareFailure?:'client-session'|'loader'|'loaderror';lostAck?:boolean}={}) {
  const events=new Map<string,()=>void>(), destroy=vi.fn(), mount=vi.fn(), confirm=vi.fn(async()=>({}));
  const element={ on:vi.fn((name:string,callback:()=>void)=>events.set(name,callback)),off:vi.fn(),mount,destroy };
  provider.load.mockReset();
  provider.load.mockResolvedValue({elements:vi.fn(()=>({create:vi.fn(()=>element)})),confirmPayment:confirm,confirmSetup:vi.fn()});
  if(options.prepareFailure==='loader')provider.load.mockRejectedValueOnce(new Error('Synthetic loader failure'));
  const calls:Request[]=[]; let settled=false,issues=0,unresolvedStatus:'PROCESSING'|'UNKNOWN'|'REQUIRES_ACTION'='PROCESSING';
  vi.stubGlobal('fetch',async(url:string,init:RequestInit)=>{
    const request=new Request(url,init);calls.push(request);
    if(url.includes('/offers'))return json({items:offersWire().items.map(o=>({...o,direction:null,allowed_timings:[]}))});
    if(url.includes('/subscriptions?'))return json({items:settled?[subscriptionWire()]:[],next_cursor:null});
    if(url.endsWith('/action-confirmations')){if(options.lostAck){unresolvedStatus='UNKNOWN';throw new Error('Synthetic lost acknowledgement');}settled=true;return json({...mutationWire(),browser_confirm_allowed:permission},202);}
    if(url.endsWith('/client-session')){if(++issues===1&&options.prepareFailure==='client-session')throw new Error('Synthetic client-session failure');return json({provider:'stripe',flow:'PAYMENT',publishable_key:secrets[1],client_secret:secrets[2],action_token:secrets[0],expires_at:new Date(Date.now()+expiresIn).toISOString()});}
    if(url.endsWith('/action'))return json({...actionWire(),kind:'PROVIDER_CLIENT',secret:secrets[0]});
    if(url.includes('/operations/'))return json({...operationWire(),status:settled?'SUCCEEDED':unresolvedStatus,action_available:!settled&&unresolvedStatus==='REQUIRES_ACTION'});
    if(url.includes('/billing-records')||url.includes('/events'))return json({items:[],next_cursor:null});
    if(request.method==='POST')return json({...mutationWire(),browser_confirm_allowed:false},202);
    throw new Error('Unexpected synthetic manager request');
  });
  sdk.registerGenericSubscriptionManager(); const manager=document.createElement('generic-subscription-manager') as sdk.GenericSubscriptionManager;
  manager.client=sdk.createGenericPaymentClient({apiBaseUrl:base});manager.customerSession=subscriptionSession();manager.priceId=subscriptionPriceId;manager.productId=productId;document.body.append(manager);
  const root=manager.shadowRoot as unknown as HTMLElement;
  const stateEvents:unknown[]=[];manager.addEventListener('generic-subscription-state',e=>stateEvents.push((e as CustomEvent).detail));
  async function create(){await waitFor(()=>expect(getByRole(root,'button',{name:'Create subscription'})).toBeEnabled());fireEvent.click(getByLabelText(root,'I consent to recurring payments'));fireEvent.click(getByRole(root,'button',{name:'Create subscription'}));}
  return {manager,root,events,destroy,mount,confirm,calls,create,stateEvents,requireAction:()=>{unresolvedStatus='REQUIRES_ACTION';}};
}
it('hands off an accepted PROCESSING CREATE through original GET/action despite action_available=false',async()=>{
  const f=fixture();await f.create();await waitFor(()=>expect(f.mount).toHaveBeenCalledTimes(1));
  expect(f.calls.some(r=>r.url.endsWith(`/operations/${operationId}`)&&r.method==='GET')).toBe(true);
  expect(f.calls.some(r=>r.url.endsWith(`/operations/${operationId}/action`)&&r.method==='GET')).toBe(true);
  const pay=getByRole(f.root,'button',{name:'Pay'});expect(pay).toBeDisabled();f.events.get('ready')!();await waitFor(()=>expect(pay).toBeEnabled());
  const host=f.mount.mock.calls[0]![0] as HTMLElement;expect(host.isConnected).toBe(true);expect(host.parentElement).toBe(f.manager);expect(host.assignedSlot).not.toBeNull();
  f.manager.locale='pt';expect(host.isConnected).toBe(true);expect(f.mount).toHaveBeenCalledTimes(1);f.manager.locale='en';
  fireEvent.click(pay);fireEvent.click(pay);await waitFor(()=>expect(f.confirm).toHaveBeenCalledTimes(1));await waitFor(()=>expect(f.destroy).toHaveBeenCalledTimes(1));
  await waitFor(()=>expect(f.manager.state.subscription?.billingStatus).toBe('ACTIVE'));
  expect(f.calls.filter(r=>r.url.endsWith('/action-confirmations'))).toHaveLength(1);
  const sinks=[f.manager.outerHTML,f.root.innerHTML,JSON.stringify(f.manager.state),JSON.stringify(f.stateEvents),JSON.stringify({...sessionStorage}),location.href];
  expect(sinks.some(value=>secrets.some(secret=>value.includes(secret)))).toBe(false);
  expect(JSON.stringify(f.manager.state)).not.toContain('browserConfirmAllowed');
});
it('keeps refresh after spent permission read-only and never reopens the old payment form',async()=>{
  const f=fixture(false);await f.create();await waitFor(()=>expect(f.mount).toHaveBeenCalledTimes(1));f.events.get('ready')!();
  await waitFor(()=>expect(getByRole(f.root,'button',{name:'Pay'})).toBeEnabled());fireEvent.click(getByRole(f.root,'button',{name:'Pay'}));
  await waitFor(()=>expect(f.destroy).toHaveBeenCalledTimes(1));expect(f.confirm).not.toHaveBeenCalled();
  await waitFor(()=>expect(getByRole(f.root,'button',{name:'Refresh status'})).toBeEnabled());const count=f.calls.length;
  fireEvent.click(getByRole(f.root,'button',{name:'Refresh status'}));await waitFor(()=>expect(getByRole(f.root,'button',{name:'Refresh status'})).toBeEnabled());
  expect(f.calls.slice(count).every(r=>r.method==='GET')).toBe(true);expect(f.mount).toHaveBeenCalledTimes(1);
});
it('destroys a waiting form on logout and ignores the late ready callback',async()=>{
  const f=fixture();await f.create();await waitFor(()=>expect(f.mount).toHaveBeenCalledTimes(1));const ready=f.events.get('ready')!;
  f.manager.customerSession=undefined;ready();await waitFor(()=>expect(f.destroy).toHaveBeenCalledTimes(1));
  expect(f.root.textContent).toContain('Sign in');expect(f.confirm).not.toHaveBeenCalled();expect(f.manager.children).toHaveLength(0);
});
it('retires the owned host and Pay control when the action session expires',async()=>{
  const f=fixture(true,500);await f.create();await waitFor(()=>expect(f.mount).toHaveBeenCalledTimes(1));f.events.get('ready')!();
  await waitFor(()=>expect(getByRole(f.root,'button',{name:'Pay'})).toBeEnabled());
  await waitFor(()=>expect(f.destroy).toHaveBeenCalledTimes(1));expect(f.manager.children).toHaveLength(0);
  expect(f.root.querySelector('button:not([hidden])')?.textContent).not.toBe('Pay');
  expect([...f.root.querySelectorAll('button')].find(b=>b.textContent==='Pay')?.hidden).toBe(true);
});
it('retires a same-turn removed and reinserted shadow projection without waiting for another Pay',async()=>{
  const f=fixture();await f.create();await waitFor(()=>expect(f.mount).toHaveBeenCalledTimes(1));f.events.get('ready')!();
  await waitFor(()=>expect(getByRole(f.root,'button',{name:'Pay'})).toBeEnabled());
  const host=f.manager.firstElementChild as HTMLElement,wrapper=host.assignedSlot!.parentElement!,pay=getByRole(f.root,'button',{name:'Pay'});
  wrapper.remove();pay.before(wrapper);
  await waitFor(()=>expect(f.destroy).toHaveBeenCalledTimes(1));expect(f.manager.children).toHaveLength(0);expect(f.confirm).not.toHaveBeenCalled();
});
it.each(['client-session','loader','loaderror'] as const)('recovers an unspent supported form after %s failure only through explicit original-operation Refresh',async prepareFailure=>{
  const f=fixture(true,60000,{prepareFailure});await f.create();
  if(prepareFailure==='loaderror'){await waitFor(()=>expect(f.mount).toHaveBeenCalledTimes(1));f.events.get('loaderror')!();}
  await waitFor(()=>expect(f.root.querySelector('[role="alert"]')).toHaveTextContent('Unable to complete this action.'));
  expect(f.manager.state.phase).toBe('error');expect(f.manager.state.operation?.operationId).toBe(operationId);
  expect(f.calls.filter(r=>r.url.endsWith('/client-session'))).toHaveLength(1);expect(f.calls.filter(r=>r.url.endsWith('/action-confirmations'))).toHaveLength(0);expect(f.confirm).not.toHaveBeenCalled();
  await waitFor(()=>expect(getByRole(f.root,'button',{name:'Refresh status'})).toBeEnabled());const before=f.calls.length;
  fireEvent.click(getByRole(f.root,'button',{name:'Refresh status'}));fireEvent.click(getByRole(f.root,'button',{name:'Refresh status'}));
  await waitFor(()=>expect(f.mount).toHaveBeenCalledTimes(prepareFailure==='loaderror'?2:1));
  const recovered=f.calls.slice(before);
  expect(recovered.filter(r=>r.method==='GET').map(r=>new URL(r.url).pathname)).toEqual([`/api/v1/customer/subscriptions/${subscriptionId}/operations/${operationId}`,`/api/v1/customer/subscriptions/${subscriptionId}/operations/${operationId}/action`]);
  expect(recovered.filter(r=>r.method==='POST').map(r=>new URL(r.url).pathname)).toEqual([`/api/v1/customer/subscriptions/${subscriptionId}/operations/${operationId}/client-session`]);
  expect(f.calls.filter(r=>r.method==='POST'&&new URL(r.url).pathname==='/api/v1/customer/subscriptions')).toHaveLength(1);
  const pay=getByRole(f.root,'button',{name:'Pay'});expect(pay).toBeDisabled();f.events.get('ready')!();await waitFor(()=>expect(pay).toBeEnabled());
  expect(f.confirm).not.toHaveBeenCalled();expect(f.calls.filter(r=>r.url.endsWith('/action-confirmations'))).toHaveLength(0);
  const ready=f.calls.length;fireEvent.click(getByRole(f.root,'button',{name:'Refresh status'}));await waitFor(()=>expect(getByRole(f.root,'button',{name:'Refresh status'})).toBeEnabled());
  expect(f.calls.slice(ready).map(r=>[r.method,new URL(r.url).pathname])).toEqual([['GET',`/api/v1/customer/subscriptions/${subscriptionId}/operations/${operationId}`]]);
  expect(pay).toBeEnabled();expect(f.mount).toHaveBeenCalledTimes(prepareFailure==='loaderror'?2:1);
  fireEvent.click(pay);await waitFor(()=>expect(f.confirm).toHaveBeenCalledTimes(1));
});
it('never prepares again after a lost Pay acknowledgement even when the original operation needs action on Refresh',async()=>{
  const f=fixture(true,60000,{lostAck:true});await f.create();await waitFor(()=>expect(f.mount).toHaveBeenCalledTimes(1));f.events.get('ready')!();
  await waitFor(()=>expect(getByRole(f.root,'button',{name:'Pay'})).toBeEnabled());fireEvent.click(getByRole(f.root,'button',{name:'Pay'}));
  await waitFor(()=>expect(f.manager.state.operation?.status).toBe('UNKNOWN'));await waitFor(()=>expect(getByRole(f.root,'button',{name:'Refresh status'})).toBeEnabled());
  expect(f.destroy).toHaveBeenCalledTimes(1);expect(f.confirm).not.toHaveBeenCalled();f.requireAction();const before=f.calls.length;
  fireEvent.click(getByRole(f.root,'button',{name:'Refresh status'}));await waitFor(()=>expect(f.manager.state.operation?.status).toBe('REQUIRES_ACTION'));await waitFor(()=>expect(getByRole(f.root,'button',{name:'Refresh status'})).toBeEnabled());
  expect(f.calls.slice(before).map(r=>[r.method,new URL(r.url).pathname])).toEqual([['GET',`/api/v1/customer/subscriptions/${subscriptionId}/operations/${operationId}`]]);
  expect(f.calls.filter(r=>r.url.endsWith('/client-session'))).toHaveLength(1);expect(f.calls.filter(r=>r.url.endsWith('/action-confirmations'))).toHaveLength(1);expect(f.mount).toHaveBeenCalledTimes(1);
});
