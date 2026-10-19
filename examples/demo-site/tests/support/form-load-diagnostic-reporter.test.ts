import test,{mock} from 'node:test';
import assert from 'node:assert/strict';
import type {FullResult,TestCase,TestResult} from '@playwright/test/reporter';
// @ts-expect-error Native Node type erasure requires the source extension.
const module = await import('./form-load-diagnostic-reporter.ts').catch(() => ({}));
const Reporter = (module as {default?:new()=>ReporterAPI}).default;
interface ReporterAPI {
  printsToStdio():boolean;
  onStdOut(value:string|Buffer):void;
  onStdErr(value:string|Buffer):void;
  onError(error?:unknown):void;
  onTestEnd(test:TestCase,result:TestResult):void;
  onEnd(result:FullResult):Promise<{status:'passed'|'failed'}>;
}
const environment={csp_observation:'OBSERVED',csp_enforced_mask:0,csp_report_only_mask:0,resource_observation:'OBSERVED',resource_transport_failure_mask:0,resource_http_4xx_mask:0,resource_http_5xx_mask:0,environment_freshness:'SAMPLED'};
const ready=()=>({version:2,...environment,result:'FORM_READY_BEFORE_15S',binding:'BOUND',capability_started:true,capability_ready:true,
  loader_started:true,loader_ready:true,element_created:true,mount_called:true,mount_returned:true,
  ready_seen:true,loaderror_seen:false,view_completed:true,widget_completed:true,confirmation_blocked:false,
  catchup:'RESOLVED',operation:'COMPLETED',widget_state:'ready',element_state:'FRAME',pay_state:'ENABLED',pay_visible:true,bucket:'BEFORE_15S',
  no_confirmation:true,browser_closed:true,context_closed:true,runtime_closed:true});
const pending=()=>({...ready(),result:'WAITING_ELEMENT_READY',operation:'PENDING',ready_seen:false,view_completed:false,
  widget_completed:false,catchup:'NOT_STARTED',widget_state:'loading',pay_state:'ABSENT',pay_visible:false,bucket:'AFTER_15S',
  no_confirmation:false,browser_closed:false,context_closed:false,runtime_closed:false});
const wire=(value:unknown)=>JSON.stringify(value)+'\n';
const passed={status:'passed'} as FullResult;
function caseEnd(reporter:ReporterAPI,status='passed') {
  reporter.onTestEnd({title:'private-sentinel'} as TestCase,{status,attachments:[{body:Buffer.from('private-sentinel')}],errors:[{message:'private-sentinel'}]} as unknown as TestResult);
}
async function capture(run:(reporter:ReporterAPI,lines:string[])=>Promise<void>) {
  assert.equal(typeof Reporter,'function');
  const lines:string[]=[],original=process.stdout.write.bind(process.stdout);
  const output=mock.method(process.stdout,'write',((chunk:unknown,...args:unknown[])=>{
    if(typeof chunk!=='string')return original(chunk as Uint8Array,...args as []);
    lines.push(chunk);return true;
  }) as typeof process.stdout.write);
  try{await run(new Reporter!(),lines);}finally{output.mock.restore();}
}

// Break: absent safe reporter permits raw Playwright failure details to escape.
test('reporter exposes a safe output boundary',()=>assert.equal(typeof Reporter,'function'));

// Break: a nonready checkpoint permanently prevents otherwise proved final readiness.
test('one pending checkpoint followed by a proved ready final frame passes',async()=>capture(async(reporter,lines)=>{
  assert.equal(reporter.printsToStdio(),true);
  reporter.onStdOut(wire(pending()));
  const final=wire({...ready(),result:'FORM_READY_AFTER_15S',bucket:'AFTER_15S'});
  reporter.onStdOut(Buffer.from(final.slice(0,51)));reporter.onStdOut(final.slice(51));
  caseEnd(reporter);
  assert.deepEqual(await reporter.onEnd(passed),{status:'passed'});
  assert.equal(lines.length,2);assert.equal(JSON.parse(lines[0]!).result,'WAITING_ELEMENT_READY');
  assert.equal(JSON.parse(lines[1]!).result,'FORM_READY_AFTER_15S');
}));

// Break: raw errors, titles, attachments or stdout are printed through a detailed reporter.
test('raw stdout stderr error getters titles and attachments never escape',async()=>capture(async(reporter,lines)=>{
  reporter.onStdOut('raw-private-sentinel\n');reporter.onStdErr('private-sentinel');
  reporter.onStdOut(wire(ready()));caseEnd(reporter);
  let reads=0;const error=new Proxy({},{get(){reads++;throw null;},ownKeys(){reads++;throw null;}});
  reporter.onError(error);
  assert.deepEqual(await reporter.onEnd(passed),{status:'failed'});
  assert.equal(reads,0);assert.ok(!lines.join('').includes('private-sentinel'));
  assert.ok(lines.join('').includes('form_diagnostic_failed_manual_check'));
}));

// Break: malformed or duplicate frames, truncated transport or output overflow becomes success.
test('duplicate escaped keys duplicate frames extra fields overflow and truncation fail closed',async()=>{
  const valid=wire(ready());
  const cases=[valid.replace('"version":2','"version":2,"\\u0076ersion":1'),
    wire({...ready(),version:1}),wire({...ready(),csp_enforced_mask:16}),wire({...ready(),environment_freshness:'CACHED'}),
    wire({...ready(),secret:'private-sentinel'}),valid+valid,valid+wire(pending())+valid,
    'private-sentinel'.repeat(400),valid.trimEnd(),'{}\n'];
  for(const value of cases)await capture(async(reporter,lines)=>{
    reporter.onStdOut(value);caseEnd(reporter);
    assert.deepEqual(await reporter.onEnd(passed),{status:'failed'});
    assert.ok(!lines.join('').includes('private-sentinel'));
    assert.ok(lines.filter(line=>line.startsWith('{')).length<=2);
  });
});

// Break: a valid opaque failure drops the only available bootstrap evidence or leaks raw payloads.
test('opaque wire2 CSP baseline is printed as validated finite failure and never passes',async()=>capture(async(reporter,lines)=>{
  const frame={version:2,...environment,environment_freshness:'CACHED',csp_enforced_mask:1,
    result:'INSTRUMENTATION_UNAVAILABLE',no_confirmation:true,browser_closed:true,context_closed:true,runtime_closed:true};
  reporter.onStdOut(wire(frame));caseEnd(reporter);
  assert.deepEqual(await reporter.onEnd(passed),{status:'failed'});
  assert.deepEqual(JSON.parse(lines[0]!),frame);assert.equal(lines.length,2);
  assert.equal(lines[1],'form_diagnostic_failed_manual_check\n');
}));

// Break: UI readiness is treated as financial, cleanup, or Playwright pass proof.
test('final readiness needs all four proofs and exactly one passed test and passed run',async()=>{
  for(const key of ['no_confirmation','browser_closed','context_closed','runtime_closed'])await capture(async(reporter)=>{
    reporter.onStdOut(wire({...ready(),[key]:false}));caseEnd(reporter);
    assert.deepEqual(await reporter.onEnd(passed),{status:'failed'});
  });
  for(const mode of ['no-frame','no-case','two-cases','failed-case','skipped-case','failed-run'])await capture(async(reporter)=>{
    if(mode!=='no-frame')reporter.onStdOut(wire(ready()));
    if(mode!=='no-case')caseEnd(reporter,mode==='failed-case'?'failed':mode==='skipped-case'?'skipped':'passed');
    if(mode==='two-cases')caseEnd(reporter);
    assert.deepEqual(await reporter.onEnd({status:mode==='failed-run'?'failed':'passed'} as FullResult),{status:'failed'});
  });
});

// Break: later readiness erases a primary failure or post-final output corruption.
test('opaque failure remains failed even after a subsequent ready frame',async()=>capture(async(reporter)=>{
  reporter.onStdOut(wire({version:2,...environment,result:'OPERATION_FAILED',no_confirmation:true,browser_closed:true,context_closed:true,runtime_closed:true}));
  reporter.onStdOut(wire(ready()));caseEnd(reporter);
  assert.deepEqual(await reporter.onEnd(passed),{status:'failed'});
}));
