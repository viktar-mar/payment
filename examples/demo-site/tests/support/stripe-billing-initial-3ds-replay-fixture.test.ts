import assert from 'node:assert/strict';
import test from 'node:test';
// @ts-ignore Native Node executes the same TypeScript as Playwright.
import {anonymizeIssuerDecisionReplay,describeIssuerDecisionReplay} from './stripe-billing-initial-3ds-replay-fixture.ts';
// @ts-ignore Native Node executes the same form proof as Playwright.
import {externalIssuerResultForm,issuerResultForm} from './stripe-billing-initial-3ds-result-form.ts';

const foldCheckpoint='BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_TEXT_FOLD_JSON';
const dataCheckpoint='BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_TEXT_FOLD_DATA_JSON';
const fold=(value:string)=>'\r\n\t'+value.slice(0,7)+'\r\n '+value.slice(7,15)+'\n\t'+value.slice(15)+'\r\n';
const dataTag=(body:string,id:string)=>`<script type="application/json" id="${id}">${body}</script>`;
const sourceTag=(src:string)=>`<script type="text/javascript" src="${src}">\r\n</script>`;
const form=(frame:string,value:string,session:string)=>
 `<form target="${frame}" action="https://hooks.stripe.com/${value}?receipt=${session}" method="POST">`+
 `<input name="threeDSSessionData" type="hidden" value="${session}"> `+
 `<input value="${value}" name="cres" type="hidden"></form>`;

function capture(marker:string){
 const documentUrl=`https://${marker}.example.test/${marker}/decision`,asset=`./${marker}.js`;
 const frameName=`frame-${marker}`,body=Buffer.from(JSON.stringify({[marker]:`${marker}<>&`}));
 const compact=body.toString('base64');
 assert.match(compact,/[+/=]/);
 const payload=fold(compact);
 const html=Buffer.from(`<!doctype html>\r\n<html><head><meta charset="UTF-8"><title>${marker}</title>`+
  sourceTag(asset)+dataTag(payload,`data-${marker}`)+'\r\n</head><body>\r\n</body></html>');
 const script=Buffer.from(`document.write('${form(frameName,marker,`${marker}-session`)}');document.forms[0].submit();`);
 return {html,script,frameName,documentUrl,scriptUrl:new URL(asset,documentUrl).href};
}

test('a folded companion replay preserves the checkpoint, source order and line widths with synthetic data',()=>{
 const source=capture('fixture-hidden-one');
 const fixture=anonymizeIssuerDecisionReplay(source);
 assert.ok(fixture);
 assert.equal(fixture.checkpoint,foldCheckpoint);
 const rejected:string[]=[];
 assert.equal(issuerResultForm(fixture.decisionHtml,fixture.frameName,code=>rejected.push(code),fixture.documentUrl),undefined);
 assert.deepEqual(rejected,[foldCheckpoint]);
 assert.deepEqual(issuerResultForm(fixture.strictDecisionHtml,fixture.frameName,undefined,fixture.documentUrl),{
  placement:'head',scriptUrl:fixture.scriptUrl,
 });
 const extract=(html:string)=>/<script type="application\/json" id="[^"]+">([^<]*)<\/script>/.exec(html)?.[1];
 const companion=extract(fixture.decisionHtml);
 assert.ok(companion);
 assert.equal(companion.replace(/[A-Za-z0-9+/_=-]/g,'X'),extract(source.html.toString())?.replace(/[A-Za-z0-9+/_=-]/g,'X'));
 const decoded=Buffer.from(companion.replace(/[ \t\r\n]/g,''),'base64').toString('utf8');
 assert.deepEqual(JSON.parse(decoded),JSON.parse(extract(fixture.strictDecisionHtml)!));
 assert.ok(fixture.decisionHtml.indexOf('src="./')<fixture.decisionHtml.indexOf('application/json'));
 assert.match(fixture.scriptSource,/^document\.write\('/);
 const result=externalIssuerResultForm(Buffer.from(fixture.scriptSource),fixture.frameName,undefined,'head');
 assert.ok(result);
 assert.equal(result.action,'https://hooks.stripe.com/fixture-followup');
 const fields=new URLSearchParams(result.body);
 assert.deepEqual([...fields.keys()],['threeDSSessionData','cres']);
 assert.equal(fields.get('threeDSSessionData'),'fixture&session');
 assert.equal(JSON.parse(Buffer.from(fields.get('cres')!,'base64url').toString()).messageType,'CRes');
 assert.equal(JSON.stringify(fixture).includes('fixture-hidden-one'),false);
 assert.notEqual(companion.replace(/[ \t\r\n]/g,''),extract(source.html.toString())?.replace(/[ \t\r\n]/g,''));
 assert.equal(decoded.includes('fixture-hidden-one'),false);
 assert.deepEqual(fixture,anonymizeIssuerDecisionReplay(capture('fixture-hidden-two')));
});

test('a supported folded data URI retains its envelope and Base64URL alphabet',()=>{
 const documentUrl='https://private-envelope.example.test/decision',scriptUrl='https://private-envelope.example.test/run.js';
 const privateJson='"x?private-envelope"';
 const compact=Buffer.from(privateJson).toString('base64url');
 assert.match(compact,/_/);
 const payload='DATA:application/json;BASE64,'+compact.slice(0,6)+'\r\n\t'+compact.slice(6);
 const html=Buffer.from('<!doctype html><body>'+dataTag(payload,'private-envelope')+sourceTag(scriptUrl)+'</body>');
 const markup=form('_self','private-envelope','unused').replace(' value="unused"','');
 const script=Buffer.from(`document.body.insertAdjacentHTML("beforeend", ${JSON.stringify(markup)});document.forms[0].submit()`);
 const fixture=anonymizeIssuerDecisionReplay({html,script,documentUrl,scriptUrl});
 assert.ok(fixture);
 assert.equal(fixture.checkpoint,dataCheckpoint);
 assert.match(fixture.decisionHtml,/data:application\/json;base64,[^<]*_[^<]*\r\n\t/i);
 assert.match(fixture.scriptSource,/^document\.body\.insertAdjacentHTML\("beforeend",/);
 const proof=externalIssuerResultForm(Buffer.from(fixture.scriptSource),fixture.frameName,undefined,'body');
 assert.equal(new URLSearchParams(proof?.body).get('threeDSSessionData'),'');
 assert.equal(JSON.stringify(fixture).includes('private-envelope'),false);
});

test('padded Base64URL and adjacent strict companions stay inert and preserve script order',()=>{
 const source=capture('fixture-hidden-one');
 const privateJson=' "?private-envelope" \r\n';
 const compact=Buffer.from(privateJson).toString('base64').replace(/\//g,'_');
 assert.match(compact,/_.*={1,2}$/);
 const payload=fold(compact);
 const html=source.html.toString().replace(/(<script type="application\/json"[^>]*>)[^<]*/, '$1'+payload)
  .replace('</head>',dataTag('["privé-日本語"]','another-private-id')+'</head>');
 const fixture=anonymizeIssuerDecisionReplay({...source,html:Buffer.from(html)});
 assert.ok(fixture);
 assert.equal(fixture.checkpoint,foldCheckpoint);
 const data=[...fixture.decisionHtml.matchAll(/<script type="application\/json" id="[^"]+">([^<]*)<\/script>/g)];
 assert.equal(data.length,2);
 assert.match(data[0]![1]!.replace(/[ \t\r\n]/g,''),/_.*={1,2}$/);
 assert.deepEqual(JSON.parse(data[1]![1]!),[]);
 assert.doesNotMatch(JSON.stringify(fixture),/private-envelope|another-private-id|privé|日本語/);
});

test('the capture refuses unpaired, ambiguous, unsupported or oversized sources without returning content',()=>{
 const source=capture('fixture-hidden-one');
 const html=source.html.toString();
 const script=source.script.toString();
 const withHtml=(value:string)=>({...source,html:Buffer.from(value)});
 const withScript=(value:string)=>({...source,script:Buffer.from(value)});
 const rejected=[
  {...source,scriptUrl:'https://unpaired.example.test/code.js'},
  {...source,frameName:'another-frame'},
  {...source,html:Buffer.alloc(65537,0x20)},
  {...source,script:Buffer.alloc(65537,0x20)},
  {...source,html:Buffer.from([0xff])},
  {...source,script:Buffer.from([0xff])},
  withHtml(html.replace('<title>','<title title="fixture-raw-title">')),
  withHtml(html.replace('<!doctype html>','<!doctype html PUBLIC "">')),
  withHtml(html.replace('</head>','<head data-hidden="opaque"></head>')),
  withHtml(html.replace('</head>',sourceTag('/second-private.js')+'</head>')),
  withHtml(html.replace('</head>',dataTag(fold('eyJ2YWx1ZSI6dHJ1ZX0='),'extra-fold')+'</head>')),
  withHtml(html.replace(/(<script type="application\/json"[^>]*>)[^<]*/, '$1data:text/html;base64,eyJ2YW\n x1ZSI6dHJ1ZX0=')),
  withHtml(html.replace(/(<script type="application\/json"[^>]*>)[^<]*/, '$1{"strict":true}')),
  withScript(script+'\nwindow["fixture-raw-extra-expression"]()'),
  withScript(script.replace("document.write('","document.write(hiddenIdentifier + '")),
  withScript(script.replace('<form ', '<form data-hidden="fixture-raw-extra-attribute" ')),
  withScript(script.replace('method="POST">','method="POST"/>')),
  withScript(script.replace('<input ', '<input id="fixture-raw-extra-id" ')),
  withScript(script.replace('> <input','>\t<input')),
  withScript(script.replace("document.write('","document.body.insertAdjacentHTML('beforeend','")),
 ];
 for(const variant of rejected)assert.equal(anonymizeIssuerDecisionReplay(variant),undefined);
});

test('an unsupported paired producer has a layout and lexical witness without private values',()=>{
 const source=capture('fixture-hidden-one');
 const html=Buffer.from(source.html.toString().replace('</head>','<!-- private-layout-marker --></head>'));
 const script=Buffer.from(
  'const privateEnvelope=JSON.parse(atob(document.getElementById("private-lookup").textContent));'+
  'const privateProducer=document.createElement("form");privateProducer.submit();',
 );
 const variant={...source,html,script};
 assert.equal(anonymizeIssuerDecisionReplay(variant),undefined);
 const witness=describeIssuerDecisionReplay(variant);
 assert.ok(witness);
 assert.equal(witness.checkpoint,foldCheckpoint);
 assert.equal(witness.producerPlacement,'head');
 assert.equal(witness.scriptProof,'BROWSER_CHALLENGE_CRES_SCRIPT_UNSUPPORTED_DOM');
 assert.deepEqual(witness.scriptMarkers,['CREATE_FORM','ATOB','JSON_PARSE','LOOKUP_ID','SUBMIT']);
 assert.deepEqual(witness.layout.map(node=>node.element),['DOCTYPE','HTML','HEAD','META','TITLE','PRODUCER','COMPANION','COMMENT','BODY']);
 assert.deepEqual(witness.layout.find(node=>node.element==='COMPANION'),{
  element:'COMPANION',parent:'HEAD',tags:'CLOSED',attributes:['TYPE','ID'],companion:'REJECTED_JSON',
 });
 assert.doesNotMatch(JSON.stringify(witness),/fixture-hidden-one|private-layout-marker|private-lookup|privateEnvelope|privateProducer/);
});

test('the shape witness is bounded and requires the paired script URL',()=>{
 const source=capture('fixture-hidden-one');
 const html=Buffer.from(source.html.toString().replace('</body>','<!-- private-bound -->'.repeat(100)+'</body>'));
 const variant={...source,html};
 const witness=describeIssuerDecisionReplay(variant);
 assert.ok(witness);
 assert.equal(witness.layoutTruncated,true);
 assert.equal(witness.layout.length,32);
 assert.equal(witness.scriptProof,'PROVEN');
 assert.doesNotMatch(JSON.stringify(witness),/private-bound/);
 assert.equal(describeIssuerDecisionReplay({...variant,scriptUrl:'https://private-unpaired.example.test/script.js'}),undefined);
});

test('the shape witness distinguishes head elements that share the same coarse layout',()=>{
 const source=capture('fixture-hidden-one');
 const candidates=[
  ['<link rel="stylesheet" type="text/css" href="/private-head.css">','LINK_STYLESHEET'],
  ['<link rel="shortcut icon" type="image/png" href="/private-head.png">','LINK_ICON'],
  ['<link rel="preload" type="text/javascript" href="/private-head.js" as="script">','LINK_PRELOAD'],
  ['<link rel="private-rel" type="private-type" href="/private-head">','LINK_OTHER'],
  ['<link rel="stylesheet preload" type="text/css" href="/private-head.css">','LINK_OTHER'],
  ['<basefont color="private-color" type="private-type">','BASEFONT'],
  ['<bgsound loop="1" type="private-type">','BGSOUND'],
 ] as const;
 for(const [element,headKind] of candidates){
  const html=Buffer.from(source.html.toString().replace('</head>',element+'</head>'));
  const witness=describeIssuerDecisionReplay({...source,html});
  assert.ok(witness);
  assert.equal(witness.checkpoint,foldCheckpoint);
  assert.deepEqual(witness.layout.find(node=>node.element==='OTHER'),{
   element:'OTHER',parent:'HEAD',tags:'OPEN',attributes:['OTHER','TYPE'],headKind,
   ...(headKind==='LINK_ICON'?{iconHrefKind:'NETWORK_RASTER'}:{}),
  });
  assert.doesNotMatch(JSON.stringify(witness),/private-|fixture-hidden-one/);
 }
});

test('the shape witness reports the icon source category without returning its values',()=>{
 const source=capture('fixture-hidden-one');
 for(const [markup,iconHrefKind] of [
  ['<link rel="shortcut icon" type="image/x-icon" href="data:image/x-icon;,">','EMPTY_DATA'],
  ['<link rel="icon" type="image/png">','ABSENT'],
  ['<link rel="icon" type="image/png" href="https://private-icon.example/fixture.png">','NETWORK_RASTER'],
  ['<link rel="icon" type="image/png" href="data:text/plain,private-icon">','UNSUPPORTED_DATA'],
 ] as const){
  const html=Buffer.from(source.html.toString().replace('</head>',markup+'</head>'));
  const witness=describeIssuerDecisionReplay({...source,html});
  assert.ok(witness);
  assert.equal(witness.layout.find(node=>node.headKind==='LINK_ICON')?.iconHrefKind,iconHrefKind);
  assert.doesNotMatch(JSON.stringify(witness),/private-|fixture-hidden-one|data:/);
 }
});

test('only a direct head element receives a fixed kind and unsupported tags are generalized',()=>{
 const source=capture('fixture-hidden-one');
 const html=Buffer.from(source.html.toString()
  .replace('</head>','<template type="private-kind"></template></head>')
  .replace('</body>','<link rel="stylesheet" type="text/css" href="/private-body.css"></body>'));
 const witness=describeIssuerDecisionReplay({...source,html});
 assert.ok(witness);
 assert.deepEqual(witness.layout.filter(node=>node.element==='OTHER'),[
  {element:'OTHER',parent:'HEAD',tags:'CLOSED',attributes:['TYPE'],headKind:'OTHER'},
  {element:'OTHER',parent:'BODY',tags:'OPEN',attributes:['OTHER','TYPE']},
 ]);
 assert.doesNotMatch(JSON.stringify(witness),/template|private-|fixture-hidden-one/);
});

test('report structural evidence for a paired refusal on a page with additional inert markup',()=>{
 const source=capture('fixture-hidden-one');
 const html=Buffer.from(source.html.toString().replace('<html>','<html lang="private-language">')
  .replace('<head>','<head><script type="application/ld+json">{"fixture":"private-metadata"}</script>')
  .replace('</body>','<div data-fixture="private-value"></div></body>'));
 const variant={...source,html};
 assert.equal(anonymizeIssuerDecisionReplay(variant),undefined);
 const witness=describeIssuerDecisionReplay(variant);
 assert.ok(witness);
 assert.equal(witness.checkpoint,foldCheckpoint);
 assert.equal(witness.producerPlacement,'head');
 assert.equal(witness.scriptProof,'PROVEN');
 assert.equal(witness.layout.filter(node=>node.element==='COMPANION').length,2);
 assert.doesNotMatch(JSON.stringify(witness),/private-|fixture-hidden-one/);
});

test('the shape witness retains the grammar checkpoint for a paired non-JSON payload',()=>{
 const source=capture('fixture-hidden-one');
 const opaque=Buffer.from('private-not-json?').toString('base64');
 const html=Buffer.from(source.html.toString().replace(/(<script type="application\/json"[^>]*>)[^<]*/, '$1'+fold(opaque)));
 const variant={...source,html};
 assert.equal(anonymizeIssuerDecisionReplay(variant),undefined);
 const witness=describeIssuerDecisionReplay(variant);
 assert.ok(witness);
 assert.equal(witness.checkpoint,'BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_TEXT_FOLD_TEXT');
 assert.equal(witness.scriptProof,'PROVEN');
 assert.doesNotMatch(JSON.stringify(witness),/fixture-hidden-one|private-not-json/);
});
