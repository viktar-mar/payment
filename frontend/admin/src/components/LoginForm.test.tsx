import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { LoginForm } from "./LoginForm";
let navigated = "";
vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: (path: string) => {
      navigated = path;
    },
    refresh: () => {},
  }),
}));
afterEach(() => {
  vi.unstubAllGlobals();
  navigated = "";
});
it("submits credentials, clears password and navigates after 204", async () => {
  let body: unknown;
  vi.stubGlobal("fetch", async (_url: string, options: RequestInit) => {
    body = JSON.parse(options.body as string);
    return new Response(null, { status: 204 });
  });
  render(<LoginForm />);
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("Username"), "admin");
  await user.type(screen.getByLabelText("Password"), "secret");
  await user.click(screen.getByRole("button", { name: "Sign in" }));
  await waitFor(() => expect(navigated).toBe("/sites"));
  expect(body).toEqual({ username: "admin", password: "secret" });
  expect(screen.getByLabelText("Password")).toHaveValue("");
});
it("shows stable failure title and clears password on rejection", async () => {
  vi.stubGlobal("fetch", async () =>
    Response.json(
      { title: "Invalid credentials", detail: "secret" },
      { status: 401 },
    ),
  );
  render(<LoginForm />);
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("Username"), "admin");
  await user.type(screen.getByLabelText("Password"), "wrong");
  await user.click(screen.getByRole("button", { name: "Sign in" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Invalid credentials",
  );
  expect(screen.getByLabelText("Password")).toHaveValue("");
  expect(navigated).toBe("");
});
