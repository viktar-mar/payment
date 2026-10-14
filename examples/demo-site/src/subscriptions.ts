import { registerGenericSubscriptionManager } from '@generic-payments/sdk';
import type { CustomerSession, GenericPaymentClient, GenericSubscriptionManager } from '@generic-payments/sdk';

const en = {
  title: 'Subscription demo', product: 'Product', productId: 'Product ID', priceId: 'Initial price ID (optional)',
  load: 'Load products', configuration: 'Enter the two published Product UUIDs from your local Site. Prices and permissions come from the server.',
  invalid: 'Enter valid Product UUIDs and optional price UUIDs.',
  protected: 'Protected content requires backend or serverless verification of subscription access. This browser does not authorize protected content.',
  portal: 'Returned from the local Fake portal simulation. Subscription state is reloaded from the server after sign-in recovery.',
} as const;
const copy = {
  en,
  pt: {
    title: 'Demonstração de subscrições', product: 'Produto', productId: 'ID do produto', priceId: 'ID do preço inicial (opcional)',
    load: 'Carregar produtos', configuration: 'Introduza os UUIDs dos dois produtos publicados do Site local. Os preços e as permissões vêm do servidor.',
    invalid: 'Introduza UUIDs de produto válidos e UUIDs de preço opcionais.',
    protected: 'O conteúdo protegido exige verificação do acesso à subscrição no backend ou numa função serverless. Este browser não autoriza conteúdo protegido.',
    portal: 'Regresso da simulação local do portal Fake. O estado da subscrição é recarregado do servidor após recuperar a sessão.',
  },
} satisfies Record<'en' | 'pt', { [K in keyof typeof en]: string }>;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Public configuration only. Customer authority is passed explicitly by the OTP host. */
export function mountSubscriptions(configuration: { locale: string }, client: GenericPaymentClient, onLocale: (locale: 'en' | 'pt') => void): { setSession(session: CustomerSession | undefined): void } {
  registerGenericSubscriptionManager();
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
    product.required = true; for (const input of [product, price]) { input.type = 'text'; input.maxLength = 36; input.pattern = uuid.source.slice(1, -1).replaceAll('a-f', 'a-fA-F'); input.setAttribute('aria-describedby', error.id); }
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
    if (invalid) { error.hidden = false; invalid.setAttribute('aria-invalid', 'true'); invalid.focus(); return; }
    error.hidden = true;
    const url = new URL(location.href);
    for (const [i, p] of products.entries()) {
      p.product.removeAttribute('aria-invalid'); p.price.removeAttribute('aria-invalid');
      p.manager.priceId = p.price.value.trim() || undefined; p.manager.productId = p.product.value.trim();
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
  render(); if (products.every(p => uuid.test(p.product.value) && (!p.price.value || uuid.test(p.price.value)))) configure();
  return { setSession(session) { for (const p of products) p.manager.customerSession = session; } };
}
