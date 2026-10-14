// @vitest-environment node
import { NextRequest } from "next/server";
import { expect, it } from "vitest";
import { proxy, config } from "./proxy";
it("protects Donation routes through the actual Next matcher and proxy, while backend still validates cookie presence", async () => {
  const {unstable_doesMiddlewareMatch}=await import("next/experimental/testing/server");
  for(const path of ["/donations","/donations/10000000-0000-0000-0000-000000000001"]){
    expect(unstable_doesMiddlewareMatch({config,nextConfig:{},url:`https://admin.test${path}`})).toBe(true);
    expect(proxy(new NextRequest(`https://admin.test${path}`)).headers.get("location")).toBe("https://admin.test/login");
    expect(proxy(new NextRequest(`https://admin.test${path}`,{headers:{cookie:"PAYMENT_ADMIN_SESSION=unverified"}})).headers.get("location")).toBeNull();
  }
});
it("registers Customer list and detail routes for signed-out navigation", () => {
  expect(config.matcher).toContain("/customers/:path*");
  for (const path of ["/customers", "/customers/customer-1"]) {
    expect(proxy(new NextRequest(`https://admin.test${path}`)).headers.get("location")).toBe("https://admin.test/login");
  }
});
it("registers protected route families including payment details and integration", () => { expect(config.matcher).toEqual(["/sites/:path*", "/payments/:path*", "/customers/:path*", "/donations/:path*"]); for (const path of ["/payments", "/payments/payment-1", "/sites/site-1/integration"]) expect(proxy(new NextRequest(`https://admin.test${path}`)).headers.get("location")).toBe("https://admin.test/login"); });
it("redirects absent cookies while leaving cookie validation to the backend", () => {
  expect(
    proxy(new NextRequest("https://admin.test/sites")).headers.get("location"),
  ).toBe("https://admin.test/login");
  expect(
    proxy(
      new NextRequest("https://admin.test/sites", {
        headers: { cookie: "PAYMENT_ADMIN_SESSION=unverified" },
      }),
    ).headers.get("location"),
  ).toBeNull();
});
