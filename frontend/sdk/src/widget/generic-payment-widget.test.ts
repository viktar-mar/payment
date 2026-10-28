import { it, expect, vi } from "vitest";
vi.mock('../providers/stripe-loader.js', () => ({ loadStripeClient: vi.fn() }));
import { stripeUiProvider, stripeWire, stripeReadWire, stripeCapability } from '../test/stripe-ui-fixtures.js';
import { createGenericPaymentClient } from '../client.js';
import { authInput, customerWire } from '../test/customer-fixtures.js';
import userEvent from "@testing-library/user-event";
import { defineGenericPaymentWidget, GenericPaymentWidget } from "./generic-payment-widget.js";
import { wire, readWire, json, token } from "../test/fixtures.js";
import { loadStripeClient } from '../providers/stripe-loader.js';
import * as sandboxDriver from '../../../../examples/demo-site/tests/support/stripe-sandbox-fixtures.ts';
import { armManualPayment } from '../diagnostics/manual-payment-authorization.js';
defineGenericPaymentWidget();
function mount() { const el = document.createElement("generic-payment-widget") as GenericPaymentWidget; el.siteKey = "test-site"; el.priceKey = "lifetime"; el.apiBaseUrl = "https://api.test"; document.body.append(el); return el; }
const button = (el: GenericPaymentWidget) => el.shadowRoot!.querySelector("button")!;
const status = (el: GenericPaymentWidget) => el.shadowRoot!.querySelector('[role="status"]')!;
function stripeApi(backend = 'CREATED') {
  const requests: Request[] = [];
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return json(url.endsWith('/client-session') ? stripeCapability() : init.method === 'GET' ? stripeReadWire(backend) : stripeWire(), url.endsWith('/checkouts') ? 201 : 200); });
  return requests;
}
async function stripeReady(el: GenericPaymentWidget, p: ReturnType<typeof stripeUiProvider>) {
  button(el).click(); await vi.waitFor(() => expect(el.querySelector('iframe')).toBe(p.frame)); p.emit('ready'); await until(el, 'ready');
}
async function until(el: GenericPaymentWidget, state: string) { await vi.waitFor(() => expect(el.state).toBe(state)); }
// Break: treating the initial recovery read/Resume as a rearm blocks the single manual payment flow.
it('resumes the existing Catalog checkout under one manual lease and authorizes only the later Pay', async () => {
  const p = stripeUiProvider(), requests = stripeApi('CREATED');
  await createGenericPaymentClient({ apiBaseUrl: 'https://api.test' }).createCatalogCheckout({ siteKey: 'test-site', priceKey: 'lifetime' });
  const el = new GenericPaymentWidget(); el.siteKey = 'test-site'; el.priceKey = 'lifetime'; el.apiBaseUrl = 'https://api.test';
  const authorize = vi.fn(async () => true as const), lease = armManualPayment(el, { deadline: Date.now() + 60000, authorize });
  try {
    document.body.append(el); await until(el, 'checking'); const resume = el.shadowRoot!.querySelectorAll('button')[1]!;
    await vi.waitFor(() => expect(resume).toBeEnabled()); resume.click();
    await vi.waitFor(() => expect(el.querySelector('iframe')).toBe(p.frame));
    expect(authorize).not.toHaveBeenCalled(); expect(p.stripe.confirmPayment).toHaveBeenCalledTimes(0);
    p.emit('ready'); await until(el, 'ready'); button(el).click(); await until(el, 'checking');
    expect(authorize).toHaveBeenCalledTimes(1); expect(p.stripe.confirmPayment).toHaveBeenCalledTimes(1);
    expect(requests.map(request => request.method)).toEqual(['POST', 'GET', 'POST', 'GET']);
    expect(requests.filter(request => request.url.endsWith('/sites/test-site/checkouts'))).toHaveLength(1);
  } finally { lease.close(); el.remove(); }
});
// Break: probing text, capabilities or child-frame content leaks data; projecting the wrong local lifecycle hides the failing boundary.
const observeForm = (el: Element) => (sandboxDriver as unknown as {observeSandboxPaymentForm?:(el:Element)=>unknown}).observeSandboxPaymentForm?.(el);
it.each(['capability-pending','capability-rejected','loader-pending','loader-rejected','elements-throw','mount-throw','frame-pending','loaderror','ready'])('safe form observation characterizes resumed initialization %s without confirming or recreating',async stage=>{
  const p=stripeUiProvider(),requests=stripeApi();
  await createGenericPaymentClient({apiBaseUrl:'https://api.test'}).createCatalogCheckout({siteKey:'test-site',priceKey:'lifetime'});
  const el=mount();await until(el,'checking');const resume=el.shadowRoot!.querySelectorAll('button')[1];await vi.waitFor(()=>expect(resume).toBeEnabled());
  let release=()=>{};
  if(stage.startsWith('capability-')){
    const original=globalThis.fetch;
    vi.stubGlobal('fetch',async(url:string,init:RequestInit)=>{
      if(!url.endsWith('/client-session'))return original(url,init);
      requests.push(new Request(url,init));
      return stage==='capability-rejected'?json({code:'REQUEST_FAILED'},503):new Promise<Response>(resolve=>{release=()=>resolve(json(stripeCapability()));});
    });
  }
  if(stage==='loader-pending')vi.mocked(loadStripeClient).mockReturnValueOnce(new Promise(resolve=>{release=()=>resolve(null);}));
  if(stage==='loader-rejected')vi.mocked(loadStripeClient).mockRejectedValueOnce(new Error('private-loader-sentinel'));
  if(stage==='elements-throw')p.stripe.elements.mockImplementationOnce(()=>{throw new Error('private-elements-sentinel');});
  if(stage==='mount-throw')vi.spyOn(p.element,'mount').mockImplementationOnce(()=>{throw new Error('private-mount-sentinel');});
  resume.click();
  try{
    if(['capability-rejected','loader-rejected','elements-throw','mount-throw'].includes(stage))await vi.waitFor(()=>{expect(el.state).toBe('checking');expect(button(el)).toBeEnabled();});
    else if(['frame-pending','loaderror','ready'].includes(stage)){
      await vi.waitFor(()=>expect(el.querySelector('iframe')).toBe(p.frame));
      if(stage!=='frame-pending'){p.emit(stage);await until(el,stage==='ready'?'ready':'checking');}
    }else await vi.waitFor(()=>expect(requests.filter(r=>r.url.endsWith('/client-session'))).toHaveLength(1));
    const failed=['capability-rejected','loader-rejected','elements-throw','mount-throw','loaderror'].includes(stage);
    expect(observeForm(el)).toEqual({widgetState:stage==='ready'?'ready':failed?'checking':'loading',elementState:['frame-pending','ready'].includes(stage)?'FRAME':'EMPTY',payState:stage==='ready'?'ENABLED':'ABSENT'});
    expect(requests.filter(r=>r.url.endsWith('/sites/test-site/checkouts'))).toHaveLength(1);
    expect(requests.filter(r=>r.url.endsWith('/client-session'))).toHaveLength(1);expect(p.stripe.confirmPayment).not.toHaveBeenCalled();
  }finally{el.remove();release();await Promise.resolve();await Promise.resolve();}
});
// Break: treating an outer iframe or ready event as completion can hide a pending presentation catch-up.
it.each(['resolve','disconnect'])('resumed pending presentation keeps loading/FRAME/ABSENT until %s without confirming or recreating',async outcome=>{
  const p=stripeUiProvider(),requests=stripeApi();
  await createGenericPaymentClient({apiBaseUrl:'https://api.test'}).createCatalogCheckout({siteKey:'test-site',priceKey:'lifetime'});
  const el=mount();await until(el,'checking');const resume=el.shadowRoot!.querySelectorAll('button')[1];await vi.waitFor(()=>expect(resume).toBeEnabled());
  const ready=vi.fn();el.addEventListener('ready',ready);let release=()=>{};
  p.elements.update.mockImplementationOnce(()=>new Promise<void>(resolve=>{release=resolve;}));
  try{
    resume.click();await vi.waitFor(()=>expect(el.querySelector('iframe')).toBe(p.frame));
    el.locale='pt-PT';p.emit('ready');await vi.waitFor(()=>expect(p.elements.update).toHaveBeenCalledOnce());
    expect(observeForm(el)).toEqual({widgetState:'loading',elementState:'FRAME',payState:'ABSENT'});
    expect(ready).not.toHaveBeenCalled();
    if(outcome==='disconnect'){
      el.remove();release();await Promise.resolve();await Promise.resolve();
      expect(el.querySelector('iframe')).toBeNull();expect(ready).not.toHaveBeenCalled();
      expect(p.element.destroy).toHaveBeenCalledOnce();
    }else{
      release();await until(el,'ready');
      expect(observeForm(el)).toEqual({widgetState:'ready',elementState:'FRAME',payState:'ENABLED'});
      expect(ready).toHaveBeenCalledOnce();expect(el.querySelector('iframe')).toBe(p.frame);
      expect(p.element.destroy).not.toHaveBeenCalled();
    }
    expect(requests.map(request=>request.method)).toEqual(['POST','GET','POST']);
    expect(requests.filter(request=>request.url.endsWith('/sites/test-site/checkouts'))).toHaveLength(1);
    expect(requests.filter(request=>request.url.endsWith('/client-session'))).toHaveLength(1);
    expect(p.stripe.confirmPayment).not.toHaveBeenCalled();
  }finally{el.remove();release();await Promise.resolve();await Promise.resolve();}
});
// Break: registering ready after mount loses a synchronous provider event and strands a recovered checkout.
it('resumed synchronous ready during Element mount enables Pay on the same checkout without confirmation',async()=>{
  const p=stripeUiProvider(),requests=stripeApi();
  await createGenericPaymentClient({apiBaseUrl:'https://api.test'}).createCatalogCheckout({siteKey:'test-site',priceKey:'lifetime'});
  const el=mount();await until(el,'checking');const resume=el.shadowRoot!.querySelectorAll('button')[1];await vi.waitFor(()=>expect(resume).toBeEnabled());
  vi.spyOn(p.element,'mount').mockImplementationOnce(host=>{host.append(p.frame);p.emit('ready');});
  try{
    resume.click();await until(el,'ready');
    expect(observeForm(el)).toEqual({widgetState:'ready',elementState:'FRAME',payState:'ENABLED'});
    expect(requests.map(request=>request.method)).toEqual(['POST','GET','POST']);
    expect(requests.filter(request=>request.url.endsWith('/sites/test-site/checkouts'))).toHaveLength(1);
    expect(requests.filter(request=>request.url.endsWith('/client-session'))).toHaveLength(1);
    expect(p.stripe.confirmPayment).not.toHaveBeenCalled();
  }finally{el.remove();}
});
it('safe form observation is serializable and never reads markup frame URLs values or arbitrary state',()=>{
  const el=document.createElement('div'),root=el.attachShadow({mode:'open'}),host=document.createElement('div'),frame=document.createElement('iframe'),pay=document.createElement('button');
  host.className='payment-element';host.append(frame);pay.setAttribute('aria-label','Pay');root.append(host,pay);
  let reads=0;const forbidden=()=>{reads++;throw new Error('private-dom-sentinel');};
  for(const [target,key] of [[el,'innerHTML'],[root,'innerHTML'],[root,'textContent'],[frame,'src'],[frame,'contentDocument'],[pay,'value']] as const)Object.defineProperty(target,key,{get:forbidden});
  Object.defineProperty(el,'state',{value:new Proxy({},{get:forbidden}),configurable:true});
  const fn=(sandboxDriver as unknown as {observeSandboxPaymentForm?:(el:Element)=>unknown}).observeSandboxPaymentForm;
  const transported=fn?Function('return ('+fn.toString()+')')():()=>undefined;
  expect(transported(el)).toEqual({widgetState:'UNAVAILABLE',elementState:'FRAME',payState:'ENABLED'});
  pay.disabled=true;expect(observeForm(el)).toEqual({widgetState:'UNAVAILABLE',elementState:'FRAME',payState:'DISABLED'});
  pay.hidden=true;expect(observeForm(el)).toEqual({widgetState:'UNAVAILABLE',elementState:'FRAME',payState:'HIDDEN'});
  Object.defineProperty(el,'state',{get:forbidden});expect(observeForm(el)).toEqual({widgetState:'UNAVAILABLE',elementState:'UNAVAILABLE',payState:'UNAVAILABLE'});
  expect(observeForm(document.createElement('div'))).toEqual({widgetState:'UNAVAILABLE',elementState:'UNAVAILABLE',payState:'UNAVAILABLE'});expect(reads).toBe(1);
});
it('safe form observation distinguishes no host from an empty owned host without probing provider content',()=>{
  const el=document.createElement('div'),root=el.attachShadow({mode:'open'});Object.defineProperty(el,'state',{value:'idle'});
  expect(observeForm(el)).toEqual({widgetState:'idle',elementState:'NO_HOST',payState:'ABSENT'});
  const host=document.createElement('div');host.className='payment-element';root.append(host);
  expect(observeForm(el)).toEqual({widgetState:'idle',elementState:'EMPTY',payState:'ABSENT'});
});
it.each(['PROCESSING', 'UNKNOWN'])('recovered Fake %s regains original explicit confirmation after becoming actionable', async pending => {
  const p = stripeUiProvider(), requests: Request[] = []; let reads = 0;
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return json(init.method === 'GET' ? readWire(++reads === 1 ? pending : 'REQUIRES_PAYMENT_METHOD') : url.endsWith('/confirm') ? readWire('SUCCEEDED') : wire(pending), url.endsWith('/checkouts') ? 201 : 200); });
  await createGenericPaymentClient({ apiBaseUrl: 'https://api.test' }).createCatalogCheckout({ siteKey: 'test-site', priceKey: 'lifetime' });
  const el = mount(); await until(el, 'ready'); await vi.waitFor(() => expect(button(el)).toBeEnabled()); expect(button(el).textContent).toBe('Pay');
  expect(requests.map(r => r.method)).toEqual(['POST', 'GET', 'GET']); button(el).click(); await until(el, 'succeeded');
  expect(requests.map(r => r.method)).toEqual(['POST', 'GET', 'GET', 'POST']); expect(requests[3].url).toBe('https://api.test/api/v1/public/checkouts/11111111-1111-4111-8111-111111111111/confirm'); expect(p.elements.create).not.toHaveBeenCalled();
});
it.each(['reject', 'throw'])('initial Stripe presentation %s does not publish payment uncertainty or replace the ready form', async failure => {
  const p = stripeUiProvider(), requests = stripeApi('SUCCEEDED'), el = mount(), unknown = vi.fn(), failed = vi.fn(); el.addEventListener('unknown', unknown); el.addEventListener('failed', failed);
  button(el).click(); await vi.waitFor(() => expect(el.querySelector('iframe')).toBe(p.frame)); const host = p.frame.parentElement;
  p.elements.update.mockImplementationOnce(() => { if (failure === 'throw') throw Error('sensitive'); return Promise.reject(Error('sensitive')); });
  el.locale = 'pt-PT'; p.emit('ready'); await until(el, 'ready'); expect(button(el)).toBeEnabled(); expect(p.frame.parentElement).toBe(host); expect(p.element.destroy).not.toHaveBeenCalled(); expect(unknown).not.toHaveBeenCalled(); expect(failed).not.toHaveBeenCalled(); expect(requests).toHaveLength(2);
  button(el).click(); await until(el, 'succeeded'); expect(requests).toHaveLength(3);
});
it('configuration change cancels initial presentation catch-up without late ready or financial events', async () => {
  const p = stripeUiProvider(), requests = stripeApi(), el = mount(); let reject!: (reason: unknown) => void;
  button(el).click(); await vi.waitFor(() => expect(el.querySelector('iframe')).toBe(p.frame));
  p.elements.update.mockImplementationOnce(() => new Promise<void>((_, no) => { reject = no; })); el.locale = 'pt-PT'; p.emit('ready');
  await vi.waitFor(() => expect(reject).toBeTypeOf('function')); const ready = vi.fn(), unknown = vi.fn(); el.addEventListener('ready', ready); el.addEventListener('unknown', unknown);
  el.priceKey = 'other-key'; reject(Error('late sensitive')); await Promise.resolve(); await Promise.resolve();
  expect(el.state).toBe('idle'); expect(el.querySelector('iframe')).toBeNull(); expect(p.element.destroy).toHaveBeenCalledOnce(); expect(ready).not.toHaveBeenCalled(); expect(unknown).not.toHaveBeenCalled(); expect(requests).toHaveLength(2);
});
it('does not expose provider values or perform network work before authorization', () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); const el = mount(); expect(el.shadowRoot!.innerHTML).not.toMatch(/clientSecret|pk_test_|pi_.*_secret/); expect(fetch).not.toHaveBeenCalled();
});
it('expires the Stripe capability immediately with safe status and no new operation', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true }); const p = stripeUiProvider(), requests = stripeApi(), el = mount(); await stripeReady(el, p);
  await vi.advanceTimersByTimeAsync(Date.parse(wire().expires_at) - Date.now() + 1);
  expect(el.state).toBe('expired'); expect(p.element.destroy).toHaveBeenCalledOnce(); expect(el.querySelector('iframe')).toBeNull(); expect(requests).toHaveLength(2);
});
it('offers explicit same-handle action after a later verified REQUIRES_ACTION without replaying automatically', async () => {
  const p = stripeUiProvider(), requests = stripeApi(), el = mount(); await stripeReady(el, p); button(el).click(); await until(el, 'checking');
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return json(stripeReadWire('REQUIRES_ACTION')); });
  button(el).click(); await until(el, 'ready'); expect(button(el).textContent).toBe('Pay'); expect(p.stripe.confirmPayment).toHaveBeenCalledOnce(); expect(requests).toHaveLength(4);
});
it('suppresses late Stripe confirmation after configuration change without a refresh or success event', async () => {
  const p = stripeUiProvider(), requests = stripeApi('SUCCEEDED'), el = mount(); await stripeReady(el, p); let finish!: (value: unknown) => void;
  p.stripe.confirmPayment.mockImplementation(() => new Promise(r => { finish = r; })); const succeeded = vi.fn(); el.addEventListener('succeeded', succeeded); button(el).click(); el.priceKey = 'other-key'; finish({}); await Promise.resolve(); await Promise.resolve();
  expect(requests).toHaveLength(2); expect(succeeded).not.toHaveBeenCalled(); expect(el.state).toBe('idle');
});
it.each(['CREATED', 'REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION'])('does not retry failed Stripe preparation on status checking %s', async backend => {
  const p = stripeUiProvider(), requests: Request[] = [];
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); if (url.endsWith('/client-session')) throw new TypeError('raw secret'); return json(init.method === 'GET' ? stripeReadWire(backend) : stripeWire()); });
  const el = mount(); button(el).click(); await until(el, 'unknown'); expect(requests).toHaveLength(2); button(el).click(); await until(el, 'checking'); await vi.waitFor(() => expect(button(el)).toBeEnabled()); expect(button(el).textContent).toBe('Check status'); expect(requests.map(r => r.method)).toEqual(['POST', 'POST', 'GET']); expect(p.stripe.elements).not.toHaveBeenCalled();
});
it('gates Stripe Pay on ready and preserves the secure host across status and presentation updates', async () => {
  const p = stripeUiProvider(), requests = stripeApi('SUCCEEDED'), el = mount();
  button(el).click(); await vi.waitFor(() => expect(el.querySelector('iframe')).toBe(p.frame));
  expect(button(el).textContent).not.toBe('Pay'); expect(button(el)).toBeDisabled(); const host = p.frame.parentElement;
  el.locale = 'pt-PT'; el.style.setProperty('--gp-accent', '#abcdef'); el.dispatchEvent(new Event('payment-theme-change'));
  p.emit('ready'); await until(el, 'ready'); expect(p.frame.parentElement).toBe(host); expect(p.elements.update).toHaveBeenLastCalledWith(expect.objectContaining({ locale: 'pt', appearance: expect.objectContaining({ variables: expect.objectContaining({ colorPrimary: '#abcdef' }) }) }));
  const user = userEvent.setup(); button(el).focus(); await user.keyboard('{Enter}'); await until(el, 'succeeded');
  expect(requests.map(r => r.method)).toEqual(['POST', 'POST', 'GET']); expect(p.stripe.confirmPayment).toHaveBeenCalledOnce(); expect(p.element.destroy).toHaveBeenCalledOnce();
  expect(el.shadowRoot!.innerHTML + JSON.stringify({ ...sessionStorage })).not.toMatch(/pk_test_fixture|pi_fixture|clientSecret/);
});
it.each(['unknown', 'refresh-failure', 'unsettled'])('keeps Stripe %s checking without a second financial operation or immediate redundant read', async kind => {
  const p = stripeUiProvider(), requests = stripeApi(), el = mount(); await stripeReady(el, p);
  if (kind === 'unknown') p.stripe.confirmPayment.mockRejectedValue(new Error('pi_fixture_secret_sensitive'));
  if (kind === 'refresh-failure') vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); throw new TypeError('raw secret'); });
  button(el).click(); button(el).dispatchEvent(new MouseEvent('click'));
  await vi.waitFor(() => expect(status(el).textContent).toContain('Checking payment')); expect(button(el).textContent).toBe('Check status');
  expect(requests.map(r => r.method)).toEqual(['POST', 'POST', 'GET']); expect(p.stripe.confirmPayment).toHaveBeenCalledOnce();
  await Promise.resolve(); expect(requests).toHaveLength(3); expect(el.shadowRoot!.textContent).not.toContain('secret');
  vi.stubGlobal('fetch', async () => json(stripeReadWire('CREATED'))); button(el).click(); await vi.waitFor(() => expect(button(el)).toBeEnabled()); expect(button(el).textContent).toBe('Check status');
  vi.stubGlobal('fetch', async () => json(stripeReadWire('REQUIRES_PAYMENT_METHOD'))); button(el).click(); await until(el, 'ready'); expect(button(el).textContent).toBe('Pay');
});
it('keeps Stripe input decline ready on the same live card and never emits failed', async () => {
  const p = stripeUiProvider(), requests = stripeApi(), el = mount(); await stripeReady(el, p); const failed = vi.fn(); el.addEventListener('failed', failed);
  p.stripe.confirmPayment.mockResolvedValue({ error: { type: 'validation_error', message: 'raw sensitive' } }); button(el).click();
  await vi.waitFor(() => expect(p.element.focus).toHaveBeenCalledOnce()); await vi.waitFor(() => expect(button(el)).toBeEnabled()); expect(el.state).toBe('ready'); expect(document.activeElement).toBe(p.frame); expect(failed).not.toHaveBeenCalled(); expect(requests).toHaveLength(3);
});
it('lets backend success win over browser card error exactly once', async () => {
  const p = stripeUiProvider(), requests = stripeApi('SUCCEEDED'), el = mount(); await stripeReady(el, p); const success = vi.fn(); el.addEventListener('succeeded', success);
  p.stripe.confirmPayment.mockResolvedValue({ error: { type: 'card_error' } }); button(el).click(); await until(el, 'succeeded'); expect(success).toHaveBeenCalledOnce(); expect(requests).toHaveLength(3);
});
it.each(['site-key', 'api-base-url', 'price-key', 'disconnect'])('destroys pending Stripe readiness on %s and suppresses late ready', async change => {
  const p = stripeUiProvider(); stripeApi(); const el = mount(); button(el).click(); await vi.waitFor(() => expect(el.querySelector('iframe')).toBeTruthy());
  if (change === 'disconnect') el.remove(); else el.setAttribute(change, change === 'api-base-url' ? 'https://other.test' : 'other-key');
  expect(p.element.destroy).toHaveBeenCalledOnce(); p.emit('ready'); await Promise.resolve(); expect(el.querySelector('iframe')).toBeNull(); if (change !== 'disconnect') expect(el.state).toBe('idle');
});
it.each([['same-instance-unknown', 'CREATED'], ['fresh', 'CREATED'], ['fresh', 'REQUIRES_PAYMENT_METHOD'], ['fresh', 'REQUIRES_ACTION']])('recovered Stripe %s %s requires explicit Resume then separate ready Pay on the same checkout', async (kind, backend) => {
  const p = stripeUiProvider(), requests = stripeApi(backend), first = mount(); await stripeReady(first, p);
  if (kind === 'same-instance-unknown') { p.stripe.confirmPayment.mockRejectedValue(new Error('offline')); button(first).click(); await until(first, 'checking'); }
  first.remove(); const el = kind === 'fresh' ? mount() : first; if (kind !== 'fresh') document.body.append(el);
  await until(el, 'checking'); const expected = kind === 'fresh' ? 3 : 4; expect(requests).toHaveLength(expected); expect(requests.at(-1)!.method).toBe('GET'); expect(p.frame.isConnected).toBe(false);
  const resume = [...el.shadowRoot!.querySelectorAll('button')].find(b => b.textContent === 'Resume existing payment'); expect(resume).toBeDefined(); await vi.waitFor(() => expect(resume).toBeEnabled()); expect(resume).toBeVisible(); resume!.click(); resume!.click();
  await vi.waitFor(() => expect(el.querySelector('iframe')).toBe(p.frame)); expect(requests).toHaveLength(expected + 1); expect(requests.at(-1)!.url).toMatch(/11111111-1111-4111-8111-111111111111\/client-session$/); expect(p.stripe.confirmPayment).toHaveBeenCalledTimes(kind === 'fresh' ? 0 : 1); expect(button(el).textContent).not.toBe('Pay');
  p.emit('ready'); await until(el, 'ready'); expect(button(el).textContent).toBe('Pay'); expect(requests.filter(r => r.url.endsWith('/sites/test-site/checkouts'))).toHaveLength(1);
});
it('failed explicit Stripe recovery stays checking with no automatic retry', async () => {
  const p = stripeUiProvider(), requests = stripeApi(), first = mount(); await stripeReady(first, p); first.remove(); const el = mount(); await until(el, 'checking');
  const resume = el.shadowRoot!.querySelectorAll('button')[1]; await vi.waitFor(() => expect(resume).toBeEnabled());
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); throw new TypeError('sensitive'); }); resume.click(); await vi.waitFor(() => expect(button(el)).toBeEnabled()); expect(el.state).toBe('checking'); expect(requests).toHaveLength(4); expect(p.stripe.confirmPayment).not.toHaveBeenCalled();
});
// Characterization: iframe presence after Resume is not the SDK's ready-to-Pay contract.
it.each(['ready', 'loaderror'])('exposes the acceptance Pay readiness gate only for resumed Stripe ready, not %s alone', async event => {
  const p = stripeUiProvider(), requests = stripeApi(), first = mount(); await stripeReady(first, p); first.remove();
  const el = mount(); await until(el, 'checking'); const resume = el.shadowRoot!.querySelectorAll('button')[1];
  await vi.waitFor(() => expect(resume).toBeEnabled()); resume.click();
  await vi.waitFor(() => expect(el.querySelector('iframe')).toBe(p.frame));
  expect(el.state).toBe('loading'); expect(el.shadowRoot!.querySelector('button[aria-label="Pay"]:enabled')).toBeNull();
  expect(p.stripe.confirmPayment).not.toHaveBeenCalled();
  p.emit(event); await until(el, event === 'ready' ? 'ready' : 'checking');
  const pay = el.shadowRoot!.querySelector('button[aria-label="Pay"]:enabled');
  if (event === 'ready') expect(pay).toBeVisible(); else expect(pay).toBeNull();
  expect(requests.filter(r => r.url.endsWith('/sites/test-site/checkouts'))).toHaveLength(1);
  expect(requests.filter(r => r.url.endsWith('/client-session'))).toHaveLength(2);
  expect(p.stripe.confirmPayment).not.toHaveBeenCalled();
});
it('backend checkout expiry during a manual check destroys the live Stripe form', async () => {
  const p = stripeUiProvider(); stripeApi(); const el = mount(); await stripeReady(el, p); button(el).click(); await until(el, 'checking');
  vi.stubGlobal('fetch', async () => json({ code: 'CHECKOUT_EXPIRED' }, 410)); button(el).click(); await until(el, 'expired'); expect(p.element.destroy).toHaveBeenCalledOnce(); expect(el.querySelector('iframe')).toBeNull();
});
it('a recovered processing checkout that becomes actionable offers Resume without an automatic POST', async () => {
  const p = stripeUiProvider(); stripeApi(); const first = mount(); await stripeReady(first, p); first.remove(); let reads = 0;
  vi.stubGlobal('fetch', async () => json(stripeReadWire(++reads === 1 ? 'PROCESSING' : 'REQUIRES_PAYMENT_METHOD'))); const recovered = mount();
  await vi.waitFor(() => expect(reads).toBe(2)); await vi.waitFor(() => expect(button(recovered)).toBeEnabled()); expect(recovered.state).toBe('checking'); expect(button(recovered).textContent).toBe('Check status'); const resume = recovered.shadowRoot!.querySelectorAll('button')[1]; expect(resume).toBeVisible(); expect(p.frame.isConnected).toBe(false);
});
it('conservatively invalidates recovered Stripe namespace on Customer logout', async () => {
  const client = createGenericPaymentClient({ apiBaseUrl: 'https://api.test' }); vi.stubGlobal('fetch', async () => json(customerWire())); const customer = await client.exchangeSiteToken(authInput);
  const p = stripeUiProvider(); stripeApi(); const first = mount(); await stripeReady(first, p); first.remove(); const recovered = mount(); await until(recovered, 'checking');
  vi.stubGlobal('fetch', () => new Promise<Response>(() => {})); void client.logoutCustomerSession(customer).catch(() => {}); expect(recovered.state).toBe('idle'); expect(button(recovered).textContent).toBe('Start checkout');
});
it.each(['logout', 'replacement', 'memory-only'])('cleans original Customer Stripe capability immediately on %s', async kind => {
  const p = stripeUiProvider(), client = createGenericPaymentClient({ apiBaseUrl: 'https://api.test' }); vi.stubGlobal('fetch', async () => json(customerWire()));
  const storage = kind === 'memory-only' ? vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw Error('quota'); }) : undefined;
  const customer = await client.exchangeSiteToken(authInput); storage?.mockRestore(); stripeApi();
  // Memory-only fallback belongs to the authenticating client; catalog consumer here
  // uses persistent authority in normal cases. Direct creation covers memory below.
  if (kind === 'memory-only') {
    vi.stubGlobal('fetch', async () => json(stripeWire(), 201)); const session = await client.createCatalogCheckout({ siteKey: 'test-site', priceKey: 'lifetime' });
    const { subscribeCheckoutCustomer } = await import('../customer/session-lifecycle.js'); let invalidated = false; const unsubscribe = subscribeCheckoutCustomer(session, () => { invalidated = true; });
    vi.stubGlobal('fetch', () => new Promise<Response>(() => {})); void client.logoutCustomerSession(customer).catch(() => {}); expect(invalidated).toBe(true); unsubscribe(); return;
  }
  const el = mount(); await stripeReady(el, p);
  vi.stubGlobal('fetch', kind === 'replacement' ? async () => json({ ...customerWire(), session_token: 'N'.repeat(43) }) : () => new Promise<Response>(() => {}));
  if (kind === 'replacement') await client.exchangeSiteToken(authInput); else void client.logoutCustomerSession(customer).catch(() => {});
  expect(p.element.destroy).toHaveBeenCalledOnce(); expect(el.querySelector('iframe')).toBeNull(); expect(button(el).textContent).not.toBe('Pay');
});
it('stale old-token logout cannot destroy a newer known checkout', async () => {
  const client = createGenericPaymentClient({ apiBaseUrl: 'https://api.test' }); vi.stubGlobal('fetch', async () => json(customerWire())); const old = await client.exchangeSiteToken(authInput);
  vi.stubGlobal('fetch', async () => json({ ...customerWire(), session_token: 'N'.repeat(43) })); await client.exchangeSiteToken(authInput);
  const p = stripeUiProvider(); stripeApi(); const el = mount(); await stripeReady(el, p);
  vi.stubGlobal('fetch', async () => new Response(null, { status: 204 })); await client.logoutCustomerSession(old); expect(p.element.destroy).not.toHaveBeenCalled(); expect(button(el)).toBeEnabled();
});
it.each(['logout', 'replacement'])('suppresses old Customer checkout response when %s occurs during creation', async kind => {
  const client = createGenericPaymentClient({ apiBaseUrl: 'https://api.test' }); vi.stubGlobal('fetch', async () => json(customerWire())); const customer = await client.exchangeSiteToken(authInput);
  const p = stripeUiProvider(); let release!: (r: Response) => void; vi.stubGlobal('fetch', () => new Promise<Response>(r => { release = r; })); const el = mount(); button(el).click();
  vi.stubGlobal('fetch', async () => kind === 'logout' ? new Response(null, { status: 204 }) : json({ ...customerWire(), session_token: 'N'.repeat(43) }));
  if (kind === 'logout') await client.logoutCustomerSession(customer); else await client.exchangeSiteToken(authInput);
  release(json(stripeWire(), 201)); await vi.waitFor(() => expect(el.state).not.toBe('loading')); expect(el.state).not.toBe('ready'); expect(p.stripe.elements).not.toHaveBeenCalled();
});
it("registers idempotently, renders idle shadow UI with labels, focus, theme and reduced motion", () => {
  defineGenericPaymentWidget(); const el = mount(); expect(el.state).toBe("idle"); expect(button(el).textContent).toBe("Start checkout"); expect(status(el).getAttribute("aria-live")).toBe("polite");
  expect(el.shadowRoot!.querySelector("style")!.textContent).toContain("--gp-accent"); expect(el.shadowRoot!.querySelector("style")!.textContent).toContain("prefers-reduced-motion"); expect(el.shadowRoot!.querySelector("style")!.textContent).toContain(":focus-visible");
  expect("apiKey" in el).toBe(false); expect("partnerApiKey" in el).toBe(false); expect(el.getAttributeNames()).toEqual(["site-key", "price-key", "api-base-url"]);
});
it("supports native keyboard checkout, loading guard and focus after ready", async () => {
  let resolve!: (r: Response) => void; const fetch = vi.fn(() => new Promise<Response>(r => { resolve = r; })); vi.stubGlobal("fetch", fetch); const el = mount();
  const user = userEvent.setup(); button(el).focus(); await user.keyboard("{Enter}"); expect(el.state).toBe("loading"); expect(button(el).disabled).toBe(true); button(el).click(); expect(fetch).toHaveBeenCalledTimes(1);
  resolve(json(wire())); await until(el, "ready"); expect(el.shadowRoot!.activeElement).toBe(button(el)); expect(button(el).textContent).toContain("Pay"); expect(status(el).textContent).toContain("EUR");
  expect(el.shadowRoot!.textContent).not.toContain(token); expect(el.outerHTML).not.toContain(token);
});
it.each([["PROCESSING", "processing"], ["REQUIRES_ACTION", "requires_action"], ["SUCCEEDED", "succeeded"], ["FAILED", "failed"], ["CANCELLED", "failed"], ["REFUNDED", "succeeded"], ["REQUIRES_PAYMENT_METHOD", "ready"]])("maps %s and emits only safe actual transitions", async (wireStatus, wanted) => {
  const details: unknown[] = []; vi.stubGlobal("fetch", async (_: string, init: RequestInit) => json(new Headers(init.headers).has("authorization") ? readWire(wireStatus) : wire()));
  const el = mount(); for (const name of ["ready", "processing", "requires-action", "succeeded", "failed"]) el.addEventListener(name, e => { const event = e as CustomEvent; expect(event.composed).toBe(true); expect(event.bubbles).toBe(true); details.push(event.detail); expect(Object.isFrozen(event.detail)).toBe(true); });
  button(el).click(); await until(el, "ready"); button(el).click(); await until(el, wanted);
  expect(details.length).toBeGreaterThan(0); expect(JSON.stringify(details)).not.toContain(token); expect(JSON.stringify(details)).not.toContain("provider");
  for (const d of details) expect(Object.keys(d as object).sort()).toEqual(["paymentId", "status"]);
  el.remove();
});
it("ambiguous confirmation becomes unknown without automatic replay and with safe text", async () => {
  let calls = 0; vi.stubGlobal("fetch", async () => { if (++calls === 1) return json(wire()); throw new TypeError(token); }); const el = mount(); button(el).click(); await until(el, "ready"); button(el).click(); await until(el, "unknown"); expect(calls).toBe(2); expect(el.shadowRoot!.textContent).not.toContain(token); expect(button(el).textContent).toBe("Check status");
});
it("maps expired response and clears only its recovery", async () => {
  let calls = 0; vi.stubGlobal("fetch", async () => ++calls === 1 ? json(wire()) : json({ code: "CHECKOUT_EXPIRED" }, 410)); const el = mount(); button(el).click(); await until(el, "ready"); button(el).click(); await until(el, "expired"); expect(sessionStorage.length).toBe(0);
});
it("decline retry uses same Payment and fresh explicit confirm key", async () => {
  const keys: string[] = []; const urls: string[] = [];
  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { urls.push(url); if (new Headers(init.headers).has("authorization")) { keys.push(new Headers(init.headers).get("idempotency-key")!); return json(readWire("REQUIRES_PAYMENT_METHOD")); } return json(wire()); });
  const el = mount(); button(el).click(); await until(el, "ready"); button(el).click(); await vi.waitFor(() => expect(keys).toHaveLength(1)); await until(el, "ready"); button(el).click(); await vi.waitFor(() => expect(keys).toHaveLength(2)); await until(el, "ready"); expect(keys[0]).not.toBe(keys[1]); expect(urls[1]).toBe(urls[2]);
});
it("disconnect aborts pending creation, suppresses late events and persistence", async () => {
  let resolve!: (r: Response) => void; let signal!: AbortSignal; const ready = vi.fn();
  vi.stubGlobal("fetch", async (_: string, init: RequestInit) => { signal = init.signal!; return new Promise<Response>(r => { resolve = r; }); });
  const el = mount(); el.addEventListener("ready", ready); button(el).click(); el.remove(); expect(signal.aborted).toBe(true); resolve(json(wire())); await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); expect(ready).not.toHaveBeenCalled(); expect(sessionStorage.length).toBe(0);
});
it("configuration change aborts and prevents stale mutation", async () => {
  let resolve!: (r: Response) => void; let signal!: AbortSignal;
  vi.stubGlobal("fetch", async (_: string, init: RequestInit) => { signal = init.signal!; return new Promise<Response>(r => { resolve = r; }); });
  const el = mount(); button(el).click(); el.priceKey = "other-price"; expect(signal.aborted).toBe(true); resolve(json(wire())); await Promise.resolve(); await Promise.resolve(); expect(el.state).toBe("idle"); expect(sessionStorage.length).toBe(0);
});
it("recovers matching session, reads immediately and emits succeeded once across repeated reads and reconnect", async () => {
  const fetch = vi.fn(async (_: string, init: RequestInit) => json(new Headers(init.headers).has("authorization") ? readWire("SUCCEEDED") : wire())); vi.stubGlobal("fetch", fetch);
  const first = mount(); button(first).click(); await until(first, "ready"); first.remove(); const recovered = mount(); let successes = 0; recovered.addEventListener("succeeded", () => successes++); await until(recovered, "succeeded"); expect(successes).toBe(1); expect(fetch).toHaveBeenCalledTimes(2);
  recovered.remove(); document.body.append(recovered); await until(recovered, "succeeded"); await Promise.resolve(); expect(successes).toBe(1);
});
it("disconnect cancels active polling with no late DOM/event update", async () => {
  let reads = 0; let readSignal!: AbortSignal; let resolve!: (r: Response) => void;
  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { if (init.method === "GET") { reads++; readSignal = init.signal!; return new Promise<Response>(r => { resolve = r; }); } return json(url.endsWith("confirm") ? readWire() : wire()); });
  const el = mount(); const success = vi.fn(); el.addEventListener("succeeded", success); button(el).click(); await until(el, "ready"); button(el).click(); await vi.waitFor(() => expect(reads).toBe(1)); const text = el.shadowRoot!.textContent; el.remove(); expect(readSignal.aborted).toBe(true); resolve(json(readWire("SUCCEEDED"))); await Promise.resolve(); await Promise.resolve(); expect(success).not.toHaveBeenCalled(); expect(el.shadowRoot!.textContent).toBe(text); expect(sessionStorage.length).toBe(1);
});
it("initial failed transition emits a fresh empty safe lifecycle detail", async () => {
  vi.stubGlobal("fetch", async () => json({ code: "PRICE_NOT_AVAILABLE", detail: token }, 404)); const el = mount(); const details: unknown[] = []; el.addEventListener("failed", e => details.push((e as CustomEvent).detail)); button(el).click(); await until(el, "failed"); expect(details).toEqual([{}]); expect(Object.isFrozen(details[0])).toBe(true); expect(JSON.stringify(details)).not.toContain(token);
});
it("terminal transition moves focus to its live status region", async () => {
  let calls = 0; vi.stubGlobal("fetch", async () => json(++calls === 1 ? wire() : readWire("SUCCEEDED"))); const el = mount(); button(el).click(); await until(el, "ready"); button(el).click(); await until(el, "succeeded"); expect(el.shadowRoot!.activeElement).toBe(status(el)); expect(status(el)).toHaveTextContent("Payment succeeded");
});
it("locale property reformats ready amount and preserves matching recovery identity", async () => {
  vi.stubGlobal("fetch", async (_: string, init: RequestInit) => json(init.method === "GET" ? readWire("REQUIRES_PAYMENT_METHOD") : wire())); const el = mount(); button(el).click(); await until(el, "ready"); el.locale = "de-DE"; await until(el, "ready"); expect(status(el).textContent).toContain("49,99"); expect(sessionStorage.length).toBe(1);
});
for (const prior of ["ready", "succeeded"]) {
  it.each(["missing", "blocked", "malformed", "expired", "mismatched", "oversized", "unsupported"])(`Fix1 I2 reconnect from ${prior} with %s recovery renders neutral idle`, async loss => {
    const requests: Request[] = [];
    vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return json(url.endsWith("confirm") ? readWire("SUCCEEDED") : wire()); });
    const el = mount(); button(el).click(); await until(el, "ready");
    if (prior === "succeeded") { button(el).click(); await until(el, "succeeded"); }
    el.remove(); const target = sessionStorage; const recordKey = target.key(0)!; const record = JSON.parse(target.getItem(recordKey)!);
    let restoreStorage: (() => void) | undefined;
    if (loss === "missing") target.removeItem(recordKey);
    else if (loss === "blocked") {
      const blocked = vi.spyOn(globalThis, "sessionStorage", "get").mockImplementation(() => { throw new DOMException("Synthetic blocked storage", "SecurityError"); });
      restoreStorage = () => blocked.mockRestore();
    }
    else if (loss === "malformed") target.setItem(recordKey, "{");
    else if (loss === "oversized") target.setItem(recordKey, "x".repeat(8193));
    else {
      if (loss === "expired") { record.expiresAt = "2000-01-01T00:00:00Z"; record.state.expiresAt = record.expiresAt; }
      if (loss === "mismatched") record.siteKey = "other-site";
      if (loss === "unsupported") record.version = 3;
      target.setItem(recordKey, JSON.stringify(record));
    }
    const before = requests.length; const events: string[] = [];
    for (const name of ["ready", "processing", "requires-action", "succeeded", "failed", "unknown", "expired"]) el.addEventListener(name, () => events.push(name));
    try {
      document.body.append(el); await Promise.resolve(); await Promise.resolve();
      expect(el.state).toBe("idle"); expect(status(el)).toHaveTextContent("Ready to start checkout");
      expect(button(el)).toBeVisible(); expect(button(el)).toBeEnabled(); expect(button(el).textContent).toBe("Start checkout");
      expect(events).toEqual([]); expect(requests).toHaveLength(before);
      button(el).click(); await until(el, "ready");
      expect(requests).toHaveLength(before + 1); expect(requests.at(-1)!.url).toBe("https://api.test/api/v1/public/sites/test-site/checkouts");
      expect(requests.at(-1)!.headers.has("authorization")).toBe(false); expect(events).toEqual(["ready"]);
    } finally { restoreStorage?.(); }
  });
}
it("Fix1 I2 matching reconnect waits for safe read without duplicate succeeded transition", async () => {
  let resume!: (response: Response) => void; let readSignal!: AbortSignal;
  const fetch = vi.fn(async (_: string, init: RequestInit) => {
    if (init.method === "GET") { readSignal = init.signal!; return new Promise<Response>(resolve => { resume = resolve; }); }
    return json(new Headers(init.headers).has("authorization") ? readWire("SUCCEEDED") : wire());
  }); vi.stubGlobal("fetch", fetch);
  const el = mount(); let successes = 0; el.addEventListener("succeeded", () => successes++);
  button(el).click(); await until(el, "ready"); button(el).click(); await until(el, "succeeded");
  el.remove(); document.body.append(el); await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(3));
  expect(readSignal.aborted).toBe(false); expect(button(el).hidden).toBe(true); expect(successes).toBe(1);
  resume(json(readWire("SUCCEEDED"))); await Promise.resolve(); await Promise.resolve();
  expect(el.state).toBe("succeeded"); expect(successes).toBe(1);
});
it("Fix1 I3 terminal locale change rerenders without reset, network or duplicate event", async () => {
  const requests: Request[] = []; vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return json(url.endsWith("confirm") ? readWire("SUCCEEDED") : wire()); });
  const el = mount(); let successes = 0; el.addEventListener("succeeded", () => successes++);
  button(el).click(); await until(el, "ready"); button(el).click(); await until(el, "succeeded");
  const recordKey = sessionStorage.key(0)!; const record = sessionStorage.getItem(recordKey);
  el.locale = "de-DE"; expect(el.state).toBe("succeeded"); await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
  expect(requests).toHaveLength(2); expect(successes).toBe(1); expect(sessionStorage.getItem(recordKey)).toBe(record);
  expect(status(el)).toHaveTextContent("Payment succeeded");
});
it("Fix1 I3 ready locale change preserves session and events while reformatting money", async () => {
  const requests: Request[] = []; vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return json(url.endsWith("confirm") ? readWire("SUCCEEDED") : wire()); });
  const el = mount(); let readies = 0; el.addEventListener("ready", () => readies++);
  button(el).click(); await until(el, "ready"); const recordKey = sessionStorage.key(0)!; const record = sessionStorage.getItem(recordKey);
  el.setAttribute("locale", "de-DE"); expect(el.state).toBe("ready"); expect(status(el).textContent).toContain("49,99");
  await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
  expect(requests).toHaveLength(1); expect(requests[0].signal.aborted).toBe(false); expect(readies).toBe(1);
  expect(sessionStorage.getItem(recordKey)).toBe(record);
  button(el).click(); await until(el, "succeeded"); expect(requests).toHaveLength(2);
  expect(requests[1].url).toBe("https://api.test/api/v1/public/checkouts/11111111-1111-4111-8111-111111111111/confirm");
});
it("Fix1 I3 locale during creation does not abort or replace the active request", async () => {
  let resolve!: (response: Response) => void; let signal!: AbortSignal;
  const fetch = vi.fn(async (_: string, init: RequestInit) => { signal = init.signal!; return new Promise<Response>(done => { resolve = done; }); }); vi.stubGlobal("fetch", fetch);
  const el = mount(); button(el).click(); expect(el.state).toBe("loading"); el.locale = "de-DE";
  expect(signal.aborted).toBe(false); expect(el.state).toBe("loading"); expect(fetch).toHaveBeenCalledTimes(1);
  resolve(json(wire())); await until(el, "ready"); expect(status(el).textContent).toContain("49,99");
});
for (const recoveredStatus of ["CREATED", "REQUIRES_PAYMENT_METHOD"]) {
  for (const localeChange of [false, true]) {
    it.each(["native", "programmatic"])(`Fix2 F1 ${recoveredStatus} reconnect gates synchronous %s activation with locale=${localeChange}`, async activation => {
      const requests: Request[] = []; let resume!: (response: Response) => void;
      vi.stubGlobal("fetch", async (url: string, init: RequestInit) => {
        requests.push(new Request(url, init));
        if (init.method === "GET") return new Promise<Response>(resolve => { resume = resolve; });
        return json(url.endsWith("confirm") ? readWire("SUCCEEDED") : wire(recoveredStatus));
      });
      const el = mount(); const events: string[] = [];
      for (const name of ["ready", "succeeded", "unknown"]) el.addEventListener(name, () => events.push(name));
      button(el).click(); await until(el, "ready"); el.remove(); document.body.append(el);
      if (localeChange) el.locale = "de-DE";
      // No await: exercise the public action before the queued recovery read starts.
      if (activation === "native") button(el).click();
      else button(el).dispatchEvent(new MouseEvent("click", { bubbles: true }));
      expect(requests.map(request => request.method)).toEqual(["POST"]);
      expect(button(el)).toBeDisabled(); expect(events).toEqual(["ready"]);
      await vi.waitFor(() => expect(requests).toHaveLength(2));
      expect(requests[1].method).toBe("GET"); expect(requests[1].headers.has("idempotency-key")).toBe(false);
      button(el).dispatchEvent(new MouseEvent("click")); expect(requests).toHaveLength(2);
      resume(json(readWire(recoveredStatus)));
      await vi.waitFor(() => expect(button(el)).toBeEnabled()); expect(el.state).toBe("ready");
      expect(events).toEqual(["ready"]);
      button(el).click(); await until(el, "succeeded");
      expect(requests.map(request => request.method)).toEqual(["POST", "GET", "POST"]);
      expect(requests[2].url).toBe(requests[1].url + "/confirm");
      expect(requests[2].headers.get("idempotency-key")).not.toBe(requests[0].headers.get("idempotency-key"));
      expect(events).toEqual(["ready", "succeeded"]);
    });
  }
  it.each(["PROVIDER_DECLINED", "PROVIDER_TEMPORARILY_UNAVAILABLE"])(`Fix2 F2 ${recoveredStatus} preserves recoverable %s as ready for a fresh explicit intent`, async code => {
    const requests: Request[] = []; const unknown = vi.fn(); const readyDetails: unknown[] = [];
    vi.stubGlobal("fetch", async (url: string, init: RequestInit) => {
      requests.push(new Request(url, init));
      return requests.length === 1 ? json(wire(recoveredStatus)) : requests.length === 2 ? json({ code, detail: token }, 503) : json(readWire("SUCCEEDED"));
    });
    const el = mount(); el.addEventListener("unknown", unknown); el.addEventListener("ready", event => readyDetails.push((event as CustomEvent).detail));
    button(el).click(); await until(el, "ready"); const key = sessionStorage.key(0)!; const record = sessionStorage.getItem(key);
    button(el).click(); await vi.waitFor(() => expect(button(el)).toBeEnabled());
    expect(el.state).toBe("ready"); expect(button(el).textContent).toBe("Pay"); expect(unknown).not.toHaveBeenCalled();
    expect(readyDetails).toEqual([{ paymentId: wire().payment_id, status: recoveredStatus }, { paymentId: wire().payment_id, status: recoveredStatus }]);
    expect(sessionStorage.getItem(key)).toBe(record); expect(requests).toHaveLength(2);
    expect(el.shadowRoot!.textContent).not.toContain(token);
    button(el).click(); await until(el, "succeeded");
    expect(requests.map(request => request.method)).toEqual(["POST", "POST", "POST"]);
    expect(requests[1].url).toBe(requests[2].url);
    expect(requests[1].headers.get("idempotency-key")).not.toBe(requests[2].headers.get("idempotency-key"));
  });
}
it.each(["transport", "decode", "unsupported-provider", "provider-unknown", "generic-problem"])("Fix2 F2 ambiguous %s still gates confirm behind an explicit safe read", async kind => {
  const requests: Request[] = []; let resume!: (response: Response) => void; const unknown = vi.fn();
  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => {
    requests.push(new Request(url, init));
    if (requests.length === 1) return json(wire());
    if (requests.length === 2) {
      if (kind === "transport") throw new TypeError(token);
      if (kind === "decode") return new Response("{");
      if (kind === "unsupported-provider") return json({ ...readWire(), provider_type: "ALIEN" });
      return json(kind === "provider-unknown" ? { code: "PROVIDER_RESULT_UNKNOWN" } : { detail: token }, 503);
    }
    if (init.method === "GET") return new Promise<Response>(resolve => { resume = resolve; });
    return json(readWire("SUCCEEDED"));
  });
  const el = mount(); el.addEventListener("unknown", unknown);
  button(el).click(); await until(el, "ready"); button(el).click(); await until(el, "unknown");
  expect(unknown).toHaveBeenCalledTimes(1); expect(requests).toHaveLength(2); expect(button(el).textContent).toBe("Check status");
  expect(JSON.parse(sessionStorage.getItem(sessionStorage.key(0)!)!).state.status).toBe("UNKNOWN");
  button(el).click(); expect(requests.map(request => request.method)).toEqual(["POST", "POST", "GET"]);
  button(el).dispatchEvent(new MouseEvent("click")); expect(requests).toHaveLength(3);
  resume(json(readWire("REQUIRES_PAYMENT_METHOD"))); await until(el, "ready");
  button(el).click(); await until(el, "succeeded");
  expect(requests.map(request => request.method)).toEqual(["POST", "POST", "GET", "POST"]);
  expect(requests[1].url).toBe(requests[3].url); expect(requests[1].headers.get("idempotency-key")).not.toBe(requests[3].headers.get("idempotency-key"));
});
it.each(["PROVIDER_DECLINED", "PROVIDER_TEMPORARILY_UNAVAILABLE"])("Fix2 F2 %s on recovery GET does not authorize confirmation", async code => {
  const requests: Request[] = []; let resume!: (response: Response) => void;
  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => {
    requests.push(new Request(url, init));
    if (requests.length === 1) return json(wire());
    if (requests.length === 2) return json({ code }, 503);
    return new Promise<Response>(resolve => { resume = resolve; });
  });
  const el = mount(); button(el).click(); await until(el, "ready"); el.remove(); document.body.append(el);
  await until(el, "unknown"); expect(button(el).textContent).toBe("Check status");
  expect(requests.map(request => request.method)).toEqual(["POST", "GET"]);
  button(el).click(); expect(requests.map(request => request.method)).toEqual(["POST", "GET", "GET"]);
  resume(json(readWire("REQUIRES_PAYMENT_METHOD"))); await until(el, "ready");
});
