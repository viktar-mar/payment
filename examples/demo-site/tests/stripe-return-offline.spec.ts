import {test,expect,type Page} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {resolve} from 'node:path';
import {randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';

let origin:string;
const container=`task15-offline-${process.pid}-${randomUUID().slice(0,8)}`;
let started=false;
let intercepted:string[]=[];
const checkout='11111111-1111-4111-8111-111111111111',payment='22222222-2222-4222-8222-222222222222';
const expires=new Date(Date.now()+3600000).toISOString();
const token='SYNTHETIC_TEST_TOKEN_'+'x'.repeat(22);
function wire(status='REQUIRES_PAYMENT_METHOD',provider='STRIPE') {
  return {checkout_id:checkout,checkout_token:token,payment_id:payment,amount_minor:4999,currency:'EUR',
    price:{price_id:'33333333-3333-4333-8333-333333333333',price_key:'lifetime',version:1,amount_minor:4999,currency:'EUR',tax_mode:'NONE'},
    provider_type:provider,confirmation_mode:provider==='STRIPE'?'CLIENT_PROVIDER':'SERVER',provider_client_payload:{},status,expires_at:expires};
}
function read(status='REQUIRES_PAYMENT_METHOD',provider='STRIPE') {const {checkout_token:_,...value}=wire(status,provider);return value;}
test.beforeAll(async({request})=>{
  execFileSync('docker',['run','--rm','-d','--name',container,'-p','127.0.0.1::8080',
    '-v',`${resolve('dist')}:/usr/share/nginx/html:ro`,'-v',`${resolve('nginx.conf')}:/etc/nginx/conf.d/default.conf:ro`,
    'nginxinc/nginx-unprivileged:1.29.5-alpine3.23'],{stdio:'pipe'});started=true;
  const port=execFileSync('docker',['port',container,'8080/tcp'],{encoding:'utf8'}).trim().split(':').at(-1);
  origin=`http://127.0.0.1:${port}`;
  await expect.poll(async()=>{try{return(await request.get(`${origin}/healthz`)).status();}catch{return 0;}},{timeout:15000}).toBe(200);
});
test.afterAll(()=>{if(started)execFileSync('docker',['stop',container],{stdio:'pipe'});});
test.beforeEach(async({page})=>{
  intercepted=[];
  // Every non-owned request is intercepted: no real Stripe interoperability is claimed.
  await page.route(url=>url.origin!==origin,route=>{intercepted.push(new URL(route.request().url()).host);return route.fulfill({status:200,contentType:'text/javascript',body:'window.__offlineStripeLoaded=true;'});});
});
async function demo(page:Page) {await page.goto(`${origin}/?api_base_url=${encodeURIComponent(origin)}&site_key=test-site&price_key=lifetime&locale=en`);}
test('built return entry cleans before boot and actual nginx CSP permits only the approved scripts/frames',async({page})=>{
  const referers:string[]=[];const api:string[]=[];
  const failures:string[]=[];page.on('requestfailed',r=>failures.push(`${new URL(r.url()).host}:${r.failure()?.errorText}`));
  page.on('request',request=>{if(request.resourceType()!=='document')referers.push(request.headers()['referer']??'');if(request.url().includes('/api/'))api.push(request.url());});
  const response=await page.goto(`${origin}/payment-return?payment_intent=pi_demo&payment_intent_client_secret=generated-sentinel&redirect_status=succeeded#fragment`);
  expect(response?.headers()['referrer-policy']).toBe('no-referrer');
  await expect(page).toHaveURL(`${origin}/payment-return`);
  await expect(page.locator('#return-status')).toContainText('No saved checkout session');
  expect(api).toHaveLength(0);expect(referers.every(value=>value==='')).toBe(true);
  const results=await page.evaluate(async()=>{
    const violations:string[]=[];document.addEventListener('securitypolicyviolation',e=>violations.push(`${e.violatedDirective}:${e.blockedURI}`));
    const load=(src:string)=>new Promise<boolean>(resolve=>{const script=document.createElement('script');script.src=src;script.onload=()=>resolve(true);script.onerror=()=>resolve(false);document.head.append(script);});
    const approved=await load('https://js.stripe.com/v3/');const subdomain=await load('https://sandbox.js.stripe.com/test.js');
    const unrelated=await load('https://unrelated.invalid/bad.js');
    const inline=document.createElement('script');inline.textContent='window.__unrelatedInline=true';document.head.append(inline);
    const connections=await Promise.all(['https://api.stripe.com/offline','https://unrelated.invalid/data'].map(async url=>{try{await fetch(url,{mode:'no-cors'});return true;}catch{return false;}}));
    await new Promise(resolve=>setTimeout(resolve,25));
    return {approved,subdomain,unrelated,connections,inline:!!(window as unknown as Record<string,unknown>).__unrelatedInline,violations};
  });
  expect(results.subdomain,JSON.stringify({results,intercepted,failures})).toBe(true);
  expect(intercepted).toEqual(expect.arrayContaining(['js.stripe.com','sandbox.js.stripe.com','api.stripe.com']));
  expect(results).toMatchObject({approved:true,subdomain:true,unrelated:false,inline:false,connections:[true,false]});
  expect(results.violations).toContain('script-src-elem:inline');
  const csp=response!.headers()['content-security-policy']!;
  expect(csp).not.toMatch(/unsafe-inline|unsafe-eval|connect-src[^;]* https:(?:\s|;)/);
  for(const source of ['https://js.stripe.com','https://sub.js.stripe.com','https://hooks.stripe.com']) {
    await page.route(`${source}/frame`,route=>route.fulfill({contentType:'text/html',body:'<p>Offline frame loaded</p>'}));
    await page.evaluate(src=>{const f=document.createElement('iframe');f.src=src;document.body.append(f);},`${source}/frame`);
    await expect(page.frameLocator(`iframe[src="${source}/frame"]`).getByText('Offline frame loaded')).toBeVisible();
  }
  const blockedFrame=await page.evaluate(()=>new Promise<boolean>(resolve=>{
    document.addEventListener('securitypolicyviolation',e=>{if(e.violatedDirective==='frame-src'&&e.blockedURI.startsWith('https://unrelated.invalid'))resolve(true);});
    const f=document.createElement('iframe');f.src='https://unrelated.invalid/frame';document.body.append(f);
  }));expect(blockedFrame).toBe(true);
  for(const kind of ['widget','customer','donations','subscriptions']) {
    const module=await import(pathToFileURL(resolve(`../../frontend/sdk/dist/${kind}/styles.js`)).href);
    const style=Object.values(module)[0] as string;
    expect(await page.evaluate(css=>{const host=document.createElement('div');document.body.append(host);const shadow=host.attachShadow({mode:'open'});
      const style=document.createElement('style');style.textContent=css;shadow.append(style);return style.sheet!==null;},style)).toBe(true);
  }
  const logs=execFileSync('docker',['logs',container],{encoding:'utf8',stdio:['ignore','pipe','pipe']});
  expect(logs).not.toContain('generated-sentinel');
});
test('stale saved success and failed storage write never identify a Donation return or cause automatic network',async({page})=>{
  const requests:string[]=[];
  await page.addInitScript(({origin,checkout,payment,token,expires})=>{
    sessionStorage.setItem('demo:public-return-configuration:v1',JSON.stringify({apiBaseUrl:origin,siteKey:'test-site',priceKey:'lifetime',locale:'en'}));
    sessionStorage.setItem(`generic-payments:v1:${encodeURIComponent(origin)}:test-site:lifetime`,JSON.stringify({version:2,checkoutId:checkout,checkoutToken:token,
      expiresAt:expires,apiBaseUrl:origin,siteKey:'test-site',priceKey:'lifetime',providerType:'STRIPE',confirmationMode:'CLIENT_PROVIDER',
      state:{checkoutId:checkout,paymentId:payment,amountMinor:4999,currency:'EUR',price:{priceId:'33333333-3333-4333-8333-333333333333',priceKey:'lifetime',version:1,amountMinor:4999,currency:'EUR',taxMode:'NONE'},status:'SUCCEEDED',expiresAt:expires}}));
    Storage.prototype.setItem=()=>{throw new DOMException('Storage unavailable','QuotaExceededError');};
  },{origin,checkout,payment,token,expires});
  await page.route('**/api/v1/**',route=>{requests.push(route.request().method());return route.fulfill({json:read('SUCCEEDED')});});
  await page.goto(`${origin}/payment-return?redirect_status=succeeded&payment_intent_client_secret=donation-sentinel`);
  await expect(page.getByRole('button',{name:'Check previously saved catalog payment'})).toBeVisible();
  expect(requests).toHaveLength(0);await expect(page.locator('#return-status')).not.toContainText('SUCCEEDED');
  await page.getByRole('button',{name:'Check previously saved catalog payment'}).click();
  await expect(page.locator('#return-status')).toContainText(`Saved catalog payment ${payment} · SUCCEEDED`);
  expect(requests).toEqual(['GET']);
});
test('guest Stripe uses same-created widget recovery and never Fake confirm or second create',async({page})=>{
  const calls:string[]=[];
  await page.route('**/api/v1/**',route=>{const request=route.request();calls.push(`${request.method()} ${new URL(request.url()).pathname}`);
    return route.fulfill({status:request.method()==='POST'?201:200,json:request.method()==='POST'?wire():read()});});
  await demo(page);await page.locator('#guest-pay').click();
  await expect(page.locator('#customer-checkout-host').getByRole('button',{name:'Resume existing payment'})).toBeVisible();
  expect(calls.filter(value=>value.startsWith('POST'))).toEqual(['POST /api/v1/public/sites/test-site/checkouts']);
  expect(calls.filter(value=>value.endsWith(`/checkouts/${checkout}`))).toHaveLength(1);
});
test('same-created Stripe storage failure shows safe fallback without a second creation',async({page})=>{
  await page.addInitScript(()=>{Storage.prototype.setItem=()=>{throw new DOMException('Unavailable','QuotaExceededError');};});
  const calls:string[]=[];await page.route('**/api/v1/**',route=>{calls.push(route.request().method());return route.fulfill({status:201,json:wire()});});
  await demo(page);await page.locator('#guest-pay').click();
  await expect(page.locator('#customer-action-status')).toContainText('Existing payment session is unavailable');
  expect(calls).toEqual(['POST']);await expect(page.locator('#customer-checkout-host generic-payment-widget')).toHaveCount(0);
});
test('Fake direct guest confirmation is preserved',async({page})=>{
  const calls:string[]=[];await page.route('**/api/v1/**',route=>{const path=new URL(route.request().url()).pathname;calls.push(path);
    return route.fulfill({status:path.endsWith('/confirm')?200:201,json:path.endsWith('/confirm')?read('SUCCEEDED','FAKE'):wire('REQUIRES_PAYMENT_METHOD','FAKE')});});
  await demo(page);await page.locator('#guest-pay').click();await expect(page.locator('#customer-action-status')).toContainText('SUCCEEDED');
  expect(calls).toEqual(['/api/v1/public/sites/test-site/checkouts',`/api/v1/public/checkouts/${checkout}/confirm`]);
});
async function customer(page:Page) {
  await page.addInitScript(({origin,expires})=>{
    sessionStorage.setItem(`generic-payments:customer:v1:${encodeURIComponent(origin)}:test-site:${encodeURIComponent(origin)}`,
      JSON.stringify({version:1,sessionToken:'SYNTHETIC_CUSTOMER_'+'x'.repeat(24),apiBaseUrl:origin,siteKey:'test-site',origin,
        customer:{id:'44444444-4444-4444-8444-444444444444',status:'ACTIVE',maskedEmail:null},expiresAt:expires,scopes:['CUSTOMER_READ','CHECKOUT_CREATE']}));
  },{origin,expires});
}
function me(){return {id:'44444444-4444-4444-8444-444444444444',status:'ACTIVE',masked_email:null,authentication_modes:['SITE_TOKEN'],session_expires_at:expires};}
test('Customer Stripe uses the same created checkout and logout removes its recovery host',async({page})=>{
  await customer(page);const posts:string[]=[];
  await page.route('**/api/v1/**',route=>{const request=route.request();const path=new URL(request.url()).pathname;
    if(path==='/api/v1/customer/me')return route.fulfill({json:me()});
    if(request.method()==='DELETE')return route.fulfill({status:204});
    if(request.method()==='POST'){posts.push(path);return route.fulfill({status:201,json:wire()});}
    return route.fulfill({json:read()});
  });
  await demo(page);await expect(page.locator('#customer-pay')).toBeEnabled();await page.locator('#customer-pay').click();
  await expect(page.locator('#customer-checkout-host').getByRole('button',{name:'Resume existing payment'})).toBeVisible();
  expect(posts).toEqual(['/api/v1/public/sites/test-site/checkouts']);
  await page.locator('#customer-logout').click();await expect(page.locator('#customer-checkout-host')).toBeEmpty();
});
test('stale Customer creation response cannot mount a checkout after authority changes',async({page})=>{
  await customer(page);let release:()=>void=()=>{};let entered=false;
  const held=new Promise<void>(resolve=>{release=resolve;});let creations=0;
  await page.route('**/api/v1/**',async route=>{const request=route.request();const path=new URL(request.url()).pathname;
    if(path==='/api/v1/customer/me')return route.fulfill({json:me()});
    if(request.method()==='POST'){creations++;entered=true;await held;return route.fulfill({status:201,json:wire()});}
    return route.fulfill({json:read()});
  });
  await demo(page);await expect(page.locator('#customer-pay')).toBeEnabled();await page.locator('#customer-pay').click();
  await expect.poll(()=>entered).toBe(true);
  await page.evaluate(()=>{sessionStorage.clear();document.querySelector('#customer-login')?.dispatchEvent(new Event('customer-authenticated'));});
  release();await expect(page.locator('#customer-summary')).toContainText('Not signed in');
  await expect(page.locator('#customer-checkout-host')).toBeEmpty();expect(creations).toBe(1);
});
