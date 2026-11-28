import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdtempSync,readFileSync,rmSync} from 'node:fs';
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
// @ts-ignore Native Node executes the same fixture as the result-form tests.
import {syntheticIssuerDomForm} from './stripe-billing-initial-3ds-dom-fixture.ts';

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
const secondDecisionPost='a repeat of the original trusted decision POST';
const decisionCres='a CRes POST from a committed hooks issuer document';
const isolatedDecisionCres='a CRes POST between isolated issuer and hooks targets';
const namedDecisionCres='a CRes form targeting the same issuer frame with an empty session value';
const dynamicDecisionCres='a CRes form assembled by arbitrary issuer JavaScript';
const changedCresAction='a CRes form whose action is set by issuer JavaScript';
const changedCresBody='a CRes form whose optional session data is set by issuer JavaScript';
const externalFolded='a CRes form assembled from folded data by an external issuer script';
const parserStylesheet='a dynamic CRes form with a blocked parser-initiated stylesheet';
const scriptStylesheet='a CRes form with a blocked script-initiated stylesheet';
const oversizedDecision='a gzip decision that expands past 64 KiB';
const invalidUtf8Decision='a gzip decision with trailing invalid UTF-8';
const compressedDecisions=new Map([
 ['a gzip CRes decision between isolated issuer and hooks targets',{encoding:'gzip',compress:gzipSync}],
 ['a chunked Brotli CRes decision with exactly 64 KiB decoded',{encoding:'br',compress:brotliCompressSync,chunked:true,decodedSize:65536}],
 ['a deflate CRes decision between isolated issuer and hooks targets',{encoding:'deflate',compress:deflateSync}],
 ['a Zstandard CRes decision between isolated issuer and hooks targets',{encoding:'zstd',compress:zstdCompressSync}],
 [oversizedDecision,{encoding:'gzip',compress:gzipSync,decodedSize:65537}],
 [invalidUtf8Decision,{encoding:'gzip',compress:gzipSync}],
]);
const lateDecisionCres='a CRes POST with late issuer response observation';
const unavailableDecisionHeaders='a CRes POST with unavailable Playwright response headers';
const repeatedCres='a second attempt to forward the same paused CRes POST';
const cancelledCres='a CRes POST cancelled after CDP authorization';
const wrongIssuerDestination='a CRes POST to the issuer origin';
const rootHooksDestination='a CRes POST to the hooks root';
const unexpectedField='a CRes POST with an unexpected form field';
const duplicateCres='a CRes POST with a second CRes field';
const duplicateSession='a CRes POST with a second session data field';
const legacyEncoding='a CRes POST with invalid UTF-8 form data';
const wrongAcsId='a CRes POST with the wrong issuer transaction ID';
const wrongServerId='a CRes POST with the wrong server transaction ID';
const wrongVersion='a CRes POST with the wrong message version';
const nonterminalStatus='a CRes POST with a nonterminal status';
const mismatchedDecision='a CRes POST with the Playwright response of a different request';
const replacedIssuerDocument='a CRes POST after the issuer document changes its URL';
const refusedFollowups=new Map([
 [secondDecisionPost,'BROWSER_CHALLENGE_ISSUER_SUBMISSION_REPEAT'],
 [wrongIssuerDestination,'BROWSER_CHALLENGE_CRES_DESTINATION'],
 [rootHooksDestination,'BROWSER_CHALLENGE_CRES_DESTINATION'],
 [cancelledCres,'BROWSER_CHALLENGE_ROUTE_FORWARD'],
 ...[unexpectedField,duplicateCres,duplicateSession,legacyEncoding,wrongAcsId,wrongServerId,wrongVersion,nonterminalStatus]
  .map(mode=>[mode,'BROWSER_CHALLENGE_CRES_BODY'] as const),
 [mismatchedDecision,'BROWSER_CHALLENGE_CRES_DECISION_RESPONSE'],
 [replacedIssuerDocument,'BROWSER_CHALLENGE_ISSUER_SUBMISSION_DOCUMENT'],
]);
const wrongScriptMime='issuer script with the wrong MIME type is refused';
const unclaimedDecision='an issuer decision response without an authorized request';
const wrongDecisionMime='an issuer decision response with the wrong MIME type';
const wrongResultMime='a CRes response with the wrong MIME type';
const rejectedResponses=new Map([
 [unclaimedDecision,'BROWSER_GUARD_RESPONSE_UNCLAIMED'],
 [wrongDecisionMime,'BROWSER_GUARD_RESPONSE_ISSUER_DECISION'],
 [wrongResultMime,'BROWSER_GUARD_RESPONSE_ISSUER_RESULT'],
]);
const cresCases=new Set([decisionCres,isolatedDecisionCres,namedDecisionCres,dynamicDecisionCres,changedCresAction,changedCresBody,
 externalFolded,parserStylesheet,scriptStylesheet,...compressedDecisions.keys(),lateDecisionCres,unavailableDecisionHeaders,repeatedCres,
 wrongResultMime,...[...refusedFollowups.keys()].filter(mode=>mode!==secondDecisionPost)]);
const modes=['normal observation','late Playwright response','separate issuer process','late issuer response','direct Stripe EMVCo frame',
 'direct reviewed issuer origin','direct reviewed issuer passive image blocked','direct reviewed issuer passive font blocked',twoFonts,sixFonts,
 stylesheetBlock,parserScript,decisionScript,wrongScriptMime,secondDecisionPost,...cresCases,...rejectedAssets.keys(),unclaimedDecision,wrongDecisionMime];
for(const mode of modes)
test(rejectedAssets.has(mode)?mode+' before handoff':refusedFollowups.has(mode)||rejectedResponses.has(mode)?mode+' is blocked':mode===wrongScriptMime?mode:
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
 const dynamicForm=mode===dynamicDecisionCres;
 const externalForm=mode===externalFolded||mode===parserStylesheet;
 const hasDecisionStylesheet=mode===parserStylesheet||mode===scriptStylesheet;
 const scriptInitiatedStylesheet=mode===scriptStylesheet;
 const submitsCres=cresCases.has(mode);
 const isolated=submitsCres&&mode!==decisionCres||mode==='separate issuer process';
 const submitsSecondDecision=mode===secondDecisionPost||submitsCres;
 const rejectedAsset=rejectedAssets.get(mode);
 const scopedIssuer=mode==='direct reviewed issuer origin'||blocksPassiveAsset||loadsScript||loadsDecisionScript||submitsSecondDecision&&mode!==decisionCres||Boolean(rejectedAsset)||rejectedResponses.has(mode);
 const direct=mode==='direct Stripe EMVCo frame'||scopedIssuer;
 const issuerName=direct?'stripe-challenge-frame':'acsFrame';
 const approvedIssuerOrigin=scopedIssuer?'https://reviewed-test-issuer.stripe.com':undefined;
 const issuerOrigin=approvedIssuerOrigin??'https://hooks.stripe.com',issuer=issuerOrigin+'/fixture-issuer',decision=issuer+'/decision';
 const assetPath=issuerOrigin+(rejectedAsset?.path??(loadsScript?'/fixture-required.js':blocksPassiveStylesheet?'/fixture-passive.css':
  blocksPassiveFont?'/fixture-passive.woff2':'/fixture-passive.png'));
 const decisionAsset=issuerOrigin+'/fixture-decision.js';
 const decisionStylesheetPath='/fixture-decision.css',decisionStylesheet=issuerOrigin+decisionStylesheetPath;
 const followupOrigin=submitsCres&&mode!==wrongIssuerDestination?'https://hooks.stripe.com':issuerOrigin;
 const followup=mode===secondDecisionPost?decision:followupOrigin+(mode===rootHooksDestination?'/':'/fixture-followup');
 const observedFollowup=mode===changedCresAction?followup+'-different':followup;
 const fontCount=mode===sixFonts?6:mode===twoFonts?2:1;
 const assetUrls=[assetPath,...Array.from({length:fontCount-1},(_,index)=>issuerOrigin+`/fixture-passive-${index+2}.woff2`)];
 const isDecisionStylesheet=(url:string)=>hasDecisionStylesheet&&url===decisionStylesheet;
 const server='11111111-2222-4333-8444-555555555555',acs='11111111-2222-4333-8444-555555555554';
 const creq=Buffer.from(JSON.stringify({messageType:'CReq',messageVersion:'2.2.0',threeDSServerTransID:server,acsTransID:acs})).toString('base64url');
 const encodedCres=Buffer.from(JSON.stringify({
  messageType:'CRes',messageVersion:mode===wrongVersion?'2.1.0':'2.2.0',
  threeDSServerTransID:mode===wrongServerId?'11111111-2222-4333-8444-555555555553':server,
  acsTransID:mode===wrongAcsId?'11111111-2222-4333-8444-555555555553':acs,transStatus:mode===nonterminalStatus?'D':'Y',
 })).toString('base64url');
 const cres=externalForm?encodedCres.padEnd(Math.ceil(encodedCres.length/4)*4,'='):encodedCres;
 const cresWire=cres.replace(/=/g,'%3D');
 const domFixture=externalForm?syntheticIssuerDomForm(followup,{threeDSSessionData:'é + &\nline',cres}):undefined;
 const hidden=(name:string,value:string)=>`<input type="hidden" name="${name}" value="${value}">`;
 const followupFields=submitsCres?
  hidden('cres',cres)+(namedForm?'<input type="hidden" name="threeDSSessionData">':
   hidden('threeDSSessionData',mode===legacyEncoding?'é':'fixture&amp;session'))+
  (mode===unexpectedField?hidden('other','fixture'):mode===duplicateCres?hidden('cres',cres):mode===duplicateSession?hidden('threeDSSessionData','again'):''):
  hidden('decision','complete');
 const expectedSession=mode===legacyEncoding?'%E9':namedForm?'':mode===changedCresBody?'different':'fixture%26session';
 const extraField=mode===unexpectedField?'&other=fixture':mode===duplicateCres?'&cres='+cres:mode===duplicateSession?'&threeDSSessionData=again':'';
 const expectedWire=Buffer.from(externalForm?`threeDSSessionData=%C3%A9+%2B+%26%0D%0Aline&cres=${cresWire}`:
  dynamicForm?`cres=${cresWire}`:`cres=${cresWire}&threeDSSessionData=${expectedSession}${extraField}`);
 const followupScript=[
  mode===changedCresAction?'document.forms[0].action='+JSON.stringify(observedFollowup):'',
  mode===changedCresBody?'document.forms[0].elements.namedItem("threeDSSessionData").value="different"':'',
  mode===replacedIssuerDocument?'history.replaceState(null,"","/different-issuer-document")':'',
  'document.forms[0].submit()',
 ].filter(Boolean).join(';');
 const staticSubmit=scriptInitiatedStylesheet?`
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
 const dynamicSource=`
  const result=document.createElement('form');result.method='post';result.action=${JSON.stringify(followup)};
  const field=document.createElement('input');field.type='hidden';field.name='cres';field.value=${JSON.stringify(cres)};
  result.append(field);document.body.append(result);result.submit();
 `;
 const decisionHead=mode===parserStylesheet?`<head><link rel="stylesheet" href="${decisionStylesheetPath}" type="text/css"></head>`:'';
 const externalDocument=domFixture?`<!doctype html>${decisionHead}<body><section><script>void 0;</script>
  <script type="application/json" id="${domFixture.id}">${domFixture.folded}</script><script src="${decisionAsset}"></script></section></body>`:'';
 let decisionHtml=submitsSecondDecision?
  externalForm?externalDocument:
  dynamicForm?`<!doctype html><body><section><script>${dynamicSource}</script></section></body>`:
  `<!doctype html>${followupForm}<script>${staticSubmit}</script>`:
  loadsDecisionScript?`<!doctype html><script src="${decisionAsset}"></script>`:
  '<!doctype html><script>'+issuerDecision+'</script>';
 if(mode===legacyEncoding)decisionHtml=decisionHtml.replace('<!doctype html>','<!doctype html><meta charset=windows-1252>');
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
  [decision,{contentType:mode===wrongDecisionMime?'text/plain':mode===legacyEncoding?'text/html':'text/html; charset=utf-8',
   body:mode===legacyEncoding?Buffer.from(decisionHtml,'latin1'):decisionHtml}],
  [complete,json({complete:true})],
 ]);
 if(rejectedAsset)responses.set(assetPath,{contentType:rejectedAsset.contentType,body:rejectedAsset.body});
 if(loadsScript)responses.set(assetPath,{contentType:badMime?'text/html':'application/javascript',body:revealButton});
 if(loadsDecisionScript||externalForm)responses.set(decisionAsset,{contentType:'application/javascript',body:issuerDecision});
 if(submitsCres)responses.set(observedFollowup,{contentType:mode===wrongResultMime?'text/plain':'text/html',
  body:'<!doctype html><script>'+issuerDecision+'</script>'});
 const channel=process.env.PAYMENT_TEST_CHROME_CHANNEL==='chrome'?'chrome':'chromium';
 const networkUrl=externalForm?decisionAsset:decision;
 const networkFixture=domFixture?await serveIssuerResponse(new URL(networkUrl),gzipSync(domFixture.script),'gzip',true,true):
  compressed?await serveIssuerResponse(new URL(networkUrl),compressed.compress(decisionBytes),compressed.encoding,Boolean(compressed.chunked)):undefined;
 const browser=await chromium.launch({channel,headless:true,timeout:10_000,args:[
  '--site-per-process','--disable-background-networking','--proxy-server=direct://',
  '--host-resolver-rules='+(networkFixture?'MAP '+new URL(networkUrl).hostname+' 127.0.0.1:'+networkFixture.port+', ':'')+'MAP * ~NOTFOUND',
  ...(isolated?['--isolate-origins='+[stripe,issuerOrigin,...(submitsCres?[new URL(followup).origin]:[])].join(',')]:[]),
 ]}).catch(async error=>{await networkFixture?.close();throw error;});
 const stop=new AbortController();
 let attendance:Promise<boolean>|undefined;
 try{
  const context=await browser.newContext({serviceWorkers:'block',acceptDownloads:false,ignoreHTTPSErrors:Boolean(networkFixture)});
  const page=await context.newPage(),forwarded:string[]=[],refusals:string[]=[];
  const passiveRequests:Request[]=[],passiveFailures:Request[]=[],passiveResponses:Response[]=[];
  const passiveAborts:(string|undefined)[]=[],authorized:string[]=[];
  page.on('requestfailed',request=>{if(assetUrls.includes(request.url())||isDecisionStylesheet(request.url()))passiveFailures.push(request);});
  let lateHeadersObserved=false,issuerBodyReads=0;
  page.on('response',response=>{
   const url=response.url();
   if(assetUrls.includes(url)||isDecisionStylesheet(url))passiveResponses.push(response);
   if(url===issuer||url===decision||url===decisionAsset||url===observedFollowup||loadsScript&&url===assetPath){
    response.body=async()=>{
     issuerBodyReads++;
     assert.fail('the guard must leave issuer response bodies to Chromium');
    };
   }
   if(mode===unavailableDecisionHeaders&&url===decision){
    lateHeadersObserved=true;
    response.headerValue=()=>new Promise<never>(()=>{});
   }
  });
  const limits={deadline:Date.now()+15_000,signal:stop.signal};
  let assetErrorRedacted=false;
  let issuerFrame:Frame|undefined,attachedIssuerFrame=false,separateIssuerTarget=false,gestures=0;
  let observedDecision:Request|undefined,observedIssuer:Request|undefined;
  let replayRefusal:string|undefined;
  const guard=await guardChallengeRedirects(page,reason=>refusals.push(reason),{demo,backend},stop.signal,approvedIssuerOrigin,false);
  const challenge=initial3dsChallenge({page,limits,demo,owner:()=>({key,secret}),winning:async()=>{},check:async()=>{},
   expectIssuerAssetBlock:request=>guard.expectIssuerAssetBlock(request),
   authorizeIssuerScript:async(request,document)=>{
    await guard.authorizeIssuerScript(request,document);
    authorized.push(request.url());
   },
   authorizeIssuerDecision:async request=>{
    observedDecision=request;
    if(mode===unclaimedDecision)return;
    assert.ok(guard.authorizeIssuerDecision);
    await guard.authorizeIssuerDecision(request);
    authorized.push(request.url());
   },
   authorizeIssuerResult:async(request,document)=>{
    assert.equal(document,observedDecision);
    assert.equal(request.frame(),issuerFrame);
    assert.deepEqual(request.postDataBuffer(),expectedWire);
    await guard.authorizeIssuerResult(request,document);
    authorized.push(request.url());
    if(mode===cancelledCres){
     assert.equal(await attendance,true);
     stop.abort();
    }
   },
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
   try{
    if(challenge.owns(new URL(url))){
     if(mode==='late Playwright response'&&(url===retrieve||url===authenticate))request.existingResponse=()=>null;
     if((mode==='late issuer response'||mode===lateDecisionCres)&&url===decision)request.existingResponse=()=>null;
     const actual=Object.create(route) as Route;
     if(mode===mismatchedDecision&&url===decision)request.existingResponse=()=>observedIssuer!.existingResponse();
     actual.continue=async()=>{
      assert.ok(response,'passive asset must never reach continuation');
      const reply=url===retrieve&&forwarded.includes(complete)?json({status:'succeeded'}):response;
      if(mode===repeatedCres&&url===observedFollowup&&!replayRefusal){
       // Retry the original paused request while its committed issuer document is still current.
       await assert.rejects(challenge.forward(actual,new URL(url),code=>{replayRefusal=code;}),{message:'billing_BROWSER'});
      }
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
      observedIssuer=request;
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
  if(refusedFollowups.has(mode)||rejectedResponses.has(mode)){
   const checkpoint=refusedFollowups.get(mode)??rejectedResponses.get(mode);
   const refusedDecision=mode===unclaimedDecision||mode===wrongDecisionMime;
   const blockedUrl=refusedDecision?decision:observedFollowup;
   const failed=page.waitForEvent('requestfailed',{predicate:value=>value.url()===blockedUrl,timeout:8_000});
   attendance=challenge.attend().then(()=>true,()=>false);
   const [request,attended]=await Promise.all([failed,attendance]);
   assert.equal(attended,!refusedDecision&&mode!==mismatchedDecision);
   assert.equal(request.frame(),issuerFrame);
   assert.equal(request.method(),'POST');
   assert.equal(request.isNavigationRequest(),true);
   assert.equal(request.existingResponse(),null);
   if(submitsCres)assert.deepEqual(request.postDataBuffer(),expectedWire);
   assert.equal(refusals[0],checkpoint);
   assert.deepEqual(forwarded,[retrieve,authenticate,issuer,decision,...(mode===wrongResultMime?[observedFollowup]:[])]);
   const expectedAuthorized=mode===unclaimedDecision?forwarded.slice(0,-1):mode===cancelledCres?[...forwarded,observedFollowup]:forwarded;
   assert.deepEqual(authorized,expectedAuthorized);
   assert.equal(issuerBodyReads,0);
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
  if(mode===unavailableDecisionHeaders)assert.equal(lateHeadersObserved,true);
  if(mode===repeatedCres)assert.equal(replayRefusal,'BROWSER_CHALLENGE_CRES_REPLAY');
  if(networkFixture){
   assert.equal(networkFixture.requests(),1);
   assert.equal(networkFixture.unexpectedRequests(),0);
  }
  assert.equal(issuerBodyReads,0);
  if(isolated&&submitsCres){
   assert.ok(issuerFrame);
   const target=await context.newCDPSession(issuerFrame);
   try{
    const {targetInfo}=await target.send('Target.getTargetInfo');
    assert.equal(targetInfo.type,'iframe');
    assert.equal(targetInfo.url,observedFollowup);
   }finally{await target.detach();}
  }
  assert.deepEqual(refusals,[]);
  assert.deepEqual(forwarded,[retrieve,authenticate,issuer,...(loadsScript?[assetPath]:[]),decision,
   ...(loadsDecisionScript||externalForm?[decisionAsset]:[]),...(submitsCres?[observedFollowup]:[]),complete,retrieve]);
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
  try{await browser.close();}finally{await networkFixture?.close();}
  if(blocksPassiveAsset||rejectedAsset||badMime){
   assert.equal(browser.isConnected(),false);
   if(attendance)assert.equal(await attendance,false);
  }
 }
});
