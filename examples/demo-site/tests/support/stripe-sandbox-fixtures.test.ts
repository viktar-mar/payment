import test,{after,mock} from 'node:test';
import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import net from 'node:net';
import filesystem from 'node:fs/promises';
import {syncBuiltinESMExports} from 'node:module';
import type {Browser} from '@playwright/test';
import type {SandboxFixture,SandboxCase} from '../../../../infrastructure/smoke-stage-5a.mjs';
// @ts-expect-error Native Node 25 type erasure requires the source extension.
import {runSandboxCard} from './stripe-sandbox-fixtures.ts';

const forbidden=()=>{throw new Error('synthetic_test_forbids_external_boundary');};
for(const name of ['execFile','exec','spawn','fork','execSync','execFileSync','spawnSync'] as const)mock.method(childProcess,name,forbidden);
for(const name of ['createServer','connect','createConnection'] as const)mock.method(net,name,forbidden);
mock.method(globalThis,'fetch',forbidden);
for(const name of ['open','readFile','lstat','realpath','readdir','mkdir','rename','unlink'] as const)mock.method(filesystem,name,forbidden);
syncBuiltinESMExports();after(()=>{mock.restoreAll();syncBuiltinESMExports();});

function cardBoundary(begin=true,postalVisible=true,onWait?:(kind:'form'|'frame'|'number')=>Promise<void>,observe?:(body:unknown,arg:unknown,options:unknown)=>Promise<unknown>){
  const events:unknown[][]=[],record={synthetic:'session'},identity={endpoints:{backend:'http://127.0.0.1:18080',demo:'http://127.0.0.1:14173'},site:{key:'synthetic-site'},price:{key:'synthetic-price'}};
  const add=(...args:unknown[])=>{events.push(args);};
  const frame={locator:(selector:string)=>({waitFor:async(...args:unknown[])=>{add('field-wait',selector,...args);await onWait?.('number');},fill:async(...args:unknown[])=>add('fill',selector,...args),isVisible:async()=>{add('visible',selector);return postalVisible;}})};
  const widget={first(){add('first');return this;},evaluate:async(body:unknown,arg:unknown,options:unknown)=>{if(!observe)throw null;return observe(body,arg,options);},locator:(selector:string)=>({waitFor:async(...args:unknown[])=>{add('widget-wait',selector,...args);await onWait?.(selector.startsWith('button')?'form':'frame');}}),getByRole:(...args:unknown[])=>({click:async(...options:unknown[])=>add('click',...args,...options)}),frameLocator:(...args:unknown[])=>{add('frame',...args);return frame;}};
  const challenge={frameLocator:(...args:unknown[])=>{add('challenge-frame',...args);return {getByRole:(...role:unknown[])=>({click:async(...options:unknown[])=>add('complete',...role,...options)})};}};
  const lightbox={frameLocator:(...args:unknown[])=>{add('challenge-container',...args);return challenge;}};
  const page={goto:async(...args:unknown[])=>add('goto',...args),locator:(...args:unknown[])=>{add('widget',...args);return widget;},frameLocator:(...args:unknown[])=>{add('challenge',...args);return lightbox;}};
  const context={addInitScript:async(body:unknown,argument:unknown)=>{assert.equal(typeof body,'function');add('install',argument);},newPage:async()=>{add('new-page');return page;},close:async()=>add('close')};
  const browser={newContext:async(...args:unknown[])=>{add('new-context',...args);return context;}} as unknown as Browser;
  const fixture={identity,beginBrowser:async(...args:unknown[])=>{add('begin',...args);return begin;},browserSession:async(...args:unknown[])=>{add('checkout',...args);return record;},markPay:async(...args:unknown[])=>add('mark',...args),confirm:async(...args:unknown[])=>add('confirm',...args)} as unknown as SandboxFixture;
  return {browser,fixture,events,record};
}
const card=(number:string,postal=true)=>[
  ['widget-wait','button[aria-label="Pay"]:enabled',{state:'visible',timeout:15000}],
  ['widget-wait','iframe[title="Secure payment input frame"]',{state:'visible',timeout:15000}],
  ['field-wait','input[name="number"]',{state:'visible',timeout:15000}],
  ['fill','input[name="number"]',number,{timeout:15000}],['fill','input[name="expiry"]','1230'],['fill','input[name="cvc"]','123'],
  ['visible','input[name="postalCode"]'],...(postal?[['fill','input[name="postalCode"]','10001']]:[]),
];
// Break: losing the failed form's safe observation makes all initialization failures indistinguishable.
for(const retry of [false,true])test(`failed ${retry?'retry':'initial'} form observes once before close without further fields or payment`,async()=>{
  let forms=0,observations=0,reads=0;const hostile=new Proxy({},{get(){reads++;throw null;}});
  const f=cardBoundary(true,true,async kind=>{if(kind==='form'&&++forms===(retry?2:1))throw hostile;},async(body,arg,options)=>{
    observations++;assert.equal(typeof body,'function');assert.equal(arg,undefined);assert.deepEqual(options,{timeout:1000});
    assert.notEqual(f.events.at(-1)?.[0],'close');return {widgetState:'checking',elementState:'EMPTY',payState:'ABSENT'};
  });
  await assert.rejects(runSandboxCard(f.browser,f.fixture,retry?'decline':'success'));
  assert.equal(observations,1);assert.equal(reads,0);assert.deepEqual(f.events.at(-1),['close']);
  assert.equal(f.events.filter(e=>e[0]==='fill'&&e[1]==='input[name="number"]').length,retry?1:0);
  assert.equal(f.events.filter(e=>e[0]==='click'&&(e[2] as {name?:string})?.name==='Pay').length,retry?1:0);
  assert.equal(f.events.some(e=>e[0]==='confirm'),false);
});
test('successful card entry and failed non-form boundaries never collect a form observation',async()=>{
  let calls=0;const observe=async()=>{calls++;throw null;};
  const success=cardBoundary(true,true,undefined,observe);await runSandboxCard(success.browser,success.fixture,'decline');
  for(const gate of ['frame','number'] as const){const f=cardBoundary(true,true,async kind=>{if(kind===gate)throw null;},observe);await assert.rejects(runSandboxCard(f.browser,f.fixture,'success'));}
  assert.equal(calls,0);
});
// Break: proceeding on Resume's click rather than provider readiness can reach fields or Pay too early.
for(const retry of [false,true])test(`card driver waits for ${retry?'retry':'initial'} form readiness before filling or further authority`,async()=>{
  let release!:()=>void,forms=0;const pending=new Promise<void>(resolve=>{release=resolve;});
  const f=cardBoundary(true,true,async kind=>{if(kind==='form'&&++forms===(retry?2:1))await pending;});
  const running=runSandboxCard(f.browser,f.fixture,retry?'decline':'success');
  await new Promise(resolve=>setImmediate(resolve));
  try{
    assert.equal(f.events.filter(e=>e[0]==='fill'&&e[1]==='input[name="number"]').length,retry?1:0);
    assert.equal(f.events.some(e=>e[0]==='confirm'),false);
    assert.equal(f.events.filter(e=>e[0]==='click'&&(e[2] as {name?:string})?.name==='Pay').length,retry?1:0);
  }finally{release();await running;}
  assert.deepEqual(f.events.at(-2),['confirm',retry?'decline':'success']);assert.deepEqual(f.events.at(-1),['close']);
});
for(const retry of [false,true])for(const gate of ['form','frame','number'] as const)test(`${retry?'retry':'initial'} ${gate} gate failure stops later fields and financial actions`,async()=>{
  let forms=0,reads=0;const hostile=new Proxy({},{get(){reads++;throw null;}});
  const f=cardBoundary(true,true,async kind=>{if(kind==='form')forms++;if(kind===gate&&forms===(retry?2:1))throw hostile;});
  await assert.rejects(runSandboxCard(f.browser,f.fixture,retry?'decline':'success'));
  assert.equal(reads,0);assert.equal(f.events.filter(e=>e[0]==='fill'&&e[1]==='input[name="number"]').length,retry?1:0);
  assert.equal(f.events.some(e=>e[0]==='confirm'),false);assert.deepEqual(f.events.at(-1),['close']);
  assert.equal(f.events.filter(e=>e[0]==='click'&&(e[2] as {name?:string})?.name==='Pay').length,retry?1:0);
});
// Break: diagnostic wrappers must not change selectors, arguments, retry ordering or the server confirmation boundary.
for(const name of ['success','decline','sca'] as const)test(`real ${name} driver preserves original boundary calls and arguments`,async()=>{
  const f=cardBoundary();await runSandboxCard(f.browser,f.fixture,name);
  assert.deepEqual(f.events,[['begin',name],['new-context',{serviceWorkers:'block'}],['checkout',name],['install',{record:f.record,origin:'http://127.0.0.1:14173'}],['new-page'],
    ['goto','http://127.0.0.1:14173/?api_base_url=http%3A%2F%2F127.0.0.1%3A18080&site_key=synthetic-site&price_key=synthetic-price&locale=en',{timeout:15000}],
    ['widget','generic-payment-widget'],['first'],['click','button',{name:'Resume existing payment',exact:true},{timeout:15000}],['frame','iframe[title="Secure payment input frame"]'],
    ...card(name==='decline'?'4000000000000002':name==='sca'?'4000002500003155':'4242424242424242'),['mark',name],['click','button',{name:'Pay',exact:true},{timeout:15000}],
    ...(name==='decline'?[['mark','decline',true],...card('4242424242424242'),['click','button',{name:'Pay',exact:true},{timeout:15000}]]:[]),
    ...(name==='sca'?[['challenge','iframe[name^="__privateStripeFrame"][src^="https://js.stripe.com/v3/three-ds-2-challenge-"]'],['challenge-container','iframe[name="stripe-challenge-frame"]'],['challenge-frame','iframe[name="acsFrame"]'],['complete','button',{name:'Complete',exact:true},{timeout:20000}]]:[]),
    ['confirm',name],['close']]);
});
test('beginBrowser false performs no browser acquisition or payment operation for every case',async()=>{
  for(const name of ['success','decline','sca'] as SandboxCase[]){const f=cardBoundary(false);await runSandboxCard(f.browser,f.fixture,name);assert.deepEqual(f.events,[['begin',name]]);}
});
test('invisible postal input is never filled before either original or retry Pay',async()=>{
  const f=cardBoundary(true,false);await runSandboxCard(f.browser,f.fixture,'decline');
  assert.equal(f.events.filter(event=>event[0]==='visible').length,2);
  assert.equal(f.events.some(event=>event[0]==='fill'&&event[1]==='input[name="postalCode"]'),false);
  assert.deepEqual(f.events.filter(event=>['mark','confirm','close'].includes(String(event[0]))),[['mark','decline'],['mark','decline',true],['confirm','decline'],['close']]);
});
