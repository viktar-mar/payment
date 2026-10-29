import type { Page } from '@playwright/test';

/** Observe only status and field names; OTP challenge values stay in the browser. */
export async function submitOtpChallenge(
  page: Pick<Page, 'waitForResponse' | 'evaluateHandle'>,
  endpoint: string,
  inspectFields: boolean,
  submit: () => Promise<void>,
): Promise<{ status: number; fields?: string[] }> {
  // Chromium's CDP body store can be unavailable even at response arrival. Clone the
  // actual fetch response before delivering it to the SDK, without replaying a request.
  const observer = await page.evaluateHandle(({ endpoint, inspectFields }) => {
    const original = window.fetch;
    const owner = Symbol.for('generic-payments:test:otp-response-observer');
    if (Object.hasOwn(original, owner)) throw new Error('OTP response observation already active');
    let active = true;
    let resolve!: (value: { status: number; fields?: string[] }) => void;
    let reject!: (error: Error) => void;
    const result = new Promise<{ status: number; fields?: string[] }>((yes, no) => { resolve = yes; reject = no; });
    // A fast failure can precede the host attaching its handle reader.
    void result.catch(() => {});
    const restore = () => {
      active = false;
      if (window.fetch === observe) window.fetch = original;
    };
    const observe: typeof window.fetch = function(this: unknown, ...args: Parameters<typeof window.fetch>) {
      const [input, init] = args;
      if (!active || !(typeof input === 'string' || input instanceof URL || input instanceof Request)) return Reflect.apply(original, this, args);
      let url: string;
      try { url = input instanceof Request ? input.url : new URL(String(input), location.href).href; }
      catch { return Reflect.apply(original, this, args); }
      const method = init?.method ?? (input instanceof Request ? input.method : 'GET');
      if (url !== endpoint || method.toUpperCase() !== 'POST') return Reflect.apply(original, this, args);
      restore(); // Own only the first matching request; never clobber another fetch owner.
      return (async () => {
        let response: Response;
        try { response = await Reflect.apply(original, this, args); }
        catch (error) { reject(new Error('OTP response observation failed')); throw error; }
        try {
          resolve({ status: response.status, fields: inspectFields ? Object.keys(await response.clone().json()).sort() : undefined });
        } catch {
          // JSON parser messages can contain body values. Fail the observation without
          // exposing them or changing the response that the application receives.
          reject(new Error('OTP response observation failed'));
        }
        return response;
      })();
    };
    Object.defineProperty(observe, owner, { value: true });
    window.fetch = observe;
    return { result, dispose: () => { restore(); reject(new Error('OTP response observation cancelled')); } };
  }, { endpoint, inspectFields });
  try {
    // Keep Playwright's existing response deadline and exact request check; never read
    // its CDP body. Observe all rejections, including ones arriving after submit fails.
    const received = page.waitForResponse(response =>
      response.url() === endpoint && response.request().method() === 'POST');
    const captured = observer.evaluate(value => value.result);
    const [, fields] = await Promise.all([received, captured, Promise.resolve().then(submit)]);
    return fields;
  } finally {
    try { await observer.evaluate(value => value.dispose()); }
    finally { await observer.dispose(); }
  }
}
