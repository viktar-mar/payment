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
const externalTag='<script src="/fixture/result.js"></script>';
const jsonBlock=(contents:string,attributes='')=>`<script type="application/json"${attributes}>${contents}</script>`;

test('derive the sole canonical external script URL from the paired HTML',()=>{
 for(const html of [
  external('./result.js'),external('result.js'),external('/fixture/result.js',' type="application/javascript"','\n'),
  external('https://reviewed-test-issuer.stripe.com/fixture/result.js'),
 ]){
  assert.deepEqual(issuerResultForm(html,undefined,undefined,decisionUrl),{
   scriptUrl:'https://reviewed-test-issuer.stripe.com/fixture/result.js',
   placement:'body',
  });
 }
});

test('derive the parser location of a sole classic external script in the head',()=>{
 assert.deepEqual(issuerResultForm(
  '<!doctype html><html><head><meta charset="utf-8"><title>Result</title><script src="/result.js"></script></head><body></body></html>',
  undefined,undefined,decisionUrl,
 ),{scriptUrl:'https://reviewed-test-issuer.stripe.com/result.js',placement:'head'});
});

for(const placement of ['head','body'] as const)test('derive the '+placement+' script alongside inert JSON data blocks with optional ids',()=>{
 const before=jsonBlock('{"label":"fixture"}',' id="fixture-data"');
 const after=jsonBlock('["document.forms[0].submit()"]',' id="fixture-metadata"');
 const html=`<!doctype html><html><${placement}>${before}${externalTag}${after}${jsonBlock('null')}</${placement}></html>`;
 const reported:string[]=[];
 assert.deepEqual(issuerResultForm(html,undefined,code=>reported.push(code),decisionUrl),{
  scriptUrl:'https://reviewed-test-issuer.stripe.com/fixture/result.js',
  placement,
 });
 assert.deepEqual(reported,[]);
});

for(const [reason,documents] of Object.entries({
 EXTERNAL_URL:[
  external(''),external('/result.js?variant=1'),external('/result.js#fragment'),external('/%72esult.js'),
  external('//reviewed-test-issuer.stripe.com/result.js'),external('https://foreign.fixture.test/result.js'),
  external('../result.js'),external('/fixture/../result.js'),
  external('https://operator@reviewed-test-issuer.stripe.com/result.js'),
 ],
 EXTERNAL_ATTRIBUTES:[
  external('/result.js',' async'),external('/result.js',' defer'),external('/result.js',' nomodule'),
  external('/result.js',' onload="submitForm()"'),external('/result.js',' nonce="private-fixture"'),
  external('/result.js',' integrity="private-fixture"'),external('/result.js',' charset="utf-16"'),
  external('/result.js',' id="fixture-producer"'),
 ],
 EXTERNAL_TYPE:[external('/result.js',' type="module"'),external('/result.js',' type="application/json"')],
 EXTERNAL_TEXT:[external('/result.js','','ignored code')],
 EXTERNAL_COUNT_MULTIPLE_SOURCES:[
  external('/result.js')+external('/different.js'),
  external('/result.js')+external('/result.js',' type="application/json"'),
  external('/result.js')+external('/result.js'),
  externalTag+'<script>otherCode()</script>'+external('/different.js')+jsonBlock(''),
  externalTag+jsonBlock('{}',' id="fixture-data" src="/other.js"'),
 ],
 EXTERNAL_COUNT_RECOGNIZED_JS_COMPANION:[
  externalTag+'<script>otherCode()</script>',
  externalTag+'<script type="module">otherCode()</script>',
  externalTag+'<script type="text/javascript"></script>',
  externalTag+jsonBlock('{}')+'<script>otherCode()</script>',
  externalTag+'<script>otherCode()</script>'+jsonBlock(''),
 ],
 EXTERNAL_COMPANION_PARENT:[externalTag+'<div>'+jsonBlock('{}')+'</div>'],
 EXTERNAL_COMPANION_TAG:[externalTag+'<script type="application/json">{}'],
 EXTERNAL_COMPANION_TYPE_MISSING:[externalTag+'<script language="private-fixture">otherCode()</script>'],
 EXTERNAL_COMPANION_TYPE_JSON_VARIANT:[
  externalTag+'<script type="application/json ">{}</script>',
  externalTag+'<script type="APPLICATION/JSON">{}</script>',
 ],
 EXTERNAL_COMPANION_TYPE_JSON_LD:[externalTag+'<script type="application/ld+json">{}</script>'],
 EXTERNAL_COMPANION_TYPE_IMPORTMAP:[externalTag+'<script type="importmap">{}</script>'],
 EXTERNAL_COMPANION_TYPE_SPECULATION_RULES:[externalTag+'<script type="speculationrules">{}</script>'],
 EXTERNAL_COMPANION_TYPE_OTHER:[
  externalTag+'<script type="text/ecmascript">otherCode()</script>',
  externalTag+'<script type="application/private-fixture">otherCode()</script>',
 ],
 EXTERNAL_COMPANION_HANDLER:[
  externalTag+jsonBlock('{}',' onload="otherCode()"'),
  externalTag+jsonBlock('{}',' id="fixture-data" onload="otherCode()"'),
 ],
 EXTERNAL_COMPANION_ATTRIBUTE_NONCE:[externalTag+'<script type="application/json" nonce="private-fixture">{}</script>'],
 EXTERNAL_COMPANION_ATTRIBUTES:[
  externalTag+'<script type="application/json" data-private-fixture="private-fixture">{}</script>',
  externalTag+'<script type="application/json" id="private-fixture" nonce="private-fixture">{}</script>',
  externalTag+jsonBlock('{}',' id="fixture-data" data-private-fixture="private-fixture"'),
 ],
 EXTERNAL_COMPANION_JSON_EMPTY:[
  externalTag+jsonBlock(''),externalTag+jsonBlock(' \t\n\f\r '),externalTag+jsonBlock('',' id="fixture-data"'),
 ],
 EXTERNAL_COMPANION_JSON_INVALID:[
  externalTag+jsonBlock('{"private-fixture": invalid}'),externalTag+jsonBlock('{"fixture": invalid}',' id="fixture-data"'),
 ],
 EXTERNAL_PARENT:['<!doctype html><body><div><script src="/result.js"></script></div></body>'],
 EXTERNAL_TAG:['<!doctype html><body><script src="/result.js">'],
 EXTERNAL_LAYOUT_TAG:[external('/result.js').replace('</body>','<template></template></body>')],
 EXTERNAL_LAYOUT_ATTRIBUTES:[
  external('/result.js').replace('<body>','<body id="private-fixture">'),
  '<!doctype html><head><meta http-equiv="Content-Security-Policy" content="script-src \'self\'"><script src="/result.js"></script></head>',
 ],
 EXTERNAL_LAYOUT_TEXT:[external('/result.js').replace('</body>','private fixture</body>')],
 EXTERNAL_LAYOUT_HANDLER:[external('/result.js').replace('<body>','<body onload="privateFixture()">')],
 DUPLICATE_ATTRIBUTE:[
  external('/result.js',' src="/different.js"'),externalTag+jsonBlock('{}',' id="first" id="second"'),
 ],
 MISSING_TEMPLATE:['<template>'+external('/result.js')+'</template>'],
 BASE:['<base href="https://foreign.fixture.test">'+external('/result.js')],
 OWNER:[external('/result.js').replace('</body>','<input form="private-fixture"></body>')],
}))test('refuse an external script reference with the fixed checkpoint '+reason,()=>{
 for(const html of documents){
  const reported:string[]=[];
  assert.equal(issuerResultForm(html,undefined,code=>reported.push(code),decisionUrl),undefined);
  assert.deepEqual(reported,['BROWSER_CHALLENGE_CRES_FORM_'+reason]);
 }
});

test('report one fixed reason from the first rejected companion in reverse parsed traversal',()=>{
 const invalid=jsonBlock('{"private-fixture": invalid}');
 const withNonce=jsonBlock('{}',' nonce="private-fixture"');
 for(const [companions,reason] of [
  [invalid+withNonce,'ATTRIBUTE_NONCE'],[withNonce+invalid,'JSON_INVALID'],
 ]){
  const reported:string[]=[];
  assert.equal(issuerResultForm(externalTag+companions+jsonBlock('null'),undefined,code=>reported.push(code),decisionUrl),undefined);
  assert.deepEqual(reported,['BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_'+reason]);
 }
});

test('report the first failing check for a companion with several rejected conditions',()=>{
 const malformed='<script type="importmap" onload="privateFixture()">not valid JSON';
 for(const [companion,reason] of [
  ['<div>'+malformed,'PARENT'],
  [malformed,'TAG'],
  [malformed+'</script>','TYPE_IMPORTMAP'],
  [malformed.replace('importmap','application/json')+'</script>','HANDLER'],
  [jsonBlock('not valid JSON',' nonce="private-fixture"'),'ATTRIBUTE_NONCE'],
 ]){
  const reported:string[]=[];
  assert.equal(issuerResultForm(externalTag+companion,undefined,code=>reported.push(code),decisionUrl),undefined);
  assert.deepEqual(reported,['BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_'+reason]);
 }
});

test('require an exact HTTPS decision URL to derive an external script reference',()=>{
 for(const url of [undefined,'file:///fixture/decision',decisionUrl+'#',' '+decisionUrl]){
  const reported:string[]=[];
  assert.equal(issuerResultForm(external('/result.js'),undefined,code=>reported.push(code),url),undefined);
  assert.deepEqual(reported,['BROWSER_CHALLENGE_CRES_FORM_'+(url===undefined?'MISSING_EXTERNAL':'EXTERNAL_URL')]);
 }
});

test('derive the exact CRes form from a 64 KiB literal external script',()=>{
 const code=Buffer.from(write(form(session+result,'target="issuer-frame"')).padEnd(65536));
 assert.deepEqual(externalIssuerResultForm(code,'issuer-frame'),{
  action:'https://hooks.stripe.com/fixture/result?one=1&two=2',
  body:'threeDSSessionData=a%2Bb%0D%0Ac&cres=YQ%3D%3D',
 });
});

test('derive the exact CRes form written by a classic external head script',()=>{
 assert.deepEqual(externalIssuerResultForm(Buffer.from(write(form(session+result))),undefined,undefined,'head'),{
  action:'https://hooks.stripe.com/fixture/result?one=1&two=2',
  body:'threeDSSessionData=a%2Bb%0D%0Ac&cres=YQ%3D%3D',
 });
});

test('refuse an external head script that requires an existing body',()=>{
 const code=Buffer.from(`document.body.insertAdjacentHTML('beforeend', '${form()}');${submit}`);
 const reported:string[]=[];
 assert.equal(externalIssuerResultForm(code,undefined,value=>reported.push(value),'head'),undefined);
 assert.deepEqual(reported,['BROWSER_CHALLENGE_CRES_SCRIPT_HEAD_INSERTION']);
 assert.deepEqual(externalIssuerResultForm(code,undefined,undefined,'body'),{
  action:'https://hooks.stripe.com/fixture/result?one=1&two=2',
  body:'cres=YQ%3D%3D',
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
