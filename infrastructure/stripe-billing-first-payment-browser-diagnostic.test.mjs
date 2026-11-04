import test, {before, after} from 'node:test';
const originalFetch=globalThis.fetch;let transportCalls=0;
before(()=>{globalThis.fetch=()=>{transportCalls++;throw new Error('offline test transport blocked');};});
after(()=>{globalThis.fetch=originalFetch;assert.equal(transportCalls,0);});
import assert from 'node:assert/strict';
import {browserFailure, browserDiagnostic, browserCleanupConfirmed, finishBrowserCleanup, isBrowserDiagnostic} from './stripe-billing-first-payment-browser-diagnostic.mjs';

const codes=['BROWSER_CONTEXT','BROWSER_PAGE','BROWSER_SETUP','BROWSER_NAVIGATION','BROWSER_CONFIGURE','BROWSER_OTP_REQUEST','BROWSER_OTP_VERIFY','BROWSER_PREPARE','BROWSER_ACTION_BRIDGE','BROWSER_SESSION_BRIDGE','BROWSER_CONFIRM_BRIDGE','BROWSER_WAIT_READY','BROWSER_CARD_NUMBER','BROWSER_CARD_EXPIRY','BROWSER_CARD_CVC','BROWSER_POSTAL_CHECK','BROWSER_POSTAL_FILL','BROWSER_PAY_CLICK','BROWSER_WAIT_OBSERVE','BROWSER_WAIT_OBSERVE_LAST_OBSERVED_SUBMIT','BROWSER_WAIT_OBSERVE_LAST_OBSERVED_TOKEN','BROWSER_CLOSE','BROWSER_CONFIRM_GUARD','BROWSER_CONFIRM_STATE','BROWSER_CONFIRM_SUBMIT_VALIDATION','BROWSER_CONFIRM_SUBMIT','BROWSER_CONFIRM_TOKEN_VALIDATION','BROWSER_CONFIRM_TOKEN','BROWSER_CONFIRM_CLIENT','BROWSER_CONFIRM_NEXT_ACTION','BROWSER_CONFIRM_RETIRED','BROWSER_CONFIRM_RECEIPT'];
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
test('foreign values and unreadable proxies cannot supply provenance',()=>{
 let reads=0;const hostile=new Proxy({diagnostic:'BROWSER_PAGE',cleanupConfirmed:true},{get(){reads++;throw null;},getPrototypeOf(){reads++;throw null;},ownKeys(){reads++;throw null;}});
 for(const e of [hostile,null,undefined,1,'BROWSER_PAGE',{code:'BROWSER_PAGE'},new Error('BROWSER_PAGE'),Object.create(browserFailure('BROWSER_PAGE'))]){assert.equal(browserDiagnostic(e),null);assert.equal(browserCleanupConfirmed(e),false);finishBrowserCleanup(e,true);}
 for(const code of ['BROWSER_UNKNOWN','BROWSER_PAGE_EXTRA','PREFIX_BROWSER_PAGE','BROWSER_PAGE1','BROWSER_PAGE\n','sk_test_SECRET',hostile]){assert.equal(isBrowserDiagnostic(code),false);assert.equal(browserDiagnostic(browserFailure(code)),null);}
 assert.equal(reads,0);
});
