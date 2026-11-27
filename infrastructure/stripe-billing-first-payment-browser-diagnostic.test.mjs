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
test('CRes form checkpoints cross the acceptance boundary as fixed codes only',()=>{
 const prefix='BROWSER_CHALLENGE_CRES_FORM';
 for(const code of [prefix,...['PARSE','DEADLINE','DUPLICATE_ATTRIBUTE','BASE','OWNER','MISSING','MULTIPLE',
  'METHOD','TARGET','ENCODING','ACTION','CONTROL','FIELD','DUPLICATE_FIELD','REQUIRED_CRES','SIZE','LITERAL_MARKUP',
  'MISSING_INLINE','MISSING_INLINE_DOM','MISSING_INLINE_MARKUP','MISSING_EXTERNAL','MISSING_TEMPLATE','MISSING_HANDLER','MISSING_MIXED',
  'EXTERNAL_COUNT','EXTERNAL_COUNT_MULTIPLE_SOURCES','EXTERNAL_COUNT_RECOGNIZED_JS_COMPANION','EXTERNAL_COUNT_UNSUPPORTED_COMPANION',
  'EXTERNAL_COMPANION_PARENT','EXTERNAL_COMPANION_TAG','EXTERNAL_COMPANION_TYPE_MISSING',
  'EXTERNAL_COMPANION_TYPE_JSON_VARIANT','EXTERNAL_COMPANION_TYPE_JSON_LD','EXTERNAL_COMPANION_TYPE_IMPORTMAP',
  'EXTERNAL_COMPANION_TYPE_SPECULATION_RULES','EXTERNAL_COMPANION_TYPE_OTHER','EXTERNAL_COMPANION_HANDLER',
  'EXTERNAL_COMPANION_ATTRIBUTE_ID','EXTERNAL_COMPANION_ATTRIBUTE_NONCE','EXTERNAL_COMPANION_ATTRIBUTES',
  'EXTERNAL_COMPANION_JSON_EMPTY','EXTERNAL_COMPANION_JSON_INVALID',
  'EXTERNAL_COMPANION_JSON_BOM','EXTERNAL_COMPANION_JSON_HTML_COMMENT','EXTERNAL_COMPANION_JSON_HTML_QUOTES',
  'EXTERNAL_COMPANION_JSON_PERCENT','EXTERNAL_COMPANION_JSON_BASE_SIXTY_FOUR','EXTERNAL_COMPANION_JSON_CONTROL',
  'EXTERNAL_COMPANION_JSON_ESCAPE','EXTERNAL_COMPANION_JSON_INCOMPLETE','EXTERNAL_COMPANION_JSON_PROPERTY',
  'EXTERNAL_COMPANION_JSON_DELIMITER','EXTERNAL_COMPANION_JSON_NUMBER','EXTERNAL_COMPANION_JSON_TRAILING',
  'EXTERNAL_COMPANION_JSON_FORMAT_LIMIT','EXTERNAL_COMPANION_JSON_FORMAT_BASE_TEXT','EXTERNAL_COMPANION_JSON_FORMAT_BASE_BYTES',
  'EXTERNAL_COMPANION_JSON_FORMAT_BOM','EXTERNAL_COMPANION_JSON_FORMAT_COMMENT','EXTERNAL_COMPANION_JSON_FORMAT_MARKUP',
  'EXTERNAL_COMPANION_JSON_FORMAT_ENTITY','EXTERNAL_COMPANION_JSON_FORMAT_PERCENT','EXTERNAL_COMPANION_JSON_FORMAT_ESCAPE',
  'EXTERNAL_COMPANION_JSON_FORMAT_QUOTED','EXTERNAL_COMPANION_JSON_FORMAT_OBJECT','EXTERNAL_COMPANION_JSON_FORMAT_ARRAY',
  'EXTERNAL_COMPANION_JSON_FORMAT_SPACE','EXTERNAL_COMPANION_JSON_FORMAT_TEXT','EXTERNAL_COMPANION_JSON_FORMAT_OTHER',
  ...['DATA_URI','URI','FORM','ASSIGN_JSON','ASSIGNMENT','CALL_JSON','CALL','JS_LITERAL','NUMERIC','BASE_SHAPE',
   'IDENTIFIER','OTHER'].map(shape=>'EXTERNAL_COMPANION_JSON_TEXT_'+shape),
  'EXTERNAL_PARENT','EXTERNAL_TAG','EXTERNAL_TYPE','EXTERNAL_ATTRIBUTES','EXTERNAL_TEXT','EXTERNAL_URL',
  'EXTERNAL_LAYOUT_TAG','EXTERNAL_LAYOUT_ATTRIBUTES','EXTERNAL_LAYOUT_TEXT','EXTERNAL_LAYOUT_HANDLER',
 ].map(reason=>prefix+'_'+reason)]){
  const error=privateOrigin.acceptanceBrowserFailure(code);
  assert.equal(browserDiagnostic(error,'ACCEPTANCE'),code);
  assert.equal(String(error),'Error: billing_BROWSER');
  assert.equal(JSON.stringify(error),'{}');
 }
 for(const raw of [prefix+'_TARGET_private',prefix+'_FIELD_CRES',prefix+'_ACTION https://fixture.test/private',
  prefix+'_MISSING_INLINE_DOM private fixture script',prefix+'_MISSING_EXTERNAL /private-fixture.js',
  prefix+'_EXTERNAL_URL /private-fixture.js',prefix+'_EXTERNAL_COUNT_MULTIPLE_SOURCES /private-fixture.js',
  prefix+'_EXTERNAL_COUNT_RECOGNIZED_JS_COMPANION '+JSON.stringify({private:'script body'}),
  prefix+'_EXTERNAL_COMPANION_TYPE_OTHER text/private-example',prefix+'_EXTERNAL_COMPANION_ATTRIBUTE_NONCE_PRIVATE',
  prefix+'_EXTERNAL_COMPANION_JSON_INVALID {"private":"example"}',
  prefix+'_EXTERNAL_COMPANION_JSON_HTML_COMMENT_PRIVATE',prefix+'_EXTERNAL_COMPANION_JSON_ESCAPE at position 61',
  prefix+'_EXTERNAL_COMPANION_JSON_BASE64',
  prefix+'_EXTERNAL_COMPANION_JSON_FORMAT_MARKUP <private-example>',prefix+'_EXTERNAL_COMPANION_JSON_FORMAT_TEXT_PRIVATE',
  prefix+'_EXTERNAL_COMPANION_JSON_TEXT_ASSIGN_JSON_PRIVATE',prefix+'_EXTERNAL_COMPANION_JSON_TEXT_CALL_JSON fixture({})',
  prefix+'_EXTERNAL_LAYOUT_ATTRIBUTES custom-data']){
  assert.equal(browserDiagnostic(privateOrigin.acceptanceBrowserFailure(raw),'ACCEPTANCE'),null);
 }
});
test('CRes external script checkpoints reveal only a fixed reason',()=>{
 const prefix='BROWSER_CHALLENGE_CRES_SCRIPT_';
 for(const suffix of ['RECEIPT','READ','SIZE','UTF8','UNSUPPORTED','UNSUPPORTED_DOM','HEAD_INSERTION']){
  const code=prefix+suffix;
  const error=privateOrigin.acceptanceBrowserFailure(code);
  assert.equal(browserDiagnostic(error,'ACCEPTANCE'),code);
  assert.equal(JSON.stringify(error),'{}');
  assert.equal(browserDiagnostic(privateOrigin.acceptanceBrowserFailure(code+' /private-script.js'),'ACCEPTANCE'),null);
 }
});
test('CRes text signatures cross the boundary with five closed positions',()=>{
 const prefix='BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_TEXT_SHAPE_';
 for(const signature of ['ADS','JMBODTP','NPEBM','SL','AUC'].reduce(
  (prefixes,axis)=>prefixes.flatMap(base=>[...axis].map(letter=>base+letter)),[''])){
  const code=prefix+signature,error=privateOrigin.acceptanceBrowserFailure(code);
  assert.equal(browserDiagnostic(error,'ACCEPTANCE'),code);
  assert.equal(JSON.stringify(error),'{}');
 }
 for(const invalid of ['XJNSA','AXNSA','AJXSA','AJNXA','AJNSX','AJNS','AJNSAA','AJNSA\n','aj nsa',
  'AJNSA_PRIVATE','AJNSA private/name?value=fixture','AJNSA\0']){
  const code=prefix+invalid;
  assert.equal(privateOrigin.isAcceptanceBrowserDiagnostic(code),false);
  assert.equal(browserDiagnostic(privateOrigin.acceptanceBrowserFailure(code),'ACCEPTANCE'),null);
 }
});
test('provider boundaries are finite private provenance without payload',()=>{
 for(const code of [
  'BROWSER_PROVIDER_HEADERS','BROWSER_PROVIDER_CURRENT','BROWSER_PROVIDER_REDIRECT','BROWSER_PROVIDER_USERINFO','BROWSER_PROVIDER_AUTH','BROWSER_PROVIDER_ORIGIN_SCHEME','BROWSER_PROVIDER_ORIGIN_PORT','BROWSER_PROVIDER_ORIGIN_STRIPE_COM','BROWSER_PROVIDER_ORIGIN_STRIPE_COM_M','BROWSER_PROVIDER_ORIGIN_STRIPE_COM_R','BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER','BROWSER_PROVIDER_ORIGIN_STRIPE_NETWORK','BROWSER_PROVIDER_ORIGIN_OTHER','BROWSER_PROVIDER_NAVIGATION','BROWSER_PROVIDER_STATIC_FRAME','BROWSER_PROVIDER_STATIC_REQUEST','BROWSER_PROVIDER_STATIC_RESPONSE','BROWSER_PROVIDER_FALLBACK',
  'BROWSER_PROVIDER_STATIC_REQUEST_PHASE',
  'BROWSER_PROVIDER_STATIC_REQUEST_METHOD',
  'BROWSER_PROVIDER_STATIC_REQUEST_BODY',
  'BROWSER_PROVIDER_STATIC_REQUEST_NAVIGATION',
  'BROWSER_PROVIDER_STATIC_REQUEST_NAVIGATION_MAIN_FRAME',
  'BROWSER_PROVIDER_STATIC_REQUEST_NAVIGATION_CHECKOUT_CHILD',
  'BROWSER_PROVIDER_STATIC_REQUEST_NAVIGATION_STRIPE_PARENT',
  'BROWSER_PROVIDER_STATIC_REQUEST_NAVIGATION_OTHER_PARENT',
  'BROWSER_PROVIDER_STATIC_REQUEST_NAVIGATION_FRAME_UNAVAILABLE',
  'BROWSER_PROVIDER_STATIC_NAVIGATION_BLOCK',
  'BROWSER_PROVIDER_STATIC_REQUEST_URL',
  'BROWSER_PROVIDER_STATIC_REQUEST_CREDENTIALS',
  'BROWSER_PROVIDER_STATIC_REQUEST_RESOURCE_TYPE',
  'BROWSER_PROVIDER_STATIC_REQUEST_SUFFIX',
 ]){
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
test('private issuer relation is fixed and scoped to the 3DS refusal witness',()=>{
 const origin='https://unlisted-fixture.stripe.com',checkpoint='BROWSER_PROVIDER_ORIGIN_STRIPE_COM';
 const fresh=()=>privateOrigin.acceptanceBrowserFailure(checkpoint);
 const relation='ISSUER_TRANSACTION_MATCH';
 const error=fresh();
 assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,origin,relation),true);
 const witness=privateOrigin.browserProviderOrigin(error);
 assert.deepEqual(witness,{runId:originRun,expectedSource:originSource,origin,checkpoint,issuerRelation:relation});
 assert.equal(privateOrigin.isBrowserProviderOriginWitness(witness),true);
 assert.equal(privateOrigin.isBrowserProviderOriginWitness({...witness,checkpoint:'BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER'}),false);
 assert.equal(privateOrigin.rememberBrowserProviderOrigin(fresh(),originRun,originSource,origin,'private canary'),false);
 assert.equal(privateOrigin.isBrowserProviderOriginWitness({...witness,issuerRelation:42}),false);
 assert.equal(privateOrigin.isBrowserProviderOriginWitness({...witness,get issuerRelation(){assert.fail('must not read accessor');}}),false);
 assert.equal(JSON.stringify(error),'{}');
});
test('only the acceptance Stripe.com refusal retains its previously unrecognized origin',()=>{
 const checkpoint='BROWSER_PROVIDER_ORIGIN_STRIPE_COM',origin='https://m.stripe.com';
 const fresh=()=>privateOrigin.acceptanceBrowserFailure(checkpoint);
 for(const invalid of ['https://hooks.stripe.com','https://child.js.stripe.com','https://r.stripe.com',
  'https://merchant-ui-api.stripe.com','https://m.stripe.com:444','https://user@m.stripe.com',
  'https://m.stripe.com/path?secret=canary','https://m.stripe.com#canary','https://notstripe.com','https://m.stripe.com.']){
  assert.equal(privateOrigin.rememberBrowserProviderOrigin(fresh(),originRun,originSource,invalid),false);
 }
 for(const error of [browserFailure(checkpoint),browserFailure(checkpoint,'METHOD'),new Error(checkpoint)]){
  assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,origin),false);
 }
 const error=fresh();
 assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,origin),true);
 assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,'https://later.stripe.com'),false);
 const witness=privateOrigin.browserProviderOrigin(error);
 assert.deepEqual(witness,{runId:originRun,expectedSource:originSource,origin,checkpoint});
 assert.equal(privateOrigin.isBrowserProviderOriginWitness(witness),true);
 assert.equal(JSON.stringify(error),'{}');
});
test('method phase provenance and its private in-memory origin retain independent contracts',()=>{
 const code='BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER',error=browserFailure(code,'METHOD');
 assert.equal(browserDiagnostic(error,'METHOD'),code);
 assert.equal(browserDiagnostic(error,'INITIAL'),null);
 assert.equal(browserDiagnostic(browserFailure(code),'METHOD'),null);
 assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,'https://other.stripe.com'),true);
 assert.deepEqual(privateOrigin.browserProviderOrigin(error),{runId:originRun,expectedSource:originSource,origin:'https://other.stripe.com'});
 assert.equal(browserDiagnostic(browserFailure('BROWSER_METHOD_CONFIRM','METHOD')),'BROWSER_METHOD_CONFIRM');
 assert.equal(browserDiagnostic(browserFailure('BROWSER_METHOD_CONFIRM')),null);
 assert.equal(JSON.stringify(error),'{}');
});
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

const otherCheckpoint='BROWSER_PROVIDER_ORIGIN_OTHER';
test('private network evidence requires the matching category, a canonical DNS origin and original error',()=>{
 const checkpoint='BROWSER_PROVIDER_ORIGIN_STRIPE_NETWORK',origin='https://one.stripe.network';
 const candidate={runId:originRun,expectedSource:originSource,checkpoint,origin};
 const error=browserFailure(checkpoint);
 assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,origin),true);
 assert.deepEqual(privateOrigin.browserProviderOrigin(error),candidate);
 assert.equal(privateOrigin.isBrowserProviderOriginWitness(candidate),true);
 assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,'https://two.stripe.network'),false);
 assert.equal(privateOrigin.rememberBrowserProviderOrigin(new Error(checkpoint),originRun,originSource,origin),false);
 assert.equal(privateOrigin.isBrowserProviderOriginWitness({...witness(),origin}),false);
 assert.equal(privateOrigin.isBrowserProviderOriginWitness({...candidate,checkpoint:otherCheckpoint}),false);
 assert.equal(JSON.stringify(error),'{}');
 for(const invalid of ['https://stripe.network.evil.test','https://notstripe.network','https://other.stripe.com','https://stripe.network.',
   'https://other.stripe.network:443','https://other.stripe.network:444','https://user@other.stripe.network','https://other.stripe.network/private',
   'https://other.stripe.network?private','https://other.stripe.network#private','https://bad..stripe.network','https://_bad.stripe.network',
   'https://-bad.stripe.network','https://'+'a'.repeat(64)+'.stripe.network']){
  assert.equal(privateOrigin.isBrowserProviderOriginWitness({...candidate,origin:invalid}),false,invalid);
 }
});
test('a genuine general OTHER error retains its exact private origin only once',()=>{
 for(const origin of ['https://outside.test','https://js.stripe.com.evil.test','https://stripe.network.',
   'https://js.stripe.com.','https://xn--bcher-kva.test','https://192.0.2.1','https://[2001:db8::1]']){
  const error=browserFailure(otherCheckpoint);
  const candidate={runId:originRun,expectedSource:originSource,origin,checkpoint:otherCheckpoint};
  assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,origin),true,origin);
  assert.equal(privateOrigin.isBrowserProviderOriginWitness(candidate),true);
  assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,'https://later.test'),false);
  finishBrowserCleanup(error,true);
  const saved=privateOrigin.browserProviderOrigin(error);
  assert.deepEqual(saved,candidate);assert.ok(Object.isFrozen(saved));
  assert.equal(privateOrigin.browserProviderOrigin(new Error(otherCheckpoint)),null);
  assert.equal(browserDiagnostic(error),otherCheckpoint);assert.equal(JSON.stringify(error),'{}');
  assert.equal(error.stack.includes(origin),false);
 }
});
test('general OTHER evidence rejects raw URLs, other refusal categories and foreign errors',()=>{
 for(const origin of ['https://stripe.com','https://other.stripe.com','https://js.stripe.com',
   'https://m.stripe.com','https://stripe.network','https://other.stripe.network',
   'http://outside.test','https://outside.test:8443','https://outside.test:443','https://OUTSIDE.test',
   'https://user:pass@outside.test','https://outside.test/','https://outside.test/path',
   'https://outside.test?private','https://outside.test#private','https://outside.test\n',
   'https://'+'a'.repeat(255),'https://[0:0:0:0:0:0:0:1]','null','']){
  const error=browserFailure(otherCheckpoint);
  assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,origin),false,origin);
  assert.equal(privateOrigin.browserProviderOrigin(error),null);
  assert.equal(privateOrigin.isBrowserProviderOriginWitness({...witness(),origin,checkpoint:otherCheckpoint}),false);
 }
 const otherOrigin='https://outside.test';
 for(const error of [new Error(otherCheckpoint),Object.create(browserFailure(otherCheckpoint)),browserFailure('BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER')]){
  assert.equal(privateOrigin.rememberBrowserProviderOrigin(error,originRun,originSource,otherOrigin),false);
 }
 const tagged={...witness(),origin:otherOrigin,checkpoint:otherCheckpoint};
 const getter={...tagged};let reads=0;Object.defineProperty(getter,'checkpoint',{enumerable:true,get(){reads++;return otherCheckpoint;}});
 for(const invalid of [{...witness(),origin:otherOrigin},{...tagged,checkpoint:undefined},{...tagged,checkpoint:'BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER'},
   {...tagged,runId:'unrelated'},{...tagged,expectedSource:'bad'},{...tagged,extra:'private'},getter]){
  assert.equal(privateOrigin.isBrowserProviderOriginWitness(invalid),false);
 }
 assert.equal(reads,0);
});
