import { createGenericPaymentClient, recoverCustomerSession, registerGenericSubscriptionManager } from '@generic-payments/sdk';
import type { CustomerSession, GenericPaymentClient, GenericSubscriptionManager } from '@generic-payments/sdk';

const en = {
  title: 'Subscription demo', product: 'Product', productId: 'Product ID', priceId: 'Initial price ID (optional)',
  load: 'Load products', configuration: 'Enter one or two distinct published Product UUIDs from your local Site. Prices and permissions come from the server.',
  invalid: 'Enter valid Product UUIDs and optional price UUIDs.',
  protected: 'Protected content requires backend or serverless verification of subscription access. This browser does not authorize protected content.',
  portal: 'Returned from the local Fake portal simulation. Subscription state is reloaded from the server after sign-in recovery.',
} as const;
const copy = {
  en,
  pt: {
    title: 'Demonstração de subscrições', product: 'Produto', productId: 'ID do produto', priceId: 'ID do preço inicial (opcional)',
    load: 'Carregar produtos', configuration: 'Introduza um ou dois UUIDs de produtos publicados distintos do Site local. Os preços e as permissões vêm do servidor.',
    invalid: 'Introduza UUIDs de produto válidos e UUIDs de preço opcionais.',
    protected: 'O conteúdo protegido exige verificação do acesso à subscrição no backend ou numa função serverless. Este browser não autoriza conteúdo protegido.',
    portal: 'Regresso da simulação local do portal Fake. O estado da subscrição é recarregado do servidor após recuperar a sessão.',
  },
} satisfies Record<'en' | 'pt', { [K in keyof typeof en]: string }>;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function validSubscriptionProducts(values:string[]):boolean {return values.length===2&&uuid.test(values[0]??'')&&(values[1]===''||uuid.test(values[1]??'')&&values[1]!.toLowerCase()!==values[0]!.toLowerCase());}
const returnConfigurationKey='demo:subscription-return:v1';
function returnIdentity(raw:unknown):{apiBaseUrl:string;siteKey:string}|undefined {
  if(!raw||typeof raw!=='object')return;const value=raw as Record<string,unknown>;
  if(Object.keys(value).length!==2||typeof value.apiBaseUrl!=='string'||typeof value.siteKey!=='string'||!(/^[A-Za-z0-9][A-Za-z0-9-]{2,62}$/).test(value.siteKey))return;
  try { const url=new URL(value.apiBaseUrl);if(url.origin!==value.apiBaseUrl||!(url.protocol==='https:'||url.protocol==='http:'&&['127.0.0.1','localhost','[::1]'].includes(url.hostname)))return;return {apiBaseUrl:value.apiBaseUrl,siteKey:value.siteKey}; }catch{return;}
}
/** Return parameters carry no authority. Only an explicit recovered Customer read is offered. */
export function mountSubscriptionReturn():void {
  const status=document.querySelector<HTMLElement>('#subscription-return-status'),button=document.querySelector<HTMLButtonElement>('#read-subscriptions');if(!status||!button)return;
  let identity:{apiBaseUrl:string;siteKey:string}|undefined;
  try { const raw=sessionStorage.getItem(returnConfigurationKey);if(raw&&raw.length<=2048)identity=returnIdentity(JSON.parse(raw)); }catch{/* Public hints are optional. */}
  identity??=returnIdentity({apiBaseUrl:document.documentElement.dataset.apiBaseUrl,siteKey:document.documentElement.dataset.siteKey});
  const session=identity?recoverCustomerSession(identity):null;
  status.textContent='Sign in to inspect subscriptions. A return does not confirm billing or access.';
  if(!identity||!session)return;
  const controller=new AbortController();window.addEventListener('pagehide',()=>controller.abort(),{once:true});
  button.hidden=false;status.textContent='A saved Customer session is available. Read the server status to inspect billing and access.';
  button.addEventListener('click',async()=>{
    if(button.disabled||controller.signal.aborted)return;button.disabled=true;
    try {
      const current=recoverCustomerSession(identity!);if(!current||current.sessionToken!==session.sessionToken)throw new Error('Customer authority changed');
      const page=await createGenericPaymentClient({apiBaseUrl:identity!.apiBaseUrl,signal:controller.signal}).subscriptions.list({session:current,signal:controller.signal,limit:20});
      if(!controller.signal.aborted)status.textContent=page.items.length?page.items.map(value=>`Billing ${value.billingStatus} · Access ${value.access.allowed?'allowed':'not allowed'} · ${value.access.reason}`).join('; '):'No subscriptions are available for this Customer.';
    }catch{if(!controller.signal.aborted)status.textContent='Unable to read subscriptions. Sign in again to inspect server status.';}
    finally{if(!controller.signal.aborted)button.disabled=false;}
  });
}

/** Public configuration only. Customer authority is passed explicitly by the OTP host. */
export function mountSubscriptions(configuration: { locale: string; apiBaseUrl?: string; siteKey?: string }, client: GenericPaymentClient, onLocale: (locale: 'en' | 'pt') => void): { setSession(session: CustomerSession | undefined): void } {
  registerGenericSubscriptionManager();
  const identity=returnIdentity({apiBaseUrl:configuration.apiBaseUrl,siteKey:configuration.siteKey});
  if(identity)try{sessionStorage.setItem(returnConfigurationKey,JSON.stringify(identity));}catch{/* Public hints are optional. */}
  let locale: 'en' | 'pt' = configuration.locale.toLowerCase().startsWith('pt') ? 'pt' : 'en';
  const parameters = new URLSearchParams(location.search), page = document.documentElement.dataset;
  const section = document.createElement('section'); section.className = 'product-card subscription-demo'; section.id = 'subscription-demo';
  const title = document.createElement('h2'); title.id = 'subscription-demo-title'; section.setAttribute('aria-labelledby', title.id);
  const controls = document.createElement('div'); controls.className = 'subscription-demo-controls';
  const english = document.createElement('button'), portuguese = document.createElement('button'), theme = document.createElement('button');
  // These accessible names are the stable host integration controls.
  english.textContent = 'English'; portuguese.textContent = 'Português'; theme.textContent = 'Subscription theme';
  for (const button of [english, portuguese, theme]) button.type = 'button';
  theme.setAttribute('aria-pressed', 'false'); controls.append(english, portuguese, theme);
  const explanation = document.createElement('p'), protectedNote = document.createElement('p'), portal = document.createElement('p');
  portal.setAttribute('role', 'status'); portal.setAttribute('aria-live', 'polite'); portal.hidden = parameters.get('subscriptionPortal') !== 'simulated';
  const form = document.createElement('form'), error = document.createElement('p'); error.id = 'subscription-configuration-error'; error.setAttribute('role', 'alert'); error.hidden = true;
  const products = [1, 2].map(index => {
    const container = document.createElement('section'); container.className = 'subscription-product';
    const heading = document.createElement('h3'); heading.id = `subscription-product-${index}-title`; container.setAttribute('aria-labelledby', heading.id);
    const productLabel = document.createElement('label'), priceLabel = document.createElement('label');
    const product = document.createElement('input'), price = document.createElement('input');
    product.id = `subscription-product-${index}`; price.id = `subscription-price-${index}`; productLabel.htmlFor = product.id; priceLabel.htmlFor = price.id;
    product.required = index === 1; for (const input of [product, price]) { input.type = 'text'; input.maxLength = 36; input.pattern = uuid.source.slice(1, -1).replaceAll('a-f', 'a-fA-F'); input.setAttribute('aria-describedby', error.id); }
    product.value = parameters.get(`subscription_product_${index}_id`) ?? page[`subscriptionProduct${index}Id`] ?? '';
    price.value = parameters.get(`subscription_price_${index}_id`) ?? page[`subscriptionPrice${index}Id`] ?? '';
    form.append(productLabel, product, priceLabel, price);
    const manager = document.createElement('generic-subscription-manager') as GenericSubscriptionManager;
    manager.client = client; manager.locale = locale;
    container.append(heading, manager); return { container, heading, productLabel, product, priceLabel, price, manager };
  });
  const load = document.createElement('button'); load.type = 'submit'; form.append(load, error);
  section.append(title, controls, explanation, form, portal, ...products.map(p => p.container), protectedNote);
  document.querySelector('main')?.append(section);
  const render = () => {
    const t = copy[locale]; section.lang = locale; title.textContent = t.title; explanation.textContent = t.configuration;
    protectedNote.textContent = t.protected; portal.textContent = t.portal; load.textContent = t.load; error.textContent = t.invalid;
    english.setAttribute('aria-pressed', String(locale === 'en')); portuguese.setAttribute('aria-pressed', String(locale === 'pt'));
    products.forEach((p, i) => { p.heading.textContent = `${t.product} ${i + 1}`; p.productLabel.textContent = `${t.productId} ${i + 1}`; p.priceLabel.textContent = `${t.priceId} ${i + 1}`; p.manager.locale = locale; });
  };
  const configure = (): void => {
    const invalid = products.flatMap(p => [p.product, p.price]).find(input => (input.required || input.value.trim()) && !uuid.test(input.value.trim()));
    if (!validSubscriptionProducts(products.map(p=>p.product.value.trim()))) { error.hidden=false; return; }
    if (invalid) { error.hidden = false; invalid.setAttribute('aria-invalid', 'true'); invalid.focus(); return; }
    error.hidden = true;
    const url = new URL(location.href);
    for (const [i, p] of products.entries()) {
      p.product.removeAttribute('aria-invalid'); p.price.removeAttribute('aria-invalid');
      p.container.hidden = !p.product.value.trim(); p.manager.priceId = p.price.value.trim() || undefined; p.manager.productId = p.product.value.trim() || undefined;
      // UUIDs are public configuration, never Customer/action capabilities. Keep
      // them through the server-controlled Fake portal return/reload.
      url.searchParams.set(`subscription_product_${i + 1}_id`, p.product.value.trim());
      if (p.price.value.trim()) url.searchParams.set(`subscription_price_${i + 1}_id`, p.price.value.trim());
      else url.searchParams.delete(`subscription_price_${i + 1}_id`);
    }
    history.replaceState(null, '', url);
  };
  form.addEventListener('submit', event => { event.preventDefault(); configure(); });
  for (const [button, value] of [[english, 'en'], [portuguese, 'pt']] as const) button.addEventListener('click', () => { locale = value; render(); onLocale(value); });
  theme.addEventListener('click', () => { const active = section.classList.toggle('subscription-theme-alternate'); theme.setAttribute('aria-pressed', String(active)); });
  render(); if (validSubscriptionProducts(products.map(p=>p.product.value.trim())) && products.every(p => !p.price.value || uuid.test(p.price.value))) configure();
  return { setSession(session) { for (const p of products) p.manager.customerSession = session; } };
}
