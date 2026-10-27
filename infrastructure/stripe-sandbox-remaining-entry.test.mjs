import assert from 'node:assert/strict';
import {readFileSync,existsSync} from 'node:fs';
import {createRequire} from 'node:module';
import {execFileSync,spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import vm from 'node:vm';
import test from 'node:test';
import * as selectors from './stripe-sandbox-remaining-options.mjs';

const demo=fileURLToPath(new URL('../examples/demo-site/',import.meta.url));
const require=createRequire(new URL('../examples/demo-site/package.json',import.meta.url));
const ts=require('typescript');
const flags=()=>Object.fromEntries(['ENABLED','ALLOW_TEST_OPERATIONS','OWNED_RUN','REMAINING_ONLY'].map(key=>['PAYMENT_STRIPE_SANDBOX_'+key,'true']));
const narrow='tests/stripe-sandbox-remaining.spec.ts',full='tests/stripe-sandbox.spec.ts';
const safe={mode:'REMAINING_5A',remainingAccepted:true,accepted:false,cleanupConfirmed:true,
  proof:{cards:false,payment:true,refunds:true,anonymous:true,emailOtp:true,otp:true,missed:true},
  endpoints:{backend:'http://127.0.0.1:18080',demo:'http://127.0.0.1:14173',receiver:'http://127.0.0.1:18081'},
  site:{id:'synthetic-site',key:'synthetic-site'},price:{id:'synthetic-price',key:'synthetic-price',version:1},
  campaign:{id:'synthetic-campaign',key:'synthetic-campaign',status:'ACTIVE',version:1}};

// Execute the actual compiled entries, replacing only external setup/browser boundaries.
// Breaks caught: stripping raw authority, falling through to full mode, extra card journeys,
// skipped cleanup/proofs, and unsafe discovery configuration.
function entry(file,env,report=safe){
  assert.equal(existsSync(demo+file),true,'dedicated remaining entry must exist');
  const before=[],after=[],cases=[],events=[],options=[];
  const browser=Object.freeze({synthetic:true});
  const fixture=Object.freeze({synthetic:'payment'}),missed=Object.freeze({synthetic:'missed'}),extras=Object.freeze({synthetic:'extras'});
  const test=(title,body)=>cases.push({title,body});
  test.describe={serial:(_title,body)=>body()};test.beforeAll=body=>before.push(body);test.afterAll=body=>after.push(body);
  const owner={resources:()=>report,close:async limits=>{events.push(['close',limits]);return true;},run:async(journeys,limits)=>{
    events.push(['run',Object.keys(journeys),limits]);
    assert.equal(await journeys.payment(fixture),true);
    assert.equal(await journeys.donations(extras),true);
    assert.equal(await journeys.missed(missed),true);return true;
  }};
  const forbidden=()=>{events.push(['FORBIDDEN_RUNTIME']);throw new Error('synthetic_forbidden_runtime');};
  const modules={
    '@playwright/test':{test,expect:value=>({toBe:expected=>assert.equal(value,expected),toEqual:expected=>assert.deepEqual(JSON.parse(JSON.stringify(value)),JSON.parse(JSON.stringify(expected)))})},
    './support/stripe-sandbox-fixtures':{openStripeSandboxFixture:forbidden,runSandboxCard:async(...args)=>{events.push(['card',...args]);}},
    '../../../infrastructure/stripe-sandbox-acceptance.mjs':{openOwnedSandboxAcceptance:forbidden,openOwnedSandboxRemainingAcceptance:async(...args)=>{options.push(args);events.push(['open']);return owner;}},
    '../../../infrastructure/stripe-sandbox-remaining-options.mjs':selectors,
    './support/stripe-sandbox-acceptance':{withOwnedCardContexts:async(actual,limits,body)=>{assert.equal(actual,browser);events.push(['contexts',limits]);await body();return true;},
      runOwnedSandboxDonations:async(...args)=>{events.push(['donations',...args]);return true;}},
  };
  const code=ts.transpileModule(readFileSync(demo+file,'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
  const sandbox={exports:{},require:name=>{assert.ok(Object.hasOwn(modules,name),'unexpected entry import '+name);return modules[name];},process:{env},AbortController,URL,Date};
  vm.runInNewContext(code,sandbox,{filename:file});
  return {before,after,cases,events,options,browser,fixture,missed,extras};
}
async function setup(f){for(const hook of f.before)await hook();}

test('remaining entry admits raw explicit authority and drives only success/payment donations missed in order',async()=>{
  const env={...flags(),PAYMENT_STRIPE_SANDBOX_RUN_ID:'synthetic-run',PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE:'synthetic-source',PAYMENT_STRIPE_SANDBOX_DAEMON:'unix:///synthetic'};
  for(const [index,name] of ['BACKEND','DEMO','RECEIVER','POSTGRES','MAILPITHTTP','MAILPITSMTP'].entries())env['PAYMENT_STRIPE_SANDBOX_PORT_'+name]=String(18080+index);
  const f=entry(narrow,env);assert.equal(f.cases.length,1);await setup(f);
  assert.equal(f.options.length,1);assert.equal(f.options[0][0].env,env,'raw selector must reach owner admission');
  assert.equal(f.options[0][0].runId,'synthetic-run');assert.equal(f.options[0][0].expectedSource,'synthetic-source');
  assert.deepEqual({...f.options[0][0].ports},{backend:18080,demo:18081,receiver:18082,postgres:18083,mailpitHttp:18084,mailpitSmtp:18085});
  await f.cases[0].body({browser:f.browser});for(const hook of f.after)await hook();
  assert.deepEqual(f.events.map(event=>event[0]),['open','run','contexts','card','donations','contexts','card','close']);
  assert.deepEqual(Array.from(f.events[1][1]),['payment','donations','missed']);
  const cards=f.events.filter(event=>event[0]==='card');
  assert.deepEqual(cards,[[ 'card',f.browser,f.fixture,'success'],['card',f.browser,f.missed,'success']]);
  const donation=f.events.find(event=>event[0]==='donations');assert.equal(donation[1],f.browser);assert.equal(donation[2],f.extras);
  assert.deepEqual(JSON.parse(JSON.stringify(donation[3])),{endpoints:safe.endpoints,site:safe.site,price:safe.price,campaign:safe.campaign});
  const limits=f.options[0][1];assert.equal(limits.signal.aborted,true);assert.ok(limits.deadline>Date.now());assert.ok(limits.deadline<=Date.now()+2700000);
  assert.ok(f.events.at(-1)[1].deadline<=Date.now()+120000);
});

for(const key of ['ENABLED','ALLOW_TEST_OPERATIONS','OWNED_RUN','REMAINING_ONLY'])test('remaining entry refuses absent '+key+' before any runtime boundary',async()=>{
  const env=flags();delete env['PAYMENT_STRIPE_SANDBOX_'+key];const f=entry(narrow,env);
  await assert.rejects(setup(f),/stripe_sandbox_OPT_IN_REQUIRED/);assert.deepEqual(f.events,[]);
  for(const hook of f.after)await hook();assert.deepEqual(f.events,[]);
});
for(const key of ['MANUAL_REFUND','MANUAL_REFUND_REMAINDER','FULL_RUN','FORM_DIAGNOSTIC','MANUAL_FORM_DIAGNOSTIC','ALLOW_PREPARE_ONLY','MANUAL_PAYMENT'])test('remaining entry refuses mixed '+key+' before any runtime boundary',async()=>{
  const f=entry(narrow,{...flags(),['PAYMENT_STRIPE_SANDBOX_'+key]:'true'});await assert.rejects(setup(f),/stripe_sandbox_OPT_IN_REQUIRED/);assert.deepEqual(f.events,[]);
});
for(const kind of ['inherited','getter','hidden','malformed'])test('remaining entry rejects '+kind+' selector without getter access',async()=>{
  const env=flags(),key='PAYMENT_STRIPE_SANDBOX_REMAINING_ONLY';let reads=0;
  delete env[key];if(kind==='inherited')Object.setPrototypeOf(env,{[key]:'true'});
  if(kind==='getter')Object.defineProperty(env,key,{enumerable:true,get(){reads++;throw null;}});
  if(kind==='hidden')Object.defineProperty(env,key,{value:'true'});if(kind==='malformed')env[key]='TRUE';
  const f=entry(narrow,env);await assert.rejects(setup(f),/stripe_sandbox_OPT_IN_REQUIRED/);assert.equal(reads,0);assert.deepEqual(f.events,[]);
});
for(const owned of [false,true])for(const value of ['true','TRUE'])test(`old ${owned?'owned':'legacy'} entry refuses remaining selector ${value} before full setup`,async()=>{
  const env={PAYMENT_STRIPE_SANDBOX_REMAINING_ONLY:value,...(owned?{PAYMENT_STRIPE_SANDBOX_OWNED_RUN:'true'}:{})};
  const f=entry(full,env);await assert.rejects(setup(f),/stripe_sandbox_OPT_IN_REQUIRED/);assert.deepEqual(f.events,[]);
});
for(const patch of [{remainingAccepted:false},{accepted:true},{mode:'FULL_5A'},{cleanupConfirmed:false},{proof:{...safe.proof,cards:true}},{proof:{...safe.proof,payment:false}}])test('remaining entry refuses unproven or widened report '+JSON.stringify(patch),async()=>{
  const f=entry(narrow,flags(),{...safe,...patch});await setup(f);await assert.rejects(f.cases[0].body({browser:f.browser}));
  for(const hook of f.after)await hook();assert.equal(f.events.at(-1)[0],'close');
});
test('dedicated config retains bounded private serial execution without automatic services',()=>{
  const file='playwright.stripe-sandbox-remaining.config.ts';assert.equal(existsSync(demo+file),true,'dedicated remaining config must exist');
  const exports={},env={};vm.runInNewContext(ts.transpileModule(readFileSync(demo+file,'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS}}).outputText,
    {exports,process:{env},require:name=>{assert.equal(name,'@playwright/test');return {defineConfig:value=>value,devices:{'Desktop Chrome':{browserName:'chromium'}}};}});
  const config=exports.default;assert.equal(config.testMatch,'stripe-sandbox-remaining.spec.ts');assert.equal(config.workers,1);assert.equal(config.retries,0);
  assert.equal(config.fullyParallel,false);assert.equal(config.timeout,2700000);assert.equal(config.globalTimeout,2820000);assert.equal(config.expect.timeout,15000);
  assert.equal(config.webServer,undefined);assert.equal(config.preserveOutput,'never');assert.deepEqual(Array.from(config.reporter[0]),['./tests/support/stripe-sandbox-reporter.ts']);
  for(const key of ['trace','screenshot','video'])assert.equal(config.use[key],'off');assert.equal(env.PLAYWRIGHT_NO_COPY_PROMPT,'1');
});
test('actual default discovery excludes both Sandbox entries and every native support test',()=>{
  const stdout=execFileSync('pnpm',['exec','playwright','test','--list'],{cwd:demo,encoding:'utf8',timeout:20000,maxBuffer:65536});
  assert.doesNotMatch(stdout,/stripe-sandbox(?:-remaining)?\.spec\.ts|support\/.*\.test\.ts/);assert.match(stdout,/stripe-return-offline\.spec\.ts/);
});
test('actual remaining discovery lists exactly one narrow test',()=>{
  assert.equal(existsSync(demo+'playwright.stripe-sandbox-remaining.config.ts'),true,'dedicated remaining config must exist');
  const stdout=execFileSync('pnpm',['exec','playwright','test','--config','playwright.stripe-sandbox-remaining.config.ts','--list','--reporter=list'],{cwd:demo,encoding:'utf8',timeout:20000,maxBuffer:65536});
  assert.match(stdout,/Total: 1 test in 1 file/);assert.match(stdout,/stripe-sandbox-remaining\.spec\.ts/);assert.doesNotMatch(stdout,/stripe-sandbox\.spec\.ts/);
});
test('actual remaining command with every opt-in absent fails safely before browser launch',()=>{
  assert.equal(existsSync(demo+'playwright.stripe-sandbox-remaining.config.ts'),true,'dedicated remaining config must exist');
  const result=spawnSync('pnpm',['exec','playwright','test','--config','playwright.stripe-sandbox-remaining.config.ts'],{
    cwd:demo,env:{PATH:process.env.PATH,HOME:process.env.HOME,CI:'1',NO_COLOR:'1'},encoding:'utf8',timeout:20000,maxBuffer:65536});
  assert.equal(result.status,1);assert.match(result.stdout,/sandbox_result_failed/);assert.doesNotMatch(result.stdout+result.stderr,/stripe_sandbox_OPT_IN_REQUIRED|browserType\.launch|sk_test_|whsec_/);
});
