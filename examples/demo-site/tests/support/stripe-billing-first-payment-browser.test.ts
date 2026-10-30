import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, readFile, readdir, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {build} from 'vite';
import {chromium} from '@playwright/test';
import {createOwnedBillingFirstPayment} from '../../../../infrastructure/stripe-billing-first-payment.mjs';
// @ts-ignore Private runtime JavaScript is exercised through injected transport.
import {createBillingFirstPaymentRuntime} from '../../../../infrastructure/stripe-billing-first-payment-runtime.mjs';
// @ts-ignore Explicit source for hosted Node runner.
import {runFirstPaymentBrowser} from './stripe-billing-first-payment.ts';

// HOSTED ONLY: actual bundle + Chromium; every request is fulfilled or denied.
// Provider transport is inert, financial settlement here is synthetic. The companion
// Java integration exercises Handler -> real scenario/stores with inert official SDK.
test('dedicated deployed page preserves real SDK OTP authority through one ready original confirmation', async () => {
  const root = fileURLToPath(new URL('../..', import.meta.url));
  const directory = await mkdtemp(join(tmpdir(), 'first-payment-browser-'));
  let browser: Awaited<ReturnType<typeof chromium.launch>> | undefined;
  try {
    const nginx = await readFile(join(root, 'nginx.conf'), 'utf8');
    const csp = /add_header Content-Security-Policy "([^"]+)"/.exec(nginx)?.[1];
    assert.ok(csp);
    await build({root, mode:'billing-first-payment', envFile:false, logLevel:'silent', plugins:[{
      name:'inert-first-payment-stripe', enforce:'pre',
      resolveId(id) {
        if (id === '../providers/stripe-loader.js' || id === './stripe-loader.js' || /\/providers\/stripe-loader\.(?:ts|js)$/.test(id)) return '\0first-payment-loader';
      },
      load(id) {
        if (id === '\0first-payment-loader') return `export async function loadStripeClient() { return {
          elements: () => ({submit:async()=>({}), create:()=>{
            const listeners=new Map();let frame;return {
              on(name,callback){listeners.set(name,callback)},off(name){listeners.delete(name)},
              mount(host){
                if(!host.isConnected||!host.getClientRects()[0]?.height||!document.querySelector('#pay').disabled)throw Error('mount gate');
                frame=document.createElement('iframe');frame.title='Secure payment input frame';frame.src='/synthetic-card';
                frame.onload=()=>{if(!document.querySelector('#pay').disabled)throw Error('ready gate');listeners.get('ready')?.()};host.append(frame);
              },destroy(){frame?.remove()}
            }
          }}),
          createConfirmationToken:async()=>{
            const doc=document.querySelector('#provider iframe').contentDocument;
            if(doc.querySelector('[name=number]').value!=='4242424242424242'||doc.querySelector('[name=expiry]').value!=='1230'||doc.querySelector('[name=cvc]').value!=='123')throw Error('card boundary');
            return {confirmationToken:{id:'ctoken_fixture'}}
          },
          handleNextAction:async()=>{window.__unexpectedAction=true;throw Error('unexpected next action')},
          confirmPayment:async()=>{throw Error('second implementation')}
        }};`;
      },
    }], build:{outDir:directory, emptyOutDir:true}});
    const assets = new Map<string, string>();
    async function collect(path: string, prefix = ''): Promise<void> {
      for (const entry of await readdir(path, {withFileTypes:true})) {
        const name = prefix + '/' + entry.name;
        if (entry.isDirectory()) await collect(join(path, entry.name), name);
        else assets.set(name, await readFile(join(path, entry.name), 'utf8'));
      }
    }
    await collect(directory);
    assert.ok(assets.has('/billing-first-payment.html'));
    assert.equal(assets.get('/health'), 'ok\n');
    assert.equal(assets.has('/index.html'), false);
    browser = await chromium.launch({headless:true,args:['--disable-background-networking','--disable-component-update','--no-first-run']});
    for (const outcome of ['OBSERVE','REQUIRES_ACTION','PAGEHIDE'] as const) {
      const id = (n: number) => '11111111-2222-4333-8444-' + String(n).padStart(12,'0');
      const origin = 'http://127.0.0.1:14173';
      const backend = 'http://127.0.0.1:18080';
      const token = 'T'.repeat(43);
      const control = 'X'.repeat(43);
      const expires = new Date(Date.now() + 120000).toISOString();
      const actionExpires = new Date(Date.now() + 110000).toISOString();
      const attemptExpires = new Date(Date.now() + 100000).toISOString();
      const identity = {code:'OK',run_id:id(1),account_id:id(2),site_id:id(3),customer_id:id(4),price_id:id(5),subscription_id:id(7),operation_id:id(8)};
      const prepared = {...identity,phase:'PREPARED',action:{kind:'PROVIDER_CLIENT',secret:'bga1_' + 'G'.repeat(43),expires_at:actionExpires},session:{protocol:'SERVER_CONFIRM_V2',provider:'stripe',flow:'PAYMENT',publishable_key:'pk_test_fixture',client_secret:'pi_fixture_secret_fixture',action_token:null,attempt_id:id(9),capability:'bca2_' + 'C'.repeat(43),expires_at:attemptExpires}};
      const options = {env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_FIRST_PAYMENT:'true'},runId:id(1),expectedSource:'e'.repeat(40),daemon:'unix:///Users/test/docker.sock',ports:{backend:18080,demo:14173,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},controlPort:18083,frozenTime:1790000000,approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},selectedCase:'initial_success'};
      const binding = {runId:id(1),expectedSource:'e'.repeat(40),accountId:id(2),siteId:id(3),customerId:id(4),priceId:id(5),productId:id(6),siteKey:'first-payment-fixture',email:'fixture@example.test',endpoints:{demo:origin,backend,receiver:'http://127.0.0.1:18082'}};
      let denied = 0;
      let leaked = false;
      let sinksClean = true;
      let acknowledged = false;
      let confirmKey = '';
      const privatePaths: string[] = [];
      const dispatches: string[] = [];
      const bootstrap = {resources:()=>({cliDirectory:'/synthetic'}),prepare:async()=>true,start:async()=>true,stop:async()=>true,withFirstPaymentRuntime:async(callback:any)=>callback({endpoint:'http://127.0.0.1:18083',capability:control})};
      const owner = await createOwnedBillingFirstPayment(options, {
        preflight:async()=>true,createBootstrap:async()=>bootstrap,
        createCli:async()=>({validateBinding:async()=>true,startListener:async()=>true,stopListener:async()=>true,assertBillingListener:async()=>true}),login:async()=>true,
        createFixture:async()=>({resources:()=>binding,provision:async()=>true,close:async()=>true,withOtpCode:async(callback:any)=>callback('123456'),acknowledgeCustomer:async(value:any)=>{assert.deepEqual(value,{siteId:id(3),customerId:id(4)});acknowledged=true;return true;},beforeDispatch:async(kind:string,key:string)=>{dispatches.push(kind);if(kind==='confirm')confirmKey=key;return true;},acknowledge:async()=>true}),
        createRuntime:(args:any)=>createBillingFirstPaymentRuntime(args,{fetch:async(url:string,init:any)=>{
          const path = new URL(url).pathname;
          privatePaths.push(path);
          assert.equal(init.headers.Authorization,'Bearer '+control);
          let response: unknown;
          if (path.endsWith('/status')) response = {phase:'NEW',code:'OK',run_id:id(1)};
          else if (path.endsWith('/prepare')) {
            assert.equal(acknowledged,true);
            assert.equal(JSON.parse(init.body).customer_token,token);
            response = prepared;
          } else if (path.endsWith('/confirm')) {
            const value = JSON.parse(init.body);
            assert.deepEqual(value,{attempt_id:id(9),capability:prepared.session.capability,confirmation_token:'ctoken_fixture',idempotency_key:confirmKey});
            assert.match(confirmKey,/^[0-9a-f-]{36}$/);
            response = {...identity,phase:'OBSERVE',protocol:'SERVER_CONFIRM_V2',attempt_id:id(9),state:outcome,retry_allowed:false,successor_attempt_id:null,next_action_allowed:outcome!=='OBSERVE',expires_at:attemptExpires};
          } else {
            response = {...identity,phase:'SETTLED_LOCAL',period_start:new Date(1790000000000).toISOString(),paid_through:new Date(1792592000000).toISOString(),access_evaluated_at:new Date(1790000000000).toISOString(),access_valid_until:new Date(1790000300000).toISOString()};
          }
          return new Response(JSON.stringify(response));
        }}),
        prove:async()=>({ownedVerified:true,cashVerified:true,journalVerified:true,signedCallbacksVerified:true}),
      });
      const wrappedBrowser = {newContext:async(value:any)=>{
        const context = await browser!.newContext(value);
        await context.addInitScript(() => {
          if (window.top === window && location.origin !== 'null') (window as any).__storageAudit = {session:window.sessionStorage,local:window.localStorage};
        });
        if (outcome === 'PAGEHIDE') await context.addInitScript(() => {
          const observer = new MutationObserver(() => {
            if (document.body?.dataset.phase === 'READY') {observer.disconnect();window.dispatchEvent(new Event('pagehide'));}
          });
          observer.observe(document, {subtree:true,childList:true,attributes:true});
        });
        await context.routeWebSocket('**/*',socket=>{denied++;socket.close();});
        await context.route('**/*', async route => {
          const request = route.request();
          const url = new URL(request.url());
          const headers = {'Content-Security-Policy':csp!,'Referrer-Policy':'no-referrer','Access-Control-Allow-Origin':origin};
          if (url.origin === origin && assets.has(url.pathname)) {
            const contentType = url.pathname.endsWith('.js') ? 'text/javascript' : url.pathname.endsWith('.css') ? 'text/css' : 'text/html';
            return route.fulfill({contentType,headers,body:assets.get(url.pathname)!});
          }
          if (url.origin === origin && url.pathname === '/synthetic-card') return route.fulfill({contentType:'text/html',headers:{'Referrer-Policy':'no-referrer'},body:'<!doctype html><input name="number"><input name="expiry"><input name="cvc"><input name="postalCode">'});
          if (url.origin === backend && url.pathname === '/api/v1/public/customer-auth/email-otp/challenges') return route.fulfill({status:202,headers,json:{challenge_id:id(10),expires_at:expires}});
          if (url.origin === backend && url.pathname === '/api/v1/public/customer-auth/email-otp/challenges/' + id(10) + '/verify') return route.fulfill({headers,json:{session_token:token,expires_at:expires,site_id:id(3),scopes:['SUBSCRIPTION_READ','SUBSCRIPTION_MANAGE'],customer:{id:id(4),status:'ACTIVE',masked_email:null}}});
          denied++;
          return route.abort('blockedbyclient');
        });
        const actualClose = context.close.bind(context);
        context.close = async (...args) => {
          for (const page of context.pages()) {
            if (page.url().startsWith(origin)) {
              // Only synthetic DOM leaves this hosted fixture; the private control
              // sentinel is never supplied to the page, including during this audit.
              const snapshot = await page.evaluate(() => {
                const audit = (window as any).__storageAudit;
                return {
                  content:document.documentElement.outerHTML + location.href,
                  storageEmpty:audit?.session.length === 0 && audit?.local.length === 0,
                  nextAction:(window as any).__unexpectedAction === true,
                };
              }).catch(() => null);
              sinksClean &&= snapshot !== null && snapshot.storageEmpty && !snapshot.nextAction &&
                ![token,control,prepared.action.secret,prepared.session.client_secret,prepared.session.capability].some(value => snapshot.content.includes(value));
            }
          }
          return actualClose(...args);
        };
        context.on('page', page => {
          page.on('console', message=>{if([token,control,prepared.action.secret,prepared.session.client_secret,prepared.session.capability].some(secret=>message.text().includes(secret)))leaked=true;});
          page.on('pageerror', error=>{if([token,control,prepared.action.secret,prepared.session.client_secret,prepared.session.capability].some(secret=>error.message.includes(secret)))leaked=true;});
        });
        return context;
      }} as any;
      const run = owner.run((port, limits)=>runFirstPaymentBrowser(wrappedBrowser,port,limits), {deadline:Date.now()+15000});
      if (outcome === 'OBSERVE') assert.equal((await run).initialAccepted,true);
      else await assert.rejects(run);
      assert.deepEqual(dispatches,outcome === 'PAGEHIDE' ? ['create'] : ['create','confirm']);
      assert.deepEqual(privatePaths,['/initial-payment/status','/initial-payment/prepare',...(outcome === 'PAGEHIDE' ? [] : ['/initial-payment/confirm']),...(outcome === 'OBSERVE' ? ['/initial-payment/observe'] : [])]);
      assert.equal(denied,0);
      assert.equal(leaked,false);
      assert.equal(sinksClean,true);
      assert.equal(owner.resources().full5bAccepted,false);
    }
  } finally {
    await browser?.close();
    await rm(directory,{recursive:true,force:true});
  }
});
