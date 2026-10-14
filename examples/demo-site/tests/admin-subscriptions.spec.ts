import { test, expect } from '@playwright/test';
import { subscriptionFixture, createSubscription, refreshUntil } from './support/subscription-fixtures';
import { adminLogin, adminUrl } from './support/customer-api';

test('real Catalog policy and AppRouter subscription history/payment navigation',async({page,context},info)=>{
  const f=await subscriptionFixture('sub-admin',info.retry);await f.open(page);
  const manager=page.locator('generic-subscription-manager').first(),id=await createSubscription(manager);await refreshUntil(manager,'ACTIVE');
  const admin=await context.newPage();await adminLogin(admin);
  await admin.goto(`${adminUrl}/subscriptions?site_id=${f.fixture.siteId}`);
  await admin.getByRole('link',{name:id,exact:true}).click();
  await expect(admin.getByRole('heading',{name:'Subscription '+id,exact:true})).toBeVisible();
  await expect(admin.getByRole('heading',{name:'Lifecycle',exact:true})).toBeVisible();
  await admin.getByRole('button',{name:'Reconcile provider result',exact:true}).click();
  await admin.getByRole('dialog').getByRole('button',{name:'Confirm reconciliation',exact:true}).click();
  await expect(admin.getByRole('dialog')).toHaveCount(0);
  await admin.getByRole('link',{name:'Open payment and refunds',exact:true}).first().click();
  await expect(admin).toHaveURL(/\/payments\/[^?]+$/);await expect(admin.getByText(id,{exact:true})).toBeVisible();
  expect((await f.view(id)).state).toBe('ACTIVE');
  await admin.goto(`${adminUrl}/sites/${f.fixture.siteId}/subscriptions`);
  await expect(admin.getByRole('checkbox',{name:'Subscriptions enabled',exact:true})).toBeChecked();
  await admin.getByRole('link',{name:'Plan transitions and Prices / Transições de plano e preços',exact:true}).click();
  const transitions=admin.getByRole('heading',{name:'Plan transitions · '+f.primaryProduct.id,exact:true}).locator('..');
  await expect(transitions.getByRole('combobox',{name:'From Price',exact:true}).locator('option')).toHaveCount(3);
  await expect(transitions.getByText(f.primaryProduct.priceId+' → '+f.primaryProduct.upgradeId+' · UPGRADE · immediate allowed',{exact:true})).toBeVisible();
  await admin.goto(`${adminUrl}/sites/${f.fixture.siteId}/subscriptions`);
  const priorPolicy=await f.api.customerRequest<{version:number}>('GET',`/api/v1/admin/sites/${f.fixture.siteId}/subscription-policy`);
  await admin.getByLabel('Grace days (UTC)',{exact:true}).fill('4');await admin.getByRole('button',{name:'Save policy',exact:true}).click();
  await expect(admin.getByText('Saved policy version: '+(priorPolicy.version+1),{exact:true})).toBeVisible();
  await admin.getByRole('checkbox',{name:'Subscriptions enabled',exact:true}).uncheck();await admin.getByRole('button',{name:'Save policy',exact:true}).click();
  await expect(admin.getByRole('region',{name:'Subscription policy',exact:true}).getByRole('alert')).toContainText('Finish non-terminal subscriptions');
  await admin.close();
});

for(const boundary of ['read','dialog'] as const) test(`live Admin session revocation fences a subscription ${boundary}`,async({page,context},info)=>{
  const f=await subscriptionFixture('sub-admin-'+boundary,info.retry);await f.open(page);
  const manager=page.locator('generic-subscription-manager').first(),id=await createSubscription(manager);await refreshUntil(manager,'ACTIVE');
  const admin=await context.newPage();await adminLogin(admin);await admin.goto(`${adminUrl}/subscriptions/${id}?site_id=${f.fixture.siteId}`);
  await expect(admin.getByRole('heading',{name:'Subscription '+id,exact:true})).toBeVisible();
  const before=await f.view(id);let release:()=>void=()=>{},reached:()=>void=()=>{},financialPosts=0;
  admin.on('request',request=>{if(request.method()==='POST'&&new URL(request.url()).pathname===`/api/backend/v1/admin/subscriptions/${id}/cancellation`)financialPosts++;});
  const held=new Promise<void>(resolve=>release=resolve),started=new Promise<void>(resolve=>reached=resolve);
  const pattern=`**/api/backend/v1/admin/subscriptions/${id}?site_id=*`;
  if(boundary==='read'){
    await admin.route(pattern,async route=>{reached();await held;await route.continue();});
    await admin.getByRole('button',{name:'Refresh status',exact:true}).click();
    let timer:ReturnType<typeof setTimeout>|undefined;
    try{await Promise.race([started,new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('owned read was not reached')),10000);})]);}finally{clearTimeout(timer);}
  }else{
    await admin.getByRole('button',{name:'Cancel immediately',exact:true}).click();
    await admin.getByRole('dialog').getByLabel('Subscription UUID confirmation',{exact:true}).fill(id);
  }
  try{
    const xsrf=(await context.cookies(adminUrl)).find(cookie=>cookie.name==='XSRF-TOKEN');
    if(!xsrf)throw new Error('Owned Admin CSRF cookie unavailable');
    const revoked=await context.request.delete(adminUrl+'/api/backend/admin/v1/auth/session',{headers:{'X-XSRF-TOKEN':xsrf.value},timeout:5000});
    expect(revoked.status()).toBe(204);
    if(boundary==='read')release();else await admin.getByRole('dialog').getByRole('button',{name:'Confirm cancellation',exact:true}).click();
    await expect(admin.getByRole('link',{name:'Sign in again',exact:true})).toBeVisible();await expect(admin.getByRole('dialog')).toHaveCount(0);
    await expect(admin.getByRole('heading',{name:'Subscription '+id,exact:true})).toHaveCount(0);
    await expect(admin.getByRole('button',{name:'Cancel immediately',exact:true})).toHaveCount(0);
    expect(financialPosts).toBe(boundary==='dialog'?1:0);
    const after=await f.view(id);expect(after.state).toBe('ACTIVE');expect(after.revision).toBe(before.revision);
    expect(await f.api.paymentCount(f.fixture.siteId)).toBe(1);
  }finally{release();await admin.unroute(pattern);await admin.close();}
});
