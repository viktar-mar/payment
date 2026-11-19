import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import type {TestCase,TestResult,FullResult} from '@playwright/test/reporter';
import type {BillingLimits} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
import type {BillingFirstPaymentPort} from '../../../../infrastructure/stripe-billing-first-payment.mjs';
// @ts-ignore Node's source-only offline test runner requires the explicit extension.
import FailedRenewalReporter from './stripe-billing-failed-renewal-reporter.ts';
import {browserDiagnostic,browserCleanupConfirmed} from '../../../../infrastructure/stripe-billing-first-payment-browser-diagnostic.mjs';
import * as privateOrigin from '../../../../infrastructure/stripe-billing-first-payment-browser-diagnostic.mjs';
import {createOwnedBillingFailedRenewal,formatBillingFailedRenewalDiagnostic} from '../../../../infrastructure/stripe-billing-first-payment.mjs';

// The two source modules have only type imports from Playwright and the SDK.
// Memory fixtures exercise the held journey without importing either runtime.
const source = (relative:string) => new URL(relative,import.meta.url);
const read = (relative:string) => readFile(source(relative),'utf8');
const id = (n:number) => '11111111-2222-4333-8444-' + String(n).padStart(12,'0');
const demo = 'http://127.0.0.1:14173', backend = 'http://127.0.0.1:18080';
const path = '/billing-failed-renewal.html';
const token = 'T'.repeat(43), otp = '123456';
const actionSecret = 'bga1_' + 'S'.repeat(43);
const paySecret = 'bga1_' + 'I'.repeat(43);
const capability = 'bca2_' + 'C'.repeat(43);
const nowLimits = () => ({deadline:Date.now()+240000});
const inertFetch = async ():Promise<never> => {throw Error('external transport forbidden');};
const diagnostic='owned_failed_renewal_diagnostic stage=LISTENER checkpoint=PERSIST_FINAL_STORE cleanup=UNCONFIRMED witness=LISTENER_STOP';
const terminalAnnotation={type:'owned-failed-renewal-terminal',description:diagnostic};
const browserTerminal='owned_failed_renewal_diagnostic stage=BROWSER checkpoint=BROWSER_CARD_NUMBER cleanup=CONFIRMED witness=NONE';
const loginHandoff='Authorize the isolated Sandbox CLI at https://access.stripe.com/stripecli/oauth2/device using code ABCD-EFGH. Keep this terminal open.';
function reporterCase(change:Record<string,unknown>={}):TestCase {
 return {title:'OWNED_FAILED_RENEWAL_GRACE_RECOVERY',expectedStatus:'passed',location:{file:fileURLToPath(source('../stripe-billing-failed-renewal.spec.ts')),line:1,column:1},parent:{project:()=>({name:'owned-failed-renewal-manual-chrome'})},annotations:[],...change} as unknown as TestCase;
}
function reporterResult(change:Record<string,unknown>={}):TestResult {
 return {status:'failed',retry:0,annotations:[terminalAnnotation],stdout:['raw stdout canary'],stderr:['raw stderr canary'],errors:[{message:'raw error canary'}],attachments:[{name:'canary',contentType:'text/plain',body:Buffer.from('raw attachment canary')}],...change} as unknown as TestResult;
}
async function captureReporter(body:(reporter:FailedRenewalReporter)=>void):Promise<string> {
 const write=process.stdout.write;const chunks:string[]=[];
 process.stdout.write=((chunk:unknown)=>{chunks.push(String(chunk));return true;}) as typeof write;
 try{body(new FailedRenewalReporter());}finally{process.stdout.write=write;}
 return chunks.join('');
}
for(const kind of ['trusted','malformed','foreign','duplicate','static','stage-mismatch'] as const)test('actual reporter browser terminal delivery '+kind,async()=>{
 const annotation={type:'owned-failed-renewal-terminal',description:browserTerminal};
 const output=await captureReporter(r=>{
  const testCase=reporterCase(),result=reporterResult({annotations:[]});
  if(kind==='static')testCase.annotations=[annotation];
  r.onTestBegin(testCase,result);r.onStdOut(browserTerminal+'\nraw canary\n');r.onStdErr();r.onError();
  const delivered=kind==='malformed'?{...annotation,description:browserTerminal+' canary'}:kind==='foreign'?{...annotation,type:'foreign'}:kind==='stage-mismatch'?{...annotation,description:browserTerminal.replace('BROWSER_CARD_NUMBER','PERSIST_ACK')}:annotation;
  result.annotations=kind==='duplicate'?[delivered,delivered]:[delivered];testCase.annotations=[...result.annotations];
  r.onTestEnd(testCase,result);r.onTestEnd(testCase,result);r.onEnd({status:'failed'} as FullResult);
 });
 assert.equal(output.split('\n').filter(v=>v===browserTerminal).length,kind==='trusted'?1:0);assert.doesNotMatch(output,/canary|raw error|attachment/);
});
test('actual reporter emits one trusted post-handoff terminal diagnostic and suppresses raw channels',async()=>{
 const output=await captureReporter(r=>{
  const testCase=reporterCase({annotations:[{type:'foreign',description:'foreign annotation canary'}]}),result=reporterResult({annotations:[]});
  r.onTestBegin(testCase,result);
  r.onStdOut(diagnostic+'\nraw before canary\n'+loginHandoff+'\n');
  r.onStdOut(diagnostic+'\nraw after canary\n');r.onStdErr();r.onError();
  testCase.annotations=[...testCase.annotations,terminalAnnotation];result.annotations=[...testCase.annotations];r.onTestEnd(testCase,result);
  r.onTestEnd(reporterCase(),reporterResult());r.onEnd({status:'failed'} as FullResult);
 });
 assert.equal(output.split('\n').filter(v=>v===diagnostic).length,1);assert.ok(output.startsWith(loginHandoff+'\n'));
 assert.doesNotMatch(output,/canary|attachment|raw error/);assert.match(output,/owned_failed_renewal_not_accepted/);assert.match(output,/full5b=NOT_ACCEPTED/);
});
for(const [name,caseChange,resultChange] of [
 ['foreign title',{title:'other'},{}],['foreign file',{location:{file:'/foreign',line:1,column:1}},{}],
 ['foreign project',{parent:{project:()=>({name:'other'})}},{}],['unexpected expected status',{expectedStatus:'failed'},{}],
 ['retried',{}, {retry:1}],['passed',{}, {status:'passed'}],
 ['case-only annotation',{annotations:[terminalAnnotation]},{annotations:[]}],
 ['foreign annotation',{}, {annotations:[{...terminalAnnotation,type:'foreign'}]}],
 ['malformed annotation',{}, {annotations:[{...terminalAnnotation,description:diagnostic+' canary'}]}],
 ['duplicate annotation',{}, {annotations:[terminalAnnotation,terminalAnnotation]}],
 ['nonstring annotation',{}, {annotations:[{...terminalAnnotation,description:{toString:()=>diagnostic}}]}],
 ['contradictory cleanup',{}, {annotations:[{...terminalAnnotation,description:diagnostic.replace('UNCONFIRMED','CONFIRMED')}]}],
 ['unknown checkpoint',{}, {annotations:[{...terminalAnnotation,description:diagnostic.replace('PERSIST_FINAL_STORE','PERSIST_CANARY')}]}],
] as const)test('actual reporter suppresses '+name+' terminal annotation',async()=>{
 const output=await captureReporter(r=>{
  const testCase=reporterCase(caseChange),result=reporterResult({...resultChange,annotations:[]});
  r.onTestBegin(testCase,result);result.annotations=reporterResult(resultChange).annotations;
  r.onTestEnd(testCase,result);r.onEnd({status:'failed'} as FullResult);
 });
 assert.doesNotMatch(output,/owned_failed_renewal_diagnostic|canary/);
});
test('actual reporter rejects a reserved static annotation copied into both end arrays on setup failure',async()=>{
 const output=await captureReporter(r=>{
  const testCase=reporterCase({annotations:[terminalAnnotation]}),result=reporterResult({annotations:[]});
  if(typeof r.onTestBegin==='function')r.onTestBegin(testCase,result);
  // Playwright copies declared annotations into testInfo before setup and both
  // end arrays when the setup failure is delivered, without running owner close.
  result.annotations=[...testCase.annotations];testCase.annotations=[...result.annotations];
  r.onTestEnd(testCase,result);r.onEnd({status:'failed'} as FullResult);
 });
 assert.doesNotMatch(output,/owned_failed_renewal_diagnostic|canary/);assert.match(output,/owned_failed_renewal_not_accepted/);
});
test('actual reporter rejects foreign target at begin despite matching end identity',async()=>{
 const output=await captureReporter(r=>{
  const testCase=reporterCase({title:'foreign case'}),result=reporterResult({annotations:[]});
  r.onTestBegin(testCase,result);
  testCase.title='OWNED_FAILED_RENEWAL_GRACE_RECOVERY';result.annotations=[terminalAnnotation];testCase.annotations=[terminalAnnotation];
  r.onTestEnd(testCase,result);r.onEnd({status:'failed'} as FullResult);
 });
 assert.doesNotMatch(output,/owned_failed_renewal_diagnostic|canary/);
});
for(const boundary of ['absent begin','different case','different result','repeated begin','reserved result baseline','malformed reserved case baseline','retry at begin'])
test('actual reporter rejects terminal delivery with '+boundary,async()=>{
 const output=await captureReporter(r=>{
  const testCase=reporterCase(),result=reporterResult({annotations:[]});
  if(boundary==='reserved result baseline')result.annotations=[terminalAnnotation];
  if(boundary==='malformed reserved case baseline')testCase.annotations=[{type:'owned-failed-renewal-terminal',description:'malformed canary'}];
  if(boundary==='retry at begin')result.retry=1;
  if(boundary!=='absent begin')r.onTestBegin(boundary==='different case'?reporterCase():testCase,boundary==='different result'?reporterResult({annotations:[]}):result);
  if(boundary==='repeated begin')r.onTestBegin(testCase,result);
  result.retry=0;result.annotations=[terminalAnnotation];testCase.annotations=[terminalAnnotation];
  r.onTestEnd(testCase,result);r.onEnd({status:'failed'} as FullResult);
 });
 assert.doesNotMatch(output,/owned_failed_renewal_diagnostic|canary/);
});
test('actual reporter accepts clean begin and late post-close annotation in both native end arrays once',async()=>{
 const output=await captureReporter(r=>{
  const testCase=reporterCase(),result=reporterResult({annotations:[]});r.onTestBegin(testCase,result);
  result.annotations=[terminalAnnotation];testCase.annotations=[...result.annotations];
  r.onTestEnd(testCase,result);r.onTestEnd(testCase,result);r.onEnd({status:'failed'} as FullResult);
 });
 assert.equal(output.split('\n').filter(v=>v===diagnostic).length,1);assert.doesNotMatch(output,/canary/);
});
test('actual reporter retains the exact single-case success predicate',async()=>{
 for(const [testCase,result,full,accepted] of [
  [reporterCase(),reporterResult({status:'passed',annotations:[]}),{status:'passed'},true],
  [reporterCase(),reporterResult({status:'passed',retry:1,annotations:[]}),{status:'passed'},false],
  [reporterCase(),reporterResult({status:'passed',annotations:[]}),{status:'failed'},false],
 ] as const){
  const output=await captureReporter(r=>{r.onTestEnd(testCase,result);r.onEnd(full as FullResult);});
  assert.equal(output.includes('owned_failed_renewal_result_passed full5b=NOT_ACCEPTED'),accepted);
 }
 const output=await captureReporter(r=>{r.onTestEnd(reporterCase(),reporterResult({status:'passed'}));r.onTestEnd(reporterCase(),reporterResult({status:'passed'}));r.onEnd({status:'passed'} as FullResult);});
 assert.match(output,/owned_failed_renewal_result_not_accepted full5b=NOT_ACCEPTED/);assert.doesNotMatch(output,/owned_failed_renewal_diagnostic/);
});
test('dedicated spec attaches trusted terminal only after finally closes the actual owner',async()=>{
 const entry=await read('../stripe-billing-failed-renewal.spec.ts');
 assert.match(entry,/async\(\{browser\},info\)=>/);
 assert.match(entry,/finally\s*\{\s*controller\.abort\(\);\s*const terminal=await owner\.close\(\);\s*const diagnostic=formatBillingFailedRenewalDiagnostic\(terminal\);\s*if\(diagnostic\)info\.annotations\.push\(\{type:'owned-failed-renewal-terminal',description:diagnostic\}\);\s*\}/);
});

class Control {
  disabled = true; checked = false; hidden = false; value = ''; textContent = '';
  onclick?: (event:unknown)=>unknown;
  async activate() {assert.equal(this.disabled,false);await this.onclick?.({isTrusted:true});}
}
type ProviderProbe={target:string;headers?:Record<string,string>;headerFailure?:'getter'|'reject';redirect?:'throw'|'present';navigation?:'main'|'child'|'throw'|'frame';fallbackFailure?:boolean;hold?:boolean;method?:string;phase?:'initial'|'method'};
async function fixture(change = '',provider?:ProviderProbe[]) {
  let releaseIO!:()=>void,markIO!:()=>void;
  const heldIO=new Promise<void>(resolve=>{releaseIO=resolve;}),ioPending=new Promise<void>(resolve=>{markIO=resolve;});
  let stageHook:((where:string)=>Promise<void>)|undefined;
  const runAbort=new AbortController(),docEvents=new Map<string,Function>();
  const asynchronousStart=change.startsWith('async_');
  let startTask:Promise<unknown>|undefined,releasePreparation!:()=>void,markPreparationPending!:()=>void,markReadyWaiting!:()=>void;
  const preparationHold=new Promise<void>(resolve=>{releasePreparation=resolve;});
  const preparationPending=new Promise<void>(resolve=>{markPreparationPending=resolve;});
  const readyWaiting=new Promise<void>(resolve=>{markReadyWaiting=resolve;});
  const {installFailedRenewalPage} = await import(source('../../src/billing-failed-renewal-page.ts').href);
  const {runFailedRenewalBrowser} = await import(source('./stripe-billing-failed-renewal.ts').href);
  const terminals:unknown[][]=[];
  const counts:string[] = [], replies:any[] = [], controls = new Map<string,Control>();
  let providerFallbacks=0;
  const ctrl = (id:string) => {
    if(!controls.has(id))controls.set(id,Object.assign(new Control(),{id}));
    return controls.get(id)!;
  };
  const body = {dataset:{phase:'NEW'}};
  const doc = {body,visibilityState:'visible',querySelector:(s:string)=>ctrl(s.slice(1)),
    querySelectorAll:()=>[...controls.values()],addEventListener:(n:string,f:Function)=>docEvents.set(n,f)};
  const windowEvents=new Map<string,Function>();
  const w:any = {addEventListener:(n:string,f:Function)=>windowEvents.set(n,f)};
  w.top = w;
  const globals = {window:w,document:doc,location:{origin:demo,href:demo+path},HTMLButtonElement:Control,fetch:inertFetch};
  const original = new Map(Object.keys(globals).map(k=>[k,Object.getOwnPropertyDescriptor(globalThis,k)]));
  for(const [k,v] of Object.entries(globals))Object.defineProperty(globalThis,k,{configurable:true,value:v});
  const restore = () => {for(const [k,d] of original) {if(d)Object.defineProperty(globalThis,k,d);else Reflect.deleteProperty(globalThis,k);}};
  const expires = new Date(Date.now()+300000).toISOString();
  const identity = {run_id:id(1),account_id:id(2),site_id:id(3),customer_id:id(4),price_id:id(5),subscription_id:id(6),operation_id:id(7)};
  const action = (secret:string) => ({kind:'PROVIDER_CLIENT',secret,expires_at:expires});
  const prepared = {...identity,phase:'PREPARED',code:'OK',action:action(paySecret),session:{protocol:'SERVER_CONFIRM_V2',provider:'stripe',flow:'PAYMENT',publishable_key:'pk_test_fixture',client_secret:'pi_test_secret_fixture',attempt_id:id(8),capability,action_token:null,expires_at:expires}};
  const selected = {protocol:'BROWSER_CONFIRM_V1',provider:'stripe',flow:'SETUP',publishable_key:'pk_test_fixture',client_secret:'seti_test_secret_fixture',action_token:actionSecret,attempt_id:null,capability:null,expires_at:expires};
  const session = {origin:demo,siteId:id(3),customer:{id:id(4)},sessionToken:token,expiresAt:expires};
  const base = (op:string)=>backend+'/api/v1/customer/subscriptions/'+id(6)+'/operations/'+op;
  const otpUrl = backend+'/api/v1/public/customer-auth/email-otp/challenges';
  let url = 'about:blank', closed = 0;
  const frame = {url:()=>url}, callbacks = new Map<string,Function>(),bindings=new Map<string,Function>();
  let routeHandler:Function;
  async function probeProvider(probe:ProviderProbe) {
    let denied=false;
    const fail=()=>{throw Error('secret_fixture canary');};
    const req={url:()=>probe.target,method:()=>probe.method??'GET',
      get allHeaders(){if(probe.headerFailure==='getter')fail();return async()=>{if(probe.headerFailure==='reject')fail();if(probe.hold){markIO();await heldIO;}return probe.headers??{};};},
      redirectedFrom:()=>{if(probe.redirect==='throw')fail();return probe.redirect==='present'?{}:null;},
      isNavigationRequest:()=>{if(probe.navigation==='throw')fail();return Boolean(probe.navigation);},
      frame:()=>{if(probe.navigation==='frame')fail();return probe.navigation==='child'?{}:frame;},
      resourceType:()=> 'other',postData:()=>null};
    await routeHandler({request:()=>req,abort:async()=>{denied=true;},fallback:async()=>{providerFallbacks++;if(probe.fallbackFailure)fail();},fulfill:async()=>assert.fail('provider fulfillment')});
    if(denied)throw Error('denied mock request');
  }
  async function request(target:string,method:string,data?:any) {
    const bridge=target.endsWith('/action')?'action':target.endsWith('/confirmation-session')?'session':target.endsWith('/confirm')?'confirm':'';
    if(bridge && method!=='OPTIONS' && change===bridge+'_preflight_delivery')await request(target,'OPTIONS');
    let contents=data===undefined?null:JSON.stringify(data);
    const headers:Record<string,string>={origin:demo,...(data?{'content-type':'application/json'}:{}),
      ...(!target.startsWith(otpUrl)&&target.startsWith(backend)?{authorization:'Bearer '+token,...(method==='POST'?{'idempotency-key':crypto.randomUUID()}:{})}:{})};
    if(method==='OPTIONS'){
      delete headers.authorization;
      headers['access-control-request-method']=bridge==='action'?'GET':'POST';
      headers['access-control-request-headers']='authorization,content-type,idempotency-key';
    }
    if(change==='action_provider_header'&&target==='https://js.stripe.com/v3/')headers.authorization='Bearer '+token;
    if(change==='action_asset_header'&&target===demo+'/assets/inert.js')headers.authorization='Bearer '+token;
    if(bridge && change===bridge+'_early_header')headers.cookie='fixture=refused';
    let requestFrame=frame;
    if(target.endsWith('/action-confirmations')) {
      if(change==='frame')requestFrame={url:()=>url};
      if(change==='origin')headers.origin='http://localhost:14173';
      if(change==='identity')headers.authorization='Bearer '+'F'.repeat(43);
      if(change==='operation')target=target.replace(id(9),id(7));
      if(change==='body')contents=JSON.stringify({action_token:paySecret});
      if(change==='extra')contents=JSON.stringify({action_token:actionSecret,target:'other'});
      if(change==='duplicate_key')contents='{"action_token":"foreign","action_token":'+JSON.stringify(actionSecret)+'}';
    }
    let reply:any,denied=false;
    let headerReads=0;
    const req={url:()=>target,method:()=>method,allHeaders:async()=>{
      headerReads++;
      if(change==='hold_initial_'+bridge && headerReads===2){markIO();await heldIO;}
      return headers;
    },frame:()=>requestFrame,postData:()=>contents,
      redirectedFrom:()=>null,isNavigationRequest:()=>false,resourceType:()=> 'fetch'};
    await routeHandler({request:()=>req,abort:async()=>{denied=true;},fallback:async()=>{assert.ok(target==='https://js.stripe.com/v3/'||target===otpUrl||target===otpUrl+'/'+id(10)+'/verify');},
      fulfill:async(r:any)=>{if((method==='OPTIONS'&&change===bridge+'_preflight_delivery')||(change==='action_delivery'&&target.endsWith('/action'))||(change==='session_delivery'&&target.endsWith('/confirmation-session')))throw Error('secret_fixture canary');assert.equal(r.headers['Cache-Control'],'no-store');assert.equal(r.headers['Access-Control-Allow-Origin'],demo);reply=r;replies.push(r);}});
    if(denied)throw Error('denied mock request');
    return reply;
  }
  const subscriptions={action:async(input:any)=>{
      assert.strictEqual(input.session,session);
      if(change==='action_forged'){
        const terminal=bindings.get('__failedRenewalTerminal')!;
        for(const marker of ['BROWSER_INITIAL_SESSION','BROWSER_INITIAL_MOUNT','BROWSER_METHOD_MOUNT','BROWSER_UNKNOWN',{code:'BROWSER_INITIAL_ACTION'}])await terminal({page,frame},'STOPPED',marker);
        assert.equal(runAbort.signal.aborted,false);
      }
      if(change==='page_action')throw Error('secret_fixture canary');
      if(provider)for(const probe of provider)if(!probe.phase || probe.phase===(input.operationId===id(9)?'method':'initial'))await probeProvider(probe);
      if(change==='action_provider_header')await request('https://js.stripe.com/v3/','GET');
      if(change==='action_asset_header')await request(demo+'/assets/inert.js','GET');
      const response=await request(base(input.operationId)+'/action','GET');
      if(change==='page_action_decode')throw Error('secret_fixture canary');
      return response.json;
    },
    confirmationSession:async(input:any)=>{if(change==='page_session')throw Error('secret_fixture canary');assert.strictEqual(input.session,session);const response=await request(base(input.operationId)+'/confirmation-session','POST',{action_token:input.action.secret});if(change==='page_session_decode')throw Error('secret_fixture canary');return response.json;}};
  const sdk = {createGenericPaymentClient:()=>{counts.push('client');return {subscriptions,
    requestEmailOtp:async()=>{counts.push('challenge');await request(otpUrl,'POST',{site_key:'fixture',email:'fixture@example.test',intent:'AUTHENTICATE'});return {challengeId:id(10),expiresAt:expires};},
    verifyEmailOtp:async()=>{counts.push('verify');await request(otpUrl+'/'+id(10)+'/verify','POST',{site_key:'fixture',code:otp});return session;}};},
    mountStripePaymentAttempt:async(p:any)=>{counts.push('mountInitial');if(change==='mount_retire'){p.onRetire?.();throw Error('secret_fixture canary');}if(change==='mount_refusal')throw Error('secret_fixture canary');assert.strictEqual(p.client,subscriptions);assert.strictEqual(p.input.session,session);
      return {destroy:()=>{},confirm:async()=>{await request(base(id(7))+'/confirmation-attempts/'+id(8)+'/confirm','POST',{capability,confirmation_token:'ctoken_inert'});
        p.onRetire?.();return {state:'OBSERVE',retryAllowed:false,successorAttemptId:null};}};},
    mountStripeSubscriptionMethod:async(p:any)=>{counts.push('mountMethod');if(change==='method_mount_refusal'){p.onRetire?.();throw Error('secret_fixture canary');}assert.strictEqual(p.client,subscriptions);assert.strictEqual(p.session,session);
      assert.equal(p.subscriptionId,id(6));assert.equal(p.operationId,id(9));
      const a=await subscriptions.action(p),s=await subscriptions.confirmationSession({...p,action:a});
      assert.deepEqual(s,{protocol:'BROWSER_CONFIRM_V1',provider:'stripe',flow:'SETUP',publishable_key:selected.publishable_key,client_secret:selected.client_secret,action_token:actionSecret,expires_at:expires});
      return {destroy:()=>{},confirm:async()=>{const permit=await request(base(id(9))+'/action-confirmations','POST',{action_token:s.action_token});
        assert.equal(permit.status,202);assert.deepEqual(permit.json,{operation_id:id(9),subscription_id:id(6),status:'RESERVED',action_available:false,next_action:null,browser_confirm_allowed:true});
        counts.push('confirmSetup');p.onRetire?.();return {operationId:id(9),subscriptionId:id(6),status:'RESERVED',actionAvailable:false};}};}};
  installFailedRenewalPage(sdk);
  const labels:Record<string,string>={'Request code':'request','Verify code':'verify','Start payment':'start','Pay':'pay','Confirm test method':'confirm-method','I consent to recurring payments':'consent','Six-digit code':'code'};
  function locator(id:string):any {const c=ctrl(id);return {click:async()=>{
      if(id==='start'&&change==='start_click')throw Error('secret_fixture canary');
      if(id==='start'&&asynchronousStart){
        // DOM dispatch does not await an async event handler. Keep its task
        // separately so READY can wait for the page without making click do so.
        startTask=c.activate();void startTask.catch(()=>{});return;
      }
      await c.activate();
    },check:async()=>{c.checked=true;},fill:async()=>{if(id==='number')await stageHook?.('card');if(change===id)throw Error('secret_fixture canary');},isVisible:async()=>{if(change==='postal_check')throw Error('secret_fixture canary');return true;},isEnabled:async()=>change==='ready_button'&&id==='pay'?true:!c.disabled,
    getAttribute:async()=>body.dataset.phase,evaluate:async(f:Function,arg:any)=>id==='code'?(arg===undefined||((c.value=arg),true)):change==='manual_arm'&&id==='pay'?false:f(c,arg),
    frameLocator:()=>({locator:(s:string)=>locator(s.match(/name="([^"]+)"/)?.[1]??'card')})};}
  const page:any={mainFrame:()=>frame,url:()=>url,on:(n:string,f:Function)=>callbacks.set(n,f),goto:async(target:string)=>{assert.equal(target,demo+path);url=target;callbacks.get('framenavigated')?.(frame);},
    evaluate:async(f:Function,arg:unknown)=>f(arg),locator:(s:string)=>locator(s.replace(/^#/,'')),
    getByRole:(_role:string,o:{name:string})=>locator(labels[o.name]!),
    waitForFunction:async(f:Function,arg:unknown)=>{
      if(asynchronousStart&&arg==='READY'){
        markReadyWaiting();
        if(change==='async_readiness_failure')throw Error('secret_fixture canary');
        await startTask;
      }
      if(change==='pre_ready'&&arg==='READY')throw Error('secret_fixture canary');
      if(arg==='OBSERVE'||arg==='METHOD_CONFIRMED'){
        await stageHook?.('manual');
        const b=ctrl(arg==='OBSERVE'?'pay':'confirm-method');
        if((change==='manual_timeout'&&arg==='METHOD_CONFIRMED')||change==='initial_manual_timeout')throw Error(selected.client_secret);
        await b.activate();
      }
      assert.equal(Boolean(f(arg)),true);
    }};
  const context={on:()=>{},newPage:async()=>{counts.push('page');return page;},exposeBinding:async(n:string,f:Function)=>{bindings.set(n,f);w[n]=(...args:unknown[])=>{if(n==='__failedRenewalTerminal')terminals.push(args);return f({page,frame},...args);};},
    route:async(_pattern:string,f:Function)=>{routeHandler=f;},routeWebSocket:async()=>{},close:async()=>{closed++;callbacks.get('close')?.();w.__failedRenewalStop?.();}};
  const journey=runFailedRenewalBrowser({newContext:async()=>{counts.push('context');return context;}},runAbort);
  const initialPort={resources:()=>({runId:id(1),expectedSource:'a'.repeat(40),accountId:id(2),siteId:id(3),priceId:id(5),siteKey:'fixture',email:'fixture@example.test',endpoints:{demo,backend}}),
    withOtpCode:async(f:Function)=>f(otp),acknowledgeCustomer:async(v:any)=>{
      assert.deepEqual(v,{customerId:id(4),siteId:id(3)});
      if(change==='async_ack_page_loss'){markPreparationPending();await preparationHold;}
      return true;
    },
    prepare:async(v:any)=>{
      assert.deepEqual(v,{origin:demo,token});counts.push('prepare');
      if(asynchronousStart&&change!=='async_ack_page_loss'){markPreparationPending();await preparationHold;}
      // The port has completed CREATE_ACK; the controller must still verify
      // prepared identity before granting ACTION/SESSION/CONFIRM access.
      return change==='async_prepare_identity'?{...prepared,customer_id:id(12)}:prepared;
    },
    confirm:async()=>{counts.push('confirm');return {...identity,phase:'OBSERVE',code:'OK',protocol:'SERVER_CONFIRM_V2',attempt_id:id(8),state:'OBSERVE',retry_allowed:false,successor_attempt_id:null,next_action_allowed:false,expires_at:expires};}};
  const methodPort={prepareMethod:async()=>{counts.push('prepareMethod');return {subscriptionId:id(6),operationId:change==='old_operation'?id(7):id(9),action:action(actionSecret)};},
    selectMethod:async()=>{counts.push('selectMethod');if(change==='hold_method_session'){markIO();await heldIO;}return selected;},confirmMethod:async()=>{counts.push('permit');if(change==='permit_error')throw Error(selected.client_secret);return {browser_confirm_allowed:true,state:'RESERVED'};}};
  const lose=(event:string)=>{if(event==='close'||event==='crash')callbacks.get(event)?.();else if(event==='pagehide')windowEvents.get('pagehide')?.();else{doc.visibilityState='hidden';docEvents.get('visibilitychange')?.();}};
  const forgedTerminal=async()=>{
    const f=bindings.get('__failedRenewalTerminal');assert.ok(f);
    for(const [src,args] of [[{page,frame:{url:()=>url}},['STOPPED']],[{page:{},frame},['STOPPED']],[{page,frame},['OTHER']],[{page,frame},['STOPPED','extra']],[{page,frame},['STOPPED','BROWSER_INITIAL_MOUNT']],[{page,frame},['STOPPED','BROWSER_METHOD_MOUNT']],[{page,frame},['STOPPED',null,'extra']],[{page,frame:{url:()=>url}},['STOPPED','BROWSER_METHOD_MOUNT']],[{page:{},frame},['STOPPED','BROWSER_METHOD_MOUNT']]] as const){
      await Promise.resolve().then(()=>f(src,...args)).catch(()=>{});
    }
  };
  return {request,probeProvider,counts,replies,terminals,journey,initialPort,methodPort,runAbort,lose,forgedTerminal,preparationPending,readyWaiting,releasePreparation,ioPending,releaseIO,notifyIO:markIO,providerFallbacks:()=>providerFallbacks,
    setStageHook:(hook:typeof stageHook)=>{stageHook=hook;},terminal:async(marker:unknown)=>bindings.get('__failedRenewalTerminal')!({page,frame},'STOPPED',marker),closed:()=>closed,phase:()=>body.dataset.phase,status:()=>ctrl('status').textContent,
    restore:async()=>{try{runAbort.abort();await journey.close(nowLimits());}finally{w.__failedRenewalStop?.();restore();}}};
}

const providerCases:readonly [string,ProviderProbe,string,string?][]=[
 ['header getter',{target:'https://js.stripe.com/',headerFailure:'getter'},'BROWSER_PROVIDER_HEADERS'],
 ['header rejection',{target:'https://js.stripe.com/',headerFailure:'reject'},'BROWSER_PROVIDER_HEADERS'],
 ['current',{target:'https://js.stripe.com/',hold:true},'BROWSER_PROVIDER_CURRENT'],
 ['redirect lookup',{target:'https://user:pass@js.stripe.com/',redirect:'throw'},'BROWSER_PROVIDER_REDIRECT'],
 ['redirect refusal',{target:'https://user:pass@js.stripe.com/',redirect:'present'},'BROWSER_PROVIDER_REDIRECT'],
 ['userinfo',{target:'https://user:pass@js.stripe.com/'},'BROWSER_PROVIDER_USERINFO'],
 ['owned auth',{target:'https://js.stripe.com/',headers:{authorization:'Bearer '+token}},'BROWSER_PROVIDER_AUTH'],
 ['foreign origin auth',{target:'https://elsewhere.test/',headers:{authorization:'Bearer provider'}},'BROWSER_PROVIDER_AUTH'],
 ['scheme',{target:'http://js.stripe.com/'},'BROWSER_PROVIDER_ORIGIN_SCHEME'],
 ['opaque scheme',{target:'data:text/plain,fixture'},'BROWSER_PROVIDER_ORIGIN_SCHEME'],
 ['port',{target:'https://js.stripe.com:444/'},'BROWSER_PROVIDER_ORIGIN_PORT'],
 ['stripe m exact',{target:'https://m.stripe.com/'},'BROWSER_PROVIDER_ORIGIN_STRIPE_COM_M'],
 // Exact r and normalized/default443 rejection cases moved to the permitted matrix below.
 ['stripe m normalized case and default port',{target:'https://M.STRIPE.COM:443/arbitrary/path?fixture=yes#part'},'BROWSER_PROVIDER_ORIGIN_STRIPE_COM_M'],
 ['stripe m child',{target:'https://child.m.stripe.com/'},'BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER','https://child.m.stripe.com'],
 ['stripe r child',{target:'https://child.r.stripe.com/'},'BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER','https://child.r.stripe.com'],
 ['stripe apex',{target:'https://stripe.com/'},'BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER','https://stripe.com'],
 ['stripe subdomain',{target:'https://other.stripe.com/'},'BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER','https://other.stripe.com'],
 ['stripe m scheme before port and host',{target:'http://m.stripe.com:444/'},'BROWSER_PROVIDER_ORIGIN_SCHEME'],
 ['stripe r scheme before port and host',{target:'http://r.stripe.com:444/'},'BROWSER_PROVIDER_ORIGIN_SCHEME'],
 ['stripe m nondefault port before host',{target:'https://m.stripe.com:444/'},'BROWSER_PROVIDER_ORIGIN_PORT'],
 ['stripe r nondefault port before host',{target:'https://r.stripe.com:444/'},'BROWSER_PROVIDER_ORIGIN_PORT'],
 ['stripe m redirect before userinfo auth and origin',{target:'http://user:pass@m.stripe.com:444/',redirect:'present',headers:{authorization:'Bearer '+token}},'BROWSER_PROVIDER_REDIRECT'],
 ['stripe r redirect lookup before userinfo auth and origin',{target:'http://user:pass@r.stripe.com:444/',redirect:'throw',headers:{authorization:'Bearer '+token}},'BROWSER_PROVIDER_REDIRECT'],
 ['stripe m userinfo before auth and origin',{target:'http://user:pass@m.stripe.com:444/',headers:{authorization:'Bearer provider'}},'BROWSER_PROVIDER_USERINFO'],
 ['stripe r userinfo before auth and origin',{target:'http://user:pass@r.stripe.com:444/',headers:{authorization:'Bearer '+token}},'BROWSER_PROVIDER_USERINFO'],
 ['stripe m owned auth before origin',{target:'http://m.stripe.com:444/',headers:{authorization:'Bearer '+token}},'BROWSER_PROVIDER_AUTH'],
 ['stripe r foreign auth before origin',{target:'http://r.stripe.com:444/',headers:{authorization:'Bearer provider'}},'BROWSER_PROVIDER_AUTH'],
 ['stripe m origin before navigation',{target:'https://m.stripe.com/',navigation:'main'},'BROWSER_PROVIDER_ORIGIN_STRIPE_COM_M'],
 ['stripe r navigation lookup',{target:'https://r.stripe.com/',navigation:'throw'},'BROWSER_PROVIDER_NAVIGATION'],
 // URL parsing precedes provider classification and retains the active readiness checkpoint.
 ['malformed URL before provider classification',{target:'https://m.stripe.com:invalid/'},'BROWSER_READY_PHASE'],
 ['network apex',{target:'https://stripe.network/'},'BROWSER_PROVIDER_ORIGIN_STRIPE_NETWORK'],
 ['network subdomain',{target:'https://other.stripe.network/'},'BROWSER_PROVIDER_ORIGIN_STRIPE_NETWORK'],
 ['other',{target:'https://elsewhere.test/'},'BROWSER_PROVIDER_ORIGIN_OTHER','https://elsewhere.test'],
 ['other redirect',{target:'https://elsewhere.test/',redirect:'present'},'BROWSER_PROVIDER_REDIRECT'],
 ['other userinfo',{target:'https://user:pass@elsewhere.test/'},'BROWSER_PROVIDER_USERINFO'],
 ['other authorization',{target:'https://elsewhere.test/',headers:{authorization:'Bearer provider'}},'BROWSER_PROVIDER_AUTH'],
 ['other explicit port',{target:'https://elsewhere.test:444/'},'BROWSER_PROVIDER_ORIGIN_PORT'],
 ['com lookalike',{target:'https://notstripe.com/'},'BROWSER_PROVIDER_ORIGIN_OTHER','https://notstripe.com'],
 ['network lookalike',{target:'https://notstripe.network/'},'BROWSER_PROVIDER_ORIGIN_OTHER','https://notstripe.network'],
 ['suffix lookalike',{target:'https://js.stripe.com.evil.test/'},'BROWSER_PROVIDER_ORIGIN_OTHER','https://js.stripe.com.evil.test'],
 ['stripe m prefix lookalike',{target:'https://mstripe.com/'},'BROWSER_PROVIDER_ORIGIN_OTHER','https://mstripe.com'],
 ['stripe r prefix lookalike',{target:'https://rstripe.com/'},'BROWSER_PROVIDER_ORIGIN_OTHER','https://rstripe.com'],
 ['stripe m suffix lookalike',{target:'https://m.stripe.com.evil.test/'},'BROWSER_PROVIDER_ORIGIN_OTHER','https://m.stripe.com.evil.test'],
 ['stripe r suffix lookalike',{target:'https://r.stripe.com.evil.test/'},'BROWSER_PROVIDER_ORIGIN_OTHER','https://r.stripe.com.evil.test'],
 ['stripe m trailing dot',{target:'https://m.stripe.com./'},'BROWSER_PROVIDER_ORIGIN_OTHER','https://m.stripe.com.'],
 ['stripe r trailing dot',{target:'https://r.stripe.com./'},'BROWSER_PROVIDER_ORIGIN_OTHER','https://r.stripe.com.'],
 ['trailing dot',{target:'https://js.stripe.com./'},'BROWSER_PROVIDER_ORIGIN_OTHER','https://js.stripe.com.'],
 ['main navigation',{target:'https://js.stripe.com/',navigation:'main'},'BROWSER_PROVIDER_NAVIGATION'],
 ['navigation lookup',{target:'https://js.stripe.com/',navigation:'throw'},'BROWSER_PROVIDER_NAVIGATION'],
 ['frame lookup',{target:'https://js.stripe.com/',navigation:'frame'},'BROWSER_PROVIDER_NAVIGATION'],
 ['fallback rejection',{target:'https://js.stripe.com/',fallbackFailure:true},'BROWSER_PROVIDER_FALLBACK'],
];
function providerWorkSettlement(work:Promise<unknown>){
 return work.then(()=>({kind:'resolved' as const}),(error:unknown)=>({kind:'rejected' as const,error}));
}
async function waitProviderHeader(ioPending:Promise<void>,settled:ReturnType<typeof providerWorkSettlement>){
 const result=await Promise.race([ioPending.then(()=>({kind:'held' as const})),settled]);
 if(result.kind==='rejected')throw result.error;
 if(result.kind==='resolved')assert.fail('provider header hold not reached');
}
async function consumeProviderBoundary(h:Awaited<ReturnType<typeof fixture>>,probe:ProviderProbe,checkpoint:string,expectedOrigin?:string){
 const limits=nowLimits();let work:Promise<unknown>|undefined,first:unknown;
 try{
  if(probe.phase==='method')assert.equal(await h.journey.initial(h.initialPort,nowLimits()),true);
  work=probe.phase==='method'?h.journey.method(h.methodPort,limits):h.journey.initial(h.initialPort,limits);
  const settled=providerWorkSettlement(work!);
  if(probe.hold){await waitProviderHeader(h.ioPending,settled);limits.deadline=0;h.releaseIO();}
  await assert.rejects(work!,(error:unknown)=>{first=error;return true;});
  assert.equal(browserDiagnostic(first),checkpoint);
  assert.equal(typeof privateOrigin.browserProviderOrigin,'function');
  const saved=privateOrigin.browserProviderOrigin(first);
  const other=checkpoint==='BROWSER_PROVIDER_ORIGIN_OTHER';
  const expected=expectedOrigin && !(other && probe.phase==='method')
    ? {runId:id(1),expectedSource:'a'.repeat(40),origin:expectedOrigin,...(other?{checkpoint}:{})}
    : null;
  assert.deepEqual(saved,expected);
  if(expected)assert.equal(Object.isFrozen(saved),true);
  assert.doesNotMatch(String(first),/secret_fixture|canary|Bearer|stripe|elsewhere|user|pass/);
  assert.equal(h.providerFallbacks(),probe.fallbackFailure?1:0);
  assert.equal(h.counts.filter(v=>v==='confirm').length,probe.phase==='method'?1:0);assert.equal(h.counts.includes('permit'),false);
  await assert.rejects(h.journey.initial(h.initialPort,nowLimits()),e=>e===first);
  await assert.rejects(h.journey.method(h.methodPort,nowLimits()),e=>e===first);
  assert.equal(await h.journey.close(nowLimits()),true);assert.equal(h.closed(),1);
  assert.equal(browserCleanupConfirmed(first),true);assert.equal(browserDiagnostic(first),checkpoint);
  assert.equal(JSON.stringify(first),'{}');assert.deepEqual(Object.keys(first as object),[]);
 }finally{h.releaseIO();await work?.catch(()=>{});await h.restore();}
}
for(const [name,probe,checkpoint,expectedOrigin] of providerCases)test('provider boundary '+name,async()=>{
 await consumeProviderBoundary(await fixture('',[probe]),probe,checkpoint,expectedOrigin);
});
const merchantProviderCases:readonly [string,ProviderProbe,string,string?][]=[
 ['header getter',{target:'https://merchant-ui-api.stripe.com/',headerFailure:'getter'},'BROWSER_PROVIDER_HEADERS'],
 ['header rejection',{target:'https://merchant-ui-api.stripe.com/',headerFailure:'reject'},'BROWSER_PROVIDER_HEADERS'],
 ['current',{target:'https://merchant-ui-api.stripe.com/',hold:true},'BROWSER_PROVIDER_CURRENT'],
 ['redirect lookup before userinfo auth and origin',{target:'http://user:pass@merchant-ui-api.stripe.com:444/',redirect:'throw',headers:{authorization:'Bearer '+token}},'BROWSER_PROVIDER_REDIRECT'],
 ['redirect refusal',{target:'https://merchant-ui-api.stripe.com/',redirect:'present'},'BROWSER_PROVIDER_REDIRECT'],
 ['userinfo before auth and origin',{target:'http://user:pass@merchant-ui-api.stripe.com:444/',headers:{authorization:'Bearer provider'}},'BROWSER_PROVIDER_USERINFO'],
 ['username',{target:'https://user@merchant-ui-api.stripe.com/'},'BROWSER_PROVIDER_USERINFO'],
 ['password',{target:'https://:pass@merchant-ui-api.stripe.com/'},'BROWSER_PROVIDER_USERINFO'],
 ['customer authorization',{target:'https://merchant-ui-api.stripe.com/',headers:{authorization:'Bearer '+token}},'BROWSER_PROVIDER_AUTH'],
 ['foreign authorization',{target:'https://merchant-ui-api.stripe.com/',headers:{authorization:'Bearer provider'}},'BROWSER_PROVIDER_AUTH'],
 ['normalized foreign authorization',{target:'https://MERCHANT-UI-API.STRIPE.COM:443/',headers:{authorization:'Bearer provider'}},'BROWSER_PROVIDER_AUTH'],
 ['scheme before port and host',{target:'http://merchant-ui-api.stripe.com:444/'},'BROWSER_PROVIDER_ORIGIN_SCHEME'],
 ['nondefault port',{target:'https://merchant-ui-api.stripe.com:444/'},'BROWSER_PROVIDER_ORIGIN_PORT'],
 ['child origin before navigation',{target:'https://child.merchant-ui-api.stripe.com/',navigation:'main'},'BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER','https://child.merchant-ui-api.stripe.com'],
 ['prefix lookalike',{target:'https://othermerchant-ui-api.stripe.com/'},'BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER','https://othermerchant-ui-api.stripe.com'],
 ['suffix lookalike',{target:'https://merchant-ui-api.stripe.com.evil.test/'},'BROWSER_PROVIDER_ORIGIN_OTHER','https://merchant-ui-api.stripe.com.evil.test'],
 ['trailing dot',{target:'https://merchant-ui-api.stripe.com./'},'BROWSER_PROVIDER_ORIGIN_OTHER','https://merchant-ui-api.stripe.com.'],
 ['top navigation',{target:'https://merchant-ui-api.stripe.com/',navigation:'main'},'BROWSER_PROVIDER_NAVIGATION'],
 ['navigation lookup',{target:'https://merchant-ui-api.stripe.com/',navigation:'throw'},'BROWSER_PROVIDER_NAVIGATION'],
 ['frame lookup',{target:'https://merchant-ui-api.stripe.com/',navigation:'frame'},'BROWSER_PROVIDER_NAVIGATION'],
 ['fallback refusal',{target:'https://merchant-ui-api.stripe.com/',fallbackFailure:true},'BROWSER_PROVIDER_FALLBACK'],
];
for(const phase of ['initial','method'] as const)for(const [name,input,checkpoint,expectedOrigin] of merchantProviderCases)
test('merchant provider boundary '+phase+' '+name,async()=>{
 const probe={...input,phase};
 await consumeProviderBoundary(await fixture('',[probe]),probe,checkpoint,expectedOrigin);
});
// Apply the same route-level refusals to r in both separately attended phases.
// This also preserves the credential boundary: network admission grants no auth exception.
for(const phase of ['initial','method'] as const)for(const [name,input,checkpoint,expectedOrigin] of merchantProviderCases)
test('r provider boundary '+phase+' '+name,async()=>{
 const probe={...input,target:input.target.replace(/merchant-ui-api/gi,match=>match==='MERCHANT-UI-API'?'R':'r'),phase};
 const origin=expectedOrigin?.replace('merchant-ui-api','r');
 await consumeProviderBoundary(await fixture('',[probe]),probe,checkpoint,origin);
});
for(const [host,checkpoint] of [['other.stripe.com','BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER'],['only-private.example','BROWSER_PROVIDER_ORIGIN_OTHER']] as const)
test(checkpoint+' route keeps canonical private origin and original identity through replay and cleanup',async()=>{
 const probe={target:'https://'+host.toUpperCase()+':443/artificial_sensitive_path?artificial_secret_query=value#artificial_secret_fragment'};
 const h=await fixture('',[probe]);let first:unknown;
 try{
  await assert.rejects(h.journey.initial(h.initialPort,nowLimits()),error=>{first=error;return true;});
  assert.equal(typeof privateOrigin.browserProviderOrigin,'function');
  const expected={runId:id(1),expectedSource:'a'.repeat(40),origin:'https://'+host,...(checkpoint==='BROWSER_PROVIDER_ORIGIN_OTHER'?{checkpoint}:{})};
  assert.deepEqual(privateOrigin.browserProviderOrigin(first),expected);
  await assert.rejects(h.probeProvider({target:'https://second.example/second_secret?secret=yes#secret'}));
  assert.deepEqual(privateOrigin.browserProviderOrigin(first),expected);
  assert.equal(browserDiagnostic(first),checkpoint);
  assert.equal(h.providerFallbacks(),0);assert.equal(h.counts.includes('confirm'),false);assert.equal(h.counts.includes('permit'),false);
  await assert.rejects(h.journey.initial(h.initialPort,nowLimits()),error=>error===first);
  await assert.rejects(h.journey.method(h.methodPort,nowLimits()),error=>error===first);
  assert.equal(await h.journey.close(nowLimits()),true);assert.equal(h.closed(),1);assert.equal(browserCleanupConfirmed(first),true);
  assert.deepEqual(privateOrigin.browserProviderOrigin(first),expected);
  assert.equal(JSON.stringify(first),'{}');assert.deepEqual(Object.keys(first as object),[]);
  assert.doesNotMatch(String(first)+JSON.stringify(h.terminals)+h.status(),/stripe\.com|only-private|artificial_sensitive|artificial_secret|second_secret|aaaaaaaa/);
 }finally{h.releaseIO();await h.restore();}
});
for(const change of ['page_action','action_early_header'])test('earlier page or bridge refusal cannot acquire a later OTHER witness '+change,async()=>{
 const h=await fixture(change);let first:unknown;
 try{
  await assert.rejects(h.journey.initial(h.initialPort,nowLimits()),error=>{first=error;return true;});
  await assert.rejects(h.probeProvider({target:'https://other.stripe.com/private?secret=fixture#secret'}));
  assert.equal(typeof privateOrigin.browserProviderOrigin,'function');assert.equal(privateOrigin.browserProviderOrigin(first),null);
  assert.equal(browserDiagnostic(first),change==='page_action'?'BROWSER_INITIAL_ACTION':'BROWSER_ACTION_BRIDGE');
  assert.equal(h.providerFallbacks(),0);assert.equal(h.counts.includes('confirm'),false);assert.equal(h.counts.includes('permit'),false);
  await assert.rejects(h.journey.method(h.methodPort,nowLimits()),error=>error===first);
  await h.journey.close(nowLimits());assert.equal(h.closed(),1);assert.equal(browserCleanupConfirmed(first),true);
 }finally{h.releaseIO();await h.restore();}
});
test('provider permitted matrix preserves one-use journey and fallback',async()=>{
 const probes:ProviderProbe[]=['api.stripe.com','hooks.stripe.com','js.stripe.com','child.js.stripe.com'].map(host=>({target:'https://'+host+':443/arbitrary/path?fixture=yes#part',headers:{authorization:'Bearer provider'},method:'PATCH'}));
 probes.push({target:'https://js.stripe.com/',navigation:'child'});
 const h=await fixture('',probes);
 try{
  assert.equal(await h.journey.initial(h.initialPort,nowLimits()),true);
  assert.equal(await h.journey.method(h.methodPort,nowLimits()),true);
  assert.equal(h.providerFallbacks(),probes.length*2);
  assert.equal(h.counts.filter(v=>v==='confirm').length,1);assert.equal(h.counts.filter(v=>v==='permit').length,1);
  await h.journey.close(nowLimits());assert.equal(h.closed(),1);
 }finally{await h.restore();}
});
for(const host of ['merchant-ui-api','r'])test(host+' provider permitted matrix preserves both attended one-use phases and fallback',async()=>{
 const probes:ProviderProbe[]=[
  {target:'https://merchant-ui-api.stripe.com/',method:'GET'},
  {target:'https://merchant-ui-api.stripe.com/',method:'POST'},
  {target:'https://MERCHANT-UI-API.STRIPE.COM:443/artificial_path?fixture=yes#part',method:'GET'},
  {target:'https://MERCHANT-UI-API.STRIPE.COM:443/artificial_path?fixture=yes#part',method:'POST'},
  {target:'https://merchant-ui-api.stripe.com/',method:'GET',navigation:'child'},
  {target:'https://merchant-ui-api.stripe.com/',method:'POST',navigation:'child'},
 ];
 const admitted=probes.map(probe=>({...probe,target:probe.target.replace(/merchant-ui-api/gi,match=>match==='MERCHANT-UI-API'?host.toUpperCase():host)}));
 const h=await fixture('',admitted);let phase:'initial'|'method'='initial',attendedChecks=0;
 h.setStageHook(async()=>{
  assert.equal(h.counts.filter(v=>v==='confirm').length,phase==='initial'?0:1);
  assert.equal(h.counts.includes('permit'),false);assert.equal(h.counts.includes('confirmSetup'),false);
  attendedChecks++;
 });
 try{
  assert.equal(await h.journey.initial(h.initialPort,nowLimits()),true);
  assert.equal(h.phase(),'OBSERVE');assert.equal(h.providerFallbacks(),6);
  assert.equal(h.counts.filter(v=>v==='confirm').length,1);assert.equal(h.counts.includes('permit'),false);
  phase='method';assert.equal(await h.journey.method(h.methodPort,nowLimits()),true);
  assert.equal(h.phase(),'METHOD_CONFIRMED');assert.equal(h.providerFallbacks(),12);assert.equal(attendedChecks,4);
  assert.equal(h.counts.filter(v=>v==='confirm').length,1);assert.equal(h.counts.filter(v=>v==='permit').length,1);
  assert.equal(h.counts.filter(v=>v==='confirmSetup').length,1);assert.equal(h.runAbort.signal.aborted,false);
  await assert.rejects(h.journey.method(h.methodPort,nowLimits()));
  assert.equal(h.counts.filter(v=>v==='permit').length,1);assert.equal(h.counts.filter(v=>v==='confirmSetup').length,1);
  assert.equal(await h.journey.close(nowLimits()),true);assert.equal(h.closed(),1);
 }finally{h.setStageHook(undefined);await h.restore();}
});
async function consumeProviderPageFault(h:Awaited<ReturnType<typeof fixture>>){
 let work:Promise<unknown>|undefined,first:unknown;
 try{
  work=h.journey.initial(h.initialPort,nowLimits());
  const settled=providerWorkSettlement(work!);
  await waitProviderHeader(h.ioPending,settled);h.lose('pagehide');assert.equal(h.runAbort.signal.aborted,true);
  h.releaseIO();await assert.rejects(work!,(error:unknown)=>{first=error;return true;});
  assert.equal(browserDiagnostic(first),'BROWSER_INITIAL_ACTION');assert.equal(h.providerFallbacks(),0);
  assert.equal(typeof privateOrigin.browserProviderOrigin,'function');assert.equal(privateOrigin.browserProviderOrigin(first),null);
  assert.equal(h.counts.includes('confirm'),false);assert.equal(h.counts.includes('permit'),false);
  await assert.rejects(h.journey.initial(h.initialPort,nowLimits()),e=>e===first);
  await assert.rejects(h.journey.method(h.methodPort,nowLimits()),e=>e===first);
  await h.journey.close(nowLimits());assert.equal(h.closed(),1);assert.equal(browserCleanupConfirmed(first),true);
 }finally{h.releaseIO();await work?.catch(()=>{});await h.restore();}
}
test('earlier page fault wins pending provider currentness failure',async()=>{
 await consumeProviderPageFault(await fixture('',[{target:'https://js.stripe.com/',hold:true}]));
});
test('earlier page fault wins pending refused OTHER origin without late capture',async()=>{
 await consumeProviderPageFault(await fixture('',[{target:'https://other.stripe.com/private?secret=fixture#secret',hold:true}]));
});
test('earlier page fault wins pending non-Stripe origin without late capture',async()=>{
 await consumeProviderPageFault(await fixture('',[{target:'https://private.example/path?secret=fixture#secret',hold:true}]));
});
for(const consumer of ['boundary','page fault'] as const)for(const outcome of ['reject','resolve'] as const)test('held provider '+consumer+' restores globals after early '+outcome,async()=>{
 const names=['window','document','location','HTMLButtonElement','fetch'];
 const original=names.map(name=>Object.getOwnPropertyDescriptor(globalThis,name));
 const h=await fixture(outcome==='reject'?'page_action':'');
 let markSettled!:()=>void,originalFailure:unknown,completed=false;
 const settled=new Promise<void>(resolve=>{markSettled=resolve;});
 const initial=h.journey.initial.bind(h.journey);
 const observedInitial:typeof h.journey.initial=(port:BillingFirstPaymentPort,limits:BillingLimits)=>{
  const work=initial(port,limits);
  void work.then(()=>markSettled(),(error:unknown)=>{originalFailure=error;markSettled();});
  return work;
 };
 const owned={...h,journey:{...h.journey,initial:observedInitial}};
 const consume=consumer==='boundary'?consumeProviderBoundary(owned,{target:'https://js.stripe.com/',hold:true},'BROWSER_PROVIDER_CURRENT'):consumeProviderPageFault(owned);
 const observed=consume.then(()=>({error:undefined}),error=>({error})).then(result=>{completed=true;return result;});
 let completedBeforeRecovery=false;
 try{
  await settled;
  // A bounded event-loop gate lets the actual consumer react to settlement.
  // Explicit notification then recovers the broken pre-fix wait without a timer.
  await new Promise<void>(resolve=>setImmediate(resolve));
  completedBeforeRecovery=completed;
 }finally{h.notifyIO();h.releaseIO();await observed;await h.restore();}
 const result=await observed;
 assert.equal(completedBeforeRecovery,true,'consumer must clean up before recovery notification');
 assert.equal(h.closed(),1);
 for(const [index,name] of names.entries())assert.deepEqual(Object.getOwnPropertyDescriptor(globalThis,name),original[index]);
 if(outcome==='reject'){
  assert.equal(result.error,originalFailure);assert.equal(browserDiagnostic(originalFailure),'BROWSER_INITIAL_ACTION');
  assert.equal(browserCleanupConfirmed(originalFailure),true);assert.equal(h.counts.includes('confirm'),false);
 }else assert.equal((result.error as Error)?.message,'provider header hold not reached');
});
for(const where of ['card','manual'])test('completed readiness rejects stale original markers during '+where,async()=>{
  const h=await fixture();let phase='initial',checks=0;
  h.setStageHook(async at=>{
    if(at!==where)return;
    const markers=phase==='initial'?['BROWSER_INITIAL_ACTION','BROWSER_INITIAL_SESSION','BROWSER_INITIAL_MOUNT']:['BROWSER_METHOD_MOUNT'];
    for(const marker of markers){await h.terminal(marker);assert.equal(h.runAbort.signal.aborted,false);checks++;}
  });
  try{
    assert.equal(await h.journey.initial(h.initialPort,nowLimits()),true);phase='method';
    assert.equal(await h.journey.method(h.methodPort,nowLimits()),true);
    assert.equal(checks,4);assert.equal(h.phase(),'METHOD_CONFIRMED');
    assert.equal(h.counts.filter(v=>v==='confirm').length,1);assert.equal(h.counts.filter(v=>v==='permit').length,1);
    await h.journey.close(nowLimits());assert.equal(h.closed(),1);
  }finally{h.setStageHook(undefined);h.releaseIO();await h.restore();}
});

test('earlier bridge refusal wins over later actual page stop',async()=>{
  const h=await fixture('action_early_header');let first:unknown;
  try{
    await assert.rejects(h.journey.initial(h.initialPort,nowLimits()),error=>{first=error;return true;});
    h.lose('pagehide');
    assert.equal(browserDiagnostic(first),'BROWSER_ACTION_BRIDGE');
    await assert.rejects(h.journey.method(h.methodPort,nowLimits()),error=>error===first);
    assert.equal(h.counts.includes('confirm'),false);assert.equal(h.counts.includes('permit'),false);
    await h.journey.close(nowLimits());assert.equal(browserCleanupConfirmed(first),true);assert.equal(h.closed(),1);
  }finally{h.releaseIO();await h.restore();}
});

for(const operation of ['initial_action','initial_session','method_session'])for(const event of ['hide','pagehide'])
test('in-flight original terminal revokes before IO release '+operation+' '+event,async()=>{
  const h=await fixture('hold_'+operation);let work:Promise<unknown>|undefined,first:unknown;
  try{
    if(operation==='method_session')await h.journey.initial(h.initialPort,nowLimits());
    work=(operation==='method_session'?h.journey.method(h.methodPort,nowLimits()):h.journey.initial(h.initialPort,nowLimits()))
      .then(()=>assert.fail('held journey must reject'),(error:unknown)=>{first=error;});
    await h.ioPending;
    const expected=operation==='initial_action'?'BROWSER_INITIAL_ACTION':operation==='initial_session'?'BROWSER_INITIAL_SESSION':'BROWSER_METHOD_MOUNT';
    for(const wrong of ['BROWSER_INITIAL_ACTION','BROWSER_INITIAL_SESSION','BROWSER_INITIAL_MOUNT','BROWSER_METHOD_MOUNT'].filter(marker=>marker!==expected)){
      await h.terminal(wrong);assert.equal(h.runAbort.signal.aborted,false);
    }
    h.lose(event);assert.equal(h.runAbort.signal.aborted,true);
    assert.deepEqual(h.terminals.at(-1),['STOPPED',operation==='initial_action'?'BROWSER_INITIAL_ACTION':operation==='initial_session'?'BROWSER_INITIAL_SESSION':'BROWSER_METHOD_MOUNT']);
    assert.equal(h.counts.filter(v=>v==='confirm').length,operation==='method_session'?1:0);assert.equal(h.counts.includes('permit'),false);
    h.releaseIO();await work;
    assert.equal(browserDiagnostic(first),operation==='initial_action'?'BROWSER_INITIAL_ACTION':operation==='initial_session'?'BROWSER_INITIAL_SESSION':'BROWSER_METHOD_MOUNT');
    await assert.rejects(h.journey.method(h.methodPort,nowLimits()),error=>error===first);
    await h.journey.close(nowLimits());assert.equal(browserCleanupConfirmed(first),true);assert.equal(h.closed(),1);
  }finally{h.releaseIO();await work?.catch(()=>{});await h.restore();}
});

test('actual held helper preserves browser checkpoint through owner abort race and cleanup',async()=>{
 const {opts,scope}=await import(source('../../../../infrastructure/fixtures/failed-renewal-descriptors.mjs').href);
 const h=await fixture('number'),calls:string[]=[],yes=async()=>true;
 try{
  const options=opts();options.ports.demo=14173;
  const bootstrap={resources:()=>({cliDirectory:'/inert'}),prepare:yes,start:yes,stop:yes};
  const cli={validateBinding:yes,startListener:yes,stopListener:yes,assertBillingListener:yes};
  const resources=()=>({...scope(),...h.initialPort.resources()});
  const ownedFixture={resources,provision:yes,acknowledgeCustomer:h.initialPort.acknowledgeCustomer,withOtpCode:h.initialPort.withOtpCode,beforeDispatch:yes,acknowledge:yes,close:yes};
  const runtime={ready:yes,prepare:async()=>h.initialPort.prepare({origin:demo,token}),confirm:async()=>{calls.push('confirm');throw Error('forbidden');},observe:async()=>{calls.push('observe');throw Error('forbidden');},close:yes};
  const owner=await createOwnedBillingFailedRenewal(options,{preflight:yes,createBootstrap:async()=>bootstrap,createCli:async()=>cli,login:yes,createFixture:async()=>ownedFixture,createRuntime:()=>runtime});
  await assert.rejects(owner.run(h.journey,{...nowLimits(),signal:h.runAbort.signal}));const result=await owner.close();
  assert.equal(result.phase,'BROWSER');assert.equal(result.code,'ABORTED');assert.equal(result.diagnostic,'BROWSER_CARD_NUMBER');assert.equal(result.cleanupConfirmed,true);
  assert.equal(formatBillingFailedRenewalDiagnostic(result),browserTerminal);assert.deepEqual(calls,[]);assert.equal(h.closed(),1);
 }finally{await h.restore();}
});

for(const [change,checkpoint] of [
  ['action_delivery','BROWSER_ACTION_BRIDGE'],['session_delivery','BROWSER_SESSION_BRIDGE'],
  ['action_preflight_delivery','BROWSER_ACTION_BRIDGE'],['session_preflight_delivery','BROWSER_SESSION_BRIDGE'],['confirm_preflight_delivery','BROWSER_CONFIRM_BRIDGE'],
  ['action_early_header','BROWSER_ACTION_BRIDGE'],['session_early_header','BROWSER_SESSION_BRIDGE'],['confirm_early_header','BROWSER_CONFIRM_BRIDGE'],
  ['action_provider_header','BROWSER_PROVIDER_AUTH'],['action_asset_header','BROWSER_ASSET_ROUTE'],
  ['pre_ready','BROWSER_READY_PHASE'],['ready_button','BROWSER_READY_BUTTON'],['start_click','BROWSER_START_CLICK'],
  ['page_action','BROWSER_INITIAL_ACTION'],['page_session','BROWSER_INITIAL_SESSION'],
  ['page_action_decode','BROWSER_INITIAL_ACTION'],['page_session_decode','BROWSER_INITIAL_SESSION'],
  ['mount_refusal','BROWSER_INITIAL_MOUNT'],['mount_retire','BROWSER_INITIAL_MOUNT'],
  ['number','BROWSER_CARD_NUMBER'],['expiry','BROWSER_CARD_EXPIRY'],['cvc','BROWSER_CARD_CVC'],
  ['postal_check','BROWSER_POSTAL_CHECK'],['postalCode','BROWSER_POSTAL_FILL'],
  ['manual_arm','BROWSER_MANUAL_PAY_ARM'],['initial_manual_timeout','BROWSER_MANUAL_PAY_WAIT'],
] as const)test('held browser preserves first checkpoint through teardown on '+change,async()=>{
  const h=await fixture(change);
  try{
    let first:unknown;
    await assert.rejects(h.journey.initial(h.initialPort,nowLimits()),(error:unknown)=>{first=error;return true;});
    assert.equal(browserDiagnostic(first),checkpoint);
    assert.doesNotMatch(String(first),/secret_fixture|canary|bga1_|Bearer|ctoken_/);
    assert.equal(h.runAbort.signal.aborted,true);
    assert.equal(h.counts.includes('confirm'),false);
    await assert.rejects(h.journey.initial(h.initialPort,nowLimits()),error=>error===first);
    await assert.rejects(h.journey.method(h.methodPort,nowLimits()),error=>error===first);
    assert.equal(await h.journey.close(nowLimits()),true);
    assert.equal(browserDiagnostic(first),checkpoint);assert.equal(browserCleanupConfirmed(first),true);
    assert.equal(h.closed(),1);assert.equal(h.counts.filter(v=>v==='prepare').length,change==='start_click'?0:1);
  }finally{await h.restore();}
});

async function assertDetachedStartFailure(change:string,h:Awaited<ReturnType<typeof fixture>>) {
  const expected=change==='async_readiness_failure'?'BROWSER_READY_PHASE':'BROWSER_PREPARE';
  let first:unknown;
  // Attach the rejection observer before releasing either deterministic gate.
  const rejected=assert.rejects(h.journey.initial(h.initialPort,nowLimits()),(error:unknown)=>{first=error;return true;});
  try{
    await h.preparationPending;await h.readyWaiting;
    if(change!=='async_readiness_failure')assert.equal(h.phase(),'PREPARING');
    assert.equal(h.counts.filter(v=>v==='prepare').length,change==='async_ack_page_loss'?0:1);
    if(change==='async_prepare_page_loss'||change==='async_ack_page_loss'){
      h.lose('crash');
      assert.equal(h.runAbort.signal.aborted,true);
    }
    if(change==='async_readiness_failure'){
      // READY's own rejection must retain WAIT_READY even though the binding
      // remains suspended and subsequently fails its current-page assertion.
      await rejected;assert.equal(h.runAbort.signal.aborted,true);
    }
    h.releasePreparation();await rejected;
    assert.equal(browserDiagnostic(first),expected);
    assert.doesNotMatch(String(first),/secret_fixture|canary|bga1_|Bearer|ctoken_/);
    assert.equal(h.runAbort.signal.aborted,true);
    assert.equal(h.counts.includes('confirm'),false);assert.equal(h.counts.includes('mountInitial'),false);
    assert.equal(h.replies.length,0);
    await assert.rejects(h.journey.initial(h.initialPort,nowLimits()),error=>error===first);
    await assert.rejects(h.journey.method(h.methodPort,nowLimits()),error=>error===first);
    assert.equal(await h.journey.close(nowLimits()),true);
    assert.equal(browserDiagnostic(first),expected);assert.equal(browserCleanupConfirmed(first),true);
    assert.equal(h.closed(),1);
  }finally{h.releasePreparation();await rejected.catch(()=>{});await h.restore();}
}

for(const change of ['async_prepare_identity','async_prepare_page_loss','async_ack_page_loss','async_readiness_failure'] as const)
test('detached Start keeps precise failure checkpoint on '+change,async()=>{
  await assertDetachedStartFailure(change,await fixture(change));
});

test('unexpected detached Start success restores the fixture while preserving the failed assertion',async()=>{
  const names=['window','document','location','HTMLButtonElement','fetch'];
  const before=new Map(names.map(name=>[name,Object.getOwnPropertyDescriptor(globalThis,name)]));
  const h=await fixture('async_success');
  try{
    await assert.rejects(assertDetachedStartFailure('async_success',h),{code:'ERR_ASSERTION',operator:'rejects'});
    for(const name of names)assert.deepEqual(Object.getOwnPropertyDescriptor(globalThis,name),before.get(name),name+' must be restored');
    assert.equal(h.closed(),1);
  }finally{await h.restore();}
});

test('detached Start completes preparation before the two attended phases proceed',async()=>{
  const h=await fixture('async_success');
  const initial=h.journey.initial(h.initialPort,nowLimits());void initial.catch(()=>{});
  try{
    await h.preparationPending;await h.readyWaiting;
    assert.equal(h.phase(),'PREPARING');assert.equal(h.counts.includes('confirm'),false);
    h.releasePreparation();assert.equal(await initial,true);
    assert.equal(h.phase(),'OBSERVE');assert.equal(h.closed(),0);assert.equal(h.runAbort.signal.aborted,false);
    assert.equal(await h.journey.method(h.methodPort,nowLimits()),true);
    assert.equal(h.phase(),'METHOD_CONFIRMED');
    assert.equal(h.counts.filter(v=>v==='prepare').length,1);assert.equal(h.counts.filter(v=>v==='confirm').length,1);
    assert.equal(h.counts.filter(v=>v==='permit').length,1);
    assert.equal(await h.journey.close(nowLimits()),true);assert.equal(h.closed(),1);
  }finally{h.releasePreparation();await initial.catch(()=>{});await h.restore();}
});

for(const change of ['rejection','page_loss'] as const)test('method preparation preserves current checkpoint through teardown on '+change,async()=>{
  const h=await fixture();
  try{
    await h.journey.initial(h.initialPort,nowLimits());
    let prepares=0,first:unknown;
    const port={...h.methodPort,prepareMethod:async()=>{
      prepares++;await Promise.resolve();
      if(change==='page_loss')h.lose('crash');
      throw Error('secret_fixture canary');
    }};
    await assert.rejects(h.journey.method(port,nowLimits()),(error:unknown)=>{first=error;return true;});
    assert.equal(browserDiagnostic(first),'BROWSER_PREPARE');
    assert.doesNotMatch(String(first),/secret_fixture|canary|bga1_|Bearer|ctoken_/);
    assert.equal(h.runAbort.signal.aborted,true);
    await assert.rejects(h.journey.method(port,nowLimits()),error=>error===first);
    assert.equal(prepares,1);assert.equal(h.counts.includes('mountMethod'),false);
    assert.equal(h.counts.includes('selectMethod'),false);assert.equal(h.counts.includes('permit'),false);
    assert.equal(h.counts.filter(v=>v==='confirm').length,1);
    assert.equal(await h.journey.close(nowLimits()),true);
    assert.equal(browserDiagnostic(first),'BROWSER_PREPARE');assert.equal(browserCleanupConfirmed(first),true);
    assert.equal(h.closed(),1);
  }finally{await h.restore();}
});

test('one retained page, customer and SDK client across both separately attended phases',async()=>{
  const h=await fixture();
  try{
    assert.deepEqual(Object.keys(h.journey).sort(),['close','initial','method']);
    assert.equal(await h.journey.initial(h.initialPort,nowLimits()),true);
    assert.equal(h.closed(),0);assert.equal(h.phase(),'OBSERVE');
    assert.deepEqual(h.counts,['context','page','client','challenge','verify','prepare','mountInitial','confirm']);
    assert.equal(await h.journey.method(h.methodPort,nowLimits()),true);
    assert.deepEqual(h.counts.slice(8),['prepareMethod','mountMethod','selectMethod','permit','confirmSetup']);
    assert.equal(h.status(),'METHOD_CONFIRMED');
    await assert.rejects(h.journey.method(h.methodPort,nowLimits()));
    await Promise.all([h.journey.close(nowLimits()),h.journey.close(nowLimits())]);assert.equal(h.closed(),1);
  }finally{await h.restore();}
});
test('intentional final context close preserves the owner completion signal',async()=>{
  const h=await fixture();
  try{
    await h.journey.initial(h.initialPort,{...nowLimits(),signal:h.runAbort.signal});
    await h.forgedTerminal();assert.equal(h.runAbort.signal.aborted,false);
    await h.journey.method(h.methodPort,nowLimits());
    await h.forgedTerminal();assert.equal(h.runAbort.signal.aborted,false);
    assert.equal(await h.journey.close(nowLimits()),true);
    assert.equal(h.runAbort.signal.aborted,false);assert.equal(h.closed(),1);
  }finally{await h.restore();}
});
for(const event of ['close','hide','crash','crash-after-method'])test('actual owner refuses the next financial dispatch after held-page '+event,async()=>{
  const h=await fixture();
  try{
    const root='../../../../infrastructure/';
    const {opts,initial,iso,F,E,responses,methods}=await import(source(root+'fixtures/failed-renewal-descriptors.mjs').href);
    const {createOwnedBillingFailedRenewal}=await import(source(root+'stripe-billing-first-payment.mjs').href);
    const calls:string[]=[],mark=(name:string)=>async()=>{calls.push(name);return true;};
    const settlement={...initial(),subscription_id:id(6),operation_id:id(7),period_start:iso(F),paid_through:iso(E)};
    const stages=responses();
    for(const v of stages){v.subscription_id=id(6);v.operation_id=id(7);if(v.method_operation_id)v.method_operation_id=id(9);}
    stages[6].action=(await h.methodPort.prepareMethod()).action;stages[7].session=await h.methodPort.selectMethod();
    const runtime={...Object.fromEntries(methods.map((name:string,i:number)=>[name,async()=>{calls.push(name);return stages[i];}])),ready:mark('ready'),prepare:async(r:any)=>h.initialPort.prepare({origin:r.origin,token:r.customerToken}),confirm:h.initialPort.confirm,
      observe:async()=>settlement,
      close:mark('runtime-close')};
    const fixturePort={...h.initialPort,resources:()=>({...h.initialPort.resources(),customerId:id(4),productId:id(11)}),
      provision:mark('fixture'),beforeDeclineArm:mark('arm-intent'),acknowledgeDeclineArm:mark('arm-ack'),beforeDispatch:mark('dispatch'),acknowledge:mark('ack'),close:mark('fixture-close')};
    const bootstrap={resources:()=>({cliDirectory:'/inert'}),prepare:mark('prepare'),start:mark('start'),stop:mark('bootstrap-stop')};
    const cli={validateBinding:mark('binding'),startListener:mark('listener'),assertBillingListener:mark('check'),stopListener:mark('listener-stop')};
    const owner=await createOwnedBillingFailedRenewal(opts(),{preflight:mark('preflight'),createBootstrap:async()=>bootstrap,login:mark('login'),
      createCli:async()=>cli,createFixture:async()=>fixturePort,createRuntime:async()=>runtime,pause:mark('pause'),
      prove:async()=>{
        assert.equal(h.phase(),'OBSERVE');assert.equal(h.closed(),0);
        calls.push('initial-proof');if(event!=='crash-after-method')h.lose(event);
        return {ownedVerified:true,cashVerified:true,journalVerified:true,signedCallbacksVerified:true,
          anchor:Object.fromEntries(['initial_bill_id','initial_payment_id','initial_invoice_id','initial_allocation_id','initial_payment_intent_id'].map(k=>[k,stages[0][k]]))};
      },proveFailedRenewal:mark('unexpected-final-proof')});
    const journey={...h.journey,method:async(p:any,l:any)=>{await h.journey.method(p,l);assert.equal(h.phase(),'METHOD_CONFIRMED');h.lose('crash');return true;}};
    await assert.rejects(owner.run(journey,{...nowLimits(),signal:h.runAbort.signal}));
    const result=await owner.close();
    assert.ok(calls.includes('initial-proof'));assert.equal(calls.includes('boundary'),event==='crash-after-method');
    assert.equal(calls.includes('confirmMethod'),event==='crash-after-method');assert.equal(calls.includes('applyMethod'),false);
    assert.equal(h.runAbort.signal.aborted,true);assert.equal(result.cleanupConfirmed,true);
    assert.equal(result.failedRenewalAccepted,false);assert.equal(h.closed(),1);
    assert.deepEqual(calls.slice(-4),['runtime-close','listener-stop','bootstrap-stop','fixture-close']);
  }finally{await h.restore();}
});
for(const change of ['frame','origin','identity','operation','body','extra','duplicate_key','old_operation','manual_timeout','permit_error','expired'])test('terminates once without a second financial call on '+change,async()=>{
  const h=await fixture(change);
  try{
    await h.journey.initial(h.initialPort,nowLimits());
    const l=change==='expired'?{deadline:Date.now()-1}:nowLimits();
    await assert.rejects(h.journey.method(h.methodPort,l),(e:any)=>!String(e).includes('secret_fixture'));
    await assert.rejects(h.journey.method(h.methodPort,nowLimits()));
    assert.equal(h.counts.filter(v=>v==='permit').length,change==='permit_error'?1:0);
    assert.equal(h.counts.includes('confirmSetup'),false);
    assert.doesNotMatch(h.status(),/secret|bga1_|Bearer|ctoken_/);
    assert.equal(await h.journey.close(nowLimits()),true);assert.equal(h.closed(),1);
  }finally{await h.restore();}
});
test('dedicated build, exact-file health, manual Chrome config and default discovery',async()=>{
  const [html,vite,docker,nginx,ordinary,offline,config,entry,page]=await Promise.all([
    '../../billing-failed-renewal.html','../../vite.config.ts','../../Dockerfile','../../nginx.conf','../../playwright.config.ts','../../playwright.offline.config.ts',
    '../../playwright.stripe-billing-failed-renewal.config.ts','../stripe-billing-failed-renewal.spec.ts','../../src/billing-failed-renewal.ts'].map(read));
  for(const re of [/Stripe Sandbox/i,/4242/,/EUR\s?20/,/original.{0,36}debt/i,/Confirm test method/,/billing-failed-renewal\.ts/])assert.match(html!,re);
  assert.match(vite!,/billing-failed-renewal\.html/);assert.match(docker!,/COPY [^\n]*examples\/demo-site\/billing-failed-renewal\.html/);
  assert.match(nginx!,/location = \/billing-failed-renewal\.html\s*\{[^}]*try_files \$uri =404;/);
  assert.match(ordinary!,/testIgnore:[^\]]*stripe-billing-failed-renewal\.spec\.ts/);
  assert.match(offline!,/testMatch:\s*'stripe-return-offline\.spec\.ts'/);
  assert.match(config!,/testMatch:\s*'stripe-billing-failed-renewal\.spec\.ts'/);
  for(const re of [/workers:\s*1/,/retries:\s*0/,/headless:\s*false/,/channel:\s*'chrome'/,/trace:\s*'off'/,/video:\s*'off'/,/screenshot:\s*'off'/,/preserveOutput:\s*'never'/,/stripe-billing-failed-renewal-reporter/])assert.match(config!,re);
  assert.match(entry!,/createOwnedBillingFailedRenewal/);assert.match(entry!,/runFailedRenewalBrowser/);
  assert.match(page!,/mountStripeSubscriptionMethod/);assert.match(page!,/@generic-payments\/sdk/);
});

test('held DONE page uses remaining session lifetime for assets but never reopens the financial bridge',async()=>{
  const h=await fixture(),child=new AbortController();
  try{
    await h.journey.initial(h.initialPort,{...nowLimits(),signal:h.runAbort.signal});
    await h.journey.method(h.methodPort,{...nowLimits(),signal:child.signal});
    child.abort();
    await h.request('https://js.stripe.com/v3/','GET');
    assert.equal(h.runAbort.signal.aborted,false);
    await assert.rejects(h.request(backend+'/api/v1/customer/subscriptions/'+id(6)+'/operations/'+id(9)+'/action-confirmations','POST',{action_token:actionSecret}));
    assert.equal(h.counts.filter(n=>n==='permit').length,1);
  }finally{await h.restore();}
});

 test('method mount retirement retains fixed boundary with no permit or replay',async()=>{
 const h=await fixture('method_mount_refusal');try{
 await h.journey.initial(h.initialPort,nowLimits());let first:unknown;
 await assert.rejects(h.journey.method(h.methodPort,nowLimits()),e=>{first=e;return true;});
 assert.equal(browserDiagnostic(first),'BROWSER_METHOD_MOUNT');
 assert.doesNotMatch(String(first),/secret_fixture|canary|Bearer/);
 await assert.rejects(h.journey.method(h.methodPort,nowLimits()),e=>e===first);
 assert.equal(h.counts.includes('permit'),false);assert.equal(h.counts.includes('confirmSetup'),false);
 await h.journey.close(nowLimits());assert.equal(h.closed(),1);assert.equal(browserCleanupConfirmed(first),true);
 }finally{await h.restore();}
 });

test('ready page clears completed stage before held visibility stop',async()=>{
 const h=await fixture();try{
 await h.journey.initial(h.initialPort,nowLimits());h.lose('hide');
 assert.deepEqual(h.terminals,[['STOPPED',null]]);assert.equal(h.phase(),'STOPPED');
 assert.equal(h.runAbort.signal.aborted,true);await h.journey.close(nowLimits());assert.equal(h.closed(),1);
 }finally{await h.restore();}
});

test('initial action rejects malformed and wrong protocol-stage terminal markers',async()=>{
 const h=await fixture('action_forged');try{
 assert.equal(await h.journey.initial(h.initialPort,nowLimits()),true);
 assert.equal(h.phase(),'OBSERVE');assert.equal(h.runAbort.signal.aborted,false);
 await h.journey.close(nowLimits());assert.equal(h.closed(),1);
 }finally{await h.restore();}
});
