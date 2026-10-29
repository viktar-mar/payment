import { expect, test, type Page, type Request } from "@playwright/test";
import { AdminApi, type ProvisionedCheckout } from "./support/admin-api";

const DEMO_ORIGIN = process.env.PAYMENT_E2E_DEMO_ORIGIN ?? "http://127.0.0.1:4173";
const TOKEN_SHAPE = /\b[A-Za-z0-9_-]{43}\b/;

interface EventInspection {
  count: number;
  hasUnexpectedField: boolean;
  hasTokenLikeValue: boolean;
}

class BrowserAudit {
  private readonly observations: Promise<void>[] = [];
  private unsafeRequest = false;
  private tokenInUrl = false;
  private createBodies: string[][] = [];
  private confirmCount = 0;
  private requestCount = 0;
  private unsafeConsole = false;

  constructor(page: Page) {
    page.on("request", (request) => {
      this.requestCount += 1;
      this.observations.push(this.inspect(request).catch(() => { this.unsafeRequest = true; }));
    });
    page.on("console", (message) => {
      this.unsafeConsole ||= TOKEN_SHAPE.test(message.text()) || /Bearer\s|XSRF|task12-admin-password|task12-database-password|gpk_/i.test(message.text());
    });
  }

  get confirmations(): number {
    return this.confirmCount;
  }

  async expectSafe(): Promise<void> {
    await Promise.all(this.observations);
    expect(this.unsafeRequest).toBe(false);
    expect(this.tokenInUrl).toBe(false);
    expect(this.unsafeConsole).toBe(false);
    expect(this.createBodies).toContainEqual(["price_key"]);
    console.log(`Browser audit: ${this.requestCount} requests; ${this.confirmCount} confirms; forbidden request/URL/console sinks=0`);
  }

  private async inspect(request: Request): Promise<void> {
    const url = new URL(request.url());
    const headers = await request.allHeaders();
    const headerNames = Object.keys(headers).map((name) => name.toLowerCase());
    if (
      url.pathname.startsWith("/api/admin/") ||
      url.pathname.startsWith("/api/v1/admin/") ||
      url.pathname.startsWith("/api/v1/partner/") ||
      headerNames.includes("cookie") ||
      headerNames.includes("x-xsrf-token") ||
      headerNames.includes("x-api-key")
    ) {
      this.unsafeRequest = true;
    }
    if (headers.authorization && (!/^Bearer [A-Za-z0-9_-]{43}$/.test(headers.authorization) || !/^\/api\/v1\/public\/checkouts\/[^/]+(?:\/confirm)?$/.test(url.pathname))) {
      this.unsafeRequest = true;
    }
    if (TOKEN_SHAPE.test(`${url.pathname}${url.search}${url.hash}`) || /checkout[_-]?token|bearer|token=|task12-admin-password|task12-database-password|gpk_/i.test(`${url.pathname}${url.search}${url.hash}`)) {
      this.tokenInUrl = true;
    }
    if (request.method() !== "POST") return;
    if (/\/api\/v1\/public\/sites\/[^/]+\/checkouts$/.test(url.pathname)) {
      this.createBodies.push(bodyKeys(request));
    }
    if (/\/api\/v1\/public\/checkouts\/[^/]+\/confirm$/.test(url.pathname)) {
      this.confirmCount += 1;
      expect(bodyKeys(request)).toEqual(["payment_method_family"]);
    }
  }
}

test.describe("one-time checkout demo", () => {
  let admin: AdminApi;

  test.beforeEach(async () => {
    admin = await AdminApi.login();
  });

  test("succeeds after provider truth and reconciliation without browser admin authority", async ({ page }, testInfo) => {
    const fixture = await admin.provision("success", testInfo.retry);
    const audit = new BrowserAudit(page);
    await openDemo(page, admin, fixture);

    const start = page.getByRole("button", { name: "Start checkout" });
    await start.focus();
    await expect(start).toBeFocused();
    expect(await start.evaluate((element) => getComputedStyle(element).outlineStyle)).not.toBe("none");
    await start.press("Enter");
    await expect(widgetStatus(page)).toContainText("Ready to pay");
    const payment = await admin.waitForSinglePayment(fixture.siteId);

    const confirm = page.waitForResponse(isConfirmResponse);
    await page.getByRole("button", { name: "Pay", exact: true }).press("Space");
    expect((await confirm).status()).toBe(200);
    await expect(widgetStatus(page)).toContainText("Payment is processing");

    await admin.fakeEvent(payment.payment_id, "PAYMENT_SUCCEEDED");
    await admin.reconcile(payment.payment_id);
    await expect(widgetStatus(page)).toContainText("Payment succeeded");
    await expect(page.locator("#checkout-explanation")).toContainText(
      "Fulfillment still requires trusted server-side status.",
    );
    const details = await admin.waitForStatus(payment.payment_id, "SUCCEEDED");
    expect(details.attempts).toHaveLength(1);
    expect(await inspectEvents(page)).toEqual({ count: 3, hasUnexpectedField: false, hasTokenLikeValue: false });
    await expectNoSensitivePresentation(page);
    await audit.expectSafe();
  });

  test("retries a recoverable decline on the same checkout with a fresh attempt", async ({ page }, testInfo) => {
    const fixture = await admin.provision("decline", testInfo.retry);
    const audit = new BrowserAudit(page);
    await openDemo(page, admin, fixture);
    await page.getByRole("button", { name: "Start checkout" }).click();
    await expect(widgetStatus(page)).toContainText("Ready to pay");
    const payment = await admin.waitForSinglePayment(fixture.siteId);
    await admin.setConfirmScenario(payment.payment_id, "RECOVERABLE_DECLINE");

    const declined = page.waitForResponse(isConfirmResponse);
    await page.getByRole("button", { name: "Pay", exact: true }).click();
    expect((await declined).status()).toBe(422);
    await expect(widgetStatus(page)).toContainText("Ready to pay");
    await admin.setConfirmScenario(payment.payment_id, null);

    const accepted = page.waitForResponse(isConfirmResponse);
    await page.getByRole("button", { name: "Pay", exact: true }).click();
    expect((await accepted).status()).toBe(200);
    await expect(widgetStatus(page)).toContainText("Payment is processing");
    await admin.fakeEvent(payment.payment_id, "PAYMENT_SUCCEEDED");
    await admin.reconcile(payment.payment_id);
    await expect(widgetStatus(page)).toContainText("Payment succeeded");

    const details = await admin.waitForStatus(payment.payment_id, "SUCCEEDED");
    expect(details.payment.payment_id).toBe(payment.payment_id);
    expect(details.attempts).toHaveLength(2);
    expect(await admin.paymentCount(fixture.siteId)).toBe(1);
    await expectNoSensitivePresentation(page);
    await audit.expectSafe();
  });

  test("presents processing as non-terminal and makes no fulfillment claim", async ({ page }, testInfo) => {
    const fixture = await admin.provision("processing", testInfo.retry);
    const audit = new BrowserAudit(page);
    await openDemo(page, admin, fixture);
    await page.getByRole("button", { name: "Start checkout" }).click();
    const payment = await admin.waitForSinglePayment(fixture.siteId);
    const confirm = page.waitForResponse(isConfirmResponse);
    await page.getByRole("button", { name: "Pay", exact: true }).click();
    expect((await confirm).status()).toBe(200);

    const status = widgetStatus(page);
    await expect(status).toContainText("Payment is processing");
    await expect(status).not.toContainText(/fulfilled|shipped|complete/i);
    await expect(page.getByText("Fulfillment must wait for trusted server-side payment status.")).toBeVisible();
    const details = await admin.details(payment.payment_id);
    expect(details.payment.status).toBe("PROCESSING");
    expect(details.attempts).toHaveLength(1);
    await expectNoSensitivePresentation(page);
    await audit.expectSafe();
  });

  test("blocks a duplicate unknown confirmation and resolves the original payment by reconciliation", async ({ page }, testInfo) => {
    const fixture = await admin.provision("unknown", testInfo.retry);
    const audit = new BrowserAudit(page);
    await openDemo(page, admin, fixture);
    await page.getByRole("button", { name: "Start checkout" }).click();
    const payment = await admin.waitForSinglePayment(fixture.siteId);
    await admin.setConfirmScenario(payment.payment_id, "UNKNOWN");

    const unknown = page.waitForResponse(isConfirmResponse);
    await page.getByRole("button", { name: "Pay", exact: true }).click();
    expect((await unknown).status()).toBe(503);
    await expect(widgetStatus(page)).toContainText("Payment result unknown. Check status before trying again.");
    expect(audit.confirmations).toBe(1);

    const statusRead = page.waitForResponse(isCheckoutReadResponse);
    await page.getByRole("button", { name: "Check status" }).click();
    expect((await statusRead).request().method()).toBe("GET");
    expect(audit.confirmations).toBe(1);
    const beforeResolution = await admin.details(payment.payment_id);
    expect(beforeResolution.attempts).toHaveLength(1);

    await admin.fakeEvent(payment.payment_id, "PAYMENT_SUCCEEDED");
    await admin.reconcile(payment.payment_id);
    await page.reload();
    await installEventInspection(page);
    await expect(widgetStatus(page)).toContainText("Payment succeeded");
    const resolved = await admin.waitForStatus(payment.payment_id, "SUCCEEDED");
    expect(resolved.payment.payment_id).toBe(payment.payment_id);
    expect(resolved.attempts).toHaveLength(1);
    expect(await admin.paymentCount(fixture.siteId)).toBe(1);
    await expectNoSensitivePresentation(page);
    await audit.expectSafe();
  });

  test("renders terminal provider failure without raw provider detail or a leaked token", async ({ page }, testInfo) => {
    const fixture = await admin.provision("failure", testInfo.retry);
    const audit = new BrowserAudit(page);
    await openDemo(page, admin, fixture);
    await page.getByRole("button", { name: "Start checkout" }).click();
    const payment = await admin.waitForSinglePayment(fixture.siteId);
    await page.getByRole("button", { name: "Pay", exact: true }).click();
    await expect(widgetStatus(page)).toContainText("Payment is processing");

    await admin.fakeEvent(payment.payment_id, "PAYMENT_FAILED");
    await admin.reconcile(payment.payment_id);
    await expect(widgetStatus(page)).toContainText("Payment could not be completed");
    const details = await admin.waitForStatus(payment.payment_id, "FAILED");
    expect(details.attempts).toHaveLength(1);
    expect(await page.locator("body").evaluate((element) => /PROVIDER_|PAYMENT_STATE_|fake_pi_/i.test(element.textContent ?? ""))).toBe(false);
    await expectNoSensitivePresentation(page);
    await audit.expectSafe();
  });

  test("reload restores the sessionStorage checkout at 320px without creating a second payment", async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 320, height: 720 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    const fixture = await admin.provision("reload", testInfo.retry);
    const audit = new BrowserAudit(page);
    await openDemo(page, admin, fixture);
    const start = page.getByRole("button", { name: "Start checkout" });
    await start.focus();
    await expect(start).toBeFocused();
    await start.press("Enter");
    await expect(widgetStatus(page)).toContainText("Ready to pay");
    const payment = await admin.waitForSinglePayment(fixture.siteId);

    expect(await inspectStorage(page, { apiBaseUrl: admin.backendUrl, siteKey: fixture.siteKey,
      priceKey: fixture.priceKey, locale: "en", paymentId: payment.payment_id })).toEqual({
      entryCount: 3,
      recoveryEntryCount: 1,
      publicConfigurationIsExact: true,
      subscriptionReturnConfigurationIsExact: true,
      keyIsNamespaced: true,
      hasExpectedFields: true,
      tokenLooksOpaque: true,
      localStorageIsEmpty: true,
    });
    expect(new URL(page.url()).searchParams.has("checkout_token")).toBe(false);
    expect(new URL(page.url()).hash).toBe("");

    await page.reload();
    await installEventInspection(page);
    await expect(widgetStatus(page)).toContainText("Ready to pay");
    const restored = await admin.details(payment.payment_id);
    expect(restored.payment.payment_id).toBe(payment.payment_id);
    expect(await admin.paymentCount(fixture.siteId)).toBe(1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await expect(widgetStatus(page)).toHaveAttribute("aria-live", "polite");
    expect(await widgetStatus(page).evaluate((element) => getComputedStyle(element).animationName)).toBe("none");
    expect(
      (
        await page.locator("generic-payment-widget").evaluate((element) =>
        getComputedStyle(element).getPropertyValue("--gp-accent").trim(),
        )
      ).length,
    ).toBeGreaterThan(0);
    await expectNoSensitivePresentation(page);
    await audit.expectSafe();
  });
});

async function openDemo(page: Page, admin: AdminApi, fixture: ProvisionedCheckout): Promise<void> {
  const url = new URL("/", DEMO_ORIGIN);
  url.searchParams.set("api_base_url", admin.backendUrl);
  url.searchParams.set("site_key", fixture.siteKey);
  url.searchParams.set("price_key", fixture.priceKey);
  url.searchParams.set("locale", "en");
  await page.goto(url.toString());
  await expect(page.getByRole("heading", { name: "One-time payment demo" })).toBeVisible();
  await expect(page.getByText("€49.99", { exact: true })).toBeVisible();
  await installEventInspection(page);
}

async function installEventInspection(page: Page): Promise<void> {
  await page.evaluate(() => {
    const widget = document.querySelector("generic-payment-widget");
    if (!widget) throw new Error("widget missing");
    const allowedFields: Record<string, string[]> = {
      ready: ["paymentId", "status"],
      processing: ["paymentId", "status"],
      "requires-action": ["paymentId", "status"],
      succeeded: ["paymentId", "status"],
      failed: ["paymentId", "status"],
      unknown: ["paymentId", "status"],
      expired: ["paymentId", "status"],
    };
    const result = { count: 0, hasUnexpectedField: false, hasTokenLikeValue: false };
    for (const [name, allowed] of Object.entries(allowedFields)) {
      widget.addEventListener(name, (event) => {
        const detail = (event as CustomEvent<unknown>).detail;
        const keys = detail && typeof detail === "object" ? Object.keys(detail) : [];
        result.count += 1;
        result.hasUnexpectedField ||= keys.some((key) => !allowed.includes(key));
        result.hasTokenLikeValue ||= /[A-Za-z0-9_-]{43}/.test(JSON.stringify(detail));
      });
    }
    Object.defineProperty(window, "__task12EventInspection", { value: result, configurable: true });
  });
}

async function inspectEvents(page: Page): Promise<EventInspection> {
  return page.evaluate(() => {
    const value = (window as Window & { __task12EventInspection?: EventInspection }).__task12EventInspection;
    if (!value) throw new Error("event inspection missing");
    return { ...value };
  });
}

async function inspectStorage(page: Page, expected: { apiBaseUrl: string; siteKey: string; priceKey: string;
  locale: string; paymentId: string }): Promise<Record<string, boolean | number>> {
  return page.evaluate((expected) => {
    const entries = Object.entries(sessionStorage);
    const recovery = entries.filter(([key]) => key.startsWith("generic-payments:v1:"));
    const [key, raw] = recovery[0] ?? ["", ""];
    function object(raw: string): Record<string, unknown> {
      try { const value: unknown = JSON.parse(raw);
        return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
      } catch { return {}; } // Booleans only: malformed persistence never exposes its contents.
    }
    const record = object(raw);
    const publicConfiguration = object(sessionStorage.getItem("demo:public-return-configuration:v1") ?? "");
    const subscriptionReturnConfiguration = object(sessionStorage.getItem("demo:subscription-return:v1") ?? "");
    return {
      entryCount: entries.length,
      recoveryEntryCount: recovery.length,
      publicConfigurationIsExact: Object.keys(publicConfiguration).sort().join(",") === "apiBaseUrl,locale,priceKey,siteKey" &&
        publicConfiguration.apiBaseUrl === expected.apiBaseUrl && publicConfiguration.siteKey === expected.siteKey &&
        publicConfiguration.priceKey === expected.priceKey && publicConfiguration.locale === expected.locale,
      subscriptionReturnConfigurationIsExact: Object.keys(subscriptionReturnConfiguration).sort().join(",") === "apiBaseUrl,siteKey" &&
        subscriptionReturnConfiguration.apiBaseUrl === expected.apiBaseUrl && subscriptionReturnConfiguration.siteKey === expected.siteKey,
      keyIsNamespaced: key === `generic-payments:v1:${encodeURIComponent(expected.apiBaseUrl)}:${expected.siteKey}:${expected.priceKey}`,
      hasExpectedFields:
        Object.keys(record).sort().join(",") === "apiBaseUrl,checkoutId,checkoutToken,confirmationMode,expiresAt,priceKey,providerType,siteKey,state,version" &&
        ["version", "checkoutId", "checkoutToken", "expiresAt", "apiBaseUrl", "siteKey", "priceKey", "state", "providerType", "confirmationMode"].every(
          (field) => field in record,
        ) &&
        record.version === 2 && record.providerType === "FAKE" && record.confirmationMode === "SERVER" &&
        record.apiBaseUrl === expected.apiBaseUrl && record.siteKey === expected.siteKey && record.priceKey === expected.priceKey &&
        typeof record.state === "object" &&
        record.state !== null &&
        "paymentId" in record.state && record.state.paymentId === expected.paymentId &&
        "status" in record.state,
      tokenLooksOpaque: typeof record.checkoutToken === "string" && /^[A-Za-z0-9_-]{43}$/.test(record.checkoutToken),
      localStorageIsEmpty: localStorage.length === 0,
    };
  }, expected);
}

async function expectNoSensitivePresentation(page: Page): Promise<void> {
  const text = (await page.locator("body").innerText()).trim();
  expect(TOKEN_SHAPE.test(text)).toBe(false);
  expect(/Bearer\s|XSRF|admin password|credentials_ref/i.test(text)).toBe(false);
  const eventAttributes = await page.locator("generic-payment-widget").evaluate((element) =>
    element.getAttributeNames().filter((name) => /token|credential|password|csrf/i.test(name)),
  );
  expect(eventAttributes.length).toBe(0);
  const inspection = await page.locator("generic-payment-widget").evaluate((element) => {
    const content = `${document.documentElement.outerHTML}${element.shadowRoot?.innerHTML ?? ""}`;
    return {
      hasSecret: /\b[A-Za-z0-9_-]{43}\b|Bearer\s|XSRF|task12-admin-password|task12-database-password|gpk_/i.test(content),
      persistentEntries: localStorage.length,
      hasCookie: document.cookie.length > 0,
    };
  });
  expect(inspection).toEqual({ hasSecret: false, persistentEntries: 0, hasCookie: false });
  expect((await page.context().cookies()).length).toBe(0);
  expect(page.context().serviceWorkers().length).toBe(0);
  const events = await inspectEvents(page);
  expect(events.hasUnexpectedField).toBe(false);
  expect(events.hasTokenLikeValue).toBe(false);
}

function bodyKeys(request: Request): string[] {
  const text = request.postData();
  if (text === null) return [];
  const parsed = JSON.parse(text) as Record<string, unknown>;
  return Object.keys(parsed).sort();
}

function isConfirmResponse(response: { url(): string; request(): { method(): string } }): boolean {
  return response.request().method() === "POST" && /\/api\/v1\/public\/checkouts\/[^/]+\/confirm$/.test(new URL(response.url()).pathname);
}

function isCheckoutReadResponse(response: { url(): string; request(): { method(): string } }): boolean {
  return response.request().method() === "GET" && /\/api\/v1\/public\/checkouts\/[^/]+$/.test(new URL(response.url()).pathname);
}

function widgetStatus(page: Page) {
  return page.locator("generic-payment-widget").getByRole("status");
}
