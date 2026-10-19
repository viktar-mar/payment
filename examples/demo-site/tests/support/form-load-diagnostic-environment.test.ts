import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {EventEmitter} from 'node:events';
import type {Page} from '@playwright/test';
// @ts-expect-error Native Node source type erasure.
import {installFormCspObserver,readDiagnosticPageEvidence,createFormResourceObserver} from './form-load-diagnostic-environment.ts';

type Callback=(value:unknown)=>void;
const cspZero={csp_observation:'OBSERVED',csp_enforced_mask:0,csp_report_only_mask:0};
const cspUnavailable={csp_observation:'UNAVAILABLE',csp_enforced_mask:0,csp_report_only_mask:0};
const resourceZero={resource_observation:'OBSERVED',resource_transport_failure_mask:0,resource_http_4xx_mask:0,resource_http_5xx_mask:0,fetch_failure_destination:'NONE',fetch_failure_code:'NONE'};
const resourceUnavailable={resource_observation:'UNAVAILABLE',resource_transport_failure_mask:0,resource_http_4xx_mask:0,resource_http_5xx_mask:0,fetch_failure_destination:'UNAVAILABLE',fetch_failure_code:'UNAVAILABLE'};
const sdkSample={version:1,binding:'BOUND',capability_started:true,capability_ready:true,loader_started:true,loader_ready:true,
  element_created:true,mount_called:true,mount_returned:true,ready_seen:true,loaderror_seen:false,view_completed:true,
  widget_completed:true,confirmation_blocked:false,catchup:'NOT_STARTED',operation:'COMPLETED',
  widget_state:'ready',element_state:'FRAME',pay_state:'ENABLED'};
// Synthetic external boundary only; actual serialized production functions execute.
function realm(options:{origin?:string;top?:boolean;now?:number;installThrows?:boolean}={}) {
  let callback:Callback|undefined,registrations=0,now=options.now??100;
  const document={addEventListener:(event:string,fn:Callback,capture:boolean)=>{
    assert.equal(event,'securitypolicyviolation');assert.equal(capture,true);registrations++;callback=fn;
    if(options.installThrows)throw poison();
  }};
  const context=vm.createContext({document,location:{origin:options.origin??'http://127.0.0.1:19000'},Date:{now:()=>now}});
  vm.runInContext(`globalThis.top=${options.top===false?'{}':'globalThis'}`,context);
  vm.runInContext(`globalThis.initArgs={ownedOrigin:'http://127.0.0.1:19000',activeDeadline:200}`,context);
  const install=()=>vm.runInContext(`(${installFormCspObserver.toString()})(initArgs)`,context);
  const read=(includeForm=true)=>vm.runInContext(`(${readDiagnosticPageEvidence.toString()})(${includeForm})`,context) as {handshake:boolean;sample:unknown;csp:typeof cspZero|null};
  return {context,install,read,setTime:(value:number)=>{now=value;},dispatch:(value:unknown)=>callback?.(value),
    registrations:()=>registrations,callback:()=>callback,snapshot:()=>JSON.parse(JSON.stringify(read().csp)) as typeof cspZero|null};
}
function poison() {return new Proxy({},{get(){assert.fail('external error inspected');}});}
function trapped<T extends object>(data:T,allowed:string[]) {
  const reads:string[]=[];
  return {reads,value:new Proxy(data,{get(target,key,receiver){
    reads.push(String(key));if(!allowed.includes(String(key)))assert.fail('forbidden read '+String(key));
    return Reflect.get(target,key,receiver);
  }})};
}
function cspEvent(directive:unknown='script-src-elem',disposition:unknown='enforce',trusted:unknown=true) {
  return trapped({isTrusted:trusted,effectiveDirective:directive,disposition},['isTrusted','effectiveDirective','disposition']);
}
function pageBoundary() {
  const emitter=new EventEmitter(),captured=new Map<string,Callback>(),removed:string[]=[];
  let registrationFailure:string|undefined,unsubscribeFailure:string|undefined;
  const page={on(event:string,fn:Callback){captured.set(event,fn);emitter.on(event,fn);if(event===registrationFailure)throw poison();return page;},
    off(event:string,fn:Callback){removed.push(event);if(event===unsubscribeFailure)throw poison();emitter.off(event,fn);return page;}};
  return {page:page as unknown as Pick<Page,'on'|'off'>,emitter,captured,removed,
    registrationFailure:(event:string)=>{registrationFailure=event;},unsubscribeFailure:(event:string)=>{unsubscribeFailure=event;}};
}
function request(type:unknown) {return trapped({resourceType:()=>type,url:()=> 'https://api.stripe.com/private?sentinel',failure:()=>({errorText:'net::ERR_FAILED'})},['resourceType','url','failure']);}
const ownedOrigins=Object.freeze(['http://127.0.0.1:19000','http://127.0.0.1:18080']);
// Break caught: mixing categories from different events or losing the independent metadata read.
test('fetch failures replace one same-event pair and preserve frozen checkpoints',()=>{
  const p=pageBoundary(),observer=createFormResourceObserver(p.page,()=>true,ownedOrigins);
  const emit=(url:()=>unknown,failure:()=>unknown,type='fetch')=>{
    const req=trapped({resourceType:()=>type,url,failure},['resourceType','url','failure']);
    p.emitter.emit('requestfailed',req.value);assert.deepEqual(req.reads,['resourceType','url','failure']);
  };
  emit(()=>ownedOrigins[0]+'/private?secret=sentinel',()=>({errorText:'net::ERR_ABORTED'}));
  const first=observer.snapshot();assert.equal(first.fetch_failure_destination,'OWN_APP');assert.equal(first.fetch_failure_code,'ABORTED');
  emit(()=> 'https://api.stripe.com/private',()=>({errorText:'net::ERR_NAME_NOT_RESOLVED'}),'xhr');
  assert.equal(observer.snapshot().resource_transport_failure_mask,4);
  assert.equal(observer.snapshot().fetch_failure_destination,'STRIPE_API');assert.equal(observer.snapshot().fetch_failure_code,'NAME_NOT_RESOLVED');
  assert.ok(Object.isFrozen(first));assert.equal(first.fetch_failure_destination,'OWN_APP');assert.equal(first.fetch_failure_code,'ABORTED');
  emit(()=>{throw poison();},()=>({errorText:'net::ERR_ABORTED'}));
  assert.equal(observer.snapshot().fetch_failure_destination,'UNKNOWN');assert.equal(observer.snapshot().fetch_failure_code,'ABORTED');
  emit(()=> 'https://js.stripe.com/v3',()=>{throw poison();});
  assert.equal(observer.snapshot().fetch_failure_destination,'STRIPE_JS');assert.equal(observer.snapshot().fetch_failure_code,'UNKNOWN');
  assert.equal(JSON.stringify(observer.snapshot()).includes('sentinel'),false);
});
function response(status:unknown,type:unknown) {
  const req=request(type),res=trapped({status:()=>status,request:()=>req.value},['status','request']);
  return {value:res.value,reads:res.reads,requestReads:req.reads};
}

// Break caught: CSP directive/disposition mappings lost, combined incorrectly or counted.
test('serialized CSP observer accumulates literal directive bits separately and idempotently',()=>{
  for(const [directive,want] of [['script-src',1],['script-src-elem',1],['script-src-attr',1],['frame-src',2],['child-src',2],['connect-src',4],['img-src',8],['',8]] as const) {
    const r=realm();r.install();r.dispatch(cspEvent(directive).value);r.dispatch(cspEvent(directive).value);
    assert.deepEqual(r.snapshot(),{csp_observation:'OBSERVED',csp_enforced_mask:want,csp_report_only_mask:0});
  }
  const r=realm();r.install();const event=cspEvent();r.dispatch(event.value);r.dispatch(cspEvent('connect-src','report').value);
  assert.deepEqual(r.snapshot(),{csp_observation:'OBSERVED',csp_enforced_mask:1,csp_report_only_mask:4});
  for(const disposition of ['enforce','report'])for(const directive of ['script-src','frame-src','connect-src','unknown'])r.dispatch(cspEvent(directive,disposition).value);
  assert.deepEqual(r.snapshot(),{csp_observation:'OBSERVED',csp_enforced_mask:15,csp_report_only_mask:15});
  assert.deepEqual(event.reads,['isTrusted','effectiveDirective','disposition']);
});
// Break caught: wrong document or stale event reads escape the absolute original deadline.
test('CSP scopes to exact own-origin top document and checks deadline before event reads',()=>{
  for(const options of [{origin:'http://127.0.0.1:19001'},{top:false},{now:200}]) {
    const r=realm(options);r.install();assert.equal(r.registrations(),0);assert.equal(r.read().csp,null);
  }
  const r=realm();r.install();assert.deepEqual(r.snapshot(),cspZero);r.setTime(199);r.dispatch(cspEvent('frame-src').value);
  const saved=r.read().csp;r.setTime(200);const late=cspEvent();r.dispatch(late.value);assert.deepEqual(late.reads,[]);
  assert.deepEqual(r.snapshot(),{csp_observation:'OBSERVED',csp_enforced_mask:2,csp_report_only_mask:0});
  assert.ok(Object.isFrozen(saved));r.install();assert.equal(r.registrations(),1);
});
// Break caught: untrusted/invalid sources read private fields or recover after becoming unavailable.
test('CSP ignores untrusted and latches unavailable on invalid or throwing allowed reads',()=>{
  const r=realm();r.install();const untrusted=cspEvent('script-src','enforce',false);r.dispatch(untrusted.value);
  assert.deepEqual(untrusted.reads,['isTrusted']);assert.deepEqual(r.snapshot(),cspZero);
  for(const bad of [cspEvent('script-src','invalid').value,cspEvent(null).value,
    {get isTrusted(){throw poison();}},{isTrusted:true,get effectiveDirective(){throw poison();}},
    {isTrusted:true,effectiveDirective:'script-src',get disposition(){throw poison();}}]) {
    const source=realm();source.install();source.dispatch(cspEvent('script-src').value);source.dispatch(bad);
    assert.deepEqual(source.snapshot(),cspUnavailable);const later=cspEvent();source.dispatch(later.value);assert.deepEqual(later.reads,[]);
  }
  const failure=realm({installThrows:true});failure.install();assert.deepEqual(failure.snapshot(),cspUnavailable);
  const late=cspEvent();failure.dispatch(late.value);assert.deepEqual(late.reads,[]);
});
// Break caught: mutable payload retained, mutable/writable facade or session ordering dependency.
test('CSP publishes frozen read-only facade with independent immutable scalar snapshots',()=>{
  const r=realm();r.install();const input={isTrusted:true,effectiveDirective:'script-src',disposition:'enforce'};r.dispatch(input);
  const before=r.read().csp;input.effectiveDirective='frame-src';assert.equal(before?.csp_enforced_mask,1);
  r.dispatch(input);assert.equal(before?.csp_enforced_mask,1);assert.equal(r.snapshot()?.csp_enforced_mask,3);
  assert.equal(vm.runInContext(`Object.isFrozen(__formDiagnosticCsp)&&Reflect.ownKeys(__formDiagnosticCsp).join(',')==='snapshot'&&Object.isFrozen(__formDiagnosticCsp.snapshot())`,r.context),true);
  assert.equal(vm.runInContext(`(()=>{const d=Object.getOwnPropertyDescriptor(globalThis,'__formDiagnosticCsp');return d.writable===false&&d.configurable===false})()`,r.context),true);
  vm.runInContext(`Object.defineProperty(globalThis,'__formDiagnosticSession',{value:Object.freeze({}),configurable:false})`,r.context);
  r.install();assert.equal(r.registrations(),1);
  const sessionFirst=realm();vm.runInContext(`Object.defineProperty(globalThis,'__formDiagnosticSession',{value:Object.freeze({}),configurable:false})`,sessionFirst.context);
  sessionFirst.install();assert.deepEqual(sessionFirst.snapshot(),cspZero);
});
// Break caught: mutating init args extends original authority or later private bits rewrite a saved report.
test('CSP keeps its original deadline and late private events cannot mutate saved evidence',()=>{
  const r=realm();r.install();r.dispatch(cspEvent('script-src').value);const saved=r.read().csp;
  // Node-side abort/local end is not an RPC cancellation of the CSP realm.
  r.dispatch(cspEvent('connect-src').value);assert.equal(saved?.csp_enforced_mask,1);assert.equal(r.snapshot()?.csp_enforced_mask,5);
  vm.runInContext('initArgs.activeDeadline=1000',r.context);r.setTime(200);
  const expired=cspEvent('frame-src');r.dispatch(expired.value);assert.deepEqual(expired.reads,[]);assert.equal(r.snapshot()?.csp_enforced_mask,5);
});
// Break caught: shared catch/accessor reads hide the independent facade or includeForm=false calls SDK snapshot.
test('serialized page reader guards facade data descriptors independently',()=>{
  const r=realm();r.install();vm.runInContext(`globalThis.snapshotCalls=0;globalThis.__formLoadDiagnostic={handshake(){return true},snapshot(){snapshotCalls++;return ${JSON.stringify(sdkSample)}}}`,r.context);
  assert.equal(r.read(false).handshake,true);assert.equal(r.read(false).sample,null);assert.equal(vm.runInContext('snapshotCalls',r.context),0);
  assert.deepEqual(JSON.parse(JSON.stringify(r.read())),{handshake:true,sample:sdkSample,csp:cspZero});
  vm.runInContext(`__formLoadDiagnostic.handshake=()=>{throw {get message(){throw 1}}}`,r.context);
  assert.deepEqual(JSON.parse(JSON.stringify(r.read())),{handshake:false,sample:sdkSample,csp:cspZero});
  vm.runInContext(`__formLoadDiagnostic.snapshot=()=>{throw {get message(){throw 1}}}`,r.context);
  assert.deepEqual(JSON.parse(JSON.stringify(r.read())),{handshake:false,sample:null,csp:cspZero});
  for(const property of ['__formLoadDiagnostic','__formDiagnosticCsp']) {
    const isolated=realm();vm.runInContext(`globalThis.getterCalls=0;Object.defineProperty(globalThis,'${property}',{get(){getterCalls++;throw 1}});globalThis.${property==='__formLoadDiagnostic'?'__formDiagnosticCsp':'__formLoadDiagnostic'}=${property==='__formLoadDiagnostic'?`{snapshot(){return ${JSON.stringify(cspZero)}}}`:`{handshake(){return true},snapshot(){return ${JSON.stringify(sdkSample)}}}`}`,isolated.context);
    const read=isolated.read();assert.equal(vm.runInContext('getterCalls',isolated.context),0);
    if(property==='__formLoadDiagnostic')assert.deepEqual(JSON.parse(JSON.stringify(read)),{handshake:false,sample:null,csp:cspZero});
    else assert.deepEqual(JSON.parse(JSON.stringify(read)),{handshake:true,sample:sdkSample,csp:null});
  }
  const getters=realm();vm.runInContext(`globalThis.getterCalls=0;globalThis.__formLoadDiagnostic={get handshake(){getterCalls++;throw 1},get snapshot(){getterCalls++;throw 1}};globalThis.__formDiagnosticCsp={get snapshot(){getterCalls++;throw 1}}`,getters.context);
  assert.deepEqual(JSON.parse(JSON.stringify(getters.read())),{handshake:false,sample:null,csp:null});assert.equal(vm.runInContext('getterCalls',getters.context),0);
  const missing=realm();assert.deepEqual(JSON.parse(JSON.stringify(missing.read())),{handshake:false,sample:null,csp:null});
  const throwingCsp=realm();vm.runInContext(`globalThis.__formLoadDiagnostic={handshake(){return true},snapshot(){return ${JSON.stringify(sdkSample)}}};globalThis.__formDiagnosticCsp={snapshot(){throw {get message(){throw 1}}}}`,throwingCsp.context);
  assert.deepEqual(JSON.parse(JSON.stringify(throwingCsp.read())),{handshake:true,sample:sdkSample,csp:null});
});
// Break caught: external cloning executes getters or erases malformed snapshot keys before rejection.
for(const source of ['sample','csp'] as const) {
  const field=source==='sample'?'version':'csp_enforced_mask',scalar=source==='sample'?1:0;
  const mutations=[
    ['returning getter',`Object.defineProperty(snapshot,'${field}',{enumerable:true,get(){getterReads++;return ${scalar}}})`],
    ['throwing getter',`Object.defineProperty(snapshot,'${field}',{enumerable:true,get(){getterReads++;throw poison}})`],
    ['prototype-spelled key',`Object.defineProperty(snapshot,'__proto__',{enumerable:true,value:null})`],
    ['hidden key',`Object.defineProperty(snapshot,'extra',{value:0})`],
    ['symbol key',`snapshot[Symbol('extra')]=0`],
    ['hidden allowed field',`Object.defineProperty(snapshot,'${field}',{enumerable:false})`],
    ['nested field',`snapshot.${field}={get toJSON(){forbiddenReads++;throw poison},get valueOf(){forbiddenReads++;throw poison}}`],
    ['toJSON key',`Object.defineProperty(snapshot,'toJSON',{enumerable:true,get(){forbiddenReads++;throw poison}})`],
    ['missing field',`delete snapshot.${field}`],
    ['non-record prototype',`Object.setPrototypeOf(snapshot,{})`],
    ...['NaN','Infinity','-1','0.5','16',"'1'",'null','undefined','true'].map(value=>[`invalid ${value}`,`snapshot.${field}=${value}`]),
    ...(source==='sample'?[
      ['invalid flag',`snapshot.ready_seen=1`],['invalid binding',`snapshot.binding='UNKNOWN'`],
      ['invalid catchup',`snapshot.catchup='UNKNOWN'`],['invalid operation',`snapshot.operation='UNKNOWN'`],
      ['invalid widget state',`snapshot.widget_state='UNKNOWN'`],['invalid element state',`snapshot.element_state='UNKNOWN'`],
      ['invalid pay state',`snapshot.pay_state='UNKNOWN'`],['Node-only bucket',`snapshot.bucket='BEFORE_15S'`],
      ['Node-only Pay',`snapshot.pay_visible=true`],
    ]:[['invalid observation',`snapshot.csp_observation='UNKNOWN'`],
      ['unavailable nonzero masks',`snapshot.csp_observation='UNAVAILABLE';snapshot.csp_enforced_mask=1`],
      ['invalid report mask',`snapshot.csp_report_only_mask=16`]]),
  ];
  for(const [name,mutation] of mutations)test(`serialized reader rejects ${source} ${name} before cloning and preserves independent source`,()=>{
    const r=realm();vm.runInContext(`globalThis.getterReads=0;globalThis.forbiddenReads=0;
      globalThis.poison={get message(){forbiddenReads++;throw 1}};
      globalThis.goodSample=${JSON.stringify(sdkSample)};globalThis.goodCsp=${JSON.stringify(cspZero)};
      globalThis.snapshot=${source==='sample'?'goodSample':'goodCsp'};${mutation};
      globalThis.__formLoadDiagnostic={handshake(){return true},snapshot(){return goodSample}};
      globalThis.__formDiagnosticCsp={snapshot(){return goodCsp}}`,r.context);
    let result:unknown='CLONE_FAILED';
    try {result=structuredClone(r.read());}catch { /* Never hand an opaque thrown fixture to test-runner formatting. */ }
    assert.equal(vm.runInContext('getterReads',r.context),0);
    assert.equal(vm.runInContext('forbiddenReads',r.context),0);
    assert.deepEqual(result,{handshake:true,sample:source==='sample'?null:sdkSample,csp:source==='csp'?null:cspZero});
  });
}
// Break caught: accepted records retain source identity or reject genuine null-prototype scalar snapshots.
for(const nullPrototype of [false,true])test(`serialized reader copies finite ${nullPrototype?'null-prototype':'plain'} records before cloning`,()=>{
  const r=realm();vm.runInContext(`globalThis.sample=${JSON.stringify(sdkSample)};globalThis.csp=${JSON.stringify(cspZero)};
    ${nullPrototype?'Object.setPrototypeOf(sample,null);Object.setPrototypeOf(csp,null);':''}
    globalThis.__formLoadDiagnostic={handshake(){return true},snapshot(){return sample}};
    globalThis.__formDiagnosticCsp={snapshot(){return csp}}`,r.context);
  const raw=r.read(),cloned=structuredClone(raw);
  assert.notEqual(raw.sample,vm.runInContext('sample',r.context));assert.notEqual(raw.csp,vm.runInContext('csp',r.context));
  assert.ok(Object.isFrozen(raw.sample));assert.ok(Object.isFrozen(raw.csp));
  vm.runInContext(`sample.ready_seen=false;csp.csp_enforced_mask=15`,r.context);
  assert.deepEqual(structuredClone(raw),{handshake:true,sample:sdkSample,csp:cspZero});assert.deepEqual(cloned,structuredClone(raw));
});
// Break caught: wrong resource mapping or HTTP errors confused with transport failures.
test('resource observer accumulates literal masks with only synchronous allowed reads',()=>{
  const p=pageBoundary(),observer=createFormResourceObserver(p.page,()=>true,ownedOrigins);assert.deepEqual(observer.snapshot(),resourceZero);
  p.emitter.emit('response',response(404,'script').value);p.emitter.emit('response',response(503,'fetch').value);p.emitter.emit('requestfailed',request('document').value);
  assert.deepEqual(observer.snapshot(),{...resourceZero,resource_transport_failure_mask:2,resource_http_4xx_mask:1,resource_http_5xx_mask:4});
  for(const [type,bit] of [['script',1],['document',2],['fetch',4],['xhr',4],['image',8],['',8]] as const) {
    const boundary=pageBoundary(),source=createFormResourceObserver(boundary.page,()=>true,ownedOrigins),req=request(type),res=response(499,type);
    boundary.emitter.emit('requestfailed',req.value);boundary.emitter.emit('requestfailed',req.value);boundary.emitter.emit('response',res.value);boundary.emitter.emit('response',response(599,type).value);
    assert.deepEqual(source.snapshot(),{...resourceZero,resource_transport_failure_mask:bit,resource_http_4xx_mask:bit,resource_http_5xx_mask:bit,
      ...(bit===4?{fetch_failure_destination:'STRIPE_API',fetch_failure_code:'GENERIC_FAILED'}:{})});
    assert.deepEqual(req.reads,bit===4?['resourceType','url','failure','resourceType','url','failure']:['resourceType','resourceType']);assert.deepEqual(res.reads,['status','request']);assert.deepEqual(res.requestReads,['resourceType']);
  }
  for(const type of ['script','document','xhr','other'])for(const status of [400,500])p.emitter.emit('response',response(status,type).value);
  for(const type of ['script','document','fetch','other'])p.emitter.emit('requestfailed',request(type).value);
  assert.deepEqual(observer.snapshot(),{...resourceZero,resource_transport_failure_mask:15,resource_http_4xx_mask:15,resource_http_5xx_mask:15,fetch_failure_destination:'STRIPE_API',fetch_failure_code:'GENERIC_FAILED'});
});
// Break caught: success/redirect sets error bits or invalid source fails to latch unavailable.
test('resources distinguish ordinary statuses and latch invalid status/type/getter failures',()=>{
  const p=pageBoundary(),observer=createFormResourceObserver(p.page,()=>true,ownedOrigins);
  for(const status of [100,200,302,399])p.emitter.emit('response',response(status,'script').value);
  assert.deepEqual(observer.snapshot(),resourceZero);
  const invalid:unknown[]=[...['200',NaN,Infinity,99,600,400.5].map(status=>response(status,'script').value),response(400,null).value,
    {get status(){throw poison();}},{status(){throw poison();}}, {status:()=>400,get request(){throw poison();}},
    {status:()=>400,request(){throw poison();}}, {status:()=>400,request:()=>({get resourceType(){throw poison();}})},
    {status:()=>400,request:()=>({resourceType(){throw poison();}})}];
  for(const value of invalid) {
    const boundary=pageBoundary(),source=createFormResourceObserver(boundary.page,()=>true,ownedOrigins);boundary.emitter.emit('requestfailed',request('script').value);
    boundary.emitter.emit('response',value);assert.deepEqual(source.snapshot(),resourceUnavailable);
    const later=request('script');boundary.emitter.emit('requestfailed',later.value);assert.deepEqual(later.reads,[]);
  }
  for(const value of [request(null).value,{get resourceType(){throw poison();}},{resourceType(){throw poison();}}]) {
    const boundary=pageBoundary(),source=createFormResourceObserver(boundary.page,()=>true,ownedOrigins);boundary.emitter.emit('requestfailed',value);assert.deepEqual(source.snapshot(),resourceUnavailable);
  }
});
// Break caught: stopped/aborted/late callbacks read events, failed off reopens gate, foreign listeners removed.
test('resource gates precede all reads and stop closes before scoped cleanup even on off failure',()=>{
  const p=pageBoundary();let active=true;const observer=createFormResourceObserver(p.page,()=>active,ownedOrigins),saved=observer.snapshot();
  const foreign=()=>{};p.emitter.on('response',foreign);p.emitter.emit('response',response(404,'script').value);
  assert.ok(Object.isFrozen(saved));assert.deepEqual(saved,resourceZero);const published=observer.snapshot();active=false;
  const inactive=request('document');p.emitter.emit('requestfailed',inactive.value);assert.deepEqual(inactive.reads,[]);
  active=true;p.unsubscribeFailure('requestfailed');observer.stop();observer.stop();
  const late=request('fetch');p.captured.get('requestfailed')?.(late.value);const lateResponse=response(503,'fetch');p.captured.get('response')?.(lateResponse.value);
  assert.deepEqual(late.reads,[]);assert.deepEqual(lateResponse.reads,[]);assert.deepEqual(observer.snapshot(),published);
  assert.deepEqual(p.removed,['requestfailed','response']);assert.ok(p.emitter.listeners('response').includes(foreign));
  assert.equal(p.emitter.listenerCount('requestfailed'),1); // Failure is not proof of listener removal.
});
// Break caught: partial on failure leaves captured callback live or prevents second cleanup attempt.
test('partial resource registration failure latches unavailable and cleans up both owned listeners',()=>{
  for(const event of ['requestfailed','response']) {
    const p=pageBoundary();p.registrationFailure(event);const observer=createFormResourceObserver(p.page,()=>true,ownedOrigins);
    assert.deepEqual(observer.snapshot(),resourceUnavailable);const late=request('script');p.captured.get('requestfailed')?.(late.value);assert.deepEqual(late.reads,[]);
    observer.stop();assert.ok(p.removed.includes(event));assert.equal(p.emitter.listenerCount('requestfailed'),0);assert.equal(p.emitter.listenerCount('response'),0);
  }
});
// Break caught: abort/deadline/local end gate checked after resource payload access or stop cleanup callback.
test('abort, original deadline and local end gates suppress captured resource callbacks before reads',()=>{
  for(const ending of ['abort','deadline','local']) {
    const p=pageBoundary(),controller=new AbortController();let now=100,localEnd=180;
    const observer=createFormResourceObserver(p.page,()=>!controller.signal.aborted&&now<200&&now<localEnd,ownedOrigins);
    p.emitter.emit('requestfailed',request('script').value);const saved=observer.snapshot();
    if(ending==='abort')controller.abort();else if(ending==='deadline')now=200;else localEnd=100;
    const req=request('fetch'),res=response(503,'fetch');p.captured.get('requestfailed')?.(req.value);p.captured.get('response')?.(res.value);
    assert.deepEqual(req.reads,[]);assert.deepEqual(res.reads,[]);assert.deepEqual(observer.snapshot(),saved);observer.stop();
  }
  const callbacks=new Map<string,Callback>(),reads:string[]=[];
  const page={on(event:string,fn:Callback){callbacks.set(event,fn);return page;},off(event:string){
    callbacks.get(event)?.(new Proxy({},{get(_target,key){reads.push(String(key));throw poison();}}));return page;
  }};
  const observer=createFormResourceObserver(page as unknown as Pick<Page,'on'|'off'>,()=>true,ownedOrigins);observer.stop();
  assert.deepEqual(reads,[]);assert.deepEqual(observer.snapshot(),resourceZero);
});
