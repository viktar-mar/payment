import { randomUUID } from "node:crypto";
import postgres from "postgres";

const DEFAULT_BACKEND_URL = "http://127.0.0.1:28080";
const DEFAULT_DEMO_ORIGIN = "http://127.0.0.1:4173";
const DEFAULT_ADMIN_USERNAME = "task12-admin";
const DEFAULT_ADMIN_PASSWORD = "task12-admin-password";
const DEFAULT_DATABASE_URL = "postgres://payment:task12-database-password@127.0.0.1:25432/payment";
const MAX_RESPONSE_BYTES = 65_536;

type JsonObject = Record<string, unknown>;

export interface ProvisionedCheckout {
  accountId: string;
  siteId: string;
  siteKey: string;
  productId: string;
  priceId: string;
  priceKey: string;
}

export interface PaymentProjection {
  payment_id: string;
  site_id: string;
  status: string;
}

export interface PaymentAttempt {
  id: string;
  attempt_number: number;
  status: string;
}

export interface PaymentDetails {
  payment: PaymentProjection;
  attempts: PaymentAttempt[];
}

interface PaymentPage {
  items: PaymentProjection[];
  total: number;
}

export class AdminApi {
  readonly backendUrl: string;
  readonly demoOrigin: string;
  readonly databaseUrl: string;
  private readonly username: string;
  private readonly password: string;
  private readonly cookies = new Map<string, string>();

  private constructor() {
    this.backendUrl = env("PAYMENT_E2E_BACKEND_URL", DEFAULT_BACKEND_URL);
    this.demoOrigin = env("PAYMENT_E2E_DEMO_ORIGIN", DEFAULT_DEMO_ORIGIN);
    this.databaseUrl = env("PAYMENT_E2E_DATABASE_URL", DEFAULT_DATABASE_URL);
    this.username = env("PAYMENT_E2E_ADMIN_USERNAME", DEFAULT_ADMIN_USERNAME);
    this.password = env("PAYMENT_E2E_ADMIN_PASSWORD", DEFAULT_ADMIN_PASSWORD);
  }

  static async login(): Promise<AdminApi> {
    const api = new AdminApi();
    const started = Date.now();
    const response = await fetch(`${api.backendUrl}/api/admin/v1/auth/sessions`, {
      method: "POST",
      redirect: "error",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: api.username, password: api.password }),
    });
    api.captureCookies(response);
    if (response.status !== 204) {
      throw new Error(`Admin login returned HTTP ${response.status}`);
    }
    api.requireCookie("PAYMENT_ADMIN_SESSION");
    api.requireCookie("XSRF-TOKEN");
    console.log(`Trusted setup: login HTTP204 in ${Date.now() - started}ms; credential values omitted`);
    return api;
  }

  async provision(journey: string, retry = 0, authenticationModes = ["SITE_TOKEN"]): Promise<ProvisionedCheckout> {
    if (!/^[a-z0-9-]{2,24}$/.test(journey) || !Number.isInteger(retry) || retry < 0 || retry > 1) {
      throw new Error("The checkout fixture identity is invalid");
    }
    const unique = `t12-${Date.now().toString(36)}-${randomUUID().slice(0, 8)}`;
    const account = await this.mutate("POST", "/api/admin/v1/payment-accounts", {
      key: `${unique}-account`,
      provider: "FAKE",
      mode: "SANDBOX",
      external_account_id: `${unique}-external`,
      credentials_ref: "task12-synthetic-credentials",
    });
    const accountId = requiredId(account, "payment account");

    const siteRun = env("PAYMENT_E2E_SITE_RUN", "local");
    if (!/^[a-z0-9-]{2,20}$/.test(siteRun)) throw new Error("PAYMENT_E2E_SITE_RUN is invalid");
    const siteKey = `t12-${siteRun}-${journey}-r${retry}-site`;
    const site = await this.mutate("POST", "/api/admin/v1/sites", {
      key: siteKey,
      display_name: "Task 12 browser fixture",
      payment_account_id: accountId,
      allowed_origins: [this.demoOrigin],
      authentication_modes: authenticationModes,
      payment_method_policy: { mode: "AUTOMATIC", methods: [] },
    });
    const siteId = requiredId(site, "site");

    const product = await this.mutate("POST", `/api/admin/v1/sites/${siteId}/products`, {
      key: `${unique}-product`,
      name: "Task 12 one-time product",
      description: "Isolated Playwright fixture",
    });
    const productId = requiredId(product, "product");

    const priceKey = `${unique}-price`;
    const price = await this.mutate("POST", `/api/admin/v1/products/${productId}/prices`, {
      price_key: priceKey,
      type: "ONE_TIME",
      amount_minor: 4999,
      currency: "EUR",
      billing_interval: null,
      trial_days: 0,
      tax_mode: "INCLUSIVE",
      tax_code: null,
    });
    const priceId = requiredId(price, "price");
    await this.mutate("POST", `/api/admin/v1/prices/${priceId}/publish`, {});

    return { accountId, siteId, siteKey, productId, priceId, priceKey };
  }

  async waitForSinglePayment(siteId: string): Promise<PaymentProjection> {
    return this.poll("payment creation", async () => {
      const page = await this.read<PaymentPage>(`/api/v1/admin/payments?site_id=${encodeURIComponent(siteId)}&size=10`);
      if (page.total > 1 || page.items.length > 1) {
        throw new Error("The isolated site created more than one payment");
      }
      return page.total === 1 && page.items.length === 1 ? page.items[0] : undefined;
    });
  }

  async paymentCount(siteId: string): Promise<number> {
    const page = await this.read<PaymentPage>(`/api/v1/admin/payments?site_id=${encodeURIComponent(siteId)}&size=10`);
    return page.total;
  }

  async customerRequest<T = JsonObject>(method: "GET" | "POST" | "PUT", path: string, body?: JsonObject): Promise<T> {
    return this.request<T>(method, path, body);
  }

  async details(paymentId: string): Promise<PaymentDetails> {
    return this.read<PaymentDetails>(`/api/v1/admin/payments/${encodeURIComponent(paymentId)}`);
  }

  async waitForStatus(paymentId: string, status: string): Promise<PaymentDetails> {
    return this.poll(`payment status ${status}`, async () => {
      const details = await this.details(paymentId);
      return details.payment.status === status ? details : undefined;
    });
  }

  async fakeEvent(paymentId: string, eventType: "PAYMENT_SUCCEEDED" | "PAYMENT_FAILED", sequence = 1): Promise<void> {
    await this.mutate("POST", `/api/v1/admin/payments/${encodeURIComponent(paymentId)}/fake-events`, {
      target: "PAYMENT",
      event_type: eventType,
      sequence,
    });
  }

  async reconcile(paymentId: string): Promise<PaymentDetails> {
    return this.mutate<PaymentDetails>("POST", `/api/v1/admin/payments/${encodeURIComponent(paymentId)}/reconcile`, {});
  }

  async setConfirmScenario(paymentId: string, scenario: "RECOVERABLE_DECLINE" | "UNKNOWN" | null): Promise<void> {
    const sql = postgres(this.databaseUrl, { max: 1, connect_timeout: 5, idle_timeout: 1 });
    try {
      const rows = await sql<{ internal_object_id: string }[]>`
        update fake_provider_objects
        set scenario = ${scenario}
        where object_type = 'PAYMENT'
          and internal_object_id = ${paymentId}::uuid
          and payment_id = ${paymentId}::uuid
        returning internal_object_id
      `;
      if (rows.length !== 1 || rows[0]?.internal_object_id !== paymentId) {
        throw new Error("scenario row mismatch");
      }
    } catch {
      throw new Error("The isolated fake-provider scenario fixture could not be applied");
    } finally {
      await sql.end({ timeout: 2 });
    }
  }

  async expireCustomerSession(siteId: string, customerId: string): Promise<void> {
    const sql = postgres(this.databaseUrl, { max: 1, connect_timeout: 5, idle_timeout: 1 });
    try {
      const rows = await sql`
        update customer_sessions set issued_at=now()-interval '2 hours',last_used_at=now()-interval '2 hours',expires_at=now()-interval '1 hour'
        where site_id=${siteId}::uuid and customer_id=${customerId}::uuid and revoked_at is null returning id
      `;
      if (rows.length !== 1) throw new Error("session fixture row mismatch");
    } catch { throw new Error("The isolated Customer expiry fixture could not be applied"); }
    finally { await sql.end({ timeout: 2 }); }
  }

  private async read<T>(path: string): Promise<T> {
    return this.request<T>("GET", path);
  }

  private async mutate<T = JsonObject>(method: "POST", path: string, body: JsonObject): Promise<T> {
    return this.request<T>(method, path, body);
  }

  private async request<T>(method: "GET" | "POST" | "PUT", path: string, body?: JsonObject): Promise<T> {
    const csrf = this.requireCookie("XSRF-TOKEN");
    const started = Date.now();
    const response = await fetch(`${this.backendUrl}${path}`, {
      method,
      redirect: "error",
      headers: {
        Accept: "application/json",
        Cookie: this.cookieHeader(),
        ...(method !== "GET"
          ? {
              "Content-Type": "application/json",
              "Idempotency-Key": `task12-${randomUUID()}`,
              "X-XSRF-TOKEN": csrf,
            }
          : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    this.captureCookies(response);
    console.log(`Trusted setup: ${method} ${safePath(path)} HTTP${response.status} in ${Date.now() - started}ms; values omitted`);
    if (!response.ok) {
      const problem = await safeProblemCode(response);
      throw new Error(`Admin API ${method} ${safePath(path)} returned HTTP ${response.status} (${problem})`);
    }
    return safeJson<T>(response);
  }

  private captureCookies(response: Response): void {
    const headers = response.headers as Headers & { getSetCookie?: () => string[] };
    const values = headers.getSetCookie?.() ?? splitSetCookie(response.headers.get("set-cookie"));
    for (const value of values) {
      const match = /^([^=;,\s]+)=([^;]*)/.exec(value);
      if (!match) continue;
      const name = match[1];
      const cookieValue = match[2];
      if ((name === "PAYMENT_ADMIN_SESSION" || name === "XSRF-TOKEN") && cookieValue !== undefined) {
        this.cookies.set(name, cookieValue);
      }
    }
  }

  private requireCookie(name: "PAYMENT_ADMIN_SESSION" | "XSRF-TOKEN"): string {
    const value = this.cookies.get(name);
    if (!value) throw new Error(`Admin authentication did not establish ${name}`);
    return value;
  }

  private cookieHeader(): string {
    return ["PAYMENT_ADMIN_SESSION", "XSRF-TOKEN"]
      .map((name) => `${name}=${this.requireCookie(name as "PAYMENT_ADMIN_SESSION" | "XSRF-TOKEN")}`)
      .join("; ");
  }

  private async poll<T>(label: string, probe: () => Promise<T | undefined>): Promise<T> {
    const deadline = Date.now() + 10_000;
    while (Date.now() < deadline) {
      const value = await probe();
      if (value !== undefined) return value;
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    throw new Error(`Timed out waiting for ${label}`);
  }
}

function env(name: string, fallback: string): string {
  const value = process.env[name]?.trim();
  return value || fallback;
}

function splitSetCookie(value: string | null): string[] {
  return value ? value.split(/,(?=\s*[A-Za-z0-9_-]+=)/) : [];
}

function requiredId(value: unknown, label: string): string {
  if (!isObject(value) || typeof value.id !== "string" || !/^[0-9a-f-]{36}$/i.test(value.id)) {
    throw new Error(`Admin ${label} response omitted its identifier`);
  }
  return value.id;
}

async function safeJson<T>(response: Response): Promise<T> {
  const text = await response.text();
  if (text.length > MAX_RESPONSE_BYTES) throw new Error("Admin API response exceeded the test safety limit");
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new Error("Admin API returned malformed JSON");
  }
}

async function safeProblemCode(response: Response): Promise<string> {
  try {
    const text = await response.text();
    if (text.length > MAX_RESPONSE_BYTES) return "UNAVAILABLE";
    const problem = JSON.parse(text) as unknown;
    if (isObject(problem) && typeof problem.code === "string" && /^[A-Z0-9_-]{1,64}$/.test(problem.code)) {
      return problem.code;
    }
  } catch {
    // Error reporting is intentionally bounded and never includes the raw body.
  }
  return "UNAVAILABLE";
}

function safePath(path: string): string {
  return path.split("?")[0] ?? "/";
}

function isObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
