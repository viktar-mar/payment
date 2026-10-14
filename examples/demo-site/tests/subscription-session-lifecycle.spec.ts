import { test, expect } from '@playwright/test';
import { subscriptionFixture } from './support/subscription-fixtures';
import { signIn } from './support/customer-api';

for(const boundary of ['logout','customer-switch','disconnect'] as const)test(`held real subscription response is fenced by ${boundary}`,async({page},info)=>{
  const f=await subscriptionFixture('sub-'+boundary,info.retry);await f.open(page);
  const manager=page.locator('generic-subscription-manager').first();
  let release!:()=>void,received!:()=>void,posts=0;
  const held=new Promise<void>(resolve=>{release=resolve;}),reached=new Promise<void>(resolve=>{received=resolve;});
  await page.evaluate(()=>{(window as any).subscriptionEvents=[];document.addEventListener('generic-subscription-state',(event:any)=>(window as any).subscriptionEvents.push(event.detail));});
  await page.route('**/api/v1/customer/subscriptions',async route=>{
    if(route.request().method()!=='POST'){await route.continue();return;}posts++;
    const response=await route.fetch({timeout:10000});received();await held;
    try{await route.fulfill({response});}catch{/* The old fetch may already have been aborted by its owner. */}
  });
  try{
    await manager.getByRole('checkbox',{name:'I consent to recurring payments',exact:true}).check();
    await manager.getByRole('button',{name:'Create subscription',exact:true}).click();
    let timer:ReturnType<typeof setTimeout>|undefined;
    try{await Promise.race([reached,new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('Owned response not reached')),10000);})]);}finally{clearTimeout(timer);}
    if(boundary==='disconnect')await manager.evaluate(element=>{(window as any).detachedSubscription=element;element.remove();});
    else {await page.locator('#customer-logout').click();await expect(manager.getByRole('status')).toHaveText('Sign in to manage subscriptions.');if(boundary==='customer-switch')await signIn(page);}
    const before=await page.evaluate(()=>({events:(window as any).subscriptionEvents.length,storage:JSON.stringify(sessionStorage)}));
    release();await page.unrouteAll({behavior:'wait'});
    expect(posts).toBe(1);
    const safe=await page.evaluate(({boundary,before})=>{
      const element:any=boundary==='disconnect'?(window as any).detachedSubscription:document.querySelector('generic-subscription-manager');
      return element.state.subscription===null&&element.state.operation===null&&JSON.stringify(sessionStorage)===before.storage&&(window as any).subscriptionEvents.slice(before.events).every((event:any)=>event.subscriptionId===null&&event.operationId===null);
    },{boundary,before});expect(safe).toBe(true);
    await expect.poll(()=>f.api.paymentCount(f.fixture.siteId),{timeout:15000}).toBe(1);
  }finally{release();await page.unrouteAll({behavior:'wait'});}
});
