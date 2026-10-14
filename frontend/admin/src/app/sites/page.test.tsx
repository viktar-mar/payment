import { render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import SitesPage from "./page";
afterEach(() => vi.unstubAllGlobals());
it("loads sites and offers site creation", async () => {
  vi.stubGlobal("fetch", async (url: string) =>
    Response.json(
      url.endsWith("/sites")
        ? [
            {
              id: "site-1",
              display_name: "Demo",
              key: "demo",
              status: "ACTIVE",
            },
          ]
        : [],
    ),
  );
  render(<SitesPage />);
  expect(await screen.findByRole("link", { name: "Demo" })).toHaveAttribute(
    "href",
    "/sites/site-1/catalog",
  );
  expect(
    screen.getByRole("button", { name: "Create site" }),
  ).toBeInTheDocument();
});
