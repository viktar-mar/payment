import {test} from '@playwright/test';
import {createOwnedBillingFirstPayment} from '../../../infrastructure/stripe-billing-first-payment.mjs';
import {snapshotBillingFirstPaymentEnvironment} from '../../../infrastructure/stripe-billing-first-payment-options.mjs';
import {runFirstPaymentBrowser} from './support/stripe-billing-first-payment';

let owner: Awaited<ReturnType<typeof createOwnedBillingFirstPayment>>;
test.beforeAll(async () => {
  const raw = process.env;
  const env = snapshotBillingFirstPaymentEnvironment(raw);
  if (!env) throw new Error('first_payment_OPT_IN_REQUIRED');
  const ports = {backend:0,demo:0,receiver:0,postgres:0,mailpitHttp:0,mailpitSmtp:0};
  const integer = (value: string | undefined): number => typeof value === 'string' && /^[1-9][0-9]*$/.test(value) && Number.isSafeInteger(Number(value)) ? Number(value) : 0;
  for (const name of Object.keys(ports) as (keyof typeof ports)[]) ports[name] = integer(raw['PAYMENT_STRIPE_SANDBOX_PORT_' + name.toUpperCase()]);
  owner = await createOwnedBillingFirstPayment({
    env, ports,
    runId:raw.PAYMENT_STRIPE_SANDBOX_RUN_ID,
    expectedSource:raw.PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE,
    daemon:raw.PAYMENT_STRIPE_SANDBOX_DAEMON,
    controlPort:integer(raw.PAYMENT_STRIPE_BILLING_FIRST_PAYMENT_CONTROL_PORT),
    frozenTime:integer(raw.PAYMENT_STRIPE_BILLING_FIRST_PAYMENT_FROZEN_TIME),
    approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},
    selectedCase:'initial_success',
  });
});
test('OWNED_INITIAL_PAYMENT: one READY clock EUR20 original v2 cash and signed callbacks', async ({browser}) => {
  const controller = new AbortController();
  const limits = {deadline:Date.now() + 2700000, signal:controller.signal};
  try {
    const result = await owner.run((port, scoped) => runFirstPaymentBrowser(browser, port, scoped), limits);
    if (result.scope !== 'OWNED_INITIAL_PAYMENT' || !result.initialAccepted || !result.cleanupConfirmed || result.full5bAccepted) throw new Error('first_payment_NOT_ACCEPTED');
  } finally {
    controller.abort();
    await owner.close();
  }
});
