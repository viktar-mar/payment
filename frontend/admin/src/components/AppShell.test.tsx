import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { AppShell } from "./AppShell";
import type { ReactNode } from "react";

vi.mock("next/link", () => ({ default: ({ href, children, prefetch }: { href: string; children: ReactNode; prefetch?: boolean }) =>
  <a href={href} data-prefetch={String(prefetch)}>{children}</a> }));

let destination = "";
let pathname = "/sites";
it("provides Customers navigation", () => { render(<AppShell>Customers</AppShell>); expect(screen.getByRole("link", { name: "Customers" })).toHaveAttribute("href", "/customers"); });
it("provides Payments navigation", () => { render(<AppShell>Payments</AppShell>); expect(screen.getByRole("link", { name: "Payments" })).toHaveAttribute("href", "/payments"); });
it("provides Donations navigation",()=>{render(<AppShell>Donations</AppShell>);expect(screen.getByRole("link",{name:"Donations"})).toHaveAttribute("href","/donations");});
vi.mock("next/navigation", () => ({
  usePathname: () => pathname,
  useRouter: () => ({
    replace: (path: string) => { destination = path; },
    refresh: () => {},
  }),
}));
afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; Max-Age=0; Path=/";
  destination = "";
  pathname = "/sites";
});

it("signs out with the shared client's CSRF header and returns to login after 204", async () => {
  document.cookie = "XSRF-TOKEN=logout-csrf; Path=/";
  let request: { url: string; method?: string; csrf: string | null } | undefined;
  vi.stubGlobal("fetch", async (url: string, options: RequestInit) => {
    request = { url, method: options.method, csrf: new Headers(options.headers).get("X-XSRF-TOKEN") };
    return new Response(null, { status: 204 });
  });
  render(<AppShell>Catalog</AppShell>);
  await userEvent.setup().click(screen.getByRole("button", { name: "Sign out" }));
  await waitFor(() => expect(destination).toBe("/login"));
  expect(request).toEqual({ url: "/api/backend/admin/v1/auth/session", method: "DELETE", csrf: "logout-csrf" });
});

it("keeps the admin on the page and exposes a safe error when sign out fails", async () => {
  vi.stubGlobal("fetch", async () => Response.json(
    { title: "Backend unavailable", detail: "private upstream secret" }, { status: 502 },
  ));
  render(<AppShell>Catalog</AppShell>);
  await userEvent.setup().click(screen.getByRole("button", { name: "Sign out" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Backend unavailable");
  expect(screen.queryByText(/private upstream secret/)).not.toBeInTheDocument();
  expect(destination).toBe("");
  expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();
});

it("offers sign in on the login page", () => {
  pathname = "/login";
  render(<AppShell>Login</AppShell>);
  expect(screen.getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/login");
  expect(screen.queryByRole("button", { name: "Sign out" })).not.toBeInTheDocument();
});

it("does not cache signed-out redirects by speculatively prefetching protected navigation", () => {
  pathname = "/login";
  render(<AppShell>Login</AppShell>);
  for (const name of ["Sites", "Payments", "Customers", "Donations"])
    expect(screen.getByRole("link", { name })).toHaveAttribute("data-prefetch", "false");
});
