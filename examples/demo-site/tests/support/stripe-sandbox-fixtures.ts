import type {Browser} from '@playwright/test';
import {openStripeSandboxFixture,type SandboxFixture,type SandboxCase} from '../../../../infrastructure/smoke-stage-5a.mjs';
import {runSandboxBrowserStep,rethrowSandboxBrowserFailure,type SandboxBrowserStep} from '../../../../infrastructure/stripe-sandbox-acceptance.mjs';

export {openStripeSandboxFixture};
export type {SandboxFixture};

/** Self-contained browser callback: only public state and owned DOM presence/flags, never markup, URLs or frame contents. */
export function observeSandboxPaymentForm(element:Element) {
  const unavailable={widgetState:'UNAVAILABLE',elementState:'UNAVAILABLE',payState:'UNAVAILABLE'};
  try{
    const state=(element as Element&{state?:unknown}).state,root=element.shadowRoot;
    if(!root)return unavailable;
    // Public finite DOM description only: this callback is serialized by
    // Playwright and carries no SDK lease or binding authority.
    const wrapper=root.querySelector('.payment-element'),slot=wrapper?.querySelector('slot');
    const projected=slot?.assignedElements();
    const host=slot?(projected?.length===1&&projected[0]?.parentElement===element?projected[0]:null):wrapper;
    const pay=root.querySelector<HTMLButtonElement>('button[aria-label="Pay"]');
    return {
      widgetState:['idle','loading','ready','checking','processing','requires_action','succeeded','failed','unknown','expired'].includes(state as string)?state:'UNAVAILABLE',
      elementState:host?(host.querySelector('iframe')?'FRAME':'EMPTY'):'NO_HOST',
      payState:!pay?'ABSENT':pay.hidden?'HIDDEN':pay.disabled?'DISABLED':'ENABLED',
    };
  }catch{return unavailable;}
}

/** Actual Stripe-hosted Element. No route interception or browser-result financial assertions. */
export async function runSandboxCard(browser:Browser,fixture:SandboxFixture,name:SandboxCase):Promise<void> {
  const step=<T>(boundary:SandboxBrowserStep,body:()=>Promise<T>,observeFailure?:()=>Promise<unknown>)=>runSandboxBrowserStep(name,boundary,body,observeFailure);
  if(!await step('BEGIN_BROWSER',()=>fixture.beginBrowser(name)))return;
  const context=await step('NEW_CONTEXT',()=>browser.newContext({serviceWorkers:'block'}));
  let failed=false,primary:unknown;
  try {
    const record=await step('CHECKOUT_SESSION',()=>fixture.browserSession(name)),identity=fixture.identity;
    await step('INSTALL_SESSION',()=>context.addInitScript(({record,origin})=>{
      if(location.origin!==origin)return;
      const key=`generic-payments:v1:${encodeURIComponent(String(record.apiBaseUrl))}:${record.siteKey}:${record.priceKey}`;
      sessionStorage.setItem(key,JSON.stringify(record));
    },{record,origin:identity.endpoints.demo}));
    const page=await step('NEW_PAGE',()=>context.newPage());
    const url=new URL('/',identity.endpoints.demo);url.searchParams.set('api_base_url',identity.endpoints.backend);
    url.searchParams.set('site_key',identity.site.key);url.searchParams.set('price_key',identity.price.key);url.searchParams.set('locale','en');
    await step('NAVIGATE',()=>page.goto(url.href,{timeout:15000}));
    const widget=page.locator('generic-payment-widget').first();
    await step('RESUME',()=>widget.getByRole('button',{name:'Resume existing payment',exact:true}).click({timeout:15000}));
    const frame=widget.frameLocator('iframe[title="Secure payment input frame"]');
    async function card(number:string,retry=false) {
      // Resume starts asynchronous mounting; a click is not provider-readiness proof.
      await step(retry?'RETRY_PAYMENT_FORM_READY':'PAYMENT_FORM_READY',()=>widget.locator('button[aria-label="Pay"]:enabled').waitFor({state:'visible',timeout:15000}),
        ()=>widget.evaluate(observeSandboxPaymentForm,undefined,{timeout:1000}));
      await step(retry?'RETRY_CARD_FRAME':'CARD_FRAME',()=>widget.locator('iframe[title="Secure payment input frame"]').waitFor({state:'visible',timeout:15000}));
      await step(retry?'RETRY_CARD_NUMBER_READY':'CARD_NUMBER_READY',()=>frame.locator('input[name="number"]').waitFor({state:'visible',timeout:15000}));
      await step(retry?'RETRY_CARD_NUMBER':'CARD_NUMBER',()=>frame.locator('input[name="number"]').fill(number,{timeout:15000}));
      await step(retry?'RETRY_CARD_EXPIRY':'CARD_EXPIRY',()=>frame.locator('input[name="expiry"]').fill('1230'));
      await step(retry?'RETRY_CARD_CVC':'CARD_CVC',()=>frame.locator('input[name="cvc"]').fill('123'));
      const postal=frame.locator('input[name="postalCode"]');if(await step(retry?'RETRY_POSTAL_CHECK':'POSTAL_CHECK',()=>postal.isVisible()))await step(retry?'RETRY_POSTAL_FILL':'POSTAL_FILL',()=>postal.fill('10001'));
    }
    // Official https://docs.stripe.com/testing test-only card numbers; never real card data.
    await card(name==='decline'?'4000000000000002':name==='sca'?'4000002500003155':'4242424242424242');
    await step('MARK_PAY',()=>fixture.markPay(name));await step('PAY',()=>widget.getByRole('button',{name:'Pay',exact:true}).click({timeout:15000}));
    if(name==='decline') {
      // Requires verified server decline; no second confirmation from a browser error alone.
      await step('MARK_RETRY',()=>fixture.markPay(name,true));await card('4242424242424242',true);
      await step('RETRY_PAY',()=>widget.getByRole('button',{name:'Pay',exact:true}).click({timeout:15000}));
    }
    if(name==='sca') {
      // Stripe's private lightbox owns the issuer frame; it is not a page-level
      // child. Restrict the wrapper to the official 3DS app, not other Elements.
      // Third-party markup drift remains a manual-check failure, never a mock.
      const wrapper='iframe[name^="__privateStripeFrame"][src^="https://js.stripe.com/v3/three-ds-2-challenge-"]';
      const inner='iframe[name="stripe-challenge-frame"]',issuer='iframe[name="acsFrame"]';
      // These are structural visibility boundaries, not issuer/provider success.
      // Share the old20s bound: never reset it per frame or pass timeout0 (infinite).
      const deadline=performance.now()+20000;
      const remaining=()=>{
        const value=Math.floor(deadline-performance.now());
        if(!Number.isSafeInteger(value)||value<1||value>20000)throw null;
        return value;
      };
      const lightbox=()=>page.frameLocator(wrapper),challenge=()=>lightbox().frameLocator(inner);
      await step('SCA_WRAPPER_FRAME',()=>page.locator(wrapper).waitFor({state:'visible',timeout:remaining()}));
      await step('SCA_CHALLENGE_FRAME',()=>lightbox().locator(inner).waitFor({state:'visible',timeout:remaining()}));
      await step('SCA_ISSUER_FRAME',()=>challenge().locator(issuer).waitFor({state:'visible',timeout:remaining()}));
      await step('SCA_COMPLETE',()=>challenge().frameLocator(issuer).getByRole('button',{name:'Complete',exact:true}).click({timeout:remaining()}));
    }
    await step('SERVER_CONFIRM',()=>fixture.confirm(name));
  }catch(error){failed=true;primary=error;}
  finally{try{await step('CLOSE_CONTEXT',()=>context.close());}catch(error){if(!failed){failed=true;primary=error;}}}
  if(failed)rethrowSandboxBrowserFailure(primary);
}
