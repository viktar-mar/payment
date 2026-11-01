import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {readStripePaymentAttemptFailure as read, retainStripePaymentAttemptFailure as retain} from './stripe-payment-attempt-diagnostic.js';
import {mountStripePaymentAttempt} from './stripe-payment-attempt-driver.js';
import {registerSubscriptionAuthority} from './authority.js';
import {bindPaymentSession} from './confirmation-v2.js';
import {PaymentSdkError} from '../errors.js';
import {subscriptionId, operationId, subscriptionSession} from '../test/subscription-fixtures.js';
import type {SubscriptionClient, SubscriptionPaymentSession} from './contracts.js';
const provider=vi.hoisted(()=>({load:vi.fn()}));
vi.mock('../providers/stripe-loader.js',()=>({loadStripeClient:provider.load}));
const blockedFetch=vi.fn(()=>{throw new Error('offline test transport blocked');});
afterEach(()=>{expect(blockedFetch).not.toHaveBeenCalled();});
beforeEach(()=>{blockedFetch.mockClear();vi.stubGlobal('fetch',blockedFetch);vi.spyOn(HTMLElement.prototype,'getClientRects').mockReturnValue([{width:300,height:100}] as unknown as DOMRectList);});
function deferred(){let resolve!:(value:any)=>void;const promise=new Promise<any>(r=>{resolve=r;});return {promise,resolve};}
async function fixture(options:{validation?:boolean;nextAction?:boolean}={validation:true}){
 let current=true,authority=true;
 const receipt={protocol:'SERVER_CONFIRM_V2',attemptId:'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',state:'OBSERVE',retryAllowed:false,successorAttemptId:null,expiresAt:new Date(Date.now()+50000).toISOString(),nextActionAllowed:options.nextAction??false};
 const submit=vi.fn<()=>Promise<any>>(async()=>options.validation?{error:{message:'private'}}:{});
 const token=vi.fn<()=>Promise<any>>(async()=>({confirmationToken:{id:'ctoken_fixture'}}));
 const confirm=vi.fn<(...args:any[])=>Promise<any>>(async()=>receipt),next=vi.fn<()=>Promise<any>>(async()=>({})),destroy=vi.fn(),retire=vi.fn();
 const client={confirmPaymentAttempt:confirm} as unknown as SubscriptionClient;
 registerSubscriptionAuthority(client,()=>()=>{if(!authority)throw new PaymentSdkError('INVALID_SESSION');});
 const input={session:subscriptionSession(),subscriptionId,operationId};
 const context=bindPaymentSession(client,input,{protocol:'SERVER_CONFIRM_V2',flow:'PAYMENT',publishableKey:'pk_test_fixture',clientSecret:'pi_fixture_secret_fixture',attemptId:receipt.attemptId,capability:'bca2_'+'A'.repeat(43),expiresAt:receipt.expiresAt} as SubscriptionPaymentSession);
 provider.load.mockResolvedValue({elements:()=>({submit,create:()=>{let ready=()=>{};return {on:(n:string,f:()=>void)=>{if(n==='ready')ready=f;},off:vi.fn(),mount:()=>queueMicrotask(ready),destroy};}}),createConfirmationToken:token,handleNextAction:next});
 const container=document.createElement('div');document.body.append(container);
 return {handle:await mountStripePaymentAttempt({client,input,context,container,isCurrent:()=>current,onRetire:retire}),confirm,submit,token,next,destroy,retire,input,context,receipt,loseCurrent:()=>{current=false;},loseAuthority:()=>{authority=false;}};
}
it('retains SUBMIT_VALIDATION on the actual returned handle without dispatch or result mutation',async()=>{
 const f=await fixture();try {const result=await f.handle.confirm();expect(result).toEqual({validationFailed:true});expect(Object.keys(result)).toEqual(['validationFailed']);expect(f.confirm).not.toHaveBeenCalled();expect(read(f.handle)).toBe('SUBMIT_VALIDATION');expect(Object.keys(f.handle)).toEqual(['protocol','destroy','confirm']);} finally {f.handle.destroy();}
});
it('admits only nine literal categories with immutable first provenance and unreadable keys',()=>{
 let reads=0;const hostile=new Proxy({}, {get(){reads++;throw null;},getPrototypeOf(){reads++;throw null;},ownKeys(){reads++;throw null;}});
 for(const code of ['GUARD','STATE','SUBMIT_VALIDATION','SUBMIT','TOKEN_VALIDATION','TOKEN','CLIENT','NEXT_ACTION','RETIRED']){const h={};retain(h,code);retain(h,'STATE');expect(read(h)).toBe(code);expect(read(Object.create(h))).toBeNull();}
 for(const code of ['RECEIPT','CLIENT_EXTRA','PREFIX_CLIENT','client','CLIENT\n',{},hostile,null]){const h={};retain(h,code);expect(read(h)).toBeNull();}
 for(const h of [null,undefined,1,'GUARD',true,Symbol('x')]){retain(h,'GUARD');expect(read(h)).toBeNull();}
 retain(hostile,'TOKEN');expect(read(hostile)).toBe('TOKEN');expect(reads).toBe(0);
});
it.each(['SUBMIT','TOKEN','CLIENT','NEXT_ACTION'])('records the actual %s rejection boundary without reading private rejected properties',async code=>{
 const f=await fixture({nextAction:true});let reads=0;const foreign=Object.create(null);for(const key of ['message','cause','stack','diagnostic'])Object.defineProperty(foreign,key,{get(){reads++;throw null;}});
 const fn={SUBMIT:f.submit,TOKEN:f.token,CLIENT:f.confirm,NEXT_ACTION:f.next}[code]!;fn.mockRejectedValue(foreign);
 await expect(f.handle.confirm()).rejects.toMatchObject({code:'PROVIDER_RESULT_UNKNOWN'});expect(read(f.handle)).toBe(code);expect(reads).toBe(0);expect(f.confirm).toHaveBeenCalledTimes(['CLIENT','NEXT_ACTION'].includes(code)?1:0);expect(f.destroy).toHaveBeenCalledTimes(1);
});
it.each([{error:{}},{}])('records token validation and missing token without dispatch: %j',async value=>{const f=await fixture({});f.token.mockResolvedValue(value);expect(await f.handle.confirm()).toEqual({validationFailed:true});expect(read(f.handle)).toBe('TOKEN_VALIDATION');expect(f.confirm).not.toHaveBeenCalled();expect(f.destroy).not.toHaveBeenCalled();f.handle.destroy();});
it('attributes pre-confirm current and bound-session checks to GUARD',async()=>{
 for(const loss of ['current','authority','binding']){const f=await fixture({});if(loss==='current')f.loseCurrent();if(loss==='authority')f.loseAuthority();if(loss==='binding')f.input.operationId='foreign';await expect(f.handle.confirm()).rejects.toBeDefined();expect(read(f.handle)).toBe('GUARD');expect(f.submit).not.toHaveBeenCalled();expect(f.confirm).not.toHaveBeenCalled();}
});
it.each(['SUBMIT','TOKEN','CLIENT','NEXT_ACTION'])('guard rejection after resolved %s wins over outer operation category',async boundary=>{
 const f=await fixture({nextAction:true}),gate=deferred();const fn={SUBMIT:f.submit,TOKEN:f.token,CLIENT:f.confirm,NEXT_ACTION:f.next}[boundary]!;fn.mockReturnValue(gate.promise);const running=f.handle.confirm();const rejected=expect(running).rejects.toBeDefined();await vi.waitFor(()=>expect(fn).toHaveBeenCalledTimes(1));f.loseCurrent();gate.resolve(boundary==='TOKEN'?{confirmationToken:{id:'ctoken_fixture'}}:boundary==='CLIENT'?f.receipt:{});await rejected;expect(read(f.handle)).toBe('GUARD');expect(f.confirm).toHaveBeenCalledTimes(['CLIENT','NEXT_ACTION'].includes(boundary)?1:0);
});
it('captures authority loss at post-await guard before dispatch',async()=>{const f=await fixture({});f.submit.mockImplementation(async()=>{f.loseAuthority();return {};});await expect(f.handle.confirm()).rejects.toMatchObject({code:'INVALID_SESSION'});expect(read(f.handle)).toBe('GUARD');expect(f.confirm).not.toHaveBeenCalled();});
it('records external retirement while pending and ignores late token',async()=>{const f=await fixture({}),gate=deferred();f.token.mockReturnValue(gate.promise);const running=f.handle.confirm();const rejected=expect(running).rejects.toMatchObject({name:'AbortError'});await vi.waitFor(()=>expect(f.token).toHaveBeenCalled());f.handle.destroy();expect(read(f.handle)).toBe('RETIRED');gate.resolve({confirmationToken:{id:'ctoken_late'}});await rejected;expect(read(f.handle)).toBe('RETIRED');expect(f.confirm).not.toHaveBeenCalled();expect(f.destroy).toHaveBeenCalledTimes(1);});
it('records STATE for concurrent confirm without issuing a duplicate call',async()=>{const f=await fixture({}),gate=deferred();f.submit.mockReturnValue(gate.promise);const running=f.handle.confirm();await expect(f.handle.confirm()).rejects.toMatchObject({code:'PAYMENT_STATE_CONFLICT'});expect(read(f.handle)).toBe('STATE');gate.resolve({});expect(await running).toBe(f.receipt);expect(f.confirm).toHaveBeenCalledTimes(1);expect(read(f.handle)).toBe('STATE');});
it('normal successful finally-destroy carries no failure and repeated confirmation remains fenced',async()=>{const f=await fixture({nextAction:true});expect(await f.handle.confirm()).toBe(f.receipt);expect(read(f.handle)).toBeNull();expect(f.destroy).toHaveBeenCalledTimes(1);expect(f.retire).toHaveBeenCalledTimes(1);expect(f.next).toHaveBeenCalledTimes(1);const args=f.confirm.mock.calls[0]![0];expect(args.context).toBe(f.context);expect(args.confirmationToken).toBe('ctoken_fixture');expect(args.subscriptionId).toBe(subscriptionId);expect(args.operationId).toBe(operationId);expect(args.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);await expect(f.handle.confirm()).rejects.toMatchObject({code:'PAYMENT_STATE_CONFLICT'});expect(read(f.handle)).toBe('STATE');expect(f.confirm).toHaveBeenCalledTimes(1);});
it('keeps ordinary SDK validation correction and first category across a later success',async()=>{const f=await fixture();expect(await f.handle.confirm()).toEqual({validationFailed:true});f.submit.mockResolvedValue({});expect(await f.handle.confirm()).toBe(f.receipt);expect(f.submit).toHaveBeenCalledTimes(2);expect(f.confirm).toHaveBeenCalledTimes(1);expect(read(f.handle)).toBe('SUBMIT_VALIDATION');expect(f.destroy).toHaveBeenCalledTimes(1);});
it('destroying an idle returned handle is not pending cancellation',async()=>{const f=await fixture({});f.handle.destroy();expect(read(f.handle)).toBeNull();await expect(f.handle.confirm()).rejects.toBeDefined();expect(read(f.handle)).toBe('GUARD');expect(f.confirm).not.toHaveBeenCalled();});
