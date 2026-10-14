import { expect, test } from "@playwright/test";
import { ownedContainerCommand } from '../../../infrastructure/smoke-stage-4.mjs';
import type { Page } from "@playwright/test";
import { adminLogin, adminUrl, challenge, customerFixture, demoPath, localEmail, signIn } from "./support/customer-api";
import { enterPrivateText } from './support/private-entry';

test.use({ trace: "off", screenshot: "off", video: "off" });

for (const recovery of ["logout", "expiry"] as const) {
  test(`same-document Customer ${recovery} restores email sign-in and permits a fresh login`, async ({ page }, info) => {
    const { api, fixture } = await customerFixture(`recover${recovery}`, info.retry);
    await page.goto(demoPath(api, fixture));
    await page.evaluate(() => { (window as any).recoveryDocument = "original-document"; });
    const first = await signIn(page);
    if (recovery === "logout") {
      await page.getByRole("button", { name: "Log out", exact: true }).click();
      await expect(page.locator("#customer-action-status")).toHaveText("Logged out.");
    } else {
      await api.expireCustomerSession(fixture.siteId, first);
      await page.getByRole("button", { name: "Load my payments" }).click();
      await expect(page.locator("#customer-summary")).toContainText("Session unavailable");
    }
    await expect(page.locator("generic-email-otp").getByRole("textbox", { name: "Email", exact: true })).toBeVisible();
    expect(await page.evaluate(() => Object.keys(sessionStorage).filter(k => k.startsWith("generic-payments:customer:v1:")).length)).toBe(0);
    const second = await signIn(page);
    expect(second).not.toBe(first);
    expect(await page.evaluate(() => (window as any).recoveryDocument)).toBe("original-document");
    await expect(page.getByRole("button", { name: "Pay as Customer", exact: true })).toBeEnabled();
    await page.getByRole("button", { name: "Load my payments" }).click();
    await expect(page.locator("#customer-action-status")).toHaveText("No payments yet.");
    expect(await api.paymentCount(fixture.siteId)).toBe(0);
  });
}

test("signed-out Customer list and detail navigation redirects, then real Admin login opens both", async ({ page, context }, info) => {
  const { api, fixture } = await customerFixture("navigation", info.retry);
  await page.goto(demoPath(api, fixture));
  const customer = await signIn(page);
  const admin = await context.newPage();
  const list = `${adminUrl}/customers?site_id=${fixture.siteId}`;
  const detail = `${adminUrl}/customers/${customer}?site_id=${fixture.siteId}`;
  for (const url of [list, detail]) {
    await admin.goto(url);
    await expect(admin).toHaveURL(/\/login$/);
    await expect(admin.getByRole("button", { name: "Sign in", exact: true })).toBeVisible();
  }
  await adminLogin(admin);
  await admin.goto(list);
  await expect(admin.getByRole("link", { name: customer, exact: true })).toBeVisible();
  await admin.getByRole("link", { name: customer, exact: true }).click();
  await expect(admin.getByRole("region", { name: "Customer summary" })).toContainText("ACTIVE");
});

test("Customer demo mounts native email sign-in and safe guest controls", async ({ page }) => {
  await page.goto("/?api_base_url=http%3A%2F%2F127.0.0.1%3A28080&site_key=customer-demo&price_key=customer-price&locale=en-GB");
  await expect(page.getByRole("heading", { name: "Customer access" })).toBeVisible();
  await expect(page.locator("generic-email-otp").getByRole("textbox", { name: "Email" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Pay as guest" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Load my payments" })).toBeDisabled();
});

test("Mailpit Customer payment, same-tab restore, explicit guest, Admin settings and anonymization retain facts and fail closed",async({page,context},info)=>{
  const {api,fixture,provider}=await customerFixture("customer",info.retry);
  const sinks:{bad:boolean;preflight:number;customer:number;checkout:number;event:string}={bad:false,preflight:0,customer:0,checkout:0,event:""};
  page.on("request",request=>{
    const url=new URL(request.url());if(url.origin!==api.backendUrl)return;
    const headers=request.headers();const path=url.pathname;
    if(headers.cookie||headers["x-xsrf-token"]||url.searchParams.has("token"))sinks.bad=true;
    if(request.method()==="OPTIONS")sinks.preflight++;
    if(path.startsWith("/api/v1/customer/")&&headers.authorization)sinks.customer++;
    if(path.startsWith("/api/v1/public/checkouts/")&&headers.authorization)sinks.checkout++;
    if(path.includes("customer-auth")&&headers.authorization)sinks.bad=true;
  });
  await page.goto(demoPath(api,fixture));
  await page.evaluate(()=>{
    (window as any).safeEvents=[];
    document.addEventListener("customer-authenticated",e=>(window as any).safeEvents.push((e as CustomEvent).detail));
  });
  const customerId=await signIn(page);
  const safe=await page.evaluate(()=>{
    const record=Object.keys(sessionStorage).filter(k=>k.startsWith("generic-payments:customer:v1:"));
    if(record.length!==1)return false;
    const session=JSON.parse(sessionStorage.getItem(record[0]!)!);
    const event=JSON.stringify((window as any).safeEvents);
    return event.includes(session.customer.id)&&!event.includes(session.sessionToken)&&!document.documentElement.outerHTML.includes(session.sessionToken)&&
      !location.href.includes(session.sessionToken)&&localStorage.length===0;
  });expect(safe,"only sessionStorage holds opaque Customer authority; events/DOM/URL credential free").toBe(true);
  await page.getByRole("button",{name:"Pay as Customer",exact:true}).click();
  await expect(page.locator("#customer-action-status")).toContainText("Payment ");
  const payment=await api.waitForSinglePayment(fixture.siteId);
  await page.getByRole("button",{name:"Load my payments"}).click();
  await expect(page.locator("#customer-payments")).toContainText(payment.payment_id);
  await page.reload();
  await expect(page.locator("#customer-summary")).toContainText(customerId);
  await page.getByRole("button",{name:"Load my payments"}).click();
  await expect(page.locator("#customer-payments")).toContainText(payment.payment_id);
  await page.getByRole("button",{name:"Pay as guest",exact:true}).click();
  await expect(page.locator("#customer-action-status")).toContainText("Payment ");
  await expect.poll(()=>api.paymentCount(fixture.siteId)).toBe(2);
  const historyCount=await page.evaluate(async({base})=>{
    const key=Object.keys(sessionStorage).find(k=>k.startsWith("generic-payments:customer:v1:"))!;
    const s=JSON.parse(sessionStorage.getItem(key)!);
    const r=await fetch(base+"/api/v1/customer/payments?limit=20",{headers:{Authorization:"Bearer "+s.sessionToken}});
    return (await r.json()).items.length;
  },{base:api.backendUrl});expect(historyCount).toBe(1);
  const before=await api.details(payment.payment_id);
  const admin=await context.newPage();await adminLogin(admin);
  await admin.goto(`${adminUrl}/sites/${fixture.siteId}/authentication`);
  await expect(admin.getByRole("heading",{name:/Site authentication/})).toBeVisible();
  await admin.getByRole("button",{name:"Edit local-email",exact:true}).click();
  await expect(admin.getByLabel("Sender reference")).toBeFocused();
  await api.customerRequest("PUT",`/api/v1/admin/sites/${fixture.siteId}/identity-providers/${provider.id}`,
    {provider_key:"local-email",mode:"EMAIL_OTP",sender_ref:"local-default",template_key:"otp-default",expected_version:provider.version});
  await admin.getByLabel("Sender reference").fill("local-default");
  await admin.getByLabel("Template key").fill("otp-default");
  await admin.getByRole("button",{name:"Save provider",exact:true}).click();
  await expect(admin.getByRole("alert").filter({hasText:"Refresh providers before editing"})).toBeVisible();
  await admin.getByRole("button",{name:"Refresh providers",exact:true}).click();
  await admin.getByRole("button",{name:"Edit local-email",exact:true}).click();
  await admin.getByLabel("Sender reference").fill("local-default");
  await admin.getByLabel("Template key").fill("otp-default");
  await admin.getByRole("button",{name:"Save provider",exact:true}).click();
  await expect(admin.getByRole("status").filter({hasText:"Provider saved."})).toBeVisible();
  await admin.getByRole("button",{name:"Disable local-email",exact:true}).click();
  await expect(admin.getByRole("status").filter({hasText:"Provider disabled."})).toBeVisible();
  await expect(admin.getByRole("button",{name:"Edit local-email",exact:true})).toHaveCount(0);
  await expect(admin.getByRole("button",{name:"Disable local-email",exact:true})).toHaveCount(0);
  await api.customerRequest("POST",`/api/v1/admin/sites/${fixture.siteId}/identity-providers`,
    {provider_key:"replacement-email",mode:"EMAIL_OTP",sender_ref:"local-default",template_key:"otp-default"});
  await admin.goto(`${adminUrl}/customers?site_id=${fixture.siteId}`);
  await expect(admin.getByRole("link",{name:customerId,exact:true})).toBeVisible();
  await admin.getByRole("link",{name:customerId,exact:true}).click();
  const trigger=admin.getByRole("button",{name:"Anonymize customer",exact:true});await trigger.click();
  const dialog=admin.getByRole("dialog",{name:"Anonymize customer",exact:true});
  await expect(dialog.getByRole("button",{name:"Cancel",exact:true})).toBeFocused();
  await dialog.getByRole("button",{name:"Cancel",exact:true}).press("Shift+Tab");
  await expect(dialog.getByLabel("Customer UUID confirmation")).toBeFocused();
  await dialog.getByLabel("Customer UUID confirmation").press("Escape");await expect(trigger).toBeFocused();
  await trigger.click();await dialog.getByLabel("Customer UUID confirmation").fill(" "+customerId);
  await expect(dialog.getByRole("button",{name:"Confirm anonymization",exact:true})).toBeDisabled();
  await dialog.getByLabel("Customer UUID confirmation").fill(customerId);
  await dialog.getByRole("button",{name:"Confirm anonymization",exact:true}).click();
  await expect(admin.getByRole("status").filter({hasText:"Financial facts retained"})).toBeVisible();
  await expect(admin.locator("[data-customer-heading]")).toBeFocused();
  await expect(admin.getByRole("region",{name:"Customer summary"})).toContainText("ANONYMIZED");
  const after=await api.details(payment.payment_id);expect(after.payment).toEqual(before.payment);
  await page.getByRole("button",{name:"Load my payments"}).click();
  await expect(page.locator("#customer-action-status")).toContainText("Unable to complete");
  await expect(page.getByRole("button",{name:"Pay as Customer",exact:true})).toBeDisabled();
  expect(await api.paymentCount(fixture.siteId)).toBe(2);
  expect(sinks.bad,"browser authorization stays separated from Admin cookies/CSRF and auth bodies").toBe(false);
  expect(sinks.customer).toBeGreaterThan(0);expect(sinks.checkout).toBeGreaterThan(0);
});

test("Customer logout removes same-tab authority; explicit expired record is removed without guest checkout",async({page},info)=>{
  const {api,fixture}=await customerFixture("expiry",info.retry);
  await page.goto(demoPath(api,fixture));await signIn(page);
  await page.getByRole("button",{name:"Log out",exact:true}).click();
  await expect(page.locator("#customer-action-status")).toHaveText("Logged out.");
  expect(await page.evaluate(()=>Object.keys(sessionStorage).filter(k=>k.startsWith("generic-payments:customer:v1:")).length)).toBe(0);
  await page.reload();await expect(page.getByRole("button",{name:"Pay as Customer",exact:true})).toBeDisabled();
  const expiringCustomer=await signIn(page);
  await api.expireCustomerSession(fixture.siteId,expiringCustomer);
  await page.getByRole("button",{name:"Pay as Customer",exact:true}).click();
  await expect(page.locator("#customer-action-status")).toContainText("Unable to complete");
  await expect(page.getByRole("button",{name:"Pay as Customer",exact:true})).toBeDisabled();
  expect(await page.evaluate(()=>Object.keys(sessionStorage).filter(k=>k.startsWith("generic-payments:customer:v1:")).length)).toBe(0);
  await page.reload();await signIn(page);
  await page.evaluate(()=>{for(const k of Object.keys(sessionStorage))if(k.startsWith("generic-payments:customer:v1:")){const r=JSON.parse(sessionStorage.getItem(k)!);r.expiresAt=new Date(Date.now()-1000).toISOString();sessionStorage.setItem(k,JSON.stringify(r));}});
  await page.reload();await expect(page.getByRole("button",{name:"Pay as Customer",exact:true})).toBeDisabled();
  expect(await page.evaluate(()=>Object.keys(sessionStorage).filter(k=>k.startsWith("generic-payments:customer:v1:")).length)).toBe(0);
  expect(await api.paymentCount(fixture.siteId)).toBe(0);
});

test("removed same-tab Customer authority cannot silently create a guest Payment",async({page},info)=>{
  const {api,fixture}=await customerFixture("storage",info.retry);
  await page.goto(demoPath(api,fixture));await signIn(page);
  await page.evaluate(()=>{for(const k of Object.keys(sessionStorage))if(k.startsWith("generic-payments:customer:v1:"))sessionStorage.removeItem(k);});
  await page.getByRole("button",{name:"Pay as Customer",exact:true}).click();
  await expect(page.locator("#customer-action-status")).toContainText("Unable to complete");
  await expect(page.getByRole("button",{name:"Pay as Customer",exact:true})).toBeDisabled();
  expect(await api.paymentCount(fixture.siteId)).toBe(0);
});

// Hold only the first matching response, after the real backend has handled it.
// No authentication response/body is replaced or retained as a diagnostic artifact.
async function heldCustomerResponse(page:Page,path:string,method:string) {
  let release!:()=>void;const gate=new Promise<void>(resolve=>release=resolve);
  let reached!:()=>void;const started=new Promise<void>(resolve=>reached=resolve);
  let taken=false;
  let backendStatus=0;
  let heldRequest:import("@playwright/test").Request;
  const pattern=`**/api/v1/${path.startsWith("public/")?path:`customer/${path}`}`;
  const handler=async(route:import("@playwright/test").Route)=>{
    if(taken||route.request().method()!==method){await route.continue();return;}
    taken=true;heldRequest=route.request();
    const response=await route.fetch();backendStatus=response.status();
    const responseHeaders=response.headers();
    console.log(`Held response safety: Origin present=${Boolean((await heldRequest.allHeaders()).origin)}; exact allowed Origin=${responseHeaders["access-control-allow-origin"]===new URL(page.url()).origin}; encoded=${Boolean(responseHeaders["content-encoding"])}; length declared=${Boolean(responseHeaders["content-length"])}`);
    reached();await gate;
    await route.fulfill({response});
  };
  await page.route(pattern,handler);
  return {started,release:async()=>{
    // An unread 204 body can be cancelled after fetch succeeds; distinguish response from terminal events.
    const completed=new Promise<string>(resolve=>{
      const finish=(request:import("@playwright/test").Request)=>{if(request===heldRequest){cleanup();resolve("finished");}};
      const fail=(request:import("@playwright/test").Request)=>{if(request===heldRequest){cleanup();resolve(request.failure()?.errorText==="net::ERR_ABORTED"?"aborted after response":"failed terminal event");}};
      const cleanup=()=>{page.off("requestfinished",finish);page.off("requestfailed",fail);};
      page.on("requestfinished",finish);page.on("requestfailed",fail);
    });
    const delivered=page.waitForEvent("response",{predicate:response=>response.request()===heldRequest,timeout:5000});
    release();const browserResponse=await delivered;const terminal=await completed;await page.unroute(pattern,handler);
    expect(browserResponse.status(),"held real response status reaches browser").toBe(backendStatus);
    console.log(`Held real action: backend HTTP${backendStatus}; browser response HTTP${browserResponse.status()}; terminal ${terminal}; credential values omitted`);
    await page.evaluate(()=>new Promise<void>(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>resolve()))));
  }};
}

const obsoleteActions=[
  {journey:"racecheckout",name:"checkout",button:"Pay as Customer",path:"me",method:"GET",expired:false},
  {journey:"raceconfirm",name:"checkout confirmation",button:"Pay as Customer",path:"public/checkouts/*/confirm",method:"POST",expired:false},
  {journey:"racehistory",name:"history success",button:"Load my payments",path:"payments?*",method:"GET",expired:false},
  {journey:"racelogout",name:"logout success",button:"Log out",path:"session",method:"DELETE",expired:false},
  {journey:"racehistoryerror",name:"history rejection",button:"Load my payments",path:"payments?*",method:"GET",expired:true},
  {journey:"racelogouterror",name:"logout rejection",button:"Log out",path:"session",method:"DELETE",expired:true},
] as const;

for(const operation of obsoleteActions)test(`obsolete Customer ${operation.name} cannot mutate newer same-tab authority`,async({page},info)=>{
  const {api,fixture}=await customerFixture(operation.journey,info.retry);
  await page.goto(demoPath(api,fixture));const customerA=await signIn(page);
  await page.getByRole("button",{name:"Pay as Customer",exact:true}).click();
  await expect(page.locator("#customer-action-status")).toContainText("Payment ");
  const paymentA=await api.waitForSinglePayment(fixture.siteId);
  await page.reload();await expect(page.locator("#customer-summary")).toContainText(customerA);
  // This is the actual reachability: restored A and an unauthenticated email component.
  await expect(page.locator("generic-email-otp").getByRole("textbox",{name:"Email",exact:true})).toBeVisible();
  await page.getByRole("button",{name:"Load my payments",exact:true}).click();
  await expect(page.locator("#customer-payments")).toContainText(paymentA.payment_id);
  if(operation.expired)await api.expireCustomerSession(fixture.siteId,customerA);
  const held=await heldCustomerResponse(page,operation.path,operation.method);
  await page.getByRole("button",{name:operation.button,exact:true}).click();await held.started;
  const customerB=await signIn(page);expect(customerB!==customerA).toBe(true);
  await expect(page.locator("#customer-summary")).toContainText(customerB);
  const tokenB=await page.evaluate(()=>{
    const key=Object.keys(sessionStorage).find(k=>k.startsWith("generic-payments:customer:v1:"))!;
    return JSON.parse(sessionStorage.getItem(key)!).sessionToken as string;
  });
  // Soft assertions preserve the first RED's distinct pre-release and completion failures.
  await expect.soft(page.locator("#customer-payments li")).toHaveCount(0,{timeout:1000});
  await expect.soft(page.getByRole("button",{name:"Pay as Customer",exact:true})).toBeEnabled({timeout:1000});
  await held.release();
  expect(await page.evaluate(({tokenB,customerB})=>{
    const key=Object.keys(sessionStorage).find(k=>k.startsWith("generic-payments:customer:v1:"));
    const current=key?JSON.parse(sessionStorage.getItem(key)!):null;
    return current?.sessionToken===tokenB&&current?.customer.id===customerB;
  },{tokenB,customerB}),"newer exact SDK session survives obsolete completion").toBe(true);
  await expect.soft(page.locator("#customer-summary")).toContainText(customerB,{timeout:1000});
  await expect.soft(page.locator("generic-email-otp").getByRole("status")).toHaveText("Signed in.",{timeout:1000});
  await expect.soft(page.locator("generic-email-otp").getByRole("textbox",{name:"Email",exact:true})).toHaveCount(0);
  await expect.soft(page.locator("#customer-payments li")).toHaveCount(0,{timeout:1000});
  await expect.soft(page.locator("#customer-action-status")).toHaveText("",{timeout:1000});
  for(const name of ["Pay as Customer","Load my payments","Log out"])await expect.soft(page.getByRole("button",{name,exact:true})).toBeEnabled({timeout:1000});
  const ownedPaymentCount=operation.method==="POST"?2:1;
  expect(await api.paymentCount(fixture.siteId),"obsolete action never creates a guest Payment").toBe(ownedPaymentCount);
  // Do not let a broken RED spend the whole action timeout; soft failures above retain its cause.
  if(await page.getByRole("button",{name:"Pay as Customer",exact:true}).isDisabled())return;
  await page.getByRole("button",{name:"Load my payments",exact:true}).click();
  await expect(page.locator("#customer-action-status")).toHaveText("No payments yet.");
  await expect(page.locator("#customer-payments li")).toHaveCount(0);
  await page.getByRole("button",{name:"Pay as Customer",exact:true}).click();
  await expect(page.locator("#customer-action-status")).toContainText("Payment ");
  await page.getByRole("button",{name:"Load my payments",exact:true}).click();
  await expect(page.locator("#customer-payments li")).toHaveCount(1);
  await expect(page.locator("#customer-payments")).not.toContainText(paymentA.payment_id);
  expect(await api.paymentCount(fixture.siteId)).toBe(ownedPaymentCount+1);
});

test("obsolete Customer finally cannot clear a newer action busy state",async({page},info)=>{
  const {api,fixture}=await customerFixture("racebusy",info.retry);
  await page.goto(demoPath(api,fixture));await signIn(page);await page.reload();
  await expect(page.getByRole("button",{name:"Load my payments",exact:true})).toBeEnabled();
  const old=await heldCustomerResponse(page,"session","DELETE");
  await page.getByRole("button",{name:"Log out",exact:true}).click();await old.started;
  const customerB=await signIn(page);await expect(page.locator("#customer-summary")).toContainText(customerB);
  await expect(page.getByRole("button",{name:"Load my payments",exact:true})).toBeEnabled();
  const newer=await heldCustomerResponse(page,"payments?*","GET");
  await page.getByRole("button",{name:"Load my payments",exact:true}).click();await newer.started;
  // Release only A; leave B's real history response pending.
  await old.release();
  await expect(page.locator("#customer-summary")).toContainText(customerB);
  await expect(page.locator("#customer-action-status")).toHaveText("Please wait.");
  for(const name of ["Pay as Customer","Pay as guest","Load my payments","Log out"])await expect(page.getByRole("button",{name,exact:true})).toBeDisabled();
  await newer.release();
  await expect(page.locator("#customer-action-status")).toHaveText("No payments yet.");
  await expect(page.getByRole("button",{name:"Pay as Customer",exact:true})).toBeEnabled();
  expect(await api.paymentCount(fixture.siteId)).toBe(0);
});

test("actual browser CORS preflight rejects cross-Site, exact-Origin and separated credentials",async({page},info)=>{
  const {api,fixture}=await customerFixture("scope",info.retry);
  const other=await customerFixture("second",info.retry);
  await page.goto(demoPath(api,fixture));await signIn(page);
  const result=await page.evaluate(async({base,second,price,own,ownPrice})=>{
    const key=Object.keys(sessionStorage).find(k=>k.startsWith("generic-payments:customer:v1:"))!;const s=JSON.parse(sessionStorage.getItem(key)!);
    const cross=await fetch(`${base}/api/v1/public/sites/${second}/checkouts`,{method:"POST",headers:{"Content-Type":"application/json",Authorization:"Bearer "+s.sessionToken,"Idempotency-Key":crypto.randomUUID()},body:JSON.stringify({price_key:price})});
    // A real checkout admits preflight; an unknown UUID fails routing before credential separation.
    const created=await fetch(`${base}/api/v1/public/sites/${own}/checkouts`,{method:"POST",headers:{"Content-Type":"application/json",Authorization:"Bearer "+s.sessionToken,"Idempotency-Key":crypto.randomUUID()},body:JSON.stringify({price_key:ownPrice})});
    if(created.status!==201)throw new Error("Real checkout fixture unavailable");
    const checkout=await created.json();
    const customerAsCheckout=await fetch(`${base}/api/v1/public/checkouts/${checkout.checkout_id}`,{headers:{Authorization:"Bearer "+s.sessionToken}});
    return {cross:cross.status,separated:customerAsCheckout.status};
  },{base:api.backendUrl,second:other.fixture.siteKey,price:other.fixture.priceKey,own:fixture.siteKey,ownPrice:fixture.priceKey});
  expect(result).toEqual({cross:401,separated:401});
  expect(await api.paymentCount(other.fixture.siteId)).toBe(0);
  // A real new Origin cannot read even when a credential is explicitly carried internally by the test.
  const denied=await page.evaluate(async({base})=>{
    const key=Object.keys(sessionStorage).find(k=>k.startsWith("generic-payments:customer:v1:"))!;const s=JSON.parse(sessionStorage.getItem(key)!);
    (window as any).originTestToken=s.sessionToken;
    return Boolean(s.sessionToken);
  },{base:api.backendUrl});expect(denied).toBe(true);
  const token=await page.evaluate(()=>{const t=(window as any).originTestToken;delete (window as any).originTestToken;return t as string;});
  const deniedOrigin=await page.context().newPage();await deniedOrigin.goto("http://localhost:4173");
  const blocked=await deniedOrigin.evaluate(async({base,token})=>{try {const r=await fetch(base+"/api/v1/customer/me",{headers:{Authorization:"Bearer "+token}});return !r.ok;}catch{return true;}},{base:api.backendUrl,token});
  expect(blocked).toBe(true);await deniedOrigin.close();
});

for(const mutation of ["disconnect","site","api"] as const)test(`pending real OTP verification followed by ${mutation} has no late storage or event`,async({page},info)=>{
  const {api,fixture}=await customerFixture(mutation==="disconnect"?"pending":mutation==="site"?"admin":"unavailable",info.retry);
  await page.goto(demoPath(api,fixture));const code=await challenge(page,localEmail());
  await page.evaluate(()=>{(window as any).lateEvents=0;document.addEventListener("customer-authenticated",()=>{(window as any).lateEvents++;});});
  let release!:()=>void;const gate=new Promise<void>(r=>release=r);let reached!:()=>void;const started=new Promise<void>(r=>reached=r);
  await page.route("**/customer-auth/email-otp/challenges/*/verify",async route=>{
    const response=await route.fetch();reached();await gate;try {await route.fulfill({response});}catch { /* browser abort after real backend verification */ }
  });
  await enterPrivateText(page.locator("generic-email-otp").getByRole("textbox",{name:"Six-digit code"}),code);
  await page.locator("generic-email-otp").getByRole("button",{name:"Verify code",exact:true}).click();await started;
  await page.evaluate(mutation=>{const e=document.querySelector("generic-email-otp")!;if(mutation==="disconnect")e.remove();else e.setAttribute(mutation==="site"?"site-key":"api-base-url",mutation==="site"?"changed-site":"http://127.0.0.1:28081");},mutation);
  release();await page.unrouteAll({behavior:"wait"});
  await expect.poll(()=>page.evaluate(()=>({events:(window as any).lateEvents,records:Object.keys(sessionStorage).filter(k=>k.startsWith("generic-payments:customer:v1:")).length}))).toEqual({events:0,records:0});
});

for(const unavailable of ["configuration","delivery"] as const)test(`real OTP ${unavailable} unavailability is uniform for known and unknown addresses`,async({page},info)=>{
  const {api,fixture,provider}=await customerFixture(unavailable==="configuration"?"config":"delivery",info.retry);
  const knownEmail=localEmail();await page.goto(demoPath(api,fixture));await signIn(page,knownEmail);
  await page.getByRole("button",{name:"Log out",exact:true}).click();
  await expect(page.locator("#customer-action-status")).toHaveText("Logged out.");
  let unavailableVersion:number|undefined;
  if(unavailable==="configuration")unavailableVersion=(await api.customerRequest<{version:number}>("PUT",`/api/v1/admin/sites/${fixture.siteId}/identity-providers/${provider.id}`,
    {provider_key:"local-email",mode:"EMAIL_OTP",sender_ref:"unavailable-local-sender",template_key:"otp-default",expected_version:provider.version})).version;
  let stopped=false;
  try {
    if(unavailable==="delivery"){await ownedContainerCommand(process.env,'stop');stopped=true;}
    for(const email of [knownEmail,localEmail()]) {
      await page.reload();const component=page.locator("generic-email-otp");
      const received=page.waitForResponse(r=>r.url().endsWith("/customer-auth/email-otp/challenges")&&r.request().method()==="POST");
      await enterPrivateText(component.getByRole("textbox",{name:"Email",exact:true}),email);
      await component.getByRole("textbox",{name:"Email",exact:true}).press("Enter");
      const response=await received;expect(response.status()).toBe(unavailable==="delivery"?202:503);
      if(unavailable==="delivery") {
        expect(Object.keys(await response.json()).sort()).toEqual(["challenge_id","expires_at"]);
        await expect(component.getByRole("status")).toHaveText("If available, use the code sent to your email.");
        await expect(component.getByRole("textbox",{name:"Six-digit code"})).toBeFocused();
      } else await expect(component.getByRole("alert")).toHaveText("Unable to complete sign in. Please try again.");
      expect(await component.evaluate((e,email)=>!e.shadowRoot!.innerHTML.includes(email)&&!location.href.includes(email),email)).toBe(true);
      expect(await api.paymentCount(fixture.siteId)).toBe(0);
    }
  } finally {
    if(stopped)await ownedContainerCommand(process.env,'start');
    if(unavailableVersion!==undefined)await api.customerRequest("PUT",`/api/v1/admin/sites/${fixture.siteId}/identity-providers/${provider.id}`,
      {provider_key:"local-email",mode:"EMAIL_OTP",sender_ref:"local-default",template_key:"otp-default",expected_version:unavailableVersion});
  }
});
