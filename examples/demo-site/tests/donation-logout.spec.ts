import { expect, test } from '@playwright/test';
import { customerFixture, demoPath, signIn } from './support/customer-api';
import { enterPrivateText } from './support/private-entry';
import { entryCanary } from './support/entry-canary';

const cases = [
  { phase: 'draft', reject: true, journey: 'donateoutdraft' },
  { phase: 'intent', reject: false, journey: 'donateoutintent' },
  { phase: 'watch', reject: false, journey: 'donateoutok' },
  { phase: 'watch', reject: true, journey: 'donateouterr' },
] as const;

for (const scenario of cases) test(`pending Donation logout disposes ${scenario.phase} before ${scenario.reject ? 'rejected' : 'settled'} A revocation without disturbing B`, async ({ page }, info) => {
  const { api, fixture } = await customerFixture(scenario.journey, info.retry);
  const campaign = await api.customerRequest<{ id: string; version: number }>('POST', `/api/v1/admin/sites/${fixture.siteId}/donation-campaigns`, {
    key: 'help-campaign', display_name: 'Logout lifetime', purpose_description: null, currency: 'EUR', suggested_amounts: [4999, 1000], minimum_amount: 100, maximum_amount: 10000, custom_amount_allowed: true, anonymous_allowed: true,
  });
  await api.customerRequest('POST', `/api/v1/admin/sites/${fixture.siteId}/donation-campaigns/${campaign.id}/status`, { status: 'ACTIVE', expected_version: campaign.version });
  await page.goto(demoPath(api, fixture) + '&campaign_key=help-campaign');
  const customerA = await signIn(page);
  const donation = page.locator('generic-donation');
  await donation.getByRole('button', { name: 'Create donation' }).click();
  await expect(donation.getByRole('button', { name: 'Pay', exact: true })).toBeEnabled();
  // Reload restores A authentication, not its Donation capability. It also leaves a
  // fresh email component, so B is reachable during pending revocation on RED too.
  await page.reload();
  await expect(page.locator('#customer-summary')).toContainText(customerA);
  await page.getByRole('button', { name: 'Load my donations' }).click();
  await expect(page.locator('#customer-donations li')).toHaveCount(1);
  await expect(donation.getByRole('button', { name: 'Create donation' })).toBeEnabled();
  await page.evaluate(() => {
    const previous = window.fetch.bind(window);
    (window as any).logoutSignals = [];
    (window as any).oldDonationEvents = 0;
    (window as any).oldDonation = document.querySelector('generic-donation');
    for (const name of ['donation-created', 'donation-state', 'donation-succeeded']) (window as any).oldDonation.addEventListener(name, () => { (window as any).oldDonationEvents++; });
    window.fetch = (input, init) => {
      const url = String(input);
      if (url.includes('/api/v1/public/') && init?.signal) (window as any).logoutSignals.push(init.signal);
      return previous(input, init);
    };
  });
  await enterPrivateText(donation.getByLabel('Optional message'), entryCanary);
  let releaseWatch!: () => void;
  let watchStarted!: () => void;
  const watchGate = new Promise<void>(resolve => { releaseWatch = resolve; });
  const watching = new Promise<void>(resolve => { watchStarted = resolve; });
  if (scenario.phase === 'intent') {
    await page.route('**/donation-campaigns/help-campaign/checkouts', async route => {
      await route.fetch(); // Real local creation, deliberately ambiguous browser outcome.
      await route.abort('failed');
    }, { times: 1 });
    await donation.getByRole('button', { name: 'Create donation' }).click();
    await expect(donation.getByRole('alert')).toHaveText('Unable to complete donation. Please try again.');
  } else if (scenario.phase === 'watch') {
    await donation.getByRole('button', { name: 'Create donation' }).click();
    await expect(donation.getByRole('button', { name: 'Pay', exact: true })).toBeEnabled();
    await page.route('**/api/v1/public/checkouts/*', async route => {
      if (route.request().method() !== 'GET') { await route.continue(); return; }
      watchStarted();
      await watchGate;
      try { await route.continue(); } catch { /* Expected exact old-generation abort. */ }
    }, { times: 1 });
    await donation.getByRole('button', { name: 'Pay', exact: true }).click();
    await watching;
  }
  if (scenario.reject) await api.expireCustomerSession(fixture.siteId, customerA);
  let releaseLogout!: () => void;
  let logoutStarted!: () => void;
  let logoutStatus = 0;
  const logoutGate = new Promise<void>(resolve => { releaseLogout = resolve; });
  const loggingOut = new Promise<void>(resolve => { logoutStarted = resolve; });
  await page.route('**/api/v1/customer/session', async route => {
    if (route.request().method() !== 'DELETE') { await route.continue(); return; }
    const response = await route.fetch();
    logoutStatus = response.status();
    logoutStarted();
    await logoutGate;
    await route.fulfill({ response });
  }, { times: 1 });
  // Teardown deliberately starts a replacement campaign read. Observe only A's
  // requests, not the new anonymous generation created by local logout itself.
  await page.evaluate(() => { (window as any).ownedLogoutSignals = [...(window as any).logoutSignals]; });
  await page.getByRole('button', { name: 'Log out', exact: true }).click();
  await loggingOut;
  if (scenario.phase !== 'draft') expect(await page.evaluate(() => (window as any).ownedLogoutSignals.length > 0), 'intent/watcher case observes an owned public request signal').toBe(true);
  const immediate = await page.evaluate(() => {
    const old = (window as any).oldDonation as HTMLElement;
    (window as any).eventsAtLogout = (window as any).oldDonationEvents;
    return {
      disconnected: !old.isConnected,
      messageDisposed: (old.shadowRoot!.querySelector('textarea') as HTMLTextAreaElement).value === '',
      actionDisposed: (old.shadowRoot!.querySelector('button[type=submit]') as HTMLButtonElement).disabled,
      signalsAborted: (window as any).ownedLogoutSignals.every((signal: AbortSignal) => signal.aborted),
      historyCleared: document.querySelectorAll('#customer-donations li').length === 0,
      authorityCleared: !Object.keys(sessionStorage).some(key => key.startsWith('generic-payments:customer:v1:')),
      summaryCleared: document.querySelector('#customer-summary')!.textContent === 'Not signed in.',
    };
  });
  // Soft keeps RED bounded and lets the same test prove newer B ownership later.
  expect.soft(immediate, 'local Donation lifetime ends before pending revocation settles').toEqual({ disconnected: true, messageDisposed: true, actionDisposed: true, signalsAborted: true, historyCleared: true, authorityCleared: true, summaryCleared: true });
  let oldActions = 0;
  const countAction = (request: import('@playwright/test').Request) => { if (request.method() === 'POST' && /\/public\/.*(?:checkouts|confirm)/.test(request.url())) ++oldActions; };
  page.on('request', countAction);
  await page.evaluate(() => ((window as any).oldDonation as HTMLElement).shadowRoot!.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => resolve())));
  page.off('request', countAction);
  expect.soft(oldActions, 'disposed A cannot create or confirm during pending revocation').toBe(0);
  const customerB = await signIn(page);
  expect(customerB !== customerA, 'replacement Customer is distinct').toBe(true);
  await donation.getByRole('button', { name: 'Create donation' }).click();
  await expect(donation.getByRole('button', { name: 'Pay', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: 'Load my donations' }).click();
  await expect(page.locator('#customer-donations li')).toHaveCount(1);
  await page.evaluate(() => {
    (window as any).replacementDonation = document.querySelector('generic-donation');
    const key = Object.keys(sessionStorage).find(key => key.startsWith('generic-payments:customer:v1:'))!;
    (window as any).replacementRecord = sessionStorage.getItem(key);
    (window as any).replacementHistory = document.querySelector('#customer-donations')!.textContent;
  });
  releaseWatch();
  releaseLogout();
  await page.unrouteAll({ behavior: 'wait' });
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
  expect(logoutStatus, 'late response is actual success or authentication rejection').toBe(scenario.reject ? 401 : 204);
  expect(await page.evaluate(() => {
    const key = Object.keys(sessionStorage).find(key => key.startsWith('generic-payments:customer:v1:'));
    return document.querySelector('generic-donation') === (window as any).replacementDonation &&
      Boolean(key) && sessionStorage.getItem(key!) === (window as any).replacementRecord &&
      document.querySelector('#customer-donations')!.textContent === (window as any).replacementHistory &&
      (window as any).oldDonationEvents === (window as any).eventsAtLogout;
  }), 'late A cannot remount B, clear B authority/history, or publish obsolete events').toBe(true);
  await expect(page.locator('#customer-summary')).toContainText(customerB);
  await expect(page.locator('#customer-action-status')).toHaveText('Donations loaded.');
  await expect(page.locator('generic-email-otp').getByRole('status')).toHaveText('Signed in.');
  await expect(donation.getByRole('button', { name: 'Pay', exact: true })).toBeEnabled();
});
