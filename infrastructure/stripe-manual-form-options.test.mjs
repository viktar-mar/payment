import test from 'node:test';
import assert from 'node:assert/strict';

// Break: new-mode exclusion after normalization or evaluating an accessor.
test('remainder exclusion precedes snapshots configuration and dependencies',async()=>{
  const {manualFormOptIn:accept}=await import('./stripe-manual-form-options.mjs'); const base={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC:'true'};
  let reads=0;const trap=new Proxy({},{get(){reads++;throw null;}});
  for(const shape of ['true','malformed','boolean','accessor','hidden','inherited']){
    const raw=shape==='inherited'?Object.assign(Object.create({PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER:'true'}),base):{...base};
    if(shape!=='inherited')Object.defineProperty(raw,'PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER',shape==='accessor'?{enumerable:true,get(){reads++;throw null;}}:{enumerable:shape!=='hidden',value:shape==='true'||shape==='hidden'?'true':shape==='boolean'?false:{private:'canary'}});
    assert.equal(accept(raw),false);
  }
  assert.equal(reads,0);
});
import {initializeStripeSandboxState,openStripeSandboxFixture,runStripeSandboxSmoke} from './smoke-stage-5a.mjs';
const subject=await import('./stripe-manual-form-options.mjs').catch(()=>({}));
const flags={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'};
const options=()=>({env:{...flags},runId:'11111111-2222-4333-8444-555555555555',expectedSource:'e'.repeat(40),daemon:'unix:///Users/test/docker.sock',manualPort:18083,
  ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025}});
test('every historical smoke guard rejects manual before metadata, private env or IO access',async()=>{
  let touched=0;const env={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC:'true'};
  const input=new Proxy({env},{get(target,key){if(key==='env')return env;touched++;throw null;}}),metadata=new Proxy({},{get(){touched++;throw null;}});
  for(const call of [()=>initializeStripeSandboxState(metadata,input),()=>openStripeSandboxFixture(input),()=>runStripeSandboxSmoke(input)])
    await assert.rejects(call(),{message:'stripe_sandbox_OPT_IN_REQUIRED'});
  assert.equal(touched,0);
});
test('manual options expose an inert dedicated gate and frozen seven-port configuration',()=>{
  assert.equal(typeof subject.manualFormOptIn,'function');assert.equal(typeof subject.validateManualFormOptions,'function');
  assert.equal(subject.manualFormOptIn(flags),true);const input=options(),value=subject.validateManualFormOptions(input);
  assert.deepEqual(value,input);assert.ok(Object.isFrozen(value));assert.ok(Object.isFrozen(value.env));assert.ok(Object.isFrozen(value.ports));
  input.ports.demo=19000;assert.equal(value.ports.demo,18081);
});
for(const [name,value] of [...Object.keys(flags).map(name=>[name,undefined]),...Object.keys(flags).map(name=>[name,'TRUE']),
  ...['PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC','PAYMENT_STRIPE_SANDBOX_FULL_RUN','PAYMENT_STRIPE_SANDBOX_OWNED_RUN','PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS'].map(name=>[name,'true']),
  ['DEBUG','*'],['NODE_DEBUG','http'],['PWDEBUG','1'],['PW_TRACE','on']])
test('manual admission refuses '+name+'='+value+' before non-admission or private access',()=>{
  assert.equal(typeof subject.validateManualFormOptions,'function');let touched=0;const input=options();input.env[name]=value;
  for(const target of [input,input.env])Object.defineProperty(target,'privateCanary',{get(){touched++;throw null;}});
  Object.defineProperty(input,'ports',{get(){touched++;throw null;}});
  assert.equal(subject.manualFormOptIn(input.env),false);assert.equal(subject.validateManualFormOptions(input),null);assert.equal(touched,0);
});
for(const patch of [{manualPort:80},{manualPort:65536},{manualPort:18083.5},{manualPort:'18083'},{manualPort:undefined},{manualPort:18081},
  {expectedSource:'E'.repeat(40)},{runId:'2f3f49b1-df07-41b1-b978-bc884b390813'},{daemon:'unix:///tmp/../tmp/socket'},{daemon:'tcp://127.0.0.1:2375'},
  {ports:{backend:18080}},{ports:{...options().ports,demo:18080}},{extra:true}])
test('manual options refuse malformed configuration '+JSON.stringify(patch),()=>{
  assert.equal(typeof subject.validateManualFormOptions,'function');assert.equal(subject.validateManualFormOptions({...options(),...patch}),null);
});
for(const key of ['env','ports','manualPort','runId','expectedSource','daemon'])
test('manual options reject hostile '+key+' accessor without invoking it',()=>{
  assert.equal(typeof subject.validateManualFormOptions,'function');let touched=0;const input=options();
  Object.defineProperty(input,key,{enumerable:true,get(){touched++;throw null;}});
  assert.equal(subject.validateManualFormOptions(input),null);assert.equal(touched,0);
});
test('manual gate and port records reject accessors without executing them',()=>{
  assert.equal(typeof subject.manualFormOptIn,'function');let touched=0;const input=options();
  Object.defineProperty(input.env,'PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC',{enumerable:true,get(){touched++;throw null;}});
  assert.equal(subject.manualFormOptIn(input.env),false);assert.equal(subject.validateManualFormOptions(input),null);
  const other=options();Object.defineProperty(other.ports,'demo',{enumerable:true,get(){touched++;throw null;}});
  assert.equal(subject.validateManualFormOptions(other),null);assert.equal(touched,0);
});
test('manual gate reads only allowlisted environment descriptors, never private entries',()=>{
  let touched=0;const env=new Proxy({...flags,PRIVATE_CANARY:'synthetic-private-value'},{getOwnPropertyDescriptor(target,key){
    if(key==='PRIVATE_CANARY'){touched++;throw null;}return Reflect.getOwnPropertyDescriptor(target,key);
  }});
  assert.equal(subject.manualFormOptIn(env),true);assert.notEqual(subject.validateManualFormOptions({...options(),env}),null);assert.equal(touched,0);
});
