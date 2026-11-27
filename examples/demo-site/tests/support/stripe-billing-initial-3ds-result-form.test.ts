import assert from 'node:assert/strict';
import test from 'node:test';
// @ts-ignore Native Node executes the same form parser as Playwright.
import {externalIssuerResultForm,issuerResultForm} from './stripe-billing-initial-3ds-result-form.ts';
// @ts-ignore Native Node executes the same synthetic companion as Chrome.
import {syntheticIssuerDomForm} from './stripe-billing-initial-3ds-dom-fixture.ts';

test('prove the folded companion only with its exact dynamic form recipe',()=>{
 const destination='https://hooks.stripe.com/fixture/result?one=1&two=2';
 const fields={threeDSSessionData:'é + &\r\nx\n',cres:'YQ=='};
 const {id,folded,script}=syntheticIssuerDomForm(destination,fields);
 const wire='threeDSSessionData=%C3%A9+%2B+%26%0D%0Ax%0D%0A&cres=YQ%3D%3D';
 const html=`<!doctype html><body><script type="application/json" id="${id}">${folded}</script><script src="/result.js"></script></body>`;
 const source=issuerResultForm(html,undefined,undefined,'https://reviewed-test-issuer.stripe.com/fixture/decision');
 assert.ok(source&&'scriptUrl' in source&&source.foldedCompanion);
 assert.equal(source.scriptUrl,'https://reviewed-test-issuer.stripe.com/result.js');
 assert.deepEqual(externalIssuerResultForm(Buffer.from(script),undefined,undefined,source.placement,source.foldedCompanion),
  {action:destination,body:wire});
 assert.equal(externalIssuerResultForm(Buffer.from(script.replace(id,'different-id')),undefined,undefined,source.placement,source.foldedCompanion),undefined);
 assert.equal(externalIssuerResultForm(Buffer.from("document.write('<form></form>');document.forms[0].submit();"),
  undefined,undefined,source.placement,source.foldedCompanion),undefined);
});

test('prove canonical folded Base64 with a byte count that needs no padding',()=>{
 const destination='https://hooks.stripe.com/fixture/result';
 const fixtures=['x','xy','xyz'].map(cres=>({...syntheticIssuerDomForm(destination,{cres}),cres}));
 const fixture=fixtures.find(value=>/^[A-Za-z0-9\n]+$/.test(value.folded));
 assert.ok(fixture);
 const html=`<!doctype html><body><script type="application/json" id="${fixture.id}">${fixture.folded}</script><script src="/result.js"></script></body>`;
 const source=issuerResultForm(html,undefined,undefined,'https://reviewed-test-issuer.stripe.com/fixture/decision');
 assert.ok(source&&'scriptUrl' in source&&source.foldedCompanion);
 assert.deepEqual(externalIssuerResultForm(Buffer.from(fixture.script),undefined,undefined,source.placement,source.foldedCompanion),
  {action:destination,body:'cres='+fixture.cres});
});

test('require a blocked same-origin stylesheet receipt for one plain folded-companion head link',()=>{
 const {id,folded,json}=syntheticIssuerDomForm('https://hooks.stripe.com/fixture/result',{cres:'YQ=='});
 const documentUrl='https://reviewed-test-issuer.stripe.com/fixture/decision';
 const href='/fixture-decision.css';
 const headLink=`<link rel="stylesheet" href="${href}" type="text/css">`;
 const document=(head:string,data=folded,body='')=>`<!doctype html><html><head>${head}</head><body>${body}<script type="application/json" id="${id}">${data}</script><script src="/result.js"></script></body></html>`;
 const read=(html:string)=>issuerResultForm(html,undefined,undefined,documentUrl);
 const proof=read(document(headLink));
 assert.ok(proof&&'scriptUrl' in proof&&proof.foldedCompanion);
 assert.equal(proof.blockedStylesheetUrl,new URL(href,documentUrl).href);
 assert.equal(read(document(headLink,json)),undefined);
 for(const head of [
  headLink+headLink,
  headLink+headLink.replace(href,'/other.css'),
  headLink+'<style type="text/css">body {color:black}</style>',
  headLink.replace('stylesheet','icon'),
  headLink.replace('stylesheet','preload'),
  headLink.replace('stylesheet','alternate stylesheet'),
  headLink.replace('type="text/css"','type="text/plain"'),
  headLink.replace('type="text/css"',''),
  headLink.replace(href,'https://foreign.example/other.css'),
  headLink.replace(href,'//reviewed-test-issuer.stripe.com/other.css'),
  headLink.replace(href,href+'?other=1'),
  headLink.replace(href,href+'#other'),
  headLink.replace(href,'/fixture/%6Fther.css'),
  headLink.replace(href,'/fixture/../other.css'),
  headLink.replace(' rel=',' onload="void 0" rel='),
  headLink.replace(' rel=',' media="print" rel='),
  headLink.replace(' rel=',' type="text/css" rel='),
 ])assert.equal(read(document(head)),undefined,head);
 assert.equal(read(document('',folded,headLink)),undefined);
});

test('keep its strict layout, base64 and controls even when a folded JSON candidate is present',()=>{
 const destination='https://hooks.stripe.com/fixture/result';
 const {id,folded}=syntheticIssuerDomForm(destination,{cres:'YQ=='});
 const data=(encoded=folded,identifier=id)=>`<script type="application/json" id="${identifier}">${encoded}</script>`;
 const producer='<script src="/result.js"></script>';
 const body=(markup:string)=>'<!doctype html><body>'+markup+'</body>';
 const fold=(json:string)=>Buffer.from(json).toString('base64').replace(/^.{8}/,'$&\n');
 for(const html of [
  body(producer+data()), body(data()+producer+data()), body(data()+producer.replace('/result.js','https://foreign.example/result.js')),
  body(data(folded,'bad id')+producer), body(data(folded.replace(/\s/g,''))+producer),
  body(data(fold(JSON.stringify({destination,controls:{cres:'YQ==',unrelated:'sentinel'}})))+producer),
  body(data(fold(JSON.stringify({destination,controls:{cres:'é'}})))+producer),
  body(data(fold(JSON.stringify({destination:'http://hooks.stripe.com/fixture/result',controls:{cres:'YQ=='}})))+producer),
  '<!doctype html><head><link rel="icon" type="image/png" href="/fixture.png"></head><body>'+data()+producer+'</body>',
  '<!doctype html><head>'+data()+producer+'</head>',
 ])assert.equal(issuerResultForm(html,undefined,undefined,'https://reviewed-test-issuer.stripe.com/fixture/decision'),undefined);
});

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

test('locate the exact external script for evidence while refusing the invalid JSON companion',()=>{
 const reported:string[]=[],located:unknown[]=[];
 const html=externalTag+jsonBlock('eyJma\nXh0dXJlIjp0cnVlfQ==',' id="fixture-data"');
 assert.equal(issuerResultForm(html,undefined,code=>reported.push(code),decisionUrl,value=>located.push(value)),undefined);
 assert.deepEqual(located,[{scriptUrl:'https://reviewed-test-issuer.stripe.com/fixture/result.js',placement:'head'}]);
 assert.deepEqual(reported,['BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_TEXT_FOLD_JSON']);
});

test('locate a refused folded companion beside ordinary markup or a second inert data block',()=>{
 const folded=jsonBlock('eyJma\nXh0dXJlIjp0cnVlfQ==',' id="fixture-data"');
 for(const html of [
  '<!doctype html><html lang="en"><head>'+externalTag+folded+'</head></html>',
  '<!doctype html><html><head><script type="application/ld+json">{}</script>'+externalTag+folded+'</head></html>',
  '<!doctype html><html><head>'+externalTag+folded+'</head><body><div hidden></div></body></html>',
 ]){
  const reports:string[]=[],locations:unknown[]=[];
  assert.equal(issuerResultForm(html,undefined,code=>reports.push(code),decisionUrl,ref=>locations.push(ref)),undefined);
  assert.deepEqual(reports,['BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_TEXT_FOLD_JSON']);
  assert.deepEqual(locations,[{placement:'head',scriptUrl:'https://reviewed-test-issuer.stripe.com/fixture/result.js'}]);
  assert.equal(issuerResultForm(html.replace(folded,jsonBlock('{"fixture":true}')),undefined,undefined,decisionUrl),undefined);
 }
});

test('offer no evidence locator when the external layout, ownership or source is ambiguous',()=>{
 const companion=jsonBlock('not valid JSON');
 for(const html of [
  externalTag+companion+external('/second.js'),
  externalTag+companion+'<script>anotherProducer()</script>',
  '<script language="javascript">anotherProducer()</script>'+externalTag+companion,
  '<script type="importmap">{"imports":{}}</script>'+externalTag+companion,
  external('/fixture/result.js?alternative=1')+companion,
  external('/result.js',' defer')+companion,
  '<!doctype html><body><div>'+externalTag+'</div>'+companion+'</body>',
  externalTag+jsonBlock(''),
 ]){
  const locations:unknown[]=[];
  assert.equal(issuerResultForm(html,undefined,undefined,decisionUrl,ref=>locations.push(ref)),undefined);
  assert.deepEqual(locations,[]);
 }
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
 EXTERNAL_COMPANION_JSON_FORMAT_OBJECT:[
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
  [invalid+withNonce,'ATTRIBUTE_NONCE'],[withNonce+invalid,'JSON_FORMAT_OBJECT'],
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

for(const [reason,contents] of Object.entries({
 BOM:[' \t\uFEFF{"private-fixture":true}\n'],
 HTML_COMMENT:['\t<!-- {"private-fixture":true} -->\n'],
 HTML_QUOTES:['{&quot;private-fixture&QUOT;:&#034;value&#X022;}', '{&#34;private-fixture&#x22;:true}'],
 PERCENT:[' %7B%22private-fixture%22%3Atrue%7D\t','{%22private-fixture%22:true}','42%0A'],
 BASE_SIXTY_FOUR:[' Ing/Ig==\n','\tIng_Ig ',' Ing_Ig== ','Ij4-Pj4-Ig=='],
 CONTROL:['["private-fixture\u0001"]'],
 ESCAPE:['["private-fixture\\x12"]','["private-fixture\\u0z00"]'],
 INCOMPLETE:['{"private-fixture":','"private-fixture','"private-fixture\\'],
 PROPERTY:['{privateFixture:true}','{"private-fixture":true,}','{&quot;private-fixture&quot;:invalid}'],
 DELIMITER:['{"private-fixture" true}','{"private-fixture":true "extra":false}','["private-fixture" true]'],
 NUMBER:['[-]','[1.]','[1e]','[01]','01'],
 TRAILING:['{"private-fixture":true} extra','77u/e30=','1 fixture'], // The base64 candidate decodes to a BOM followed by JSON.
 FORMAT_BASE_TEXT:['ZTMwPQ==','IO+7v3t9'], // Decodes to Base64 text or whitespace + BOM + JSON, respectively.
 FORMAT_BASE_BYTES:['Iv8i'],
 FORMAT_BOM:['\uFEFF\uFEFF{}','\uFEFF<!-- {} -->'],
 FORMAT_COMMENT:['<!-- {}','<!-- "earlier --> end" -->','/* {} */','// {}'],
 FORMAT_MARKUP:['<fixture>private-fixture</fixture>'],
 FORMAT_ENTITY:['&quot;private-fixture&quot; extra','&amp;quot;private-fixture&amp;quot;'],
 FORMAT_PERCENT:['%7B%FF%7D','%257B%257D'],
 FORMAT_ESCAPE:[String.raw`\"private-fixture\"`],
 FORMAT_QUOTED:["'private-fixture'",'`private-fixture`'],
 FORMAT_OBJECT:['{"private-fixture":invalid}'],
 FORMAT_ARRAY:['[\f]','[\v]','[privateFixture]'],
 FORMAT_SPACE:['\u00a0%7B%7D','\t\v{}','\u0001{}'],
 TEXT_DATA_URI:['data:application/json,%7B%7D','\tDaTa:text/plain,private-fixture\n','data:,'],
 TEXT_URI:['https://example.invalid/private-fixture','scheme+name://example.invalid/fixture'],
 TEXT_FORM:['fixture=private-value&extra=','a=YQ==&b=private+fixture','\tfixture=%7B%7D&other=1\n'],
 TEXT_ASSIGN_JSON:[
  ' state = {"fixture":";"} ;\t','const privateFixture = [true,false,null]',
  'window . privateFixture = "value"','state="value&other=private"',
 ],
 TEXT_ASSIGNMENT:[
  'fixture=','fixture=private-value','state={fixture:1}','state={};extra()','state={};;',
  'state=/* fixture */{}','state=\u00a0{}','state= {} \u00a0;','a=1&b=2 trailing',
 ],
 TEXT_CALL_JSON:[' fixture({"s":")"}) ;\n','window . privateFixture(42)','fixture(true)'],
 TEXT_CALL:[
  'fixture(','fixture()','fixture({}) trailing','fixture({});extra()','fixture({}, {})','fixture({k:1})',
  'fixture({});;','fixture({})\u00a0','fixture({})\u2028;',
 ],
 TEXT_JS_LITERAL:['undefined','NaN','False'],
 TEXT_NUMERIC:['+1','+.5','+12.3e-4'],
 TEXT_BASE_SHAPE:['Ing/Ih==','Ing_Ih==','Is+_Ig==','Ing_Ig===','Ing/Ig'],
 TEXT_IDENTIFIER:['hello','privateFixture','window.privateFixture'],
 TEXT_SHAPE_ATNSA:['private fixture','_private fixture','another secret'],
 TEXT_SHAPE_STNSA:['+private fixture'],
 TEXT_SHAPE_AONSA:[
  'prefix https://example.invalid/fixture','data:application/json,private fixture',
  'prefix a=1&b=2','state\t+=1','fixture < 1',
 ],
 TEXT_SHAPE_AOPSA:['prefix data:application/json,%7B%7D','private %7b'],
 TEXT_SHAPE_AOESA:['private &quot;value&quot;','private &#x21;','private &#33;'],
 TEXT_SHAPE_APBSA:[String.raw`private \u0021`],
 TEXT_SHAPE_AOMSA:[String.raw`private %7B &quot;value&quot; \u0021`],
 TEXT_SHAPE_ADNSA:['data:','data:private-fixture','private fixture; another'],
 TEXT_SHAPE_ABNSA:[
  'prefix fixture({})','fixture => ({})','fixture label: {about:1}',
  'fixture label: {};;','fixture label: {} trailing','fixture label: [broken] [true]',
 ],
 TEXT_SHAPE_AMNSA:['prefix <fixture attr="value"> {about:1}','prefix </fixture>','prefix <!-- fixture -->'],
 TEXT_SHAPE_AJNSA:[
  'fixture label: {"fixture":true};','a different label: {"secret":"sentinel"}',
  'fixture == {}','prefix <fixture> {"fixture":true}',
  'prefix [broken] {"fixture":true}','prefix {broken} [true]',
 ],
 TEXT_SHAPE_AJNLA:['\tfixture label:\n [true] ;\r\n'],
 TEXT_SHAPE_ABNSU:['fixture label: {}\u00a0;'],
 TEXT_SHAPE_ATNLA:['fixture\nlabel','fixture\r\nlabel'],
 TEXT_SHAPE_AONLU:['Ing/\n\u00a0Ig=='],
 TEXT_SHAPE_AOPLA:['fixture %7B\nlabel'],
 TEXT_SHAPE_ATNSU:['fixture café','fixture label\u2028'],
 TEXT_SHAPE_ATNLU:['fixture\u2028label','fixture\u2029label'],
 TEXT_SHAPE_ATNLC:['fixture\u0085label'],
 TEXT_SHAPE_APNSC:['fixture\u200dlabel','fixture\u0001label'],
 TEXT_SHAPE_APNSA:['fixture.'+' '.repeat(65527)+'?'],
 TEXT_FOLD_JSON:['eyJma\nXh0dXJlIjp0cnVlfQ==','Ing_\r\n\tIg==','Ing/\n Ig==','OD\nA='], // JSON number 80 also matches zlib CMF/FLG.
 TEXT_FOLD_TEXT:['ZTMw\nPQ==','IO+7\nv3t9IA=='],
 TEXT_FOLD_BYTES:['Iv8i/\nw==','H4sJ/\nw==','eJ\n0=','KLUv/\nA=='],
 TEXT_FOLD_GZIP:['H4sI/\nw=='],
 TEXT_FOLD_ZLIB:['eJ\nw=','eA\nE='],
 TEXT_FOLD_ZSTD:['KLUv/\nQ=='],
 TEXT_FOLD_BASE:['Ing/\nIh==','Is+_\nIg=='],
 TEXT_FOLD_DATA_JSON:['data:application/json;base64,eyJma\nXh0dXJlIjp0cnVlfQ==','DATA:;BASE64,Ing_\r\nIg=='],
 TEXT_FOLD_DATA:[
  'data:text/plain,\nprivate-fixture','data:application/json;base64,Ing/\nIh==',
  'data:application/json;base64,IO+7\nv3t9','data:text/plain,Ing/\nIg==',
 ],
 TEXT_FOLD_FORM:['first-key=private&\nother-key=more','first-key=x\r\n &\tother-key='],
 TEXT_LINE_PAIRS:['first-key = private\r\n\r\n second-key = more','first-key: x=y\nsecond-key: private'],
 TEXT_LINE_OP_E:[
  'fixture label =\nrest','first-key == private\nsecond-key = more','first-key: x\nsecond-key = more',
  'first-key = private\nextra line\nsecond-key = more','first-key = \nsecond-key = more',
 ],
 TEXT_LINE_OP_C:['fixture <= private\nlabel','fixture != private\nlabel'],
 TEXT_LINE_OP_L:['fixture & private\nlabel','fixture | private\nlabel'],
 TEXT_LINE_OP_S:['fixture / private\nlabel.'],
 TEXT_LINE_OP_R:['fixture * private\nlabel','fixture ^ private\nlabel'],
 TEXT_LINE_OP_M:[
  'fixture != private\nlabel = more','first-key => private\nsecond-key => more','first-key=x\nextra&other-key=more',
  'first-key=x &\n extra line & other-key=more','first-key=x &\nother-key=more\n&',
 ],
 FORMAT_LIMIT:['fixture.'+' '.repeat(65528)+'?'],
 FORMAT_OTHER:[')private-fixture','é'],
}))test('reject malformed companion JSON with the fixed '+reason+' diagnostic',()=>{
 for(const text of contents){
  const reported:string[]=[];
  assert.equal(issuerResultForm(externalTag+jsonBlock(text),undefined,code=>reported.push(code),decisionUrl),undefined);
  assert.deepEqual(reported,['BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_'+reason]);
 }
});

test('allow strict companion JSON with encoding and envelope markers inside strings',()=>{
 const content=['&quot;','&#34;','%7B%7D','Ing_Ig','<!-- data -->','fixture({})','a=1&b=2','data:text/plain,private'];
 const html=externalTag+jsonBlock(' \t'+JSON.stringify(content)+'\n');
 const reported:string[]=[];
 assert.deepEqual(issuerResultForm(html,undefined,code=>reported.push(code),decisionUrl),{
  scriptUrl:'https://reviewed-test-issuer.stripe.com/fixture/result.js',placement:'head',
 });
 assert.deepEqual(reported,[]);
});

test('classify invalid companion wrappers only within 64 KiB of parsed UTF-8',()=>{
 for(const [extra,reason] of [['a','BOM'],['ab','FORMAT_LIMIT']]){
  const text='\uFEFF"'+'é'.repeat(32765)+extra+'"';
  const reported:string[]=[];
  assert.equal(issuerResultForm(externalTag+jsonBlock(text),undefined,code=>reported.push(code),decisionUrl),undefined);
  assert.deepEqual(reported,['BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_'+reason]);
 }
});

test('enforce the companion format budget after parsing replaces HTML NUL characters',()=>{
 const html=externalTag+jsonBlock('\u0000'.repeat(21846)+'{}');
 assert.ok(Buffer.byteLength(html,'utf8')<=65536);
 const reported:string[]=[];
 assert.equal(issuerResultForm(html,undefined,code=>reported.push(code),decisionUrl),undefined);
 assert.deepEqual(reported,['BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_FORMAT_LIMIT']);
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
