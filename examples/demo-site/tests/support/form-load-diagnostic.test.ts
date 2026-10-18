import test from 'node:test';
import assert from 'node:assert/strict';
import type {BrowserType} from '@playwright/test';
import type {FormDiagnosticHandoff} from '../../../../infrastructure/stripe-form-diagnostic-runtime.mjs';
import {setImmediate as immediate} from 'node:timers/promises';
// @ts-expect-error Native Node source type erasure.
import {runFormLoadBrowser} from './form-load-diagnostic.ts';
import {runFormLoadDiagnostic} from '../../../../infrastructure/stripe-form-diagnostic.mjs';
import {classifyFormDiagnosticSample} from '../../../../infrastructure/stripe-form-diagnostic-schema.mjs';

const pending={version:1,binding:'BOUND',capability_started:true,capability_ready:true,loader_started:true,loader_ready:true,
  element_created:true,mount_called:true,mount_returned:true,ready_seen:false,loaderror_seen:false,view_completed:false,
  widget_completed:false,confirmation_blocked:false,catchup:'NOT_STARTED',operation:'PENDING',
  widget_state:'loading',element_state:'FRAME',pay_state:'ABSENT'};
const ready={...pending,ready_seen:true,view_completed:true,widget_completed:true,operation:'COMPLETED',widget_state:'ready',pay_state:'ENABLED'};
const handoff:FormDiagnosticHandoff={runId:'00000000-0000-4000-8000-000000000001',expectedSource:'a'.repeat(40),endpoint:'http://127.0.0.1:14173',
  session:{version:2,apiBaseUrl:'http://127.0.0.1:18080',siteKey:'site',priceKey:'price',checkoutId:'00000000-0000-4000-8000-000000000002',checkoutToken:'a'.repeat(43),
    expiresAt:'2099-01-01T00:00:00.000Z',providerType:'STRIPE',confirmationMode:'CLIENT_PROVIDER',state:{checkoutId:'00000000-0000-4000-8000-000000000002',paymentId:'00000000-0000-4000-8000-000000000003',status:'REQUIRES_PAYMENT_METHOD',amountMinor:2000,currency:'EUR',price:null,expiresAt:'2099-01-01T00:00:00.000Z'}}};
function fake(sample=ready) {
  const events:string[]=[],writes:unknown[]=[];
  const locator={count:async()=>sample.pay_state==='ENABLED'?1:0,isEnabled:async()=>sample.pay_state==='ENABLED',isVisible:async()=>sample.pay_state==='ENABLED',
    click:async()=>{events.push('resume');}};
  const page={goto:async()=>{events.push('goto');},evaluate:async(fn:Function)=>fn.name==='handshake'?true:sample,
    locator:(selector:string)=>{assert.equal(selector,'generic-payment-widget');return {count:async()=>1,
      getByRole:(_role:string,query:{name:string;exact:boolean})=>{assert.deepEqual(query,{name:'Resume existing payment',exact:true});return locator;},
      locator:(selector:string)=>{assert.equal(selector,'button[aria-label="Pay"]:enabled');return locator;}};}};
  const context={addInitScript:async(_fn:Function,value:unknown)=>{writes.push(value);events.push('init');},newPage:async()=>page,close:async()=>{events.push('context-close');}};
  const browser={newContext:async(options:unknown)=>{assert.deepEqual(options,{serviceWorkers:'block',acceptDownloads:false});events.push('context');return context;},close:async()=>{events.push('browser-close');}};
  const type={launch:async(options:unknown)=>{assert.equal((options as {headless:boolean}).headless,true);events.push('launch');return browser;}} as unknown as Pick<BrowserType,'launch'>;
  return {type,browser,context,page,locator,events,writes};
}
const limits=()=>({deadline:Date.now()+900000,signal:new AbortController().signal});
test('one ready load uses exactly one Resume and closes context before browser; no payment capability',async()=>{
  const f=fake(),result=await runFormLoadBrowser(f.type,handoff,limits());
  assert.equal(result.outcome,'OBSERVING');assert.equal(result.samples.length,1);
  assert.equal(result.samples[0]?.bucket,'BEFORE_15S');assert.equal(result.samples[0]?.pay_visible,true);
  assert.equal(result.browserClosed,true);assert.equal(result.contextClosed,true);assert.ok(Object.isFrozen(result.samples[0]));
  assert.deepEqual(f.events,['launch','context','init','goto','resume','context-close','browser-close']);
  assert.equal(JSON.stringify(result).includes(handoff.session.checkoutToken),false);
});
test('unavailable instrumentation and opaque page failure do not become waiting diagnoses',async()=>{
  const f=fake();f.page.evaluate=async()=>false as never;
  const result=await runFormLoadBrowser(f.type,handoff,limits());
  assert.equal(result.outcome,'INSTRUMENTATION_UNAVAILABLE');assert.deepEqual(result.samples,[]);
  assert.equal(f.events.includes('resume'),false);assert.equal(result.browserClosed,true);
  const g=fake();let reads=0;g.page.goto=async()=>{throw new Proxy({},{get(){reads++;throw null;}});};
  const failed=await runFormLoadBrowser(g.type,handoff,limits());assert.equal(failed.outcome,'FAILED');assert.equal(reads,0);
});
test('abort before launch accesses no browser capability',async()=>{
  const controller=new AbortController();controller.abort();const f=fake();
  assert.equal((await runFormLoadBrowser(f.type,handoff,{deadline:Date.now()+1000,signal:controller.signal})).outcome,'ABORTED');assert.deepEqual(f.events,[]);
});
test('uncertain context cleanup stays unconfirmed even after successful browser close',async()=>{
  const f=fake();f.context.close=async()=>{throw null;};
  const result=await runFormLoadBrowser(f.type,handoff,limits());assert.equal(result.contextClosed,false);assert.equal(result.browserClosed,true);
  assert.equal(result.outcome,'FAILED');
});
test('hanging context close cannot prevent browser close and parent cleanup keeps the original ceiling',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake(),original=limits();let resolveContext!:()=>void;
  f.context.close=()=>{f.events.push('context-close');return new Promise<void>(resolve=>{resolveContext=resolve;});};
  const run=runFormLoadDiagnostic({env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'},
    runId:handoff.runId,expectedSource:handoff.expectedSource,daemon:'synthetic-daemon',
    ports:{backend:18080,demo:14173,receiver:14174,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025}},original,{
    createRuntime:async()=>({prepare:async()=>handoff,verifyNoConfirmation:async()=>{f.events.push('proof');return true;},
      close:async(cleanup:{deadline:number})=>{assert.equal(cleanup.deadline,original.deadline+120000);f.events.push('runtime-close');return true;}}),
    runBrowser:(h:FormDiagnosticHandoff,l:ReturnType<typeof limits>)=>runFormLoadBrowser(f.type,h,l),
  });
  await immediate();
  const closedBeforeCeiling=f.events.includes('browser-close');
  assert.equal(f.events.includes('runtime-close'),false);
  t.mock.timers.tick(1020000);await immediate();
  const result=await run;
  assert.equal(closedBeforeCeiling,true);
  assert.equal(result.exitCode,1);assert.equal(result.frames[0]?.context_closed,false);
  assert.equal(result.frames[0]?.browser_closed,true);
  assert.deepEqual(f.events.slice(-3),['context-close','browser-close','runtime-close']);
  resolveContext();await immediate();assert.equal(result.frames[0]?.context_closed,false);
});
test('pending checkpoint then late ready yields two bounded snapshots and one Resume',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const sample={...pending},f=fake(sample as typeof ready),run=runFormLoadBrowser(f.type,handoff,limits());
  await immediate();t.mock.timers.tick(15000);await immediate();
  Object.assign(sample,ready);t.mock.timers.tick(5000);await immediate();
  const result=await run;assert.equal(result.samples.length,2);
  assert.deepEqual(result.samples.map(s=>s.bucket),['AFTER_15S','AFTER_15S']);
  assert.equal(result.samples[0]?.ready_seen,false);assert.equal(result.samples[1]?.pay_visible,true);
  assert.equal(f.events.filter(s=>s==='resume').length,1);
});
test('pending form stops at original60s window, without retry or new capability',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake({...pending} as typeof ready),run=runFormLoadBrowser(f.type,handoff,limits());
  await immediate();t.mock.timers.tick(15000);await immediate();t.mock.timers.tick(45000);await immediate();
  const result=await run;assert.equal(result.outcome,'FAILED');assert.equal(result.samples.length,2);
  assert.equal(result.samples[1]?.bucket,'AT_LIMIT');assert.equal(f.events.filter(s=>s==='resume').length,1);
  assert.equal(classifyFormDiagnosticSample(result.samples[1],result.outcome),'OPERATION_FAILED');
});
test('readiness bucket includes completion of the actual Pay visibility proof across15s',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake();f.page.evaluate=async(fn:Function)=>{
    if(fn.name==='handshake')return true;
    t.mock.timers.setTime(1014999);return ready;
  };
  f.locator.isVisible=async()=>{t.mock.timers.setTime(1015001);return true;};
  const result=await runFormLoadBrowser(f.type,handoff,limits());
  assert.equal(result.outcome,'OBSERVING');assert.equal(result.samples.length,1);
  assert.equal(classifyFormDiagnosticSample(result.samples[0]),'FORM_READY_AFTER_15S');
});
for(const boundary of [60000,60001])for(const stage of ['snapshot','visibility']){
  test(`${stage} settlement at ${boundary}ms cannot beat the local deadline timer or promote readiness`,async t=>{
    t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
    const sample={...pending},f=fake(sample as typeof ready);let reads=0,payReads=0;
    const payCount=f.locator.count;f.locator.count=async()=>{payReads++;return payCount();};
    f.page.evaluate=async(fn:Function)=>{
      if(fn.name==='handshake')return true;
      if(++reads===1)return {...pending};
      Object.assign(sample,ready);
      t.mock.timers.setTime(1000000+(stage==='snapshot'?boundary:59999));return {...ready};
    };
    f.locator.isVisible=async()=>{t.mock.timers.setTime(1000000+boundary);return true;};
    const run=runFormLoadBrowser(f.type,handoff,limits());
    await immediate();t.mock.timers.tick(100);await immediate();
    const result=await run;
    assert.equal(result.outcome,'FAILED');assert.equal(result.samples.length,1);
    assert.equal(result.samples[0]?.bucket,'AT_LIMIT');assert.equal(result.samples[0]?.ready_seen,false);
    assert.equal(result.samples[0]?.pay_visible,false);
    assert.equal(payReads,stage==='snapshot'?1:2);
    assert.equal(classifyFormDiagnosticSample(result.samples[0],result.outcome),'OPERATION_FAILED');
    assert.equal(result.contextClosed,true);assert.equal(result.browserClosed,true);
  });
}
test('expired local window admits no new snapshot even with ample original active time',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const sample={...pending},f=fake(sample as typeof ready);let reads=0;
  f.page.evaluate=async(fn:Function)=>fn.name==='handshake'?true:(reads++,{...sample});
  const run=runFormLoadBrowser(f.type,handoff,limits());await immediate();
  Object.assign(sample,ready);t.mock.timers.tick(60000);await immediate();
  const result=await run;
  assert.equal(reads,1);assert.equal(result.outcome,'FAILED');
  assert.equal(result.samples[0]?.bucket,'AT_LIMIT');assert.equal(result.samples[0]?.ready_seen,false);
});
test('late browser acquisition is closed and never upgrades uncertainty to success',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake();let resolve!:(browser:unknown)=>void;
  const launch=new Promise<unknown>(yes=>{resolve=yes;});
  const type={launch:()=>launch} as unknown as Pick<BrowserType,'launch'>;
  const run=runFormLoadBrowser(type,handoff,{deadline:Date.now()+1000,signal:new AbortController().signal});
  await immediate();t.mock.timers.tick(1000);await immediate();resolve(f.browser);await immediate();
  const result=await run;assert.equal(result.outcome,'DEADLINE_REACHED');assert.equal(result.browserClosed,false);
  assert.deepEqual(f.events,['browser-close']);
});
test('a hung local observation cannot extend the60s window to the15min active ceiling',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake({...pending} as typeof ready),controller=new AbortController();let reads=0,settled=false;
  f.page.evaluate=async(fn:Function)=>fn.name==='handshake'?true:++reads<3?pending:await new Promise(()=>{}) as never;
  const run=runFormLoadBrowser(f.type,handoff,{deadline:Date.now()+900000,signal:controller.signal}).then(result=>{settled=true;return result;});
  await immediate();t.mock.timers.tick(15000);await immediate();t.mock.timers.tick(100);await immediate();
  t.mock.timers.tick(44900);await immediate();
  try {assert.equal(settled,true);}finally {controller.abort();await run;}
});
test('late context acquisition is closed without converting unknown context ownership into success',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake();let resolve!:(context:unknown)=>void;
  const acquired=new Promise<unknown>(yes=>{resolve=yes;});f.browser.newContext=async()=>await acquired as never;
  const run=runFormLoadBrowser(f.type,handoff,{deadline:Date.now()+1000,signal:new AbortController().signal});
  await immediate();t.mock.timers.tick(1000);await immediate();resolve(f.context);await immediate();
  const result=await run;assert.equal(result.outcome,'DEADLINE_REACHED');assert.equal(result.contextClosed,false);
  assert.equal(result.browserClosed,true);assert.equal(f.events.filter(v=>v==='context-close').length,1);
  assert.equal(f.events.includes('resume'),false);
});
test('abort during local observation preserves frozen checkpoint and prevents late readiness improvement',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const sample={...pending},f=fake(sample as typeof ready),controller=new AbortController();
  const run=runFormLoadBrowser(f.type,handoff,{deadline:Date.now()+900000,signal:controller.signal});
  await immediate();t.mock.timers.tick(15000);await immediate();controller.abort();Object.assign(sample,ready);
  const result=await run;assert.equal(result.outcome,'ABORTED');assert.equal(result.samples.length,1);
  assert.equal(result.samples[0]?.ready_seen,false);assert.equal(result.samples[0]?.pay_visible,false);
  assert.equal(result.contextClosed,true);assert.equal(result.browserClosed,true);
});
