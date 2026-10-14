import { createGenericPaymentClient, defineGenericDonation, defineGenericEmailOtp, defineGenericPaymentWidget, PaymentSdkError, recoverCustomerSession } from "@generic-payments/sdk";
import type { CustomerSession } from "@generic-payments/sdk";
import { mountSubscriptions } from './subscriptions';

import "./styles.css";

const PUBLIC_CONFIGURATION_KEYS = ["api_base_url", "site_key", "price_key", "campaign_key", "locale"] as const;

type PublicConfigurationKey = (typeof PUBLIC_CONFIGURATION_KEYS)[number];

type DemoConfiguration = {
  apiBaseUrl: string;
  siteKey: string;
  priceKey: string;
  locale: string;
  campaignKey?: string;
};

const SAFE_KEY_PATTERN = /^[A-Za-z0-9][A-Za-z0-9-]{2,62}$/;
const SAFE_LOCALE_PATTERN = /^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/;

const lifecycleCopy: Readonly<Record<string, string>> = {
  ready: "Checkout is ready for a one-time payment.",
  processing: "Payment confirmation is processing.",
  succeeded:
    "Payment succeeded in the browser. Fulfillment still requires trusted server-side status.",
  failed: "Payment did not complete. Follow the safe guidance in the widget.",
};

function queryOverride(parameters: URLSearchParams, key: PublicConfigurationKey): string | null {
  const value = parameters.get(key);
  return value === null ? null : value.trim();
}

function requireSafeKey(value: string): string {
  if (!SAFE_KEY_PATTERN.test(value)) {
    throw new Error("invalid public key");
  }
  return value;
}

function requireSafeLocale(value: string): string {
  if (value.length > 35 || !SAFE_LOCALE_PATTERN.test(value)) {
    throw new Error("invalid locale");
  }

  const canonical = Intl.getCanonicalLocales(value);
  const [canonicalLocale] = canonical;
  if (canonicalLocale === undefined || canonical.length !== 1) {
    throw new Error("invalid locale");
  }
  return canonicalLocale;
}

function requireSafeApiBaseUrl(value: string): string {
  const url = new URL(value);
  const isLoopbackHttp =
    url.protocol === "http:" && (url.hostname === "127.0.0.1" || url.hostname === "localhost");
  if (
    (url.protocol !== "https:" && !isLoopbackHttp) ||
    url.username !== "" ||
    url.password !== "" ||
    url.search !== "" ||
    url.hash !== "" ||
    url.pathname !== "/"
  ) {
    throw new Error("invalid public API base URL");
  }
  return url.origin;
}

function configurationFromPage(): DemoConfiguration {
  const page = document.documentElement.dataset;
  const parameters = new URLSearchParams(window.location.search);

  const apiBaseUrl = queryOverride(parameters, "api_base_url") ?? page.apiBaseUrl ?? "";
  const siteKey = queryOverride(parameters, "site_key") ?? page.siteKey ?? "";
  const priceKey = queryOverride(parameters, "price_key") ?? page.priceKey ?? "";
  const locale = queryOverride(parameters, "locale") ?? page.locale ?? "";
  const campaignKey = queryOverride(parameters, "campaign_key") ?? page.campaignKey;

  return {
    apiBaseUrl: requireSafeApiBaseUrl(apiBaseUrl),
    siteKey: requireSafeKey(siteKey),
    priceKey: requireSafeKey(priceKey),
    locale: requireSafeLocale(locale),
    ...(campaignKey ? { campaignKey: requireSafeKey(campaignKey) } : {}),
  };
}

function mountCheckout(): void {
  const widget = document.querySelector<HTMLElement>("#checkout-widget");
  const configurationMessage = document.querySelector<HTMLElement>("#configuration-message");
  const checkoutExplanation = document.querySelector<HTMLElement>("#checkout-explanation");
  if (widget === null || configurationMessage === null || checkoutExplanation === null) {
    return;
  }

  try {
    const configuration = configurationFromPage();
    widget.setAttribute("api-base-url", configuration.apiBaseUrl);
    widget.setAttribute("site-key", configuration.siteKey);
    widget.setAttribute("price-key", configuration.priceKey);
    widget.setAttribute("locale", configuration.locale);
    defineGenericPaymentWidget();
    mountDonation(configuration);
    mountCustomer(configuration);
  } catch {
    widget.hidden = true;
    configurationMessage.hidden = false;
    return;
  }

  for (const [eventName, safeCopy] of Object.entries(lifecycleCopy)) {
    widget.addEventListener(eventName, () => {
      checkoutExplanation.textContent = safeCopy;
    });
  }
}

mountCheckout();

function mountDonation(configuration: DemoConfiguration): void {
  const section = document.querySelector<HTMLElement>('#donation-section');
  let component = document.querySelector<HTMLElement>('#donation-widget');
  const anonymous = document.querySelector<HTMLInputElement>('#donation-anonymous');
  if (!section || !component || !anonymous || !configuration.campaignKey) return;
  section.hidden = false;
  for (const [attribute,value] of Object.entries({ 'api-base-url': configuration.apiBaseUrl, 'site-key': configuration.siteKey, 'campaign-key': configuration.campaignKey, locale: configuration.locale })) component.setAttribute(attribute,value);
  defineGenericDonation();
  anonymous.addEventListener('change', () => component!.setAttribute('customer-session',anonymous.checked ? 'omit' : 'auto'));
  // A changed Customer authority ends the previous live Donation intent/checkout.
  document.addEventListener('demo-customer-authority-changed', () => {
    const replacement = component!.cloneNode(false) as HTMLElement;
    component!.replaceWith(replacement); component = replacement;
  });
}

function mountCustomer(configuration: DemoConfiguration): void {
  const login = document.querySelector<HTMLElement>("#customer-login");
  const summary = document.querySelector<HTMLElement>("#customer-summary");
  const status = document.querySelector<HTMLElement>("#customer-action-status");
  const payments = document.querySelector<HTMLUListElement>("#customer-payments");
  const pay = document.querySelector<HTMLButtonElement>("#customer-pay");
  const guest = document.querySelector<HTMLButtonElement>("#guest-pay");
  const history = document.querySelector<HTMLButtonElement>("#customer-history");
  const logout = document.querySelector<HTMLButtonElement>("#customer-logout");
  const donationHistory = document.querySelector<HTMLButtonElement>('#customer-donation-history');
  const donations = document.querySelector<HTMLUListElement>('#customer-donations');
  if (!login || !summary || !status || !payments || !pay || !guest || !history || !logout) return;
  const client = createGenericPaymentClient({ apiBaseUrl: configuration.apiBaseUrl });
  const subscriptionDemo = mountSubscriptions(configuration, client, locale => {
    configuration.locale = locale;
    for (const selector of ['#customer-login', '#donation-widget', '#checkout-widget']) document.querySelector(selector)?.setAttribute('locale', locale);
  });
  let session: CustomerSession | null = null;
  let busy = false;
  let generation = 0;
  let loginElement = login;
  const authorityChanged = (): void => {
    subscriptionDemo.setSession(session ?? undefined);
    document.dispatchEvent(new Event('demo-customer-authority-changed'));
  };
  const resetLogin = (): void => {
    // Remount only when the current action owns the cleared authority.
    // A fresh component resets its private success state without a global SDK lifecycle API.
    const replacement = loginElement.cloneNode(false) as HTMLElement;
    replacement.addEventListener("customer-authenticated", () => { void restore(); });
    loginElement.replaceWith(replacement);
    loginElement = replacement;
  };
  const controls = (): void => {
    pay.disabled = busy || session === null;
    history.disabled = busy || session === null;
    logout.disabled = busy || session === null;
    guest.disabled = busy;
    if (donationHistory) donationHistory.disabled = busy || session === null;
  };
  const unavailable = (): void => {
    ++generation;
    session = null;
    busy = false;
    summary.textContent = "Session unavailable. Sign in again; Customer actions never fall back to guest.";
    payments.replaceChildren();
    donations?.replaceChildren();
    authorityChanged();
    resetLogin();
    controls();
  };
  const restore = async (): Promise<void> => {
    const current = ++generation;
    // A new authority owns the whole view, not just its summary.
    session = null;
    busy = true;
    payments.replaceChildren();
    donations?.replaceChildren();
    authorityChanged();
    status.textContent = "";
    summary.textContent = "Not signed in.";
    controls();
    const recovered = recoverCustomerSession(configuration);
    if (!recovered) { busy = false; controls(); return; }
    try {
      const customer = await client.getCurrentCustomer(recovered);
      if (current !== generation) return;
      session = recovered;
      busy = false;
      summary.textContent = `Customer ${customer.id} · ${customer.status}${customer.maskedEmail ? ` · ${customer.maskedEmail}` : ""}`;
      authorityChanged();
      controls();
    } catch { if (current === generation) unavailable(); }
  };
  for (const [attribute, value] of Object.entries({ "api-base-url": configuration.apiBaseUrl, "site-key": configuration.siteKey, locale: configuration.locale })) login.setAttribute(attribute, value);
  login.addEventListener("customer-authenticated", () => { void restore(); });
  defineGenericEmailOtp();
  void restore();
  const action = async (operation: (ownedSession: CustomerSession | null, ownsCurrent: () => boolean) => Promise<void>): Promise<void> => {
    if (busy) return;
    const current = ++generation;
    const ownedSession = session;
    const ownsCurrent = (): boolean => current === generation;
    busy = true; controls(); status.textContent = "Please wait.";
    try { await operation(ownedSession, ownsCurrent); }
    catch (error) {
      if (!ownsCurrent()) return;
      status.textContent = "Unable to complete this action. Please try again.";
      if ((error instanceof PaymentSdkError && ["INVALID_SESSION", "SESSION_EXPIRED", "AUTHENTICATION_FAILED"].includes(error.code)) ||
          (ownedSession && Date.parse(ownedSession.expiresAt) <= Date.now())) unavailable();
    } finally { if (ownsCurrent()) { busy = false; controls(); } }
  };
  const checkout = async (asGuest: boolean, current: CustomerSession | null, ownsCurrent: () => boolean): Promise<void> => {
    if (!asGuest) {
      const ownsStoredToken = (): boolean => current !== null && recoverCustomerSession(configuration)?.sessionToken === current.sessionToken;
      if (!current || !ownsStoredToken()) throw new PaymentSdkError("INVALID_SESSION");
      await client.getCurrentCustomer(current);
      if (!ownsCurrent()) return;
      // A storage change during the read must not turn the explicit Customer action into guest checkout.
      if (session !== current || !ownsStoredToken()) throw new PaymentSdkError("INVALID_SESSION");
    }
    const checkout = await client.createCatalogCheckout({ siteKey: configuration.siteKey, priceKey: configuration.priceKey,
      ...(asGuest ? { customerSession: "omit" as const } : {}) });
    if (!ownsCurrent()) return;
    const result = await client.confirm(checkout);
    if (!ownsCurrent()) return;
    status.textContent = `Payment ${result.paymentId} · ${result.status}. Fulfillment requires trusted server-side status.`;
  };
  pay.addEventListener("click", () => { void action((current, ownsCurrent) => checkout(false, current, ownsCurrent)); });
  guest.addEventListener("click", () => { void action((current, ownsCurrent) => checkout(true, current, ownsCurrent)); });
  history.addEventListener("click", () => { void action(async (current, ownsCurrent) => {
    if (!current) throw new Error("Customer authentication required");
    const page = await client.listCustomerPayments(current, { limit: 20 });
    if (!ownsCurrent()) return;
    payments.replaceChildren(...page.items.map(payment => {
      const item = document.createElement("li");
      item.textContent = `${payment.paymentId} · ${payment.amountMinor} ${payment.currency} minor units · ${payment.status}`;
      return item;
    }));
    status.textContent = page.items.length ? "Payments loaded." : "No payments yet.";
  }); });
  donationHistory?.addEventListener('click', () => { void action(async (current, ownsCurrent) => {
    if (!current) throw new PaymentSdkError('INVALID_SESSION');
    const page = await client.listCustomerDonations(current, { limit: 20 });
    if (!ownsCurrent()) return;
    donations?.replaceChildren(...page.items.map(donation => {
      const item = document.createElement('li');
      item.textContent = `${donation.donationId} · ${donation.amountMinor} ${donation.currency} minor units · ${donation.status} · message ${donation.messageState}`;
      return item;
    }));
    status.textContent = page.items.length ? 'Donations loaded.' : 'No donations yet.';
  }); });
  logout.addEventListener("click", () => { void action(async (current, ownsCurrent) => {
    if (!current) return;
    // The captured session owns remote revocation; local Donation lifetime ends now,
    // even if that request stalls. A newer login owns any later view changes.
    session = null;
    payments.replaceChildren();
    donations?.replaceChildren();
    authorityChanged();
    summary.textContent = "Not signed in.";
    resetLogin();
    await client.logoutCustomerSession(current);
    if (!ownsCurrent()) return;
    status.textContent = "Logged out.";
  }); });
  window.setInterval(() => {
    if (session && Date.parse(session.expiresAt) <= Date.now()) {
      const expired = session;
      unavailable();
      void client.getCurrentCustomer(expired).catch(() => { /* SDK exact-token cleanup is fail-closed. */ });
    }
  }, 1000);
}
