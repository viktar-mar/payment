import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {lstatSync,mkdirSync,mkdtempSync,readFileSync,realpathSync,rmSync,writeFileSync} from 'node:fs';
import {createServer} from 'node:https';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import test from 'node:test';
import {brotliCompressSync,deflateSync,gzipSync,zstdCompressSync} from 'node:zlib';
import {chromium,type Frame,type Request,type Response,type Route} from '@playwright/test';
// @ts-ignore Native Node executes the same challenge source as Playwright.
import {initial3dsChallenge} from './stripe-billing-initial-3ds.ts';
// @ts-ignore Native Node executes the guarded response source.
import {guardChallengeRedirects} from './stripe-billing-initial-3ds-redirect.ts';
// @ts-ignore Native Node executes the same private writer as the dedicated worker.
import {prepareIssuerReplayCapture} from './stripe-billing-initial-3ds-replay-capture.ts';
// @ts-ignore Native Node executes the same fixture as the result-form tests.
import {syntheticIssuerDomForm} from './stripe-billing-initial-3ds-dom-fixture.ts';
import type {IssuerReplayCapture} from './stripe-billing-initial-3ds';
import type {IssuerDecisionReplayShape} from './stripe-billing-initial-3ds-replay-fixture';
import type {IssuerReplayStatus} from './stripe-billing-initial-3ds-terminal';

type CaptureSource=Parameters<IssuerReplayCapture['save']>[0];

async function serveIssuerResponse(url:URL,bytes:Buffer,encoding:string,chunked:boolean,script=false){
 const directory=mkdtempSync(join(tmpdir(),'billing-decision-tls-'));
 const key=join(directory,'key.pem'),cert=join(directory,'cert.pem');
 const server=(()=>{
  try{
   execFileSync('/usr/bin/openssl',['req','-x509','-newkey','rsa:2048','-nodes','-days','1','-subj','/CN='+url.hostname,
    '-keyout',key,'-out',cert],{stdio:'ignore',timeout:5000});
   return createServer({key:readFileSync(key),cert:readFileSync(cert)});
  }finally{rmSync(directory,{recursive:true,force:true});}
 })();
 let requests=0,unexpectedRequests=0;
 server.on('request',(request,response)=>{
  if(request.method!==(script?'GET':'POST')||request.url!==url.pathname||request.headers.host!==url.host||++requests!==1){
   unexpectedRequests++;response.writeHead(404).end();return;
  }
  request.resume();
  const length=chunked?{}:{'Content-Length':bytes.length};
  response.writeHead(200,{'Content-Type':script?'application/javascript; charset=UTF-8':'text/html','Content-Encoding':encoding,'Cache-Control':'no-store',...length});
  if(chunked){response.write(bytes);response.end();}
  else response.end(bytes);
 });
 await new Promise<void>((resolve,reject)=>{
  server.once('error',reject);
  server.listen(0,'127.0.0.1',resolve);
 });
 const address=server.address();
 assert.ok(address&&typeof address==='object');
 return {port:address.port,requests:()=>requests,unexpectedRequests:()=>unexpectedRequests,close:async()=>{
  await new Promise<void>((resolve,reject)=>{
   server.close(error=>error?reject(error):resolve());
   server.closeAllConnections();
  });
 }};
}

const rejectedAssets=new Map([
 ['direct reviewed issuer stylesheet rejected',{
  path:'/fixture-private-stylesheet.scss',kind:'stylesheet',checkpoint:'BROWSER_CHALLENGE_ISSUER_ASSET_STYLESHEET_SUFFIX',
  contentType:'text/css',body:':root { color: black; }',
 }],
 ['direct reviewed issuer script rejected',{
  path:'/fixture-private-script.js',kind:'script',checkpoint:'BROWSER_GUARD_ROUTE_ISSUER_SCRIPT_PARSER',
  contentType:'application/javascript',
  body:"document.querySelector('form').append(document.querySelector('template').content.cloneNode(true))",
 }],
]);
const stylesheetBlock='direct reviewed issuer passive stylesheet blocked';
const twoFonts='direct reviewed issuer two passive fonts blocked';
const sixFonts='direct reviewed issuer six passive fonts blocked';
const parserScript='one parser-initiated script from the exact issuer document';
const decisionScript='one parser-initiated script from the issuer decision document';
const secondDecisionPost='a second document POST from the issuer decision';
const decisionCres='a CRes document POST to the Stripe hooks origin';
const isolatedDecisionCres='a CRes document POST between isolated issuer and hooks targets';
const namedDecisionCres='a CRes form targeting the same issuer frame with an implicit empty session value';
const literalDecisionCres='a CRes form written by one literal inline script';
const insertionDecisionCres='a CRes form inserted by one literal inline script';
const dynamicDecisionCres='a CRes form assembled by an unsupported DOM script';
const literalBodyMismatch='a CRes literal form whose observed wire body differs';
const externalLiteral='a CRes form from one original external issuer script';
const externalGzip='a CRes form from one gzip external issuer script';
const externalHead='a CRes form written by a single external script in the decision head';
const externalHeadWithData='a CRes form written by an external head script beside inert JSON data';
const externalWithExecutable='a CRes form written by an external script beside unproved executable code';
const externalHeadWithCompanionId='a CRes form written by an external head script beside identified JSON data';
const externalWithCompanionId='a CRes form inserted by an external body script beside identified JSON data';
const externalWithInvalidCompanion='a CRes form written by an external script beside JSON with HTML-escaped quotes';
const externalWithCommentCompanion='a CRes form written by an external script beside an HTML comment containing escaped JSON';
const externalWithAssignmentCompanion='a CRes form written by an external script beside a JSON value in assignment syntax';
const externalWithLabelledCompanion='a CRes form written by an external script beside a labelled JSON suffix';
const externalWithStrictCompanion='a CRes form written by an external script beside the strict JSON control';
const externalWithFoldedCompanion='a CRes form written by an external script beside folded Base64 JSON';
const foldedDom='a folded Base64 companion and its dynamic CRes form';
const foldedDomHeadStylesheet='a folded dynamic CRes form with a blocked head stylesheet';
const foldedDomWrongStylesheetReceipt='a folded dynamic CRes form with a different head stylesheet receipt';
const foldedDomLocalHeadIcon='a folded dynamic CRes form with a local head icon';
const foldedDomNetworkHeadIcon='a folded dynamic CRes form with a network head icon';
const foldedDomInlinePngHeadIcon='a folded dynamic CRes form with a raster data head icon';
const foldedDomMixedCaseHeadIcon='a folded dynamic CRes form with mixed-case data head icon metadata';
const foldedDomIconAfterHead='a folded dynamic CRes form with a head icon written after the closing tag';
const captureUnsupportedHeadIcon='a folded dynamic CRes form with an unsupported data head icon and private capture';
const staticWithScriptStylesheet='a static CRes form with a blocked script-initiated stylesheet';
const foldedDomExtraScript='a folded dynamic CRes form with an extra script statement';
const foldedDomWireMismatch='a folded dynamic CRes form with different expected POST bytes';
const foldedDomLegacyEncoding='a folded dynamic CRes form with legacy document encoding';
const foldedDomWrongTransaction='a folded dynamic CRes form for a different 3DS transaction';
const foldedDomCases=new Set([foldedDom,foldedDomHeadStylesheet,foldedDomWrongStylesheetReceipt,
 foldedDomLocalHeadIcon,foldedDomNetworkHeadIcon,foldedDomInlinePngHeadIcon,foldedDomMixedCaseHeadIcon,foldedDomIconAfterHead,captureUnsupportedHeadIcon,
 foldedDomExtraScript,foldedDomWireMismatch,foldedDomLegacyEncoding,foldedDomWrongTransaction]);
const captureFoldedCompanion='a CRes refusal with opt-in to its anonymized paired fixture';
const captureMaskedLayout='a CRes refusal with opt-in and an outer language attribute';
const captureMissingDecision='a CRes refusal with opt-in and no CDP decision receipt';
const captureMissingReceipt='a CRes refusal with opt-in and no paired fixture receipt';
const captureAbortFailure='a CRes refusal with opt-in and no confirmed abort for the fixture';
const captureOutcomes=new Map<string,IssuerReplayStatus>([
 [captureFoldedCompanion,'CAPTURED'],[captureMaskedLayout,'STRUCTURAL_ONLY'],[captureMissingDecision,'DECISION_RECEIPT_MISSING'],
 [captureMissingReceipt,'SCRIPT_RECEIPT_MISSING'],[captureAbortFailure,'ABORT_UNCONFIRMED'],[captureUnsupportedHeadIcon,'STRUCTURAL_ONLY'],
]);
const externalNested='a CRes form written by an external script under a nonstructural parent';
const externalUnknown='a CRes form from an unsupported external DOM builder';
const externalMismatch='a CRes form beside identified JSON data with different expected POST bytes';
const externalNoReceipt='a CRes form from an external script without a matching receipt';
const externalInvalidUtf8='a CRes form from an external script with invalid UTF-8';
const oversizedDecision='a gzip CRes decision that expands past 64 KiB';
const invalidUtf8Decision='a gzip CRes decision with invalid UTF-8';
const compressedDecisions=new Map([
 ['a gzip CRes decision between isolated issuer and hooks targets',{encoding:'gzip',compress:gzipSync}],
 ['a chunked Brotli CRes decision with exactly 64 KiB decoded',{encoding:'br',compress:brotliCompressSync,chunked:true,decodedSize:65536}],
 ['a deflate CRes decision between isolated issuer and hooks targets',{encoding:'deflate',compress:deflateSync}],
 ['a Zstandard CRes decision between isolated issuer and hooks targets',{encoding:'zstd',compress:zstdCompressSync}],
 [oversizedDecision,{encoding:'gzip',compress:gzipSync,decodedSize:65537}],
 [invalidUtf8Decision,{encoding:'gzip',compress:gzipSync}],
]);
const lateDecisionCres='a CRes document POST with late issuer response observation';
const lateDecisionHeaders='a captured CRes decision with unavailable Playwright headers';
const uncapturedDecision='a CRes document POST using the paired Playwright response';
const changedCresAction='a CRes form whose action changed after the issuer response';
const changedCresBody='a CRes form whose session data changed after the issuer response';
const unsupportedCresForm='a CRes sent from a form with an unproved control';
const issuerCresDestination='a CRes sent to the issuer origin';
const refusedFollowups=new Map([
 [secondDecisionPost,'BROWSER_CHALLENGE_ISSUER_SUBMISSION_FOLLOWUP'],
 [changedCresAction,'BROWSER_CHALLENGE_CRES_ACTION'],
 [changedCresBody,'BROWSER_CHALLENGE_CRES_BODY'],
 [unsupportedCresForm,'BROWSER_CHALLENGE_CRES_FORM_CONTROL'],
 [dynamicDecisionCres,'BROWSER_CHALLENGE_CRES_FORM_MISSING_INLINE_DOM'],
 [literalBodyMismatch,'BROWSER_CHALLENGE_CRES_BODY'],
 [externalUnknown,'BROWSER_CHALLENGE_CRES_SCRIPT_UNSUPPORTED_DOM'],
 [externalMismatch,'BROWSER_CHALLENGE_CRES_BODY'],
 [externalNoReceipt,'BROWSER_CHALLENGE_CRES_SCRIPT_RECEIPT'],
 [externalInvalidUtf8,'BROWSER_CHALLENGE_CRES_SCRIPT_READ'],
 [externalNested,'BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_PARENT'],
 [externalWithExecutable,'BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COUNT_RECOGNIZED_JS_COMPANION'],
 [externalWithInvalidCompanion,'BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_HTML_QUOTES'],
 [externalWithCommentCompanion,'BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_FORMAT_COMMENT'],
 [externalWithAssignmentCompanion,'BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_TEXT_ASSIGN_JSON'],
 [externalWithLabelledCompanion,'BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_TEXT_SHAPE_AJNSA'],
 [externalWithFoldedCompanion,'BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_TEXT_FOLD_JSON'],
 [foldedDomExtraScript,'BROWSER_CHALLENGE_CRES_SCRIPT_UNSUPPORTED_DOM'],
 [foldedDomWrongStylesheetReceipt,'BROWSER_CHALLENGE_CRES_HEAD_STYLESHEET_BLOCK'],
 [foldedDomNetworkHeadIcon,'BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_TEXT_FOLD_JSON'],
 [foldedDomIconAfterHead,'BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_TEXT_FOLD_JSON'],
 [foldedDomWireMismatch,'BROWSER_CHALLENGE_CRES_BODY'],
 [foldedDomLegacyEncoding,'BROWSER_CHALLENGE_CRES_BODY'],
 [foldedDomWrongTransaction,'BROWSER_CHALLENGE_ISSUER_SUBMISSION_FOLLOWUP'],
 ...[...captureOutcomes.keys()].map(mode=>[mode,'BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_TEXT_FOLD_JSON'] as const),
 [issuerCresDestination,'BROWSER_CHALLENGE_CRES_DESTINATION'],
 [oversizedDecision,'BROWSER_CHALLENGE_CRES_DECISION_SIZE'],
 [invalidUtf8Decision,'BROWSER_CHALLENGE_CRES_DECISION_UTF8'],
]);
const wrongScriptMime='issuer script with the wrong MIME type is refused';
for(const mode of new Set(['normal observation','late Playwright response','separate issuer process','late issuer response','direct Stripe EMVCo frame','direct reviewed issuer origin','direct reviewed issuer passive image blocked','direct reviewed issuer passive font blocked',twoFonts,sixFonts,stylesheetBlock,parserScript,decisionScript,decisionCres,isolatedDecisionCres,namedDecisionCres,literalDecisionCres,insertionDecisionCres,externalLiteral,externalGzip,externalHead,externalHeadWithData,externalHeadWithCompanionId,externalWithCompanionId,externalWithStrictCompanion,foldedDom,foldedDomHeadStylesheet,foldedDomLocalHeadIcon,foldedDomInlinePngHeadIcon,foldedDomMixedCaseHeadIcon,staticWithScriptStylesheet,...compressedDecisions.keys(),lateDecisionCres,lateDecisionHeaders,uncapturedDecision,wrongScriptMime,...refusedFollowups.keys(),...rejectedAssets.keys()]))
test(rejectedAssets.has(mode)?mode+' before handoff':refusedFollowups.has(mode)?mode+' is blocked after the original 2xx':mode===wrongScriptMime?mode:
 'an issuer iframe completes the exact challenge with '+mode, {timeout:35_000},async()=>{
 const demo='http://127.0.0.1:18081',backend='http://127.0.0.1:18080',stripe='https://js.stripe.com';
 const secret='pi_fixture_secret_fixture',key='pk_test_fixture',source='fingerprint_fixture-v2',api='https://api.stripe.com';
 const retrieve=api+'/v1/payment_intents/pi_fixture?client_secret='+secret+'&key='+key;
 const authenticate=api+'/v1/3ds2/authenticate',complete=api+'/v1/3ds2/challenge_complete';
 const outer=stripe+'/v3/three-ds-2-challenge-fixture.html',inner=stripe+'/v3/fixture-inner.html';
 const blocksPassiveImage=mode==='direct reviewed issuer passive image blocked';
 const blocksPassiveFont=mode==='direct reviewed issuer passive font blocked'||mode===twoFonts||mode===sixFonts;
 const blocksPassiveStylesheet=mode===stylesheetBlock;
 const blocksPassiveAsset=blocksPassiveImage||blocksPassiveFont||blocksPassiveStylesheet;
 const badMime=mode===wrongScriptMime,loadsScript=mode===parserScript||badMime;
 const loadsDecisionScript=mode===decisionScript;
 const compressed=compressedDecisions.get(mode);
 const namedForm=mode===namedDecisionCres;
 const literalForm=[literalDecisionCres,insertionDecisionCres,literalBodyMismatch].includes(mode);
 const dynamicForm=mode===dynamicDecisionCres;
 const captureEnabled=captureOutcomes.has(mode);
 const missingDecisionReceipt=mode===uncapturedDecision||mode===captureMissingDecision;
 const hasFoldedDom=foldedDomCases.has(mode);
 const foldedCompanion=mode===externalWithFoldedCompanion||captureEnabled&&!hasFoldedDom;
 const companionReplay=mode===externalWithStrictCompanion||foldedCompanion;
 const legacyStylesheet=mode===staticWithScriptStylesheet;
 const hasDecisionStylesheet=mode===foldedDomHeadStylesheet||mode===foldedDomWrongStylesheetReceipt||legacyStylesheet;
 const hasHeadIcon=[foldedDomLocalHeadIcon,foldedDomNetworkHeadIcon,foldedDomInlinePngHeadIcon,foldedDomMixedCaseHeadIcon,
  foldedDomIconAfterHead,captureUnsupportedHeadIcon].includes(mode);
 const externalForm=[externalLiteral,externalGzip,externalHead,externalHeadWithData,externalWithExecutable,
  externalHeadWithCompanionId,externalWithCompanionId,externalWithInvalidCompanion,externalWithCommentCompanion,
  externalWithAssignmentCompanion,externalWithLabelledCompanion,
  externalNested,externalUnknown,externalMismatch,externalNoReceipt,externalInvalidUtf8].includes(mode)||companionReplay||hasFoldedDom;
 const submitsCres=Boolean(compressed)||[decisionCres,isolatedDecisionCres,namedDecisionCres,lateDecisionCres,lateDecisionHeaders,uncapturedDecision,
  changedCresAction,changedCresBody,unsupportedCresForm,issuerCresDestination,staticWithScriptStylesheet].includes(mode)||literalForm||dynamicForm||externalForm;
 const isolated=mode===isolatedDecisionCres||namedForm||literalForm||dynamicForm||
  (externalForm&&!missingDecisionReceipt)||compressed||mode===lateDecisionHeaders||mode==='separate issuer process';
 const submitsSecondDecision=mode===secondDecisionPost||submitsCres;
 const rejectedAsset=rejectedAssets.get(mode);
 const scopedIssuer=mode==='direct reviewed issuer origin'||blocksPassiveAsset||loadsScript||loadsDecisionScript||submitsSecondDecision||Boolean(rejectedAsset);
 const direct=mode==='direct Stripe EMVCo frame'||scopedIssuer;
 const issuerName=direct?'stripe-challenge-frame':'acsFrame';
 const approvedIssuerOrigin=scopedIssuer?'https://reviewed-test-issuer.stripe.com':undefined;
 const issuerOrigin=approvedIssuerOrigin??'https://hooks.stripe.com',issuer=issuerOrigin+'/fixture-issuer',decision=issuer+'/decision';
 const assetPath=issuerOrigin+(rejectedAsset?.path??(loadsScript?'/fixture-required.js':blocksPassiveStylesheet?'/fixture-passive.css':
  blocksPassiveFont?'/fixture-passive.woff2':'/fixture-passive.png'));
 const decisionAsset=issuerOrigin+'/fixture-decision.js';
 const decisionStylesheetPath='/fixture-decision.css',decisionStylesheet=issuerOrigin+decisionStylesheetPath;
 const followup=(submitsCres&&mode!==issuerCresDestination?'https://hooks.stripe.com':issuerOrigin)+'/fixture-followup';
 const observedFollowup=mode===changedCresAction?followup+'-different':followup;
 const fontCount=mode===sixFonts?6:mode===twoFonts?2:1;
 const assetUrls=[assetPath,...Array.from({length:fontCount-1},(_,index)=>issuerOrigin+`/fixture-passive-${index+2}.woff2`)];
 const isDecisionStylesheet=(url:string)=>hasDecisionStylesheet&&url===decisionStylesheet;
 const server='11111111-2222-4333-8444-555555555555',acs='11111111-2222-4333-8444-555555555554';
 const creq=Buffer.from(JSON.stringify({messageType:'CReq',messageVersion:'2.2.0',threeDSServerTransID:server,acsTransID:acs})).toString('base64url');
 // Model the documented callback in the paired cases using synthetic transaction IDs.
 // https://docs.3dsecure.io/3dsv2/challenge_flow.html#receiving-challenge-results
 const resultMessage={messageType:'CRes',messageVersion:'2.2.0',threeDSServerTransID:server,
  acsTransID:mode===foldedDomWrongTransaction?'11111111-2222-4333-8444-555555555553':acs,transStatus:'Y'};
 const cresBytes=Buffer.from(JSON.stringify(companionReplay?{...resultMessage,challengeCompletionInd:'Y'}:resultMessage));
 const encodedCres=cresBytes.toString('base64url');
 const cres=companionReplay?encodedCres.padEnd(Math.ceil(encodedCres.length/4)*4,'='):encodedCres;
 const replayWire=Buffer.from(`cres=${encodeURIComponent(cres)}&threeDSSessionData=fixture%26session`);
 const domFixture=hasFoldedDom?syntheticIssuerDomForm(followup,{threeDSSessionData:'é + &\nline',cres}):undefined;
 const domAccent=mode===foldedDomLegacyEncoding?'%E9':'%C3%A9';
 const domWire=Buffer.from(`threeDSSessionData=${domAccent}+%2B+%26%0D%0Aline&cres=${encodeURIComponent(cres)}`);
 const sessionValue=namedForm?'':' value="fixture&amp;session"';
 const followupFields=submitsCres?`<input type="${mode===unsupportedCresForm?'text':'hidden'}" name="cres" value="${cres}"><input type="hidden" name="threeDSSessionData"${sessionValue}>`:
  '<input name="result" value="fixture">';
 const followupScript=[
  mode===changedCresAction?'document.forms[0].action='+JSON.stringify(observedFollowup):'',
  mode===changedCresBody?'document.forms[0].elements.namedItem("threeDSSessionData").value="different"':'',
  'document.forms[0].submit()',
 ].filter(Boolean).join(';');
 const staticSubmit=legacyStylesheet?`
  const stylesheet=document.createElement('link');
  stylesheet.rel='stylesheet';stylesheet.type='text/css';stylesheet.href=${JSON.stringify(decisionStylesheetPath)};
  stylesheet.addEventListener('error',()=>document.forms[0].submit(),{once:true});
  document.head.append(stylesheet);
 `:followupScript;
 const script=`(async()=>{
  await (await fetch(${JSON.stringify(retrieve)})).json();
  await (await fetch(${JSON.stringify(authenticate)},{method:'POST',body:new URLSearchParams({source:${JSON.stringify(source)},key:${JSON.stringify(key)}})})).json();
  const frame=document.createElement('iframe');frame.name=${JSON.stringify(issuerName)};document.body.append(frame);
  window.addEventListener('message',async event=>{
   if(event.source!==frame.contentWindow || event.origin!==${JSON.stringify(submitsCres?new URL(followup).origin:issuerOrigin)} || event.data!=='fixture decision')return;
   ${loadsDecisionScript?'frame.remove();':''}
   try{
    const reply=await fetch(${JSON.stringify(complete)},{method:'POST',body:new URLSearchParams({source:${JSON.stringify(source)},key:${JSON.stringify(key)}})});
    if(!(await reply.json()).complete)throw Error();
    const final=await (await fetch(${JSON.stringify(retrieve)})).json();
    document.body.dataset.fixtureResult=final.status;
   }catch{document.body.dataset.fixtureResult='refused';}
  });
  const form=document.createElement('form');form.method='post';form.target=frame.name;form.action=${JSON.stringify(issuer)};
  const field=document.createElement('input');field.type='hidden';field.name='creq';field.value=${JSON.stringify(creq)};
  form.append(field);document.body.append(form);form.submit();
 })()`;
 const json=(value:object)=>({contentType:'application/json',headers:{'Access-Control-Allow-Origin':stripe},body:JSON.stringify(value)});
 const issuerButton='<button name="decision" value="complete">Complete</button>';
 const revealButton="document.querySelector('form').append(document.querySelector('template').content.cloneNode(true))";
 const issuerDecision='parent.postMessage("fixture decision",'+JSON.stringify(stripe)+')';
 const issuerBody='<!doctype html><form method="post" action="'+decision+'">'+
  (blocksPassiveAsset||rejectedAsset||loadsScript?'<template>'+issuerButton+'</template>':issuerButton)+'</form>'+
  (blocksPassiveImage?`<img width="1" height="1" alt="" src="${assetPath}" onerror="${revealButton}">`:
   blocksPassiveFont?`<style>${assetUrls.map((url,index)=>`@font-face{font-family:FixturePassive${index};src:url("${url}") format("woff2")}`).join('')}</style>
     <script>Promise.allSettled([${assetUrls.map((_,index)=>`document.fonts.load('16px FixturePassive${index}','X')`).join(',')}])
       .then(results=>{if(results.every(result=>result.status==='rejected')){${revealButton}}})</script>`:
   blocksPassiveStylesheet?`<link rel="stylesheet" href="${assetPath}" onerror="${revealButton}">`:
   rejectedAsset?.kind==='stylesheet'?`<link rel="stylesheet" href="${assetPath}" onload="${revealButton}">`:
   rejectedAsset?.kind==='script'?`<script>const s=document.createElement('script');s.src="${assetPath}";document.head.append(s)</script>`:
   loadsScript?`<script src="${assetPath}"></script>`:'');
 const followupTarget=namedForm?` target="${issuerName}"`:'';
 const followupForm=`<form method="post" action="${followup}"${followupTarget}>${followupFields}</form>`;
 const insertForm=mode===insertionDecisionCres||mode===externalWithCompanionId?
  "document.body.insertAdjacentHTML('beforeend', ":'document.write(';
 const literalSource=`${insertForm}${JSON.stringify(followupForm)});${followupScript};`;
 const inlineDecision=(source:string)=>`<!doctype html><body><script>${source}</script></body>`;
 const dynamicSource=`
  const result=document.createElement('form');result.method='post';result.action=${JSON.stringify(followup)};
  const field=document.createElement('input');field.type='hidden';field.name='cres';field.value=${JSON.stringify(cres)};
  result.append(field);document.body.append(result);result.submit();
 `;
 const externalTag='<script src="/fixture-decision.js"></script>';
 const jsonWithId=(id:string,contents='{"fixture":true}')=>`<script type="application/json" id="${id}">${contents}</script>`;
 // The two synthetic decision pages differ only in the inline data block.
 const companionJson='{"fixture":true}';
 const replayCompanion=foldedCompanion?
  Buffer.from(companionJson).toString('base64').replace(/^.{5}/,'$&\n'):companionJson;
 const png='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGNgAAIAAAUAAXpeqz8AAAAASUVORK5CYII=';
 const pngHref='data:image/png;base64,'+png,mixedCasePngHref='DaTa:ImAgE/PnG;BaSe64,'+png;
 const unsupportedIconHref='data:image/svg+xml,%3Csvg%20id%3D%22fixture-private-icon%22%2F%3E';
 const decisionIcon=mode===captureUnsupportedHeadIcon?`<link rel="icon" href="${unsupportedIconHref}" type="image/png">`:
  mode===foldedDomMixedCaseHeadIcon?`<link rel="icon" href="${mixedCasePngHref}" type="image/png">`:
  mode===foldedDomInlinePngHeadIcon?`<link rel="icon" href="${pngHref}" type="image/png">`:
  `<link rel="shortcut icon" href="${mode===foldedDomNetworkHeadIcon?'/fixture-head-icon.ico':'data:image/x-icon;,'}" type="image/x-icon">`;
 const decisionHead=hasDecisionStylesheet?`<head><link rel="stylesheet" href="${decisionStylesheetPath}" type="text/css"></head>`:
  mode===foldedDomIconAfterHead?`<head></head>${decisionIcon}`:
  hasHeadIcon?`<head>${decisionIcon}</head>`:'';
 const externalDocument=domFixture?`<!doctype html>${decisionHead}<body>${jsonWithId(domFixture.id,domFixture.folded)}${externalTag}</body>`:
  mode===externalHead?`<!doctype html><head>${externalTag}</head><body></body>`:
  mode===externalHeadWithData?`<!doctype html><head><script type="application/json">{"fixture":true}</script>${externalTag}</head><body></body>`:
  mode===externalHeadWithCompanionId?`<!doctype html><head>${jsonWithId('document')}${jsonWithId('write')}${externalTag}</head><body></body>`:
  mode===externalWithExecutable?`<!doctype html><body><script>void 0;</script>${externalTag}</body>`:
  mode===externalWithCompanionId?`<!doctype html><body>${jsonWithId('body')}${jsonWithId('forms')}${jsonWithId('submit')}${externalTag}</body>`:
  mode===externalWithInvalidCompanion?`<!doctype html><body>${jsonWithId('fixture-data','{&quot;fixture&quot;:true}')}${externalTag}</body>`:
  mode===externalWithCommentCompanion?`<!doctype html><body>${jsonWithId('fixture-data','<!-- {&quot;fixture&quot;:true} -->')}${externalTag}</body>`:
  mode===externalWithAssignmentCompanion?`<!doctype html><body>${jsonWithId('fixture-data','window.fixture = {"fixture":true};')}${externalTag}</body>`:
  mode===externalWithLabelledCompanion?`<!doctype html><body>${jsonWithId('fixture-data','fixture label: {"fixture":true};')}${externalTag}</body>`:
  companionReplay?`<!doctype html>${mode===captureMaskedLayout?'<html lang="en">':''}<body>${jsonWithId('fixture-data',replayCompanion)}${externalTag}</body>`:
  mode===externalMismatch?`<!doctype html><body>${jsonWithId('fixture-data')}${externalTag}</body>`:
  mode===externalNested?`<!doctype html><body><div>${externalTag}</div></body>`:
  `<!doctype html><body>${externalTag}</body>`;
 let decisionHtml=submitsSecondDecision?
  externalForm?externalDocument:
  literalForm?inlineDecision(literalSource):dynamicForm?inlineDecision(dynamicSource):
  `<!doctype html>${followupForm}<script>${staticSubmit}</script>`:
  loadsDecisionScript?`<!doctype html><script src="${decisionAsset}"></script>`:
  '<!doctype html><script>'+issuerDecision+'</script>';
 if(compressed)decisionHtml='\uFEFF'+decisionHtml+'<!-- Résultat -->';
 if(compressed?.decodedSize){
  decisionHtml+='<!--'+'x'.repeat(compressed.decodedSize-Buffer.byteLength(decisionHtml)-7)+'-->';
  assert.equal(Buffer.byteLength(decisionHtml),compressed.decodedSize);
 }
 const decisionBytes=mode===invalidUtf8Decision?Buffer.concat([Buffer.from(decisionHtml),Buffer.from([0xff])]):Buffer.from(decisionHtml);
 const responses=new Map<string,NonNullable<Parameters<Route['fulfill']>[0]>>([
  [demo+'/',{contentType:'text/html',body:'<!doctype html><link rel="icon" href="data:,"><iframe name="__privateStripeFrame7" src="'+outer+'"></iframe>'}],
  [outer,{contentType:'text/html',body:direct?'<!doctype html><body><script>'+script+'</script></body>':
   '<!doctype html><iframe name="stripe-challenge-frame" src="'+inner+'"></iframe>'}],
  [inner,{contentType:'text/html',body:'<!doctype html><body><script>'+script+'</script></body>'}],
  [retrieve,json({object:'payment_intent',
   livemode:false,id:'pi_fixture',client_secret:secret,status:'requires_action',
   next_action:{type:'use_stripe_sdk',use_stripe_sdk:{type:'stripe_3ds2_fingerprint',three_d_secure_2_source:source}}})],
  [authenticate,json({state:'challenge_required',
   ares:{messageType:'ARes',transStatus:'C',messageVersion:'2.2.0',threeDSServerTransID:server,acsTransID:acs,acsURL:issuer}})],
  [issuer,{contentType:'text/html',body:issuerBody}],
  [decision,{contentType:hasFoldedDom&&mode!==foldedDomLegacyEncoding?'text/html; charset=utf-8':'text/html',body:decisionHtml}],
  [complete,json({complete:true})],
 ]);
 if(rejectedAsset)responses.set(assetPath,{contentType:rejectedAsset.contentType,body:rejectedAsset.body});
 if(loadsScript)responses.set(assetPath,{contentType:badMime?'text/html':'application/javascript',body:revealButton});
 if(loadsDecisionScript||externalForm)responses.set(decisionAsset,{contentType:'application/javascript',body:issuerDecision});
 if(submitsSecondDecision)responses.set(observedFollowup,{contentType:'text/html',body:submitsCres?
  '<!doctype html><script>'+issuerDecision+'</script>':'unexpected forwarded form'});
 const channel=process.env.PAYMENT_TEST_CHROME_CHANNEL==='chrome'?'chrome':'chromium';
 const invalidMarker=literalSource.indexOf('fixture&amp;session');
 const externalBytes=mode===externalInvalidUtf8?Buffer.concat([
  Buffer.from(literalSource.slice(0,invalidMarker)),Buffer.from([0xf0,0x90,0x80]),
  Buffer.from(literalSource.slice(invalidMarker+'fixture'.length)),
 ]):Buffer.from(domFixture?domFixture.script+(mode===foldedDomExtraScript?'void 0;':''):mode===externalUnknown?dynamicSource:literalSource);
 const networkUrl=externalForm?decisionAsset:decision;
 const networkFixture=externalForm?await serveIssuerResponse(new URL(networkUrl),
  mode===externalGzip?gzipSync(externalBytes):externalBytes,mode===externalGzip?'gzip':'identity',mode===externalGzip,true):
  compressed?await serveIssuerResponse(new URL(networkUrl),compressed.compress(decisionBytes),compressed.encoding,Boolean(compressed.chunked)):undefined;
 const browser=await chromium.launch({channel,headless:true,timeout:10_000,args:[
  '--site-per-process','--disable-background-networking','--proxy-server=direct://',
  '--host-resolver-rules='+(networkFixture?'MAP '+new URL(networkUrl).hostname+' 127.0.0.1:'+networkFixture.port+', ':'')+'MAP * ~NOTFOUND',
  ...(isolated?['--isolate-origins='+[stripe,issuerOrigin,...(submitsCres?[new URL(followup).origin]:[])].join(',')]:[]),
 ]}).catch(async error=>{await networkFixture?.close();throw error;});
 const stop=new AbortController();
 let attendance:Promise<boolean>|undefined;
 let captureDirectory:string|undefined;
 try{
  const context=await browser.newContext({serviceWorkers:'block',acceptDownloads:false,ignoreHTTPSErrors:Boolean(networkFixture)});
  const page=await context.newPage(),forwarded:string[]=[],refusals:string[]=[];
  if(mode===foldedDomMixedCaseHeadIcon){
   const responses=await page.evaluate(urls=>Promise.all(urls.map(async url=>{
    const response=await fetch(url);
    return {type:response.headers.get('content-type'),bytes:[...new Uint8Array(await response.arrayBuffer())]};
   })),[pngHref,mixedCasePngHref]);
   const image={type:'image/png',bytes:[...Buffer.from(png,'base64')]};
   assert.deepEqual(responses,[image,image]);
  }
  const passiveRequests:Request[]=[],passiveFailures:Request[]=[],passiveResponses:Response[]=[];
  const passiveAborts:(string|undefined)[]=[],authorized:string[]=[];
  page.on('requestfailed',request=>{if(assetUrls.includes(request.url())||isDecisionStylesheet(request.url()))passiveFailures.push(request);});
  let lateHeadersObserved=false,decisionBodyCaptured=false,scriptBodyCaptured=false,scriptBodyRequested=false;
  let abortConfirmed=false,captureCalls=0;
  let captureAfterAbort=false,capturedSource:CaptureSource|undefined;
  const captureStages:IssuerReplayStatus[]=[];
  let captureWriter:ReturnType<typeof prepareIssuerReplayCapture>,captureFile:string|undefined;
  if(captureEnabled){
   captureDirectory=realpathSync(mkdtempSync(join(tmpdir(),'billing-challenge-capture-')));
   const id='11111111-2222-4333-8444-555555555555',run=join(captureDirectory,id);
   mkdirSync(run,{mode:0o700});
   writeFileSync(join(run,'owner.lock'),'synthetic\n',{mode:0o600});
   captureFile=join(run,'issuer-replay-fixture.private.json');
   captureWriter=prepareIssuerReplayCapture(id,id,captureDirectory);
  }
  const captureDone=Promise.withResolvers<void>();
  page.on('response',response=>{
   if(assetUrls.includes(response.url())||isDecisionStylesheet(response.url()))passiveResponses.push(response);
   if(mode===lateDecisionHeaders&&response.url()===decision){
    lateHeadersObserved=true;
    response.headerValue=()=>new Promise<never>(()=>{});
   }
  });
  const limits={deadline:Date.now()+15_000,signal:stop.signal};
  let assetErrorRedacted=false;
  let issuerFrame:Frame|undefined,attachedIssuerFrame=false,separateIssuerTarget=false,gestures=0;
  let claimedStylesheetDocument:Request|undefined,observedDecision:Request|undefined;
  let stylesheetParserProved=false;
  const guard=await guardChallengeRedirects(page,reason=>refusals.push(reason),{demo,backend},stop.signal,approvedIssuerOrigin,true);
  const challenge=initial3dsChallenge({page,limits,demo,owner:()=>({key,secret}),winning:async()=>{},check:async()=>{},
   expectIssuerAssetBlock:async(request,document)=>{
    const proof=await guard.expectIssuerAssetBlock(request,document);
    if(isDecisionStylesheet(request.url())){
     claimedStylesheetDocument=document;
     stylesheetParserProved=proof===true;
    }
    return proof;
   },
   authorizeIssuerScript:async(request,document,capture)=>{
    await guard.authorizeIssuerScript(request,document,capture);
    if(externalForm)authorized.push(request.url());
   },
   takeIssuerScriptBody:(request,document)=>{
    scriptBodyRequested=true;
    assert.equal(guard.takeIssuerScriptBody(request,request),undefined);
    const receipt=guard.takeIssuerScriptBody(request,document);
    assert.equal(guard.takeIssuerScriptBody(request,document),undefined);
    if(mode===externalNoReceipt||mode===captureMissingReceipt)return;
    return receipt?.then(body=>{
     if(body instanceof Uint8Array){
      assert.deepEqual(Buffer.from(body),externalBytes);
      scriptBodyCaptured=true;
      if(mode===externalMismatch)return Buffer.from(literalSource.replace('fixture&amp;session','other&amp;session'));
     }
     return body;
    });
   },
   authorizeIssuerDecision:async request=>{
    observedDecision=request;
    authorized.push(request.url());
    if(missingDecisionReceipt)await guard.authorize(request);
    else{
     assert.ok(guard.authorizeIssuerDecision);
     await guard.authorizeIssuerDecision(request);
    }
   },
   takeIssuerDecisionBody:async request=>{
    const bytes=await guard.takeIssuerDecisionBody(request);
    if(request.url()===decision&&bytes instanceof Uint8Array){
     if(compressed)assert.equal(Buffer.from(bytes).equals(decisionBytes),true);
     decisionBodyCaptured=true;
     if(mode===literalBodyMismatch)return Buffer.from(decisionHtml.replace('fixture&amp;session','server&amp;session'));
     if(mode===foldedDomWrongStylesheetReceipt){
      return Buffer.from(decisionHtml.replace(decisionStylesheetPath,'/other-fixture-decision.css'));
     }
     if(mode===foldedDomWireMismatch&&domFixture){
      const different=syntheticIssuerDomForm(followup,{threeDSSessionData:'different',cres});
      return Buffer.from(decisionHtml.replace(domFixture.folded,different.folded));
     }
    }
    return bytes;
   },
   authorizeIssuerResult:async(request,document)=>{
    if(namedForm){
     assert.equal(request.frame(),issuerFrame);
     assert.deepEqual(request.postDataBuffer(),Buffer.from(`cres=${cres}&threeDSSessionData=`));
    }
    if(companionReplay)assert.deepEqual(request.postDataBuffer(),replayWire);
    if(hasFoldedDom)assert.deepEqual(request.postDataBuffer(),domWire);
    authorized.push(request.url());
    await guard.authorizeIssuerResult(request,document);
   },
   issuerReplay:captureWriter?{
    save:source=>{
     captureCalls++;
     captureAfterAbort=abortConfirmed;
     capturedSource=source;
     return captureWriter!(source);
    },
    report:stage=>captureStages.push(stage),
   }:undefined,
   approvedIssuerOrigin,authorizeResponse:async request=>{
    authorized.push(request.url());
    assert.ok(!assetUrls.includes(request.url()));
    await guard.authorize(request);
   },waitForGesture:async()=>{
    if(blocksPassiveAsset){
     assert.equal(passiveRequests.length,assetUrls.length);
     assert.deepEqual(new Set(passiveFailures),new Set(passiveRequests));
     assert.deepEqual(passiveAborts,assetUrls.map(()=>'blockedbyclient'));
    }
    gestures++;
    if(isolated&&issuerFrame){
     const session=await context.newCDPSession(issuerFrame);
     try{
      const {targetInfo}=await session.send('Target.getTargetInfo');
      separateIssuerTarget=targetInfo.type==='iframe'&&targetInfo.url===issuer;
     }finally{await session.detach();}
    }
   },
   stripeFrames:request=>{
    const target=request.frame(),parent=target.parentFrame();
    if(direct&&target.url()===outer&&parent===page.mainFrame()){
     return {target,frames:new Map([[target,outer],[parent,demo+'/']])};
    }
    if(target.url()===inner&&parent?.url()===outer&&parent.parentFrame()===page.mainFrame()){
     return {target,frames:new Map([[target,inner],[parent,outer]])};
    }
   },
  });
  function assertDecisionStylesheetBlocked(){
   if(!hasDecisionStylesheet)return;
   assert.ok(observedDecision);
   assert.equal(claimedStylesheetDocument,observedDecision);
   assert.equal(stylesheetParserProved,!legacyStylesheet);
   assert.equal(passiveRequests.length,1);
   const [stylesheet]=passiveRequests;
   assert.equal(stylesheet?.url(),decisionStylesheet);
   assert.equal(stylesheet?.resourceType(),'stylesheet');
   assert.equal(stylesheet?.frame(),issuerFrame);
   assert.equal(stylesheet?.existingResponse(),null);
   assert.deepEqual(passiveFailures,passiveRequests);
   assert.deepEqual(passiveAborts,['blockedbyclient']);
   assert.deepEqual(passiveResponses,[]);
  }
  await context.route('**/*',async route=>{
   const request=route.request(),url=request.url(),response=responses.get(url);
   const isPassiveAsset=blocksPassiveAsset&&assetUrls.includes(url)||isDecisionStylesheet(url);
   const isRejectedAsset=rejectedAsset&&url===assetPath;
   if(!response&&!isPassiveAsset){await route.abort('blockedbyclient');return;}
   guard.registerRoute(request);
   let checkpoint='route refused';
   let attemptedRoute:Route|undefined;
   try{
    if(challenge.owns(new URL(url))){
     if(mode==='late Playwright response'&&(url===retrieve||url===authenticate))request.existingResponse=()=>null;
     if((mode==='late issuer response'||mode===lateDecisionCres)&&url===decision)request.existingResponse=()=>null;
     const actual=Object.create(route) as Route;
     attemptedRoute=actual;
     actual.continue=async()=>{
      assert.ok(response,'passive asset must never reach continuation');
      const reply=url===retrieve&&forwarded.includes(complete)?json({status:'succeeded'}):response;
      forwarded.push(url);
      if(networkFixture&&url===networkUrl){await route.continue();return;}
      await route.fulfill(reply);
     };
     if(isPassiveAsset||isRejectedAsset){
      passiveRequests.push(request);
     }
     if(isPassiveAsset){
      actual.abort=async errorCode=>{passiveAborts.push(errorCode);await route.abort(errorCode);};
      actual.fallback=async()=>assert.fail('passive asset must never reach fallback');
      actual.fulfill=async()=>assert.fail('passive asset must never receive a synthetic response');
     }
     if(url===issuer){
      issuerFrame=request.frame();
      attachedIssuerFrame=issuerFrame.name()===issuerName&&issuerFrame.url()==='about:blank';
     }
     await challenge.forward(actual,new URL(url),code=>{checkpoint=code;});
    }else{
     assert.ok(response);
     await guard.authorize(request);await route.fulfill(response);
    }
   }catch(error){
    refusals.push(checkpoint);
    if(isRejectedAsset)assetErrorRedacted=error instanceof Error&&String(error)==='Error: billing_BROWSER'&&JSON.stringify(error)==='{}';
    if(captureEnabled&&url===followup&&attemptedRoute){
     const capture=challenge.captureRefusedIssuerFixture(attemptedRoute,async()=>{
      await route.abort('blockedbyclient');
      if(mode===captureAbortFailure)throw Error('unconfirmed abort');
      abortConfirmed=true;
     });
     if(capture){
      await capture;
      assert.equal(challenge.captureRefusedIssuerFixture(attemptedRoute,()=>assert.fail('capture was used twice')),undefined);
      captureDone.resolve();
      return;
     }
     captureDone.resolve();
    }
    await route.abort('blockedbyclient').catch(()=>{});
   }
  });
  if(rejectedAsset){
   attendance=challenge.attend().then(()=>true,()=>false);
   const [blocked]=await Promise.all([
    page.waitForEvent('requestfailed',{predicate:request=>request.url()===assetPath,timeout:8_000}),
    page.goto(demo+'/',{waitUntil:'domcontentloaded',timeout:7_000}),
   ]);
   assert.equal(attachedIssuerFrame,true);
   assert.equal(refusals[0],rejectedAsset.checkpoint);
   // The owner retains the first failure. The route and Chromium can both report the abort.
   assert.ok(refusals.slice(1).every(code=>code==='BROWSER_GUARD_RESPONSE_NETWORK' ||
    rejectedAsset.kind==='script' && code==='BROWSER_CHALLENGE_ISSUER_ASSET_SCRIPT'),JSON.stringify(refusals));
   assert.equal(assetErrorRedacted,true);
   assert.equal(passiveRequests.length,1);
   assert.equal(passiveRequests[0]===blocked,true);
   assert.equal(blocked.resourceType(),rejectedAsset.kind);
   assert.equal(blocked.method(),'GET');
   assert.equal(blocked.frame()===issuerFrame,true);
   assert.equal(blocked.isNavigationRequest(),false);
   assert.equal(blocked.serviceWorker(),null);
   assert.match(blocked.failure()?.errorText??'',/^net::ERR_BLOCKED_BY_CLIENT(?:\.Inspector)?$/);
   assert.equal(blocked.existingResponse(),null);
   assert.equal(await blocked.frame().locator('form button[name=decision]').count(),0);
   assert.equal(passiveResponses.length,0);
   assert.deepEqual(forwarded,[retrieve,authenticate,issuer]);
   assert.deepEqual(authorized,[retrieve,authenticate,issuer]);
   assert.equal(gestures,0);
   return;
  }
  if(badMime){
   attendance=challenge.attend().then(()=>true,()=>false);
   const [request]=await Promise.all([
    page.waitForEvent('requestfailed',{predicate:value=>value.url()===assetPath,timeout:8_000}),
    page.goto(demo+'/',{waitUntil:'domcontentloaded',timeout:7_000}),
   ]);
   assert.equal(refusals[0],'BROWSER_GUARD_RESPONSE_ISSUER_SCRIPT');
   assert.equal(request.existingResponse(),null);
   assert.equal(await request.frame().locator('form button').count(),0);
   assert.deepEqual(forwarded,[retrieve,authenticate,issuer,assetPath]);
   assert.equal(gestures,0);
   return;
  }
  await page.goto(demo+'/',{waitUntil:'domcontentloaded',timeout:7_000});
  if(refusedFollowups.has(mode)){
   const [request]=await Promise.all([
    page.waitForEvent('requestfailed',{predicate:value=>value.url()===observedFollowup,timeout:8_000}),
    challenge.attend(),
   ]);
   assert.equal(request.frame(),issuerFrame);
   assert.equal(request.method(),'POST');
   assert.equal(request.isNavigationRequest(),true);
   assert.equal(request.existingResponse(),null);
   if(hasFoldedDom)assert.deepEqual(request.postDataBuffer(),domWire);
   // Chromium can return invalid source as exact bytes or lossy decoded text.
   assert.equal(refusals[0],mode===externalInvalidUtf8&&scriptBodyCaptured?
    'BROWSER_CHALLENGE_CRES_SCRIPT_UTF8':refusedFollowups.get(mode));
   if(companionReplay||captureEnabled){
    if(companionReplay)assert.deepEqual(request.postDataBuffer(),replayWire);
    if(captureEnabled)await captureDone.promise;
    const saved=mode===captureFoldedCompanion||mode===captureMaskedLayout||mode===captureUnsupportedHeadIcon;
    assert.equal(scriptBodyRequested,saved||mode===captureMissingReceipt);
    assert.equal(captureCalls,saved?1:0);
    assert.equal(captureStages.at(-1),captureOutcomes.get(mode));
    if(saved){
     assert.ok(capturedSource);
     assert.equal(captureAfterAbort,true);
     assert.deepEqual(Buffer.from(capturedSource.html),decisionBytes);
     assert.deepEqual(Buffer.from(capturedSource.script),externalBytes);
     assert.equal(capturedSource.documentUrl,decision);
     assert.equal(capturedSource.scriptUrl,decisionAsset);
     assert.equal(capturedSource.frameName,issuerName);
     assert.equal(lstatSync(captureFile!).mode&0o777,0o600);
     const artifact=readFileSync(captureFile!,'utf8');
     const report=JSON.parse(artifact) as {status:string;shape?:IssuerDecisionReplayShape};
     assert.equal(report.status,captureOutcomes.get(mode));
     assert.doesNotMatch(artifact,/reviewed-test-issuer|stripe-challenge-frame|fixture-decision\.js/);
     if(mode===captureUnsupportedHeadIcon){
      assert.deepEqual(Object.keys(report).sort(),['shape','status']);
      const icon=report.shape?.layout.find(node=>node.headKind==='LINK_ICON');
      assert.equal(icon?.iconHrefKind,'UNSUPPORTED_DATA');
      assert.equal(icon?.iconDataRejection,'MEDIA_TYPE');
      assert.doesNotMatch(artifact,/fixture-private-icon|image\/svg\+xml|%3Csvg|data:/i);
     }
    }else if(captureFile)assert.throws(()=>lstatSync(captureFile),{code:'ENOENT'});
   }
   if(submitsCres&&mode!==issuerCresDestination)assert.equal(decisionBodyCaptured,
    mode!==oversizedDecision&&mode!==foldedDomWrongTransaction&&!missingDecisionReceipt);
   if(networkFixture)assert.equal(networkFixture.requests(),1);
   if(hasHeadIcon)assert.equal(networkFixture?.unexpectedRequests(),0);
   if(externalForm&&mode!==externalInvalidUtf8)assert.equal(scriptBodyCaptured,
    ![externalNoReceipt,externalNested,externalWithExecutable,externalWithInvalidCompanion,externalWithCommentCompanion,
     externalWithAssignmentCompanion,externalWithLabelledCompanion,externalWithFoldedCompanion,
     captureMissingDecision,captureMissingReceipt,captureAbortFailure,foldedDomWrongTransaction,
     foldedDomWrongStylesheetReceipt,foldedDomNetworkHeadIcon,foldedDomIconAfterHead].includes(mode));
   assert.deepEqual(forwarded,[retrieve,authenticate,issuer,decision,...(externalForm?[decisionAsset]:[])]);
   assert.deepEqual(authorized,forwarded);
   assertDecisionStylesheetBlocked();
   assert.equal(gestures,1);
   return;
  }
  try{
   const stripeFrame=page.frameLocator('iframe');
   const result=(direct?stripeFrame:stripeFrame.frameLocator('iframe')).locator('body[data-fixture-result]');
   await Promise.all([challenge.attend(),result.waitFor({state:'attached',timeout:8_000})]);
   assert.equal(await result.getAttribute('data-fixture-result'),'succeeded');
  }catch{assert.fail(JSON.stringify({attachedIssuerFrame,separateIssuerTarget,refusals,gestures,forwarded:forwarded.length,
   passiveAborts:passiveAborts.length,passiveFailures:passiveFailures.length}));}
  assert.equal(attachedIssuerFrame,true);
  if(isolated)assert.equal(separateIssuerTarget,true);
  if(mode===lateDecisionHeaders)assert.ok(lateHeadersObserved&&decisionBodyCaptured);
  if(networkFixture){
   assert.equal(networkFixture.requests(),1);
   assert.equal(decisionBodyCaptured,true);
  }
  if(hasHeadIcon)assert.equal(networkFixture?.unexpectedRequests(),0);
  if(externalForm)assert.equal(scriptBodyCaptured,true);
  if(companionReplay)assert.equal(scriptBodyRequested,true);
  if(mode===uncapturedDecision)assert.equal(decisionBodyCaptured,false);
  if(mode===isolatedDecisionCres||namedForm||externalForm||compressed||mode===lateDecisionHeaders){
   assert.ok(issuerFrame);
   const target=await context.newCDPSession(issuerFrame);
   try{
    const {targetInfo}=await target.send('Target.getTargetInfo');
    assert.equal(targetInfo.type,'iframe');
    assert.equal(targetInfo.url,followup);
   }finally{await target.detach();}
  }
  assert.deepEqual(refusals,[]);
  assert.deepEqual(forwarded,[retrieve,authenticate,issuer,...(loadsScript?[assetPath]:[]),decision,
   ...(loadsDecisionScript||externalForm?[decisionAsset]:[]),...(submitsCres?[followup]:[]),complete,retrieve]);
  if(submitsCres)assert.deepEqual(authorized,forwarded);
  assertDecisionStylesheetBlocked();
  assert.equal(gestures,1);
  if(blocksPassiveAsset){
   assert.deepEqual(passiveRequests.map(asset=>asset.url()).sort(),[...assetUrls].sort());
   assert.equal(passiveFailures.length,assetUrls.length);
   for(const asset of passiveRequests){
    assert.equal(asset.method(),'GET');
    assert.equal(asset.postDataBuffer(),null);
    assert.equal(asset.resourceType(),blocksPassiveFont?'font':blocksPassiveStylesheet?'stylesheet':'image');
    assert.equal(asset.isNavigationRequest(),false);
    assert.equal(asset.frame(),issuerFrame);
    assert.equal(asset.serviceWorker(),null);
    assert.match(asset.failure()?.errorText??'',/^net::ERR_BLOCKED_BY_CLIENT(?:\.Inspector)?$/);
    assert.equal(asset.existingResponse(),null);
   }
   assert.deepEqual(passiveResponses,[]);
   assert.deepEqual(authorized,[retrieve,authenticate,issuer,decision,complete,retrieve]);
  }
 }finally{
  stop.abort();
  try{await browser.close();}finally{
   try{await networkFixture?.close();}finally{if(captureDirectory)rmSync(captureDirectory,{recursive:true,force:true});}
  }
  if(blocksPassiveAsset||rejectedAsset||badMime){
   assert.equal(browser.isConnected(),false);
   if(attendance)assert.equal(await attendance,false);
  }
 }
});
