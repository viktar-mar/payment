import { afterEach, expect, it, vi } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import * as subscriptions from './subscriptions';
import viteConfiguration from '../vite.config';
const sdk=vi.hoisted(()=>({recover:vi.fn(),list:vi.fn()}));
vi.mock('@generic-payments/sdk',()=>({registerGenericSubscriptionManager:vi.fn(),recoverCustomerSession:sdk.recover,createGenericPaymentClient:()=>({subscriptions:{list:sdk.list}})}));
afterEach(()=>{vi.unstubAllGlobals();vi.resetAllMocks();});
it('scrubs every query and fragment before subscription return application imports',()=>{
  const file=new URL('../subscription-return.html',import.meta.url);
  const html=readFileSync(existsSync(file)?file:new URL('../index.html',import.meta.url),'utf8');
  const location=new URL('https://shop.test/subscription-return?setup_intent=seti_fixture&setup_intent_client_secret=private-sentinel&subscription_id=foreign#fragment');
  const history={replaceState:(state:unknown,_title:string,path:string)=>{expect(state).toBeNull();location.href=new URL(path,location).href;}};
  for(const script of html.matchAll(/<script([^>]*)>([\s\S]*?)<\/script>/g)){if(script[1]!.includes('type="module"'))break;runInNewContext(script[2]!,{location,history});}
  expect(location.href==='https://shop.test/subscription-return').toBe(true);expect(html).toContain('name="referrer" content="no-referrer"');
});
it('requires explicit recovered Customer authority and makes only an explicit authenticated subscription list read',async()=>{
  expect(subscriptions).toHaveProperty('mountSubscriptionReturn',expect.any(Function));
  const handlers=new Map<string,()=>Promise<void>>();const button={disabled:false,hidden:true,addEventListener:(name:string,callback:()=>Promise<void>)=>handlers.set(name,callback)};
  const status={textContent:''};const data=new Map<string,string>();
  vi.stubGlobal('document',{documentElement:{dataset:{apiBaseUrl:'https://api.test',siteKey:'test-site'}},querySelector:(name:string)=>name==='#subscription-return-status'?status:button});
  vi.stubGlobal('window',{addEventListener:vi.fn()});vi.stubGlobal('sessionStorage',{getItem:(key:string)=>data.get(key)??null,setItem:(key:string,value:string)=>data.set(key,value)});
  const customer={sessionToken:'customer-authority-sentinel'};sdk.recover.mockReturnValue(customer);sdk.list.mockResolvedValue({items:[{billingStatus:'ACTIVE',access:{allowed:false,reason:'EXPIRED'}}],nextCursor:null});
  subscriptions.mountSubscriptionReturn();expect(sdk.list).not.toHaveBeenCalled();
  await handlers.get('click')!();expect(sdk.list).toHaveBeenCalledTimes(1);expect(sdk.list.mock.calls[0]![0].session===customer).toBe(true);
  expect(status.textContent).toContain('ACTIVE');expect(status.textContent).toContain('EXPIRED');expect(status.textContent).not.toContain(customer.sessionToken);expect([...data.values()].some(v=>v.includes(customer.sessionToken))).toBe(false);
});
it('does not read when the recovered Customer session is missing',()=>{
  expect(subscriptions).toHaveProperty('mountSubscriptionReturn',expect.any(Function));const button={hidden:true,disabled:false,addEventListener:vi.fn()},status={textContent:''};
  vi.stubGlobal('document',{documentElement:{dataset:{apiBaseUrl:'https://api.test',siteKey:'test-site'}},querySelector:(name:string)=>name==='#subscription-return-status'?status:button});vi.stubGlobal('window',{addEventListener:vi.fn()});
  sdk.recover.mockReturnValue(null);subscriptions.mountSubscriptionReturn();expect(sdk.list).not.toHaveBeenCalled();expect(button.hidden).toBe(true);expect(status.textContent).toContain('Sign in');
});
it('scrubs subscription return capabilities at the actual Vite middleware entry before downstream logging',()=>{
  const configuration=(viteConfiguration as Function)({command:'serve',mode:'test'});
  const seen:{url?:string;originalUrl?:string}[]=[];
  const server={middlewares:{handle:(request:{url?:string;originalUrl?:string})=>{seen.push({...request});}}};
  const plugin=configuration.plugins.find((p:{name:string})=>p.name==='private-payment-return-request');plugin.configureServer(server);
  for(const path of ['/subscription-return','/subscription-return.html','/%73ubscription-return'])server.middlewares.handle({url:path+'?setup_intent_client_secret=synthetic-sentinel',originalUrl:path+'#synthetic-sentinel'});
  expect(seen.some(r=>r.url?.includes('synthetic-sentinel')||r.originalUrl?.includes('synthetic-sentinel'))).toBe(false);
  expect(configuration.build.rollupOptions.input.subscriptionReturn==='subscription-return.html').toBe(true);
});
