import test,{after,mock} from 'node:test';
import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import net from 'node:net';
import filesystem from 'node:fs/promises';
import {syncBuiltinESMExports} from 'node:module';
import type {Route,Request,Response,Page,Browser} from '@playwright/test';
import type {SandboxAcceptanceExtras} from '../../../../infrastructure/stripe-sandbox-extras.mjs';
import {runSandboxBrowserStep,rethrowSandboxBrowserFailure} from '../../../../infrastructure/stripe-sandbox-acceptance.mjs';
// @ts-expect-error Native Node 25 type erasure requires the source extension.
import {forwardDonationCreate,acknowledgeDonationResponse,runDonationOtp,withOwnedCardContexts,runOwnedSandboxDonations} from './stripe-sandbox-acceptance.ts';

const forbidden=()=>{throw new Error('synthetic_test_forbids_external_boundary');};
for(const name of ['execFile','exec','spawn','fork','execSync','execFileSync','spawnSync'] as const)mock.method(childProcess,name,forbidden);
for(const name of ['createServer','connect','createConnection'] as const)mock.method(net,name,forbidden);
mock.method(globalThis,'fetch',forbidden);
for(const name of ['open','readFile','lstat','realpath','readdir','mkdir','rename','unlink'] as const)mock.method(filesystem,name,forbidden);
syncBuiltinESMExports();after(()=>{mock.restoreAll();syncBuiltinESMExports();});
const binding={endpoints:{backend:'http://127.0.0.1:18080',demo:'http://127.0.0.1:14173',receiver:'http://127.0.0.1:18090'},
  site:{id:'00000000-0000-4000-8000-000000000002',key:'s5a-site'},campaign:{id:'00000000-0000-4000-8000-000000000006',key:'s5a-donate',status:'ACTIVE',version:1},
  price:{id:'00000000-0000-4000-8000-000000000004',key:'s5a-price',version:1}};
const endpoint=binding.endpoints.backend+'/api/v1/public/sites/s5a-site/donation-campaigns/s5a-donate/checkouts';
const limits=()=>({deadline:Date.now()+120000,signal:new AbortController().signal});
const trustedFailure=(error:unknown):boolean=>{
  assert.ok(Object.isFrozen(error));assert.equal(Object.getPrototypeOf(error),null);assert.deepEqual(Reflect.ownKeys(error as object),[]);
  assert.throws(()=>rethrowSandboxBrowserFailure(error),actual=>actual===error);return true;
};
// Break: an inner trusted failure must keep identity through acquisition, bounded waits and forwarding cleanup.
test('donation helpers preserve authenticated first token identity through their catches',async()=>{
  let primary:unknown,reads=0,closes=0;
  const hostile=new Proxy({},{get(){reads++;throw null;},ownKeys(){reads++;throw null;}});
  try{await runSandboxBrowserStep('anonymous','DONATION_SETUP',async()=>{throw hostile;});}catch(error){primary=error;}
  trustedFailure(primary);
  const same=(error:unknown)=>error===primary;
  await assert.rejects(runOwnedSandboxDonations({newContext:async()=>{throw primary;}} as unknown as Browser,{} as SandboxAcceptanceExtras,binding,limits()),same);
  await assert.rejects(runOwnedSandboxDonations({newContext:async()=>({newPage:async()=>{throw primary;},close:async()=>{closes++;throw hostile;}})} as unknown as Browser,{} as SandboxAcceptanceExtras,binding,limits()),same);
  const route={request:()=>request(),continue:forbidden,abort:async()=>{throw hostile;}} as Pick<Route,'request'|'continue'|'abort'>;
  await assert.rejects(forwardDonationCreate(route,{beforeDonationCreate:async()=>{throw primary;}},'anonymous',binding,limits()),same);
  await assert.rejects(runDonationOtp({locator:()=>({first(){return this;}}),on:()=>{},off:()=>{}} as unknown as Page,{beginOtp:async()=>{throw primary;}} as unknown as SandboxAcceptanceExtras,binding,limits()),same);
  assert.equal(closes,1);assert.equal(reads,0);
});
// Break: a later owned context close rejection must not replace the first trusted card failure.
test('card owner preserves trusted first failure while awaiting rejecting created-context cleanup',async()=>{
  const contexts:unknown[]=[],events:string[]=[];
  const browser={contexts:()=>contexts} as unknown as Browser;
  let primary:unknown;
  try{await runSandboxBrowserStep('success','RESUME',async()=>{throw null;});}catch(error){primary=error;}
  await assert.rejects(withOwnedCardContexts(browser,limits(),async()=>{
    contexts.push({close:async()=>{events.push('cleanup-start');await Promise.resolve();events.push('cleanup-end');throw new Error('private-cleanup');}});
    events.push('journey-failed');rethrowSandboxBrowserFailure(primary);
  }),error=>error===primary);
  assert.deepEqual(events,['journey-failed','cleanup-start','cleanup-end']);
});
test('card owner keeps arbitrary hostile journey failures opaque through rejecting cleanup',async()=>{
  let reads=0,closes=0;const contexts:unknown[]=[],hostile=new Proxy({},{get(){reads++;throw null;},ownKeys(){reads++;throw null;}});
  await assert.rejects(withOwnedCardContexts({contexts:()=>contexts} as unknown as Browser,limits(),async()=>{
    contexts.push({close:async()=>{closes++;throw hostile;}});throw hostile;
  }),{message:'stripe_sandbox_MANUAL_CHECK_BROWSER'});
  assert.equal(reads,0);assert.equal(closes,1);
});
function request(method='POST',body='{"amount_minor":500}',headers:Record<string,string>={origin:binding.endpoints.demo,'content-type':'application/json','idempotency-key':'sdk-original','x-original':'preserved'}) {
  return {url:()=>endpoint,method:()=>method,postData:()=>body,allHeaders:async()=>headers,redirectedFrom:()=>null} as unknown as Request;
}
// Break: forwarding before durable key, or replacing the body/auth headers changes the genuine SDK write.
test('genuine donation POST forwards the original wire with only the durable key replaced',async()=>{
  const events:string[]=[],original=request();let forwarded:unknown;
  const route={request:()=>original,continue:async value=>{events.push('continue');forwarded=value;},abort:async()=>{events.push('abort');}} as Pick<Route,'request'|'continue'|'abort'>;
  const extras:Pick<SandboxAcceptanceExtras,'beforeDonationCreate'>={beforeDonationCreate:async(kind,facts)=>{events.push('durable');assert.equal(kind,'anonymous');assert.deepEqual(facts,{siteKey:'s5a-site',campaignKey:'s5a-donate',amountMinor:500,hasCustomerAuthority:false});return 's5a:original:donation:anonymous';}};
  await forwardDonationCreate(route,extras,'anonymous',binding,limits());
  assert.deepEqual(events,['durable','continue']);assert.deepEqual(forwarded,{headers:{origin:binding.endpoints.demo,'content-type':'application/json','idempotency-key':'s5a:original:donation:anonymous','x-original':'preserved'}});
});
const invalidDonationRequests=()=>[request('POST','{"amount_minor":501}'),request('POST','{"amount_minor":500,"message":""}'),request('GET'),request('POST','{"amount_minor":500}',{origin:binding.endpoints.demo,authorization:'Bearer private'})];
// Break: allowing a failed, altered or unauthorized POST through is a new uncontrolled financial write.
test('bad donation POST rejects before key acquisition for its owning route to abort',async()=>{
  for(const original of invalidDonationRequests()){
    const events:string[]=[];
    const route={request:()=>original,continue:async()=>{events.push('continue');},abort:async()=>{events.push('abort');}} as Pick<Route,'request'|'continue'|'abort'>;
    const extras={beforeDonationCreate:async()=>{events.push('durable');return 'key';}} as unknown as SandboxAcceptanceExtras;
    await assert.rejects(forwardDonationCreate(route,extras,'anonymous',binding,limits()),{message:'stripe_sandbox_BROWSER_DONATION_PENDING'});assert.deepEqual(events,[]);
  }
});
test('owning donation route aborts each bad request once before any key or continue',async()=>{
  for(const original of invalidDonationRequests()){
    const events:string[]=[];let contexts=0,handle!:(route:unknown)=>Promise<void>;
    const route={request:()=>original,continue:async()=>{events.push('continue');},abort:async()=>{events.push('abort');}};
    const widget={first(){return this;},locator:()=>({click:async()=>{},evaluate:async()=>true}),getByRole:(_role:string,{name}:{name:string})=>({click:async()=>{events.push(name);await handle(route);}})};
    const page={goto:async()=>{},evaluate:async()=>true,locator:()=>widget,waitForResponse:()=>new Promise(()=>{}),on:()=>{},off:()=>{}};
    const browser={newContext:async()=>{contexts++;return {route:async(_url:string,callback:typeof handle)=>{handle=callback;},newPage:async()=>page,close:async()=>{events.push('close');}};}} as unknown as Browser;
    const extras={beforeDonationCreate:async()=>{events.push('durable');return 'key';},ackDonationCreate:async()=>{events.push('ack');return true;},
      beforeDonationPay:async()=>{events.push('before-pay');return true;},confirmDonation:async()=>{events.push('confirm');return true;}} as unknown as SandboxAcceptanceExtras;
    await assert.rejects(runOwnedSandboxDonations(browser,extras,binding,limits()),trustedFailure);
    assert.deepEqual(events,['Create donation','abort','close']);assert.equal(contexts,1);
  }
});
// Break: treating OPTIONS as a mutation consumes an operation key before the actual SDK write.
test('genuine CORS preflight continues unchanged without a financial key',async()=>{
  let options:unknown='unset';const route={request:()=>request('OPTIONS'),continue:async value=>{options=value;},abort:forbidden} as Pick<Route,'request'|'continue'|'abort'>;
  await forwardDonationCreate(route,{beforeDonationCreate:forbidden} as unknown as SandboxAcceptanceExtras,'anonymous',binding,limits());assert.equal(options,undefined);
});
// Break: using a different/replayed response or passing capabilities into ACK can certify the wrong Donation.
test('only the original successful genuine response yields the safe donation ACK',async()=>{
  const original=request(),acks:unknown[]=[];
  const value={checkout_id:'00000000-0000-4000-8000-000000000010',payment_id:'00000000-0000-4000-8000-000000000020',checkout_token:'private-memory-only',
    amount_minor:500,currency:'EUR',price:null,provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',provider_client_payload:{},status:'CREATED',expires_at:new Date(Date.now()+120000).toISOString(),
    donation:{donation_id:'00000000-0000-4000-8000-000000000030',campaign_key:'s5a-donate',campaign_version:1}};
  const response={request:()=>original,url:()=>endpoint,status:()=>201,body:async()=>Buffer.from(JSON.stringify(value))} as Pick<Response,'request'|'url'|'status'|'body'>;
  const extras:Pick<SandboxAcceptanceExtras,'ackDonationCreate'>={ackDonationCreate:async(kind,identity)=>{acks.push({kind,identity});return true;}};
  await acknowledgeDonationResponse(response,original,extras,'anonymous',binding,limits());
  assert.deepEqual(acks,[{kind:'anonymous',identity:{checkoutId:value.checkout_id,paymentId:value.payment_id,donationId:value.donation.donation_id,campaignKey:'s5a-donate',campaignVersion:1,amountMinor:500,currency:'EUR'}}]);
  await assert.rejects(acknowledgeDonationResponse({...response,request:()=>request()},original,extras,'anonymous',binding,limits()),{message:'stripe_sandbox_BROWSER_DONATION_PENDING'});assert.equal(acks.length,1);
});

// Break: an OTP retry, early ACK or session injection bypasses the actual original challenge.
test('OTP sends and verifies once with original genuine responses before the safe customer ACK',async()=>{
  const events:string[]=[],challenge='00000000-0000-4000-8000-000000000040',customer='00000000-0000-4000-8000-000000000050',expires=new Date(Date.now()+120000).toISOString();
  const challengeUrl=binding.endpoints.backend+'/api/v1/public/customer-auth/email-otp/challenges',verifyUrl=challengeUrl+'/'+challenge+'/verify';
  const genuine=(url:string,status:number,body:object,requestBody:object)=>({url:()=>url,status:()=>status,body:async()=>Buffer.from(JSON.stringify(body)),request:()=>({method:()=>url.endsWith('/me')?'GET':'POST',url:()=>url,postData:()=>JSON.stringify(requestBody),redirectedFrom:()=>null})}) as unknown as Response;
  const responses=[genuine(challengeUrl,202,{challenge_id:challenge,expires_at:expires},{site_key:'s5a-site',email:'synthetic@example.test',intent:'AUTHENTICATE'}),
    genuine(verifyUrl,200,{session_token:'s'.repeat(43),expires_at:expires,scopes:['CUSTOMER_READ','CHECKOUT_CREATE'],customer:{id:customer,status:'ACTIVE',masked_email:'***@***'}},{site_key:'s5a-site',code:'123456'}),
    genuine(binding.endpoints.backend+'/api/v1/customer/me',200,{id:customer,status:'ACTIVE',masked_email:'***@***',authentication_modes:['EMAIL_OTP'],session_expires_at:expires},{})];
  let observe:(request:Request)=>void=()=>{};
  const page={locator:()=>({first(){return this;},getByRole:(_role:string,settings:{name:string})=>({evaluate:async(_fn:unknown,value:unknown)=>{events.push(settings.name==='Email'?'email-entry':'code-entry');assert.ok(value==='synthetic@example.test'||value==='123456');return true;},click:async()=>{events.push(settings.name==='Request code'?'send-click':'verify-click');observe(responses[settings.name==='Request code'?0:1]!.request());}})}),
    waitForResponse:async(predicate:(response:Response)=>boolean)=>{const response=responses.find(predicate);assert.ok(response);return response;},waitForFunction:async()=>{events.push('active-session');},on:(_event:string,fn:(request:Request)=>void)=>{observe=fn;},off:()=>{}} as unknown as Page;
  const extras:Pick<SandboxAcceptanceExtras,'beginOtp'|'ackOtpChallenge'|'withOtpCode'|'beforeOtpVerify'|'ackOtpVerification'>={beginOtp:async cb=>{events.push('challenge-intent');assert.equal(cb(Object.freeze({email:'synthetic@example.test',siteKey:'s5a-site'})),true);return true;},
    ackOtpChallenge:async value=>{events.push('challenge-ack');assert.equal(value.challengeId,challenge);return true;},withOtpCode:async cb=>{events.push('mail-code');assert.equal(cb('123456'),true);return true;},
    beforeOtpVerify:async()=>{events.push('verify-intent');return true;},ackOtpVerification:async value=>{events.push('verify-ack');assert.deepEqual(value,{challengeId:challenge,customerId:customer});return true;}};
  await runDonationOtp(page,extras,binding,limits());
  assert.deepEqual(events,['challenge-intent','email-entry','send-click','challenge-ack','mail-code','code-entry','verify-intent','verify-click','active-session','verify-ack']);
});
test('expired OTP browser phase dispatches no challenge',async()=>{
  await assert.rejects(runDonationOtp({locator:forbidden,off:()=>{}} as unknown as Page,{beginOtp:forbidden} as unknown as SandboxAcceptanceExtras,binding,{deadline:Date.now()-1}),{message:'stripe_sandbox_BROWSER_DONATION_PENDING'});
});
test('card callback abort closes only contexts created by that owning callback before it settles',async()=>{
  const foreign={close:forbidden},controller=new AbortController();let owned:unknown,closed=0;
  const browser={contexts:()=>owned?[foreign,owned]:[foreign]} as unknown as Browser;
  await assert.rejects(withOwnedCardContexts(browser,{deadline:Date.now()+120000,signal:controller.signal},async()=>{
    owned={close:async()=>{closed++;}};controller.abort();await new Promise(resolve=>setImmediate(resolve));
  }),{message:'stripe_sandbox_MANUAL_CHECK_BROWSER'});assert.equal(closed,1);
});
test('expired donation response consumes no body or ACK',async()=>{
  const original=request();let reads=0;
  const response={request:()=>original,url:()=>endpoint,status:()=>201,body:async()=>{reads++;return Buffer.from('{}');}} as Pick<Response,'request'|'url'|'status'|'body'>;
  await assert.rejects(acknowledgeDonationResponse(response,original,{ackDonationCreate:forbidden} as unknown as SandboxAcceptanceExtras,'anonymous',binding,{deadline:Date.now()-1}),{message:'stripe_sandbox_BROWSER_DONATION_PENDING'});assert.equal(reads,0);
});
function admissionClock() {
  const native=globalThis.setTimeout,alarms:{fire:()=>void;handle:ReturnType<typeof setTimeout>}[]=[];
  const timer=mock.method(globalThis,'setTimeout',(fn:(...args:unknown[])=>void,delay?:number,...args:unknown[])=>{
    if(delay!==15000)return native(fn,delay,...args);
    const handle=native(()=>{},1000000);alarms.push({fire:()=>fn(...args),handle});return handle;
  });
  return {count:()=>alarms.length,fire(index:number){const alarm=alarms[index];assert.ok(alarm);clearTimeout(alarm.handle);alarm.fire();},
    close(){timer.mock.restore();for(const alarm of alarms)clearTimeout(alarm.handle);}};
}
// Break: a sole-owner abort must be bounded and drained without replacing the first failure or releasing ownership early.
test('failed forwarding retains its original token through the sole bounded owner abort',async()=>{
  const clock=admissionClock(),events:string[]=[];let primary:unknown,reads=0,keys=0,posts=0,closes=0,aborts=0,abortAlarm=-1,routeSettled=false,settled=false;
  const hostile=new Proxy({},{get(){reads++;throw null;},ownKeys(){reads++;throw null;}});
  try{await runSandboxBrowserStep('anonymous','DONATION_DISPATCH',async()=>{throw hostile;});}catch(error){primary=error;}
  let handle!:(route:unknown)=>Promise<void>,routeWork:Promise<void>|undefined;
  const route={request:()=>request(),continue:async()=>{events.push('forward');throw primary;},abort:async()=>{
    aborts++;abortAlarm=clock.count()-1;await new Promise(()=>{});
  }};
  const widget={first(){return this;},locator:()=>({click:async()=>{},evaluate:async()=>true}),getByRole:(_role:string,{name}:{name:string})=>({click:async()=>{
    events.push(name);assert.equal(name,'Create donation');posts++;routeWork=handle(route).then(()=>{routeSettled=true;});
    await new Promise(resolve=>setImmediate(resolve));
  }})};
  const page={goto:async()=>{},evaluate:async()=>true,locator:()=>widget,waitForResponse:()=>new Promise(()=>{}),on:()=>{},off:()=>{}};
  const browser={newContext:async()=>({route:async(_url:string,callback:typeof handle)=>{handle=callback;},newPage:async()=>page,close:async()=>{closes++;}})} as unknown as Browser;
  const extras={beforeDonationCreate:async()=>{keys++;return 'synthetic-key';},ackDonationCreate:forbidden,beforeDonationPay:forbidden,confirmDonation:forbidden} as unknown as SandboxAcceptanceExtras;
  const running=runOwnedSandboxDonations(browser,extras,binding,limits()).finally(()=>{settled=true;});running.catch(()=>{});
  try{
    await new Promise(resolve=>setImmediate(resolve));await new Promise(resolve=>setImmediate(resolve));
    assert.equal(settled,false);assert.equal(routeSettled,false);assert.equal(aborts,1);assert.ok(abortAlarm>=0);assert.equal(posts,1);assert.equal(keys,1);assert.equal(closes,1);
    assert.deepEqual(events,['Create donation','forward']);assert.equal(reads,0);
    clock.fire(abortAlarm);await routeWork;await assert.rejects(running,error=>error===primary);
    assert.equal(routeSettled,true);assert.equal(aborts,1);assert.equal(keys,1);assert.equal(posts,1);
  }finally{clock.close();}
});
// Break: a resolved POST promise cannot carry a later OPTIONS failure to response/ACK/payment consumers.
for(const [at,wantAcks,wantFills,wantBeforePay,wantPays,wantConfirms] of [
  ['response',0,0,0,0,0],['responseReject',0,0,0,0,0],['body',0,0,0,0,0],['bodyReject',0,0,0,0,0],
  ['ack',1,0,0,0,0],['ackReject',1,0,0,0,0],['fill',1,1,0,0,0],['fillReject',1,1,0,0,0],
  ['beforePay',1,3,1,0,0],['beforePayReject',1,3,1,0,0],['pay',1,3,1,1,0],['payReject',1,3,1,1,0],
  ['confirm',1,3,1,1,1],['confirmReject',1,3,1,1,1],
] as const)test(`late forwarding failure before ${at} retains the first token and stops later authority`,async()=>{
  let primary:unknown,reads=0,keys=0,posts=0,preflights=0,acks=0,fills=0,beforePay=0,pays=0,confirms=0,contexts=0,closes=0,bodyReads=0,forwarded=false;
  const hostile=new Proxy({},{get(){reads++;throw null;},ownKeys(){reads++;throw null;}});
  try{await runSandboxBrowserStep('anonymous','DONATION_DISPATCH',async()=>{throw hostile;});}catch(error){primary=error;}
  const original=request();let handle!:(route:unknown)=>Promise<void>,observe:((response:unknown)=>void)=()=>{},receive!:(response:unknown)=>void,rejectReceipt!:(error:unknown)=>void;
  const receipt=new Promise((resolve,reject)=>{receive=resolve;rejectReceipt=reject;});
  const failPreflight=async()=>{
    assert.equal(forwarded,true,'the original POST has already forwarded');preflights++;
    await handle({request:()=>request('OPTIONS'),continue:async()=>{throw primary;},abort:async()=>{throw hostile;}});
  };
  const visit=async(stage:string)=>{if(at===stage||at===stage+'Reject'){await failPreflight();if(at.endsWith('Reject'))throw hostile;}};
  const value={checkout_id:'00000000-0000-4000-8000-000000000010',payment_id:'00000000-0000-4000-8000-000000000020',checkout_token:'private-memory-only',
    amount_minor:500,currency:'EUR',price:null,provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',provider_client_payload:{},status:'CREATED',expires_at:new Date(Date.now()+120000).toISOString(),
    donation:{donation_id:'00000000-0000-4000-8000-000000000030',campaign_key:'s5a-donate',campaign_version:1}};
  const response={request:()=>original,url:()=>endpoint,status:()=>201,body:async()=>{bodyReads++;await visit('body');return Buffer.from(JSON.stringify(value));}};
  const widget={first(){return this;},locator:()=>({click:async()=>{},evaluate:async()=>true}),getByRole:(_role:string,{name}:{name:string})=>({click:async()=>{
    if(name==='Create donation'){
      posts++;await handle({request:()=>original,continue:async()=>{forwarded=true;observe(response);},abort:forbidden});
      // Returning from the POST callback proves its forwarded promise has resolved before the late failure.
      if(at==='response'||at==='responseReject')await failPreflight();
      if(at==='responseReject')rejectReceipt(hostile);else receive(response);
    }else{assert.equal(name,'Pay');pays++;await visit('pay');}
  }}),frameLocator:()=>({locator:()=>({fill:async()=>{fills++;await visit('fill');},isVisible:async()=>false})})};
  const page={goto:async()=>{},evaluate:async()=>true,locator:()=>widget,waitForResponse:()=>receipt,on:(_event:string,callback:typeof observe)=>{observe=callback;},off:()=>{}};
  const browser={newContext:async()=>{contexts++;return {route:async(_url:string,callback:typeof handle)=>{handle=callback;},newPage:async()=>page,close:async()=>{closes++;throw hostile;}};}} as unknown as Browser;
  const extras={beforeDonationCreate:async()=>{keys++;return 'synthetic-key';},ackDonationCreate:async()=>{acks++;await visit('ack');return true;},
    beforeDonationPay:async()=>{beforePay++;await visit('beforePay');return true;},confirmDonation:async()=>{confirms++;await visit('confirm');return true;}} as unknown as SandboxAcceptanceExtras;
  await assert.rejects(runOwnedSandboxDonations(browser,extras,binding,limits()),error=>error===primary);
  assert.equal(posts,1);assert.equal(keys,1);assert.equal(preflights,1);assert.equal(contexts,1);assert.equal(closes,1);assert.equal(reads,0);
  assert.equal(acks,wantAcks);assert.equal(fills,wantFills);assert.equal(beforePay,wantBeforePay);assert.equal(pays,wantPays);assert.equal(confirms,wantConfirms);
  assert.equal(bodyReads,at==='response'||at==='responseReject'?0:1);
});
// I2: local admission expiry abandons ownership even while the original deadline is open.
// Break: the first abort can hide a late failure until after payment or until the owning close has finished.
for(const at of ['body','bodyBound','cleanupBound','postBody','close','unsettled'] as const)test(`pending first abort at ${at} stays owned and cannot escape into another donation`,async()=>{
  const clock=admissionClock();let primary:unknown,received:unknown,reads=0,keys=0,posts=0,acks=0,pays=0,confirms=0,contexts=0,closes=0,aborts=0,abortAlarm=-1,cleanupAlarm=-1,settled=false,routeSettled=false;
  const hostile=new Proxy({},{get(){reads++;throw null;},ownKeys(){reads++;throw null;}});
  try{await runSandboxBrowserStep('anonymous','DONATION_DISPATCH',async()=>{throw hostile;});}catch(error){primary=error;}
  let handle!:(route:unknown)=>Promise<void>,observe:((response:unknown)=>void)=()=>{},releaseAbort!:()=>void,rejectContinuation!:(error:unknown)=>void,routeWork:Promise<void>|undefined;
  const aborted=new Promise<void>(resolve=>{releaseAbort=resolve;}),continuation=new Promise<void>((_resolve,reject)=>{rejectContinuation=reject;}),original=request();
  const startLate=()=>{
    assert.equal(posts,1);assert.equal(routeWork,undefined);
    routeWork=handle({request:()=>request('OPTIONS'),continue:async()=>{if(at==='close'||at==='unsettled')return continuation;throw primary;},abort:async()=>{aborts++;abortAlarm=clock.count()-1;await aborted;}}).then(()=>{routeSettled=true;});routeWork.catch(()=>{});
  };
  const value={checkout_id:'00000000-0000-4000-8000-000000000010',payment_id:'00000000-0000-4000-8000-000000000020',checkout_token:'private-memory-only',
    amount_minor:500,currency:'EUR',price:null,provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',provider_client_payload:{},status:'CREATED',expires_at:new Date(Date.now()+120000).toISOString(),
    donation:{donation_id:'00000000-0000-4000-8000-000000000030',campaign_key:'s5a-donate',campaign_version:1}};
  const response={request:()=>original,url:()=>endpoint,status:()=>201,body:async()=>{
    if(at==='body'||at==='bodyBound'||at==='cleanupBound'){startLate();await new Promise(resolve=>setImmediate(resolve));}
    else if(at==='close'||at==='unsettled')startLate();
    return Buffer.from(JSON.stringify(value));
  }};
  const widget={first(){return this;},locator:()=>({click:async()=>{},evaluate:async()=>true}),getByRole:(_role:string,{name}:{name:string})=>({click:async()=>{
    if(name==='Create donation'){posts++;await handle({request:()=>original,continue:async()=>observe(response),abort:forbidden});}else{assert.equal(name,'Pay');pays++;}
  }}),frameLocator:()=>({locator:()=>({fill:async()=>{},isVisible:async()=>false})})};
  const page={goto:async()=>{},evaluate:async()=>true,locator:()=>widget,waitForResponse:async()=>response,on:(_event:string,callback:typeof observe)=>{observe=callback;},
    off:()=>{if(at==='postBody')startLate();}};
  const browser={newContext:async()=>{
    if(++contexts!==1)throw hostile;
    return {route:async(_url:string,callback:typeof handle)=>{handle=callback;},newPage:async()=>page,close:async()=>{
      closes++;cleanupAlarm=clock.count()-1;if(at==='close')rejectContinuation(primary);await new Promise(resolve=>setImmediate(resolve));
      if(at!=='bodyBound'&&at!=='cleanupBound'&&at!=='unsettled'){releaseAbort();await routeWork;}
    }};
  }} as unknown as Browser;
  const extras={beforeDonationCreate:async()=>{keys++;return 'synthetic-key';},ackDonationCreate:async()=>{acks++;return true;},beforeDonationPay:async()=>true,
    confirmDonation:async()=>{confirms++;return true;}} as unknown as SandboxAcceptanceExtras;
  const running=runOwnedSandboxDonations(browser,extras,binding,limits()).finally(()=>{settled=true;});running.catch(error=>{received=error;});
  try{
    await new Promise(resolve=>setImmediate(resolve));await new Promise(resolve=>setImmediate(resolve));await new Promise(resolve=>setImmediate(resolve));
    if(at==='bodyBound'){
      assert.equal(settled,false,'the owner must retain the pending route through cleanup');assert.equal(aborts,1);assert.ok(abortAlarm>=0);clock.fire(abortAlarm);
    }else if(at==='cleanupBound'||at==='unsettled'){
      assert.equal(settled,false);assert.equal(routeSettled,false);assert.ok(cleanupAlarm>=0);clock.fire(cleanupAlarm);
    }
    await assert.rejects(running,at==='unsettled'?trustedFailure:error=>error===primary);
    if(at==='cleanupBound'){assert.equal(routeSettled,false);clock.fire(abortAlarm);await routeWork;}
    else if(at==='unsettled'){
      const first=received;assert.notEqual(first,primary);rejectContinuation(primary);releaseAbort();await routeWork;assert.equal(received,first);
    }
    assert.equal(contexts,1);assert.equal(closes,1);assert.equal(posts,1);assert.equal(keys,1);assert.equal(aborts,1);assert.equal(reads,0);
    const finishedBeforeFailure=at==='postBody'||at==='close'||at==='unsettled';assert.equal(acks,finishedBeforeFailure?1:0);assert.equal(pays,finishedBeforeFailure?1:0);assert.equal(confirms,finishedBeforeFailure?1:0);
  }finally{releaseAbort();await running.catch(()=>{});await routeWork;clock.close();}
});
test('late donation context after local timeout is closed before the failed owner settles',async()=>{
  const clock=admissionClock();let create!: (context:unknown)=>void,finishClose!:()=>void,closes=0,settled=false;
  const creation=new Promise(resolve=>{create=resolve;}),closing=new Promise<void>(resolve=>{finishClose=resolve;});
  const browser={newContext:()=>creation} as unknown as Browser;
  const running=runOwnedSandboxDonations(browser,{} as SandboxAcceptanceExtras,binding,limits()).finally(()=>{settled=true;});running.catch(()=>{});
  try {
    await new Promise(resolve=>setImmediate(resolve));clock.fire(0);await new Promise(resolve=>setImmediate(resolve));
    const settledBeforeCreation=settled;
    create({close:async()=>{closes++;await closing;}});await new Promise(resolve=>setImmediate(resolve));
    const settledBeforeClose=settled;finishClose();
    await assert.rejects(running,trustedFailure);
    assert.equal(settledBeforeCreation,false);assert.equal(settledBeforeClose,false);assert.equal(closes,1);
  }finally{finishClose();clock.close();}
});
test('late donation context with rejecting close remains a fixed failed owner',async()=>{
  const clock=admissionClock();let create!:(context:unknown)=>void,closes=0;
  const browser={newContext:()=>new Promise(resolve=>{create=resolve;})} as unknown as Browser;
  const running=runOwnedSandboxDonations(browser,{} as SandboxAcceptanceExtras,binding,limits());running.catch(()=>{});
  try {
    await new Promise(resolve=>setImmediate(resolve));clock.fire(0);await new Promise(resolve=>setImmediate(resolve));
    create({close:async()=>{closes++;throw new Error('synthetic-private-close');}});
    await assert.rejects(running,trustedFailure);assert.equal(closes,1);
  }finally{clock.close();}
});
test('late donation context with pending close ends at the independent cleanup bound',async()=>{
  const clock=admissionClock();let create!:(context:unknown)=>void,closes=0;
  const browser={newContext:()=>new Promise(resolve=>{create=resolve;})} as unknown as Browser;
  const running=runOwnedSandboxDonations(browser,{} as SandboxAcceptanceExtras,binding,limits());running.catch(()=>{});
  try {
    await new Promise(resolve=>setImmediate(resolve));clock.fire(0);await new Promise(resolve=>setImmediate(resolve));
    create({close:async()=>{closes++;await new Promise(()=>{});}});await new Promise(resolve=>setImmediate(resolve));
    const cleanupAlarms=clock.count();if(cleanupAlarms>1)clock.fire(1);
    await assert.rejects(running,trustedFailure);assert.equal(cleanupAlarms,2);assert.equal(closes,1);
  }finally{clock.close();}
});
test('creation beyond cleanup bound stays abandoned and its eventual context still closes once',async()=>{
  const clock=admissionClock();let create!:(context:unknown)=>void,closes=0;
  const browser={newContext:()=>new Promise(resolve=>{create=resolve;})} as unknown as Browser;
  const running=runOwnedSandboxDonations(browser,{} as SandboxAcceptanceExtras,binding,limits());running.catch(()=>{});
  try {
    await new Promise(resolve=>setImmediate(resolve));clock.fire(0);await new Promise(resolve=>setImmediate(resolve));
    const cleanupAlarms=clock.count();if(cleanupAlarms>1)clock.fire(1);
    await assert.rejects(running,trustedFailure);
    create({close:async()=>{closes++;}});await new Promise(resolve=>setImmediate(resolve));assert.equal(cleanupAlarms,2);assert.equal(closes,1);
  }finally{clock.close();}
});
test('malformed donation expiry refuses the safe ACK',async()=>{
  const original=request();let acks=0;
  const value={checkout_id:'00000000-0000-4000-8000-000000000010',payment_id:'00000000-0000-4000-8000-000000000020',checkout_token:'s'.repeat(43),
    amount_minor:500,currency:'EUR',price:null,provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',provider_client_payload:{},status:'CREATED',expires_at:'not-a-date',
    donation:{donation_id:'00000000-0000-4000-8000-000000000030',campaign_key:'s5a-donate',campaign_version:1}};
  const response={request:()=>original,url:()=>endpoint,status:()=>201,body:async()=>Buffer.from(JSON.stringify(value))} as Pick<Response,'request'|'url'|'status'|'body'>;
  await assert.rejects(acknowledgeDonationResponse(response,original,{ackDonationCreate:async()=>{acks++;return true;}},'anonymous',binding,limits()),{message:'stripe_sandbox_BROWSER_DONATION_PENDING'});
  assert.equal(acks,0);
});

// I2: successful acquisition must enter its finalizer before handoff admission can fail.
for(const boundary of ['deadline','cancellation'] as const)test(`${boundary} at successful donation context handoff closes once before any page or authority`,async()=>{
  const controller=new AbortController(),nativeNow=Date.now,nativeListen=controller.signal.addEventListener.bind(controller.signal);
  let now=nativeNow(),registrations=0,closes=0,pages=0,authority=0;
  const deadline=now+120000;
  const clock=mock.method(Date,'now',()=>now);
  const listen=mock.method(controller.signal,'addEventListener',(...args:Parameters<AbortSignal['addEventListener']>)=>{
    // The first registration protects acquisition; the second is the acquired owner's handoff.
    if(++registrations===2){if(boundary==='deadline')now=deadline;else controller.abort();}
    nativeListen(...args);
  });
  const browser={newContext:async()=>({close:async()=>{closes++;},newPage:async()=>{pages++;throw new Error('synthetic-page-forbidden');}})} as unknown as Browser;
  const extras=new Proxy({},{get(){authority++;throw new Error('synthetic-authority-forbidden');}}) as SandboxAcceptanceExtras;
  try {
    await assert.rejects(runOwnedSandboxDonations(browser,extras,binding,{deadline,signal:controller.signal}),trustedFailure);
    assert.equal(closes,1);assert.equal(pages,0);assert.equal(authority,0);
  }finally{listen.mock.restore();clock.mock.restore();}
});
