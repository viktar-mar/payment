import { expect, test } from '@playwright/test';
import { customerFixture, demoPath, signIn } from './support/customer-api';
import type { AdminApi, ProvisionedCheckout } from './support/admin-api';
import { anonymousDonorMessage, customerDonorMessage } from './support/donation-fixtures';
import { enterPrivateText } from './support/private-entry';

async function campaign(api: AdminApi, fixture: ProvisionedCheckout, policy: Record<string,unknown> = {}) {
  const created = await api.customerRequest<{id:string;version:number}>('POST', `/api/v1/admin/sites/${fixture.siteId}/donation-campaigns`, {
    key:'help-campaign', display_name:'Community <b>support</b>', purpose_description:'Literal <b>text</b>', currency:'EUR', suggested_amounts:[4999,1000], minimum_amount:100, maximum_amount:10000, custom_amount_allowed:true, anonymous_allowed:true, ...policy,
  });
  await api.customerRequest('POST',`/api/v1/admin/sites/${fixture.siteId}/donation-campaigns/${created.id}/status`,{status:'ACTIVE',expected_version:created.version});
}
const path = (api:AdminApi, fixture:ProvisionedCheckout) => demoPath(api,fixture)+'&campaign_key=help-campaign';
const sinks = new WeakMap<import('@playwright/test').Page,{unsafeConsole:boolean;unsafeUrl:boolean;ambientAuth:boolean}>();
async function privacy(page: import('@playwright/test').Page, message: string) {
  const safe = await page.evaluate(message => {
    const storage=JSON.stringify({local:{...localStorage},session:{...sessionStorage}});
    const component=document.querySelector('generic-donation')!;
    const dom=document.documentElement.outerHTML+(component.shadowRoot?.innerHTML??'');
    const events=JSON.stringify((window as any).donationEvents??[]);
    return !storage.includes(message)&&!dom.includes(message)&&!location.href.includes(message)&&!events.includes(message)&&!events.includes('Token')&&localStorage.length===0;
  },message);
  expect(safe,'message absent from browser persistence/DOM/URL/safe events').toBe(true);
  expect(sinks.get(page),'finite console/URL/ambient credential sink booleans').toEqual({unsafeConsole:false,unsafeUrl:false,ambientAuth:false});
}
async function observe(page: import('@playwright/test').Page) {
  const state={unsafeConsole:false,unsafeUrl:false,ambientAuth:false};sinks.set(page,state);
  page.on('console',entry=>{const text=entry.text();state.unsafeConsole ||= [anonymousDonorMessage,customerDonorMessage].some(value=>text.includes(value))||/\b[A-Za-z0-9_-]{43}\b|Bearer\s|XSRF/i.test(text);});
  page.on('request',request=>{const url=new URL(request.url());state.unsafeUrl ||= [anonymousDonorMessage,customerDonorMessage].some(value=>request.url().includes(value)||request.url().includes(encodeURIComponent(value)))||/checkout[_-]?token|bearer|token=|\b[A-Za-z0-9_-]{43}\b/i.test(url.pathname+url.search+url.hash);if(url.pathname.startsWith('/api/v1/public/')){const headers=request.headers();state.ambientAuth ||= Boolean(headers.cookie||headers['x-xsrf-token']);}});
  await page.evaluate(()=>{
    (window as any).donationEvents=[];
    for(const name of ['donation-created','donation-state','donation-succeeded'])document.addEventListener(name,event=>{
      const detail=(event as CustomEvent).detail;
      (window as any).donationEvents.push({name,keys:Object.keys(detail).sort(),detail});
    });
  });
}

// Never retain DOM snapshots, request bodies, credentials or donor-message media.
test.use({ trace: 'off', screenshot: 'off', video: 'off' });

test('public campaign demo mounts isolated accessible Donation UI', async ({ page }) => {
  await page.route('**/api/v1/public/sites/donor-site/donation-campaigns/help-campaign', route => route.fulfill({ json: {
    key: 'help-campaign', display_name: '<b>Community</b>', purpose_description: '<script>Plain text</script>', currency: 'EUR', suggested_amounts: [4999,1000], minimum_amount: 100, maximum_amount: 10000, custom_amount_allowed: true, anonymous_allowed: true,
  } }));
  await page.goto('/?api_base_url=http%3A%2F%2F127.0.0.1%3A28080&site_key=donor-site&price_key=donor-price&campaign_key=help-campaign&locale=en-GB');
  const donation = page.locator('generic-donation');
  await expect(donation.getByRole('heading', { name: '<b>Community</b>' })).toBeVisible();
  await expect(donation.getByRole('group', { name: 'Donation amount' })).toBeVisible();
  await donation.getByLabel('Custom amount (minor units)').fill('99');
  await donation.getByRole('button', { name: 'Create donation' }).click();
  await expect(donation.getByRole('alert')).toHaveText('Choose a permitted amount.');
  await expect(donation.getByLabel('Custom amount (minor units)')).toBeFocused();
  await expect(page.getByRole('button', { name: 'Load my donations' })).toBeDisabled();
});

test('anonymous donation uses exact body, clears local message, watches provider truth and never restores checkout', async ({page},info)=>{
  const {api,fixture}=await customerFixture('donateanon',info.retry);await campaign(api,fixture);await page.goto(path(api,fixture));await observe(page);
  const donation=page.locator('generic-donation');const message=anonymousDonorMessage;
  await expect(donation.getByLabel('Optional message')).toBeEditable();
  await enterPrivateText(donation.getByLabel('Optional message'),message);
  const create=page.waitForRequest(r=>r.method()==='POST'&&r.url().includes('/donation-campaigns/help-campaign/checkouts'));
  await donation.getByRole('button',{name:'Create donation'}).press('Enter');const request=await create;
  expect(Object.keys(request.postDataJSON()).sort()).toEqual(['amount_minor','message']);expect(request.postDataJSON().amount_minor).toBe(4999);expect(request.headers().authorization).toBeUndefined();expect(request.headers()['idempotency-key']).toBeTruthy();
  await expect(donation.getByRole('button',{name:'Pay',exact:true})).toBeFocused();await expect.poll(async()=>await donation.getByLabel('Optional message').inputValue()==='',{message:'donor draft cleared after local creation'}).toBe(true);await privacy(page,message);
  expect(await page.evaluate(()=>(window as any).donationEvents.some((e:any)=>e.name==='donation-succeeded'))).toBe(false);
  const payment=await api.waitForSinglePayment(fixture.siteId);
  await donation.getByRole('button',{name:'Pay',exact:true}).press('Space');await expect(donation.getByRole('status')).toContainText('Payment is processing');
  await api.fakeEvent(payment.payment_id,'PAYMENT_SUCCEEDED');await api.reconcile(payment.payment_id);await expect(donation.getByRole('status')).toHaveText('Payment succeeded');await privacy(page,message);
  expect(await page.evaluate(()=>(window as any).donationEvents.every((e:any)=>JSON.stringify(e.keys)===JSON.stringify(['checkoutId','donationId','paymentId','status'])))).toBe(true);
  expect(await page.evaluate(()=>Object.keys(sessionStorage).filter(k=>k.startsWith('generic-payments:checkout:')).length)).toBe(0);
  await page.reload();await expect(donation.getByRole('button',{name:'Create donation'})).toBeEnabled();expect(await api.paymentCount(fixture.siteId)).toBe(1);
});

test('email Customer donation restores login and real owned history, deliberate anonymous mode stays separate',async({page},info)=>{
  const {api,fixture}=await customerFixture('donatecustomer',info.retry);await campaign(api,fixture);await page.goto(path(api,fixture));const customer=await signIn(page);await observe(page);
  const donation=page.locator('generic-donation');const message=customerDonorMessage;await enterPrivateText(donation.getByLabel('Optional message'),message);
  const create=page.waitForRequest(r=>r.method()==='POST'&&r.url().includes('/donation-campaigns/help-campaign/checkouts'));
  await donation.getByRole('button',{name:'Create donation'}).click();expect(Boolean((await create).headers().authorization)).toBe(true);await expect(donation.getByRole('button',{name:'Pay',exact:true})).toBeEnabled();await privacy(page,message);
  const payment=await api.waitForSinglePayment(fixture.siteId);await donation.getByRole('button',{name:'Pay',exact:true}).click();await expect(donation.getByRole('status')).toContainText('processing');await api.fakeEvent(payment.payment_id,'PAYMENT_SUCCEEDED');await api.reconcile(payment.payment_id);await expect(donation.getByRole('status')).toHaveText('Payment succeeded');
  await page.getByRole('button',{name:'Load my donations'}).click();await expect(page.locator('#customer-donations')).toContainText('SUCCEEDED');await expect(page.locator('#customer-donations')).toContainText('message PRESENT');await privacy(page,message);
  await page.reload();await expect(page.locator('#customer-summary')).toContainText(customer);await expect(donation.getByRole('button',{name:'Create donation'})).toBeEnabled();
  await page.getByRole('button',{name:'Load my donations'}).click();await expect(page.locator('#customer-donations li')).toHaveCount(1);
  await page.getByLabel('Intentionally donate anonymously').check();const anonymous=page.waitForRequest(r=>r.method()==='POST'&&r.url().includes('/donation-campaigns/help-campaign/checkouts'));
  await donation.getByRole('button',{name:'Create donation'}).click();expect((await anonymous).headers().authorization).toBeUndefined();await expect(donation.getByRole('button',{name:'Pay',exact:true})).toBeEnabled();
  await page.getByRole('button',{name:'Load my donations'}).click();await expect(page.locator('#customer-donations li')).toHaveCount(1);
  await page.getByRole('button',{name:'Log out',exact:true}).click();await expect(page.locator('#customer-action-status')).toHaveText('Logged out.');await expect(donation.getByRole('button',{name:'Create donation'})).toBeEnabled();await expect(page.locator('#customer-donations li')).toHaveCount(0);
});

test('cross-Site stored authority fails without anonymous retry, intentional omit still works',async({page},info)=>{
  const {api,fixture}=await customerFixture('donateinvalid',info.retry);await campaign(api,fixture);await page.goto(path(api,fixture));await signIn(page);
  await page.evaluate(()=>{const key=Object.keys(sessionStorage).find(k=>k.startsWith('generic-payments:customer:v1:'))!;const raw=JSON.parse(sessionStorage.getItem(key)!);raw.siteKey='another-site';sessionStorage.setItem(key,JSON.stringify(raw));});
  let creates=0;page.on('request',r=>{if(r.method()==='POST'&&r.url().includes('/donation-campaigns/help-campaign/checkouts'))creates++;});
  const donation=page.locator('generic-donation');await donation.getByRole('button',{name:'Create donation'}).click();await expect(donation.getByRole('alert')).toHaveText('Customer session unavailable. Sign in again.');expect(creates).toBe(0);expect(await api.paymentCount(fixture.siteId)).toBe(0);
  await page.getByLabel('Intentionally donate anonymously').check();await donation.getByRole('button',{name:'Create donation'}).click();await expect(donation.getByRole('button',{name:'Pay',exact:true})).toBeEnabled();expect(creates).toBe(1);
});

test('actual mixed Customer Payment history includes catalog and donation through the SDK after reload',async({page},info)=>{
  const {api,fixture}=await customerFixture('donatemixed',info.retry);await campaign(api,fixture);await page.goto(path(api,fixture));await signIn(page);await observe(page);
  await page.getByRole('button',{name:'Pay as Customer',exact:true}).click();
  await expect.poll(()=>api.paymentCount(fixture.siteId),{message:'one real identified catalog payment'}).toBe(1);
  const donation=page.locator('generic-donation');await donation.getByRole('button',{name:'Create donation'}).click();await expect(donation.getByRole('button',{name:'Pay',exact:true})).toBeEnabled();
  await page.getByRole('button',{name:'Load my payments',exact:true}).click();await expect(page.locator('#customer-payments li')).toHaveCount(2);
  await page.getByRole('button',{name:'Load my donations',exact:true}).click();await expect(page.locator('#customer-donations li')).toHaveCount(1);
  await page.reload();await expect(page.getByRole('button',{name:'Load my payments',exact:true})).toBeEnabled();
  await page.getByRole('button',{name:'Load my payments',exact:true}).click();await expect(page.locator('#customer-payments li')).toHaveCount(2);
  expect(await api.paymentCount(fixture.siteId)).toBe(2);await privacy(page,customerDonorMessage);
});

test('server-revoked locally unexpired authority returns readable cross-origin rejection and discards owned draft and intent',async({page},info)=>{
  const {api,fixture}=await customerFixture('donaterevoked',info.retry);await campaign(api,fixture);await page.goto(path(api,fixture));await signIn(page);await observe(page);
  if(process.env.DEBUG||(process.env.PWDEBUG&&process.env.PWDEBUG!=='0'))throw new Error('Private browser operation requires debug off');
  // Revoke on the server without calling SDK logout: the owned local capability remains unexpired.
  const revoked=await page.evaluate(async()=>{
    try {
      const key=Object.keys(sessionStorage).find(k=>k.startsWith('generic-payments:customer:v1:'));if(!key)return false;
      const owned=JSON.parse(sessionStorage.getItem(key)!);if(Date.parse(owned.expiresAt)<=Date.now())return false;
      const result=await fetch(owned.apiBaseUrl+'/api/v1/customer/session',{method:'DELETE',headers:{Authorization:'Bearer '+owned.sessionToken},credentials:'omit',redirect:'error'});
      return result.status===204&&sessionStorage.getItem(key)!==null&&new URL(owned.apiBaseUrl).origin!==location.origin;
    } catch { return false; }
  });
  expect(revoked,'server revoked while local authority remains unexpired and cross-origin').toBe(true);
  const attempts:{key:string;authorized:boolean;hasMessage:boolean}[]=[];
  page.on('request',request=>{if(request.method()==='POST'&&request.url().includes('/donation-campaigns/help-campaign/checkouts'))attempts.push({key:request.headers()['idempotency-key']??'',authorized:Boolean(request.headers().authorization),hasMessage:Object.hasOwn(request.postDataJSON(),'message')});});
  const donation=page.locator('generic-donation');await enterPrivateText(donation.getByLabel('Optional message'),customerDonorMessage);
  const rejection=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().includes('/donation-campaigns/help-campaign/checkouts'));
  await donation.getByRole('button',{name:'Create donation'}).click();
  const response=await rejection;expect(response.status()).toBe(401);expect(response.headers()['access-control-allow-origin']).toBe(new URL(page.url()).origin);
  await expect(donation.getByRole('alert')).toHaveText('Customer session unavailable. Sign in again.');
  expect(await page.evaluate(()=>Object.keys(sessionStorage).filter(k=>k.startsWith('generic-payments:customer:v1:')).length)).toBe(0);
  await expect.poll(async()=>await donation.getByLabel('Optional message').inputValue()==='',{message:'owned rejected draft cleared'}).toBe(true);
  expect(attempts.length).toBe(1);expect(await api.paymentCount(fixture.siteId)).toBe(0);await privacy(page,customerDonorMessage);
  await donation.getByRole('button',{name:'Create donation'}).click();await expect(donation.getByRole('button',{name:'Pay',exact:true})).toBeEnabled();
  expect(attempts.length).toBe(2);expect(attempts.map(a=>a.authorized)).toEqual([true,false]);expect(attempts.map(a=>a.hasMessage)).toEqual([true,false]);expect(attempts.every(a=>Boolean(a.key)),'both attempts carry an intent key').toBe(true);expect(attempts[0]?.key===attempts[1]?.key,'discarded intent is not retried').toBe(false);
  expect(await api.paymentCount(fixture.siteId)).toBe(1);await privacy(page,customerDonorMessage);
});

test('server campaign policy disallows custom and anonymous creation, keyboard options preserve order',async({page},info)=>{
  const {api,fixture}=await customerFixture('donatepolicy',info.retry);await campaign(api,fixture,{custom_amount_allowed:false,anonymous_allowed:false});await page.goto(path(api,fixture));
  const donation=page.locator('generic-donation');await expect(donation.getByRole('button',{name:'€49.99'})).toBeVisible();await expect(donation.getByLabel('Custom amount (minor units)')).toBeHidden();
  await page.getByLabel('Intentionally donate anonymously').check();await donation.getByRole('button',{name:'€10.00'}).focus();await donation.getByRole('button',{name:'€10.00'}).press('Space');await expect(donation.getByRole('button',{name:'€10.00'})).toHaveAttribute('aria-pressed','true');
  await donation.getByRole('button',{name:'Create donation'}).click();await expect(donation.getByRole('alert')).toHaveText('Sign in is required for this campaign.');await expect(donation.getByRole('status')).toBeFocused();expect(await api.paymentCount(fixture.siteId)).toBe(0);
});

test('new Customer authority immediately clears the previous owned Donation history and checkout',async({page},info)=>{
  const {api,fixture}=await customerFixture('donaterace',info.retry);await campaign(api,fixture);await page.goto(path(api,fixture));await signIn(page);
  const donation=page.locator('generic-donation');await donation.getByRole('button',{name:'Create donation'}).click();await expect(donation.getByRole('button',{name:'Pay',exact:true})).toBeEnabled();
  await page.getByRole('button',{name:'Load my donations'}).click();await expect(page.locator('#customer-donations li')).toHaveCount(1);
  // A's restored authentication and fresh OTP UI make replacement B reachable without logout.
  await page.reload();await expect(page.getByRole('button',{name:'Load my donations'})).toBeEnabled();await page.getByRole('button',{name:'Load my donations'}).click();await expect(page.locator('#customer-donations li')).toHaveCount(1);
  await signIn(page);await expect(page.locator('#customer-donations li')).toHaveCount(0);
  await expect(donation.getByRole('button',{name:'Create donation'})).toBeEnabled();
});
