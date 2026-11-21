import assert from 'node:assert/strict';
import {mkdtemp,readdir,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import test from 'node:test';
import {build} from 'vite';
// @ts-ignore Explicit source extension for Node's source-only test runner.
import {createLoadController} from '../../src/stripe-browser-load-check.ts';

// Node records the script that the installed loader would insert. Its DOM
// double does not download or execute scripts.
async function checkFirstScript(outDir:string, html:string, profile?:'recovery-presentation') {
  const entry = /<script\b[^>]*\bsrc="(\/assets\/[\w.-]+\.js)"/.exec(html)?.[1];
  assert.ok(entry, 'dedicated page entry');
  const scripts:string[] = [];
  const host = {isConnected:true, style:{}};
  const status = {textContent:'IDLE'};
  const window = {
    addEventListener() {}, removeEventListener() {},
    Stripe: undefined as unknown,
    configureStripeBrowserLoadCheck: undefined as unknown as (key:string,profile?:string)=>Promise<void>
  };
  const document = {
    head: {appendChild(script:{src:string}) {scripts.push(script.src);}},
    getElementById: (id:string)=>id==='provider-host'?host:status,
    querySelectorAll: ()=>[],
    querySelector: ()=>null,
    createElement(tag:string) {
      if(tag==='link') return {relList:{supports:()=>true}};
      assert.equal(tag, 'script');
      return {addEventListener(event:string, listener:()=>void) {
        if(event==='load') queueMicrotask(()=>{
          window.Stripe = Object.assign(()=>null, {version:'endive'});
          listener();
        });
      }};
    }
  };
  const globals={window,document};
  for(const name of Object.keys(globals)) assert.ok(!Object.hasOwn(globalThis,name));
  Object.assign(globalThis,globals);
  try {
    await import(pathToFileURL(join(outDir,entry)).href);
    assert.deepEqual(scripts, [], 'page import must not load Stripe');
    await assert.rejects(window.configureStripeBrowserLoadCheck('pk_test_artificialOnly','invalid-profile'));
    assert.deepEqual(scripts, [], 'unknown profile must not load Stripe');
    await window.configureStripeBrowserLoadCheck('pk_test_artificialOnly',profile);
    assert.equal(scripts.length,1);
    const url=new URL(scripts[0]!);
    assert.equal(url.origin,'https://js.stripe.com');
    assert.deepEqual([...url.searchParams], profile ? [] : [['advancedFraudSignals','false']]);
  } finally {
    for(const name of Object.keys(globals)) Reflect.deleteProperty(globalThis,name);
  }
}

test('dedicated build uses shared loader in isolated default and recovery profiles and production excludes probe',async()=>{
  const root=fileURLToPath(new URL('../..',import.meta.url));
  const directory=await mkdtemp(join(tmpdir(),'stripe-load-build-'));
  try{
    for(const [directoryName,mode] of [['ordinary','stripe-browser-load-check'],['recovery','stripe-browser-load-check'],['production','production']] as const){
      const modules=new Set<string>();const outDir=join(directory,directoryName);
      await build({root,configFile:join(root,'vite.config.ts'),configLoader:'runner',envFile:false,mode,logLevel:'silent',cacheDir:join(directory,'cache'),build:{outDir},plugins:[{name:'inspect-load-only',generateBundle(_options,bundle){for(const output of Object.values(bundle))if(output.type==='chunk')for(const id of output.moduleIds)modules.add(id);}}]});
      const files=await readdir(outDir);
      if(mode==='production'){
        assert.ok(!files.includes('stripe-browser-load-check.html'));assert.ok(![...modules].some(id=>id.endsWith('/stripe-browser-load-check.ts')));
      }else{
        assert.deepEqual(files.sort(),['assets','stripe-browser-load-check.html']);
        assert.ok([...modules].some(id=>id.endsWith('/frontend/sdk/src/providers/stripe-loader.ts')));
        assert.ok([...modules].some(id=>id.includes('/@stripe/stripe-js/')&&id.endsWith('/pure/index.js')),'real installed Stripe pure loader is bundled');
        assert.ok(![...modules].some(id=>/\/(?:client|runtime-factory|stripe-billing-failed-renewal|stripe-billing-first-payment)\.[mt]?[js]+$/.test(id)));
        const html=await readFile(join(outDir,'stripe-browser-load-check.html'),'utf8');
        assert.ok(!/<(?:form|input|button)\b/i.test(html));assert.match(html,/\binert\b/);assert.match(html,/tabindex="-1"/);
        await checkFirstScript(outDir,html,directoryName==='recovery'?'recovery-presentation':undefined);
      }
    }
  }finally{await rm(directory,{recursive:true,force:true});}
});

function recoveryControllerFixture(
  mountCallbacks?: (emit:(event:string)=>void)=>void,
  registrationCallbacks?: (name:string,emit:(event:string)=>void)=>void
) {
  const listeners=new Map<string,()=>void>();
  const states:string[]=[];
  const calls:unknown[][]=[];
  const host={isConnected:true,inert:false,tabIndex:0,style:{pointerEvents:''}};
  let loss:(()=>void)|undefined,deadline:(()=>void)|undefined;
  const emit=(event:string)=>listeners.get(event)?.();
  const element={
    on(name:string, listener:()=>void) {listeners.set(name,listener);registrationCallbacks?.(name,emit);},
    mount(target:HTMLElement) {calls.push(['mount',target]);mountCallbacks?.(emit);},
    destroy() {calls.push(['destroy']);}
  };
  const controller=createLoadController({
    host:host as unknown as HTMLElement,
    async load(...args:unknown[]) {
      calls.push(['load',...args]);
      return {elements(options:unknown) {
        calls.push(['elements',options]);
        return {create(type:string,options:unknown) {calls.push(['create',type,options]);return element;}};
      }};
    },
    status(state:string) {states.push(state);},
    onPageLoss(listener:()=>void) {loss=listener;return ()=>{loss=undefined;};},
    deadline(listener:()=>void) {deadline=listener;return ()=>{deadline=undefined;};}
  });
  return {controller,host,states,calls,emit,lose:()=>loss?.(),expire:()=>deadline?.()};
}

test('recovery presentation mounts deferred subscription without secrets and holds an inert ready Element until loss',async()=>{
  const f=recoveryControllerFixture();
  await f.controller.configure('pk_test_artificialOnly','recovery-presentation');
  assert.deepEqual(f.calls,[
    ['load','pk_test_artificialOnly','recovery-presentation'],
    ['elements',{mode:'subscription',amount:2000,currency:'eur',allowedPaymentMethodTypes:['card'],locale:'en'}],
    ['create','payment',{wallets:{applePay:'never',googlePay:'never',link:'never'}}],
    ['mount',f.host]
  ]);
  assert.equal(f.host.inert,true);
  assert.equal(f.host.tabIndex,-1);
  assert.equal(f.host.style.pointerEvents,'none');
  f.emit('ready');
  f.emit('ready');
  assert.deepEqual(f.states,['LOADING','READY']);
  assert.equal(f.calls.some(([name])=>name==='destroy'),false);
  f.lose();
  f.emit('ready');
  f.controller.stop();
  assert.deepEqual(f.states,['LOADING','READY','STOPPED']);
  assert.equal(f.calls.filter(([name])=>name==='destroy').length,1);
});

test('held recovery Element retires exactly once on deadline, stop or loaderror',async()=>{
  for(const [action,status] of [['expire','TIMEOUT'],['stop','STOPPED'],['error','ERROR']] as const){
    const f=recoveryControllerFixture();
    await f.controller.configure('pk_test_artificialOnly','recovery-presentation');
    f.emit('ready');
    if(action==='expire')f.expire();
    else if(action==='error')f.emit('loaderror');
    else f.controller.stop();
    f.expire();
    f.lose();
    f.emit('ready');
    assert.deepEqual(f.states,['LOADING','READY',status]);
    assert.equal(f.calls.filter(([name])=>name==='destroy').length,1);
  }
});

test('synchronous Element events report ready only when mount succeeds without loaderror',async()=>{
  const ready=recoveryControllerFixture(emit=>{emit('ready');});
  await ready.controller.configure('pk_test_artificialOnly','recovery-presentation');
  assert.deepEqual(ready.states,['LOADING','READY']);
  ready.controller.stop();
  for(const badMount of [
    (emit:(event:string)=>void)=>{emit('ready');emit('loaderror');},
    (emit:(event:string)=>void)=>{emit('loaderror');emit('ready');},
    (emit:(event:string)=>void)=>{emit('ready');throw Error('private provider error');}
  ]){
    const f=recoveryControllerFixture(badMount);
    await f.controller.configure('pk_test_artificialOnly','recovery-presentation');
    assert.deepEqual(f.states,['LOADING','ERROR']);
    assert.equal(f.calls.filter(([name])=>name==='destroy').length,1);
  }
});

test('an Element retired during listener registration is never mounted',async()=>{
  const f=recoveryControllerFixture(undefined,(name,emit)=>{
    if(name==='ready'){emit('ready');emit('loaderror');}
  });
  await f.controller.configure('pk_test_artificialOnly','recovery-presentation');
  assert.deepEqual(f.states,['LOADING','ERROR']);
  assert.equal(f.calls.filter(([name])=>name==='mount').length,0);
  assert.equal(f.calls.filter(([name])=>name==='destroy').length,1);
});
