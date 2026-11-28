import test from 'node:test';
import assert from 'node:assert/strict';
import {chromium} from '@playwright/test';
// @ts-ignore Native Node executes the same helper as Playwright.
import {attendContractChallenge,contractChallengeOrigin} from './stripe-billing-contract-challenge.ts';

test('the challenge origin boundary admits only verified HTTPS provider destinations',()=>{
 const approved='https://synthetic-issuer.stripe.com';
 for(const origin of [
  'https://api.stripe.com','https://hooks.stripe.com','https://js.stripe.com','https://child.js.stripe.com',
  'https://r.stripe.com','https://merchant-ui-api.stripe.com','https://m.stripe.com','https://m.stripe.network',
  'https://pay.stripe.com','https://payments.stripe.com',approved,
 ])assert.equal(contractChallengeOrigin(new URL(origin+'/document?owned=1'),approved),true,origin);
 for(const url of [
  'http://hooks.stripe.com/','https://hooks.stripe.com:444/','https://hooks.stripe.com./',
  'https://hooks.stripe.com/#','https://u@hooks.stripe.com/','https://hooks.stripe.com.bad.test/',
  'https://bad.test/','https://dashboard.stripe.com/','https://unapproved-issuer.stripe.com/',
  'https://bad..js.stripe.com/','https://a_b.js.stripe.com/','https://stripe.network/',
 ])assert.equal(contractChallengeOrigin(new URL(url),approved),false,url);
 for(const pin of ['https://bad.test','https://bad.stripe.com/','https://bad.stripe.com:443','https://m.stripe.com']){
  assert.equal(contractChallengeOrigin(new URL('https://bad.test/'),pin),false);
 }
 assert.equal(contractChallengeOrigin(new URL(approved)),false);
});

test('offline Chrome drives the challenge button once regardless of issuer markup and rejects replaced or foreign controls',
 {timeout:45_000},async t=>{
 const app='http://127.0.0.1:18081/',outer='https://child.js.stripe.com/outer';
 const frameUrl='https://hooks.stripe.com/decision',foreign='https://foreign.test/decision';
 const channel=process.env.PAYMENT_TEST_CHROME_CHANNEL==='chrome'?'chrome':'chromium';
 const browser=await chromium.launch({channel,headless:true,timeout:10_000,args:[
  '--site-per-process','--disable-background-networking','--proxy-server=direct://','--host-resolver-rules=MAP * ~NOTFOUND',
 ]});
 t.after(()=>browser.close());
 const button='<script>const control=document.createElement("section");control.setAttribute("role","button");'+
  'control.textContent="Complete";control.onclick=e=>{document.body.dataset.clicked=e.isTrusted?"yes":"synthetic";control.remove()};'+
  'document.body.append(control)</script>';
 const html='<!doctype html><link rel="icon" href="data:,"><body>';
 for(const scenario of ['dynamic','replaced','new document','foreign','ambiguous','revoked'] as const)await t.test(scenario,async()=>{
  const target=scenario==='foreign'?foreign:frameUrl;
  const context=await browser.newContext({serviceWorkers:'block',acceptDownloads:false});
  try{
   const page=await context.newPage();
   const wire:string[]=[];
   await context.route('**/*',async route=>{
    const url=route.request().url();wire.push(url);
    if(![app,outer,target].includes(url)){await route.abort('blockedbyclient');return;}
    const body=url===app?'<iframe src="'+outer+'"></iframe>':
     url===outer?'<iframe src="'+target+'"></iframe>'+(scenario==='ambiguous'?'<iframe src="'+target+'"></iframe>':''):button;
    await route.fulfill({contentType:'text/html',body:html+body});
   });
   await page.goto(app,{timeout:6_000});
   const frame=page.frames().find(item=>item.url()===target)!;
   assert.ok(frame);
   await frame.getByRole('button',{name:'Complete',exact:true}).waitFor({timeout:4_000});
   let handoffs=0,requestsAtHandoff:number|undefined,revoked=false;
   const operation=attendContractChallenge(page,{deadline:Date.now()+(scenario==='foreign'?450:5_000)},
    contractChallengeOrigin,async()=>{if(revoked)throw Error('Synthetic revocation');},async limits=>{
     assert.ok(!limits.signal.aborted);
     handoffs++;requestsAtHandoff=wire.length;
     if(scenario==='replaced')await frame.evaluate(()=>document.querySelector('[role=button]')!.replaceWith(document.querySelector('[role=button]')!.cloneNode(true)));
     if(scenario==='new document')await frame.goto(frameUrl,{timeout:4_000});
     if(scenario==='revoked')revoked=true;
    });
   if(scenario==='dynamic'){
    await operation;
    assert.equal(await frame.locator('body').getAttribute('data-clicked'),'yes');
    assert.equal(wire.length,requestsAtHandoff);
   }else{
    await assert.rejects(operation,{message:'billing_BROWSER'});
    assert.equal(await frame.locator('body').getAttribute('data-clicked'),null);
   }
   assert.equal(handoffs,['foreign','ambiguous'].includes(scenario)?0:1);
  }finally{await context.close();}
 });
});
