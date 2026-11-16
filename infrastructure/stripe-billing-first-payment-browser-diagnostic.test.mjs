import test, {before, after} from 'node:test';
const originalFetch=globalThis.fetch;let transportCalls=0;
before(()=>{globalThis.fetch=()=>{transportCalls++;throw new Error('offline test transport blocked');};});
after(()=>{globalThis.fetch=originalFetch;assert.equal(transportCalls,0);});
import assert from 'node:assert/strict';
import {browserFailure, browserDiagnostic, browserCleanupConfirmed, finishBrowserCleanup, isBrowserDiagnostic} from './stripe-billing-first-payment-browser-diagnostic.mjs';
import * as privateOrigin from './stripe-billing-first-payment-browser-diagnostic.mjs';

const codes=['BROWSER_CONTEXT','BROWSER_PAGE','BROWSER_SETUP','BROWSER_NAVIGATION','BROWSER_CONFIGURE','BROWSER_OTP_REQUEST','BROWSER_OTP_VERIFY','BROWSER_PREPARE','BROWSER_ACTION_BRIDGE','BROWSER_SESSION_BRIDGE','BROWSER_CONFIRM_BRIDGE','BROWSER_WAIT_READY','BROWSER_START_CLICK','BROWSER_READY_PHASE','BROWSER_READY_BUTTON','BROWSER_INITIAL_ACTION','BROWSER_INITIAL_SESSION','BROWSER_INITIAL_MOUNT','BROWSER_METHOD_MOUNT','BROWSER_PROVIDER_ROUTE','BROWSER_ASSET_ROUTE','BROWSER_CARD_NUMBER','BROWSER_CARD_EXPIRY','BROWSER_CARD_CVC','BROWSER_POSTAL_CHECK','BROWSER_POSTAL_FILL','BROWSER_PAY_CLICK','BROWSER_WAIT_OBSERVE','BROWSER_WAIT_OBSERVE_LAST_OBSERVED_SUBMIT','BROWSER_WAIT_OBSERVE_LAST_OBSERVED_TOKEN','BROWSER_CLOSE','BROWSER_CONFIRM_GUARD','BROWSER_CONFIRM_STATE','BROWSER_CONFIRM_SUBMIT_VALIDATION','BROWSER_CONFIRM_SUBMIT','BROWSER_CONFIRM_TOKEN_VALIDATION','BROWSER_CONFIRM_TOKEN','BROWSER_CONFIRM_CLIENT','BROWSER_CONFIRM_NEXT_ACTION','BROWSER_CONFIRM_RETIRED','BROWSER_CONFIRM_RECEIPT'];
test('internal errors carry only a finite checkpoint and conservative private cleanup knowledge',()=>{
 for(const code of codes){const e=browserFailure(code);assert.equal(String(e),'Error: first_payment_BROWSER');assert.equal(browserDiagnostic(e),code);assert.equal(browserCleanupConfirmed(e),false);assert.deepEqual(Object.keys(e),[]);finishBrowserCleanup(e,true);assert.equal(browserCleanupConfirmed(e),true);assert.equal(isBrowserDiagnostic(code),true);}
});
test('bounded wait marker categories carry no payload and remain closed',()=>{
 for(const suffix of ['MARKER_MISSING','SUBMIT_CLEARED','TOKEN_CLEARED','MARKER_INVALID']) {
  const code='BROWSER_WAIT_OBSERVE_'+suffix,error=browserFailure(code);
  assert.equal(browserDiagnostic(error),code);assert.deepEqual(Object.keys(error),[]);
  assert.equal(isBrowserDiagnostic(code+'_PRIVATE'),false);
 }
});
test('provider boundaries are finite private provenance without payload',()=>{
 for(const code of ['BROWSER_PROVIDER_HEADERS','BROWSER_PROVIDER_CURRENT','BROWSER_PROVIDER_REDIRECT','BROWSER_PROVIDER_USERINFO','BROWSER_PROVIDER_AUTH','BROWSER_PROVIDER_ORIGIN_SCHEME','BROWSER_PROVIDER_ORIGIN_PORT','BROWSER_PROVIDER_ORIGIN_STRIPE_COM','BROWSER_PROVIDER_ORIGIN_STRIPE_COM_M','BROWSER_PROVIDER_ORIGIN_STRIPE_COM_R','BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER','BROWSER_PROVIDER_ORIGIN_STRIPE_NETWORK','BROWSER_PROVIDER_ORIGIN_OTHER','BROWSER_PROVIDER_NAVIGATION','BROWSER_PROVIDER_FALLBACK']){
  const error=browserFailure(code);assert.equal(browserDiagnostic(error),code);assert.deepEqual(Object.keys(error),[]);
  assert.equal(String(error),'Error: first_payment_BROWSER');assert.equal(isBrowserDiagnostic(code),true);assert.equal(isBrowserDiagnostic(code+'_PRIVATE'),false);
  for(const forged of [code,{diagnostic:code,cleanupConfirmed:true},new Error(code),Object.create(error)]){
   assert.equal(browserDiagnostic(forged),null);assert.equal(browserCleanupConfirmed(forged),false);finishBrowserCleanup(forged,true);assert.equal(browserCleanupConfirmed(forged),false);
  }
  for(const raw of [code+' https://m.stripe.com/private',code+'\n',code+'_PRIVATE']){
   assert.equal(isBrowserDiagnostic(raw),false);assert.equal(browserDiagnostic(browserFailure(raw)),null);
  }
  finishBrowserCleanup(error,true);assert.equal(browserCleanupConfirmed(error),true);
 }
});
test('foreign values and unreadable proxies cannot supply provenance',()=>{
 let reads=0;const hostile=new Proxy({diagnostic:'BROWSER_PAGE',cleanupConfirmed:true},{get(){reads++;throw null;},getPrototypeOf(){reads++;throw null;},ownKeys(){reads++;throw null;}});
 for(const e of [hostile,null,undefined,1,'BROWSER_PAGE',{code:'BROWSER_PAGE'},new Error('BROWSER_PAGE'),Object.create(browserFailure('BROWSER_PAGE'))]){assert.equal(browserDiagnostic(e),null);assert.equal(browserCleanupConfirmed(e),false);finishBrowserCleanup(e,true);}
 for(const code of ['BROWSER_UNKNOWN','BROWSER_PAGE_EXTRA','PREFIX_BROWSER_PAGE','BROWSER_PAGE1','BROWSER_PAGE\n','sk_test_SECRET',hostile]){assert.equal(isBrowserDiagnostic(code),false);assert.equal(browserDiagnostic(browserFailure(code)),null);}
 assert.equal(reads,0);
});

const originRun='11111111-2222-4333-8444-000000000001',originSource='a'.repeat(40);
const witness=()=>({runId:originRun,expectedSource:originSource,origin:'https://other.stripe.com'});
test('only the original OTHER error retains one immutable private origin snapshot',()=>{
 assert.equal(typeof privateOrigin.rememberBrowserProviderOrigin,'function');
 assert.equal(typeof privateOrigin.browserProviderOrigin,'function');
 const error=browserFailure('BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER');
 assert.equal(privateOrigin.browserProviderOrigin(error),null);
 assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,'https://other.stripe.com'),true);
 const saved=privateOrigin.browserProviderOrigin(error);
 assert.deepEqual(saved,witness());assert.equal(Object.isFrozen(saved),true);
 assert.throws(()=>{saved.origin='https://second.stripe.com';},TypeError);
 assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,'https://second.stripe.com'),false);
 assert.deepEqual(privateOrigin.browserProviderOrigin(error),witness());
 finishBrowserCleanup(error,true);assert.equal(browserCleanupConfirmed(error),true);
 assert.equal(browserDiagnostic(error),'BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER');
 assert.equal(String(error),'Error: first_payment_BROWSER');assert.equal(JSON.stringify(error),'{}');
 assert.deepEqual(Object.keys(error),[]);assert.deepEqual(Reflect.ownKeys(error).sort(),['message','stack']);
 assert.doesNotMatch(error.stack,/stripe\.com|11111111|aaaaaaaa/);
});
test('origin capture rejects foreign errors without evaluating their payloads',()=>{
 assert.equal(typeof privateOrigin.rememberBrowserProviderOrigin,'function');
 let reads=0;
 const hostile=new Proxy({},{get(){reads++;throw null;},getPrototypeOf(){reads++;throw null;},ownKeys(){reads++;throw null;},getOwnPropertyDescriptor(){reads++;throw null;}});
 const other=browserFailure('BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER');
 for(const error of [hostile,null,undefined,1,'BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER',{},new Error('BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER'),Object.create(other),browserFailure('BROWSER_PAGE'),browserFailure('BROWSER_PROVIDER_ORIGIN_STRIPE_COM_M'),browserFailure('BROWSER_PROVIDER_ORIGIN_STRIPE_COM_R')]){
  assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,'https://other.stripe.com'),false);
  assert.equal(privateOrigin.browserProviderOrigin(error),null);
 }
 assert.equal(reads,0);
});
test('witness validator rejects the network-admitted merchant origin as new OTHER evidence',()=>{
 assert.equal(privateOrigin.isBrowserProviderOriginWitness({...witness(),origin:'https://merchant-ui-api.stripe.com'}),false);
});
test('private capture rejects the network-admitted merchant origin as new OTHER evidence',()=>{
 const error=browserFailure('BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER');
 assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,'https://merchant-ui-api.stripe.com'),false);
 assert.equal(privateOrigin.browserProviderOrigin(error),null);
});
test('merchant origin descendant remains eligible for new OTHER evidence',()=>{
 const error=browserFailure('BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER');
 const descendant='https://child.merchant-ui-api.stripe.com',descendantCandidate={...witness(),origin:descendant};
 assert.equal(privateOrigin.isBrowserProviderOriginWitness(descendantCandidate),true);
 assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,descendant),true);
 assert.deepEqual(privateOrigin.browserProviderOrigin(error),descendantCandidate);
});
test('witness validator admits only canonical bounded OTHER HTTPS DNS origins and original lowercase identity',()=>{
 assert.equal(typeof privateOrigin.isBrowserProviderOriginWitness,'function');
 for(const origin of ['https://stripe.com','https://other.stripe.com','https://child.m.stripe.com','https://child.r.stripe.com','https://a-b.stripe.com','https://xn--bcher-kva.stripe.com']){
  const candidate={...witness(),origin};
  assert.equal(privateOrigin.isBrowserProviderOriginWitness(candidate),true,origin);
  const error=browserFailure('BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER');
  assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,origin),true,origin);
  assert.deepEqual(privateOrigin.browserProviderOrigin(error),candidate);
 }
 for(const origin of ['https://m.stripe.com','https://r.stripe.com','https://api.stripe.com','https://hooks.stripe.com','https://js.stripe.com','https://child.js.stripe.com','https://other.stripe.com/','https://OTHER.stripe.com','HTTPS://other.stripe.com','https://other.stripe.com:443','https://other.stripe.com:444','http://other.stripe.com','https://u:p@other.stripe.com','https://other.stripe.com/p','https://other.stripe.com?q=secret','https://other.stripe.com#secret','https://other.stripe.com.','https://notstripe.com','https://stripe.network','https://other.stripe.com.evil.test','https://a_b.stripe.com','https://-a.stripe.com','https://a-.stripe.com','https://a..stripe.com','https://xn--a.stripe.com','https://'+'a'.repeat(64)+'.stripe.com','https://'+('a'.repeat(63)+'.').repeat(4)+'stripe.com','https://other.stripe.com\n','']){
  const candidate={...witness(),origin};
  assert.equal(privateOrigin.isBrowserProviderOriginWitness(candidate),false,origin);
  const error=browserFailure('BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER');
  assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,origin),false,origin);
  assert.equal(privateOrigin.browserProviderOrigin(error),null);
 }
 for(const changed of [{runId:originRun.toUpperCase().replace('11111111','ABCDEFAB')},{runId:originRun+'\n'},{runId:'x'},{expectedSource:'A'.repeat(40)},{expectedSource:'a'.repeat(39)},{expectedSource:originSource+'\n'},{expectedSource:1},{origin:new String('https://other.stripe.com')}]){
  const candidate={...witness(),...changed};assert.equal(privateOrigin.isBrowserProviderOriginWitness(candidate),false);
  assert.equal(privateOrigin.rememberBrowserProviderOrigin(browserFailure('BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER'),candidate.runId,candidate.expectedSource,candidate.origin),false);
 }
 const error=browserFailure('BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER');
 assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,'https://api.stripe.com'),false);
 assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,'https://other.stripe.com'),true);
});
test('encoded origin labels stay canonical even when URL accepts unchanged ASCII origins',t=>{
 t.mock.method(globalThis,'URL',function PermissiveURL(origin){return {origin};});
 for(const origin of ['https://other.stripe.com','https://xn--bcher-kva.stripe.com','https://xn--bcher-kva.xn--bcher-kva.stripe.com']){
  assert.equal(new URL(origin).origin,origin);
  const candidate={...witness(),origin};
  assert.equal(privateOrigin.isBrowserProviderOriginWitness(candidate),true,origin);
  const error=browserFailure('BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER');
  assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,origin),true,origin);
  assert.deepEqual(privateOrigin.browserProviderOrigin(error),candidate);
 }
 for(const label of ['xn--a','xn--8i7caa']){
  for(const hostname of [label+'.stripe.com','xn--bcher-kva.'+label+'.stripe.com',label+'.xn--bcher-kva.stripe.com']){
   const origin='https://'+hostname,candidate={...witness(),origin};
   assert.equal(new URL(origin).origin,origin);
   assert.equal(privateOrigin.isBrowserProviderOriginWitness(candidate),false,origin);
   const error=browserFailure('BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER');
   assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,origin),false,origin);
   assert.equal(privateOrigin.browserProviderOrigin(error),null);
  }
 }
});
test('witness validator rejects nonplain inherited extra and accessor fields without getter evaluation',()=>{
 assert.equal(typeof privateOrigin.isBrowserProviderOriginWitness,'function');
 let reads=0;const getter=()=>{reads++;throw Error('secret accessor');};
 const accessor={...witness()};Object.defineProperty(accessor,'origin',{enumerable:true,get:getter});
 const inherited=Object.create(witness());
 const nullPrototype=Object.assign(Object.create(null),witness());
 const hidden={...witness()};Object.defineProperty(hidden,'origin',{value:'https://other.stripe.com',enumerable:false});
 const symbol={...witness(),[Symbol('extra')]:'secret'};
 const hostile=new Proxy({},{get:getter,getPrototypeOf(){throw null;},ownKeys(){throw null;},getOwnPropertyDescriptor(){throw null;}});
 for(const value of [null,undefined,1,'origin',[],new String('origin'),inherited,nullPrototype,accessor,hidden,symbol,{...witness(),extra:'secret'},hostile])assert.equal(privateOrigin.isBrowserProviderOriginWitness(value),false);
 assert.equal(reads,0);
 const input=witness();assert.equal(privateOrigin.isBrowserProviderOriginWitness(input),true);
 const error=browserFailure('BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER');
 assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,input.runId,input.expectedSource,input.origin),true);
 input.origin='https://changed.stripe.com';assert.deepEqual(privateOrigin.browserProviderOrigin(error),witness());
});
