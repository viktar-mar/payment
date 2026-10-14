import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { CreateSiteForm } from "./CreateSiteForm";
import { CreateProductForm } from "./CreateProductForm";
import { CreatePriceForm } from "./CreatePriceForm";
afterEach(() => vi.unstubAllGlobals());
it("loads accounts and creates a site with the exact snake_case policy DTO", async () => {
  let submitted: unknown;
  let created = false;
  vi.stubGlobal("fetch", async (_url: string, options: RequestInit) => {
    if (options.method === "GET")
      return Response.json([
        {
          id: "account-1",
          key: "sandbox",
          provider: "FAKE",
          mode: "SANDBOX",
          status: "ACTIVE",
          external_account_id: "fake",
        },
      ]);
    submitted = JSON.parse(options.body as string);
    return Response.json(
      { id: "site-1", ...(submitted as object) },
      { status: 201 },
    );
  });
  render(
    <CreateSiteForm
      onCreated={() => {
        created = true;
      }}
    />,
  );
  const user = userEvent.setup();
  await screen.findByRole("option", { name: /sandbox/ });
  await user.type(screen.getByLabelText("Site key"), "demo-site");
  await user.type(screen.getByLabelText("Display name"), "Demo");
  await user.selectOptions(
    screen.getByLabelText("Payment account"),
    "account-1",
  );
  await user.type(
    screen.getByLabelText("Allowed origins (one per line)"),
    "https://shop.test",
  );
  await user.click(screen.getByRole("button", { name: "Create site" }));
  await waitFor(() => expect(created).toBe(true));
  expect(submitted).toEqual({
    key: "demo-site",
    display_name: "Demo",
    payment_account_id: "account-1",
    allowed_origins: ["https://shop.test"],
    authentication_modes: ["SITE_TOKEN"],
    payment_method_policy: { mode: "AUTOMATIC", methods: [] },
  });
});
it("rejects origin paths before submitting", async () => {
  let posts = 0;
  vi.stubGlobal("fetch", async (_url: string, options: RequestInit) => {
    if (options.method === "POST") posts++;
    return Response.json([
      { id: "account-1", key: "sandbox", status: "ACTIVE" },
    ]);
  });
  render(<CreateSiteForm onCreated={() => {}} />);
  const user = userEvent.setup();
  await screen.findByRole("option", { name: /sandbox/ });
  await user.type(screen.getByLabelText("Site key"), "demo-site");
  await user.type(screen.getByLabelText("Display name"), "Demo");
  await user.selectOptions(
    screen.getByLabelText("Payment account"),
    "account-1",
  );
  await user.type(
    screen.getByLabelText("Allowed origins (one per line)"),
    "https://shop.test/path",
  );
  await user.click(screen.getByRole("button", { name: "Create site" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(/origin/i);
  expect(posts).toBe(0);
});
it("creates a product using the site-scoped route", async () => {
  let target = "";
  let body: unknown;
  vi.stubGlobal("fetch", async (url: string, options: RequestInit) => {
    target = url;
    body = JSON.parse(options.body as string);
    return Response.json({ id: "product-1" }, { status: 201 });
  });
  render(<CreateProductForm siteId="site-1" onCreated={() => {}} />);
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("Product key"), "basic");
  await user.type(screen.getByLabelText("Product name"), "Basic");
  const description = screen.getByLabelText("Description");
  await user.click(description);
  await user.paste("a".repeat(1000));
  await user.type(description, "b");
  expect(description).toHaveValue("a".repeat(1000));
  await user.click(screen.getByRole("button", { name: "Create product" }));
  expect(target).toBe("/api/backend/admin/v1/sites/site-1/products");
  expect(body).toEqual({ key: "basic", name: "Basic", description: "a".repeat(1000) });
});
it("creates a subscription price with rounded minor units and full billing and tax fields", async () => {
  let body: unknown;
  let target = "";
  vi.stubGlobal("fetch", async (url: string, options: RequestInit) => {
    target = url;
    body = JSON.parse(options.body as string);
    return Response.json({ id: "price-1" }, { status: 201 });
  });
  render(<CreatePriceForm productId="product-1" onCreated={() => {}} />);
  expect(screen.getByLabelText("Currency")).toHaveValue("EUR");
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("Price key"), "monthly");
  await user.type(screen.getByLabelText("Amount"), "1.005");
  await user.selectOptions(screen.getByLabelText("Price type"), "SUBSCRIPTION");
  await user.selectOptions(screen.getByLabelText("Billing interval"), "YEAR");
  await user.clear(screen.getByLabelText("Trial days"));
  await user.type(screen.getByLabelText("Trial days"), "14");
  await user.selectOptions(screen.getByLabelText("Trial payment method"), "OPTIONAL_DURING_TRIAL");
  await user.selectOptions(screen.getByLabelText("Tax mode"), "EXCLUSIVE");
  await user.type(screen.getByLabelText("Tax code"), "txcd_1");
  await user.click(screen.getByRole("button", { name: "Create draft price" }));
  expect(target).toBe("/api/backend/admin/v1/products/product-1/prices");
  expect(body).toEqual({
    price_key: "monthly",
    type: "SUBSCRIPTION",
    amount_minor: 101,
    currency: "EUR",
    billing_interval: "YEAR",
    trial_days: 14,
    trial_payment_method_policy: "OPTIONAL_DURING_TRIAL",
    tax_mode: "EXCLUSIVE",
    tax_code: "txcd_1",
  });
});
