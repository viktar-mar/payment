"use client";
import { useEffect, useState, type FormEvent } from "react";
import { api, errorTitle } from "../lib/api/client";
import type { components } from "../lib/api/schema";
type Schemas = components["schemas"];
export function CreateSiteForm({
  onCreated,
}: {
  onCreated: (site: Schemas["SiteResponse"]) => void;
}) {
  const [accounts, setAccounts] = useState<Schemas["PaymentAccountDto"][]>([]);
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  const [policy, setPolicy] = useState<Schemas["Policy"]["mode"]>("AUTOMATIC");
  useEffect(() => {
    const controller = new AbortController();
    api<Schemas["PaymentAccountDto"][]>("admin/v1/payment-accounts", {
      signal: controller.signal,
    })
      .then(setAccounts)
      .catch((error) => {
        if (!controller.signal.aborted) setError(errorTitle(error));
      });
    return () => controller.abort();
  }, []);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setError("");
    const origins = [
      ...new Set(
        String(data.get("origins"))
          .split(/\r?\n/)
          .map((value) => value.trim())
          .filter(Boolean),
      ),
    ];
    try {
      if (
        origins.length === 0 ||
        origins.length > 100 ||
        origins.some((origin) => {
          const url = new URL(origin);
          return (
            origin.length > 255 ||
            url.username ||
            url.password ||
            url.search ||
            url.hash ||
            !/^https?:\/\/[^/?#]+$/.test(origin) ||
            (url.protocol !== "https:" &&
              !["localhost", "127.0.0.1", "[::1]"].includes(url.hostname))
          );
        })
      )
        throw new Error();
    } catch {
      setError(
        "Enter valid HTTPS origins without paths (HTTP is allowed for localhost).",
      );
      return;
    }
    const modes = data.getAll(
      "modes",
    ) as Schemas["CreateSiteRequest"]["authentication_modes"];
    const methods =
      policy === "AUTOMATIC"
        ? []
        : (data.getAll("methods") as Schemas["Policy"]["methods"]);
    if (!modes.length || (policy === "ALLOWLIST" && !methods.length)) {
      setError(
        "Choose an authentication mode and at least one allowed payment method.",
      );
      return;
    }
    const body: Schemas["CreateSiteRequest"] = {
      key: String(data.get("key")),
      display_name: String(data.get("name")).trim(),
      payment_account_id: String(data.get("account")),
      allowed_origins: origins,
      authentication_modes: modes,
      payment_method_policy: { mode: policy, methods },
    };
    setPending(true);
    try {
      onCreated(
        await api<Schemas["SiteResponse"]>("admin/v1/sites", {
          method: "POST",
          body,
        }),
      );
      form.reset();
      setPolicy("AUTOMATIC");
    } catch (error) {
      setError(errorTitle(error));
    } finally {
      setPending(false);
    }
  }
  return (
    <form className="surface form-stack" onSubmit={submit}>
      <h2>Create site</h2>
      <label>
        Site key
        <input
          name="key"
          required
          pattern="[a-zA-Z0-9][a-zA-Z0-9\-]{2,62}"
          minLength={3}
          maxLength={63}
        />
      </label>
      <label>
        Display name
        <input name="name" required maxLength={120} />
      </label>
      <label>
        Payment account
        <select name="account" required defaultValue="">
          <option value="">Choose an account</option>
          {accounts
            .filter((account) => account.status === "ACTIVE")
            .map((account) => (
              <option key={account.id} value={account.id}>
                {account.key} · {account.provider} · {account.mode}
              </option>
            ))}
        </select>
      </label>
      {!accounts.length && (
        <p>
          No payment accounts loaded. An active account must be provisioned
          before creating a site.
        </p>
      )}
      <label>
        Allowed origins (one per line)
        <textarea
          name="origins"
          required
          rows={3}
          placeholder="https://shop.example.com"
        />
      </label>
      <fieldset>
        <legend>Authentication modes</legend>
        {(["SITE_TOKEN", "OIDC_TOKEN", "EMAIL_OTP"] as const).map((mode) => (
          <label className="check" key={mode}>
            <input
              type="checkbox"
              name="modes"
              value={mode}
              defaultChecked={mode === "SITE_TOKEN"}
            />
            {mode}
          </label>
        ))}
      </fieldset>
      <label>
        Payment policy
        <select
          value={policy}
          onChange={(event) =>
            setPolicy(event.target.value as Schemas["Policy"]["mode"])
          }
        >
          <option>AUTOMATIC</option>
          <option>ALLOWLIST</option>
        </select>
      </label>
      {policy === "ALLOWLIST" && (
        <fieldset>
          <legend>Allowed payment methods</legend>
          {["CARD", "APPLE_PAY", "GOOGLE_PAY", "PAYPAL"].map((method) => (
            <label className="check" key={method}>
              <input type="checkbox" name="methods" value={method} />
              {method}
            </label>
          ))}
        </fieldset>
      )}
      {error && <p role="alert">{error}</p>}
      <button disabled={pending}>
        {pending ? "Creating…" : "Create site"}
      </button>
    </form>
  );
}
