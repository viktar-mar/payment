import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Component, type ReactNode } from "react";
import { afterEach, expect, it, vi } from "vitest";
import type { components } from "../../lib/api/schema";
import { PlanTransitionForm } from "./PlanTransitionForm";
import { policy, priceId, productId, siteId, targetPriceId } from "./test-fixtures";

type S = components["schemas"];
const transition: S["SubscriptionTransitionResponse"] = {
  site_id: siteId, product_id: productId, from_price_id: priceId,
  to_price_id: targetPriceId, direction: "DOWNGRADE", immediate_allowed: false, policy_version: 4,
};
const catalog: S["CatalogResponse"] = { prices: [
  { id: priceId, product_id: productId, status: "PUBLISHED", type: "SUBSCRIPTION", currency: "EUR", amount_minor: 3000, price_key: "from", version: 1 },
  { id: targetPriceId, product_id: productId, status: "PUBLISHED", type: "SUBSCRIPTION", currency: "EUR", amount_minor: 1000, price_key: "to", version: 2 },
  { id: "usd", product_id: productId, status: "PUBLISHED", type: "SUBSCRIPTION", currency: "USD" },
  { id: "draft", product_id: productId, status: "DRAFT", type: "SUBSCRIPTION", currency: "EUR" },
  { id: "other-product", product_id: "other", status: "PUBLISHED", type: "SUBSCRIPTION", currency: "EUR" },
] };

// Keep a rendering failure observable as an assertion failure rather than an unhandled test error.
class RenderBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() { return this.state.failed ? <p role="alert">Transition editor failed to load</p> : this.props.children; }
}
afterEach(() => vi.unstubAllGlobals());

it.each([
  { name: "empty", envelope: { items: [] } satisfies S["SubscriptionTransitions"] },
  { name: "populated", envelope: { items: [transition] } satisfies S["SubscriptionTransitions"] },
])("renders the actual $name envelope and preserves exact prices, explicit direction and CAS conflict safety", async ({ envelope }) => {
  const posts: unknown[] = [];
  vi.stubGlobal("fetch", async (path: string, init: RequestInit) => {
    if (init.method === "POST") { posts.push(JSON.parse(init.body as string)); return Response.json({ title: "Policy changed" }, { status: 409 }); }
    if (path.includes("subscription-policy")) return Response.json(policy);
    if (path.includes("subscription-transitions")) return Response.json(envelope);
    return Response.json(catalog);
  });
  render(<RenderBoundary><PlanTransitionForm siteId={siteId} productId={productId} /></RenderBoundary>);
  const user = userEvent.setup();
  await user.selectOptions(await screen.findByLabelText("From Price"), priceId);
  expect(screen.queryAllByRole("listitem")).toHaveLength(envelope.items.length);
  if (envelope.items.length) expect(screen.getByRole("listitem")).toHaveTextContent(`${priceId} → ${targetPriceId} · DOWNGRADE · next period only`);
  expect(screen.queryByRole("option", { name: /draft/ })).not.toBeInTheDocument();
  expect(screen.queryByRole("option", { name: /other-product/ })).not.toBeInTheDocument();
  expect(within(screen.getByLabelText("To Price")).queryByRole("option", { name: /usd/ })).not.toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText("To Price"), targetPriceId);
  expect(screen.getByRole("button", { name: "Save transition" })).toBeDisabled();
  await user.selectOptions(screen.getByLabelText("Direction"), "UPGRADE");
  await user.click(screen.getByRole("button", { name: "Save transition" }));
  await screen.findByText(/Reload transitions/);
  expect(posts).toEqual([{ policy_version: 4, from_price_id: priceId, to_price_id: targetPriceId, direction: "UPGRADE", immediate_allowed: false }]);
  await user.click(screen.getByRole("button", { name: "Save transition" }));
  await waitFor(() => expect(posts).toHaveLength(1));
});

it("updates an existing populated-envelope transition after an explicit successful save", async () => {
  const posts: unknown[] = [];
  vi.stubGlobal("fetch", async (path: string, init: RequestInit) => {
    if (init.method === "POST") {
      const body = JSON.parse(init.body as string); posts.push(body);
      return Response.json({ ...transition, direction: body.direction, immediate_allowed: body.immediate_allowed });
    }
    if (path.includes("subscription-policy")) return Response.json(policy);
    if (path.includes("subscription-transitions")) return Response.json({ items: [transition] } satisfies S["SubscriptionTransitions"]);
    return Response.json(catalog);
  });
  render(<RenderBoundary><PlanTransitionForm siteId={siteId} productId={productId} /></RenderBoundary>);
  const user = userEvent.setup();
  await user.selectOptions(await screen.findByLabelText("From Price"), priceId);
  expect(screen.getByRole("listitem")).toHaveTextContent("DOWNGRADE · next period only");
  await user.selectOptions(screen.getByLabelText("To Price"), targetPriceId);
  await user.selectOptions(screen.getByLabelText("Direction"), "UPGRADE");
  await user.click(screen.getByLabelText("Allow immediate transition"));
  await user.click(screen.getByRole("button", { name: "Save transition" }));
  await waitFor(() => expect(screen.getByRole("listitem")).toHaveTextContent(`${priceId} → ${targetPriceId} · UPGRADE · immediate allowed`));
  expect(screen.getAllByRole("listitem")).toHaveLength(1);
  expect(posts).toEqual([{ policy_version: 4, from_price_id: priceId, to_price_id: targetPriceId, direction: "UPGRADE", immediate_allowed: true }]);
});
