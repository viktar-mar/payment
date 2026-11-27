import assert from 'node:assert/strict';
import test from 'node:test';
// @ts-ignore Native Node executes the same form parser as Playwright.
import {externalIssuerResultForm,issuerResultForm} from './stripe-billing-initial-3ds-result-form.ts';

const action='https://hooks.stripe.com/fixture/result?one=1&amp;two=2';
const result='<input type="hidden" name="cres" value="YQ==">';
const session='<input type="hidden" name="threeDSSessionData" value="a+b&#13;c">';
const form=(fields=result,attributes='')=>'<form method="POST" action="'+action+'" '+attributes+'>'+fields+'</form>';
const submit='document.forms[0].submit();';
const inline=(code:string,attributes='')=>`<!doctype html><body><script${attributes}>${code}</script></body>`;
const write=(markup:string)=>`document.write(${JSON.stringify(markup)});${submit}`;
const decisionUrl='https://reviewed-test-issuer.stripe.com/fixture/decision';
const external=(src:string,attributes='',contents='')=>inline(contents,` src="${src}"${attributes}`);

test('derive the sole canonical external script URL from the paired HTML',()=>{
 for(const html of [
  external('./result.js'),external('result.js'),external('/fixture/result.js',' type="application/javascript"','\n'),
  external('https://reviewed-test-issuer.stripe.com/fixture/result.js'),
 ]){
  assert.deepEqual(issuerResultForm(html,undefined,undefined,decisionUrl),{
   scriptUrl:'https://reviewed-test-issuer.stripe.com/fixture/result.js',
  });
 }
});

for(const html of [
 external('/result.js?variant=1'),external('/result.js#fragment'),external('/%72esult.js'),
 external('//reviewed-test-issuer.stripe.com/result.js'),external('https://foreign.fixture.test/result.js'),
 external('../result.js'),external('/fixture/../result.js'),
 external('https://operator@reviewed-test-issuer.stripe.com/result.js'),
 external('/result.js',' async'),external('/result.js',' defer'),external('/result.js',' type="module"'),
 external('/result.js',' nomodule'),external('/result.js',' onload="submitForm()"'),
 external('/result.js',' src="/different.js"'),external('/result.js','','ignored code'),
 external('/result.js')+external('/different.js'),'<template>'+external('/result.js')+'</template>',
 '<base href="https://foreign.fixture.test">'+external('/result.js'),
 '<!doctype html><head><script src="/result.js"></script></head>',
 '<!doctype html><body><script src="/result.js">',
])test('refuse a nonexclusive or unbound external script reference',()=>{
 assert.equal(issuerResultForm(html,undefined,undefined,decisionUrl),undefined);
});

test('require an exact HTTPS decision URL to derive an external script reference',()=>{
 for(const url of [undefined,'file:///fixture/decision',decisionUrl+'#',' '+decisionUrl]){
  assert.equal(issuerResultForm(external('/result.js'),undefined,undefined,url),undefined);
 }
});

test('derive the exact CRes form from a 64 KiB literal external script',()=>{
 const code=Buffer.from(write(form(session+result,'target="issuer-frame"')).padEnd(65536));
 assert.deepEqual(externalIssuerResultForm(code,'issuer-frame'),{
  action:'https://hooks.stripe.com/fixture/result?one=1&two=2',
  body:'threeDSSessionData=a%2Bb%0D%0Ac&cres=YQ%3D%3D',
 });
});

for(const [reason,source] of [
 ['SCRIPT_UTF8',Buffer.from([0xff])],
 ['SCRIPT_UNSUPPORTED',Buffer.from(write(form()).padEnd(65537))],
 ['SCRIPT_UNSUPPORTED',Buffer.from(write(form())+'otherCode()')],
 ['SCRIPT_UNSUPPORTED',Buffer.from(`document.write(${JSON.stringify(form())}+suffix);${submit}`)],
 ['SCRIPT_UNSUPPORTED_DOM',Buffer.from(`const form=document.createElement('form');${submit}`)],
 ['FORM_LITERAL_MARKUP',Buffer.from(write(form()+form()))],
 ['FORM_ACTION',Buffer.from(write(form().replace('hooks.stripe.com','private.fixture.test')))],
] as const)test('refuse external script with the fixed checkpoint '+reason,()=>{
 const reported:string[]=[];
 assert.equal(externalIssuerResultForm(source,undefined,code=>reported.push(code)),undefined);
 assert.deepEqual(reported,['BROWSER_CHALLENGE_CRES_'+reason]);
});

test('derive the exact CRes action and ordered wire bytes from the issuer form',()=>{
 assert.deepEqual(issuerResultForm('<!doctype html>'+form(session+result,'target="_self"')),{
  action:'https://hooks.stripe.com/fixture/result?one=1&two=2',
  body:'threeDSSessionData=a%2Bb%0D%0Ac&cres=YQ%3D%3D',
 });
});

for(const code of [write(form(session+result)),`document.body.insertAdjacentHTML('beforeend', '${form(session+result)}'); ${submit}`]){
 test('derive the ordered CRes form from one literal inline producer',()=>{
  assert.deepEqual(issuerResultForm(inline(code)),{
   action:'https://hooks.stripe.com/fixture/result?one=1&two=2',
   body:'threeDSSessionData=a%2Bb%0D%0Ac&cres=YQ%3D%3D',
  });
 });
}

for(const [reason,html] of [
 ['MISSING_INLINE_MARKUP',inline(write(form())+'document.body.remove();')],
 ['MISSING_INLINE_MARKUP',inline(`document.write(${JSON.stringify(form())} + suffix); ${submit}`)],
 ['MISSING_INLINE_DOM',inline(`const form=document.createElement('form'); ${submit}`)],
 ['MISSING_EXTERNAL','<!doctype html><script src="/private-fixture.js"></script>'],
 ['MISSING_TEMPLATE','<!doctype html><template>'+form()+'</template>'],
 ['MISSING_HANDLER','<!doctype html><body onload="privateFixture()"></body>'],
 ['MISSING_MIXED',inline(write(form())).replace('</body>','<script src="/private-fixture.js"></script></body>')],
 ['MISSING',inline(write(form()),' type="application/json"')],
 ['MISSING',inline(write(form()),' type="\u00a0text/javascript\u00a0"')],
 ['MISSING_INLINE_MARKUP',inline(write(form()),' type="module"')],
 ['MISSING_INLINE_MARKUP',inline(write(form()),' nomodule')],
 ['MISSING_TEMPLATE','<!doctype html><template>'+inline(write(form()))+'</template>'],
 ['MISSING_INLINE_MARKUP','<!doctype html><head><script>'+write(form())+'</script></head>'],
 ['MISSING_INLINE_MARKUP','<!doctype html><body><script>'+write(form())],
 ['MISSING_INLINE_MARKUP',inline(write(form())).replace('</body>','<div>other body content</div></body>')],
 ['LITERAL_MARKUP',inline(write(form()+form()))],
 ['LITERAL_MARKUP',inline(write(form('<form>'+result+'</form>')))],
 ['LITERAL_MARKUP',inline(write(form().slice(0,-7)))],
 ['LITERAL_MARKUP',inline(write('<body onload="privateFixture()">'+form()))],
 ['LITERAL_MARKUP',inline(write(form(result,'name="forms"')))],
 ['LITERAL_MARKUP',inline(write(form(result.replace('type=','id="submit" type='))))],
 ['LITERAL_MARKUP',inline(write('<div>'+form()+'</div>'))],
 ['LITERAL_MARKUP',inline(write(form(result+'<script>privateFixture()</script>')).replace('</script>','<\\/script>'))],
 ['ACTION',inline(write(form().replace('hooks.stripe.com','private.fixture.test')))],
 ['REQUIRED_CRES',inline(write(form(session)))],
 ['TARGET',inline(write(form(result,'target="_parent"')))],
 ['CONTROL',inline(write(form(result.replace('hidden','text'))))],
 ['DUPLICATE_ATTRIBUTE',inline(write(form(result,'action="https://private.fixture.test"')))],
 ['METHOD',form().replace('method="POST"','method="get"')+inline(write(form()))],
] as const)test('dynamic CRes fails closed with '+reason,()=>{
 const reported:string[]=[];
 assert.equal(issuerResultForm(html,undefined,code=>reported.push(code)),undefined);
 assert.deepEqual(reported,['BROWSER_CHALLENGE_CRES_FORM_'+reason]);
});

test('derive the exact wire body for a named current frame and an implicit empty session value',()=>{
 const emptySession='<input type="hidden" name="threeDSSessionData">';
 const html=form(emptySession+result,'target="stripe-challenge-frame"');
 assert.deepEqual(issuerResultForm(html,'stripe-challenge-frame'),{
  action:'https://hooks.stripe.com/fixture/result?one=1&two=2',
  body:'threeDSSessionData=&cres=YQ%3D%3D',
 });
 assert.equal(issuerResultForm(html),undefined);
 assert.equal(issuerResultForm(html,'Stripe-challenge-frame'),undefined);
});

for(const target of ['_blank','_parent','_top'])test('refuse '+target+' even if supplied as the current frame name',()=>{
 assert.equal(issuerResultForm(form(result,`target="${target}"`),target),undefined);
});

for(const [name,html] of Object.entries({
 'a form in a script': '<script>const html = '+JSON.stringify(form())+'</script>',
 'a form in a comment': '<!-- '+form()+' -->',
 'a form in a template': '<template>'+form()+'</template>',
 'multiple forms': form()+form(),
 'a foreign destination': form().replace('hooks.stripe.com','hooks.stripe.com.fixture.test'),
 'a relative destination': form().replace('https://hooks.stripe.com',''),
 'a form with duplicate action attributes': form(result,'action="https://fixture.test"'),
 'a different frame target': form(result,'target="_blank"'),
 'a document-wide frame target': '<base target="_blank">'+form(),
 'a disabled input': form(result.replace('type=','disabled type=')),
 'a form under a disabled fieldset': '<fieldset disabled>'+form()+'</fieldset>',
 'a form under a datalist': '<datalist>'+form()+'</datalist>',
 'a control under a datalist in the form': form('<datalist>'+result+'</datalist>'),
 'a missing CRes value': form(result.replace(' value="YQ=="','')),
 'a destination with an empty fragment': form().replace('two=2','two=2#'),
 'a duplicate CRes': form(result+result),
 'an extra successful control': form(result+'<input type="hidden" name="other" value="value">'),
 'an externally associated control': form(result,'id="notify"')+'<input form="notify" type="hidden" name="cres" value="YQ">',
 'a non-hidden input': form(result.replace('hidden','text')),
}))test('refuse '+name+' as CRes transport evidence',()=>{
 assert.equal(issuerResultForm(html),undefined);
});

for(const [reason,html] of Object.entries({
 DUPLICATE_ATTRIBUTE:form(result,'action="https://private.fixture.test"'),
 BASE:'<base target="_blank">'+form(),
 OWNER:form()+'<input form="private-fixture">',
 MISSING:'<!-- no active form -->',
 MULTIPLE:form()+form(),
 METHOD:form().replace('method="POST"','method="get"'),
 TARGET:form(result,'target="private-fixture"'),
 ENCODING:form(result,'enctype="multipart/form-data"'),
 ACTION:form().replace('hooks.stripe.com','private.fixture.test'),
 CONTROL:form(result.replace('type="hidden"','type="text"')),
 FIELD:form(result+'<input type="hidden" name="private-fixture" value="private fixture">'),
 DUPLICATE_FIELD:form(result+result),
 REQUIRED_CRES:form(session),
 SIZE:form(result.replace('YQ==','X'.repeat(32769))),
}))test('report the fixed CRes form rejection '+reason,()=>{
 const reported:string[]=[];
 assert.equal(issuerResultForm(html,undefined,code=>reported.push(code)),undefined);
 assert.deepEqual(reported,['BROWSER_CHALLENGE_CRES_FORM_'+reason]);
});
