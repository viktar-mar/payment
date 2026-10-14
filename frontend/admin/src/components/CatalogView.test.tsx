import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { CatalogView } from "./CatalogView";
const catalog = {
  products: [
    {
      id: "product-1",
      site_id: "site-1",
      key: "basic",
      name: "Basic",
      description: "Basic plan",
      status: "ACTIVE" as const,
    },
  ],
  prices: [
    {
      id: "price-1",
      site_id: "site-1",
      product_id: "product-1",
      price_key: "monthly",
      version: 1,
      type: "ONE_TIME" as const,
      amount_minor: 1200,
      currency: "USD",
      trial_days: 0,
      tax_mode: "NONE" as const,
      status: "DRAFT" as const,
    },
  ],
};
afterEach(() => vi.unstubAllGlobals());
it("blocks publication of a replacement draft while the same key is already published", async () => {
  const published = { ...catalog.prices[0], status: "PUBLISHED", provider_price_id: "fake-price-1" };
  vi.stubGlobal("fetch", async () => Response.json({
    ...catalog,
    prices: [published, { ...catalog.prices[0], id: "price-2", version: 2 },
      { ...catalog.prices[0], id: "price-3", price_key: "annual" }],
  }));
  render(<CatalogView siteId="site-1" />);
  const replacement = (await screen.findByRole("heading", { name: /monthly.*v2/ })).closest("li")!;
  const button = within(replacement).getByRole("button", { name: "Publish" });
  expect(button).toBeDisabled();
  expect(button).toHaveAccessibleDescription(/Stage 1.*already published.*replacement.*archiv/i);
  await userEvent.setup().click(button);
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  const otherKey = screen.getByRole("heading", { name: /annual.*v1/ }).closest("li")!;
  expect(within(otherKey).getByRole("button", { name: "Publish" })).toBeEnabled();
});
it("groups price versions under products and waits for explicit publish confirmation", async () => {
  let publishes = 0;
  vi.stubGlobal("fetch", async (url: string) => {
    if (url.endsWith("/publish")) {
      publishes++;
      return Response.json({
        ...catalog.prices[0],
        status: "PUBLISHED",
        provider_price_id: "fake-price-1",
      });
    }
    return Response.json(catalog);
  });
  render(<CatalogView siteId="site-1" />);
  const user = userEvent.setup();
  expect(
    await screen.findByRole("heading", { name: "Basic" }),
  ).toBeInTheDocument();
  expect(screen.getByText(/monthly.*v1/)).toBeInTheDocument();
  expect(screen.getByText("DRAFT")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Publish" }));
  expect(publishes).toBe(0);
  const cancel = within(screen.getByRole("dialog")).getByRole("button", {
    name: "Cancel",
  });
  expect(cancel).toHaveFocus();
  await user.tab({ shift: true });
  expect(
    within(screen.getByRole("dialog")).getByRole("button", {
      name: "Confirm publish",
    }),
  ).toHaveFocus();
  await user.tab();
  expect(cancel).toHaveFocus();
  await user.click(
    within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }),
  );
  expect(publishes).toBe(0);
  expect(screen.getByRole("button", { name: "Publish" })).toHaveFocus();
  await user.click(screen.getByRole("button", { name: "Publish" }));
  await user.click(
    within(screen.getByRole("dialog")).getByRole("button", {
      name: "Confirm publish",
    }),
  );
  expect(await screen.findByText("PUBLISHED")).toBeInTheDocument();
  expect(publishes).toBe(1);
  expect(screen.getByRole("heading", { name: /monthly.*v1/ })).toHaveFocus();
});
it("offers product creation and price creation within the product", async () => {
  vi.stubGlobal("fetch", async () => Response.json(catalog));
  render(<CatalogView siteId="site-1" />);
  await screen.findByRole("heading", { name: "Basic" });
  expect(
    screen.getByRole("button", { name: "Create product" }),
  ).toBeInTheDocument();
  expect(
    screen.getByRole("button", { name: "Create draft price" }),
  ).toBeInTheDocument();
});
it("keeps publication errors visible inside the confirmation so the admin can retry", async () => {
  vi.stubGlobal("fetch", async (url: string) =>
    url.endsWith("/publish")
      ? Response.json({ title: "Provider unavailable" }, { status: 502 })
      : Response.json(catalog),
  );
  render(<CatalogView siteId="site-1" />);
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "Publish" }));
  await user.click(screen.getByRole("button", { name: "Confirm publish" }));
  expect(
    await within(screen.getByRole("dialog")).findByRole("alert"),
  ).toHaveTextContent("Provider unavailable");
  expect(screen.getByText("DRAFT")).toBeInTheDocument();
});
