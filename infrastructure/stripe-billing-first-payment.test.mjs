import test from 'node:test';
import assert from 'node:assert/strict';
const api=await import('./stripe-billing-first-payment.mjs').catch(()=>({}));
const opt=await import('./stripe-billing-first-payment-options.mjs').catch(()=>({}));
const runtimeApi=await import('./stripe-billing-first-payment-runtime.mjs').catch(()=>({}));
const id=n=>'11111111-2222-4333-8444-'+String(n).padStart(12,'0');
const options=()=>({env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_FIRST_PAYMENT:'true'},runId:id(1),expectedSource:'e'.repeat(40),daemon:'unix:///Users/test/docker.sock',ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},controlPort:18083,frozenTime:1790000000,approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},selectedCase:'initial_success'});
const limits=()=>({deadline:Date.now()+10000});
const scope=()=>({runId:id(1),expectedSource:'e'.repeat(40),accountId:id(2),siteId:id(3),customerId:id(4),priceId:id(5),productId:id(6),endpoints:{demo:'http://127.0.0.1:18081'}});
const prepared=()=>({phase:'PREPARED',code:'OK',run_id:id(1),account_id:id(2),site_id:id(3),customer_id:id(4),price_id:id(5),subscription_id:id(7),operation_id:id(8),action:{kind:'PROVIDER_CLIENT',secret:'action-secret',expires_at:'2099-01-01T00:00:00Z'},session:{protocol:'SERVER_CONFIRM_V2',provider:'stripe',flow:'PAYMENT',publishable_key:'pk_test_example',client_secret:'pi_example_secret_example',action_token:null,attempt_id:id(9),capability:'selected-capability',expires_at:'2099-01-01T00:00:00Z'}});
const confirmation=()=>({attempt_id:id(9),capability:'selected-capability',confirmation_token:'ctoken_example',idempotency_key:'sdk-original-key'});
const confirmed=()=>({...Object.fromEntries(Object.entries(prepared()).filter(([k])=>!['action','session'].includes(k))),phase:'OBSERVE',protocol:'SERVER_CONFIRM_V2',attempt_id:id(9),state:'OBSERVE',retry_allowed:false,successor_attempt_id:null,next_action_allowed:false,expires_at:'2099-01-01T00:00:00Z'});
function harness(change={}){
 const calls=[],record=n=>async()=>{calls.push(n);return true;};let fields=scope();
 const fixture={resources:()=>({...fields}),provision:record('provision'),withOtpCode:record('otp'),acknowledgeCustomer:record('customer'),beforeDispatch:async(kind,key)=>{calls.push(kind+'-dispatch:'+key);return true;},acknowledge:async(kind,v)=>{calls.push(kind+'-ack');if(kind==='create')fields={...fields,...v};return true;},close:record('fixture-close')};
 const bootstrap={resources:()=>({cliDirectory:'/private/cli'}),prepare:record('bootstrap-prepare'),start:record('start'),stop:record('stop'),verifyForwardTarget(){},persistWebhookSecret(){}};
 const cli={validateBinding:record('binding'),startListener:record('listen'),stopListener:record('listener-stop'),assertBillingListener:record('listener-check')};
 const runtime={ready:record('ready'),prepare:async()=>{calls.push('runtime-prepare');return prepared();},confirm:async req=>{calls.push('runtime-confirm:'+req.idempotency_key);return confirmed();},observe:async()=>{calls.push('observe');return {...confirmed(),phase:'SETTLED_LOCAL',period_start:new Date(1790000000000).toISOString(),paid_through:new Date(1792592000000).toISOString(),access_evaluated_at:new Date(1790000000000).toISOString(),access_valid_until:new Date(1790000300000).toISOString()};},close:record('runtime-close')};
 const dependencies={preflight:record('preflight'),createBootstrap:async()=>bootstrap,createCli:async()=>cli,login:record('login'),createFixture:async()=>fixture,createRuntime:()=>runtime,prove:async()=>({cashVerified:true,journalVerified:true,signedCallbacksVerified:true,ownedVerified:true}),...change};return {calls,fixture,bootstrap,cli,runtime,dependencies};
}
const journey=async p=>{await p.prepare({token:'customer-token',origin:scope().endpoints.demo},'create-original',limits());await p.confirm(confirmation(),limits());return true;};
test('closed options require distinct seventh port fixed budget and every exclusion',()=>{
 assert.equal(typeof opt.validateBillingFirstPaymentOptions,'function');const accepted=opt.validateBillingFirstPaymentOptions(options());assert.deepEqual(accepted.budget,{clock:1,customer:1,create:1,confirm:1});assert.ok(Object.isFrozen(accepted));assert.deepEqual(opt.validateBillingFirstPaymentOptions(accepted),accepted);
 for(const mutate of [o=>o.controlPort=o.ports.backend,o=>o.controlPort=8080,o=>o.frozenTime=NaN,o=>o.frozenTime=0,o=>o.budget={clock:2,customer:1,create:1,confirm:1},o=>o.env.PAYMENT_STRIPE_BILLING_ACCEPTANCE='true',o=>o.env.PAYMENT_STAGE4_TEST_RUNTIME='true',o=>o.env.PAYMENT_STRIPE_ACCEPTANCE='true',o=>o.env.PAYMENT_STRIPE_NATIVE_ACCEPTANCE='true',o=>o.selectedCase='renewal',o=>o.daemon='unix:///tmp/../test.sock']){const o=options();mutate(o);assert.equal(opt.validateBillingFirstPaymentOptions(o),null);}
 let reads=0;const o=options();Object.defineProperty(o,'frozenTime',{get(){reads++;}});assert.equal(opt.validateBillingFirstPaymentOptions(o),null);assert.equal(reads,0);
});
test('inert owner rejects admission before accessing dependencies',async()=>{assert.equal(typeof api.createOwnedBillingFirstPayment,'function');let reads=0;const deps=new Proxy({},{get(){reads++;throw null;}});await assert.rejects(api.createOwnedBillingFirstPayment({...options(),env:{}},deps));assert.equal(reads,0);const h=harness();const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies);assert.deepEqual(h.calls,[]);assert.equal(owner.resources().scope,'OWNED_INITIAL_PAYMENT');});
test('owner records originals before dispatch and requires independent proof and cleanup',async()=>{const h=harness(),owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies);let port;const result=await owner.run(async p=>{port=p;return journey(p);},limits());assert.equal(result.initialAccepted,true);assert.equal(result.full5bAccepted,false);assert.deepEqual(Object.keys(port).sort(),['acknowledgeCustomer','confirm','prepare','resources','withOtpCode']);assert.ok(!JSON.stringify(result).includes('capability'));assert.deepEqual(h.calls.filter(x=>/dispatch|ack$|runtime-(prepare|confirm)/.test(x)),['create-dispatch:create-original','runtime-prepare','create-ack','confirm-dispatch:sdk-original-key','runtime-confirm:sdk-original-key','confirm-ack']);await assert.rejects(owner.run(journey,limits()),/SINGLE_USE/);});
for(const kind of ['prepare','confirm'])test('lost '+kind+' ACK stops without retry even if caller catches',async()=>{const h=harness();h.runtime[kind]=async()=>{h.calls.push('lost');throw {get message(){throw null;}};};const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies);await assert.rejects(owner.run(async p=>{try{await journey(p);}catch{}try{await journey(p);}catch{}return true;},limits()));assert.equal(h.calls.filter(x=>x==='lost').length,1);assert.ok(h.calls.includes('stop'));assert.equal(owner.resources().initialAccepted,false);});
test('unexpected next action never accepts or observes',async()=>{const h=harness();h.runtime.confirm=async()=>({...confirmed(),state:'REQUIRES_ACTION',next_action_allowed:true});const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies);await assert.rejects(owner.run(journey,limits()));assert.ok(!h.calls.includes('observe'));});
test('fresh observations bounded to thirty',async()=>{const h=harness();h.runtime.observe=async()=>{h.calls.push('observe');return confirmed();};const owner=await api.createOwnedBillingFirstPayment(options(),{...h.dependencies,pause:async()=>{}});await assert.rejects(owner.run(journey,limits()));assert.equal(h.calls.filter(x=>x==='observe').length,30);});
test('abort stops exact owned bootstrap while financial request is unresolved',async()=>{const h=harness();let release,signal;h.runtime.confirm=(_r,l)=>{signal=l.signal;return new Promise(r=>release=r);};const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies),controller=new AbortController();const pending=owner.run(journey,{...limits(),signal:controller.signal});pending.catch(()=>{});while(!release)await new Promise(r=>setTimeout(r,1));controller.abort();await assert.rejects(pending);assert.equal(signal.aborted,true);assert.ok(h.calls.includes('stop'));release(confirmed());assert.equal(owner.resources().initialAccepted,false);});
for(const failure of ['ownedVerified','signedCallbacksVerified','cleanup'])test('missing '+failure+' rejects acceptance',async()=>{const h=harness();if(failure==='cleanup')h.bootstrap.stop=async()=>false;else h.dependencies.prove=async()=>({ownedVerified:true,cashVerified:true,journalVerified:true,signedCallbacksVerified:true,[failure]:false});const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies);await assert.rejects(owner.run(journey,limits()));assert.equal(owner.resources().initialAccepted,false);});
test('runtime private callback binds exact routes and bounded body with no retry',async()=>{assert.equal(typeof runtimeApi.createBillingFirstPaymentRuntime,'function');const calls=[];const bootstrap={withFirstPaymentRuntime:async(cb)=>{calls.push('private');return cb({endpoint:'http://127.0.0.1:18083',capability:'x'.repeat(43)});}};const runtime=runtimeApi.createBillingFirstPaymentRuntime({configuration:opt.validateBillingFirstPaymentOptions(options()),bootstrap},{fetch:async(url,init)=>{calls.push({url,init});return new Response(JSON.stringify(url.endsWith('/prepare')?prepared():url.endsWith('/confirm')?confirmed():{phase:'NEW',code:'OK',run_id:id(1)}),{status:200});}});const lifetime=limits();await runtime.ready(lifetime);await runtime.prepare({...scope(),customerToken:'customer-token',origin:scope().endpoints.demo,createKey:'create-original'},lifetime);await runtime.confirm(confirmation(),lifetime);const sent=calls.filter(x=>typeof x==='object');assert.equal(sent[1].url,'http://127.0.0.1:18083/initial-payment/prepare');assert.equal(JSON.parse(sent[2].init.body).idempotency_key,'sdk-original-key');assert.equal(sent[2].init.headers.Authorization,'Bearer '+'x'.repeat(43));assert.equal(runtime.resources,undefined);await assert.rejects(runtime.confirm(confirmation(),limits()));assert.equal(calls.filter(x=>typeof x==='object').length,3);});
test('every normalizing legacy parser rejects first-payment mode',async()=>{for(const [path,name,keys]of [['stripe-billing-acceptance-options','billingAcceptanceOptIn',[]],['stripe-manual-payment-options','manualPaymentOptIn',['ALLOW_TEST_OPERATIONS','MANUAL_PAYMENT']],['stripe-manual-form-options','manualFormOptIn',['ALLOW_PREPARE_ONLY','MANUAL_FORM_DIAGNOSTIC']],['stripe-manual-refund-options','manualRefundOptIn',['ALLOW_TEST_OPERATIONS','MANUAL_REFUND']],['stripe-manual-refund-remainder-options','manualRefundRemainderOptIn',['ALLOW_TEST_OPERATIONS','MANUAL_REFUND_REMAINDER']],['stripe-sandbox-remaining-options','remainingOnlyOptIn',['ALLOW_TEST_OPERATIONS','OWNED_RUN','REMAINING_ONLY']]]){const module=await import('./'+path+'.mjs');const env={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',...(keys.length?Object.fromEntries(keys.map(k=>['PAYMENT_STRIPE_SANDBOX_'+k,'true'])):{PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true'})};assert.equal(module[name](env),true);env.PAYMENT_STRIPE_BILLING_FIRST_PAYMENT='true';assert.equal(module[name](env),false,path);}});
test('runtime refuses deadline renewal before a second transport',async()=>{let calls=0;const runtime=runtimeApi.createBillingFirstPaymentRuntime({configuration:options(),bootstrap:{withFirstPaymentRuntime:cb=>cb({endpoint:'http://127.0.0.1:18083',capability:'x'.repeat(43)})}},{fetch:async()=>{calls++;return new Response(JSON.stringify({phase:'NEW',code:'OK',run_id:id(1)}));}}),l=limits();await runtime.ready(l);await assert.rejects(runtime.prepare({...scope(),origin:scope().endpoints.demo,customerToken:'customer-token',createKey:'key'},{deadline:l.deadline+1}));assert.equal(calls,1);});
for(const fault of ['oversized','status','extra','lost'])test('private runtime stops on '+fault+' response and never retries financial call',async()=>{let calls=0;const runtime=runtimeApi.createBillingFirstPaymentRuntime({configuration:options(),bootstrap:{withFirstPaymentRuntime:cb=>cb({endpoint:'http://127.0.0.1:18083',capability:'x'.repeat(43)})}},{fetch:async()=>{calls++;if(calls===1)return new Response(JSON.stringify({phase:'NEW',code:'OK',run_id:id(1)}));if(fault==='lost')throw {get message(){throw null;}};return new Response(fault==='oversized'?'x'.repeat(16385):JSON.stringify({...prepared(),...(fault==='extra'?{private_key:'do-not-forward'}:{})}),{status:fault==='status'?400:200});}}),l=limits();await runtime.ready(l);await assert.rejects(runtime.prepare({...scope(),origin:scope().endpoints.demo,customerToken:'customer-token',createKey:'key'},l));await assert.rejects(runtime.prepare({...scope()},l));assert.equal(calls,2);});
test('owner rejects settlement identity drift before proof',async()=>{const h=harness(),observe=h.runtime.observe;h.runtime.observe=async()=>({...await observe(),subscription_id:id(99)});let proofs=0;h.dependencies.prove=async()=>{proofs++;return {ownedVerified:true,cashVerified:true,journalVerified:true,signedCallbacksVerified:true};};const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies);await assert.rejects(owner.run(journey,limits()));assert.equal(proofs,0);});
test('rejected original dispatch persistence makes no runtime call',async()=>{const h=harness();h.fixture.beforeDispatch=async()=>false;const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies);await assert.rejects(owner.run(journey,limits()));assert.ok(!h.calls.includes('runtime-prepare'));});
test('total bound includes bootstrap and cleanup false remains unconfirmed',async()=>{const h=harness();h.bootstrap.stop=async()=>false;const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies);await assert.rejects(owner.run(journey,{deadline:Date.now()+2700001}));assert.ok(!h.calls.includes('preflight'));assert.equal(owner.resources().initialAccepted,false);});
test('expiry cleanup cannot renew the bootstrap captured lifetime',async()=>{const h=harness();let originalDeadline;h.runtime.confirm=async()=>{while(Date.now()<=originalDeadline+5){}return confirmed();};h.bootstrap.stop=async l=>l.deadline<=originalDeadline+120000;const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies);originalDeadline=Date.now()+30;await assert.rejects(owner.run(journey,{deadline:originalDeadline}));assert.equal(owner.resources().cleanupConfirmed,true);});
test('cleanup follows shared bootstrap listener-stop prerequisite and remains bounded',async()=>{const h=harness(),owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies);await owner.run(journey,limits());assert.deepEqual(h.calls.filter(x=>['runtime-close','listener-stop','stop','fixture-close'].includes(x)),['runtime-close','listener-stop','stop','fixture-close']);});
test('real owner and private client compose exact Task1 prepare confirm observe DTOs',async()=>{const h=harness(),requests=[];h.bootstrap.withFirstPaymentRuntime=async cb=>cb({endpoint:'http://127.0.0.1:18083',capability:'x'.repeat(43)});h.dependencies.createRuntime=args=>runtimeApi.createBillingFirstPaymentRuntime(args,{fetch:async(url,init)=>{requests.push({url,body:init.body&&JSON.parse(init.body)});let v;if(url.endsWith('/status'))v={phase:'NEW',code:'OK',run_id:id(1)};else if(url.endsWith('/prepare'))v=prepared();else if(url.endsWith('/confirm'))v=confirmed();else {const settled=await h.runtime.observe();v=Object.fromEntries(Object.entries(settled).filter(([k])=>!['protocol','attempt_id','state','retry_allowed','successor_attempt_id','next_action_allowed','expires_at'].includes(k)));}return new Response(JSON.stringify(v));}});const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies);assert.equal((await owner.run(journey,limits())).initialAccepted,true);assert.deepEqual(requests.map(r=>r.url.split('/').at(-1)),['status','prepare','confirm','observe']);assert.equal(requests[1].body.customer_token,'customer-token');assert.equal(requests[2].body.idempotency_key,'sdk-original-key');assert.deepEqual(requests[3].body,{});});

// Inert diagnostic contracts: Response is synthetic and every dependency is injected.
const serverStages=['REQUEST','PREFLIGHT','CLOCK_ADMISSION','CLOCK_CREATE','CUSTOMER_CREATE','LOCAL_RESERVE','SUBSCRIPTION_CREATE','LOCAL_RECORD','RECEIPT','ACTION_SELECT','ACTION_SCOPE','ACTION_AUTHORITY','ACTION_VERIFICATION','ACTION_GRAPH','ACTION_GRAPH_LINE_ASSOCIATION','ACTION_GRAPH_INVOICE_SETTLEMENT','ACTION_GRAPH_PAYMENT_ALLOCATION','ACTION_RESERVATION','ACTION_RESULT','ACTION_BINDING','SESSION_SELECT','RESPONSE_BUILD','UNCLASSIFIED'];
async function diagnosticRuntime(reply,bootstrapChange){
 let calls=0;const bootstrap={withFirstPaymentRuntime:cb=>cb({endpoint:'http://127.0.0.1:18083',capability:'x'.repeat(43)})};
 const runtime=runtimeApi.createBillingFirstPaymentRuntime({configuration:options(),bootstrap},{fetch:async()=>{calls++;return calls===1?new Response(JSON.stringify({phase:'NEW',code:'OK',run_id:id(1)})):reply();}}),l=limits();
 await runtime.ready(l);if(bootstrapChange)bootstrap.withFirstPaymentRuntime=bootstrapChange;
 return {runtime,l,calls:()=>calls};
}
for(const stage of serverStages)test('fixed prepare server diagnostic '+stage+' survives terminal reuse',async()=>{
 const f=await diagnosticRuntime(()=>new Response(JSON.stringify({code:'INITIAL_PAYMENT_STOPPED',stage}),{status:400}));
 let first;try{await f.runtime.prepare(scope(),f.l);}catch(e){first=e;}
 assert.equal(runtimeApi.billingFirstPaymentDiagnostic(first),'PREPARE_SERVER_'+stage);
 let duplicate;try{await f.runtime.prepare(scope(),f.l);}catch(e){duplicate=e;}
 assert.equal(runtimeApi.billingFirstPaymentDiagnostic(duplicate),'PREPARE_SERVER_'+stage);assert.equal(f.calls(),2);
});
for(const [name,reply,expected]of [
 ['transport',()=>{throw new Proxy({},{get(){throw null;}});},'PREPARE_REQUEST'],
 ['status',()=>new Response('private-sentinel',{status:503}),'PREPARE_HTTP'],
 ['malformed',()=>new Response('{secret-sentinel'),'PREPARE_RESPONSE'],
 ['oversized',()=>new Response('x'.repeat(16385)),'PREPARE_RESPONSE'],
 ['success-extra',()=>new Response(JSON.stringify({...prepared(),secret:'private-sentinel'})),'PREPARE_RESPONSE'],
 ...['{}','{','{"code":"INITIAL_PAYMENT_STOPPED","stage":"UNKNOWN"}','{"code":"INITIAL_PAYMENT_STOPPED","stage":"REQUEST","secret":"private-sentinel"}','{"code":"INITIAL_PAYMENT_STOPPED","stage":"REQUEST","stage":"PREFLIGHT"}','{"code":"INITIAL_PAYMENT_STOPPED","stage":"sk_test_secret"}','x'.repeat(16385)].map((body,i)=>['invalid-server-'+i,()=>new Response(body,{status:400}),'PREPARE_HTTP']),
])test('bounded fixed diagnostic '+name,async()=>{const f=await diagnosticRuntime(reply);let failure;try{await f.runtime.prepare(scope(),f.l);}catch(e){failure=e;}assert.equal(runtimeApi.billingFirstPaymentDiagnostic(failure),expected);assert.equal(f.calls(),2);});
// The three graph stages use the existing strict error envelope only.
for(const stage of ["ACTION_GRAPH_LINE_ASSOCIATION","ACTION_GRAPH_INVOICE_SETTLEMENT","ACTION_GRAPH_PAYMENT_ALLOCATION"])for(const [kind,body,status,expected]of [
 ['extra',JSON.stringify({code:'INITIAL_PAYMENT_STOPPED',stage,secret:'private-sentinel'}),400,'PREPARE_HTTP'],
 ['wrong-code',JSON.stringify({code:'OK',stage}),400,'PREPARE_HTTP'],
 ['wrong-status',JSON.stringify({code:'INITIAL_PAYMENT_STOPPED',stage}),200,'PREPARE_RESPONSE'],
 ['duplicate-stage','{"code":"INITIAL_PAYMENT_STOPPED","stage":"'+stage+'","stage":"ACTION_GRAPH"}',400,'PREPARE_HTTP'],
 ['escaped-stage','{"code":"INITIAL_PAYMENT_STOPPED","stage":"\\u0041'+stage.slice(1)+'"}',400,'PREPARE_HTTP'],
 ['forged-stage',JSON.stringify({code:'INITIAL_PAYMENT_STOPPED',stage:stage+'_FOREIGN'}),400,'PREPARE_HTTP'],
 ['object-stage',JSON.stringify({code:'INITIAL_PAYMENT_STOPPED',stage:{stage}}),400,'PREPARE_HTTP'],
])test('graph diagnostic rejects '+stage+' '+kind+' and preserves first failure',async()=>{
 const f=await diagnosticRuntime(()=>new Response(body,{status}));
 let first;try{await f.runtime.prepare(scope(),f.l);}catch(e){first=e;}
 assert.equal(runtimeApi.billingFirstPaymentDiagnostic(first),expected);
 let duplicate;try{await f.runtime.prepare(scope(),f.l);}catch(e){duplicate=e;}
 assert.equal(runtimeApi.billingFirstPaymentDiagnostic(duplicate),expected);assert.equal(f.calls(),2);
});
test('prepare callback admission denial has authority diagnostic and no transport',async()=>{const f=await diagnosticRuntime(()=>{throw null;},async()=>false);let failure;try{await f.runtime.prepare(scope(),f.l);}catch(e){failure=e;}assert.equal(runtimeApi.billingFirstPaymentDiagnostic(failure),'PREPARE_AUTHORITY');assert.equal(f.calls(),1);});
test('diagnostic lookup never inspects foreign rejection or forged fields',()=>{let reads=0;const foreign=new Proxy({diagnostic:'PREPARE_SERVER_REQUEST'},{get(){reads++;throw null;},getOwnPropertyDescriptor(){reads++;throw null;}});assert.equal(runtimeApi.billingFirstPaymentDiagnostic(foreign),null);assert.equal(runtimeApi.billingFirstPaymentDiagnostic(null),null);assert.equal(reads,0);});
test('owner keeps first runtime diagnostic when browser catches and duplicate/cleanup fail',async()=>{
 const h=harness();h.bootstrap.withFirstPaymentRuntime=cb=>cb({endpoint:'http://127.0.0.1:18083',capability:'x'.repeat(43)});let dispatches=0;
 h.dependencies.createRuntime=args=>runtimeApi.createBillingFirstPaymentRuntime(args,{fetch:async url=>{if(url.endsWith('/status'))return new Response(JSON.stringify({phase:'NEW',code:'OK',run_id:id(1)}));dispatches++;return new Response('{"code":"INITIAL_PAYMENT_STOPPED","stage":"SUBSCRIPTION_CREATE"}',{status:400});}});
 h.bootstrap.stop=async()=>{throw new Proxy({},{get(){throw null;}});};
 const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies);
 await assert.rejects(owner.run(async p=>{for(let i=0;i<2;i++)try{await journey(p);}catch{}},limits()));
 const r=await owner.close();assert.equal(r.diagnostic,'PREPARE_SERVER_SUBSCRIPTION_CREATE');assert.equal(r.cleanupConfirmed,false);assert.equal(r.initialAccepted,false);assert.equal(dispatches,1);assert.deepEqual(h.calls.filter(x=>x.startsWith('create-dispatch:')),['create-dispatch:create-original']);assert.ok(!h.calls.some(x=>x.startsWith('confirm-dispatch:')));
 assert.equal(api.formatBillingFirstPaymentDiagnostic(r),'owned_initial_payment_diagnostic code=PREPARE_SERVER_SUBSCRIPTION_CREATE cleanup=UNCONFIRMED');
});
test('evolving customer resource acknowledgement precedes prepare and fixed ACK rejection',async()=>{
 const h=harness();let customer=null;h.fixture.resources=()=>({...scope(),customerId:customer});h.fixture.acknowledgeCustomer=async v=>{customer=v.customerId;return true;};h.runtime.prepare=async s=>{assert.equal(s.customerId,id(4));return {...prepared(),customer_id:id(99)};};
 const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies);await assert.rejects(owner.run(async p=>{assert.equal(p.resources().customerId,null);await p.acknowledgeCustomer({customerId:id(4),siteId:id(3)});return journey(p);},limits()));assert.equal(owner.resources().diagnostic,'PREPARE_ACK');assert.ok(!h.calls.includes('create-ack'));
});
test('foreign runtime rejection stays generic without property reads',async()=>{const h=harness();let reads=0;h.runtime.prepare=async()=>{throw new Proxy({diagnostic:'PREPARE_SERVER_REQUEST'},{get(){reads++;throw null;}});};const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies);await assert.rejects(owner.run(journey,limits()));assert.equal(owner.resources().diagnostic,null);assert.equal(reads,0);});
test('cancelled prepare remains consumed and late acknowledgement cannot replace diagnostic',async()=>{
 const h=harness();let release;h.runtime.prepare=()=>new Promise(r=>release=r);const controller=new AbortController(),owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies);
 const pending=owner.run(journey,{...limits(),signal:controller.signal});pending.catch(()=>{});while(!release)await new Promise(r=>setTimeout(r,1));controller.abort();await assert.rejects(pending);assert.equal(owner.resources().diagnostic,'PREPARE_REQUEST');release(prepared());await new Promise(r=>setTimeout(r,1));assert.equal(owner.resources().diagnostic,'PREPARE_REQUEST');assert.ok(!h.calls.includes('create-ack'));assert.equal(owner.resources().initialAccepted,false);
});

test('pending prepare duplicate latches first failure and rejects late successful response',async()=>{
 let release;const f=await diagnosticRuntime(()=>new Promise(r=>release=r));const pending=f.runtime.prepare(scope(),f.l);pending.catch(()=>{});while(!release)await new Promise(r=>setTimeout(r,1));await assert.rejects(f.runtime.prepare(scope(),f.l));release(new Response(JSON.stringify(prepared())));await assert.rejects(pending);assert.equal(f.calls(),2);
});
test('pending prepare duplicate cannot acquire a later server failure diagnostic',async()=>{
 let release;const f=await diagnosticRuntime(()=>new Promise(r=>release=r));const pending=f.runtime.prepare(scope(),f.l);pending.catch(()=>{});while(!release)await new Promise(r=>setTimeout(r,1));await assert.rejects(f.runtime.prepare(scope(),f.l));release(new Response('{"code":"INITIAL_PAYMENT_STOPPED","stage":"REQUEST"}',{status:400}));let failure;try{await pending;}catch(e){failure=e;}assert.equal(runtimeApi.billingFirstPaymentDiagnostic(failure),null);
});
test('diagnostic formatter requires rejected closed result and parser rejects terminal newline',async()=>{
 const h=harness(),owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies),initial=owner.resources();assert.equal(api.formatBillingFirstPaymentDiagnostic(initial),null);
 assert.equal(api.formatBillingFirstPaymentDiagnostic({...initial,status:'CLOSED',code:'ACK',diagnostic:'PREPARE_ACK'}),'owned_initial_payment_diagnostic code=PREPARE_ACK cleanup=UNCONFIRMED');
 for(const patch of [{status:'RUNNING'},{initialAccepted:true},{diagnostic:'private-sentinel'},{cleanupConfirmed:'CONFIRMED'}])assert.equal(api.formatBillingFirstPaymentDiagnostic({...initial,status:'CLOSED',code:'ACK',diagnostic:'PREPARE_ACK',...patch}),null);
 assert.equal(api.parseBillingFirstPaymentDiagnosticLine('owned_initial_payment_diagnostic code=PREPARE_ACK cleanup=CONFIRMED\n'),null);
});
test('runtime cancellation while reading prepare body carries request uncertainty',async()=>{
 const controller=new AbortController();let entered;const entry=new Promise(r=>entered=r);const f=await diagnosticRuntime(()=>({status:200,body:{async *[Symbol.asyncIterator](){entered();await new Promise(()=>{});},cancel:async()=>{}}}));
 const pending=f.runtime.prepare(scope(),{...f.l,signal:controller.signal});pending.catch(()=>{});await entry;controller.abort();let failure;try{await pending;}catch(e){failure=e;}assert.equal(runtimeApi.billingFirstPaymentDiagnostic(failure),'PREPARE_REQUEST');
});

for(const [name,reply,code]of [
 ['success-wrong-phase',()=>new Response(JSON.stringify({...prepared(),phase:'STOPPED'})),'PREPARE_RESPONSE'],
 ['invalid-utf8',()=>new Response(new Uint8Array([255])),'PREPARE_RESPONSE'],
 ['unauthorized-error-dto',()=>new Response('{"code":"INITIAL_PAYMENT_STOPPED","stage":"REQUEST"}',{status:401}),'PREPARE_HTTP'],
 ['wrong-success-status',()=>new Response('{"code":"INITIAL_PAYMENT_STOPPED","stage":"REQUEST"}',{status:200}),'PREPARE_RESPONSE'],
 ['hostile-stream',()=>({status:200,body:{async *[Symbol.asyncIterator](){throw new Proxy({},{get(){throw null;}});},cancel:async()=>{}}}),'PREPARE_RESPONSE'],
])test('prepare diagnostic rejects '+name,async()=>{const f=await diagnosticRuntime(reply);let failure;try{await f.runtime.prepare(scope(),f.l);}catch(e){failure=e;}assert.equal(runtimeApi.billingFirstPaymentDiagnostic(failure),code);assert.equal(f.calls(),2);});
test('fixed server error on confirm remains generic',async()=>{
 let calls=0;const runtime=runtimeApi.createBillingFirstPaymentRuntime({configuration:options(),bootstrap:{withFirstPaymentRuntime:cb=>cb({endpoint:'http://127.0.0.1:18083',capability:'x'.repeat(43)})}},{fetch:async()=>{calls++;return calls===1?new Response(JSON.stringify({phase:'NEW',code:'OK',run_id:id(1)})):calls===2?new Response(JSON.stringify(prepared())):new Response('{"code":"INITIAL_PAYMENT_STOPPED","stage":"REQUEST"}',{status:400});}}),l=limits();await runtime.ready(l);await runtime.prepare(scope(),l);let failure;try{await runtime.confirm(confirmation(),l);}catch(e){failure=e;}assert.equal(runtimeApi.billingFirstPaymentDiagnostic(failure),null);assert.equal(calls,3);
});

for(const [kind,admit,calls]of [
 ['no-callback',async()=>true,1],
 ['false-after-callback',async cb=>{await cb({endpoint:'http://127.0.0.1:18083',capability:'x'.repeat(43)});return false;},2],
 ['duplicate-callback',async cb=>{await cb({endpoint:'http://127.0.0.1:18083',capability:'x'.repeat(43)});return cb({endpoint:'http://127.0.0.1:18083',capability:'x'.repeat(43)});},2],
])test('prepare callback authority refuses '+kind,async()=>{const f=await diagnosticRuntime(()=>new Response(JSON.stringify(prepared())),admit);let failure;try{await f.runtime.prepare(scope(),f.l);}catch(e){failure=e;}assert.equal(runtimeApi.billingFirstPaymentDiagnostic(failure),'PREPARE_AUTHORITY');assert.equal(f.calls(),calls);});

test('prepare rejects missing bounds with fixed request metadata',async()=>{const f=await diagnosticRuntime(()=>new Response(JSON.stringify(prepared())));let failure;try{await f.runtime.prepare(scope());}catch(e){failure=e;}assert.equal(runtimeApi.billingFirstPaymentDiagnostic(failure),'PREPARE_REQUEST');assert.equal(f.calls(),1);});
test('owner preserves request diagnostic when only prepare caller signal aborts',async()=>{
 const h=harness();let entered;const entry=new Promise(r=>entered=r);h.runtime.prepare=async()=>{entered();return new Promise(()=>{});};const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies),controller=new AbortController();
 const pending=owner.run(async p=>{await p.prepare({token:'customer-token',origin:scope().endpoints.demo},'original-key',{...limits(),signal:controller.signal});},limits());pending.catch(()=>{});await entry;controller.abort();await assert.rejects(pending);assert.equal(owner.resources().diagnostic,'PREPARE_REQUEST');assert.equal(h.calls.filter(x=>x.startsWith('create-dispatch:')).length,1);
});

for(const stage of ["ACTION_SCOPE","ACTION_AUTHORITY","ACTION_VERIFICATION","ACTION_GRAPH","ACTION_GRAPH_LINE_ASSOCIATION","ACTION_GRAPH_INVOICE_SETTLEMENT","ACTION_GRAPH_PAYMENT_ALLOCATION","ACTION_RESERVATION","ACTION_RESULT","ACTION_BINDING"])test('action boundary '+stage+' reaches closed formatter and parser once',async()=>{
 const h=harness();let requests=0;h.bootstrap.withFirstPaymentRuntime=cb=>cb({endpoint:'http://127.0.0.1:18083',capability:'x'.repeat(43)});
 h.dependencies.createRuntime=args=>runtimeApi.createBillingFirstPaymentRuntime(args,{fetch:async url=>{requests++;return url.endsWith('/status')?new Response(JSON.stringify({phase:'NEW',code:'OK',run_id:id(1)})):new Response(JSON.stringify({code:'INITIAL_PAYMENT_STOPPED',stage}),{status:400});}});
 h.bootstrap.stop=async()=>{h.calls.push('stop');return false;};
 const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies);
 await assert.rejects(owner.run(async p=>{try{await journey(p);}catch{}try{await journey(p);}catch{}return true;},limits()));
 const result=await owner.close(),line=api.formatBillingFirstPaymentDiagnostic(result);
 assert.equal(line,'owned_initial_payment_diagnostic code=PREPARE_SERVER_'+stage+' cleanup=UNCONFIRMED');
 assert.equal(api.parseBillingFirstPaymentDiagnosticLine(line),line);
 assert.equal(result.initialAccepted,false);assert.equal(requests,2);
 assert.equal(h.calls.filter(c=>c.startsWith('create-dispatch:')).length,1);
 assert.ok(!h.calls.some(c=>c.startsWith('confirm-dispatch:')||c==='observe'||c==='create-ack'));
 assert.deepEqual(h.calls.filter(c=>['listener-stop','stop','fixture-close'].includes(c)),['listener-stop','stop','fixture-close']);
});

// Break: clipping bootstrap.stop to 30s loses valid cleanup inside its shared 120s.
for(const listenerMs of [0,25000])test('startup failure permits 45s owned stop after '+listenerMs+'ms listener cleanup',async t=>{
 t.mock.timers.enable({apis:['Date','setTimeout'],now:1000});
 const h=harness();let enteredListener,enteredStop;const listening=new Promise(r=>enteredListener=r),stopping=new Promise(r=>enteredStop=r);let stopLimits;
 h.bootstrap.start=async()=>{h.calls.push('start');throw null;};
 h.cli.stopListener=async()=>{h.calls.push('listener-stop');enteredListener();await new Promise(r=>setTimeout(r,listenerMs));return true;};
 h.bootstrap.stop=async l=>{h.calls.push('stop');stopLimits=l;enteredStop();await new Promise(r=>setTimeout(r,45000));return true;};
 const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies),running=owner.run(journey,{deadline:101000}),rejected=assert.rejects(running,/billing_START/);
 await listening;t.mock.timers.tick(listenerMs);await stopping;t.mock.timers.tick(45000);await rejected;
 assert.equal(stopLimits.deadline,121000);assert.equal(owner.resources().cleanupConfirmed,true);assert.equal(owner.resources().code,'START');assert.equal(owner.resources().initialAccepted,false);
 const closed=await owner.close();assert.equal(closed.cleanupConfirmed,true);assert.equal(h.calls.filter(c=>c==='stop').length,1);assert.deepEqual(h.calls.filter(c=>['listener-stop','stop'].includes(c)),['listener-stop','stop']);
});
for(const lateMs of [95000,95001])test('startup cleanup keeps original shared ceiling when stop resolves at '+(25000+lateMs)+'ms',async t=>{
 t.mock.timers.enable({apis:['Date','setTimeout'],now:1000});
 const h=harness();let enteredListener,enteredStop,release;const listening=new Promise(r=>enteredListener=r),stopping=new Promise(r=>enteredStop=r);let stopLimits;
 h.bootstrap.start=async()=>{throw null;};h.cli.stopListener=async()=>{enteredListener();await new Promise(r=>setTimeout(r,25000));return true;};
 h.bootstrap.stop=async l=>{h.calls.push('stop');stopLimits=l;enteredStop();return new Promise(r=>release=r);};
 const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies),running=owner.run(journey,{deadline:101000}),rejected=assert.rejects(running,/billing_START/);
 await listening;t.mock.timers.tick(25000);await stopping;assert.equal(stopLimits.deadline,121000);t.mock.timers.tick(lateMs);await rejected;
 assert.equal(stopLimits.signal.aborted,true);assert.equal(owner.resources().cleanupConfirmed,false);release(true);await Promise.resolve();await Promise.resolve();
 assert.equal((await owner.close()).cleanupConfirmed,false);assert.equal(owner.resources().code,'START');assert.equal(h.calls.filter(c=>c==='stop').length,1);
});

for(const diagnostic of ['START_BACKEND_COMMAND_REJECTED','START_BACKEND_COMMAND_NONZERO_EXIT','START_BACKEND_COMMAND_OUTPUT_LIMIT','START_BACKEND_COMMAND_SPAWN_FAILURE','START_BACKEND_HEALTH_NOT_RUNNING','START_BACKEND_HEALTH_UNHEALTHY','START_BACKEND_HEALTH_PROOF_REJECTED','START_BACKEND_PUBLIC_HEALTH_REJECTED','START_BACKEND_COMMAND_ABORT','START_BACKEND_COMMAND_TIMEOUT','START_BACKEND_HEALTH_WAIT_ABORT','START_BACKEND_HEALTH_WAIT_TIMEOUT','START_BACKEND_HEALTH_REJECTED'])test('startup diagnostic owner retains '+diagnostic+' before cleanup',async()=>{
 const h=harness();let first=diagnostic,reads=0,errorReads=0;h.bootstrap.startupDiagnostic=()=>{reads++;return first;};
 h.bootstrap.start=async()=>{h.calls.push('start');throw new Proxy({diagnostic},{get(){errorReads++;throw null;}});};
 h.cli.stopListener=async()=>{h.calls.push('listener-stop');first='START_BACKEND_COMMAND_ABORT';throw null;};h.bootstrap.stop=async()=>{h.calls.push('stop');first=null;throw null;};
 const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies);await assert.rejects(owner.run(journey,limits()));const r=await owner.close();
 assert.equal(r.diagnostic,diagnostic);assert.equal(r.phase,'START');assert.equal(r.code,'START');assert.equal(r.cleanupConfirmed,false);assert.equal(r.initialAccepted,false);assert.equal(reads,1);assert.equal(errorReads,0);
 assert.ok(!h.calls.some(c=>['runtime-ready','provision','runtime-prepare','create-ack','confirm-ack','observe'].includes(c)));assert.equal(h.calls.filter(c=>c==='start').length,1);
 const line=api.formatBillingFirstPaymentDiagnostic(r);assert.equal(line,'owned_initial_payment_diagnostic code='+diagnostic+' cleanup=UNCONFIRMED');assert.equal(api.parseBillingFirstPaymentDiagnosticLine(line),line);assert.ok(line.length<=160);
 assert.equal(runtimeApi.isBillingFirstPaymentDiagnostic(diagnostic),false);
});
for(const kind of ['unknown','malformed','object','throwing','getter'])test('startup diagnostic owner ignores '+kind+' accessor result',async()=>{
 const h=harness();let reads=0;
 h.bootstrap.start=async()=>{throw new Proxy({},{get(){reads++;throw null;}});};
 if(kind==='getter')Object.defineProperty(h.bootstrap,'startupDiagnostic',{get(){reads++;throw null;}});
 else h.bootstrap.startupDiagnostic=()=>{if(kind==='throwing')throw new Proxy({},{get(){reads++;throw null;}});return kind==='unknown'?'START_PRIVATE_COMMAND_REJECTED':kind==='malformed'?'START_BACKEND_COMMAND_REJECTED\nprivate-sentinel':new Proxy({},{get(){reads++;throw null;}});};
 const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies);await assert.rejects(owner.run(journey,limits()));assert.equal(owner.resources().diagnostic,null);assert.equal(api.formatBillingFirstPaymentDiagnostic(owner.resources()),null);assert.equal(reads,0);
});
test('startup labels never enter authenticated private PREPARE wire diagnostics',async()=>{
 for(const stage of ['START_BACKEND_COMMAND_NONZERO_EXIT','BACKEND_HEALTH_UNHEALTHY']){
  const f=await diagnosticRuntime(()=>new Response(JSON.stringify({code:'INITIAL_PAYMENT_STOPPED',stage}),{status:400}));let error;try{await f.runtime.prepare(scope(),f.l);}catch(e){error=e;}assert.equal(runtimeApi.billingFirstPaymentDiagnostic(error),'PREPARE_HTTP');assert.equal(f.calls(),2);
 }
});

test('startup diagnostic owner retains false START result before cleanup',async()=>{
 const h=harness();h.bootstrap.start=async()=>false;h.bootstrap.startupDiagnostic=()=> 'START_BOOTSTRAP_PROOF_REJECTED';h.bootstrap.stop=async()=>{h.bootstrap.startupDiagnostic=()=>null;return true;};
 const owner=await api.createOwnedBillingFirstPayment(options(),h.dependencies);await assert.rejects(owner.run(journey,limits()));assert.equal(owner.resources().diagnostic,'START_BOOTSTRAP_PROOF_REJECTED');assert.equal(owner.resources().code,'START');
});
