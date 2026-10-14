import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { SiteList } from "./SiteList";
it("discovers Site-filtered Donations",()=>{render(<SiteList sites={[{id:"site-1",display_name:"Shop"}]}/>);expect(screen.getByRole("link",{name:"Donations for Shop"})).toHaveAttribute("href","/donations?site_id=site-1");});
it("discovers Site-nested campaign management", () => { render(<SiteList sites={[{id:"site-1",display_name:"Shop"}]} />);expect(screen.getByRole("link",{name:"Donation campaigns for Shop"})).toHaveAttribute("href","/sites/site-1/donation-campaigns"); });
it("links Site authentication settings and scoped Customers", () => { render(<SiteList sites={[{ id: "site-1", display_name: "Shop" }]} />); expect(screen.getByRole("link", { name: "Authentication settings for Shop" })).toHaveAttribute("href", "/sites/site-1/authentication"); expect(screen.getByRole("link", { name: "Customers for Shop" })).toHaveAttribute("href", "/customers?site_id=site-1"); });
it("links labelled integration settings while preserving the catalog entry", () => { render(<SiteList sites={[{ id: "site-1", display_name: "Shop" }]} />); expect(screen.getByRole("link", { name: "Integration settings for Shop" })).toHaveAttribute("href", "/sites/site-1/integration"); expect(screen.getByRole("link", { name: "Shop" })).toHaveAttribute("href", "/sites/site-1/catalog"); });

it("renders site identity and opens its catalog", () => {
  render(
    <SiteList
      sites={[
        {
          id: "site-1",
          key: "demo-site",
          display_name: "Demo Site",
          status: "ACTIVE",
        },
      ]}
    />,
  );
  expect(screen.getByRole("link", { name: "Demo Site" })).toHaveAttribute(
    "href",
    "/sites/site-1/catalog",
  );
});
it("shows the site's account, origins, authentication and payment policy", () => {
  render(
    <SiteList
      sites={[
        {
          id: "site-2",
          key: "shop-key",
          display_name: "Shop",
          status: "DISABLED",
          payment_account_id: "account-1",
          allowed_origins: ["https://shop.test"],
          authentication_modes: ["EMAIL_OTP"],
          payment_method_policy: { mode: "ALLOWLIST", methods: ["CARD"] },
        },
      ]}
    />,
  );
  for (const value of [
    "shop-key",
    "DISABLED",
    "account-1",
    "https://shop.test",
    "EMAIL_OTP",
    /ALLOWLIST.*CARD/,
  ])
    expect(screen.getByText(value)).toBeInTheDocument();
});
