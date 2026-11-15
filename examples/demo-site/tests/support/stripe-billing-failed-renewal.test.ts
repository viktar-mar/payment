import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';

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

class Control {
  disabled = true; checked = false; hidden = false; value = ''; textContent = '';
  onclick?: (event:unknown)=>unknown;
  async activate() {assert.equal(this.disabled,false);await this.onclick?.({isTrusted:true});}
}
async function fixture(change = '') {
  const runAbort=new AbortController(),docEvents=new Map<string,Function>();
  const {installFailedRenewalPage} = await import(source('../../src/billing-failed-renewal-page.ts').href);
  const {runFailedRenewalBrowser} = await import(source('./stripe-billing-failed-renewal.ts').href);
  const counts:string[] = [], replies:any[] = [], controls = new Map<string,Control>();
  const ctrl = (id:string) => {
    if(!controls.has(id))controls.set(id,Object.assign(new Control(),{id}));
    return controls.get(id)!;
  };
  const body = {dataset:{phase:'NEW'}};
  const doc = {body,visibilityState:'visible',querySelector:(s:string)=>ctrl(s.slice(1)),
    querySelectorAll:()=>[...controls.values()],addEventListener:(n:string,f:Function)=>docEvents.set(n,f)};
  const w:any = {addEventListener:()=>{}};
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
  async function request(target:string,method:string,data?:any) {
    let contents=data===undefined?null:JSON.stringify(data);
    const headers:Record<string,string>={origin:demo,...(data?{'content-type':'application/json'}:{}),
      ...(!target.startsWith(otpUrl)&&target.startsWith(backend)?{authorization:'Bearer '+token,...(method==='POST'?{'idempotency-key':crypto.randomUUID()}:{})}:{})};
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
    const req={url:()=>target,method:()=>method,allHeaders:async()=>headers,frame:()=>requestFrame,postData:()=>contents,
      redirectedFrom:()=>null,isNavigationRequest:()=>false,resourceType:()=> 'fetch'};
    await routeHandler({request:()=>req,abort:async()=>{denied=true;},fallback:async()=>{assert.ok(target==='https://js.stripe.com/v3/'||target===otpUrl||target===otpUrl+'/'+id(10)+'/verify');},
      fulfill:async(r:any)=>{assert.equal(r.headers['Cache-Control'],'no-store');assert.equal(r.headers['Access-Control-Allow-Origin'],demo);reply=r;replies.push(r);}});
    if(denied)throw Error('denied mock request');
    return reply;
  }
  const subscriptions={action:async(input:any)=>{assert.strictEqual(input.session,session);return (await request(base(input.operationId)+'/action','GET')).json;},
    confirmationSession:async(input:any)=>{assert.strictEqual(input.session,session);return (await request(base(input.operationId)+'/confirmation-session','POST',{action_token:input.action.secret})).json;}};
  const sdk = {createGenericPaymentClient:()=>{counts.push('client');return {subscriptions,
    requestEmailOtp:async()=>{counts.push('challenge');await request(otpUrl,'POST',{site_key:'fixture',email:'fixture@example.test',intent:'AUTHENTICATE'});return {challengeId:id(10),expiresAt:expires};},
    verifyEmailOtp:async()=>{counts.push('verify');await request(otpUrl+'/'+id(10)+'/verify','POST',{site_key:'fixture',code:otp});return session;}};},
    mountStripePaymentAttempt:async(p:any)=>{counts.push('mountInitial');assert.strictEqual(p.client,subscriptions);assert.strictEqual(p.input.session,session);
      return {destroy:()=>{},confirm:async()=>{await request(base(id(7))+'/confirmation-attempts/'+id(8)+'/confirm','POST',{capability,confirmation_token:'ctoken_inert'});
        p.onRetire?.();return {state:'OBSERVE',retryAllowed:false,successorAttemptId:null};}};},
    mountStripeSubscriptionMethod:async(p:any)=>{counts.push('mountMethod');assert.strictEqual(p.client,subscriptions);assert.strictEqual(p.session,session);
      assert.equal(p.subscriptionId,id(6));assert.equal(p.operationId,id(9));
      const a=await subscriptions.action(p),s=await subscriptions.confirmationSession({...p,action:a});
      assert.deepEqual(s,{protocol:'BROWSER_CONFIRM_V1',provider:'stripe',flow:'SETUP',publishable_key:selected.publishable_key,client_secret:selected.client_secret,action_token:actionSecret,expires_at:expires});
      return {destroy:()=>{},confirm:async()=>{const permit=await request(base(id(9))+'/action-confirmations','POST',{action_token:s.action_token});
        assert.equal(permit.status,202);assert.deepEqual(permit.json,{operation_id:id(9),subscription_id:id(6),status:'RESERVED',action_available:false,next_action:null,browser_confirm_allowed:true});
        counts.push('confirmSetup');p.onRetire?.();return {operationId:id(9),subscriptionId:id(6),status:'RESERVED',actionAvailable:false};}};}};
  installFailedRenewalPage(sdk);
  const labels:Record<string,string>={'Request code':'request','Verify code':'verify','Start payment':'start','Pay':'pay','Confirm test method':'confirm-method','I consent to recurring payments':'consent','Six-digit code':'code'};
  function locator(id:string):any {const c=ctrl(id);return {click:()=>c.activate(),check:async()=>{c.checked=true;},fill:async()=>{},isVisible:async()=>true,isEnabled:async()=>!c.disabled,
    getAttribute:async()=>body.dataset.phase,evaluate:async(f:Function,arg:any)=>id==='code'?(arg===undefined||((c.value=arg),true)):f(c,arg),
    frameLocator:()=>({locator:()=>locator('card')})};}
  const page:any={mainFrame:()=>frame,url:()=>url,on:(n:string,f:Function)=>callbacks.set(n,f),goto:async(target:string)=>{assert.equal(target,demo+path);url=target;callbacks.get('framenavigated')?.(frame);},
    evaluate:async(f:Function,arg:unknown)=>f(arg),locator:(s:string)=>locator(s.replace(/^#/,'')),
    getByRole:(_role:string,o:{name:string})=>locator(labels[o.name]!),
    waitForFunction:async(f:Function,arg:unknown)=>{
      if(arg==='OBSERVE'||arg==='METHOD_CONFIRMED'){
        const b=ctrl(arg==='OBSERVE'?'pay':'confirm-method');
        if(change==='manual_timeout'&&arg==='METHOD_CONFIRMED')throw Error(selected.client_secret);
        await b.activate();
      }
      assert.equal(Boolean(f(arg)),true);
    }};
  const context={on:()=>{},newPage:async()=>{counts.push('page');return page;},exposeBinding:async(n:string,f:Function)=>{bindings.set(n,f);w[n]=(...args:unknown[])=>f({page,frame},...args);},
    route:async(_pattern:string,f:Function)=>{routeHandler=f;},routeWebSocket:async()=>{},close:async()=>{closed++;callbacks.get('close')?.();w.__failedRenewalStop?.();}};
  const journey=runFailedRenewalBrowser({newContext:async()=>{counts.push('context');return context;}},runAbort);
  const initialPort={resources:()=>({runId:id(1),accountId:id(2),siteId:id(3),priceId:id(5),siteKey:'fixture',email:'fixture@example.test',endpoints:{demo,backend}}),
    withOtpCode:async(f:Function)=>f(otp),acknowledgeCustomer:async(v:any)=>{assert.deepEqual(v,{customerId:id(4),siteId:id(3)});return true;},
    prepare:async(v:any)=>{assert.deepEqual(v,{origin:demo,token});counts.push('prepare');return prepared;},
    confirm:async()=>{counts.push('confirm');return {...identity,phase:'OBSERVE',code:'OK',protocol:'SERVER_CONFIRM_V2',attempt_id:id(8),state:'OBSERVE',retry_allowed:false,successor_attempt_id:null,next_action_allowed:false,expires_at:expires};}};
  const methodPort={prepareMethod:async()=>{counts.push('prepareMethod');return {subscriptionId:id(6),operationId:change==='old_operation'?id(7):id(9),action:action(actionSecret)};},
    selectMethod:async()=>{counts.push('selectMethod');return selected;},confirmMethod:async()=>{counts.push('permit');if(change==='permit_error')throw Error(selected.client_secret);return {browser_confirm_allowed:true,state:'RESERVED'};}};
  const lose=(event:string)=>{if(event==='close'||event==='crash')callbacks.get(event)?.();else{doc.visibilityState='hidden';docEvents.get('visibilitychange')?.();}};
  const forgedTerminal=async()=>{
    const f=bindings.get('__failedRenewalTerminal');assert.ok(f);
    for(const [src,args] of [[{page,frame:{url:()=>url}},['STOPPED']],[{page:{},frame},['STOPPED']],[{page,frame},['OTHER']],[{page,frame},['STOPPED','extra']]] as const){
      await Promise.resolve().then(()=>f(src,...args)).catch(()=>{});
    }
  };
  return {request,counts,replies,journey,initialPort,methodPort,runAbort,lose,forgedTerminal,closed:()=>closed,phase:()=>body.dataset.phase,status:()=>ctrl('status').textContent,
    restore:async()=>{try{runAbort.abort();await journey.close(nowLimits());}finally{w.__failedRenewalStop?.();restore();}}};
}

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
