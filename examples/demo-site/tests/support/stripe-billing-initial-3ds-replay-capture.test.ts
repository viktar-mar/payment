import assert from 'node:assert/strict';
import test,{type TestContext} from 'node:test';
import {lstatSync,mkdirSync,mkdtempSync,readFileSync,realpathSync,rmSync,symlinkSync,writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
// @ts-ignore The Node suite imports the same capture as the dedicated worker.
import {prepareIssuerReplayCapture} from './stripe-billing-initial-3ds-replay-capture.ts';

const runId='11111111-2222-4333-8444-555555555555';
const otherId='11111111-2222-4333-8444-555555555556';
const name='issuer-replay-fixture.private.json';
const raw={
 html:Buffer.from('<p>private-canary-html</p>'),script:Buffer.from('privateCanaryScript();'),
 documentUrl:'https://private.example/decision',scriptUrl:'https://private.example/decision.js',frameName:'private-canary-frame',
};
const foldedJson=Buffer.from('{"private-canary-data":"fixture0"}').toString('base64');
const foldedCompanion=foldedJson.slice(0,12)+'\n'+foldedJson.slice(12);

function pairedSource(companion:string,code?:string){
 const html=Buffer.from('<!doctype html><body><script type="application/json" id="private-canary-id">'+
  companion+'</script><script src="/private-canary.js"></script></body>');
 const form='<form method="post" action="https://hooks.stripe.com/private-canary-response">'+
  '<input type="hidden" name="cres" value="private-canary-cres"></form>';
 const script=Buffer.from(code??'document.write('+JSON.stringify(form)+');document.forms[0].submit();');
 return {...raw,html,script,scriptUrl:'https://private.example/private-canary.js'};
}

function directory(t:TestContext){
 const root=realpathSync(mkdtempSync(join(tmpdir(),'issuer-replay-capture-')));
 t.after(()=>rmSync(root,{force:true,recursive:true}));
 const run=join(root,runId);
 mkdirSync(run,{mode:0o700});
 writeFileSync(join(run,'owner.lock'),'synthetic\n',{mode:0o600});
 return {root,run,file:join(run,name)};
}

test('the replay capture is off by default and must name the exact fresh run',()=>{
 assert.equal(prepareIssuerReplayCapture(undefined,runId),undefined);
 for(const option of ['',otherId,'true','../'+runId,runId+'\n']){
  assert.throws(()=>prepareIssuerReplayCapture(option,runId),/billing_OPT_IN_REQUIRED/);
 }
 assert.throws(()=>prepareIssuerReplayCapture(runId,undefined),/billing_OPT_IN_REQUIRED/);
});

test('record only a fixed unsupported disposition once with private mode',t=>{
 const {root,file}=directory(t);
 const capture=prepareIssuerReplayCapture(runId,runId,root)!;
 assert.equal(capture(raw),'UNSUPPORTED');
 const written=readFileSync(file,'utf8');
 assert.deepEqual(JSON.parse(written),{status:'UNSUPPORTED'});
 assert.equal(lstatSync(file).mode&0o777,0o600);
 assert.ok(!written.includes('private'));
 writeFileSync(file,'subsequent local contents\n');
 capture(raw);
 assert.equal(readFileSync(file,'utf8'),'subsequent local contents\n');
});

test('write the paired synthetic replay and strict control without original values',t=>{
 const {root,file}=directory(t);
 assert.equal(prepareIssuerReplayCapture(runId,runId,root)!(pairedSource(foldedCompanion)),'CAPTURED');
 const record=readFileSync(file,'utf8');
 const {status,fixture}=JSON.parse(record);
 assert.equal(status,'CAPTURED');
 assert.equal(fixture.documentUrl,'https://issuer-replay.test/fixture/decision');
 assert.match(fixture.decisionHtml,/application\/json/);
 assert.match(fixture.strictDecisionHtml,/application\/json/);
 assert.match(fixture.scriptSource,/hooks\.stripe\.com\/fixture-followup/);
 assert.ok(!record.includes('private'));
 assert.ok(!record.includes(foldedJson));
 assert.equal(lstatSync(file).mode&0o777,0o600);
});

test('write only safe categories for an unsupported multiline companion and form producer',t=>{
 const {root,file}=directory(t);
 const script='const privateForm=document.createElement("form");privateForm.submit();';
 const source=pairedSource('first-key = private-canary\nsecond-key = private-canary',script);
 assert.equal(prepareIssuerReplayCapture(runId,runId,root)!(source),'STRUCTURAL_ONLY');
 const record=readFileSync(file,'utf8');
 const {status,shape}=JSON.parse(record);
 assert.equal(status,'STRUCTURAL_ONLY');
 assert.equal(shape.checkpoint,'BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_TEXT_LINE_PAIRS');
 assert.equal(shape.scriptProof,'BROWSER_CHALLENGE_CRES_SCRIPT_UNSUPPORTED_DOM');
 assert.deepEqual(shape.scriptMarkers,['CREATE_FORM','SUBMIT']);
 assert.doesNotMatch(record,/private|<script|document\.createElement/);
});

test('retain the structural record when the regenerated HTML exceeds the serialized limit',t=>{
 const {root,file}=directory(t);
 const source=pairedSource(foldedCompanion);
 const html=Buffer.from(source.html.toString().replace('<body>','<body>'+' '.repeat(36000)));
 prepareIssuerReplayCapture(runId,runId,root)!({...source,html});
 const record=readFileSync(file,'utf8');
 const {status,shape}=JSON.parse(record);
 assert.equal(status,'STRUCTURAL_ONLY');
 assert.equal(shape.checkpoint,'BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_TEXT_FOLD_JSON');
 assert.equal(shape.scriptProof,'PROVEN');
 assert.ok(Buffer.byteLength(record)<65536);
 assert.doesNotMatch(record,/private|<script/);
});

test('never follow an output symlink, overwrite an existing artifact or use a different run directory',t=>{
 const {root,run,file}=directory(t);
 const previous=join(root,'previous');
 writeFileSync(previous,'preserved',{mode:0o600});
 symlinkSync(previous,file);
 assert.equal(prepareIssuerReplayCapture(runId,runId,root)!(raw),'STORAGE_FAILED');
 assert.equal(readFileSync(previous,'utf8'),'preserved');
 assert.ok(lstatSync(file).isSymbolicLink());
 rmSync(file);
 writeFileSync(file,'existing fixture',{mode:0o600});
 prepareIssuerReplayCapture(runId,runId,root)!(raw);
 assert.equal(readFileSync(file,'utf8'),'existing fixture');
 rmSync(run,{recursive:true});
 const other=join(root,otherId);
 mkdirSync(other,{mode:0o700});
 writeFileSync(join(other,'owner.lock'),'synthetic\n',{mode:0o600});
 symlinkSync(other,run);
 prepareIssuerReplayCapture(runId,runId,root)!(raw);
 assert.throws(()=>lstatSync(join(other,name)),{code:'ENOENT'});
});

test('require the private run directory and a named private owner lock',t=>{
 const {root,run,file}=directory(t);
 const lock=join(run,'owner.lock');
 rmSync(lock);
 assert.equal(prepareIssuerReplayCapture(runId,runId,root)!(raw),'STORAGE_UNVERIFIED');
 assert.throws(()=>lstatSync(file),{code:'ENOENT'});
 const foreign=join(root,'elsewhere');
 writeFileSync(foreign,'foreign',{mode:0o600});
 symlinkSync(foreign,lock);
 prepareIssuerReplayCapture(runId,runId,root)!(raw);
 assert.throws(()=>lstatSync(file),{code:'ENOENT'});
});
