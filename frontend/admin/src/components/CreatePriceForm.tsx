"use client";
import { useState, type FormEvent } from "react";
import { api, errorTitle } from "../lib/api/client";
import type { components } from "../lib/api/schema";
import { toMinorUnits } from "../lib/money";
import { copy, Language, type Locale } from "./subscriptions/SubscriptionFilters";
type Request = components["schemas"]["CreatePriceRequest"];
export function CreatePriceForm({
  productId,
  onCreated,
}: {
  productId: string;
  onCreated: (price: components["schemas"]["PriceResponse"]) => void;
}) {
  const [type, setType] = useState<Request["type"]>("ONE_TIME");
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  const [trial, setTrial] = useState("0"); const [locale, setLocale] = useState<Locale>("en");
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setError("");
    const currency = String(data.get("currency")).toUpperCase();
    let amount: number;
    try {
      amount = toMinorUnits(String(data.get("amount")), currency);
    } catch (error) {
      setError(error instanceof Error ? error.message : "Invalid amount");
      return;
    }
    const body: Request = {
      price_key: String(data.get("key")),
      type,
      amount_minor: amount,
      currency,
      trial_days: type === "SUBSCRIPTION" ? Number(data.get("trial")) : 0,
      ...(type === "SUBSCRIPTION" && Number(data.get("trial")) > 0 ? { trial_payment_method_policy: data.get("trial_method") as Request["trial_payment_method_policy"] } : {}),
      tax_mode: data.get("tax") as Request["tax_mode"],
      ...(type === "SUBSCRIPTION"
        ? {
            billing_interval: data.get(
              "interval",
            ) as Request["billing_interval"],
          }
        : {}),
      ...(data.get("code") ? { tax_code: String(data.get("code")) } : {}),
    };
    setPending(true);
    try {
      onCreated(
        await api<components["schemas"]["PriceResponse"]>(
          `admin/v1/products/${productId}/prices`,
          { method: "POST", body },
        ),
      );
      form.reset();
      setType("ONE_TIME");
      setTrial("0");
    } catch (error) {
      setError(errorTitle(error));
    } finally {
      setPending(false);
    }
  }
  return (
    <form className="form-stack" onSubmit={submit}>
      <h3>Create draft price</h3>
      <Language locale={locale} onChange={setLocale} />
      <label>
        Price key
        <input
          name="key"
          required
          pattern="[a-zA-Z0-9][a-zA-Z0-9\-]{2,62}"
          minLength={3}
          maxLength={63}
        />
      </label>
      <label>
        Price type
        <select
          value={type}
          onChange={(event) => setType(event.target.value as Request["type"])}
        >
          <option>ONE_TIME</option>
          <option>SUBSCRIPTION</option>
        </select>
      </label>
      <label>
        Amount
        <input
          name="amount"
          required
          inputMode="decimal"
          pattern="[0-9]+(\.[0-9]+)?"
        />
      </label>
      <p className="hint">
        Amounts round half up to the currency’s minor unit.
      </p>
      <label>
        Currency
        <input
          name="currency"
          required
          pattern="[A-Za-z]{3}"
          maxLength={3}
          defaultValue="EUR"
        />
      </label>
      {type === "SUBSCRIPTION" && (
        <>
          <label>
            Billing interval
            <select name="interval">
              <option>MONTH</option>
              <option>YEAR</option>
            </select>
          </label>
          <label>
            Trial days
            <input
              name="trial"
              type="number"
              min={0}
              max={2147483647}
              step={1}
              value={trial}
              onChange={event => setTrial(event.target.value)}
              required
            />
          </label>
          {Number(trial) > 0 && <label>{copy(locale,"Trial payment method","Método de pagamento no teste")}<select name="trial_method" required defaultValue=""><option value="" disabled>{copy(locale,"Choose explicitly","Escolha explicitamente")}</option><option value="REQUIRED_BEFORE_TRIAL">{copy(locale,"Required before trial","Obrigatório antes do teste")}</option><option value="OPTIONAL_DURING_TRIAL">{copy(locale,"Optional during trial","Opcional durante o teste")}</option></select></label>}
        </>
      )}
      <label>
        Tax mode
        <select name="tax">
          <option>NONE</option>
          <option>INCLUSIVE</option>
          <option>EXCLUSIVE</option>
          <option>STRIPE_TAX</option>
        </select>
      </label>
      <label>
        Tax code
        <input name="code" maxLength={64} />
      </label>
      {error && <p role="alert">{error}</p>}
      <button disabled={pending}>
        {pending ? "Creating…" : "Create draft price"}
      </button>
    </form>
  );
}
