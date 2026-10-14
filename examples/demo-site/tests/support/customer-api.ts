import { randomUUID } from "node:crypto";
import type { Page } from "@playwright/test";
import { expect } from "@playwright/test";
import { AdminApi, type ProvisionedCheckout } from "./admin-api";
import { enterPrivateText } from './private-entry';

export const mailpitUrl=process.env.PAYMENT_E2E_MAILPIT_URL??"http://127.0.0.1:28025";
export const adminUrl=process.env.PAYMENT_E2E_ADMIN_URL??"http://127.0.0.1:23000";
export async function customerFixture(journey:string,retry:number,sender="local-default") {
  const api=await AdminApi.login();
  const fixture=await api.provision(journey,retry,["SITE_TOKEN","OIDC_TOKEN","EMAIL_OTP"]);
  const provider=await api.customerRequest<{id:string;version:number}>("POST",`/api/v1/admin/sites/${fixture.siteId}/identity-providers`,
    {provider_key:"local-email",mode:"EMAIL_OTP",sender_ref:sender,template_key:"otp-default"});
  return {api,fixture,provider};
}
export function demoPath(api:AdminApi,fixture:ProvisionedCheckout):string {
  return "/?"+new URLSearchParams({api_base_url:api.backendUrl,site_key:fixture.siteKey,price_key:fixture.priceKey,locale:"en-GB"});
}
export function localEmail():string {return `browser-${randomUUID()}@example.test`;}
async function mailJson(path:string):Promise<any> {
  const r=await fetch(mailpitUrl+path,{redirect:"error",signal:AbortSignal.timeout(3000)});
  if(!r.ok)throw new Error("Local mail API unavailable");
  const reader=r.body!.getReader();const chunks:Uint8Array[]=[];let length=0;
  try {for(;;){const next=await reader.read();if(next.done)break;length+=next.value.length;if(length>262144){await reader.cancel();throw new Error("Local mail response too large");}chunks.push(next.value);}}
  finally {reader.releaseLock();}
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}
export async function mailCode(email:string):Promise<string> {
  let code:string|undefined;
  await expect.poll(async()=>{
    const listing=await mailJson("/api/v1/messages");
    const message=listing.messages.find((m:any)=>m.To?.some((to:any)=>to.Address===email));
    if(message)code=/\b([0-9]{6})\b/.exec((await mailJson(`/api/v1/message/${message.ID}`)).Text??"")?.[1];
    return Boolean(code);
  },{message:"actual SMTP delivery available through local Mailpit",timeout:15000}).toBe(true);
  return code!;
}
export async function challenge(page:Page,email:string):Promise<string> {
  const component=page.locator("generic-email-otp");
  await enterPrivateText(component.getByRole("textbox",{name:"Email",exact:true}),email);
  await component.getByRole("textbox",{name:"Email",exact:true}).press("Enter");
  await expect(component.getByRole("status")).toHaveText("If available, use the code sent to your email.");
  await expect(component.getByRole("textbox",{name:"Six-digit code"})).toBeFocused();
  return mailCode(email);
}
export async function signIn(page:Page,email=localEmail()):Promise<string> {
  const code=await challenge(page,email);
  const component=page.locator("generic-email-otp");
  await enterPrivateText(component.getByRole("textbox",{name:"Six-digit code"}),code);
  await component.getByRole("textbox",{name:"Six-digit code"}).press("Enter");
  await expect(component.getByRole("status")).toHaveText("Signed in.");
  await expect(component.getByRole("status")).toBeFocused();
  await expect(page.locator("#customer-summary")).toContainText("ACTIVE");
  const safe=await page.evaluate(({email,code})=>{
    const component=document.querySelector("generic-email-otp")!;
    const text=document.documentElement.outerHTML+(component.shadowRoot?.innerHTML??"");
    return !text.includes(email)&&!text.includes(code)&&!location.href.includes(email)&&!location.href.includes(code);
  },{email,code});
  expect(safe,"ephemeral email/OTP absent from rendered component and URL after verification").toBe(true);
  return page.evaluate(()=>{
    const key=Object.keys(sessionStorage).find(k=>k.startsWith("generic-payments:customer:v1:"));
    return key?JSON.parse(sessionStorage.getItem(key)!).customer.id:"";
  });
}
export async function adminLogin(page:Page):Promise<void> {
  await page.goto(adminUrl+"/login");
  await enterPrivateText(page.getByLabel("Username",{exact:true}),process.env.PAYMENT_E2E_ADMIN_USERNAME??"task12-admin");
  await enterPrivateText(page.getByLabel("Password",{exact:true}),process.env.PAYMENT_E2E_ADMIN_PASSWORD??"task12-admin-password");
  await page.getByRole("button",{name:"Sign in",exact:true}).click();
  await expect(page).toHaveURL(/\/sites$/);
}
