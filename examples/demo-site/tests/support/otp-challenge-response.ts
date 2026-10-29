import type { Page } from '@playwright/test';

/** Observe only status and field names; OTP challenge values stay inside the reader. */
export async function submitOtpChallenge(
  page: Pick<Page, 'waitForResponse'>,
  endpoint: string,
  inspectFields: boolean,
  submit: () => Promise<void>,
): Promise<{ status: number; fields?: string[] }> {
  // Capture at response arrival, before the keyboard action finishes or a later reload.
  const received = page.waitForResponse(response =>
    response.url() === endpoint && response.request().method() === 'POST').then(async response => ({
      status: response.status(),
      fields: inspectFields ? Object.keys(await response.json()).sort() : undefined,
    }));
  // Observe both rejections immediately, including a late response failure after submit fails.
  const [captured] = await Promise.all([received, Promise.resolve().then(submit)]);
  return captured;
}
