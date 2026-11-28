import assert from 'node:assert/strict';
import test from 'node:test';
// @ts-ignore Offline tests use the same source as the acceptance worker.
import {proveIssuerDomForm} from './stripe-billing-initial-3ds-dom-form.ts';

const valid=`
const envelope = JSON.parse(atob(document.getElementById('fixture-data').textContent));
const form = document.createElement('form');
form.method = 'POST';
form.action = envelope.action;
for (const [name, value] of Object.entries(envelope.fields)) {
 const input = document.createElement('input');
 input.type = 'hidden';
 input.name = name;
 input.value = value;
 form.append(input);
}
document.body.append(form);
form.submit();
`;
const data={
 id:'fixture-data',
 actionKey:'action',
 fieldsKey:'fields',
 action:'https://hooks.stripe.com/fixture-result',
 fields:[['threeDSSessionData','with + and &'],['cres','fixture-cres']] as const,
};

test('prove exactly the bound DOM form with its original field order',()=>{
 const proof={action:data.action,fields:data.fields};
 assert.deepEqual(proveIssuerDomForm(valid,data),proof);
 const renamed=valid.replaceAll(/\b(envelope|form|input|name|value)\b(?!')/g,match=>'local_'+match)
  .replaceAll('.local_name','.name').replaceAll('.local_value','.value');
 assert.deepEqual(proveIssuerDomForm(renamed,data),proof);
});

test('bind the companion lookup to the original data block',()=>{
 assert.equal(proveIssuerDomForm(valid,{...data,id:'different-fixture'}),undefined);
 assert.equal(proveIssuerDomForm(valid.replace('fixture-data','elsewhere'),data),undefined);
});

test('bind both static accesses to the original data properties',()=>{
 assert.equal(proveIssuerDomForm(valid,{...data,actionKey:'returnTo'}),undefined);
 assert.equal(proveIssuerDomForm(valid,{...data,fieldsKey:'controls'}),undefined);
 const script=valid.replace('envelope.action','envelope.returnTo').replace('envelope.fields','envelope.controls');
 assert.deepEqual(proveIssuerDomForm(script,{...data,actionKey:'returnTo',fieldsKey:'controls'}),
  {action:data.action,fields:data.fields});
});

test('accept only plain spellings of arguments used in DOM calls',()=>{
 const script=valid.replace("'fixture-data'",'"fixture-data"').replace("'POST'",'"post"');
 assert.deepEqual(proveIssuerDomForm(script,data),{action:data.action,fields:data.fields});
 assert.equal(proveIssuerDomForm(valid.replace("'fixture-data'","'fixture-\\u0064ata'"),data),undefined);
});

test('reject fields that could change the semantics of the DOM form',()=>{
 assert.equal(proveIssuerDomForm(valid,{...data,fields:[['submit','anything'],['cres','value']]}),undefined);
 assert.equal(proveIssuerDomForm(valid,{...data,fields:[['cres','first'],['cres','second']]}),undefined);
 assert.equal(proveIssuerDomForm(valid,{...data,fields:[['threeDSSessionData','only-session']]}),undefined);
 assert.equal(proveIssuerDomForm(valid,{...data,fields:[['cres','']]}),undefined);
});

const unsupported=[
 ['computed lookup','document.getElementById(',"document['getElementById']("],
 ['optional lookup','document.getElementById(','document?.getElementById('],
 ['second submit','form.submit();','form.submit();form.submit();'],
 ['conditional submit','form.submit();','if (false) form.submit();'],
 ['literal action','envelope.action','"https://hooks.stripe.com/substitute"'],
 ['different field source','envelope.fields','envelope.alternateFields'],
 ['reversed control bindings','[name, value]','[value, name]'],
 ['defaulted control binding','[name, value]','[name = "default", value]'],
 ['other attribute assignment','input.value = value','input.value ||= value'],
 ['additional field mutation','form.append(input);','form.append(input); input.value = "other";'],
 ['typed binding','const envelope =','const envelope: any ='],
 ['built-in shadow','const envelope =','const JSON ='],
 ['colliding shadow','for (const [name, value]','for (const [form, value]'],
 ['eval','form.submit();','eval("form.submit()");'],
 ['parser-recovered missing brace','}\ndocument.body.append','\ndocument.body.append'],
 ['unicode escape in identifier','envelope.action','envelope.\\u0061ction'],
 ['non-ASCII source','form.submit();','form.ѕubmit();'],
 ['source annotation','form.submit();','form.submit();\n//# sourceMappingURL=fixture.js.map'],
] as const;

test('reject a top-level lexical binding that collides with restricted browser globals',()=>{
 assert.equal(proveIssuerDomForm(valid.replaceAll('envelope','location'),data),undefined);
 assert.equal(proveIssuerDomForm(valid.replaceAll('envelope','undefined'),data),undefined);
});
for(const [name,before,after] of unsupported)test('reject '+name,()=>{
 const source=valid.replace(before,after);
 assert.notEqual(source,valid);
 assert.equal(proveIssuerDomForm(source,data),undefined);
});

test('bound the source before parsing',()=>{
 assert.equal(proveIssuerDomForm(' '.repeat(65536)+valid,data),undefined);
 assert.equal(proveIssuerDomForm('('.repeat(4000)+valid+')'.repeat(4000),data),undefined);
});
