import {test} from '@playwright/test';
import {createOwnedBillingFirstRenewal, formatBillingFirstRenewalDiagnostic} from '../../../infrastructure/stripe-billing-first-payment.mjs';
import {snapshotBillingFirstRenewalEnvironment} from '../../../infrastructure/stripe-billing-first-renewal-options.mjs';
import {runFirstPaymentBrowser} from './support/stripe-billing-first-payment';
import {firstRenewalBrowserMode} from './support/stripe-billing-first-renewal-browser-mode';

let owner: Awaited<ReturnType<typeof createOwnedBillingFirstRenewal>>;
test.beforeAll(async () => {
  const raw = process.env;
  const env = snapshotBillingFirstRenewalEnvironment(raw);
  if (!env) throw new Error('first_renewal_OPT_IN_REQUIRED');
  const ports = {backend:0,demo:0,receiver:0,postgres:0,mailpitHttp:0,mailpitSmtp:0};
  const integer = (value: string | undefined): number => typeof value === 'string' && /^[1-9][0-9]*$/.test(value) && Number.isSafeInteger(Number(value)) ? Number(value) : 0;
  for (const name of Object.keys(ports) as (keyof typeof ports)[]) ports[name] = integer(raw['PAYMENT_STRIPE_SANDBOX_PORT_' + name.toUpperCase()]);
  owner = await createOwnedBillingFirstRenewal({
    env, ports,
    runId:raw.PAYMENT_STRIPE_SANDBOX_RUN_ID,
    expectedSource:raw.PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE,
    daemon:raw.PAYMENT_STRIPE_SANDBOX_DAEMON,
    controlPort:integer(raw.PAYMENT_STRIPE_BILLING_FIRST_RENEWAL_CONTROL_PORT),
    frozenTime:integer(raw.PAYMENT_STRIPE_BILLING_FIRST_RENEWAL_FROZEN_TIME),
    approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},
    selectedCase:'initial_and_first_renewal',
  });
});
test('OWNED_INITIAL_AND_FIRST_RENEWAL: EUR20 original v2 plus one EUR20 renewal and four signed callbacks', async ({browser},testInfo) => {
  const controller = new AbortController();
  const limits = {deadline:Date.now() + 2700000, signal:controller.signal};
  try {
    const mode = firstRenewalBrowserMode(testInfo.project.name);
    const result = await owner.run((port, scoped) => runFirstPaymentBrowser(browser, port, scoped, mode), limits);
    if (result.scope !== 'OWNED_INITIAL_AND_FIRST_RENEWAL' || !result.initialAccepted || !result.renewalAccepted || !result.cleanupConfirmed || result.full5bAccepted) throw new Error('first_renewal_NOT_ACCEPTED');
  } finally {
    controller.abort();
    const final = await owner.close();
    const diagnostic = formatBillingFirstRenewalDiagnostic(final);
    if (diagnostic) process.stdout.write(diagnostic + '\n');
  }
});
