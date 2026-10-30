import type {Browser, BrowserContext, Route} from '@playwright/test';
import type {BillingBinding, BillingLimits} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
import type {BillingFirstPaymentPort, FirstPaymentPrepared} from '../../../../infrastructure/stripe-billing-first-payment.mjs';
import {billingBounded, billingCheck} from '../../../../infrastructure/stripe-billing-limits.mjs';
// @ts-ignore Explicit source import for dependency-free Node tests.
import {enterPrivateText} from './private-entry.ts';

const fail = (): never => {throw new Error('first_payment_BROWSER');};
const need = (value: unknown): void => {if (!value) fail();};
const exact = (value: unknown, keys: string[]): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).sort().join(',') === [...keys].sort().join(',');
const remaining = (limits: BillingLimits): number => {
  billingCheck(limits);
  return Math.min(45000, limits.deadline - Date.now());
};

/** Dedicated test transport only. No public handler, fetch override or synthetic SDK binding. */
export function createFirstPaymentBridge(options: {
  binding: BillingBinding;
  prepared: FirstPaymentPrepared;
  token: string;
  port: Pick<BillingFirstPaymentPort, 'confirm'>;
  limits: BillingLimits;
}): (route: Pick<Route, 'request' | 'fulfill'>) => Promise<void> {
  const {binding, prepared, token, port, limits} = options;
  const original = `${binding.endpoints.backend}/api/v1/customer/subscriptions/${prepared.subscription_id}/operations/${prepared.operation_id}`;
  let step = 0;
  return async route => {
    try {
      billingCheck(limits);
      need(step < 3 && Date.now() < Date.parse(prepared.session.expires_at));
      const request = route.request();
      const headers = await request.allHeaders();
      need(!request.redirectedFrom() && headers.authorization === 'Bearer ' + token && headers.origin === binding.endpoints.demo && !headers.cookie);
      const current = step++;
      let json: unknown;
      if (current === 0) {
        need(request.url() === original + '/action' && request.method() === 'GET' && !request.postData());
        json = prepared.action;
      } else {
        need(request.method() === 'POST' && headers['content-type'] === 'application/json');
        const key = headers['idempotency-key'];
        need(typeof key === 'string' && /^[A-Za-z0-9_.:/-]{1,255}$/.test(key));
        const value: unknown = JSON.parse(request.postData() ?? '');
        if (current === 1) {
          need(request.url() === original + '/confirmation-session' && exact(value, ['action_token']) && value.action_token === prepared.action.secret);
          const s = prepared.session;
          // The private DTO includes action_token:null; v2's existing parser forbids it.
          json = {protocol:s.protocol, provider:s.provider, flow:s.flow, publishable_key:s.publishable_key, client_secret:s.client_secret, attempt_id:s.attempt_id, capability:s.capability, expires_at:s.expires_at};
        } else {
          need(request.url() === original + '/confirmation-attempts/' + prepared.session.attempt_id + '/confirm');
          need(exact(value, ['capability', 'confirmation_token']) && value.capability === prepared.session.capability && typeof value.confirmation_token === 'string' && /^ctoken_[A-Za-z0-9]{1,200}$/.test(value.confirmation_token));
          const body = value as {capability:string;confirmation_token:string};
          const result = await billingBounded(limits, scoped => port.confirm({attempt_id:prepared.session.attempt_id, capability:body.capability, confirmation_token:body.confirmation_token, idempotency_key:key!}, scoped));
          // Reject 3DS before the driver can receive next-action permission.
          need(result.phase === 'OBSERVE' && result.protocol === 'SERVER_CONFIRM_V2' && result.attempt_id === prepared.session.attempt_id && result.state === 'OBSERVE' && result.retry_allowed === false && result.successor_attempt_id === null && result.next_action_allowed === false);
          for (const name of ['run_id','account_id','site_id','customer_id','price_id','subscription_id','operation_id'] as const) need(result[name] === prepared[name]);
          need(result.expires_at === prepared.session.expires_at);
          json = {protocol:result.protocol, attempt_id:result.attempt_id, state:result.state, retry_allowed:result.retry_allowed, successor_attempt_id:result.successor_attempt_id, next_action_allowed:result.next_action_allowed, expires_at:result.expires_at};
        }
      }
      billingCheck(limits);
      await route.fulfill({status:200, contentType:'application/json', headers:{'Cache-Control':'no-store','Access-Control-Allow-Origin':binding.endpoints.demo}, json});
      billingCheck(limits);
    } catch {
      step = 3;
      fail();
    }
  };
}

/** Genuine OTP and one real driver; settlement remains exclusively with the Node owner. */
export async function runFirstPaymentBrowser(browser: Browser, port: BillingFirstPaymentPort, limits: BillingLimits): Promise<true> {
  let context: BrowserContext | undefined;
  let abandoned = false;
  let closing: Promise<void> | undefined;
  let failure = false;
  const close = (value: BrowserContext): Promise<void> => {
    closing ??= value.close();
    void closing.catch(() => {});
    return closing;
  };
  const abort = (): void => {
    abandoned = true;
    if (context) void close(context);
  };
  const opening = browser.newContext({serviceWorkers:'block', acceptDownloads:false});
  void opening.then(value => {if (abandoned) return close(value);}, () => {}).catch(() => {});
  limits.signal?.addEventListener('abort', abort, {once:true});
  const pending = new Set<Promise<void>>();
  try {
    context = await billingBounded(limits, () => opening);
    const page = await billingBounded(limits, () => context!.newPage());
    const waitPhase = async (expected: string): Promise<void> => {
      await page.waitForFunction(value => [value,'STOPPED'].includes(document.body.dataset.phase ?? ''), expected, {timeout:remaining(limits)});
      need(await page.locator('body').getAttribute('data-phase') === expected);
    };
    const binding = port.resources();
    const otp = binding.endpoints.backend + '/api/v1/public/customer-auth/email-otp/challenges';
    let bridge: ReturnType<typeof createFirstPaymentBridge> | undefined;
    let prepared = false;
    let challenges = 0;
    let verifies = 0;
    let documents = 0;
    page.on('framenavigated', frame => {
      if (frame === page.mainFrame() && ++documents > 1) {failure = true; abort();}
    });
    await context.exposeBinding('__firstPaymentPrepare', async ({frame}, input: unknown) => {
      try {
        billingCheck(limits);
        need(!failure && !prepared && frame === page.mainFrame() && new URL(frame.url()).origin === binding.endpoints.demo);
        prepared = true;
        need(exact(input, ['token','origin','customerId','siteId']) && input.origin === binding.endpoints.demo && input.siteId === binding.siteId && typeof input.customerId === 'string' && typeof input.token === 'string' && /^[A-Za-z0-9_-]{43}$/.test(input.token));
        const value = input as {token:string;origin:string;customerId:string;siteId:string};
        await port.acknowledgeCustomer({customerId:value.customerId, siteId:value.siteId});
        const result = await port.prepare({token:value.token, origin:value.origin}, crypto.randomUUID(), limits);
        bridge = createFirstPaymentBridge({binding, prepared:result, token:value.token, port, limits});
        return {subscriptionId:result.subscription_id, operationId:result.operation_id};
      } catch {
        failure = true;
        abort();
        fail();
      }
    });
    await context.route(binding.endpoints.backend + '/**', route => {
      const work = (async () => {
        try {
          billingCheck(limits);
          need(!failure);
          const request = route.request();
          if (request.method() === 'OPTIONS') {
            need(request.url() === otp || request.url().startsWith(otp + '/') || request.url().startsWith(binding.endpoints.backend + '/api/v1/customer/subscriptions/'));
            await route.fulfill({status:204, headers:{'Access-Control-Allow-Origin':binding.endpoints.demo,'Access-Control-Allow-Methods':'GET, POST','Access-Control-Allow-Headers':'Content-Type, Authorization, Idempotency-Key','Cache-Control':'no-store'}});
            return;
          }
          if (request.url() === otp && request.method() === 'POST') {
            need(++challenges === 1);
            const value: unknown = JSON.parse(request.postData() ?? '');
            need(exact(value, ['site_key','email','intent']) && value.site_key === binding.siteKey && value.email === binding.email && value.intent === 'AUTHENTICATE');
            await route.fallback();
            return;
          }
          if (request.url().startsWith(otp + '/') && /^\/[0-9a-f-]{36}\/verify$/.test(request.url().slice(otp.length)) && request.method() === 'POST') {
            need(++verifies === 1 && challenges === 1);
            await route.fallback();
            return;
          }
          need(bridge);
          await bridge!(route);
        } catch {
          failure = true;
          await route.abort('failed').catch(() => {});
          abort();
        }
      })();
      pending.add(work);
      void work.finally(() => pending.delete(work));
      return work;
    });
    await page.goto(binding.endpoints.demo + '/billing-first-payment.html', {timeout:remaining(limits)});
    await page.waitForFunction(() => typeof (window as any).__firstPaymentConfigure === 'function', undefined, {timeout:remaining(limits)});
    await page.evaluate(value => (window as any).__firstPaymentConfigure(value), {backend:binding.endpoints.backend, siteKey:binding.siteKey, email:binding.email, deadline:Math.min(limits.deadline, Date.now() + 300000)});
    await page.getByRole('button', {name:'Request code', exact:true}).click({timeout:remaining(limits)});
    await waitPhase('CODE');
    await port.withOtpCode(async code => {
      await enterPrivateText(page.getByRole('textbox', {name:'Six-digit code', exact:true}), code);
      return true as const;
    }, limits);
    await page.getByRole('button', {name:'Verify code', exact:true}).click({timeout:remaining(limits)});
    await waitPhase('AUTHENTICATED');
    await page.getByRole('checkbox', {name:'I consent to recurring payments', exact:true}).check({timeout:remaining(limits)});
    await page.getByRole('button', {name:'Start payment', exact:true}).click({timeout:remaining(limits)});
    await waitPhase('READY');
    need(await page.getByRole('button', {name:'Pay', exact:true}).isEnabled());
    const frame = page.locator('#provider').frameLocator('iframe[title="Secure payment input frame"]');
    await frame.locator('input[name="number"]').fill('4242424242424242', {timeout:remaining(limits)});
    await frame.locator('input[name="expiry"]').fill('1230', {timeout:remaining(limits)});
    await frame.locator('input[name="cvc"]').fill('123', {timeout:remaining(limits)});
    const postal = frame.locator('input[name="postalCode"]');
    if (await postal.isVisible()) await postal.fill('10001', {timeout:remaining(limits)});
    await page.getByRole('button', {name:'Pay', exact:true}).click({timeout:remaining(limits)});
    await waitPhase('OBSERVE');
    billingCheck(limits);
    need(!failure && prepared && challenges === 1 && verifies === 1);
    return true;
  } catch {
    return fail();
  } finally {
    abandoned = true;
    limits.signal?.removeEventListener('abort', abort);
    if (context) await billingBounded({deadline:Date.now() + 15000}, async () => {
      await close(context!);
      await Promise.allSettled(pending);
    });
  }
}
