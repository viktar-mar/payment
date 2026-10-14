import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { PaymentList } from "./PaymentList";
import { payment } from "./test-fixtures";
afterEach(() => { vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });
it("adds Customer/Campaign/Donation filters while retaining old default query behavior", async () => {
  const ids = ["40000000-0000-0000-0000-000000000004", "30000000-0000-0000-0000-000000000003", "10000000-0000-0000-0000-000000000001"];
  const urls: string[] = []; vi.stubGlobal("fetch", async (url: string) => { urls.push(url); return Response.json({ items: [], page: 0, size: 20, total: 0 }); });
  render(<PaymentList />); await screen.findByText("No payments found.");
  expect(urls[0]).toBe("/api/backend/v1/admin/payments?page=0&size=20&sort=CREATED_DESC");
  const user = userEvent.setup();
  for (const [index, name] of ["Customer ID", "Campaign ID", "Donation ID"].entries()) await user.type(screen.getByLabelText(name), ids[index]);
  await user.click(screen.getByRole("button", {name:"Apply filters"}));
  await waitFor(()=>expect(urls.at(-1)).toBe(`/api/backend/v1/admin/payments?campaign_id=${ids[1]}&customer_id=${ids[0]}&donation_id=${ids[2]}&page=0&size=20&sort=CREATED_DESC`));
});
it.each(["customer_id", "campaign_id", "donation_id"])("rejects invalid additive %s before querying", async name => {
  window.history.replaceState(null, "", `/payments?${name}=invalid`);const fetcher=vi.fn();vi.stubGlobal("fetch", fetcher);render(<PaymentList />);
  expect(await screen.findByRole("alert")).toHaveTextContent("valid UUID");expect(fetcher).not.toHaveBeenCalled();
});
it("loads a labelled payment table, links every payment, and keeps filter values encoded", async () => {
  const destinations: string[] = [];
  vi.stubGlobal("fetch", async (url: string) => { destinations.push(url); return Response.json({ items: [payment], page: 0, size: 20, total: 1, sort: "CREATED_DESC" }); });
  render(<PaymentList />);
  expect(screen.getByRole("status")).toHaveTextContent("Loading payments");
  expect(await screen.findByRole("table", { name: "Payments" })).toBeInTheDocument();
  expect(screen.getAllByRole("link", { name: "payment-1" })).toHaveLength(1);
  expect(screen.getByRole("link", { name: "payment-1" })).toHaveAttribute("href", "/payments/payment-1");
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("External order ID"), "a&b ?/+");
  await user.click(screen.getByRole("button", { name: "Apply filters" }));
  await waitFor(() => expect(destinations.at(-1)).toBe("/api/backend/v1/admin/payments?external_order_id=a%26b+%3F%2F%2B&page=0&size=20&sort=CREATED_DESC"));
  expect(window.location.search).toContain("external_order_id=a%26b+%3F%2F%2B");
  expect(screen.getByRole("button", { name: "Next page" })).toBeDisabled();
});
it("rejects invalid calendar instants and reversed UTC ranges without replacing results", async () => {
  const fetchSpy = vi.fn(async () => Response.json({ items: [], page: 0, size: 20, total: 0 })); vi.stubGlobal("fetch", fetchSpy);
  render(<PaymentList />); await screen.findByText("No payments found."); const user = userEvent.setup();
  const from = screen.getByLabelText("Created from (UTC)"); const to = screen.getByLabelText("Created to (UTC)");
  await user.type(from, "2027-02-30T00:00:00Z"); await user.click(screen.getByRole("button", { name: "Apply filters" }));
  expect(screen.getByRole("alert")).toHaveTextContent("Enter valid UTC instants"); expect(fetchSpy).toHaveBeenCalledTimes(1);
  await user.clear(from); await user.type(from, "2027-02-28T00:00:00Z"); await user.type(to, "2027-02-27T00:00:00Z"); await user.click(screen.getByRole("button", { name: "Apply filters" }));
  expect(screen.getByRole("alert")).toHaveTextContent("Created from must be before created to"); expect(fetchSpy).toHaveBeenCalledTimes(1);
});
it("bounds paging and sort from browser state, omits empties and resets paging on filters", async () => {
  window.history.replaceState(null, "", "/payments?page=999999&size=999&sort=evil&provider=&status=");
  const urls: string[] = [];
  vi.stubGlobal("fetch", async (url: string) => { urls.push(url); return Response.json({ items: [], page: 100000, size: 100, total: 10000001 }); });
  render(<PaymentList />);
  expect(await screen.findByText("No payments found.")).toBeInTheDocument();
  expect(urls[0]).toBe("/api/backend/v1/admin/payments?page=100000&size=100&sort=CREATED_DESC");
  await userEvent.setup().click(screen.getByRole("button", { name: "Apply filters" }));
  await waitFor(() => expect(urls.at(-1)).toContain("page=0"));
});
it("aborts on unmount and renders only a safe error title", async () => {
  let signal: AbortSignal | undefined;
  vi.stubGlobal("fetch", (_url: string, init: RequestInit) => { signal = init.signal as AbortSignal; return new Promise(() => {}); });
  const view = render(<PaymentList />); view.unmount(); expect(signal?.aborted).toBe(true);
  vi.stubGlobal("fetch", async () => Response.json({ title: "Payment unavailable", detail: "PRIVATE DETAIL" }, { status: 503 }));
  render(<PaymentList />);
  expect(await screen.findByRole("alert")).toHaveTextContent("Payment unavailable");
  expect(screen.queryByText(/PRIVATE DETAIL/)).not.toBeInTheDocument();
});
it("rejects invalid UUID/date filter inputs before sending a query", async () => {
  let calls = 0;
  vi.stubGlobal("fetch", async () => { calls++; return Response.json({ items: [], page: 0, size: 20, total: 0 }); });
  render(<PaymentList />); await screen.findByText("No payments found.");
  await userEvent.setup().type(screen.getByLabelText("Site ID"), "invalid");
  await userEvent.setup().click(screen.getByRole("button", { name: "Apply filters" }));
  expect(screen.getByRole("alert")).toHaveTextContent("Enter valid UUID filters"); expect(calls).toBe(1);
});

function navigate(search: string) {
  act(() => { window.history.pushState(null, "", `/payments?${search}`); window.dispatchEvent(new PopStateEvent("popstate")); });
}
function listResponse(id = "payment-1") { return Response.json({ items: [{ ...payment, payment_id: id }], page: 0, size: 20, total: 21 }); }
function expectNoOldResults() {
  expect(screen.queryByRole("link", { name: "payment-1" })).not.toBeInTheDocument();
  expect(screen.queryByRole("navigation", { name: "Payment pagination" })).not.toBeInTheDocument();
}

it("resets popstate rows/totals/pagination while pending and replaces them with the active query", async () => {
  let resolve: (response: Response) => void = () => {}; const urls: string[] = [];
  vi.stubGlobal("fetch", (url: string) => { urls.push(url); return urls.length === 1 ? Promise.resolve(listResponse()) : new Promise<Response>(done => { resolve = done; }); });
  render(<PaymentList />); await screen.findByRole("link", { name: "payment-1" });
  navigate("external_order_id=new-order");
  expect(screen.getByLabelText("External order ID")).toHaveValue("new-order"); expect(screen.getByRole("status")).toHaveTextContent("Loading payments"); expectNoOldResults();
  expect(urls[1]).toContain("external_order_id=new-order");
  await act(async () => { resolve(listResponse("payment-new")); });
  expect(await screen.findByRole("link", { name: "payment-new" })).toBeInTheDocument(); expect(screen.getByRole("navigation", { name: "Payment pagination" })).toBeInTheDocument(); expect(screen.queryByRole("link", { name: "payment-1" })).not.toBeInTheDocument();
});

it("clears popstate old results for invalid UTC state without another fetch", async () => {
  const fetchSpy = vi.fn(async () => listResponse()); vi.stubGlobal("fetch", fetchSpy);
  render(<PaymentList />); await screen.findByRole("link", { name: "payment-1" });
  navigate("created_from=2027-02-30T00%3A00%3A00Z");
  expect(screen.getByRole("alert")).toHaveTextContent("Enter valid UTC instants"); expectNoOldResults(); expect(fetchSpy).toHaveBeenCalledTimes(1);
});

it("clears popstate old results for a failed query and resets that error on the next transition", async () => {
  let calls = 0;
  vi.stubGlobal("fetch", async () => ++calls === 1 ? listResponse() : Response.json({ title: "Payment unavailable", detail: "PRIVATE" }, { status: 503 }));
  render(<PaymentList />); await screen.findByRole("link", { name: "payment-1" }); navigate("external_order_id=failed");
  expect(await screen.findByRole("alert")).toHaveTextContent("Payment unavailable"); expectNoOldResults(); expect(screen.queryByText(/PRIVATE/)).not.toBeInTheDocument();
  vi.stubGlobal("fetch", () => new Promise<Response>(() => {})); navigate("external_order_id=pending");
  expect(screen.queryByRole("alert")).not.toBeInTheDocument(); expect(screen.getByRole("status")).toHaveTextContent("Loading payments"); expectNoOldResults();
});

it("aborts popstate prior query and ignores its late response after current results arrive", async () => {
  let calls = 0; let resolvePrior: (response: Response) => void = () => {}; let priorSignal: AbortSignal | undefined;
  vi.stubGlobal("fetch", (_url: string, init: RequestInit) => {
    calls++;
    if (calls === 1) return Promise.resolve(listResponse());
    if (calls === 2) { priorSignal = init.signal as AbortSignal; return new Promise<Response>(done => { resolvePrior = done; }); }
    return Promise.resolve(listResponse("payment-current"));
  });
  render(<PaymentList />); await screen.findByRole("link", { name: "payment-1" }); navigate("external_order_id=prior"); navigate("external_order_id=current");
  expect(await screen.findByRole("link", { name: "payment-current" })).toBeInTheDocument(); expect(priorSignal?.aborted).toBe(true);
  await act(async () => { resolvePrior(listResponse("payment-late")); });
  expect(screen.queryByRole("link", { name: "payment-late" })).not.toBeInTheDocument(); expect(screen.getByRole("link", { name: "payment-current" })).toBeInTheDocument();
});
