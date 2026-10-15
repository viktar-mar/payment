import type {Browser} from '@playwright/test';
import {openStripeSandboxFixture,type SandboxFixture,type SandboxCase} from '../../../../infrastructure/smoke-stage-5a.mjs';

export {openStripeSandboxFixture};
export type {SandboxFixture};

/** Actual Stripe-hosted Element. No route interception or browser-result financial assertions. */
export async function runSandboxCard(browser:Browser,fixture:SandboxFixture,name:SandboxCase):Promise<void> {
  if(!await fixture.beginBrowser(name))return;
  const context=await browser.newContext({serviceWorkers:'block'});
  try {
    const record=await fixture.browserSession(name),identity=fixture.identity;
    await context.addInitScript(({record,origin})=>{
      if(location.origin!==origin)return;
      const key=`generic-payments:v1:${encodeURIComponent(String(record.apiBaseUrl))}:${record.siteKey}:${record.priceKey}`;
      sessionStorage.setItem(key,JSON.stringify(record));
    },{record,origin:identity.endpoints.demo});
    const page=await context.newPage();
    const url=new URL('/',identity.endpoints.demo);url.searchParams.set('api_base_url',identity.endpoints.backend);
    url.searchParams.set('site_key',identity.site.key);url.searchParams.set('price_key',identity.price.key);url.searchParams.set('locale','en');
    await page.goto(url.href,{timeout:15000});
    const widget=page.locator('generic-payment-widget').first();
    await widget.getByRole('button',{name:'Resume existing payment',exact:true}).click({timeout:15000});
    const frame=widget.frameLocator('iframe[title="Secure payment input frame"]');
    async function card(number:string) {
      await frame.locator('input[name="number"]').fill(number,{timeout:15000});
      await frame.locator('input[name="expiry"]').fill('1230');
      await frame.locator('input[name="cvc"]').fill('123');
      const postal=frame.locator('input[name="postalCode"]');if(await postal.isVisible())await postal.fill('10001');
    }
    // Official https://docs.stripe.com/testing test-only card numbers; never real card data.
    await card(name==='decline'?'4000000000000002':name==='sca'?'4000002500003155':'4242424242424242');
    await fixture.markPay(name);await widget.getByRole('button',{name:'Pay',exact:true}).click({timeout:15000});
    if(name==='decline') {
      // Requires verified server decline; no second confirmation from a browser error alone.
      await fixture.markPay(name,true);await card('4242424242424242');
      await widget.getByRole('button',{name:'Pay',exact:true}).click({timeout:15000});
    }
    if(name==='sca') {
      // Third-party selectors may change: timeout is a manual-check failure, never replaced by a mock.
      await page.frameLocator('iframe[name="stripe-challenge-frame"]').frameLocator('iframe[name="acsFrame"]')
        .getByRole('button',{name:'Complete',exact:true}).click({timeout:20000});
    }
    await fixture.confirm(name);
  }catch{throw new Error('stripe_sandbox_MANUAL_CHECK_BROWSER');}
  finally{await context.close();}
}
