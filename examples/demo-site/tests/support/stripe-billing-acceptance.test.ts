import test from 'node:test';import assert from 'node:assert/strict';import {readFile} from 'node:fs/promises';
// @ts-expect-error Native Node type erasure requires explicit source extension.
import {forwardBillingDispatch} from './stripe-billing-acceptance.ts';
import type {BillingBinding,BillingPort} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
import type {Route} from '@playwright/test';
const binding={endpoints:{backend:'http://127.0.0.1:18080',demo:'http://127.0.0.1:18081',receiver:'http://127.0.0.1:18082'},priceId:'11111111-2222-4333-8444-555555555555'} as BillingBinding;
function fixture(){const calls:string[]=[],headers={origin:binding.endpoints.demo,'content-type':'application/json',authorization:'Bearer '+'A'.repeat(43),'idempotency-key':'original-sdk-key'};let args:unknown[]=[];const request={url:()=>binding.endpoints.backend+'/api/v1/customer/subscriptions',method:()=> 'POST',redirectedFrom:()=>null,postData:()=>JSON.stringify({price_id:binding.priceId,recurring_consent:true}),allHeaders:async()=>headers};const route={request:()=>request,continue:async(...v:unknown[])=>{args=v;calls.push('continue');}} as unknown as Route;const port={beforeDispatch:async()=>{calls.push('persist');return true;}} as unknown as BillingPort;return {calls,headers,request,route,port,args:()=>args};}
test('original browser CREATE waits for durable key before unmodified continuation',async()=>{const f=fixture();await forwardBillingDispatch(f.route,f.port,binding,'Bearer '+'A'.repeat(43),'create',undefined,{deadline:Date.now()+1000});assert.deepEqual(f.calls,['persist','continue']);assert.deepEqual(f.args(),[]);});
for(const kind of ['foreign-origin','cookie','authority','body','persist-failure'])test('browser dispatch refuses '+kind,async()=>{const f=fixture();if(kind==='foreign-origin')f.headers.origin='https://other.test';if(kind==='cookie')Object.assign(f.headers,{cookie:'session=bad'});if(kind==='authority')f.headers.authorization='Bearer '+'B'.repeat(43);if(kind==='body')f.request.postData=()=>'{"price_id":"foreign","recurring_consent":true}';if(kind==='persist-failure')f.port.beforeDispatch=async()=>{throw Error('secret-sentinel');};await assert.rejects(()=>forwardBillingDispatch(f.route,f.port,binding,'Bearer '+'A'.repeat(43),'create',undefined,{deadline:Date.now()+1000}),e=>e instanceof Error&&!e.message.includes('sentinel'));assert.ok(!f.calls.includes('continue'));});
test('native Billing discovery is dedicated, bounded and explicitly excluded by default',async()=>{const config=await readFile(new URL('../../playwright.stripe-billing.config.ts',import.meta.url),'utf8'),normal=await readFile(new URL('../../playwright.config.ts',import.meta.url),'utf8');assert.match(config,/workers:1/);assert.match(config,/retries:0/);assert.match(config,/trace:'off'/);assert.doesNotMatch(config,/webServer/);assert.match(normal,/stripe-billing\.spec\.ts/);assert.match(normal,/stripe-billing-acceptance\.test\.ts/);assert.match(normal,/subscription-elements-browser\.test\.ts/);});
test('native runner reserves cleanup and scheduling time beyond the unchanged owner budget',async()=>{
 const [config,spec,owner]=await Promise.all([
  readFile(new URL('../../playwright.stripe-billing.config.ts',import.meta.url),'utf8'),
  readFile(new URL('../stripe-billing.spec.ts',import.meta.url),'utf8'),
  readFile(new URL('../../../../infrastructure/stripe-billing-acceptance.mjs',import.meta.url),'utf8')
 ]);
 const number=(source:string,pattern:RegExp)=>{const match=source.match(pattern);assert.ok(match,'expected explicit timeout contract');return Number(match[1]);};
 const ownerBudget=number(spec,/limits=\{deadline:Date\.now\(\)\+(\d+)/);
 const cleanupBudget=number(spec,/owner\.close\(\{deadline:Date\.now\(\)\+(\d+)/);
 const runnerBudget=number(config,/(?<!\w)timeout:(\d+)/);
 const globalBudget=number(config,/globalTimeout:(\d+)/);
 assert.equal(ownerBudget,45*60*1000);
 assert.equal(number(owner,/limits\.deadline-Date\.now\(\)>(\d+)/),ownerBudget);
 assert.equal(cleanupBudget,120*1000);
 assert.equal(number(owner,/let ok=true;const limits=\{deadline:Date\.now\(\)\+(\d+)/),cleanupBudget);
 assert.equal(runnerBudget,ownerBudget+cleanupBudget+60*1000);
 assert.equal(runnerBudget,48*60*1000);
 assert.equal(globalBudget,50*60*1000);
 assert.ok(globalBudget>runnerBudget);
});
