import { test, expect } from '@playwright/test';
import { subscriptionFixture, createSubscription, refreshUntil } from './support/subscription-fixtures';

test('local Portal returns to the canonical manager URL with reads only',async({page},info)=>{
  const f=await subscriptionFixture('sub-portal',info.retry);await f.open(page);
  const manager=page.locator('generic-subscription-manager').first();
  const id=await createSubscription(manager);await refreshUntil(manager,'ACTIVE');
  const before=await f.view(id),cash=await f.api.paymentCount(f.fixture.siteId);
  const canonical=page.url(),methods:string[]=[];let portalPosts=0;
  page.on('request',request=>{
    const path=new URL(request.url()).pathname;
    if(path.startsWith('/api/v1/customer/subscriptions')) {
      if(path.endsWith('/portal-sessions')&&request.method()==='POST')portalPosts++;
      else methods.push(request.method());
    }
  });
  await manager.getByRole('button',{name:'Open customer portal',exact:true}).click();
  await expect.poll(()=>new URL(page.url()).searchParams.get('subscriptionPortal')).toBe('simulated');
  const returned=new URL(page.url());
  expect(returned.searchParams.getAll('subscriptionPortal')).toHaveLength(1);
  expect(returned.searchParams.getAll('operationId')).toHaveLength(1);
  returned.searchParams.delete('subscriptionPortal');returned.searchParams.delete('operationId');
  expect(returned.href===canonical,'exact canonical unmarked return').toBe(true);
  await expect(page.getByText('Returned from the local Fake portal simulation. Subscription state is reloaded from the server after sign-in recovery.',{exact:true})).toBeVisible();
  await expect(manager.getByRole('button',{name:'Open customer portal',exact:true})).toBeEnabled();
  await refreshUntil(manager,'ACTIVE');
  expect(portalPosts).toBe(1);expect(methods.length).toBeGreaterThan(0);expect(methods.every(method=>method==='GET')).toBe(true);
  const after=await f.view(id);
  expect(after.revision).toBe(before.revision);expect(after.state).toBe(before.state);
  expect(after.cancellation).toEqual(before.cancellation);expect(after.billing_price).toEqual(before.billing_price);
  expect(await f.api.paymentCount(f.fixture.siteId)).toBe(cash);
});

test('OTP trial, setup, real invoice, upgrade preview, grace recovery and cancellation',async({page},info)=>{
  const f=await subscriptionFixture('sub-journey',info.retry,7);await f.open(page);
  const manager=page.locator('generic-subscription-manager').first();const id=await createSubscription(manager);
  await refreshUntil(manager,'TRIALING');expect(await f.api.paymentCount(f.fixture.siteId)).toBe(0);
  await manager.getByRole('checkbox',{name:'I consent to recurring payments',exact:true}).check();
  await manager.getByRole('button',{name:'Update payment method',exact:true}).click();
  await expect(manager.getByRole('button',{name:'Complete Fake action',exact:true})).toBeEnabled();
  await manager.getByRole('button',{name:'Complete Fake action',exact:true}).click();
  await refreshUntil(manager,'TRIALING');expect(await f.api.paymentCount(f.fixture.siteId)).toBe(0);
  await f.advance(id,(await f.view(id)).trial.end);await refreshUntil(manager,'ACTIVE');
  expect(await f.api.paymentCount(f.fixture.siteId)).toBe(1);
  await manager.getByLabel('Published plan',{exact:true}).selectOption(f.primaryProduct.upgradeId);
  await manager.getByRole('combobox',{name:'Change timing',exact:true}).selectOption('IMMEDIATE');
  await manager.getByRole('button',{name:'Preview plan change',exact:true}).click();
  await expect(manager.getByText(/Due now/)).toBeVisible();
  await manager.getByRole('checkbox',{name:'I confirm this plan change',exact:true}).check();
  await manager.getByRole('button',{name:'Apply plan change',exact:true}).click();
  await expect.poll(async()=>{await manager.getByRole('button',{name:'Refresh status',exact:true}).click();return (await f.view(id)).entitled_price.price_id;}).toBe(f.primaryProduct.upgradeId);
  await f.fake(id,{action:'SCENARIO',scenario:'DECLINE'});await f.advance(id,(await f.view(id)).period.end);await refreshUntil(manager,'PAST_DUE');
  const debt=(await f.view(id)).debt;expect(debt.grace_deadline).not.toBeNull();expect((await f.view(id)).access.allowed).toBe(true);
  await f.fake(id,{action:'SCENARIO',scenario:'SUCCESS'});await manager.getByRole('button',{name:'Pay the existing invoice',exact:true}).click();await refreshUntil(manager,'ACTIVE');
  await manager.getByRole('button',{name:'Cancel at period end',exact:true}).click();await manager.getByRole('checkbox',{name:'I confirm cancellation',exact:true}).check();await manager.getByRole('button',{name:'Confirm cancellation',exact:true}).click();await refreshUntil(manager,'CANCEL_AT_PERIOD_END');
  await manager.getByRole('button',{name:'Withdraw cancellation',exact:true}).click();await refreshUntil(manager,'ACTIVE');
  await manager.getByRole('button',{name:'Cancel immediately',exact:true}).click();await manager.getByRole('checkbox',{name:'I confirm cancellation',exact:true}).check();await manager.getByRole('button',{name:'Confirm cancellation',exact:true}).click();await refreshUntil(manager,'CANCELLED');expect((await f.view(id)).access.allowed).toBe(false);
});

test('ready theme and locale changes are keyboard accessible without financial mutations',async({page},info)=>{
  const f=await subscriptionFixture('sub-locale',info.retry);await f.open(page);let mutations=0;
  page.on('request',r=>{if(r.method()==='POST'&&r.url().includes('/customer/subscriptions'))mutations++;});
  await page.getByRole('button',{name:'Português',exact:true}).press('Enter');
  await expect(page.locator('generic-subscription-manager').first().getByRole('button',{name:'Criar subscrição',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Subscription theme',exact:true}).press('Enter');
  await expect(page.getByRole('button',{name:'Subscription theme',exact:true})).toHaveAttribute('aria-pressed','true');
  await page.getByRole('button',{name:'English',exact:true}).press('Enter');expect(mutations).toBe(0);expect(await f.api.paymentCount(f.fixture.siteId)).toBe(0);
});
