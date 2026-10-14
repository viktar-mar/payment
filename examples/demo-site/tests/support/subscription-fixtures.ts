import { randomUUID } from 'node:crypto';
import { expect, type Locator, type Page } from '@playwright/test';
import { customerFixture, demoPath, signIn } from './customer-api';

export async function subscriptionFixture(journey: string, retry: number, trialDays = 0) {
  const control=process.env.PAYMENT_STAGE4_CONTROL_URL,key=process.env.PAYMENT_STAGE4_CONTROL_KEY;
  if(!control||!key)throw new Error('Explicit owned business clock required');
  const clock=await fetch(control,{headers:{Authorization:'Bearer '+key},redirect:'error',signal:AbortSignal.timeout(5000)});
  if(clock.status!==200)throw new Error('Owned business clock unavailable');
  const saved=Date.parse(await clock.text());
  if(!Number.isFinite(saved))throw new Error('Invalid owned business clock');
  const now=Date.now();
  if(saved<now){
    const aligned=await fetch(control,{method:'POST',headers:{Authorization:'Bearer '+key},body:new Date(now).toISOString(),redirect:'error',signal:AbortSignal.timeout(5000)});
    if(aligned.status!==200)throw new Error('Owned business clock alignment failed');await aligned.arrayBuffer();
  }
  const { api, fixture } = await customerFixture(journey, retry);
  const policyPath = `/api/v1/admin/sites/${fixture.siteId}/subscription-policy`;
  const savedPolicy = await api.customerRequest<{version:number}>('GET', policyPath);
  await api.customerRequest('POST', policyPath, { version:savedPolicy.version, enabled:true, grace_days:3,
    allow_immediate_cancellation:true, portal_enabled:false, defer_upgrades:false,
    portal_return_urls:[] });
  const products: Array<{id:string;priceId:string;upgradeId:string}> = [];
  for (let index=0;index<2;index++) {
    const product=await api.customerRequest<{id:string}>('POST',`/api/admin/v1/sites/${fixture.siteId}/products`,{key:'sub-'+randomUUID(),name:'Owned subscription '+index,description:'Task17 local browser acceptance'});
    const ids:string[]=[];
    for(const amount of [2000,4000]) {
      const price=await api.customerRequest<{id:string}>('POST',`/api/admin/v1/products/${product.id}/prices`,{
        price_key:'sub-'+randomUUID(),type:'SUBSCRIPTION',amount_minor:amount,currency:'EUR',billing_interval:'MONTH',
        trial_days:trialDays,trial_payment_method_policy:'OPTIONAL_DURING_TRIAL',tax_mode:'INCLUSIVE',tax_code:null,
      });
      await api.customerRequest('POST',`/api/admin/v1/prices/${price.id}/publish`,{});ids.push(price.id);
    }
    const [priceId,upgradeId]=ids;
    if(!priceId||!upgradeId)throw new Error('Owned subscription prices were not provisioned');
    const currentPolicy=await api.customerRequest<{version:number}>('GET',policyPath);
    await api.customerRequest('POST',`/api/v1/admin/sites/${fixture.siteId}/products/${product.id}/subscription-transitions`,{
      from_price_id:priceId,to_price_id:upgradeId,direction:'UPGRADE',immediate_allowed:true,policy_version:currentPolicy.version,
    });
    products.push({id:product.id,priceId,upgradeId});
  }
  const path=new URL(demoPath(api,fixture),api.demoOrigin);
  products.forEach((p,i)=>{path.searchParams.set(`subscription_product_${i+1}_id`,p.id);path.searchParams.set(`subscription_price_${i+1}_id`,p.priceId);});
  const portalPolicy=await api.customerRequest<{version:number}>('GET',policyPath);
  await api.customerRequest('POST',policyPath,{version:portalPolicy.version,enabled:true,grace_days:3,
    allow_immediate_cancellation:true,portal_enabled:true,defer_upgrades:false,portal_return_urls:[path.href]});
  async function open(page:Page){
    const diagnostic={pageErrors:0,cspErrors:0,subscriptionResponses:[] as number[]};
    page.on('pageerror',()=>{diagnostic.pageErrors++;});
    page.on('console',message=>{if(message.text().includes('Content Security Policy'))diagnostic.cspErrors++;});
    page.on('response',response=>{if(new URL(response.url()).pathname.startsWith('/api/v1/customer/subscription'))diagnostic.subscriptionResponses.push(response.status());});
    await page.goto(path.pathname+path.search);const customerId=await signIn(page);
    const manager=page.locator('generic-subscription-manager').first();
    try {await expect(manager.getByRole('button',{name:'Create subscription',exact:true})).toBeEnabled();}
    catch(error){
      const state=await manager.evaluate((element:any)=>({defined:!!customElements.get(element.localName),shadow:!!element.shadowRoot,client:!!element.client,session:!!element.customerSession,read:element.customerSession?.scopes.includes('SUBSCRIPTION_READ')??false,manage:element.customerSession?.scopes.includes('SUBSCRIPTION_MANAGE')??false,product:!!element.productId,phase:element.state?.phase??'absent'}));
      console.log('Subscription payload-free diagnostic '+JSON.stringify({...diagnostic,...state}));throw error;
    }
    expect(diagnostic.cspErrors,'accepted SDK styles satisfy the exact-hash demo CSP').toBe(0);
    return customerId;
  }
  async function view(id:string):Promise<any>{return api.customerRequest('GET',`/api/v1/admin/subscriptions/${id}?site_id=${fixture.siteId}`);}
  async function fake(id:string,body:Record<string,unknown>){await api.customerRequest('POST',`/api/v1/admin/subscriptions/${id}/fake-controls?site_id=${fixture.siteId}`,body);}
  async function advance(id:string,until:string){
    const url=process.env.PAYMENT_STAGE4_CONTROL_URL,key=process.env.PAYMENT_STAGE4_CONTROL_KEY;
    if(!url||!key)throw new Error('Explicit owned business clock required');
    const r=await fetch(url,{method:'POST',headers:{Authorization:'Bearer '+key},body:until,redirect:'error',signal:AbortSignal.timeout(5000)});
    if(r.status!==200)throw new Error('Owned business clock failed');await r.arrayBuffer();
    await fake(id,{action:'ADVANCE',until});
  }
  const primaryProduct=products[0];
  if(!primaryProduct)throw new Error('Owned primary Product was not provisioned');
  return {api,fixture,products,primaryProduct,open,view,fake,advance};
}
export async function createSubscription(manager:Locator){
  const observed: number[]=[];
  const observe=(response:import('@playwright/test').Response)=>{if(response.request().method()==='POST'&&new URL(response.url()).pathname==='/api/v1/customer/subscriptions')observed.push(response.status());};
  manager.page().on('response',observe);
  await manager.getByRole('checkbox',{name:'I consent to recurring payments',exact:true}).check();
  await manager.getByRole('button',{name:'Create subscription',exact:true}).press('Enter');
  try {await expect.poll(async()=>{
    await manager.getByRole('button',{name:'Refresh status',exact:true}).click();
    return manager.evaluate((element:any)=>element.state.subscription?.subscriptionId??null);
  },{timeout:15000}).not.toBeNull();}
  catch(error){console.log('Create payload-free diagnostic '+JSON.stringify({statuses:observed,...await manager.evaluate((element:any)=>({phase:element.state.phase,hasOperation:!!element.state.operation,operationStatus:element.state.operation?.status??'absent'}))}));throw error;}
  finally{manager.page().off('response',observe);}
  return manager.evaluate((element:any)=>element.state.subscription.subscriptionId as string);
}
export async function refreshUntil(manager:Locator,state:string){
  await expect.poll(async()=>{await manager.getByRole('button',{name:'Refresh status',exact:true}).click();return manager.evaluate((element:any)=>element.state.subscription?.state??'');},{timeout:20000}).toBe(state);
}
