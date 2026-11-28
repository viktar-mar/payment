import { expect, it, vi } from 'vitest';
import { createGenericPaymentClient } from '../client.js';
import { base, json } from '../test/customer-fixtures.js';
import { actionWire, operationId, subscriptionId, subscriptionSession } from '../test/subscription-fixtures.js';
const attempt='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const sessionWire=()=>({protocol:'SERVER_CONFIRM_V2',provider:'stripe',flow:'PAYMENT',publishable_key:'pk_test_fixture',client_secret:'pi_fixture_secret_fixture',attempt_id:attempt,capability:'bca2_'+ 'A'.repeat(43),expires_at:new Date(Date.now()+60000).toISOString()});
const statusWire=()=>({protocol:'SERVER_CONFIRM_V2',attempt_id:attempt,state:'DECLINED',retry_allowed:true,successor_attempt_id:null,expires_at:new Date(Date.now()+60000).toISOString()});
it('selects v2 with strict redacted capability and sends exact server commands and safe reads',async()=>{
 const client=createGenericPaymentClient({apiBaseUrl:base}).subscriptions;
 expect(client).toHaveProperty('confirmationSession',expect.any(Function));
 const input={session:subscriptionSession(),subscriptionId,operationId};const calls:Request[]=[];
 vi.stubGlobal('fetch',async(url:string,init:RequestInit)=>{const r=new Request(url,init);calls.push(r);return json(url.endsWith('/action')?{...actionWire(),kind:'PROVIDER_CLIENT'}:url.endsWith('/confirmation-session')?sessionWire():url.endsWith('/confirm')?{...statusWire(),next_action_allowed:false}:statusWire());});
 const action=await client.action(input);const context=await client.confirmationSession!({...input,action,idempotencyKey:'select'});
 expect(context.protocol).toBe('SERVER_CONFIRM_V2');expect(Object.keys(context)).toEqual([]);expect(JSON.stringify(context)).not.toContain('secret');
 if(context.protocol!=='SERVER_CONFIRM_V2')throw new Error('Expected v2');
 await client.confirmPaymentAttempt!({...input,context,confirmationToken:'ctoken_fixture',idempotencyKey:'pay'});
 await client.confirmationAttempt!({...input,attemptId:attempt});
 await client.retryPaymentAttempt!({...input,attemptId:attempt,idempotencyKey:'retry'});
 expect(calls.map(r=>[r.method,new URL(r.url).pathname.split('/').at(-1)])).toEqual([['GET','action'],['POST','confirmation-session'],['POST','confirm'],['GET',attempt],['POST','retry']]);
 expect(await calls[2]!.json()).toEqual({capability:sessionWire().capability,confirmation_token:'ctoken_fixture'});
 expect(await calls[4]!.json()).toEqual({});
 await expect(client.confirmPaymentAttempt!({...input,context,confirmationToken:'ctoken_again',idempotencyKey:'again'})).rejects.toThrow();expect(calls).toHaveLength(5);
});
it('rejects duplicate capability keys, foreign attempt and unsafe status shapes',async()=>{
 const client=createGenericPaymentClient({apiBaseUrl:base}).subscriptions;expect(client).toHaveProperty('confirmationSession',expect.any(Function));
 const input={session:subscriptionSession(),subscriptionId,operationId};let response=JSON.stringify(sessionWire());
 vi.stubGlobal('fetch',async(url:string)=>url.endsWith('/action')?json({...actionWire(),kind:'PROVIDER_CLIENT'}):new Response(response,{headers:{'Content-Type':'application/json'}}));
 const action=await client.action(input);
 for(const key of ['capability','capab\\u0069lity']){response=JSON.stringify(sessionWire()).replace(/}$/,`,"${key}":"bca2_${'B'.repeat(43)}"}`);await expect(client.confirmationSession!({...input,action,idempotencyKey:'select'})).rejects.toMatchObject({code:'INVALID_RESPONSE'});}
 for(const bad of [{...statusWire(),attempt_id:operationId},{...statusWire(),state:'UNKNOWN'},{...statusWire(),next_action_allowed:true},{...statusWire(),client_secret:'private'}]){response=JSON.stringify(bad);await expect(client.confirmationAttempt!({...input,attemptId:attempt})).rejects.toMatchObject({code:'INVALID_RESPONSE'});}
});
it('rejects malformed session fields and contradictory retry status',async()=>{
 const client=createGenericPaymentClient({apiBaseUrl:base}).subscriptions;const input={session:subscriptionSession(),subscriptionId,operationId};let response:unknown=sessionWire();
 vi.stubGlobal('fetch',async(url:string)=>json(url.endsWith('/action')?{...actionWire(),kind:'PROVIDER_CLIENT'}:response));const action=await client.action(input);
 for(const patch of [{flow:'SETUP'},{protocol:'BROWSER_CONFIRM_V1'},{provider:'STRIPE'},{capability:'bca2_bad'},{publishable_key:'sk_test_secret'},{client_secret:'seti_wrong_secret_wrong'},{expires_at:'2000-01-01T00:00:00Z'},{expires_at:new Date(Date.now()+86400000).toISOString()},{action_token:'extra'},{attempt_id:'not-uuid'}]){response={...sessionWire(),...patch};await expect(client.confirmationSession!({...input,action,idempotencyKey:'select'})).rejects.toMatchObject({code:'INVALID_RESPONSE'});}
 response={...statusWire(),successor_attempt_id:operationId};await expect(client.confirmationAttempt!({...input,attemptId:attempt})).rejects.toMatchObject({code:'INVALID_RESPONSE'});
});
it('validates the pinned backend token limit before consuming or sending an attempt',async()=>{const client=createGenericPaymentClient({apiBaseUrl:base}).subscriptions,input={session:subscriptionSession(),subscriptionId,operationId};let count=0;vi.stubGlobal('fetch',async(url:string)=>{count++;return json(url.endsWith('/action')?{...actionWire(),kind:'PROVIDER_CLIENT'}:url.endsWith('/confirmation-session')?sessionWire():{...statusWire(),next_action_allowed:false});});const action=await client.action(input),context=await client.confirmationSession!({...input,action,idempotencyKey:'select'});if(context.protocol!=='SERVER_CONFIRM_V2')throw Error('Expected v2');await expect(client.confirmPaymentAttempt!({...input,context,confirmationToken:'ctoken_'+ 'A'.repeat(201),idempotencyKey:'pay'})).rejects.toMatchObject({code:'INVALID_CONFIGURATION'});expect(count).toBe(2);await expect(client.confirmPaymentAttempt!({...input,context:{...context},confirmationToken:'ctoken_valid',idempotencyKey:'copied'})).rejects.toMatchObject({code:'INVALID_SESSION'});expect(count).toBe(2);await client.confirmPaymentAttempt!({...input,context,confirmationToken:'ctoken_valid',idempotencyKey:'pay'});expect(count).toBe(3);});
