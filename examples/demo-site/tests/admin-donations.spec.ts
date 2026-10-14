import { expect, test, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { AdminApi } from './support/admin-api';
import { enterPrivateText } from './support/private-entry';
import { adminDonorMessage } from './support/admin-donation-fixtures';
const adminOrigin=process.env.PAYMENT_E2E_ADMIN_URL??'http://127.0.0.1:23000';
async function login(page:Page){
  await page.goto(adminOrigin+'/login');
  await enterPrivateText(page.getByLabel('Username'),process.env.PAYMENT_E2E_ADMIN_USERNAME??'');
  await enterPrivateText(page.getByLabel('Password'),process.env.PAYMENT_E2E_ADMIN_PASSWORD??'');
  await page.getByRole('button',{name:'Sign in',exact:true}).click();
  await expect(page.getByRole('button',{name:'Sign out',exact:true})).toBeVisible();
}
const configuration={key:'browser-campaign',display_name:'Browser campaign',currency:'EUR',suggested_amounts:[500],minimum_amount:100,maximum_amount:2000,custom_amount_allowed:true,anonymous_allowed:true};
test.use({trace:'off',screenshot:'off',video:'off'});

test('Admin Donation refund uses existing payment controls and separates acceptance from financial finality',async({page},info)=>{
  const api=await AdminApi.login(),fixture=await api.provision('adminrefund',info.retry);
  const campaign=await api.customerRequest<{id:string;version:number}>('POST',`/api/v1/admin/sites/${fixture.siteId}/donation-campaigns`,configuration);
  await api.customerRequest('POST',`/api/v1/admin/sites/${fixture.siteId}/donation-campaigns/${campaign.id}/status`,{status:'ACTIVE',expected_version:campaign.version});
  const response=await fetch(api.backendUrl+`/api/v1/public/sites/${fixture.siteKey}/donation-campaigns/${configuration.key}/checkouts`,{method:'POST',redirect:'error',signal:AbortSignal.timeout(10000),headers:{Origin:api.demoOrigin,'Content-Type':'application/json','Idempotency-Key':randomUUID()},body:JSON.stringify({amount_minor:500})});
  expect(response.status).toBe(201);
  // Checkout authority remains in this setup closure, never in matcher diagnostics or UI arguments.
  let checkout:{checkout_id:string;checkout_token:string;payment_id:string;donation:{donation_id:string}};
  try{checkout=await response.json();}catch{throw new Error('Donation checkout fixture JSON unavailable; payload omitted');}
  const confirmation=await fetch(api.backendUrl+`/api/v1/public/checkouts/${checkout.checkout_id}/confirm`,{method:'POST',redirect:'error',signal:AbortSignal.timeout(10000),headers:{Origin:api.demoOrigin,Authorization:'Bearer '+checkout.checkout_token,'Content-Type':'application/json','Idempotency-Key':randomUUID()},body:JSON.stringify({payment_method_family:'CARD'})});
  expect(confirmation.ok).toBe(true);checkout.checkout_token='';
  await api.fakeEvent(checkout.payment_id,'PAYMENT_SUCCEEDED',7);
  await api.waitForStatus(checkout.payment_id,'SUCCEEDED');await api.reconcile(checkout.payment_id);
  await login(page);await page.goto(adminOrigin+'/donations/'+checkout.donation.donation_id);
  await expect(page.getByRole('region',{name:'Donation summary'}).getByText('SUCCEEDED',{exact:true})).toBeVisible();
  await page.getByRole('link',{name:'Payment details, refunds and reconciliation',exact:true}).click();
  const summary=page.getByRole('region',{name:'Payment summary'}),refunds=page.getByRole('region',{name:'Refunds'});
  const refunded=summary.locator('dt').filter({hasText:'Confirmed refunded amount in minor units'}).locator('xpath=following-sibling::dd[1]');
  await expect(summary.getByText('SUCCEEDED',{exact:true})).toBeVisible();await expect(refunded).toHaveText('0');
  await page.getByRole('button',{name:'Refund',exact:true}).click();
  const dialog=page.getByRole('dialog',{name:'Refund payment'});
  await expect(dialog.getByLabel('Amount in minor units')).toHaveValue('500');
  await dialog.getByLabel('Amount in minor units').fill('500');await dialog.getByRole('combobox',{name:/^Reason/}).selectOption('REQUESTED_BY_CUSTOMER');
  const acceptedResponse=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith(`/api/backend/v1/admin/payments/${checkout.payment_id}/refunds`));
  await dialog.getByRole('button',{name:'Confirm refund',exact:true}).click();
  expect((await acceptedResponse).status()).toBe(202);
  await expect(dialog.getByRole('status')).toHaveText('Refund accepted; awaiting confirmation.');
  await expect(summary.getByText('SUCCEEDED',{exact:true})).toBeVisible();await expect(refunded).toHaveText('0');
  const pending=await api.customerRequest<{payment:{status:string;refunded_amount_minor:number};refunds:{status:string;amount_minor:number}[]}>('GET',`/api/v1/admin/payments/${checkout.payment_id}`);
  expect(pending.payment.status==='SUCCEEDED'&&pending.payment.refunded_amount_minor===0&&pending.refunds.length===1&&pending.refunds[0]?.amount_minor===500&&['PENDING','PROCESSING'].includes(pending.refunds[0]?.status??'')).toBe(true);
  await dialog.getByRole('button',{name:'Close',exact:true}).click();await expect(refunds.locator('li')).toHaveCount(1);
  // Only the real fake-provider/inbox path supplies refund finality; no direct refund API call.
  await page.getByRole('button',{name:'Simulate fake event',exact:true}).click();
  const fake=page.getByRole('dialog',{name:'Confirm fake event'});
  await fake.getByRole('combobox',{name:/^Target/}).selectOption('REFUND');await fake.getByRole('combobox',{name:/^Refund/}).selectOption({index:1});
  await fake.getByRole('combobox',{name:/^Event type/}).selectOption('REFUND_SUCCEEDED');await fake.getByLabel('Sequence',{exact:true}).fill('9');
  await fake.getByRole('button',{name:'Confirm fake event',exact:true}).click();await expect(fake).toHaveCount(0);
  await api.waitForStatus(checkout.payment_id,'REFUNDED');
  await page.getByRole('button',{name:'Reconcile',exact:true}).click();
  await page.getByRole('dialog',{name:'Confirm reconcile'}).getByRole('button',{name:'Confirm reconcile',exact:true}).click();
  await expect(summary.getByText('REFUNDED',{exact:true})).toBeVisible();await expect(refunded).toHaveText('500');
  await expect(refunds.getByText('SUCCEEDED',{exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'Refund',exact:true})).toHaveCount(0);
  await page.getByRole('region',{name:'Donation and Customer context'}).getByRole('link',{name:checkout.donation.donation_id,exact:true}).click();
  await expect(page.getByRole('region',{name:'Donation summary'}).getByText('REFUNDED',{exact:true})).toBeVisible();
});

test('Admin Campaign create and disappearing lifecycle triggers restore focus; conflicts refresh and navigation respects sessions',async({page,context},info)=>{
  const api=await AdminApi.login(),fixture=await api.provision('admincampaign',info.retry);
  await login(page);const list=adminOrigin+`/sites/${fixture.siteId}/donation-campaigns`;
  await page.goto(list);await page.getByRole('button',{name:'Create campaign',exact:true}).click();
  await page.getByLabel('Campaign key').fill(configuration.key);await page.getByLabel('Display name').fill(configuration.display_name);
  await page.getByRole('button',{name:'Save campaign',exact:true}).click();
  await expect(page.getByRole('button',{name:'Create campaign',exact:true})).toBeFocused();
  await page.getByRole('link',{name:configuration.display_name,exact:true}).click();
  await page.getByRole('button',{name:'Activate',exact:true}).click();
  await expect(page.getByRole('button',{name:'Cancel',exact:true})).toBeFocused();
  await page.getByRole('button',{name:'Confirm activate',exact:true}).click();
  await expect(page.locator('[data-campaign-heading]')).toBeFocused();
  await expect(page.getByRole('button',{name:'Pause',exact:true})).toBeVisible();
  const campaigns=await api.customerRequest<{items:{id:string;version:number}[]}>('GET',`/api/v1/admin/sites/${fixture.siteId}/donation-campaigns`);
  const campaign=campaigns.items[0];if(!campaign)throw new Error('Campaign setup unavailable');
  await api.customerRequest('POST',`/api/v1/admin/sites/${fixture.siteId}/donation-campaigns/${campaign.id}/status`,{status:'PAUSED',expected_version:campaign.version});
  await page.getByRole('button',{name:'Pause',exact:true}).click();await page.getByRole('button',{name:'Confirm pause',exact:true}).click();
  await expect(page.locator('p[role="alert"]')).toHaveText('Refresh campaign and review its latest state before retrying.');
  await expect(page.getByRole('button',{name:'Confirm pause',exact:true})).toBeDisabled();
  await page.getByRole('button',{name:'Refresh campaign',exact:true}).click();await expect(page.getByRole('button',{name:'Resume',exact:true})).toBeVisible();
  const other=await api.customerRequest<{id:string}>('POST',`/api/v1/admin/sites/${fixture.siteId}/donation-campaigns`,{...configuration,key:'other-campaign',display_name:'Other campaign'});
  await page.goto(list+'/'+other.id);await expect(page.locator('[data-campaign-heading]')).toHaveText('Other campaign');
  await expect(page.getByRole('button',{name:'Activate',exact:true})).toBeVisible();
  await context.clearCookies();await page.reload();await expect(page.getByRole('button',{name:'Sign in',exact:true})).toBeVisible();
});

test('Admin message is literal text; UUID redaction prevents double submission and retries the same accepted intent safely',async({page},info)=>{
  const api=await AdminApi.login(),fixture=await api.provision('adminredact',info.retry);
  const campaign=await api.customerRequest<{id:string;version:number}>('POST',`/api/v1/admin/sites/${fixture.siteId}/donation-campaigns`,configuration);
  await api.customerRequest('POST',`/api/v1/admin/sites/${fixture.siteId}/donation-campaigns/${campaign.id}/status`,{status:'ACTIVE',expected_version:campaign.version});
  const response=await fetch(api.backendUrl+`/api/v1/public/sites/${fixture.siteKey}/donation-campaigns/${configuration.key}/checkouts`,{method:'POST',redirect:'error',signal:AbortSignal.timeout(10000),headers:{Origin:api.demoOrigin,'Content-Type':'application/json','Idempotency-Key':randomUUID()},body:JSON.stringify({amount_minor:500,message:adminDonorMessage})});
  expect(response.status).toBe(201);const donation=(await response.json()).donation.donation_id as string;
  let unsafeConsole=false;page.on('console',entry=>{unsafeConsole ||= entry.text().includes(adminDonorMessage);});
  await login(page);await page.goto(adminOrigin+'/donations/'+donation);
  await expect.poll(async()=>await page.getByLabel('Donor message').evaluate((node,value)=>node.textContent===value,adminDonorMessage),{message:'authorized message rendered as literal text'}).toBe(true);
  expect(await page.getByLabel('Donor message').locator('img,script').count()).toBe(0);
  expect(await page.evaluate(()=>Boolean((window as any).donorExecuted))).toBe(false);
  await page.getByRole('button',{name:'Redact message',exact:true}).click();await expect(page.getByRole('button',{name:'Cancel',exact:true})).toBeFocused();
  await page.getByLabel('Donation UUID confirmation').fill('not-a-uuid');await expect(page.getByRole('button',{name:'Confirm redaction',exact:true})).toBeDisabled();
  await page.getByLabel('Donation UUID confirmation').fill(donation);
  let calls=0,firstKey='',sameKey=true,release:()=>void=()=>{};const hold=new Promise<void>(resolve=>{release=resolve;});
  await page.route('**/api/backend/v1/admin/donations/*/message-redactions',async route=>{
    calls++;const key=route.request().headers()['idempotency-key']??'';if(calls===1){firstKey=key;await hold;await route.fetch();await route.fulfill({status:502,contentType:'application/problem+json',body:JSON.stringify({type:'urn:payment:problem:backend_unavailable',code:'backend_unavailable',status:502,title:'Backend unavailable'})});}
    else {sameKey&&=key===firstKey;await route.continue();}
  });
  await page.getByRole('dialog').locator('form').evaluate((form)=>{(form as HTMLFormElement).requestSubmit();(form as HTMLFormElement).requestSubmit();});
  await expect.poll(()=>calls).toBe(1);await expect(page.getByRole('button',{name:'Confirm redaction',exact:true})).toBeDisabled();release();
  await page.getByRole('button',{name:'Retry redaction',exact:true}).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);await expect(page.locator('[data-donation-heading]')).toBeFocused();
  expect(calls).toBe(2);expect(sameKey&&Boolean(firstKey)).toBe(true);
  expect(await page.evaluate(value=>!document.documentElement.outerHTML.includes(value)&&!JSON.stringify({...localStorage,...sessionStorage}).includes(value),adminDonorMessage)).toBe(true);
  expect(unsafeConsole).toBe(false);
  const detail=await api.customerRequest<{message:null;donation:{message_state:string}}>('GET','/api/v1/admin/donations/'+donation);
  expect(detail.message===null&&detail.donation.message_state==='REDACTED').toBe(true);
});
