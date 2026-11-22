import test from 'node:test';import assert from 'node:assert/strict';
const api=await import('./stripe-billing-login.mjs').catch(()=>({}));
test('authorization parser emits only official current handoff',()=>{assert.equal(typeof api.billingAuthorization,'function');assert.match(api.billingAuthorization(JSON.stringify({browser_url:'https://dashboard.stripe.com/stripecli/auth',verification_code:'red-blue-green',next_step:'stripe login --complete-device'})),/red-blue-green/);for(const raw of ['{}','{"browser_url":"https://evil.test","verification_code":"sk_test_secret","next_step":"curl evil"}','{"browser_url":"https://dashboard.stripe.com","browser_url":"https://dashboard.stripe.com","verification_code":"abcd","next_step":"stripe login --complete-device"}'])assert.throws(()=>api.billingAuthorization(raw));});
import {EventEmitter} from 'node:events';import {fileURLToPath} from 'node:url';
import CancellationReporter from '../examples/demo-site/tests/support/stripe-billing-cancellation-reporter.ts';
function loginFixture(){
 const root=fileURLToPath(new URL('../',import.meta.url)).replace(/\/$/,''),runId='11111111-2222-4333-8444-555555555555',directory=root+'/test-results/stripe-sandbox/'+runId+'/cli',events=[];
 const descriptor=complete=>({file:'/usr/bin/sandbox-exec',args:['-p','(version 1)(allow default)(deny process-exec (literal "/usr/bin/security"))','/opt/homebrew/bin/stripe','--config',directory+'/config/stripe/config.toml','--project-name','default','--color','off','--log-level','error','login',...(complete?['--complete-device']:[])],options:{cwd:root,shell:false,env:{PATH:'/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C',HOME:directory+'/home',XDG_CONFIG_HOME:directory+'/config',NO_COLOR:'1'},maxBuffer:65536,timeout:complete?600000:180000}});
 return {descriptor,runId,events};
}
test('protected login requires callback and captured child close and emits only allowlisted handoff',async()=>{
 const {descriptor,runId,events}=loginFixture();
 const cli={loginInvocation:async()=>descriptor(false),completeLoginInvocation:async()=>{assert.deepEqual(events,['closed','output']);return descriptor(true);}};
 let calls=0;const execFile=(_file,_args,_options,callback)=>{const child=new EventEmitter(),first=++calls===1;queueMicrotask(()=>{callback(null,first?JSON.stringify({browser_url:'https://dashboard.stripe.com/stripecli/auth',verification_code:'four-safe-words',next_step:'stripe login --complete-device'}):'','');setTimeout(()=>{events.push('closed');child.emit('close',0,null);},2);});return child;};
 assert.equal(await api.completeBillingLogin(cli,{runId},{deadline:Date.now()+1000},{execFile,output:text=>{assert.match(text,/^Authorize the isolated Sandbox CLI/);assert.ok(!text.includes('next_step'));events.push('output');}}),true);assert.equal(calls,2);assert.deepEqual(events,['closed','output','closed']);
});

test('failed login producer validates the fixed device URI and pairing code before stdout fanout',async()=>{
 const device='https://access.stripe.com/stripecli/oauth2/device',opaque='T'.repeat(43);
 for(const [uri,code,allowed] of [[device,'ABCD-EFGH',true],[device+'?x='+opaque,'ABCD-EFGH',false],[device,opaque,false],
  [device.replace('access.','dashboard.'),'ABCD-EFGH',false],[device,'abcd-efgh',false],[device+'?','ABCD-EFGH',false],
  [device.replace('/stripecli/','/other/../stripecli/'),'ABCD-EFGH',false]]){
  const {descriptor,runId}=loginFixture(),output=[];
  const cli={loginInvocation:()=>descriptor(false),completeLoginInvocation:()=>descriptor(true)};
  let calls=0;
  const execFile=(_file,_args,_options,cb)=>{const child=new EventEmitter(),first=++calls===1;
   queueMicrotask(()=>{cb(null,first?JSON.stringify({browser_url:uri,verification_code:code,next_step:'stripe login --complete-device'}):'','');child.emit('close',0,null);});
   return child;};
  const result=api.completeBillingLogin(cli,{runId,selectedCase:'initial_failed_renewal_grace_recovery'},
   {deadline:Date.now()+1000},{execFile,output:text=>output.push(text)});
  if(allowed)assert.equal(await result,true);else await assert.rejects(result);
  assert.deepEqual(output,allowed?['Authorize the isolated Sandbox CLI at '+device+' using code ABCD-EFGH. Keep this terminal open.\n']:[]);
  assert.equal(calls,allowed?2:1);
 }
});

test('cancellation login starts completion only after producing a handoff its reporter displays',async()=>{
 const device='https://access.stripe.com/stripecli/oauth2/device';
 for(const [uri,code,allowed] of [[device,'ABCD-EFGH',true],[device+'?other=1','ABCD-EFGH',false],[device,'four-safe-words',false]]){
  const {runId,descriptor}=loginFixture(),reporter=new CancellationReporter(),displayed=[];
  const expected='Authorize the isolated Sandbox CLI at '+device+' using code ABCD-EFGH. Keep this terminal open.\n';
  let completions=0,emitted=0;
  const cli={loginInvocation:()=>descriptor(false),completeLoginInvocation:()=>{
   assert.deepEqual(displayed,[expected]);completions++;return descriptor(true);
  }};
  const execFile=(_file,args,_options,callback)=>{
   const child=new EventEmitter();
   queueMicrotask(()=>{callback(null,args.includes('--complete-device')?'':JSON.stringify({browser_url:uri,verification_code:code,next_step:'stripe login --complete-device'}),'');child.emit('close',0,null);});
   return child;
  };
  const output=line=>{
   emitted++;
   const write=process.stdout.write;
   try{
    process.stdout.write=chunk=>{displayed.push(String(chunk));return true;};
    reporter.onStdOut(line.slice(0,17));reporter.onStdOut(line.slice(17));
   }finally{process.stdout.write=write;}
  };
  const result=api.completeBillingLogin(cli,{runId,selectedCase:'initial_cancel_withdraw'},{deadline:Date.now()+1000},{execFile,output});
  if(allowed)assert.equal(await result,true);else await assert.rejects(result);
  assert.equal(emitted,allowed?1:0);assert.equal(completions,allowed?1:0);assert.deepEqual(displayed,allowed?[expected]:[]);
 }
});
